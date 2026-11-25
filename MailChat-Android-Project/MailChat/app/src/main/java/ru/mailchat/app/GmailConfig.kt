package ru.mailchat.app

object GmailConfig {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REDIRECT_URI = "com.googleusercontent.apps.150613527215-b8pfo2q526mpvgihvlj03iq9kmjpdn9i:/oauth2redirect"
    const val GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me"
    const val SCOPE = "openid email https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/contacts.readonly"
    // Set this GitHub Actions repository variable before building. Never add a client secret to the APK.
    const val CLIENT_ID = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
}
