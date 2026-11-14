package com.jarvis.shop.ui.counter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.shop.AppGraph
import com.jarvis.shop.audio.Recorder
import com.jarvis.shop.data.ApiException
import com.jarvis.shop.data.CartItem
import com.jarvis.shop.data.Config
import com.jarvis.shop.data.DeliverRequest
import com.jarvis.shop.data.Line
import com.jarvis.shop.data.MenuItem
import com.jarvis.shop.data.Order
import com.jarvis.shop.data.Role
import com.jarvis.shop.data.TurnRequest
import com.jarvis.shop.ui.components.MicState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

sealed interface Sheet {
    /** Customer pays first (or a ready order is being billed): show the QR and wait for the server to confirm. */
    data class Pay(val order: Order, val qr: String?, val paid: Boolean = false, val pin: String? = null, val busy: Boolean = false, val error: String? = null) : Sheet
    /** Pay-later shops: the order went straight to the team. */
    data class Sent(val order: Order) : Sheet
    /** An order that is ready and needs billing and/or handing over. */
    data class Handover(val order: Order, val busy: Boolean = false, val error: String? = null) : Sheet
}

data class CounterState(
    val lines: List<Line> = emptyList(),
    val mic: MicState = MicState.Idle,
    val heard: String? = null,
    val reply: String? = null,
    val lang: String = "en-IN",
    val error: String? = null,
    val sheet: Sheet? = null,
    val readyOrders: List<Order> = emptyList(),
    val connected: Boolean = true,
    val checkingOut: Boolean = false,
    val successTick: Int = 0,
    val textMode: Boolean = false,
) {
    val total get() = lines.sumOf { it.amount }
    val count get() = lines.size
}

class CounterViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.repo
    private val _state = MutableStateFlow(CounterState())
    val state: StateFlow<CounterState> = _state.asStateFlow()
    private var recorder: Recorder? = null
    private var menu: Map<String, MenuItem> = emptyMap()
    private var payJob: Job? = null
    private var eventsJob: Job? = null

    fun setConfig(cfg: Config) { menu = cfg.menu.items.associateBy { it.id } }

    /** Start listening to live events and load ready orders. Safe to call repeatedly. */
    fun start() {
        if (eventsJob?.isActive == true) return
        refreshReady()
        eventsJob = viewModelScope.launch {
            repo.events(Role.Counter).collect { e ->
                when (e.type) {
                    "connected" -> _state.update { it.copy(connected = true) }
                    "disconnected" -> _state.update { it.copy(connected = false) }
                    "ready" -> { refreshReady(); e.text?.let { speak(it, e.lang ?: "en-IN") } }
                    "update", "order" -> refreshReady()
                    "menu" -> runCatching { setConfig(repo.config()) }
                }
            }
        }
    }

    fun stopListening() { eventsJob?.cancel(); eventsJob = null }

    private fun cart() = _state.value.lines.map { CartItem(it.id, it.qty, it.note) }

    private fun refreshReady() {
        viewModelScope.launch {
            runCatching { repo.kitchen() }.onSuccess { k ->
                _state.update { it.copy(readyOrders = k.orders.filter { o -> o.status == "ready" }) }
            }
        }
    }

    private fun speak(text: String, lang: String) {
        viewModelScope.launch { runCatching { repo.tts(text, lang).audio }.getOrNull()?.let(graph.speaker::play) }
    }

    // ---- voice / text turns
    fun micTapped(hasPermission: Boolean, requestPermission: () -> Unit) {
        when (_state.value.mic) {
            MicState.Processing -> return
            MicState.Listening -> finishRecording()
            MicState.Idle -> {
                if (!hasPermission) { requestPermission(); return }
                graph.speaker.stop()
                val r = graph.newRecorder().also { recorder = it }
                if (r.start(viewModelScope)) _state.update { it.copy(mic = MicState.Listening, error = null) }
                else _state.update { it.copy(error = "Couldn't start the microphone.") }
            }
        }
    }

    private fun finishRecording() {
        val r = recorder ?: return
        recorder = null
        _state.update { it.copy(mic = MicState.Processing) }
        viewModelScope.launch {
            val audio = r.stop()
            if (audio == null) { _state.update { it.copy(mic = MicState.Idle, error = "That was too short. Tap, speak, then tap again.") }; return@launch }
            turn(TurnRequest(audio = audio, cart = cart(), lang = _state.value.lang))
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(mic = MicState.Processing, error = null) }
        viewModelScope.launch { turn(TurnRequest(text = text.trim(), cart = cart(), lang = _state.value.lang)) }
    }

    private suspend fun turn(req: TurnRequest) {
        try {
            val r = repo.turn(req)
            _state.update {
                it.copy(mic = MicState.Idle, heard = r.heard.ifBlank { null }, reply = r.reply.ifBlank { null }, lang = r.lang,
                    lines = if (r.action == "clear") emptyList() else r.lines, error = if (r.limited) r.reply else null)
            }
            graph.speaker.play(r.audio)
            if (r.action == "checkout" && r.lines.isNotEmpty()) checkout()
        } catch (e: ApiException) {
            _state.update { it.copy(mic = MicState.Idle, error = e.message) }
        }
    }

    fun toggleTextMode() { _state.update { it.copy(textMode = !it.textMode) } }
    fun dismissError() { _state.update { it.copy(error = null) } }

    // ---- cart edits (local: prices come from the menu the server sent)
    fun add(item: MenuItem) = change(item.id, item.step, item)
    fun inc(line: Line) = change(line.id, menu[line.id]?.step ?: 1.0)
    fun dec(line: Line) = change(line.id, -(menu[line.id]?.step ?: 1.0))

    private fun change(id: String, delta: Double, item: MenuItem? = menu[id]) {
        val m = item ?: return
        _state.update { s ->
            val old = s.lines.firstOrNull { it.id == id }
            val q = round(((old?.qty ?: 0.0) + delta) * 100) / 100
            val rest = s.lines.filterNot { it.id == id }
            if (q <= 0) s.copy(lines = rest, reply = null)
            else {
                val line = (old ?: Line(id, m.name, 0.0, m.price, m.unit)).copy(qty = q, amount = round(q * m.price).toInt())
                s.copy(lines = if (old == null) rest + line else s.lines.map { if (it.id == id) line else it }, reply = null)
            }
        }
    }

    fun remove(line: Line) { _state.update { s -> s.copy(lines = s.lines.filterNot { it.id == line.id }) } }
    fun clear() { _state.update { it.copy(lines = emptyList(), reply = null, heard = null) } }

    // ---- checkout & payment
    fun checkout() {
        val s = _state.value
        if (s.lines.isEmpty() || s.checkingOut) return
        viewModelScope.launch {
            _state.update { it.copy(checkingOut = true, error = null) }
            try {
                val r = repo.checkout(cart(), s.lang)
                if (r.payLater) {
                    _state.update { it.copy(sheet = Sheet.Sent(r.order), lines = emptyList(), reply = null, heard = null, successTick = it.successTick + 1) }
                } else {
                    _state.update { it.copy(sheet = Sheet.Pay(r.order, r.qr)) }
                    watchPayment(r.order.id)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.message) }
            } finally { _state.update { it.copy(checkingOut = false) } }
        }
    }

    /** Polls the server (which asks the payment provider). The client never decides that an order is paid. */
    private fun watchPayment(orderId: String) {
        payJob?.cancel()
        payJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val st = runCatching { repo.orderStatus(orderId) }.getOrNull() ?: continue
                val cur = _state.value.sheet as? Sheet.Pay ?: return@launch
                if (cur.paid) {   // already paid: keep the result sheet live until the kitchen has it ready
                    _state.update { it.copy(sheet = cur.copy(order = st.order)) }
                    if (st.order.status == "ready" || st.order.status == "delivered") return@launch
                    continue
                }
                if (st.order.paidAt != null) {
                    _state.update { it.copy(sheet = cur.copy(order = st.order, paid = true, pin = st.pin),
                        lines = if (cur.order.id == orderId && !cur.order.status.equals("ready")) emptyList() else it.lines, successTick = it.successTick + 1) }
                    graph.speaker.play(st.announce?.audio)
                    refreshReady()
                    continue
                }
                if (st.order.status == "cancelled") { _state.update { it.copy(sheet = null) }; return@launch }
            }
        }
    }

    fun mockPay() {
        val p = _state.value.sheet as? Sheet.Pay ?: return
        viewModelScope.launch { runCatching { repo.mockPay(p.order.id) } }
    }

    /** Cancel the unpaid order. [keepCart] lets the user edit and pay again. */
    fun cancelPayment() {
        val p = _state.value.sheet as? Sheet.Pay ?: return
        payJob?.cancel()
        viewModelScope.launch {
            runCatching { repo.cancel(p.order.id) }
            _state.update { it.copy(sheet = null) }
        }
    }

    fun closeSheet() {
        payJob?.cancel()
        val s = _state.value.sheet
        _state.update { it.copy(sheet = null) }
        if (s is Sheet.Pay && !s.paid) viewModelScope.launch { runCatching { repo.cancel(s.order.id) } }
        refreshReady()
    }

    // ---- ready orders: bill (pay-after shops) and hand over
    fun openHandover(o: Order) { _state.update { it.copy(sheet = Sheet.Handover(o)) } }

    fun billWithQr() {
        val h = _state.value.sheet as? Sheet.Handover ?: return
        viewModelScope.launch {
            try {
                val r = repo.bill(h.order.id)
                _state.update { it.copy(sheet = Sheet.Pay(r.order, r.qr)) }
                watchPayment(r.order.id)
            } catch (e: ApiException) { _state.update { it.copy(sheet = h.copy(error = e.message)) } }
        }
    }

    fun billWithCash() {
        val h = _state.value.sheet as? Sheet.Handover ?: return
        viewModelScope.launch {
            try {
                val r = repo.cash(h.order.id)
                _state.update { it.copy(sheet = h.copy(order = r.order, error = null), successTick = it.successTick + 1) }
                refreshReady()
            } catch (e: ApiException) { _state.update { it.copy(sheet = h.copy(error = e.message)) } }
        }
    }

    fun deliver(pin: String?, override: Boolean) {
        val h = _state.value.sheet as? Sheet.Handover ?: return
        viewModelScope.launch {
            _state.update { it.copy(sheet = h.copy(busy = true, error = null)) }
            try {
                repo.deliver(DeliverRequest(h.order.number, pin, override))
                _state.update { it.copy(sheet = null, successTick = it.successTick + 1) }
                refreshReady()
            } catch (e: ApiException) {
                _state.update { it.copy(sheet = h.copy(busy = false, error = if (e.message == "wrong pickup code") "Wrong pickup code" else e.message)) }
            }
        }
    }

    override fun onCleared() { recorder?.let { r -> viewModelScope.launch { r.stop() } } }
}
