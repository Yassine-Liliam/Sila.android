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

Every screen is wired, including onboarding, write actions, Settings and push — a new owner
can install the app and get all the way to a working business without touching the website —
and the design (Phase 10) and polish (Phase 11) passes are done. **The only phase left is the
Play Store (Phase 12)**: signing key, listing, screenshots. One thing carried into it: nothing
has been run through TalkBack or a large-font setting yet.

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

### ~~Phase 3 — Onboarding~~ ✅ *(done 2026-08-09, last of the functional phases)*
The 5-step wizard, native (`OnboardingScreen.kt`). A first-time owner installs the app, gets
onboarded, and is in — no detour through the website. "Onboarded" stays `user has ≥ 1
Business`, same as the web, so the gate is just `Session.business != null`.

**It reuses Settings' controls rather than its own.** Both screens ask the same questions —
Settings edits the answers this screen collects — so the field composables moved to
`ui/AnswerFields.kt`. A field added in one place now shows up in both, which is the failure
this avoids: the two drifting until only one knows about a question.

**Steps are an `Int` and a `when`**, per the repo's nav rule, with one `BackHandler`. Same
call made in Phase 6b and for the same reason: no back stack, no arguments, no deep link.

Three things worth keeping:
- **The screen never composes the AI brief** — it posts the answers and the backend derives
  `businessProfile`. Same rule as Settings, same reason.
- **`delivers` starts null and blocks the step until answered.** Everything else has a
  sensible default, but this one gates whether confirming an order creates a delivery, so a
  silently-defaulted "no" would break deliveries in a way the owner can't see from the app.
- **Language defaults to the device's** and stays a question. The app has no language picker
  (the platform provides one), but the *business* language is a business decision, not a
  phone setting.

Not done: the answers are plain `remember`, so a **rotation mid-wizard loses them** (the step
itself is saveable). Same gap as the assistant conversation, same fix — a `Saver`.

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

### ~~Phase 6 — Write actions~~ ✅ *(done 2026-08-08)*

Both sub-phases below. The one thing not built is the **product image picker** — tracked in
Phase 11, since it's a gap in a shipped screen rather than unfinished work here.

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

**The language switcher is in the drawer** (added 2026-08-09, reversing the original "the
platform already has a picker" call — an owner shouldn't have to leave the app to find it).
It writes the **platform's** per-app locale via `LocaleManager`, so the drawer and the entry
in Android Settings are one setting, not two that can disagree, and
`res/xml/locales_config.xml` + `localeConfig` still declare what's offered. Changing it
recreates the activity — that's the platform, and the assistant conversation goes with it.

**API 33+ only**: below that the item is absent and the app follows the system language, as
it always did. Covering older phones means adding `appcompat` for
`AppCompatDelegate.setApplicationLocales`, a dependency for a shrinking slice of devices.

### ~~Phase 9 — Push notifications~~ ✅ *(done 2026-08-08, verified on device 2026-08-09)*
FCM: **new pending order**. The one thing the web genuinely cannot do, and the reason an owner
keeps the app installed.

Inbound DMs were pushed too at first and it was wrong — the AI answers them, so every message
buzzed for nothing. That push was cut 2026-08-09; an order is the only event that actually
needs the owner. Restoring a DM push (gated on the conversation being paused) is the obvious
next lever if human-takeover threads should buzz.

**Chasing the first missing order push found a prompt bug, not a push bug**: the owner's AI
rule *"Confirm order details before closing"* collided with the DM prompt's hardcoded "never
confirm an order yourself", so the AI recapped the order and then waited forever without
calling `record_order`. Fixed in `lib/ai-reply.ts` in the Next repo. Worth remembering when
push "doesn't work": the notification is downstream of a tool call that may never have run —
check the Purchases list before touching FCM.

**Setup that isn't in this repo:** a Firebase project (`silati-2b4b5`) with `app.silati`
registered, `app/google-services.json` committed, and the matching **service-account JSON**
set as the Worker secret `FCM_SERVICE_ACCOUNT`. The service account must come from the same
Firebase project — a key from a different Google Cloud project fails at send time.

Things that will bite whoever touches this next:
- **`onMessageReceived` only fires in the foreground.** A push carrying a `notification`
  payload is drawn by the system when the app is backgrounded or dead, and the service is
  never called. `SilatiMessagingService` exists for the foreground case only.
- **The notification channel is created in `SilatiApplication`**, not an activity: Android 8+
  silently drops notifications posted to a channel that doesn't exist, and a push can arrive
  long before `MainActivity` ever runs. The manifest also names it as FCM's default channel,
  for the system-drawn case.
- **Registration runs on every sign-in**, not just the first — FCM rotates tokens on
  reinstall, restore, and at its own discretion. The backend upserts, so repeating is free;
  missing one means a permanently silent phone. `onNewToken` re-registers too.
- **Unregister happens *before* the session is cleared** on sign-out, because the request
  needs that token. A signed-out phone that keeps buzzing is worse than no push at all.
- Tapping a notification carries a `destination` and lands on Purchases or Conversations;
  the app is usually already running, so it arrives via `onNewIntent`, not `onCreate`.

Backend side lives in the Next repo: `lib/push.ts` (FCM HTTP v1, JWT signed with WebCrypto —
`firebase-admin` can't run on Workers) and `POST/DELETE /api/mobile/devices`.

### ~~Phase 10 — Visual design~~ ✅ *(done 2026-08-09)*

The app was stock Material 3 defaults — *correct* but not *designed*. This pass decided what
Silati looks like on Android, and the answer is deliberately **more platform, not less**: the
stance at the top of this README held, so design meant choosing inside Material's grammar
rather than decorating on top of it.

**Material 3 Expressive was tried and is not reachable.** On the material3 that Compose BOM
`2026.02.01` resolves (1.4.0), `MaterialExpressiveTheme`, `MotionScheme` and
`ExperimentalMaterial3ExpressiveApi` are **all `internal`** — the classes ship in the artifact
but nothing outside the library may name them. Getting Expressive means pinning a newer
material3 against the BOM. Don't re-try it without changing the version first.

What landed:

- **Colour** — unchanged on purpose. Dynamic colour (Android 12+) already takes the palette
  from the owner's wallpaper, including on One UI, which is what makes the app look like the
  phone it runs on. The cyan seed stands in below 12.
- **Typography** — no custom `Typography`; the M3 scale *is* the platform's, and the problem
  was assignment, not the scale. The role table now lives in Conventions; two strays were
  fixed (a `titleLarge` sheet header, a `bodyMedium` row secondary). The template `Type.kt`
  was deleted — its only override restated an M3 default.
- **The font stays `FontFamily.Default`** — Roboto on a Pixel, One UI Sans on a Galaxy.
  Bundling one would look *more* branded and *less* native, which is the wrong trade here.
- **Launcher icon** — the green robot is gone. The mark is generated by **inverting** the web
  repo's `silati-icon.svg`: that file is a tile whose logo is the *holes*, so a full-canvas
  rectangle is prepended to its path data and the whole thing filled `evenOdd`, which drops
  everything covered twice and leaves the mark. White on black, plus the Android 13+
  monochrome layer. Per-density bitmaps deleted — dead at minSdk 26.
- **Launch theme** — `Theme.Silati` was pinned to `Theme.Material.Light`, so a phone in dark
  mode flashed white on every cold start. `values-night/` now supplies the dark counterpart.
- **Motion** — `Crossfade` between drawer destinations, `Modifier.animateItem()` on list rows
  so a write that re-fetches doesn't snap them into place.
- **Drawer** — app mark beside the business name and email, a `Business` group label matching
  the web sidebar, no dividers, spaced items, and an account section pinned to the bottom
  holding Language, Settings and a red Sign out.
- **Screen titles removed** from the top bar: the drawer already names the destination.
- **Assistant empty state** — the app mark on a rounded tile above the heading.

Still open, deliberately:
- **The below-Android-12 fallback palette.** `lightColorScheme`/`darkColorScheme` override
  only primary/secondary/tertiary, so every other role is still M3's purple baseline — cyan
  buttons on lavender containers on Android 8–11. Invisible on any test device that has
  dynamic colour, which is why it wasn't done blind; it needs an API 30 emulator and a full
  seeded scheme pasted in.
- **Icons** are still core-set picks (a shopping cart for products, a map pin for deliveries).
  Better ones live in `material-icons-extended`, a large dependency for cosmetics.

### ~~Phase 11 — Polish~~ ✅ *(done 2026-08-09)*

Not a feature phase: everything already worked, it just didn't feel finished. Most of it
landed in `ui/PagedList.kt`, which is the point of having one list implementation — five
screens got each fix at once.

**Dates and timestamps** ✅ *2026-08-08* — sheets showed raw ISO strings and lists showed no
dates. `ui/DateFormat.kt`: relative times in rows (platform-localised via `DateUtils`),
absolute date+time in sheets.

**States**
- **Pull-to-refresh** on every list, reusing the same reload path as the retry button and a
  write action — one way a list re-reads itself.
- **Empty states** get an icon, and an action where the app can actually create the thing
  (Products, Clients). Purchases, Deliveries and Conversations get none, because an order
  comes from the AI, a delivery from confirming one, and a DM thread from a customer. A
  *filtered* empty shows text only — offering "add one" after a fruitless search would create
  something unrelated to what was typed.
- **Skeleton rows** replace the centred spinner. Deliberately not shimmering: the animation is
  the part that costs code, and the stillness is what fixed the layout jumping.
- **Errors** with rows on screen show a **snackbar with Retry**. The `SnackbarHostState` lives
  inside `PagedList`, so no screen has to plumb one down from the Scaffold.

**Lists and rows**
- Clients get an initial **avatar**, deliveries a **status dot** on the leading edge; row
  secondary text dropped to `bodySmall` in Phase 10, which is what stopped rows reading flat.
- **Scroll position** is captured before a write re-fetch and restored after. ponytail: only
  the first page is re-fetched, so this holds for a list that fits in one page and clamps
  otherwise — the real fix is re-fetching as many pages as were loaded.

**Product image picker** ✅ — a photo no longer has to be set from the web. `PickVisualMedia`
(no new dependency), the Uri previewed straight by Coil, and `encodeImage()` in
`data/Products.kt` doing the work at save time, off the main thread.

**The downscale is what makes it work, not an optimisation.** A phone camera photo is
routinely 4–12MB and the upload gate rejects anything over 5MB, so sending the original would
fail for most real pictures. Sampled decode (so a 50MP original is never fully decoded),
1600px long edge, JPEG 85. ponytail: JPEG for everything, so a PNG with transparency comes
back on black — product photos are photographs.

**Rotation** — the assistant conversation has a `Saver` that round-trips the same JSON the
wire uses (lossless by construction, since the content blocks are raw and get posted back
verbatim), and every wizard answer is `rememberSaveable`. Both now survive rotation *and* the
language switch, which recreates the activity. ponytail: saved state goes in a Bundle, which
dies past roughly 500KB — a long conversation with images could reach it, and the fix then is
a file plus its name, not a smaller Saver.

**First impressions** — the sign-in screen leads with the app mark instead of a bare word, and
the assistant's empty state offers three tappable first prompts. They teach what it can do:
an owner who has never used it has no way to guess "what did I sell today" is answerable.

**Accessibility**
- `StatusChip` text now flips with the theme — 700-level in light, 300/400 in dark. The single
  hardcoded pair was dark-on-translucent, which is legible over white and nearly invisible
  over black. The fills stay literal on purpose: order status must survive dynamic colour
  repainting the scheme from the owner's wallpaper.
- The product detail hero is described ("Photo of X"); row thumbnails stay `null`, decorative
  beside a name that is about to be read out anyway.

**Still not done:** nothing has been run through **TalkBack or a large-font setting**. That is
a device pass with no code to write until it finds something — worth doing before Phase 12,
since the Play listing is screenshots of these screens.

### Phase 12 — Play Store
Signing key, app icon, privacy-policy link, screenshots, listing.

*Last on purpose:* the listing is screenshots of the screens Phases 10 and 11 produce, and
the signing key is the one thing that can't be changed afterwards.

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
| `app/src/main/java/app/silati/OnboardingScreen.kt` | First-run setup: five steps as an `Int` and a `when`, creating the owner's first business. |
| `app/src/main/java/app/silati/ui/AnswerFields.kt` | The controls that edit onboarding answers — shared by Onboarding and Settings so a new question lands in both. |
| `app/src/main/java/app/silati/AssistantScreen.kt` | The assistant chat: bubbles, tool lines, composer. |
| `app/src/main/java/app/silati/data/Api.kt` | Retrofit service + wire types, and the OkHttp timeouts. Optional fields carry defaults and unknown keys are ignored, so a backend change can't crash the app. |
| `app/src/main/java/app/silati/data/Chat.kt` | Chat wire types (content stays raw JSON) and the flattening into displayable items. |
| `app/src/main/java/app/silati/data/Paging.kt` | `Page<T>` — the envelope every list route returns. |
| `app/src/main/java/app/silati/data/Entities.kt` | Client / Conversation / Purchase / Delivery read models + repositories. |
| `app/src/main/java/app/silati/data/Repos.kt` | All repositories, built once and passed down (no DI framework). |
| `app/src/main/java/app/silati/ui/PagedList.kt` | The list machinery all five screens share: paging, pull-to-refresh, skeletons, empty/error states, the Retry snackbar, plus `StatusChip` / `StatusDot`. |
| `app/src/main/java/app/silati/Forms.kt` | Product and client create/edit, including the photo picker. |
| `app/src/main/java/app/silati/{Products,Clients,Conversations,Purchases,Deliveries}Screen.kt` | The five entity screens. Shared row/sheet furniture lives in `ProductsScreen.kt`. |
| `app/src/main/java/app/silati/data/Session.kt` | Owns the token; exchanges the Google ID token, restores on launch, maps failures to offline / signed-out / failed. |
| `app/src/main/java/app/silati/data/TokenStore.kt` | Session token at rest: AES-256-GCM with the key in Android Keystore. |
| `app/src/main/java/app/silati/ui/theme/` | Material 3 theme — dynamic color on 12+, cyan-seeded fallback below. |
| `app/src/main/res/values/strings.xml` | All user-facing strings; `values-fr` / `values-ar` are complete. |
| `gradle/libs.versions.toml` | Dependency versions (version catalog). |

## Conventions

- **Claude never runs write git commands** — no `commit`, no `push`, no `add`. That is
  exclusively the owner's job. Read-only git is fine.
- **Native Android first.** Material 3 components over custom ones, platform behaviour over
  reimplementation. If the platform does it, we don't.
- **No dependency without need.** Nav is a state value and a `when` until a screen actually
  needs a back stack, arguments or deep links.
- **Every user-facing string goes in `strings.xml`**, never hardcoded in a composable.
- **Type is the stock M3 scale, assigned by role** — no custom `Typography`, because the
  default scale *is* the platform's and a custom one only makes the app look less native:

  | Role | Style |
  |---|---|
  | Sheet / detail header | `headlineSmall` |
  | Card or section title | `titleMedium` |
  | Row title | `bodyLarge` + `FontWeight.Medium` |
  | Row secondary line | `bodySmall`, `onSurfaceVariant` |
  | Body copy, hints, errors | `bodyMedium` (`bodySmall` when secondary) |
  | Field label above chips | `labelMedium`, `onSurfaceVariant` |
  | Timestamps, badges, meta | `labelSmall`, `onSurfaceVariant` |

  The one deliberate exception is the sign-in hero (`displaySmall`). Row secondary is
  `bodySmall` rather than the `bodyMedium` M3's own list spec suggests: at `bodyLarge`/
  `bodyMedium` the two lines are nearly the same size and the row reads flat.
- **Deliberate shortcuts get a `ponytail:` comment** naming the ceiling and the upgrade path.
- **Claude does not test** — changes are proposed, applied, and then verified by the owner on
  a device, one step at a time.

## Related

- **Next.js repo:** `Documents/Sila` — the web app, the Worker, and the `/api/mobile/*`
  endpoints this client calls. Pushing it to `main` deploys production.
