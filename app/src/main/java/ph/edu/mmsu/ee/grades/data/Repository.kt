package ph.edu.mmsu.ee.grades.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one place the rest of the app talks to.
 *
 * Reads follow the same shape everywhere: the screen asks for the cached copy
 * first so it can paint immediately, then asks for a fresh copy. If the fresh
 * copy fails because the phone is offline, the cached one stays on screen with
 * a note saying how old it is.
 */
class Repository(
    val api: ApiClient,
    private val store: Store
) {

    /* ------------------------------- session -------------------------------- */

    suspend fun currentToken(): String? = store.token()

    suspend fun hasSession(): Boolean = store.token() != null

    private suspend fun requireToken(): String =
        store.token() ?: throw ApiException("Please sign in.", authRequired = true)

    suspend fun login(role: String, username: String, password: String): LoginResult {
        val data = api.call(
            action = "login",
            payload = buildJsonObject {
                put("role", role)
                put("username", username)
                put("password", password)
            }
        )
        val result = api.json.decodeFromJsonElement(LoginResult.serializer(), data)
        // Clear first: a shared phone must never show the previous person's grades.
        store.clearCache()
        store.setToken(result.token)
        saveProfile(result.profile)
        return result
    }

    suspend fun session(): SessionInfo {
        val data = api.call("session", token = requireToken())
        val info = api.json.decodeFromJsonElement(SessionInfo.serializer(), data)
        saveProfile(info.profile)
        return info
    }

    /**
     * The last profile we saw, so the app can open straight into the right
     * screens when it starts with no signal.
     */
    suspend fun cachedProfile(): Profile? = readCache(KEY_PROFILE, Profile.serializer())?.value

    private suspend fun saveProfile(profile: Profile) {
        store.putCache(KEY_PROFILE, api.json.encodeToString(Profile.serializer(), profile))
    }

    suspend fun portal(): PortalInfo {
        val data = api.call("portal")
        return api.json.decodeFromJsonElement(PortalInfo.serializer(), data)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val data = api.call(
            action = "changePassword",
            payload = buildJsonObject {
                put("currentPassword", currentPassword)
                put("newPassword", newPassword)
            },
            token = requireToken()
        )
        val issued = api.json.decodeFromJsonElement(TokenOnly.serializer(), data)
        // The server invalidates the old token the moment the hash changes, so
        // adopting the replacement immediately is what keeps the session alive.
        if (issued.token.isNotBlank()) store.setToken(issued.token)
    }

    suspend fun signOut() = store.clearAll()

    /* -------------------------------- student ------------------------------- */

    suspend fun cachedStudentHome(): Cached<StudentHome>? =
        readCache(KEY_STUDENT_HOME, StudentHome.serializer())

    suspend fun studentHome(): Cached<StudentHome> =
        fetch("student.home", JsonObject(emptyMap()), KEY_STUDENT_HOME, StudentHome.serializer())

    suspend fun cachedStudentClass(sectionId: String): Cached<StudentClass>? =
        readCache(keyStudentClass(sectionId), StudentClass.serializer())

    suspend fun studentClass(sectionId: String): Cached<StudentClass> =
        fetch(
            action = "student.class",
            payload = buildJsonObject { put("sectionId", sectionId) },
            cacheKey = keyStudentClass(sectionId),
            serializer = StudentClass.serializer()
        )

    /* --------------------------------- admin -------------------------------- */

    suspend fun cachedAdminHome(semesterId: String): Cached<AdminHome>? =
        readCache(keyAdminHome(semesterId), AdminHome.serializer())

    suspend fun adminHome(semesterId: String): Cached<AdminHome> =
        fetch(
            action = "admin.home",
            payload = buildJsonObject { if (semesterId.isNotBlank()) put("semesterId", semesterId) },
            cacheKey = keyAdminHome(semesterId),
            serializer = AdminHome.serializer()
        )

    suspend fun cachedAdminSection(sectionId: String): Cached<AdminSectionData>? =
        readCache(keyAdminSection(sectionId), AdminSectionData.serializer())

    suspend fun adminSection(sectionId: String): Cached<AdminSectionData> =
        fetch(
            action = "admin.section.data",
            payload = buildJsonObject { put("sectionId", sectionId) },
            cacheKey = keyAdminSection(sectionId),
            serializer = AdminSectionData.serializer()
        )

    /* ------------------------------- internals ------------------------------ */

    private suspend fun <T> fetch(
        action: String,
        payload: JsonObject,
        cacheKey: String,
        serializer: KSerializer<T>
    ): Cached<T> {
        val data = api.call(action, payload, requireToken())
        val value = api.json.decodeFromJsonElement(serializer, data)
        store.putCache(cacheKey, data.toString())
        return Cached(value, System.currentTimeMillis(), fromCache = false)
    }

    private suspend fun <T> readCache(key: String, serializer: KSerializer<T>): Cached<T>? {
        val (savedAt, text) = store.getCache(key) ?: return null
        return try {
            Cached(api.json.decodeFromString(serializer, text), savedAt, fromCache = true)
        } catch (e: Exception) {
            null // A cache we can no longer read is simply not a cache.
        }
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_STUDENT_HOME = "student.home"
        fun keyStudentClass(id: String) = "student.class:$id"
        fun keyAdminHome(id: String) = "admin.home:$id"
        fun keyAdminSection(id: String) = "admin.section:$id"
    }
}
