package ph.edu.mmsu.ee.grades.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.prefs: DataStore<Preferences> by preferencesDataStore(name = "ee_grades")

/**
 * Everything the app keeps on disk: the session token, and a small JSON cache
 * so screens open instantly and still read when there is no signal.
 *
 * This lives in the app's private storage and is excluded from cloud backup
 * and device transfer (see res/xml/data_extraction_rules.xml). Nothing here
 * is ever sent anywhere except back to your own Apps Script deployment.
 */
class Store(private val context: Context) {

    private val tokenKey = stringPreferencesKey("session_token")

    private suspend fun read(): Preferences =
        context.prefs.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .first()

    /* --------------------------------- token -------------------------------- */

    suspend fun token(): String? = read()[tokenKey]?.takeIf { it.isNotBlank() }

    suspend fun setToken(value: String?) {
        context.prefs.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(tokenKey) else prefs[tokenKey] = value
        }
    }

    /* --------------------------------- cache -------------------------------- */

    /** Stored as "<epochMillis>|<json>" so we can show how old a screen is. */
    suspend fun putCache(key: String, jsonText: String) {
        context.prefs.edit { prefs ->
            prefs[cacheKey(key)] = "${System.currentTimeMillis()}|$jsonText"
        }
    }

    suspend fun getCache(key: String): Pair<Long, String>? {
        val stored = read()[cacheKey(key)] ?: return null
        val separator = stored.indexOf('|')
        if (separator <= 0) return null
        val savedAt = stored.substring(0, separator).toLongOrNull() ?: return null
        return savedAt to stored.substring(separator + 1)
    }

    /** Wipes cached screens but keeps the session. Used when a new person signs in. */
    suspend fun clearCache() {
        val names = read().asMap().keys
            .map { it.name }
            .filter { it.startsWith(CACHE_PREFIX) }
        if (names.isEmpty()) return
        context.prefs.edit { prefs ->
            names.forEach { prefs.remove(stringPreferencesKey(it)) }
        }
    }

    /** Full sign-out: token and every cached screen. */
    suspend fun clearAll() {
        context.prefs.edit { it.clear() }
    }

    private fun cacheKey(key: String) = stringPreferencesKey(CACHE_PREFIX + key)

    private companion object {
        const val CACHE_PREFIX = "cache:"
    }
}
