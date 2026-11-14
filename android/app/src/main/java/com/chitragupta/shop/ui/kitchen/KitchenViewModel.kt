package com.chitragupta.shop.ui.kitchen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.ApiException
import com.chitragupta.shop.data.CompleteRequest
import com.chitragupta.shop.data.KitchenView
import com.chitragupta.shop.data.ReadyRequest
import com.chitragupta.shop.data.Role
import com.chitragupta.shop.ui.components.MicState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val ALL_ORDERS = "_all"

data class KitchenState(
    val kitchen: KitchenView? = null,
    val me: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val connected: Boolean = true,
    val mic: MicState = MicState.Idle,
    val heard: String? = null,
    val taskTick: Int = 0,
    val doneTick: Int = 0,
)

class KitchenViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.repo
    private val prefs = graph.appContext.getSharedPreferences("kitchen", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(KitchenState(me = prefs.getString("me_${graph.session.current.shopCode}", null)))
    val state: StateFlow<KitchenState> = _state.asStateFlow()
    private var eventsJob: Job? = null
    private var recorder: com.chitragupta.shop.audio.Recorder? = null

    fun start() {
        if (eventsJob?.isActive == true) return
        refresh()
        eventsJob = viewModelScope.launch {
            repo.events(Role.Kitchen).collect { e ->
                when (e.type) {
                    "connected" -> _state.update { it.copy(connected = true) }
                    "disconnected" -> _state.update { it.copy(connected = false) }
                    "task" -> if (e.chefId == _state.value.me) {
                        _state.update { it.copy(taskTick = it.taskTick + 1) }
                        e.text?.let { t -> runCatching { repo.tts(t, e.lang ?: "en-IN").audio }.getOrNull()?.let(graph.speaker::play) }
                        refresh()
                    } else refresh()
                    "upcoming", "reminder" -> {   // phone order booked for later / time to start it: spoken on every kitchen device
                        e.text?.let { t -> runCatching { repo.tts(t, e.lang ?: "en-IN").audio }.getOrNull()?.let(graph.speaker::play) }
                        refresh()
                    }
                    "update", "order", "menu" -> refresh()
                }
            }
        }
    }

    fun stop() { eventsJob?.cancel(); eventsJob = null }

    fun refresh() {
        viewModelScope.launch {
            try {
                val k = repo.kitchen()
                _state.update { it.copy(kitchen = k, loading = false, error = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun chooseMe(id: String?) {
        prefs.edit().apply { if (id == null) remove("me_${graph.session.current.shopCode}") else putString("me_${graph.session.current.shopCode}", id) }.apply()
        _state.update { it.copy(me = id) }
    }

    fun setOnline(online: Boolean) {
        val me = _state.value.me ?: return
        viewModelScope.launch {
            runCatching { repo.setOnline(me, online) }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun done() {
        val me = _state.value.me ?: return
        viewModelScope.launch {
            try { repo.complete(me); _state.update { it.copy(doneTick = it.doneTick + 1) } }
            catch (e: ApiException) { _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun transfer() {
        val me = _state.value.me ?: return
        viewModelScope.launch {
            try { repo.transfer(me, "any") } catch (e: ApiException) { _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun orderReady(number: Int) {
        viewModelScope.launch {
            try {
                val r = repo.ready(ReadyRequest(number = number))
                _state.update { it.copy(doneTick = it.doneTick + 1) }
                r.announcements.firstOrNull()?.let { a -> runCatching { repo.tts(a.text, a.lang).audio }.getOrNull()?.let(graph.speaker::play) }
            } catch (e: ApiException) { _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun lineReady(number: Int, lineId: String) {
        viewModelScope.launch {
            runCatching { repo.ready(ReadyRequest(number = number, lineId = lineId)) }
                .onSuccess { _state.update { it.copy(doneTick = it.doneTick + 1) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun unavailable(number: Int, lineId: String) {
        viewModelScope.launch {
            runCatching { repo.lineUnavailable(number, lineId) }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    /** "Say done": records a short clip and lets the server decide if it was a done phrase. */
    fun micTapped(hasPermission: Boolean, requestPermission: () -> Unit) {
        val me = _state.value.me ?: return
        when (_state.value.mic) {
            MicState.Processing -> return
            MicState.Listening -> {
                val r = recorder ?: return; recorder = null
                _state.update { it.copy(mic = MicState.Processing) }
                viewModelScope.launch {
                    val audio = r.stop()
                    if (audio == null) { _state.update { it.copy(mic = MicState.Idle) }; return@launch }
                    try {
                        val res = repo.complete(me, CompleteRequest(audio = audio))
                        _state.update { it.copy(heard = res.heard, doneTick = if (res.completed) it.doneTick + 1 else it.doneTick) }
                    } catch (e: ApiException) { _state.update { it.copy(error = e.message) } }
                    _state.update { it.copy(mic = MicState.Idle) }
                    refresh()
                }
            }
            MicState.Idle -> {
                if (!hasPermission) { requestPermission(); return }
                val r = graph.newRecorder().also { recorder = it }
                if (r.start(viewModelScope)) _state.update { it.copy(mic = MicState.Listening, heard = null) }
            }
        }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }
}
