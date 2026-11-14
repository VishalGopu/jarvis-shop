package com.jarvis.shop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiException(message: String) : IOException(message) {
    class Unreachable(msg: String = "Can't reach the server. Is your phone on the same Wi-Fi?") : ApiException(msg)
    class Http(val code: Int, msg: String) : ApiException(msg)
    class Unauthorized(msg: String) : ApiException(msg)
    class LockedOut(msg: String) : ApiException(msg)
}

val AppJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }

/** Turn what the user typed ("192.168.1.5:3000") into a clean base URL, or null if it can't be one. */
fun normalizeServerUrl(raw: String): String? {
    var s = raw.trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    return s.toHttpUrlOrNull()?.let { it.toString().trimEnd('/') }
}

class ApiClient(private val session: SessionStore, val http: OkHttpClient = defaultClient()) {
    @PublishedApi internal val jsonType = "application/json".toMediaType()
    @PublishedApi internal val json = AppJson

    val baseUrl get() = session.current.serverUrl

    /** Set by the app: re-authenticates the current role after a 401. Return true if a new token was stored. */
    @Volatile var refresh: (suspend (Role) -> Boolean)? = null

    private fun token() = session.current.role?.let { session.current.tokenFor(it) }

    fun request(path: String, base: String = baseUrl): Request.Builder {
        val b = Request.Builder().url(base + path)
        token()?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    /** Runs the request; on 401 asks [refresh] for a new token and retries once. */
    suspend fun execute(build: () -> Request): String =
        try { exec(build()) } catch (e: ApiException.Unauthorized) {
            val r = session.current.role
            if (r != null && refresh?.invoke(r) == true) exec(build()) else throw e
        }

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body.string()
                if (r.isSuccessful) return@use text
                val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?: "Server error (${r.code})"
                when (r.code) {
                    401 -> throw ApiException.Unauthorized(msg)
                    429 -> throw ApiException.LockedOut(msg)
                    else -> throw ApiException.Http(r.code, msg)
                }
            }
        } catch (e: ApiException) { throw e } catch (e: IOException) { throw ApiException.Unreachable() }
    }

    suspend inline fun <reified R> get(path: String): R =
        json.decodeFromString(serializer<R>(), execute { request(path).get().build() })

    suspend inline fun <reified B, reified R> post(path: String, body: B): R {
        val payload = json.encodeToString(serializer<B>(), body)
        return json.decodeFromString(serializer<R>(), execute { request(path).post(payload.toRequestBody(jsonType)).build() })
    }

    suspend fun postEmpty(path: String) { execute { request(path).post("{}".toRequestBody(jsonType)).build() } }

    // ---- gateway (no auth)
    suspend fun mode(base: String): ModeInfo {
        return AppJson.decodeFromString(serializer(), exec(Request.Builder().url("$base/api/mode").get().build()))
    }
    suspend fun join(req: JoinRequest): JoinResponse = post("/api/join", req)
    suspend fun createShop(req: CreateShopRequest): CreateShopResponse = post("/api/shops", req)

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    }
}
