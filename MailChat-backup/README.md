# MailChat — Android prototype scaffold

This repository is the initial Android project scaffold for the MailChat concept.

## Current state (v0.1 scaffold)

Implemented:
- Native Android app shell using Kotlin + Jetpack Compose.
- Demo chat list, demo conversation screen, settings screen.
- Demo-only local message bubbles (not email delivery).
- Draft MailChat message envelope and proposed email headers.
- GitHub Actions workflow to build a debug APK after the project is pushed to GitHub.

Not implemented yet:
- Gmail OAuth sign-in.
- IMAP receiving / SMTP sending.
- Real contact discovery / chat synchronization.
- Push notifications / IMAP IDLE.
- Delivery/read receipts.
- End-to-end encryption.
- Real message persistence.

**This project is not yet a working email messenger.** The UI currently uses sample data. Do not use it for private or sensitive communication.

## Gmail integration plan

Gmail must be connected using OAuth 2.0. The app must never request or store a user's Google password.

For IMAP/SMTP access, the required Gmail scope is `https://mail.google.com/`.
Google may require OAuth consent-screen configuration, test-user registration, and app verification before public distribution. A production OAuth setup must be completed in a Google Cloud project.

`GmailConfig.kt` contains the provider endpoints and a placeholder client ID. Do not add a client secret to the Android application.

## Build in the cloud

1. Create a GitHub repository named `MailChat`.
2. Upload the contents of this project to the repository's `main` branch.
3. Open the **Actions** tab.
4. Run **Android APK** (or push a commit).
5. Download the `MailChat-debug` artifact from the completed workflow.
6. Extract `app-debug.apk` and install it on the Android phone.

The GitHub Actions workflow installs Gradle 8.9 directly, so a Gradle wrapper is not required for the cloud build. To build locally, open the project in Android Studio and let it configure Gradle.

## Next development milestones

1. Add Gradle wrapper and verify clean cloud build.
2. Configure Gmail OAuth and account connection.
3. Implement SMTP sending with XOAUTH2.
4. Implement IMAP sync with XOAUTH2 and MailChat header filtering.
5. Add local database and conversation grouping.
6. Add WorkManager fallback sync and later IMAP IDLE where provider/device behavior allows.
7. Add explicit delivery/read receipt messages.
8. Add E2EE only after the unencrypted prototype is validated.

## Important product decisions

- New account setup should offer an opt-in import of old mail; default off.
- Ordinary mail visibility should be a user setting.
- MailChat messages must be identified by a structured, validated envelope, not by sender display name or subject alone.
- The initial unencrypted prototype should still use TLS for transport.
- Email is not a guaranteed real-time transport. Provider quotas and Android background restrictions must be tested.
