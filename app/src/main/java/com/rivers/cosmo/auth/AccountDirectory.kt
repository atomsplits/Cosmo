package com.rivers.cosmo.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The account server answered, but with an error, as opposed to not being reachable at all.
 * [message] says what's wrong in words for the user (usually a setup problem).
 */
class AccountServerException(message: String) : IOException(message)

/** Where accounts are registered and checked, so the same account works on any phone. */
interface AccountDirectory {
    enum class Verification { Ok, Mismatch, Unknown }

    /** Returns false if the Cosmo ID is taken. Throws [IOException] if the server can't be reached. */
    fun register(cosmoId: String, verifier: String): Boolean

    fun verify(cosmoId: String, verifier: String): Verification
}

/** Supabase's connection details, from local.properties (see the README); blank when not set up. */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val configured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
}

/**
 * Accounts in Supabase, through the register_account and verify_account database functions
 * (PostgREST's /rest/v1/rpc). The project's anon key is public by design: the functions are the
 * only access it has to accounts. Calls block, so make them off the main thread.
 */
class SupabaseAccountDirectory(private val config: SupabaseConfig) : AccountDirectory {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    override fun register(cosmoId: String, verifier: String): Boolean =
        call("register_account", cosmoId, verifier).toBooleanStrictOrNull() ?: throw IOException("Unexpected response")

    override fun verify(cosmoId: String, verifier: String): AccountDirectory.Verification =
        when (call("verify_account", cosmoId, verifier)) {
            "\"ok\"" -> AccountDirectory.Verification.Ok
            "\"mismatch\"" -> AccountDirectory.Verification.Mismatch
            "\"unknown\"" -> AccountDirectory.Verification.Unknown
            else -> throw IOException("Unexpected response")
        }

    private fun call(function: String, cosmoId: String, verifier: String): String {
        val body = JSONObject().put("p_cosmo_id", cosmoId).put("p_verifier", verifier).toString()
        val request = Request.Builder()
            .url("${config.url.trimEnd('/')}/rest/v1/rpc/$function")
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer ${config.anonKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) throw AccountServerException(describeError(response.code, text))
            return text
        }
    }

    companion object {
        /** Explains a Supabase (PostgREST) error response, e.g. {"code":"PGRST202","message":"Could not find the function …"}. */
        fun describeError(status: Int, body: String): String {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val code = json?.optString("code").orEmpty()
            val message = json?.optString("message")?.takeIf { it.isNotBlank() } ?: body.take(200)
            return when {
                code == "PGRST202" || status == 404 ->
                    "Cosmo's account functions aren't in Supabase yet. Apply the migrations in supabase/migrations (see supabase/README.md)."
                status == 401 || status == 403 || "API key" in message ->
                    "Supabase rejected the app's key. Check supabase.anonKey in local.properties, then rebuild."
                else -> "Cosmo's account server returned an error ($status): $message"
            }
        }
    }
}
