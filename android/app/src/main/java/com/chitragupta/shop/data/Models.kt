package com.chitragupta.shop.data

import kotlinx.serialization.Serializable

enum class Role(val wire: String, val label: String) {
    Counter("counter", "Counter"), Kitchen("kitchen", "Kitchen"), Owner("owner", "Owner");

    companion object { fun from(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

// ---- gateway / auth
@Serializable data class ShopType(val id: String, val label: String)
@Serializable data class ModeInfo(val multi: Boolean = false, val types: List<ShopType> = emptyList())
@Serializable data class JoinRequest(val code: String, val role: String, val pin: String? = null)
@Serializable data class ShopRef(val name: String = "", val profile: String = "")
@Serializable data class JoinResponse(val token: String, val role: String, val shop: ShopRef? = null)
@Serializable data class CreateShopRequest(val name: String, val profile: String, val pin: String)
@Serializable data class PublicShop(val id: String = "", val name: String = "", val profile: String = "", val code: String = "")
@Serializable data class CreateShopResponse(val shop: PublicShop, val token: String, val role: String = "owner")

// ---- config / menu
@Serializable data class Words(
    val staff: String = "Chef", val staffPl: String = "Chefs", val station: String = "Kitchen",
    val working: String = "cooking", val setup: String = "Kitchen team",
)
@Serializable data class Profile(
    val id: String = "", val kindLabel: String = "shop", val fulfil: String = "cook", val payWhen: String = "before",
    val table: Boolean = false, val words: Words = Words(), val shops: List<ShopType> = emptyList(),
) { val payAfter get() = payWhen == "after" }

@Serializable data class MenuItem(
    val id: String, val name: String, val price: Int = 0, val unit: String = "piece", val step: Double = 1.0,
    val aliases: List<String> = emptyList(), val stock: Double? = null, val soldOut: Boolean = false,
)
@Serializable data class Menu(val shop: String = "", val items: List<MenuItem> = emptyList())
@Serializable data class Config(val menu: Menu = Menu(), val profile: Profile = Profile(), val sarvam: Boolean = false, val mock: Boolean = true)

// ---- counter
@Serializable data class CartItem(val id: String, val qty: Double, val note: String? = null)
@Serializable data class Line(
    val id: String, val name: String, val qty: Double, val price: Int = 0, val unit: String = "piece",
    val amount: Int = 0, val note: String? = null, val unavailable: Boolean = false,
)
@Serializable data class TurnRequest(
    val text: String? = null, val audio: String? = null, val cart: List<CartItem> = emptyList(),
    val lang: String? = null, val add: AddItem? = null,
)
@Serializable data class AddItem(val id: String, val qty: Double)
@Serializable data class TurnResponse(
    val heard: String = "", val reply: String = "", val lang: String = "en-IN", val cart: List<CartItem> = emptyList(),
    val lines: List<Line> = emptyList(), val total: Int = 0, val audio: String? = null, val action: String = "none", val limited: Boolean = false,
)
@Serializable data class CheckoutRequest(val cart: List<CartItem>, val lang: String, val table: String? = null)
@Serializable data class Order(
    val id: String, val number: Int, val lines: List<Line> = emptyList(), val total: Int = 0, val status: String = "",
    val paidAt: Long? = null, val lang: String = "en-IN", val table: String? = null, val method: String? = null,
    val source: String? = null, val payLater: Boolean = false, val pickupAt: Long? = null, val startAt: Long? = null,
) { val isPhone get() = source == "phone" }
@Serializable data class CheckoutResponse(val order: Order, val qr: String? = null, val payLater: Boolean = false, val pin: String? = null)
@Serializable data class BillRequest(val pin: String? = null, val override: Boolean = false)
@Serializable data class Announce(val text: String = "", val audio: String? = null)
@Serializable data class OrderStatus(val order: Order, val announce: Announce? = null, val pin: String? = null)
@Serializable data class OkResponse(val ok: Boolean = false, val status: String? = null)
@Serializable data class OrderResponse(val order: Order)
@Serializable data class DeliverRequest(val number: Int, val pin: String? = null, val override: Boolean = false)

// ---- kitchen
@Serializable data class KLine(
    val id: String, val name: String, val qty: Double = 0.0, val total: Double = 0.0, val done: Double = 0.0,
    val unit: String = "piece", val note: String? = null, val unavailable: Boolean = false,
)
@Serializable data class ChefTask(val number: Int, val since: Long = 0, val table: String? = null, val items: List<KLine> = emptyList())
@Serializable data class QueuedTask(val number: Int, val from: String = "", val items: List<KLine> = emptyList())
@Serializable data class Chef(
    val id: String, val name: String, val lang: String = "en-IN", val skills: List<String> = emptyList(),
    val also: List<String> = emptyList(), val helpAny: Boolean = true, val online: Boolean = false,
    val task: ChefTask? = null, val queued: List<QueuedTask> = emptyList(),
)
@Serializable data class KitchenView(
    val orders: List<Order> = emptyList(), val upcoming: List<Order> = emptyList(), val mode: String = "order", val chefs: List<Chef> = emptyList(),
    val fulfil: String = "cook", val words: Words = Words(),
)
@Serializable data class OnlineRequest(val online: Boolean)
@Serializable data class TransferRequest(val to: String = "any")
@Serializable data class ChefsResponse(val chefs: List<Chef> = emptyList())
@Serializable data class CompleteRequest(val text: String? = null, val audio: String? = null)
@Serializable data class CompleteResponse(val heard: String = "", val completed: Boolean = false, val reason: String? = null, val orderReady: Boolean = false)
@Serializable data class ReadyRequest(val number: Int? = null, val lineId: String? = null, val text: String? = null, val audio: String? = null)
@Serializable data class Announcement(val number: Int = 0, val lang: String = "en-IN", val text: String = "")
@Serializable data class ReadyResponse(val heard: String = "", val announcements: List<Announcement> = emptyList())
@Serializable data class UnavailableRequest(val number: Int, val lineId: String)
@Serializable data class ChefConfig(val id: String? = null, val name: String, val lang: String = "en-IN", val skills: List<String> = emptyList(), val also: List<String> = emptyList(), val helpAny: Boolean = true)
@Serializable data class KitchenConfigRequest(val mode: String? = null, val chefs: List<ChefConfig>? = null)

// ---- owner
@Serializable data class SummaryStats(val orders: Int = 0, val revenue: Int = 0, val peakHour: Int? = null)
@Serializable data class Summary(val text: String = "", val stats: SummaryStats = SummaryStats())
@Serializable data class Briefing(val text: String = "", val tips: List<String> = emptyList(), val audio: String? = null)
@Serializable data class CopilotRequest(val text: String? = null, val audio: String? = null, val lang: String? = null)
@Serializable data class CopilotResponse(val heard: String = "", val answer: String = "", val lang: String = "en-IN", val audio: String? = null)
@Serializable data class UsageDay(val orders: Int = 0)
@Serializable data class Usage(val today: UsageDay? = null)
@Serializable data class PhoneInfo(val number: String = "", val on: String = "", val busy: String = "", val unreachable: String = "", val off: String = "")
@Serializable data class ShopInfo(val name: String = "", val code: String? = null, val multi: Boolean = false, val phone: PhoneInfo? = null)
@Serializable data class SoldOutRequest(val id: String, val soldOut: Boolean)
@Serializable data class CatalogItem(val id: String? = null, val name: String, val price: Int, val unit: String, val step: Double, val stock: Double? = null, val aliases: List<String> = emptyList())
@Serializable data class CatalogRequest(val items: List<CatalogItem>)
@Serializable data class MenuResponse(val menu: Menu = Menu())
@Serializable data class ShopSwitchRequest(val profile: String)
@Serializable data class TtsRequest(val text: String, val lang: String)
@Serializable data class TtsResponse(val audio: String? = null)

// ---- live events (SSE)
@Serializable data class ServerEvent(val type: String = "", val chefId: String? = null, val number: Int? = null, val lang: String? = null, val text: String? = null)

// ---- insights (only the fields the app shows)
@Serializable data class ItemAgg(val name: String = "", val qty: Double = 0.0)
@Serializable data class Agg(val orders: Int = 0, val revenue: Double = 0.0, val items: List<ItemAgg> = emptyList())
@Serializable data class Facts(val today: Agg = Agg(), val yesterday: Agg = Agg(), val week: Agg = Agg())
@Serializable data class Insights(val facts: Facts = Facts())
