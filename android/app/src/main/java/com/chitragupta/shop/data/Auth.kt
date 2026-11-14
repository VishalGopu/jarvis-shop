package com.chitragupta.shop.data

/** Gets and refreshes per-role tokens so the user can hop between Counter, Kitchen and Owner without re-pairing. */
class AuthManager(private val api: ApiClient, private val session: SessionStore) {
    /** Counter/Kitchen only need the shop code. Owner needs the PIN. In single-shop mode there is nothing to do. */
    suspend fun ensure(role: Role, pin: String? = null) {
        val s = session.current
        if (!s.multi) return
        if (role == Role.Owner && pin == null) return   // owner access only ever comes from a PIN entry
        if (role != Role.Owner && s.tokenFor(role) != null) return
        val res = api.join(JoinRequest(s.shopCode, role.wire, pin))
        session.update { it.copy(tokens = it.tokens + (role to res.token), shopName = res.shop?.name ?: it.shopName) }
        if (role == Role.Owner) session.ownerUnlocked.value = true
    }

    /** Called by [ApiClient] after a 401. Counter/Kitchen silently re-join; Owner must re-enter the PIN. */
    suspend fun refresh(role: Role): Boolean {
        if (!session.current.multi) return false
        if (role == Role.Owner) { session.ownerUnlocked.value = false; session.update { it.copy(tokens = it.tokens - Role.Owner) }; return false }
        return runCatching {
            session.update { it.copy(tokens = it.tokens - role) }
            ensure(role); true
        }.getOrDefault(false)
    }
}
