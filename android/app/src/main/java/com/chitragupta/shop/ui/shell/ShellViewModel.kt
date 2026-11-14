package com.chitragupta.shop.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.ApiException
import com.chitragupta.shop.data.Config
import com.chitragupta.shop.data.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShellState(
    val config: Config? = null,
    val configError: String? = null,
    val switching: Boolean = false,
    val askOwnerPin: Boolean = false,
    val pinError: String? = null,
    val message: String? = null,
)

/** Owns "which hat is this phone wearing right now" and the shop config every role needs. */
class ShellViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    init { loadConfig() }

    fun loadConfig() {
        viewModelScope.launch {
            try {
                val s = graph.session.current
                s.role?.let { graph.auth.ensure(it) }
                _state.update { it.copy(config = graph.repo.config(), configError = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(configError = e.message) }
            }
        }
    }

    fun switchTo(role: Role) {
        val s = graph.session.current
        if (role == s.role) return
        if (role == Role.Owner && s.multi && !graph.session.ownerUnlocked.value) {
            _state.update { it.copy(askOwnerPin = true, pinError = null) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(switching = true) }
            try {
                graph.auth.ensure(role)
                graph.session.update { it.copy(role = role) }
                loadConfig()
            } catch (e: ApiException) {
                _state.update { it.copy(message = e.message) }
            } finally { _state.update { it.copy(switching = false) } }
        }
    }

    fun submitOwnerPin(pin: String) {
        viewModelScope.launch {
            _state.update { it.copy(switching = true, pinError = null) }
            try {
                graph.auth.ensure(Role.Owner, pin)
                graph.session.update { it.copy(role = Role.Owner) }
                _state.update { it.copy(askOwnerPin = false) }
                loadConfig()
            } catch (e: ApiException) {
                _state.update { it.copy(pinError = if (e is ApiException.LockedOut) "Too many tries. Wait a few minutes." else if (e is ApiException.Unreachable) e.message else "Wrong PIN") }
            } finally { _state.update { it.copy(switching = false) } }
        }
    }

    fun dismissPin() { _state.update { it.copy(askOwnerPin = false, pinError = null) } }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    fun signOut() { graph.speaker.stop(); graph.session.signOut() }
}
