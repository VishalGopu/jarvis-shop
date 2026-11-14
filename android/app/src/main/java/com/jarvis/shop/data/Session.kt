package com.jarvis.shop.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything that survives app restarts: server, shop code, one token per role, last role. */
data class SessionState(
    val serverUrl: String = "",
    val multi: Boolean = false,
    val shopName: String = "",
    val shopCode: String = "",
    val tokens: Map<Role, String> = emptyMap(),
    val role: Role? = null,
) {
    /** Ready to open the app: server known and (single mode, or a shop was joined). */
    val signedIn get() = serverUrl.isNotBlank() && role != null && (!multi || shopCode.isNotBlank())
    fun tokenFor(r: Role) = tokens[r]
}

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val current get() = _state.value

    /** Owner PIN entered during this app run. Deliberately not persisted: the owner view re-asks after a restart. */
    val ownerUnlocked = MutableStateFlow(false)

    private fun read() = SessionState(
        serverUrl = prefs.getString("server", "") ?: "",
        multi = prefs.getBoolean("multi", false),
        shopName = prefs.getString("shopName", "") ?: "",
        shopCode = prefs.getString("shopCode", "") ?: "",
        tokens = Role.entries.mapNotNull { r -> prefs.getString("token_${r.wire}", null)?.let { r to it } }.toMap(),
        role = Role.from(prefs.getString("role", null)),
    )

    fun update(block: (SessionState) -> SessionState) {
        val s = block(_state.value)
        prefs.edit().apply {
            putString("server", s.serverUrl); putBoolean("multi", s.multi)
            putString("shopName", s.shopName); putString("shopCode", s.shopCode)
            putString("role", s.role?.wire)
            Role.entries.forEach { r -> s.tokens[r]?.let { putString("token_${r.wire}", it) } ?: remove("token_${r.wire}") }
        }.apply()
        _state.value = s
    }

    /** Forget the shop and tokens but keep the server address for the next sign-in. */
    fun signOut() { ownerUnlocked.value = false; update { SessionState(serverUrl = it.serverUrl, multi = it.multi) } }
}
