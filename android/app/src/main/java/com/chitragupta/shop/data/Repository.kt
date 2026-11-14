package com.chitragupta.shop.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Shop operations the screens use. Role permissions are enforced by the server. */
class ShopRepository(private val api: ApiClient, private val session: SessionStore) {
    suspend fun config(): Config = api.get("/api/config")
    suspend fun turn(req: TurnRequest): TurnResponse = api.post("/api/turn", req)
    suspend fun checkout(cart: List<CartItem>, lang: String): CheckoutResponse = api.post("/api/checkout", CheckoutRequest(cart, lang))
    suspend fun orderStatus(id: String): OrderStatus = api.get("/api/order/$id")
    suspend fun mockPay(id: String) = api.postEmpty("/api/order/$id/mock-pay")
    suspend fun bill(id: String, req: BillRequest = BillRequest()): CheckoutResponse = api.post("/api/order/$id/bill", req)
    suspend fun cash(id: String, req: BillRequest = BillRequest()): OrderResponse = api.post("/api/order/$id/cash", req)
    suspend fun cancel(id: String): OkResponse = api.post("/api/order/$id/cancel", EmptyBody)
    suspend fun deliver(req: DeliverRequest): OkResponse = api.post("/api/deliver", req)

    suspend fun kitchen(): KitchenView = api.get("/api/kitchen")
    suspend fun setOnline(chef: String, online: Boolean): ChefsResponse = api.post("/api/chefs/$chef/online", OnlineRequest(online))
    suspend fun complete(chef: String, req: CompleteRequest = CompleteRequest()): CompleteResponse = api.post("/api/chefs/$chef/complete", req)
    suspend fun freeChef(chef: String): ChefsResponse = api.post("/api/chefs/$chef/free", EmptyBody)
    suspend fun transfer(chef: String, to: String = "any"): OkResponse = api.post("/api/chefs/$chef/transfer", TransferRequest(to))
    suspend fun ready(req: ReadyRequest): ReadyResponse = api.post("/api/ready", req)
    suspend fun lineUnavailable(number: Int, lineId: String): OkResponse = api.post("/api/line/unavailable", UnavailableRequest(number, lineId))
    suspend fun kitchenConfig(req: KitchenConfigRequest): KitchenView = api.post("/api/kitchen/config", req)

    suspend fun insights(): Insights = api.get("/api/insights")
    suspend fun switchShopType(profile: String): Config = api.post<ShopSwitchRequest, Config>("/api/shop", ShopSwitchRequest(profile))
    suspend fun summary(): Summary = api.get("/api/summary")
    suspend fun briefing(): Briefing = api.get("/api/briefing")
    suspend fun copilot(req: CopilotRequest): CopilotResponse = api.post("/api/copilot", req)
    suspend fun shopInfo(): ShopInfo = api.get("/api/shop-info")
    suspend fun soldOut(id: String, v: Boolean): OkResponse = api.post("/api/soldout", SoldOutRequest(id, v))
    suspend fun saveCatalog(items: List<CatalogItem>): MenuResponse = api.post("/api/catalog", CatalogRequest(items))
    suspend fun tts(text: String, lang: String): TtsResponse = api.post("/api/tts", TtsRequest(text, lang))

    /** Live server events for the current role (reconnects automatically). */
    fun events(role: Role): Flow<ServerEvent> = callbackFlow {
        val token = session.current.tokenFor(role)
        val url = session.current.serverUrl + "/api/events" + (token?.let { "?t=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val client = api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        var es: EventSource? = null
        var closed = false
        fun open() {
            es = EventSources.createFactory(client).newEventSource(Request.Builder().url(url).build(), object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    runCatching { AppJson.decodeFromString(ServerEvent.serializer(), data) }.getOrNull()?.let { trySend(it) }
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (closed) return
                    trySend(ServerEvent(type = "disconnected"))
                    Thread { Thread.sleep(2500); if (!closed) open() }.start()
                }
                override fun onOpen(eventSource: EventSource, response: Response) { trySend(ServerEvent(type = "connected")) }
            })
        }
        open()
        awaitClose { closed = true; es?.cancel() }
    }
}

@kotlinx.serialization.Serializable object EmptyBody
