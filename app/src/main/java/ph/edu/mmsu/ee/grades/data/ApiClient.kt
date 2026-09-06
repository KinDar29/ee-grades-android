package ph.edu.mmsu.ee.grades.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An error the server chose to report. [authRequired] mirrors the flag Api.gs
 * sets when a token has expired or an account was deactivated — the app clears
 * the session and returns to the sign-in screen when it sees it.
 */
class ApiException(
    message: String,
    val authRequired: Boolean = false
) : Exception(message)

/** A network or transport failure, as opposed to a rejection by the server. */
class OfflineException(message: String) : IOException(message)

/**
 * Talks to the Apps Script web app.
 *
 * Every request is a POST of {action, payload, token} to the single /exec URL,
 * which Mobile.gs hands to the same api() router the web portal uses.
 *
 * Two details worth knowing:
 *  - Apps Script answers /exec with a 302 to script.googleusercontent.com.
 *    OkHttp follows it automatically; do not turn redirects off.
 *  - The body goes out as text/plain. Apps Script reads e.postData.contents
 *    regardless of type, and text/plain keeps the request simple.
 */
class ApiClient(private val baseUrl: String) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // Sheets can be slow; be patient
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val plainText = "text/plain; charset=utf-8".toMediaType()

    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") && !baseUrl.contains("PASTE_YOUR_DEPLOYMENT_ID")

    /**
     * Performs one call and returns the `data` half of the envelope.
     * Throws [ApiException] when the server said no, [OfflineException] when
     * we could not reach it at all.
     */
    suspend fun call(
        action: String,
        payload: JsonObject = JsonObject(emptyMap()),
        token: String? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw ApiException(
                "This build has no server address. Rebuild with your Apps Script " +
                    "deployment URL set as EE_API_URL."
            )
        }

        val requestBody = buildJsonObject {
            put("action", action)
            put("payload", payload)
            if (!token.isNullOrEmpty()) put("token", token)
        }.toString()

        val request = Request.Builder()
            .url(baseUrl)
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(plainText))
            .build()

        val raw: String = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw OfflineException(
                        "The server replied ${response.code}. If this keeps happening, " +
                            "the web app may need to be redeployed."
                    )
                }
                text
            }
        } catch (e: OfflineException) {
            throw e
        } catch (e: IOException) {
            throw OfflineException("Could not reach the portal. Check your connection.")
        }

        parseEnvelope(raw)
    }

    private fun parseEnvelope(raw: String): JsonElement {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            // Almost always an HTML sign-in or error page from Google, which
            // means the deployment is not set to "Anyone" access.
            throw OfflineException(
                "The server sent a page instead of data. Check that the web app is " +
                    "deployed with access set to \"Anyone\"."
            )
        }

        val ok = root["ok"]?.jsonPrimitive?.content == "true"
        if (ok) {
            return root["data"] ?: JsonObject(emptyMap())
        }

        val message = root["error"]?.jsonPrimitive?.content ?: "Something went wrong."
        val authRequired = root["authRequired"]?.jsonPrimitive?.content == "true"
        throw ApiException(message, authRequired)
    }
}
