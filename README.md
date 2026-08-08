# Silati — Android

The Android app for [Silati](https://silati.app), an Instagram commerce assistant for small
businesses. A signed-in owner manages products, clients, conversations, purchases and
deliveries — by hand or through an **AI assistant chat that is the app's home screen**.

**Stack:** Kotlin · Jetpack Compose · Material 3 (dynamic color) · minSdk 26 / targetSdk 37 ·
AGP 9.3.1 · Kotlin 2.2.10 · Compose BOM 2026.02.01 · package `app.silati`.

---

## Design stance

The web app has its own identity — black surfaces, glass panels, one cyan shader. **None of
that comes here.** This app looks like an Android app: Material 3 components, dynamic color
from the user's wallpaper (the One UI feel on Samsung), native navigation, native gestures.

Corollary, and it is deliberate: **a web feature with no good Android equivalent is not
built.** Dropped outright — the landing page, `/work`, `/services`, `/pricing`, `/contact`,
the contact form, the background shader. `/privacy` becomes a Play Store link, not a screen.

The Android app is **workspace + settings + onboarding + login**, and nothing else.

## Architecture

```
Android app ──HTTPS + Bearer──► /api/mobile/* on Worker `sila`
                                    └─► Postgres @ prisma.io
                                    └─► Anthropic (Claude)
                                    └─► Instagram Graph API
```

This repo holds the **client**. Its API lives in the Next.js repo (`Documents/Sila`) as a
small `/api/mobile/*` surface on the existing Cloudflare Worker.

**Why not talk to Postgres directly from the app.** An APK is a zip — any string inside it is
public. Shipping `DATABASE_URL` would hand every business's data to anyone who downloads the
app, and it would break the isolation model, which is *structural*: "only this business's
rows" is enforced by the shape of each query. If the client writes the queries, the client can
write any query. Same reasoning for `ANTHROPIC_API_KEY`, `INSTAGRAM_CLIENT_SECRET` and
`ENCRYPTION_KEY`. **A secret in a client is a published secret** — anything the phone must not
know, the Worker does on its behalf.

**Why the existing Worker rather than a second backend.** It already holds the database
connection, every secret, and the `Session` table, and it has to keep running anyway because
it is what Meta delivers DM webhooks to. A second backend would duplicate all of that —
including putting `ENCRYPTION_KEY` in two places, which is strictly worse — plus a second
deploy pipeline and schema changes that must stay in sync across two codebases.

**This does not make the app a thin client.** The repos are separate and share no code; the
Kotlin app owns its screens, navigation, state and offline behaviour. An API is just how any
client reaches data — the web app's server actions are the same thing, browser-only.

**Auth** reuses the web app's session model rather than inventing one. Auth.js stores database
sessions — the `Session` table is just `sessionToken / userId / expires`. Mobile sign-in is:
native Google sign-in → ID token → Worker verifies it → mints a `Session` row → returns the
token → the app sends it as a Bearer header. Same sessions, same `User` rows, no parallel auth
system.

### Planned endpoints

```
POST /api/mobile/auth/google     ID token in, session token out
GET  /api/mobile/me              user + business
POST /api/mobile/onboarding      creates the first Business
POST /api/mobile/chat            the assistant (Sonnet 5 + the 13 owner tools)
     …/products …/clients …/purchases …/deliveries …/conversations
```

All behind one Bearer check, each reusing `lib/ai-tools.ts`, `lib/business.ts` and
`lib/prisma.ts` — logic that already exists and is already tenant-scoped.

## Status

**Phases 1–2 are done (2026-08-08): the app signs in against production and stays signed in.**

- **Navigation shell** — `ModalNavigationDrawer` with seven destinations (Assistant, Products,
  Clients, Chats, Purchases, Deliveries, Settings) plus Sign out; screens are placeholders.
- **Theme** — Material 3 with dynamic color on Android 12+, cyan-seeded fallback below.
- **Sign-in** — Credential Manager + `GetGoogleIdOption` (`SignIn.kt`), working.
- **Session** — the Google ID token goes straight to the backend and is never trusted
  on-device; the returned session token is stored encrypted (`data/TokenStore.kt`) and
  restored on launch, so a returning owner never sees the sign-in screen. A 401 clears the
  token and drops back to sign-in.
- **Networking** — Retrofit 3 + kotlinx.serialization against `BuildConfig.BASE_URL`
  (`https://silati.app/` for both build types today). `data/Api.kt` declares only the routes
  in use; `data/Session.kt` owns the token and maps failures onto offline / signed-out /
  failed so each gets its own message.

- **Assistant screen** — working chat on the home destination (`AssistantScreen.kt`), tool
  calls shown as muted `⚙ name` lines. The conversation is hoisted above the drawer, so
  switching destinations keeps it; a relaunch starts fresh.

- **Read screens** — Products, Clients, Chats, Purchases, Deliveries all list, paginate and
  open a detail sheet. Purchases and Deliveries filter by status; Products and Clients search.

Every screen except Settings is wired. Still missing: **write actions** (Phase 6 — confirm /
cancel an order, pause a conversation, edit a product), **Settings**, and **onboarding
(Phase 3, skipped)** — a brand-new owner still has to start on the web.

## Known issues

- None currently.

### Resolved

- ~~**Google sign-in fails with `28444: Developer console is not set up correctly`**~~
  (2026-08-07 → fixed 2026-08-08). **Cause: `serverClientId` was set to the *Android* OAuth
  client id** (`…rmvre5rv…`) instead of the **Web** one. Fixed by pointing `WEB_CLIENT_ID` in
  `SignIn.kt` at the Web application client.

  **How it was found — the logcat signature is the diagnostic**, so keep it here:

  ```
  [VerifyCallerOperation]   Operation succeeded   ← package + SHA-1 are correct
  [CompleteSignInOperation] Operation failed      ← 28444
  ```

  `VerifyCallerOperation` validates the **Android** client (package name + SHA-1);
  `CompleteSignInOperation` mints the token for the **`serverClientId`**. Caller verification
  passing while sign-in completion fails means the Android client is fine and the
  `serverClientId` is wrong — the two are unrelated credentials, and 28444 does not say which.

  Why both clients exist: the **Android** client answers *"is the app asking really ours?"*
  (looked up automatically from package + signature, never referenced in code); the **Web**
  client is the **`aud`** of the ID token — who the token is *for*, i.e. the Worker that
  verifies it. A server's identity is a Web client, so `serverClientId` is always the Web one.

  Everything previously suspected was innocent: package name, debug SHA-1, propagation delay,
  and the emulator's Play services (a Play-Store image on API 37 with Play services 26.29.32).

---

## Roadmap

Each phase ends in something runnable on a real device.

### ~~Phase 1 — mobile API foundation + auth~~ ✅ *(done 2026-08-08, in the Next.js repo)*
All 20 routes built and deployed, not just auth. Bearer-token resolution (`lib/mobile-api.ts`),
the Google ID-token endpoint, `/me`, and the entity + chat surface. See that repo's README.

### ~~Phase 2 — Android sign-in~~ ✅ *(done 2026-08-08)*
Credential Manager, session token encrypted on-device, restored on launch. Retrofit 3 +
kotlinx.serialization.

The setup tax was the whole cost: `serverClientId` must be the **Web** client (not the
Android one — that mistake is the `28444` in Resolved issues), and the app's SHA-1 must be
registered in Google Cloud.

### Phase 3 — Onboarding
The 5-step wizard, native. A first-time owner installs the app, gets onboarded, and is in —
no detour through the website. "Onboarded" stays `user has ≥ 1 Business`, same as the web.

### ~~Phase 4 — Assistant screen~~ ✅ *(done 2026-08-08, before Phase 3)*
Chat against `api/mobile/chat` with the full owner tool set. **Ephemeral by design** — the
phone holds the conversation and round-trips it; no persistence, no memory across launches.

Two things to preserve if this is ever refactored:
- **Message `content` stays raw JSON** (`data/Chat.kt`). The entire history — `tool_use` and
  `tool_result` blocks included — is posted back every turn, so any block field we failed to
  model would be dropped and corrupt the conversation. Raw JSON is lossless by construction;
  the UI reads only what it can draw.
- **OkHttp's read timeout is 120s.** The 10s default aborts almost every turn: Sonnet plus a
  server-side tool loop routinely takes longer.

Not done: image attachments, so `set_product_image` can't be driven from the phone. The wire
format already carries them — it needs a picker and base64, not a protocol change.

*The app is already useful from here* — the assistant can do everything the entity screens
will later do by hand. Phases 5–6 are the long tail.

### ~~Phase 5 — Read screens~~ ✅ *(done 2026-08-08)*
All five: lists with cursor pagination, detail as a bottom sheet, Coil for product images.

The shape worth keeping: every list route returns the same `Page<T>` envelope, so one
generic wire type and one `ui/PagedList.kt` composable carry all five screens — first page,
next page as the end scrolls into view, debounced filter, and the loading / empty / error /
retry states. A screen is then a filter control plus a row composable.

Detail is a bottom sheet, not a destination: the list response already carries every field,
so opening one costs no request and needs no back stack. **Phase 6 is where that stops being
true** — an edit form wants real navigation, and that is the moment to add navigation-compose.

### Phase 6 — Write actions

**6a — one-tap actions** ✅ *(done 2026-08-08)*
Confirm/cancel a purchase, pause/resume a conversation, set a delivery's status. No forms and
no navigation: they're buttons in the detail sheets that already existed. Every endpoint
returns the updated entity, and the list re-fetches after a write (`reloadKey` on
`PagedList`) — confirming an order can also create a delivery, so re-reading is the only way
the list is certainly right.

Confirming is one tap (happy path, reversible by cancelling); cancelling asks first, since
nothing in the app can undo it.

**6b — create/edit forms** ✅ *(done 2026-08-08)*
Product and client create/edit, reached from a FAB on the list or an Edit button in the
detail sheet.

**`navigation-compose` was planned here and deliberately not added.** A form reached from
exactly one place needs no back stack, no route arguments and no deep link — its argument is
the entity, passed directly, and back is one `BackHandler`. Adding the library would have
meant restructuring working navigation to gain nothing. The repo convention already said as
much ("nav is a state value and a `when` until a screen actually *needs* a back stack"); this
is the first time that rule was tested, and it held.

The likely trigger for revisiting is Phase 9: a push notification opening a specific order is
a real deep link, and that is when the library earns its place.

One shape to keep: `ProductInput` / `ClientInput` have every field nullable, and the Json is
configured `explicitNulls = false`, so a null field is **omitted** from the request — which
the backend reads as "leave alone". That's what lets one type serve both POST and PATCH. To
*clear* a field you send an empty string, and `stock = ""` therefore means "stop tracking"
while `stock = "0"` means "tracked, none left".

Not done: **image picking**, so a product photo still has to be set from the web. Both routes
already accept `image: { data, mediaType }` — it needs a `PickVisualMedia` launcher and
base64, not a protocol change.

### ~~Phase 7 — Settings + Instagram connect~~ ✅ *(done 2026-08-08)*
One scrolling screen of cards rather than tabs — profile, business, AI, delivery & payment,
policies, Instagram, danger zone.

The screen **never composes the AI brief**. It sends the onboarding answers; the backend
merges, sanitises and re-derives `businessProfile` from them. That's the same rule the web
follows and the reason the answers and the text the AIs read can't drift.

**Instagram connect is a real in-app button.** Meta requires an HTTPS redirect, so the
authorize screen has to be Instagram's own web page — but that is now the *only* screen the
owner sees. Tapping Connect fetches a one-time code from
`POST /api/mobile/instagram/connect-code`, opens a **Custom Tab** at
`/api/instagram/handoff?code=…`, and the Worker turns that code into a browser session and
redirects straight into the OAuth flow. No second sign-in.

The code is 32 random bytes, single-use, and dead after 60 seconds — which is what makes it
safe to carry in a URL when the session token would not be. Fetch it on tap, never earlier.

**No deep link back**: returning is a back press, and the card has a Refresh button. Auto-
returning would mean hosting `assetlinks.json` and app-link verification to save one tap.

Account deletion is type-to-confirm (`DELETE`), matching the API's own guard. It cascades
everything including the device's session, so success drops straight to the sign-in screen.

Option lists (languages, tones, rules, payments) are **duplicated** from the web wizard's
`compose.ts` in `data/Settings.kt` — the repos share no code. The backend only length-caps
and never validates against them, so drift degrades to different suggestions, never a
rejected save.

### ~~Phase 8 — French + Arabic~~ ✅ *(done 2026-08-08)*
`values-fr` and `values-ar`, complete. Putting every string in `strings.xml` from day one
paid off exactly as intended — this was one pass, no refactor.

**RTL needed no work.** Every layout already used direction-agnostic APIs (`Arrangement.End`,
`Alignment.BottomEnd`, `padding(horizontal =)`, `RoundedCornerShape(topStart …)`), all of
which mirror automatically, and `supportsRtl` was already set. Keep it that way: an
`absolutePadding` or a `TextAlign.Left` anywhere would break Arabic silently.

**Option chips translate their labels but keep English values.** The tones, rules and payment
methods in Settings are stored and fed to the AI, so the value must stay canonical English —
only the label the owner reads is localised (`optionLabel()` in `SettingsScreen.kt`, falling
back to the raw value). Adding an option to the web wizard therefore degrades to "shows in
English", never to a blank chip.

**No in-app language switcher, on purpose.** `res/xml/locales_config.xml` + `localeConfig` in
the manifest give the app a per-app Language entry in Android Settings (13+); below that it
follows the system language. The platform already has the picker — the web app needs its own
because the browser has none.

### Phase 9 — Push notifications
FCM: new DM, new pending order. The one thing the web genuinely cannot do, and the reason an
owner keeps the app installed.

### Phase 10 — Play Store
Signing key, app icon, privacy-policy link, screenshots, listing.

---

## Security notes

Kept current here for the same reason the web repo does it — Meta App Review asks.

- **No credentials in the APK.** The app holds a session token and nothing else. Database,
  Anthropic and Instagram credentials live only in the Worker's secrets.
- **Session token at rest** is stored in Android's encrypted storage, never in plain
  `SharedPreferences`.
- **Tenant scoping** is the web app's rule, unchanged: every query filters on a
  **server-derived** `businessId` resolved from the session — never from anything the client
  sends. A forged or guessed id can only produce "not found".
- **`/api/mobile/*` is new public attack surface** on a Worker that currently exposes almost
  none. Every route authenticates before it reads and validates input server-side; client-side
  limits are convenience only.
- **Assistant history is client-supplied** (same accepted risk as the web app): an owner could
  forge turns, but `ToolCtx` is server-derived, so the blast radius is their own data.

## Setup

Open the project in Android Studio and let Gradle sync. Run with a device selected
(**Run ▶** / Shift+F10).

For a physical phone: Settings → About phone → Software information → tap **Build number**
7×, then Developer options → **USB debugging**, and connect with a data cable.

## Code map

| Where | What |
|---|---|
| `app/src/main/java/app/silati/MainActivity.kt` | Entry point, session gate (loading / signed-out / ready / failed), drawer navigation. |
| `app/src/main/java/app/silati/SignIn.kt` | Credential Manager sign-in; `WEB_CLIENT_ID` is the **Web** OAuth client, i.e. the token's `aud`. |
| `app/src/main/java/app/silati/AssistantScreen.kt` | The assistant chat: bubbles, tool lines, composer. |
| `app/src/main/java/app/silati/data/Api.kt` | Retrofit service + wire types, and the OkHttp timeouts. Optional fields carry defaults and unknown keys are ignored, so a backend change can't crash the app. |
| `app/src/main/java/app/silati/data/Chat.kt` | Chat wire types (content stays raw JSON) and the flattening into displayable items. |
| `app/src/main/java/app/silati/data/Paging.kt` | `Page<T>` — the envelope every list route returns. |
| `app/src/main/java/app/silati/data/Entities.kt` | Client / Conversation / Purchase / Delivery read models + repositories. |
| `app/src/main/java/app/silati/data/Repos.kt` | All repositories, built once and passed down (no DI framework). |
| `app/src/main/java/app/silati/ui/PagedList.kt` | The list machinery all five screens share, plus `StatusChip`. |
| `app/src/main/java/app/silati/{Products,Clients,Conversations,Purchases,Deliveries}Screen.kt` | The five entity screens. Shared row/sheet furniture lives in `ProductsScreen.kt`. |
| `app/src/main/java/app/silati/data/Session.kt` | Owns the token; exchanges the Google ID token, restores on launch, maps failures to offline / signed-out / failed. |
| `app/src/main/java/app/silati/data/TokenStore.kt` | Session token at rest: AES-256-GCM with the key in Android Keystore. |
| `app/src/main/java/app/silati/ui/theme/` | Material 3 theme — dynamic color on 12+, cyan-seeded fallback below. |
| `app/src/main/res/values/strings.xml` | All user-facing strings (`values-fr` / `values-ar` land in Phase 8). |
| `gradle/libs.versions.toml` | Dependency versions (version catalog). |

## Conventions

- **Claude never runs write git commands** — no `commit`, no `push`, no `add`. That is
  exclusively the owner's job. Read-only git is fine.
- **Native Android first.** Material 3 components over custom ones, platform behaviour over
  reimplementation. If the platform does it, we don't.
- **No dependency without need.** Nav is a state value and a `when` until a screen actually
  needs a back stack, arguments or deep links.
- **Every user-facing string goes in `strings.xml`**, never hardcoded in a composable.
- **Deliberate shortcuts get a `ponytail:` comment** naming the ceiling and the upgrade path.
- **Claude does not test** — changes are proposed, applied, and then verified by the owner on
  a device, one step at a time.

## Related

- **Next.js repo:** `Documents/Sila` — the web app, the Worker, and the `/api/mobile/*`
  endpoints this client calls. Pushing it to `main` deploys production.
