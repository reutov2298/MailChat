package ru.mailchat.app

/**
 * Gmail integration configuration.
 *
 * Before enabling OAuth, create an Android OAuth client in Google Cloud Console.
 * Add the app's package name and signing certificate SHA-1 as required by Google.
 * Gmail IMAP/SMTP access requires the https://mail.google.com/ scope.
 *
 * Do not ask users for their Google password. Use OAuth 2.0 authorization.
 */
object GmailConfig {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val IMAP_HOST = "imap.gmail.com"
    const val IMAP_PORT = 993
    const val SMTP_HOST = "smtp.gmail.com"
    const val SMTP_PORT = 465
    const val GMAIL_SCOPE = "https://mail.google.com/"
    const val REDIRECT_URI = "ru.mailchat.app://oauth2redirect"

    // Fill this with the OAuth 2.0 Android/Web client ID from your own Google Cloud project.
    // Never put a client secret in the Android app.
    const val CLIENT_ID = "REPLACE_WITH_GOOGLE_OAUTH_CLIENT_ID"
}
