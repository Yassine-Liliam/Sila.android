package app.silati.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

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

/**
 * Reads a picked image and encodes it for upload.
 *
 * **Downscaling is not an optimisation here, it's what makes the feature work.** A phone
 * camera photo is routinely 4–12MB and the upload gate rejects anything over 5MB, so sending
 * the original would fail for most real pictures — and on a Moroccan mobile connection a
 * needlessly large upload is its own punishment. 1600px on the long edge is more than a
 * product thumbnail or a detail hero ever shows.
 *
 * Decoding is sampled first (`inSampleSize`) so a 50MP original is never fully decoded into
 * memory. Returns null if the image can't be read or decoded; the caller reports that rather
 * than uploading something broken.
 *
 * ponytail: JPEG at 85 for everything, so a PNG with transparency comes back on a black
 * background. Product photos are photographs; switch on the source type if that stops being
 * true.
 */
suspend fun encodeImage(context: Context, uri: Uri): ImageInput? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE * 2) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null

        val longest = maxOf(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_IMAGE_EDGE) {
            val ratio = MAX_IMAGE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        ImageInput(
            data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            mediaType = "image/jpeg",
        )
    }.getOrNull()
}

private const val MAX_IMAGE_EDGE = 1600

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
