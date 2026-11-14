package com.chitragupta.shop.ui.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.ApiException
import com.chitragupta.shop.data.Briefing
import com.chitragupta.shop.data.CatalogItem
import com.chitragupta.shop.data.Chef
import com.chitragupta.shop.data.ChefConfig
import com.chitragupta.shop.data.CopilotRequest
import com.chitragupta.shop.data.Facts
import com.chitragupta.shop.data.KitchenConfigRequest
import com.chitragupta.shop.data.KitchenView
import com.chitragupta.shop.data.MenuItem
import com.chitragupta.shop.data.ShopInfo
import com.chitragupta.shop.ui.components.MicState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OwnerState(
    val facts: Facts? = null,
    val briefing: Briefing? = null,
    val todayLoading: Boolean = true,
    val todayError: String? = null,
    val copilotQ: String = "",
    val copilotAnswer: String? = null,
    val copilotHeard: String? = null,
    val copilotBusy: Boolean = false,
    val mic: MicState = MicState.Idle,
    val menu: List<MenuItem> = emptyList(),
    val team: KitchenView? = null,
    val shop: ShopInfo? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val savedTick: Int = 0,
)

class OwnerViewModel(private val graph: AppGraph) : ViewModel() {
    private val repo = graph.repo
    private val _state = MutableStateFlow(OwnerState())
    val state: StateFlow<OwnerState> = _state.asStateFlow()
    private var recorder: com.chitragupta.shop.audio.Recorder? = null

    private inline fun guard(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: ApiException) { _state.update { it.copy(error = e.message, saving = false, todayLoading = false) } }
        }
    }

    fun loadToday() = guard {
        _state.update { it.copy(todayLoading = true, todayError = null) }
        val ins = repo.insights()
        val b = runCatching { repo.briefing() }.getOrNull()
        _state.update { it.copy(facts = ins.facts, briefing = b, todayLoading = false) }
    }

    fun loadMenu(initial: List<MenuItem>) { _state.update { it.copy(menu = initial) } }
    fun reloadMenu() = guard { _state.update { it.copy(menu = repo.config().menu.items) } }
    fun loadTeam() = guard { _state.update { it.copy(team = repo.kitchen()) } }
    fun loadShop() = guard { _state.update { it.copy(shop = repo.shopInfo()) } }

    // ---- copilot
    fun setQuestion(q: String) { _state.update { it.copy(copilotQ = q) } }

    fun ask(text: String? = null) {
        val q = (text ?: _state.value.copilotQ).trim()
        if (q.isEmpty()) return
        guard {
            _state.update { it.copy(copilotBusy = true, copilotQ = "", error = null) }
            val r = repo.copilot(CopilotRequest(text = q))
            _state.update { it.copy(copilotAnswer = r.answer, copilotHeard = q, copilotBusy = false) }
            graph.speaker.play(r.audio)
        }
    }

    fun micTapped(hasPermission: Boolean, requestPermission: () -> Unit) {
        when (_state.value.mic) {
            MicState.Processing -> return
            MicState.Listening -> {
                val r = recorder ?: return; recorder = null
                _state.update { it.copy(mic = MicState.Processing) }
                viewModelScope.launch {
                    val audio = r.stop()
                    if (audio == null) { _state.update { it.copy(mic = MicState.Idle) }; return@launch }
                    try {
                        val res = repo.copilot(CopilotRequest(audio = audio))
                        _state.update { it.copy(copilotAnswer = res.answer, copilotHeard = res.heard) }
                        graph.speaker.play(res.audio)
                    } catch (e: ApiException) { _state.update { it.copy(error = e.message) } }
                    _state.update { it.copy(mic = MicState.Idle) }
                }
            }
            MicState.Idle -> {
                if (!hasPermission) { requestPermission(); return }
                graph.speaker.stop()
                val r = graph.newRecorder().also { recorder = it }
                if (r.start(viewModelScope)) _state.update { it.copy(mic = MicState.Listening, error = null) }
            }
        }
    }

    // ---- menu
    fun toggleSoldOut(item: MenuItem, v: Boolean) {
        _state.update { s -> s.copy(menu = s.menu.map { if (it.id == item.id) it.copy(soldOut = v) else it }) }
        guard { repo.soldOut(item.id, v) }
    }


    fun saveItem(edited: MenuItem, isNew: Boolean, onConfigChanged: () -> Unit) {
        val list = _state.value.menu
        val items = if (isNew) list.map { catalog(it) } + CatalogItem(null, edited.name, edited.price, edited.unit, edited.step, edited.stock, edited.aliases)
        else list.map { if (it.id == edited.id) catalog(edited) else catalog(it) }
        saveCatalog(items, onConfigChanged)
    }

    fun deleteItem(item: MenuItem, onConfigChanged: () -> Unit) = saveCatalog(_state.value.menu.filter { it.id != item.id }.map(::catalog), onConfigChanged)

    private fun catalog(m: MenuItem) = CatalogItem(m.id, m.name, m.price, m.unit, m.step, m.stock, m.aliases)

    private fun saveCatalog(items: List<CatalogItem>, onConfigChanged: () -> Unit) = guard {
        _state.update { it.copy(saving = true, error = null) }
        val r = repo.saveCatalog(items)
        _state.update { it.copy(menu = r.menu.items, saving = false, savedTick = it.savedTick + 1) }
        onConfigChanged()
    }

    // ---- team
    private fun cfg(c: Chef) = ChefConfig(c.id, c.name, c.lang, c.skills, c.also, c.helpAny)

    fun saveChef(chef: ChefConfig) = guard {
        val team = _state.value.team ?: repo.kitchen()
        val all = team.chefs.map { if (it.id == chef.id) chef else cfg(it) }.let { if (chef.id == null) it + chef else it }
        _state.update { it.copy(saving = true) }
        _state.update { it.copy(team = repo.kitchenConfig(KitchenConfigRequest(chefs = all)).let { v -> (it.team ?: team).copy(chefs = v.chefs, mode = v.mode) }, saving = false, savedTick = it.savedTick + 1) }
    }

    fun deleteChef(id: String) = guard {
        val team = _state.value.team ?: return@guard
        val v = repo.kitchenConfig(KitchenConfigRequest(chefs = team.chefs.filter { it.id != id }.map(::cfg)))
        _state.update { it.copy(team = team.copy(chefs = v.chefs, mode = v.mode)) }
    }

    fun setMode(mode: String) = guard {
        val v = repo.kitchenConfig(KitchenConfigRequest(mode = mode))
        _state.update { it.copy(team = (it.team ?: v).copy(mode = v.mode, chefs = v.chefs)) }
    }

    fun setOnline(id: String, online: Boolean) = guard { repo.setOnline(id, online); loadTeam() }

    // ---- shop
    fun switchType(profile: String, onConfigChanged: () -> Unit) = guard {
        _state.update { it.copy(saving = true) }
        repo.switchShopType(profile)
        _state.update { it.copy(saving = false, savedTick = it.savedTick + 1) }
        reloadMenu(); loadShop(); onConfigChanged()
    }

    fun dismissError() { _state.update { it.copy(error = null) } }
}
