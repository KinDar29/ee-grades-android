package ph.edu.mmsu.ee.grades.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ph.edu.mmsu.ee.grades.ServiceLocator
import ph.edu.mmsu.ee.grades.data.ApiException
import ph.edu.mmsu.ee.grades.data.ExamAttempt
import ph.edu.mmsu.ee.grades.data.ExamReply
import ph.edu.mmsu.ee.grades.data.OfflineException

/** What the exam screen is showing. */
enum class ExamPhase { Loading, Taking, Finished, Failed }

/** The end screen: submitted, locked, time over, or already done. */
data class ExamOutcome(
    val locked: Boolean = false,
    val message: String = "",
    val submittedAt: String = "",
    val score: Double? = null,
    val items: Int = 0,
    val late: Boolean = false
)

/**
 * One exam attempt, same rules as the web portal (Exams.js): the server owns
 * the deadline and the answer key; this keeps the student's answers, saves
 * them to the phone at once and to the server every few seconds, counts
 * exits, and submits.
 */
class ExamViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    var phase by mutableStateOf(ExamPhase.Loading)
        private set
    var attempt by mutableStateOf<ExamAttempt?>(null)
        private set
    val answers = mutableStateListOf<Int?>()
    var current by mutableStateOf(0)
    var outcome by mutableStateOf<ExamOutcome?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Seconds left by the server's clock. */
    var secondsLeft by mutableStateOf(0L)
        private set
    /** Times the student left the exam (never goes down). */
    var away by mutableStateOf(0)
        private set
    /** True while the questions are covered after leaving. */
    var out by mutableStateOf(false)
        private set
    /** Why the cover is up (shown on it). */
    var outReason by mutableStateOf("")
        private set
    var submitting by mutableStateOf(false)
        private set
    /** Last autosave problem, shown quietly; answers are on the phone anyway. */
    var saveNote by mutableStateOf<String?>(null)
        private set

    val images = mutableStateMapOf<Int, Bitmap?>()

    private var sectionId = ""
    private var examId = ""
    private var offset = 0L          // server time - phone time
    private var dirty = false
    private var started = false
    private var ticker: Job? = null
    private var saver: Job? = null

    fun start(section: String, exam: String) {
        if (started) return
        started = true
        sectionId = section
        examId = exam
        load()
    }

    fun load() {
        viewModelScope.launch {
            phase = ExamPhase.Loading
            error = null
            try {
                val a = repo.startExam(sectionId, examId)
                if (a.done) {
                    outcome = ExamOutcome(locked = a.locked, message = a.message)
                    phase = ExamPhase.Finished
                    return@launch
                }
                offset = a.serverNow - System.currentTimeMillis()
                // the phone's copy wins for answers it has (it is the newest)
                val local = repo.loadExamLocal(a.attemptId)
                answers.clear()
                for (i in 0 until a.questions.size) {
                    answers.add(local?.first?.getOrNull(i) ?: a.answers.getOrNull(i))
                }
                away = maxOf(a.away, local?.second ?: 0)
                attempt = a
                phase = ExamPhase.Taking
                startTimers()
            } catch (e: ApiException) {
                error = e.message
                phase = ExamPhase.Failed
            } catch (e: OfflineException) {
                error = e.message
                phase = ExamPhase.Failed
            } catch (e: Exception) {
                error = "Could not open the exam. Please try again."
                phase = ExamPhase.Failed
            }
        }
    }

    private fun startTimers() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive && phase == ExamPhase.Taking) {
                val a = attempt ?: break
                secondsLeft = maxOf(0L, (a.deadline - (System.currentTimeMillis() + offset)) / 1000)
                if (secondsLeft <= 0L) { submit(auto = true); break }
                delay(1000)
            }
        }
        saver?.cancel()
        saver = viewModelScope.launch {
            while (isActive && phase == ExamPhase.Taking) {
                delay(15_000)
                if (dirty) saveNow()
            }
        }
    }

    fun choose(question: Int, option: Int) {
        if (phase != ExamPhase.Taking || out) return
        answers[question] = option
        dirty = true
        val a = attempt ?: return
        viewModelScope.launch { repo.saveExamLocal(a.attemptId, answers.toList(), away) }
    }

    fun saveNow() {
        val a = attempt ?: return
        if (phase != ExamPhase.Taking) return
        dirty = false
        viewModelScope.launch {
            try {
                handleReply(repo.saveExam(a.attemptId, answers.toList(), away), fromSubmit = false)
                saveNote = null
            } catch (e: ApiException) {
                dirty = true
                saveNote = e.message
            } catch (e: Exception) {
                dirty = true
                saveNote = "No connection: your answers are kept on this phone and will be sent when you're back online."
            }
        }
    }

    /** Count one trip away from the exam and cover the questions. */
    fun recordExit(reason: String) {
        if (phase != ExamPhase.Taking || submitting) return
        outReason = reason
        if (out) return
        out = true
        away += 1
        val a = attempt ?: return
        viewModelScope.launch { repo.saveExamLocal(a.attemptId, answers.toList(), away) }
        saveNow()
    }

    /** The student chose to go back to the questions. */
    fun resume() {
        out = false
    }

    fun submit(auto: Boolean = false) {
        val a = attempt ?: return
        if (submitting || phase != ExamPhase.Taking) return
        submitting = true
        viewModelScope.launch {
            try {
                handleReply(repo.submitExam(a.attemptId, answers.toList(), away), fromSubmit = true)
            } catch (e: ApiException) {
                saveNote = e.message
            } catch (e: Exception) {
                saveNote = if (auto) "Time is up. Connect to the internet so your answers can be submitted."
                else "No connection. Your answers are safe on this phone. Try Submit again when you're online."
                if (auto) { delay(10_000); submitting = false; submit(auto = true); return@launch }
            } finally {
                if (phase == ExamPhase.Taking) submitting = false
            }
        }
    }

    private suspend fun handleReply(r: ExamReply, fromSubmit: Boolean) {
        if (r.serverNow > 0) offset = r.serverNow - System.currentTimeMillis()
        if (r.saved) away = maxOf(away, r.away)
        if (r.late && !fromSubmit) { submit(auto = true); return }
        if (r.done || r.locked) finish(r)
    }

    private suspend fun finish(r: ExamReply) {
        attempt?.let { repo.clearExamLocal(it.attemptId) }
        ticker?.cancel()
        saver?.cancel()
        outcome = ExamOutcome(
            locked = r.locked,
            message = r.message,
            submittedAt = r.submittedAt,
            score = if (r.showScore) r.score else null,
            items = r.items,
            late = r.late
        )
        out = false
        phase = ExamPhase.Finished
    }

    fun loadImage(n: Int) {
        if (images.containsKey(n)) return
        val a = attempt ?: return
        images[n] = null
        viewModelScope.launch {
            try {
                val bytes = repo.examImage(a.attemptId, n)
                images[n] = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                images.remove(n)
            }
        }
    }

    val answeredCount: Int get() = answers.count { it != null }
}
