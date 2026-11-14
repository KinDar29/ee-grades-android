package ph.edu.mmsu.ee.grades.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    /**
     * Step 1 of "forgot password": the server e-mails a 6-digit code to the
     * address on file. The reply is deliberately the same whether or not the
     * account exists, so it is shown to the person as-is.
     */
    suspend fun requestPasswordReset(role: String, username: String): String {
        val data = api.call(
            action = "forgotPassword",
            payload = buildJsonObject {
                put("role", role)
                put("username", username)
            }
        )
        return api.json.decodeFromJsonElement(MessageOnly.serializer(), data).message
    }

    /** Step 2: trade the code for a new password. The person then signs in normally. */
    suspend fun resetPassword(role: String, username: String, code: String, newPassword: String) {
        api.call(
            action = "resetPassword",
            payload = buildJsonObject {
                put("role", role)
                put("username", username)
                put("code", code)
                put("newPassword", newPassword)
            }
        )
    }

    /* ----------------------------- registration ----------------------------- */

    suspend fun registrationOptions(): RegistrationOptions {
        val data = api.call("register.options")
        return api.json.decodeFromJsonElement(RegistrationOptions.serializer(), data)
    }

    /** Registration step 1: checks the form and e-mails a code. Nothing is saved yet. */
    suspend fun registrationStart(
        studentId: String, email: String, lastName: String, firstName: String, middleName: String,
        program: String, yearLevel: String, password: String, sectionIds: List<String>
    ): EmailCodeSent {
        val data = api.call(
            action = "register.start",
            payload = buildJsonObject {
                put("studentId", studentId)
                put("email", email)
                put("lastName", lastName)
                put("firstName", firstName)
                put("middleName", middleName)
                put("program", program)
                put("yearLevel", yearLevel)
                put("password", password)
                put("sectionIds", kotlinx.serialization.json.JsonArray(sectionIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        )
        return api.json.decodeFromJsonElement(EmailCodeSent.serializer(), data)
    }

    /** Registration step 2: the code creates the account and enrols the student. */
    suspend fun registrationVerify(studentId: String, code: String): RegistrationDone {
        val data = api.call(
            action = "register.verify",
            payload = buildJsonObject {
                put("studentId", studentId)
                put("code", code)
            }
        )
        return api.json.decodeFromJsonElement(RegistrationDone.serializer(), data)
    }

    /** Confirm-e-mail step 1: e-mail a 6-digit code to [email]. */
    suspend fun sendEmailCode(email: String): EmailCodeSent {
        val data = api.call(
            action = "account.email.send",
            payload = buildJsonObject { put("email", email) },
            token = requireToken()
        )
        return api.json.decodeFromJsonElement(EmailCodeSent.serializer(), data)
    }

    /** Confirm-e-mail step 2: the code from the e-mail. Returns the updated profile. */
    suspend fun verifyEmailCode(code: String): Profile {
        val data = api.call(
            action = "account.email.verify",
            payload = buildJsonObject { put("code", code) },
            token = requireToken()
        )
        val result = api.json.decodeFromJsonElement(EmailVerified.serializer(), data)
        SessionSignals.emailNeeded.value = false
        saveProfile(result.profile)
        return result.profile
    }

    suspend fun signOut() {
        SessionSignals.emailNeeded.value = false
        store.clearAll()
    }

    /* -------------------------------- student ------------------------------- */

    suspend fun cachedStudentHome(): Cached<StudentHome>? =
        readCache(KEY_STUDENT_HOME, StudentHome.serializer())

    suspend fun studentHome(): Cached<StudentHome> =
        fetch("student.home", JsonObject(emptyMap()), KEY_STUDENT_HOME, StudentHome.serializer())

    suspend fun cachedStudentClass(sectionId: String): Cached<StudentClass>? =
        readCache(keyStudentClass(sectionId), StudentClass.serializer())

    suspend fun studentClass(sectionId: String): Cached<StudentClass> {
        val result = fetch(
            action = "student.class",
            payload = buildJsonObject { put("sectionId", sectionId) },
            cacheKey = keyStudentClass(sectionId),
            serializer = StudentClass.serializer()
        )
        // The student is looking at these scores now, so don't notify about them later.
        loadBaseline()?.let { base ->
            result.value.items.forEach { item ->
                item.score?.let { base[scoreKey(sectionId, item.id)] = it }
            }
            saveBaseline(base)
        }
        return result
    }

    /**
     * Downloads a course file into [cacheDir] and returns it. Problem sets
     * (downloadable) go to course-files/, which the FileProvider shares with
     * the phone's PDF viewer. Everything else goes to view-only/, which no
     * other app can reach, and is shown by the in-app viewer.
     * Files are not cached between visits: the admin may replace them.
     */
    suspend fun downloadCourseFile(sectionId: String, kind: String, cacheDir: java.io.File): DownloadedFile {
        val data = api.call(
            action = "student.file",
            payload = buildJsonObject {
                put("sectionId", sectionId)
                put("kind", kind)
            },
            token = requireToken()
        )
        val file = api.json.decodeFromJsonElement(CourseFileData.serializer(), data)
        val bytes = java.util.Base64.getDecoder().decode(file.data)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(cacheDir, if (file.downloadable) "course-files" else "view-only")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }   // keep only the latest one
            val safe = file.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "${file.kind}.pdf" }
            val out = java.io.File(dir, if (safe.endsWith(".pdf", true)) safe else "$safe.pdf").apply { writeBytes(bytes) }
            DownloadedFile(out, file.downloadable)
        }
    }

    /* ------------------------------ admin calls ----------------------------- */

    /** Decoder shared with the admin screens. */
    val json get() = api.json

    /** Any admin action of the portal router, answered as raw JSON. */
    suspend fun adminCall(action: String, payload: JsonObject): kotlinx.serialization.json.JsonElement =
        api.call(action, payload, requireToken())

    /** A PDF from an admin action (`admin.submission`, `admin.material`) saved under cacheDir/view-only. */
    suspend fun adminPdf(action: String, payload: JsonObject, cacheDir: java.io.File): java.io.File {
        val file = api.json.decodeFromJsonElement(CourseFileData.serializer(), adminCall(action, payload))
        val bytes = java.util.Base64.getDecoder().decode(file.data)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(cacheDir, "view-only").apply { mkdirs() }
            java.io.File(dir, "admin-" + System.nanoTime() + ".pdf").apply { writeBytes(bytes) }
        }
    }

    /* ----------------------------- problem sets ----------------------------- */

    /** Uploads a PDF answer; replaces the previous one until the due time. */
    suspend fun submitProblemSet(sectionId: String, assignmentId: String, name: String, bytes: ByteArray): SubmitResult {
        val data = api.call(
            action = "student.assignment.submit",
            payload = buildJsonObject {
                put("sectionId", sectionId)
                put("assignmentId", assignmentId)
                put("name", name)
                put("mimeType", "application/pdf")
                put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            },
            token = requireToken()
        )
        return api.json.decodeFromJsonElement(SubmitResult.serializer(), data)
    }

    /** The student's own uploaded answer, saved to cacheDir/course-files/ (their own work, shareable). */
    suspend fun downloadMySubmission(sectionId: String, assignmentId: String, cacheDir: java.io.File): DownloadedFile {
        val data = api.call(
            action = "student.submission",
            payload = buildJsonObject {
                put("sectionId", sectionId)
                put("assignmentId", assignmentId)
            },
            token = requireToken()
        )
        val file = api.json.decodeFromJsonElement(CourseFileData.serializer(), data)
        val bytes = java.util.Base64.getDecoder().decode(file.data)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(cacheDir, "course-files")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val safe = file.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "answer.pdf" }
            val out = java.io.File(dir, if (safe.endsWith(".pdf", true)) safe else "$safe.pdf").apply { writeBytes(bytes) }
            DownloadedFile(out, true)
        }
    }

    /* --------------------------------- exams -------------------------------- */

    suspend fun startExam(sectionId: String, examId: String): ExamAttempt {
        val data = api.call(
            action = "student.exam.start",
            payload = buildJsonObject {
                put("sectionId", sectionId)
                put("examId", examId)
            },
            token = requireToken()
        )
        return api.json.decodeFromJsonElement(ExamAttempt.serializer(), data)
    }

    private fun answersJson(answers: List<Int?>) = kotlinx.serialization.json.JsonArray(
        answers.map { if (it == null) kotlinx.serialization.json.JsonNull else kotlinx.serialization.json.JsonPrimitive(it) }
    )

    /** Autosave (server cache only). The reply may say the attempt was locked or is over. */
    suspend fun saveExam(attemptId: String, answers: List<Int?>, away: Int): ExamReply {
        val data = api.call(
            action = "student.exam.save",
            payload = buildJsonObject {
                put("attemptId", attemptId)
                put("answers", answersJson(answers))
                put("away", away)
            },
            token = requireToken()
        )
        return api.json.decodeFromJsonElement(ExamReply.serializer(), data)
    }

    suspend fun submitExam(attemptId: String, answers: List<Int?>, away: Int): ExamReply {
        val data = api.call(
            action = "student.exam.submit",
            payload = buildJsonObject {
                put("attemptId", attemptId)
                put("answers", answersJson(answers))
                put("away", away)
            },
            token = requireToken()
        )
        return api.json.decodeFromJsonElement(ExamReply.serializer(), data)
    }

    suspend fun examImage(attemptId: String, n: Int): ByteArray {
        val data = api.call(
            action = "student.exam.image",
            payload = buildJsonObject {
                put("attemptId", attemptId)
                put("n", n)
            },
            token = requireToken()
        )
        val img = api.json.decodeFromJsonElement(ExamImage.serializer(), data)
        return java.util.Base64.getDecoder().decode(img.data)
    }

    /** Answers + exit count kept on the phone too, so a crash or dead battery loses nothing. */
    suspend fun saveExamLocal(attemptId: String, answers: List<Int?>, away: Int) {
        val obj = buildJsonObject {
            put("a", answersJson(answers))
            put("away", away)
        }
        store.putCache("exam:$attemptId", obj.toString())
    }

    suspend fun loadExamLocal(attemptId: String): Pair<List<Int?>, Int>? {
        val raw = store.getCache("exam:$attemptId")?.second ?: return null
        return try {
            val o = api.json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
            val a = (o["a"] as kotlinx.serialization.json.JsonArray).map {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            }
            val away = (o["away"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            a to away
        } catch (e: Exception) { null }
    }

    suspend fun clearExamLocal(attemptId: String) = store.putCache("exam:$attemptId", "")

    /* ------------------------------ new scores ------------------------------ */
    /*  The phone remembers every score it has already told the student about
        ("<sectionId>|<assessmentId>" -> score). The first check after sign-in
        only records that baseline, so nobody gets a notification for scores
        they already had.                                                      */

    private val baselineSerializer = MapSerializer(String.serializer(), Double.serializer())

    private suspend fun loadBaseline(): MutableMap<String, Double>? {
        val (_, text) = store.getCache(KEY_SCORE_BASELINE) ?: return null
        return try {
            api.json.decodeFromString(baselineSerializer, text).toMutableMap()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveBaseline(map: Map<String, Double>) {
        store.putCache(KEY_SCORE_BASELINE, api.json.encodeToString(baselineSerializer, map))
    }

    /**
     * Asks the portal for the student's scores and returns the ones that are
     * new or changed since the last check. Updates the baseline either way.
     */
    suspend fun findNewScores(): List<NewScore> {
        val data = api.call("student.updates", token = requireToken())
        val updates = api.json.decodeFromJsonElement(ScoreUpdates.serializer(), data)
        val before = loadBaseline()

        // Keep what we knew about classes the server could not read this time,
        // so they don't all look "new" once it can again.
        val reported = updates.classes.map { it.id }.toSet()
        val next = mutableMapOf<String, Double>()
        before?.forEach { (key, value) ->
            if (key.substringBefore('|') !in reported) next[key] = value
        }
        updates.scores.forEach { next[scoreKey(it.sectionId, it.id)] = it.score }
        saveBaseline(next)

        if (before == null) return emptyList()
        val labels = updates.classes.associate { it.id to it.label }
        return updates.scores
            .filter { before[scoreKey(it.sectionId, it.id)] != it.score }
            .map { NewScore(it.sectionId, labels[it.sectionId].orEmpty(), it.name, it.score, it.max) }
    }

    /** Records the starting point right after sign-in. Never throws. */
    suspend fun ensureScoreBaseline() {
        if (loadBaseline() != null) return
        try {
            findNewScores()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The background check will set it up later.
        }
    }

    suspend fun askedForNotifications(): Boolean = store.getCache(KEY_ASKED_NOTIFICATIONS) != null

    suspend fun markAskedForNotifications() = store.putCache(KEY_ASKED_NOTIFICATIONS, "1")

    private fun scoreKey(sectionId: String, itemId: String) = "$sectionId|$itemId"

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
        const val KEY_SCORE_BASELINE = "scores.notified"
        const val KEY_ASKED_NOTIFICATIONS = "notifications.asked"
        fun keyStudentClass(id: String) = "student.class:$id"
        fun keyAdminHome(id: String) = "admin.home:$id"
        fun keyAdminSection(id: String) = "admin.section:$id"
    }
}
