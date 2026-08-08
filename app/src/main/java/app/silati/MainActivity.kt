package app.silati

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.silati.data.ChatMessage
import app.silati.data.Repos
import app.silati.data.Session
import app.silati.data.SessionError
import app.silati.data.SessionRepository
import app.silati.ui.theme.SilatiTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SilatiTheme {
                SilatiRoot(remember { SessionRepository(applicationContext) })
            }
        }
    }
}

/** What the app is showing right now. */
private sealed interface UiState {
    data object Loading : UiState
    data object SignedOut : UiState
    data class Ready(val session: Session) : UiState
    /** A stored token exists but couldn't be used — offline or a server error. Retryable. */
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : UiState
}

/**
 * Decides between the sign-in screen and the workspace, and owns the session.
 *
 * On launch it tries to restore a stored session, so a returning owner never sees the
 * sign-in screen. A revoked or expired token surfaces as [UiState.SignedOut] — the
 * repository has already cleared it by then.
 */
@Composable
private fun SilatiRoot(sessions: SessionRepository) {
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repos = remember { Repos(context.applicationContext) }

    // Held here, above the drawer's destination switch, so leaving the assistant for another
    // screen and coming back keeps the conversation.
    // ponytail: plain remember, so a rotation still clears it. Survives navigation, which is
    // the case that actually happens; a Saver over the raw JSON would fix the rest.
    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }

    // Re-runs when `attempt` changes, which is what the retry button bumps.
    LaunchedEffect(attempt) {
        state = if (!sessions.hasToken()) {
            UiState.SignedOut
        } else {
            runCatching { sessions.restore() }.fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { it.toUiState() },
            )
        }
    }

    when (val current = state) {
        is UiState.Loading -> LoadingScreen()

        is UiState.SignedOut -> SignInScreen(
            // The Google ID token is only ever passed straight to the backend — nothing
            // decodes or trusts it on-device.
            onSignedIn = { idToken ->
                state = UiState.Loading
                scope.launch {
                    state = runCatching { sessions.signIn(idToken) }.fold(
                        onSuccess = { UiState.Ready(it) },
                        onFailure = { it.toUiState() },
                    )
                }
            },
        )

        is UiState.Ready -> SilatiApp(
            session = current.session,
            onSignOut = {
                sessions.signOut()
                chatMessages = emptyList() // never leave one owner's conversation for the next
                state = UiState.SignedOut
            },
            // The token was already cleared server-side or locally; drop to sign-in.
            onSignedOut = {
                chatMessages = emptyList()
                state = UiState.SignedOut
            },
            repos = repos,
            chatMessages = chatMessages,
            onChatMessagesChange = { chatMessages = it },
        )

        is UiState.Failed -> ErrorScreen(
            message = current.detail ?: stringResource(current.message),
            onRetry = { attempt++ },
        )
    }
}

private fun Throwable.toUiState(): UiState = when (this) {
    is SessionError.SignedOut -> UiState.SignedOut
    is SessionError.Offline -> UiState.Failed(R.string.sign_in_offline)
    is SessionError.Failed -> UiState.Failed(R.string.sign_in_failed, detail)
    else -> UiState.Failed(R.string.sign_in_failed)
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Drawer destinations, in menu order. */
private enum class Dest(@param:StringRes val label: Int, val icon: ImageVector) {
    Assistant(R.string.nav_assistant, Icons.Default.Home),
    Products(R.string.nav_products, Icons.Default.ShoppingCart),
    Clients(R.string.nav_clients, Icons.Default.Person),
    Conversations(R.string.nav_conversations, Icons.Default.MailOutline),
    // AutoMirrored: these flip for RTL, which Arabic needs (Phase 8).
    Purchases(R.string.nav_purchases, Icons.AutoMirrored.Filled.List),
    Deliveries(R.string.nav_deliveries, Icons.Default.Place),
    Settings(R.string.nav_settings, Icons.Default.Settings),
}

// ponytail: flat destinations, so a single state value replaces a nav library. Swap in
// navigation-compose once a screen needs a back stack, arguments or deep links.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilatiApp(
    session: Session? = null,
    onSignOut: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    repos: Repos? = null,
    chatMessages: List<ChatMessage> = emptyList(),
    onChatMessagesChange: (List<ChatMessage>) -> Unit = {},
) {
    var current by rememberSaveable { mutableStateOf(Dest.Assistant) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
                    // The business name is the app's identity once signed in; the app name
                    // only stands in before there's a session (previews).
                    Text(
                        text = session?.displayName ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    session?.user?.email?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Dest.entries.forEach { dest ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(dest.label)) },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        selected = current == dest,
                        onClick = {
                            current = dest
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.sign_out)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onSignOut()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.label)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.open_menu),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            when {
                // An owner with no business has nothing for any screen to show, and the
                // assistant's tools would all fail on the server's not-onboarded guard.
                session?.onboarded == false -> Placeholder(
                    text = stringResource(R.string.not_onboarded),
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Assistant && repos != null -> AssistantScreen(
                    messages = chatMessages,
                    onMessagesChange = onChatMessagesChange,
                    chats = repos.chat,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Products && repos != null -> ProductsScreen(
                    products = repos.products,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Clients && repos != null -> ClientsScreen(
                    clients = repos.clients,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Conversations && repos != null -> ConversationsScreen(
                    conversations = repos.conversations,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Purchases && repos != null -> PurchasesScreen(
                    purchases = repos.purchases,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                current == Dest.Deliveries && repos != null -> DeliveriesScreen(
                    deliveries = repos.deliveries,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                else -> Placeholder(
                    text = stringResource(current.label),
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/** A destination that has no screen yet. */
@Composable
private fun Placeholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun SilatiAppPreview() {
    SilatiTheme {
        SilatiApp()
    }
}
