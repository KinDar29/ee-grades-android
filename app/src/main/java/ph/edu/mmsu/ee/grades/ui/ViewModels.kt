package ph.edu.mmsu.ee.grades.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ph.edu.mmsu.ee.grades.ServiceLocator
import ph.edu.mmsu.ee.grades.data.AdminHome
import ph.edu.mmsu.ee.grades.data.AdminSectionData
import ph.edu.mmsu.ee.grades.data.ApiException
import ph.edu.mmsu.ee.grades.data.LoginResult
import ph.edu.mmsu.ee.grades.data.OfflineException
import ph.edu.mmsu.ee.grades.data.PortalInfo
import ph.edu.mmsu.ee.grades.data.Profile
import ph.edu.mmsu.ee.grades.data.StudentClass
import ph.edu.mmsu.ee.grades.data.StudentHome

enum class AuthPhase { Checking, SignedOut, MustChangePassword, Ready }

/**
 * Owns the question "who is using this app right now", and nothing else.
 * Screens never decide to sign someone out; they report that the server
 * rejected their token and this class does it.
 */
class AppViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var phase by mutableStateOf(AuthPhase.Checking)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    init {
        bootstrap()
    }

    fun bootstrap() {
        viewModelScope.launch {
            phase = AuthPhase.Checking
            notice = null

            if (!repo.hasSession()) {
                phase = AuthPhase.SignedOut
                return@launch
            }

            try {
                val session = repo.session()
                profile = session.profile
                phase = if (session.mustChangePassword) {
                    AuthPhase.MustChangePassword
                } else {
                    AuthPhase.Ready
                }
            } catch (e: ApiException) {
                if (e.authRequired) {
                    repo.signOut()
                    profile = null
                    phase = AuthPhase.SignedOut
                    notice = e.message
                } else {
                    phase = AuthPhase.SignedOut
                    notice = e.message
                }
            } catch (e: OfflineException) {
                // A stored token plus no signal is not a reason to lock someone
                // out of grades they already downloaded. Go in on the cached
                // profile; individual screens will show their own offline note.
                val remembered = repo.cachedProfile()
                if (remembered != null) {
                    profile = remembered
                    phase = AuthPhase.Ready
                } else {
                    phase = AuthPhase.SignedOut
                    notice = e.message
                }
            }
        }
    }

    fun onSignedIn(result: LoginResult) {
        profile = result.profile
        notice = null
        phase = if (result.mustChangePassword) AuthPhase.MustChangePassword else AuthPhase.Ready
    }

    fun onPasswordChanged() {
        phase = AuthPhase.Ready
    }

    fun signOut(message: String? = null) {
        viewModelScope.launch {
            repo.signOut()
            profile = null
            notice = message
            phase = AuthPhase.SignedOut
        }
    }
}

/* ---------------------------------- login --------------------------------- */

class LoginViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    /**
     * Backed privately so the only way to change it is setRole(), which also
     * clears the error. A public `var role` would additionally compile to a
     * JVM setter named setRole(String) and clash with that function.
     */
    private var _role by mutableStateOf("student")
    val role: String get() = _role

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var showPassword by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var portal by mutableStateOf<PortalInfo?>(null)
        private set

    /** A green note after a successful password reset. */
    var success by mutableStateOf<String?>(null)
        private set

    /** Called when the forgot-password screen finishes. */
    fun onPasswordReset(forUser: String) {
        username = forUser
        password = ""
        error = null
        success = "Your password has been changed. Sign in with your new password."
    }

    init {
        viewModelScope.launch {
            portal = try {
                repo.portal()
            } catch (e: Exception) {
                null // The banner is decoration; never block sign-in on it.
            }
        }
    }

    fun setRole(next: String) {
        if (_role == next) return
        _role = next
        error = null
    }

    fun submit(onSuccess: (LoginResult) -> Unit) {
        if (busy) return
        if (username.isBlank() || password.isBlank()) {
            error = "Enter both fields to continue."
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            success = null
            try {
                onSuccess(repo.login(role, username.trim(), password))
            } catch (e: ApiException) {
                error = e.message
            } catch (e: OfflineException) {
                error = e.message
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                busy = false
            }
        }
    }
}

/* ----------------------------- change password ---------------------------- */

class ChangePasswordViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var current by mutableStateOf("")
    var next by mutableStateOf("")
    var confirm by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit(onDone: () -> Unit) {
        if (busy) return
        if (next != confirm) {
            error = "The two new passwords do not match."
            return
        }
        if (next.length < 6) {
            error = "Your new password must be at least 6 characters."
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            try {
                repo.changePassword(current, next)
                onDone()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: OfflineException) {
                error = e.message
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                busy = false
            }
        }
    }
}

/* ----------------------------- forgot password ---------------------------- */

class ForgotPasswordViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var username by mutableStateOf("")
    var code by mutableStateOf("")
    var next by mutableStateOf("")
    var confirm by mutableStateOf("")

    /** False while asking for the account; true once a code is (or may be) on its way. */
    var codeStep by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set

    private var prefilled = false

    fun prefill(user: String) {
        if (prefilled) return
        prefilled = true
        username = user
    }

    /** Clear everything so the next visit starts fresh. */
    fun reset() {
        prefilled = false
        username = ""
        code = ""
        next = ""
        confirm = ""
        codeStep = false
        error = null
        info = null
    }

    fun haveCode() {
        error = null
        info = null
        codeStep = true
    }

    fun sendCode(role: String) {
        if (busy) return
        if (username.isBlank()) {
            error = if (role == "admin") "Enter your e-mail address." else "Enter your student number."
            return
        }
        launchCall {
            info = repo.requestPasswordReset(role, username.trim())
            codeStep = true
        }
    }

    fun submit(role: String, onDone: (String) -> Unit) {
        if (busy) return
        val cleanCode = code.filter { it.isDigit() }
        when {
            username.isBlank() -> { error = "Enter your account first."; return }
            cleanCode.length != 6 -> { error = "Enter the 6-digit code from the e-mail."; return }
            next != confirm -> { error = "The two new passwords do not match."; return }
            next.length < 6 -> { error = "Your new password must be at least 6 characters."; return }
        }
        launchCall {
            repo.resetPassword(role, username.trim(), cleanCode, next)
            onDone(username.trim())
        }
    }

    private fun launchCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: OfflineException) {
                error = e.message
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                busy = false
            }
        }
    }
}

/* ------------------------------- student home ----------------------------- */

class HomeViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var data by mutableStateOf<StudentHome?>(null)
        private set
    var savedAt by mutableStateOf(0L)
        private set
    var showingCache by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var authLost by mutableStateOf<String?>(null)
        private set

    var semesterFilter by mutableStateOf("")

    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            repo.cachedStudentHome()?.let { cached ->
                data = cached.value
                savedAt = cached.savedAt
                showingCache = true
                if (semesterFilter.isBlank()) semesterFilter = cached.value.activeSemesterId
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val fresh = repo.studentHome()
                data = fresh.value
                savedAt = fresh.savedAt
                showingCache = false
                if (semesterFilter.isBlank()) semesterFilter = fresh.value.activeSemesterId
            } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else error = e.message
            } catch (e: OfflineException) {
                if (data == null) error = e.message else showingCache = true
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                loading = false
            }
        }
    }
}

/* ------------------------------- class detail ----------------------------- */

class ClassViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var data by mutableStateOf<StudentClass?>(null)
        private set
    var savedAt by mutableStateOf(0L)
        private set
    var showingCache by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var authLost by mutableStateOf<String?>(null)
        private set

    /** Kind of the course file being downloaded, for the button's spinner. */
    var openingFile by mutableStateOf<String?>(null)
        private set

    private var sectionId: String = ""
    private var started = false

    fun openFile(kind: String, dir: java.io.File, onReady: (java.io.File) -> Unit, onError: (String) -> Unit) {
        if (openingFile != null) return
        viewModelScope.launch {
            openingFile = kind
            try {
                onReady(repo.downloadCourseFile(sectionId, kind, dir))
            } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else onError(e.message ?: "Could not open the file.")
            } catch (e: OfflineException) {
                onError(e.message ?: "Could not reach the portal.")
            } catch (e: Exception) {
                onError("Could not open the file.")
            } finally {
                openingFile = null
            }
        }
    }

    fun start(id: String) {
        if (started) return
        started = true
        sectionId = id
        viewModelScope.launch {
            repo.cachedStudentClass(id)?.let { cached ->
                data = cached.value
                savedAt = cached.savedAt
                showingCache = true
            }
            refresh()
        }
    }

    fun refresh() {
        if (sectionId.isBlank()) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val fresh = repo.studentClass(sectionId)
                data = fresh.value
                savedAt = fresh.savedAt
                showingCache = false
            } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else error = e.message
            } catch (e: OfflineException) {
                if (data == null) error = e.message else showingCache = true
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                loading = false
            }
        }
    }
}

/* -------------------------------- admin home ------------------------------ */

class AdminHomeViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var data by mutableStateOf<AdminHome?>(null)
        private set
    var savedAt by mutableStateOf(0L)
        private set
    var showingCache by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var authLost by mutableStateOf<String?>(null)
        private set

    private var semesterId = ""
    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            repo.cachedAdminHome(semesterId)?.let { cached ->
                data = cached.value
                savedAt = cached.savedAt
                showingCache = true
            }
            refresh()
        }
    }

    fun selectSemester(id: String) {
        if (semesterId == id) return
        semesterId = id
        viewModelScope.launch {
            repo.cachedAdminHome(id)?.let { cached ->
                data = cached.value
                savedAt = cached.savedAt
                showingCache = true
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val fresh = repo.adminHome(semesterId)
                data = fresh.value
                savedAt = fresh.savedAt
                showingCache = false
                if (semesterId.isBlank()) semesterId = fresh.value.semesterId
            } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else error = e.message
            } catch (e: OfflineException) {
                if (data == null) error = e.message else showingCache = true
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                loading = false
            }
        }
    }
}

/* ------------------------------ admin section ----------------------------- */

class AdminSectionViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var data by mutableStateOf<AdminSectionData?>(null)
        private set
    var savedAt by mutableStateOf(0L)
        private set
    var showingCache by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var authLost by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")

    private var sectionId = ""
    private var started = false

    fun start(id: String) {
        if (started) return
        started = true
        sectionId = id
        viewModelScope.launch {
            repo.cachedAdminSection(id)?.let { cached ->
                data = cached.value
                savedAt = cached.savedAt
                showingCache = true
            }
            refresh()
        }
    }

    fun refresh() {
        if (sectionId.isBlank()) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val fresh = repo.adminSection(sectionId)
                data = fresh.value
                savedAt = fresh.savedAt
                showingCache = false
            } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else error = e.message
            } catch (e: OfflineException) {
                if (data == null) error = e.message else showingCache = true
            } catch (e: Exception) {
                error = "Something went wrong. Please try again."
            } finally {
                loading = false
            }
        }
    }
}
