package app.silati

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.silati.data.Product
import app.silati.data.ProductRepository
import app.silati.data.absoluteUrl
import app.silati.ui.PagedList
import coil3.compose.AsyncImage

/**
 * The catalog, read-only (Phase 5).
 *
 * Detail is a bottom sheet rather than a second destination: the list response already
 * carries every field, so opening one costs no request and needs no back stack.
 * ponytail: when Phase 6 adds create/edit, that stops being true — a real form wants
 * navigation-compose, and that is the moment to add it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    products: ProductRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Product?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.products_search),
        )
        PagedList(
            filter = query,
            load = { cursor, search -> products.page(cursor, search) },
            itemKey = { it.id },
            emptyText = stringResource(R.string.products_empty),
            emptyTextWhenFiltered = stringResource(R.string.products_no_match),
            onSignedOut = onSignedOut,
        ) { product ->
            ProductRow(product) { selected = product }
        }
    }

    selected?.let { product ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ProductDetail(product)
        }
    }
}

/** Shared by every list screen that filters by text. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
    )
}

/** The card every list row sits in. */
@Composable
fun EntityRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(product.imageUrl, size = 56)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = product.displayPrice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!product.active) {
                    Text(
                        text = stringResource(R.string.product_inactive),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = product.stock?.let { stringResource(R.string.product_stock, it) }
                        ?: stringResource(R.string.product_stock_untracked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProductDetail(product: Product) {
    SheetBody {
        product.imageUrl?.let {
            AsyncImage(
                model = absoluteUrl(it),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.height(16.dp))
        }
        Text(text = product.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = product.displayPrice,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        product.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = product.stock?.let { stringResource(R.string.product_stock, it) }
                ?: stringResource(R.string.product_stock_untracked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!product.active) {
            Text(
                text = stringResource(R.string.product_inactive_explained),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Padding shared by every detail sheet. */
@Composable
fun SheetBody(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        content = content,
    )
}

/** One labelled line in a detail sheet. Renders nothing when the value is absent. */
@Composable
fun DetailLine(label: String, value: String?) {
    value?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = it, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Thumbnail(imageUrl: String?, size: Int) {
    val shape = RoundedCornerShape(8.dp)
    if (imageUrl == null) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        AsyncImage(
            model = absoluteUrl(imageUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(shape),
        )
    }
}
