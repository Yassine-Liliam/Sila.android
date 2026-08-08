package app.silati.data

import android.content.Context

/**
 * Every repository, built once per process and passed down.
 *
 * ponytail: hand-rolled instead of a DI framework. Six constructors taking a Context is not
 * a problem worth Hilt; revisit if this list gets long or something needs a real scope.
 */
class Repos(context: Context) {
    private val app = context.applicationContext

    val chat = ChatRepository(app)
    val products = ProductRepository(app)
    val clients = ClientRepository(app)
    val conversations = ConversationRepository(app)
    val purchases = PurchaseRepository(app)
    val deliveries = DeliveryRepository(app)
    val settings = SettingsRepository(app)
}
