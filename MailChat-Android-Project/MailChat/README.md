# MC — Android 1.2.0

This project continues the existing `MailChat` Android project. Package name remains `ru.mailchat.app`; the GitHub Actions workflow and cloud build approach are retained.

## Included in this source revision

- Kotlin + Jetpack Compose Android application shell with Russian UI.
- Google OAuth 2.0 authorization flow using AppAuth (Authorization Code + PKCE).
- Gmail API REST transport for profile lookup, sending, message listing, label management, archiving, and statistics.
- A dedicated Gmail label named `MC`; incoming messages are labelled and archived only after local processing succeeds, while outgoing messages are labelled after sending.
- One-time historical migration: candidate mail is found by the MC DATA marker and accepted only after the current protocol v2 fields and Ed25519 signature validate. Valid incoming mail is archived; valid sent mail receives the MC label. Migration checkpoints survive interruption.
- Manual per-label folder statistics plus an exact unique-message count across `in:anywhere`.
- Confirmed permanent deletion of only messages currently carrying the MC label, with a saved ID snapshot, per-batch label revalidation, and resumable progress.
- The restricted `https://mail.google.com/` OAuth scope is requested incrementally only when the user invokes permanent deletion; ordinary MC operation continues to use `gmail.modify`.
- First-connection baseline: historical messages are not imported into chats; a separate signed-message migration only labels and archives verified MC messages.
- Draft MC mail envelope with `X-MC-*` headers and a duplicated `BEGIN/END MC DATA` block in the visible text body.
- Ed25519 signing and verification using Bouncy Castle. The private key and OAuth state are stored using AndroidX EncryptedSharedPreferences.
- Incoming message validation against duplicated MIME headers, recipient, sender, timestamp and signature.
- Spam-folder candidate scan; only a message that passes the MC envelope/signature checks is removed from `SPAM` and added to the local chat list.
- Local chat/message persistence, chat deletion (local only), and a compact local log with clear action.
- GitHub Actions debug APK workflow; OAuth client ID is read from the repository variable `GOOGLE_OAUTH_CLIENT_ID`.

## Important limitations — not yet complete

This is an early functional prototype, not the full specification. The following must not be represented as implemented:

- FCM push, Gmail Watch, Pub/Sub and Cloud Functions are not configured or implemented. The current UI offers manual synchronization; background polling is not yet wired.
- Signed ACK messages and the two-checkmark receipt flow are not implemented. The app must not claim remote delivery confirmation.
- The sync implementation currently retrieves and validates a bounded set of recent candidates. It is not yet a complete durable Gmail History API cursor implementation.
- The local data layer currently uses SharedPreferences JSON, not Room; a database migration is still required.
- The spam scan is performed during manual synchronization, not continuously in the background.
- The first-contact public key is TOFU. Ed25519 proves message integrity relative to the received key; it does not independently prove the human identity behind a Gmail address.
- Push notifications, Android 13 notification permission UX, WorkManager periodic sync, full error recovery, and comprehensive automated tests remain future work.
- Message body is not end-to-end encrypted. Do not use this prototype for confidential communication.

## Configure Google OAuth before testing

1. In Google Cloud Console create/configure a project and enable Gmail API.
2. Configure the OAuth consent screen and add the Gmail accounts used for testing as test users if the app is in Testing mode.
3. Create the appropriate OAuth client for the Android application. Confirm package name `ru.mailchat.app` and the signing certificate fingerprint used by the debug build.
4. Add a GitHub repository **Actions variable** named `GOOGLE_OAUTH_CLIENT_ID` with the OAuth client ID. Do not add a client secret to the Android app or repository.
5. Push the project to the existing `MailChat` repository and run the `Android APK` workflow.

The OAuth client configuration and Google consent-screen verification requirements are external prerequisites. The permanent-delete scope is restricted and may require Google OAuth verification before public distribution. A successful APK build alone does not mean Gmail authorization is configured.

## Build through GitHub Actions

1. Upload the contents of this folder to the existing repository `https://github.com/reutov2298/MailChat` (preserve the repository and branch).
2. Open **Actions** → **Android APK** → **Run workflow**.
3. Wait for the workflow to finish.
4. Download artifact `MC-1.2.0` and extract `app-debug.apk`.

The workflow uses Gradle 8.9 and JDK 17. Application ID remains `ru.mailchat.app`. The current feature build is `1.2.0` (versionCode 14), artifact `MC-1.2.0`.

## Next required engineering steps

1. Build in GitHub Actions and resolve any compiler/dependency issues found by the real Android toolchain.
2. Test OAuth and Gmail send/receive on two real Android devices.
3. Replace the provisional search-based sync with Gmail `users.history.list` and durable cursor handling.
4. Add signed ACK request/response and correct delivery state transitions.
5. Migrate local data to Room and add message deduplication with durable IDs.
6. Add Gmail Watch → Pub/Sub → Cloud Function/Run → FCM, plus WorkManager fallback.
7. Add background spam scan, Android notification channels and permission handling.
8. Add automated protocol, crypto, MIME and integration tests.
