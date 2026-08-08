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

`auth/google`, `me` and `chat` are wired. The other six destinations are still placeholders,
and **onboarding (Phase 3) was skipped** — a brand-new owner still has to start on the web.

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

### Phase 5 — Read screens
Products, Clients, Conversations, Purchases, Deliveries — lists and detail. Adds Coil for
product images.

### Phase 6 — Write actions
Confirm/cancel a purchase, pause/resume a conversation, create/edit products, update delivery
status. Purchase confirmation creates the delivery when the business delivers — same rule as
the web.

### Phase 7 — Settings + Instagram connect
Settings tabs (profile, business, AI, delivery, danger zone). **Instagram connect opens the
web flow in a Custom Tab and deep-links back** — Meta requires a public HTTPS redirect, so a
native reimplementation buys nothing.

### Phase 8 — French + Arabic
`values-fr`, `values-ar`, RTL. Strings go into `strings.xml` from day one, so this stays one
pass rather than a refactor. Stored and AI-bound values stay canonical English.

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
