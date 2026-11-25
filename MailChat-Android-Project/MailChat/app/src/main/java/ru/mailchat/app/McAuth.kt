package ru.mailchat.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.*
import org.json.JSONObject

class McAuth(private val context: Context) {
    private val service = AuthorizationService(context)
    private val config = AuthorizationServiceConfiguration(
        Uri.parse(GmailConfig.AUTHORIZATION_ENDPOINT), Uri.parse(GmailConfig.TOKEN_ENDPOINT)
    )
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "mc_auth", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun start(activity: MainActivity, includePermanentDeleteScope: Boolean = false) {
        require(GmailConfig.CLIENT_ID.isNotBlank() && !GmailConfig.CLIENT_ID.startsWith("REPLACE")) {
            "Не настроен Google OAuth Client ID. Добавьте GOOGLE_OAUTH_CLIENT_ID в GitHub Actions Variables."
        }
        val request = AuthorizationRequest.Builder(config, GmailConfig.CLIENT_ID,
            ResponseTypeValues.CODE, Uri.parse(GmailConfig.REDIRECT_URI))
            .setScope(if (includePermanentDeleteScope) GmailConfig.SCOPE + " " + GmailConfig.DELETE_SCOPE else GmailConfig.SCOPE)
            .setAdditionalParameters(mapOf("include_granted_scopes" to "true"))
            .let { builder ->
                val verifier = CodeVerifierUtil.generateRandomCodeVerifier()
                builder.setCodeVerifier(verifier, CodeVerifierUtil.deriveCodeVerifierChallenge(verifier), CodeVerifierUtil.getCodeVerifierChallengeMethod())
                builder.build()
            }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        service.performAuthorizationRequest(request, pending)
    }
    fun handle(intent: Intent, callback: (String?, String?) -> Unit): Boolean {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        if (response == null) {
            if (error != null) callback(null, error.errorDescription ?: "Ошибка OAuth")
            return error != null
        }
        service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            if (tokenResponse != null) {
                val saved = prefs.getString("auth_state", null)
                val previousRefreshToken = if (saved.isNullOrBlank()) null else
                    runCatching { AuthState.jsonDeserialize(saved).refreshToken }.getOrNull()
                val mergedTokenResponse = if (tokenResponse.refreshToken.isNullOrBlank() && !previousRefreshToken.isNullOrBlank()) {
                    TokenResponse.Builder(tokenResponse.request)
                        .setTokenType(tokenResponse.tokenType)
                        .setAccessToken(tokenResponse.accessToken)
                        .setAccessTokenExpirationTime(tokenResponse.accessTokenExpirationTime)
                        .setIdToken(tokenResponse.idToken)
                        .setRefreshToken(previousRefreshToken)
                        .setScope(tokenResponse.scope)
                        .setAdditionalParameters(tokenResponse.additionalParameters)
                        .build()
                } else tokenResponse
                val state = AuthState(response, mergedTokenResponse, tokenError)
                prefs.edit().putString("auth_state", state.jsonSerializeString()).apply()
                callback(tokenResponse.accessToken, null)
            } else {
                callback(null, tokenError?.errorDescription ?: "Не удалось получить токен")
            }
        }
        return true
    }

    fun accessToken(callback: (String?, String?) -> Unit) {
        val json = prefs.getString("auth_state", null)
        if (json == null) { callback(null, "Аккаунт Google не подключён"); return }
        try {
            val state = AuthState.jsonDeserialize(json)
            state.performActionWithFreshTokens(service) { access, _, ex ->
                if (access != null) {
                    prefs.edit().putString("auth_state", state.jsonSerializeString()).apply()
                    callback(access, null)
                } else callback(null, ex?.errorDescription ?: "Не удалось обновить авторизацию")
            }
        } catch (e: Exception) { callback(null, e.message ?: "Ошибка сохранённой авторизации") }
    }
    fun hasPermanentDeleteScope(): Boolean = runCatching {
        val json = prefs.getString("auth_state", null) ?: return@runCatching false
        val state = AuthState.jsonDeserialize(json)
        state.lastTokenResponse?.scope.orEmpty().split(" ").contains(GmailConfig.DELETE_SCOPE)
    }.getOrDefault(false)

    fun isConnected(): Boolean = prefs.getString("auth_state", null) != null
    fun disconnect() { prefs.edit().clear().apply() }
    fun close() { service.dispose() }
}
