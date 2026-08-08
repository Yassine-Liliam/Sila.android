package app.silati.data

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * A catalog product.
 *
 * `price` is a **string**, not a number, all the way from the database: it is a Postgres
 * `Decimal` and the backend serialises it as text precisely so it never passes through a
 * float. Don't "fix" it to a Double — money and binary floating point don't mix.
 */
@Serializable
data class Product(
    val id: String,
    val name: String,
    val description: String? = null,
    val price: String,
    val currency: String = "MAD",
    /** null means stock isn't tracked for this product — different from 0. */
    val stock: Int? = null,
    /** Server-relative, e.g. `/uploads/products/<uuid>.png`. See [absoluteUrl]. */
    val imageUrl: String? = null,
    val active: Boolean = true,
    val createdAt: String? = null,
) {
    val displayPrice: String get() = "$price $currency"
}

/**
 * Product images are served by the Worker on a public, unauthenticated route (the filename
 * is an unguessable UUID), so they need no bearer token — only the origin prepended.
 */
fun absoluteUrl(path: String): String =
    if (path.startsWith("http")) path else BuildConfigBaseUrl.trimEnd('/') + path

/** Kept separate so [absoluteUrl] stays testable without the Android build config. */
private val BuildConfigBaseUrl: String get() = app.silati.BuildConfig.BASE_URL

class ProductRepository(context: Context) {
    private val tokens = TokenStore(context)

    /**
     * One page of products.
     *
     * @param cursor `null` for the first page, otherwise the previous page's `nextCursor`.
     * @param search matches the name, case-insensitively.
     */
    suspend fun page(cursor: String? = null, search: String? = null): Page<Product> =
        apiCall(tokens) {
            val token = tokens.read() ?: throw SessionError.SignedOut
            api.products(
                bearer = bearer(token),
                cursor = cursor,
                search = search?.takeIf { it.isNotBlank() },
            )
        }

    suspend fun create(input: ProductInput): Product = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.createProduct(bearer(token), input).product
    }

    suspend fun update(id: String, input: ProductInput): Product = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.updateProduct(bearer(token), id, input).product
    }
}
