package com.rivers.cosmo.chat

import com.rivers.cosmo.auth.AccountServerException
import com.rivers.cosmo.auth.SupabaseAccountDirectory
import com.rivers.cosmo.auth.SupabaseConfig
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat public keys kept in Supabase (supabase/migrations/…_public_keys.sql), so a contact's key
 * can be found even when they aren't connected to the chat server.
 */
class SupabaseKeyDirectory(private val config: SupabaseConfig) : ChatKeyServer {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun fetch(cosmoId: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${base()}/rest/v1/public_keys?select=armored_public_key&cosmo_id=eq.${URLEncoder.encode(cosmoId, "UTF-8")}")
            .supabaseHeaders()
            .get()
            .build()
        val rows = JSONArray(execute(request))
        if (rows.length() == 0) null else rows.getJSONObject(0).getString("armored_public_key")
    }

    override suspend fun publish(cosmoId: String, verifier: String, fingerprint: String, armoredKey: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("p_cosmo_id", cosmoId)
                .put("p_verifier", verifier)
                .put("p_fingerprint", fingerprint)
                .put("p_armored_key", armoredKey)
                .toString()
            val request = Request.Builder()
                .url("${base()}/rest/v1/rpc/publish_public_key")
                .supabaseHeaders()
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            execute(request).toBooleanStrictOrNull() == true
        }

    private fun base() = config.url.trimEnd('/')

    private fun Request.Builder.supabaseHeaders() =
        header("apikey", config.anonKey).header("Authorization", "Bearer ${config.anonKey}")

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) throw AccountServerException(SupabaseAccountDirectory.describeError(response.code, text))
            text
        }
}
