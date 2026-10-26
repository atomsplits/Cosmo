package com.rivers.cosmo.anchor

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** A file for a SEP-12 binary field, such as a photo of an ID. */
class Upload(val bytes: ByteArray, val contentType: String, val fileName: String)

/** Blocking HTTP calls to anchors, with their errors turned into [AnchorException]s. */
class AnchorHttp : Closeable {
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        // Anchors shouldn't bounce sign-in tokens or personal details to another host.
        .followRedirects(false)
        .build()

    // stellar.toml files are public and often sit behind a redirect (e.g. to www.); never to plain http.
    private val publicClient = client.newBuilder().followRedirects(true).followSslRedirects(false).build()

    fun getText(url: String, maxBytes: Int): String = execute(Request.Builder().url(url).build(), host(url), publicClient) { body, _ ->
        if (body.length > maxBytes) throw AnchorException("${host(url)} sent a file that's too large.")
        body
    }

    fun getJson(url: String, token: String? = null): JSONObject =
        json(Request.Builder().url(url).authorized(token).build())

    fun postJson(url: String, payload: JSONObject, token: String? = null): JSONObject =
        json(Request.Builder().url(url).authorized(token).post(payload.toString().toRequestBody(JSON)).build())

    /** SEP-12 wants text fields before files in the form. */
    fun putMultipart(url: String, fields: Map<String, String>, files: Map<String, Upload>, token: String): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
            files.forEach { (name, file) ->
                addFormDataPart(name, file.fileName, file.bytes.toRequestBody(file.contentType.toMediaTypeOrNull()))
            }
        }.build()
        return json(Request.Builder().url(url).authorized(token).put(body).build())
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun json(request: Request): JSONObject = execute(request, request.url.host) { body, _ ->
        try {
            JSONObject(body.ifBlank { "{}" })
        } catch (e: JSONException) {
            throw AnchorException("${request.url.host} sent an unexpected response.", e)
        }
    }

    private fun <T> execute(request: Request, host: String, using: OkHttpClient = client, read: (String, Int) -> T): T {
        try {
            using.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    throw AnchorException(message?.let { "$host: $it" } ?: "$host returned an error (${response.code}).")
                }
                return read(body, response.code)
            }
        } catch (e: IOException) {
            throw AnchorException("Couldn't reach $host. Check your internet connection.", e)
        }
    }

    private fun Request.Builder.authorized(token: String?) = apply { token?.let { header("Authorization", "Bearer $it") } }

    private fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
