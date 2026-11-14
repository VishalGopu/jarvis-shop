package com.chitragupta.shop.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chitragupta.shop.data.ApiClient
import com.chitragupta.shop.data.ApiException
import com.chitragupta.shop.data.CreateShopRequest
import com.chitragupta.shop.data.JoinRequest
import com.chitragupta.shop.data.ModeInfo
import com.chitragupta.shop.data.Role
import com.chitragupta.shop.data.SessionStore
import com.chitragupta.shop.data.normalizeServerUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data class Ok(val mode: ModeInfo) : ServerCheck
    data class Failed(val message: String) : ServerCheck
}

data class OnboardingState(
    val serverInput: String = "",
    val check: ServerCheck = ServerCheck.Idle,
    val code: String = "",
    val role: Role? = null,
    val pin: String = "",
    val shopType: String? = null,
    val shopName: String = "",
    val newPin: String = "",
    val newPinAgain: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val createdCode: String? = null,
    val createdName: String? = null,
) {
    val mode get() = (check as? ServerCheck.Ok)?.mode
    val codeValid get() = code.trim().length >= 4
    val pinMatches get() = newPin.length in 4..6 && newPin == newPinAgain
}

/** Shared by every onboarding screen. Finishing writes the session, which switches the app to the shell. */
class OnboardingViewModel(private val session: SessionStore, private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState(serverInput = session.current.serverUrl))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    private var checkJob: Job? = null

    fun setServer(v: String) { _state.update { it.copy(serverInput = v, check = ServerCheck.Idle, error = null) } }

    /** Verifies the server answers /api/mode. Calls [onOk] with the detected mode. */
    fun checkServer(onOk: () -> Unit = {}) {
        val base = normalizeServerUrl(_state.value.serverInput)
        if (base == null) { _state.update { it.copy(check = ServerCheck.Failed("That doesn't look like an address. Try 192.168.1.5:3000")) }; return }
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(check = ServerCheck.Checking, serverInput = base) }
            try {
                val mode = api.mode(base)
                session.update { it.copy(serverUrl = base, multi = mode.multi) }
                _state.update { it.copy(check = ServerCheck.Ok(mode)) }
                delay(350)
                onOk()
            } catch (e: ApiException) {
                _state.update { it.copy(check = ServerCheck.Failed(e.message ?: "Can't reach the server")) }
            }
        }
    }

    /** From a scanned QR: either a URL, or JSON like {"url":"http://..","code":"ABC123"}. */
    fun applyScan(payload: String, onOk: () -> Unit) {
        val text = payload.trim()
        var url = text; var code: String? = null
        if (text.startsWith("{")) runCatching {
            val o = com.chitragupta.shop.data.AppJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            code = (o["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        } else if (text.contains("?")) runCatching {
            val u = android.net.Uri.parse(text); code = u.getQueryParameter("code") ?: u.getQueryParameter("c")
            url = text.substringBefore("?")
        }
        _state.update { it.copy(serverInput = url, code = code?.uppercase() ?: it.code) }
        checkServer(onOk)
    }

    fun setCode(v: String) { _state.update { it.copy(code = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(8), error = null) } }
    fun setRole(r: Role) { _state.update { it.copy(role = r, pin = "", error = null) } }
    fun setPin(v: String) { _state.update { it.copy(pin = v.filter(Char::isDigit).take(6), error = null) } }
    fun setType(id: String) { _state.update { it.copy(shopType = id) } }
    fun setName(v: String) { _state.update { it.copy(shopName = v.take(40), error = null) } }
    fun setNewPin(v: String) { _state.update { it.copy(newPin = v.filter(Char::isDigit).take(6), error = null) } }
    fun setNewPinAgain(v: String) { _state.update { it.copy(newPinAgain = v.filter(Char::isDigit).take(6), error = null) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    /** Single-shop mode: no shop code, just pick what this phone does. */
    fun enterSingle(role: Role) {
        session.update { it.copy(role = role, shopName = "", shopCode = "", tokens = emptyMap()) }
    }

    fun join() {
        val s = _state.value; val role = s.role ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val res = api.join(JoinRequest(s.code.trim(), role.wire, if (role == Role.Owner) s.pin else null))
                if (role == Role.Owner) session.ownerUnlocked.value = true
                session.update { it.copy(shopCode = s.code.trim(), shopName = res.shop?.name ?: "", tokens = mapOf(role to res.token), role = role) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = friendly(e)) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun create() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val res = api.createShop(CreateShopRequest(s.shopName.trim(), s.shopType ?: return@launch, s.newPin))
                // Hold the session write until the user has seen the code; the ready screen calls finishCreate().
                pendingToken = res.token; pendingCode = res.shop.code; pendingName = res.shop.name
                _state.update { it.copy(createdCode = res.shop.code, createdName = res.shop.name) }
            } catch (e: ApiException) {
                _state.update { it.copy(error = friendly(e)) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }

    private var pendingToken: String? = null
    private var pendingCode = ""
    private var pendingName = ""

    fun finishCreate() {
        val t = pendingToken ?: return
        session.ownerUnlocked.value = true
        session.update { it.copy(shopCode = pendingCode, shopName = pendingName, tokens = mapOf(Role.Owner to t), role = Role.Owner) }
    }

    private fun friendly(e: ApiException) = when (e) {
        is ApiException.LockedOut -> "Too many wrong attempts. Wait a few minutes and try again."
        is ApiException.Unreachable -> e.message ?: "Can't reach the server."
        is ApiException.Http -> when (e.message) {
            "unknown shop code" -> "That shop code doesn't exist. Check it and try again."
            "wrong PIN" -> "Wrong PIN. Try again."
            else -> e.message ?: "Something went wrong."
        }
        else -> e.message ?: "Something went wrong."
    }
}
