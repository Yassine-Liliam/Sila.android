package app.silati

import android.Manifest
import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.silati.data.ChatMessage
import app.silati.data.Repos
import app.silati.data.Session
import app.silati.data.SessionError
import app.silati.data.SessionRepository
import app.silati.ui.theme.SilatiTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    /**
     * Where a tapped notification wants us to go, from the `destination` data field the
     * backend sends. Held as state rather than read once, because the app is usually already
     * running when a notification is tapped — onNewIntent, not onCreate.
     */
    private val tappedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tappedDestination.value = intent?.getStringExtra(SilatiMessagingService.EXTRA_DESTINATION)
        setContent {
            SilatiTheme {
                SilatiRoot(
                    sessions = remember { SessionRepository(applicationContext) },
                    tappedDestination = tappedDestination.value,
                    onDestinationHandled = { tappedDestination.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tappedDestination.value = intent.getStringExtra(SilatiMessagingService.EXTRA_DESTINATION)
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
private fun SilatiRoot(
    sessions: SessionRepository,
    tappedDestination: String? = null,
    onDestinationHandled: () -> Unit = {},
) {
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

    // Notifications are opt-in from Android 13. Asked once the owner is actually signed in,
    // not at launch: the prompt only makes sense next to something worth being told about,
    // and a denial here is permanent until they go into system settings.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Granted or not, the app works — only the notifications differ. */ }

    // Registration runs on every sign-in, not just the first: FCM rotates tokens, and the
    // backend upserts, so repeating is free and missing one means a silent phone.
    LaunchedEffect(state) {
        if (state is UiState.Ready) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            runCatching { repos.push.register() }
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

        // A brand-new owner has no business yet, so no screen has anything to show and the
        // assistant's tools would all fail the server's not-onboarded guard: set up first.
        is UiState.Ready -> if (!current.session.onboarded) {
            OnboardingScreen(
                sessions = sessions,
                userName = current.session.user.name,
                onDone = { state = UiState.Ready(it) },
                onSignedOut = { state = UiState.SignedOut },
            )
        } else {
            SilatiApp(
                session = current.session,
                onSignOut = {
                    scope.launch {
                        // Unregister BEFORE clearing the session — the request needs that
                        // token, and a signed-out phone that keeps buzzing is worse than no
                        // push at all.
                        runCatching { repos.push.unregister() }
                        sessions.signOut()
                        chatMessages = emptyList() // don't leave one owner's chat for the next
                        state = UiState.SignedOut
                    }
                },
                // The token was already cleared server-side or locally; drop to sign-in.
                onSignedOut = {
                    chatMessages = emptyList()
                    state = UiState.SignedOut
                },
                repos = repos,
                chatMessages = chatMessages,
                onChatMessagesChange = { chatMessages = it },
                tappedDestination = tappedDestination,
                onDestinationHandled = onDestinationHandled,
            )
        }

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
    tappedDestination: String? = null,
    onDestinationHandled: () -> Unit = {},
) {
    var current by rememberSaveable { mutableStateOf(Dest.Assistant) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // A tapped notification says where it came from; land there instead of on the assistant.
    // Cleared once handled, so rotating the screen doesn't yank the owner back.
    LaunchedEffect(tappedDestination) {
        when (tappedDestination) {
            "purchases" -> current = Dest.Purchases
            "conversations" -> current = Dest.Conversations
            null -> return@LaunchedEffect
            else -> Unit // unknown destination: stay put rather than guess
        }
        onDestinationHandled()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The launcher mark, tinted rather than drawn in brand cyan, so it
                        // sits in whatever palette dynamic colour gave the rest of the app.
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_glyph),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            // The business name is the app's identity once signed in; the app
                            // name only stands in before there's a session (previews).
                            Text(
                                text = session?.displayName ?: stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            session?.user?.email?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.nav_group),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                        )
                        // Settings is not in this list: it lives with Sign out at the bottom,
                        // where account-level actions belong rather than in the content menu.
                        Dest.entries.filter { it != Dest.Settings }.forEach { dest ->
                            NavigationDrawerItem(
                                label = { Text(stringResource(dest.label)) },
                                icon = { Icon(dest.icon, contentDescription = null) },
                                selected = current == dest,
                                onClick = {
                                    current = dest
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(
                                    NavigationDrawerItemDefaults.ItemPadding
                                ),
                            )
                        }
                    }

                    // Pushes the account section to the bottom of the sheet.
                    Spacer(Modifier.weight(1f))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        LanguageItem()
                        NavigationDrawerItem(
                            label = { Text(stringResource(Dest.Settings.label)) },
                            icon = { Icon(Dest.Settings.icon, contentDescription = null) },
                            selected = current == Dest.Settings,
                            onClick = {
                                current = Dest.Settings
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.sign_out)) },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = null,
                                )
                            },
                            selected = false,
                            // Signing out is the one destructive thing in the drawer.
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedIconColor = MaterialTheme.colorScheme.error,
                                unselectedTextColor = MaterialTheme.colorScheme.error,
                            ),
                            onClick = {
                                scope.launch { drawerState.close() }
                                onSignOut()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    // No title: the drawer names the destination, and repeating it above every
                    // screen spent a whole bar on a word the owner just tapped.
                    title = {},
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
            // Fade through between destinations. Switching used to be an instant swap, which
            // reads as a glitch rather than a navigation; the drawer already animates, so the
            // content was the one part that didn't move.
            Crossfade(targetState = current, label = "destination") { dest ->
            when {
                // Not-onboarded never reaches here — SilatiRoot routes it to OnboardingScreen.
                dest == Dest.Assistant && repos != null -> AssistantScreen(
                    messages = chatMessages,
                    onMessagesChange = onChatMessagesChange,
                    chats = repos.chat,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Products && repos != null -> ProductsScreen(
                    products = repos.products,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Clients && repos != null -> ClientsScreen(
                    clients = repos.clients,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Conversations && repos != null -> ConversationsScreen(
                    conversations = repos.conversations,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Purchases && repos != null -> PurchasesScreen(
                    purchases = repos.purchases,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Deliveries && repos != null -> DeliveriesScreen(
                    deliveries = repos.deliveries,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                dest == Dest.Settings && repos != null -> SettingsScreen(
                    settings = repos.settings,
                    onSignedOut = onSignedOut,
                    modifier = Modifier.padding(innerPadding),
                )

                else -> Placeholder(
                    text = stringResource(dest.label),
                    modifier = Modifier.padding(innerPadding),
                )
            }
            }
        }
    }
}

/**
 * Language picker for the drawer.
 *
 * Sets the app's locale through the **platform's** per-app language setting
 * (`LocaleManager`), so this and the entry in Android Settings are the same switch rather
 * than two that can disagree — and `res/xml/locales_config.xml` still declares what's on
 * offer. Changing it recreates the activity, which is the platform's doing; the assistant
 * conversation is lost with it, same as a rotation.
 *
 * ponytail: API 33+ only, so the item is simply absent below that and the app follows the
 * system language as it always did. Covering older phones means adding `appcompat` for
 * `AppCompatDelegate.setApplicationLocales` — a dependency for a shrinking slice of devices.
 */
@Composable
private fun LanguageItem() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Language names are written in their own language, never translated.
    val languages = listOf("en" to "English", "fr" to "Français", "ar" to "العربية")
    val current = Locale.getDefault().language

    Box {
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.nav_language)) },
            // The current language's code stands in for an icon: the core icon set has no
            // globe, and this says which language is active without a second line.
            icon = {
                Text(
                    text = current.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            selected = false,
            onClick = { open = true },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        context.getSystemService(LocaleManager::class.java)
                            ?.applicationLocales = LocaleList.forLanguageTags(tag)
                    },
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
