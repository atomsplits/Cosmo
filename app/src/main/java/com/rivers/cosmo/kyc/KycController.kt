package com.rivers.cosmo.kyc

import android.content.Context
import com.rivers.cosmo.chat.crypto.KeyVault
import com.rivers.cosmo.chat.crypto.Pgp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

class KycException(message: String) : Exception(message)

/**
 * Identity verification for the signed-in user. The Cosmo server holds the Sumsub credentials;
 * this asks it for SDK access tokens and the current status, signing each request with the
 * user's chat PGP key so the server knows who is asking.
 */
class KycController(context: Context, private val userId: String, chatServerUrl: String) {
    data class State(
        val status: KycStatus? = null,
        val loading: Boolean = false,
        val error: String? = null
    )

    private val vault = KeyVault(context)
    private val baseUrl = KycProtocol.httpBaseUrl(chatServerUrl)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val status = KycProtocol.parseStatus(post("status").getString("status"))
                _state.update { State(status = status) }
            } catch (e: KycException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** A fresh Sumsub SDK access token. Used to start verification and when the SDK's token expires. */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        try {
            post("token").getString("token")
        } catch (e: JSONException) {
            throw KycException("The Cosmo server sent an unexpected response.")
        }
    }

    fun close() {
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    private fun post(action: String): JSONObject {
        val key = vault.loadOrCreate(userId)
        val timestamp = System.currentTimeMillis()
        val body = JSONObject()
            .put("userId", userId)
            .put("timestamp", timestamp)
            .put("signature", Pgp.sign(KycProtocol.signedText(action, userId, timestamp), key))
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/kyc/$action")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                if (!response.isSuccessful) {
                    throw KycException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "The Cosmo server returned an error (${response.code}).")
                }
                return json ?: throw KycException("The Cosmo server sent an unexpected response.")
            }
        } catch (e: IOException) {
            throw KycException("Can't reach the Cosmo server at $baseUrl. Check Settings → Cosmo server.")
        }
    }
}
