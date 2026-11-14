package ph.edu.mmsu.ee.grades.data

import kotlinx.serialization.Serializable

/*
 * These mirror the objects the Apps Script backend already returns.
 * Every field carries a default, and the parser is configured with
 * ignoreUnknownKeys + coerceInputValues, so the app keeps working if the
 * server grows a field, drops one, or sends null where a value was expected.
 */

@Serializable
data class Profile(
    val role: String = "student",
    val id: String = "",
    val name: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val program: String = "",
    val yearLevel: String = "",
    val email: String = "",
    val emailVerified: Boolean = false,
    val adminRole: String = ""
) {
    val isAdmin: Boolean get() = role == "admin"

    /** "AB" from "Ana Bautista" — used for the avatar bubble. */
    val initials: String
        get() {
            val source = if (name.isNotBlank()) name else id
            val parts = source.trim().split(" ").filter { it.isNotBlank() }
            return when {
                parts.isEmpty() -> "?"
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
            }
        }
}

@Serializable
data class Semester(
    val id: String = "",
    val name: String = "",
    val schoolYear: String = "",
    val term: String = "",
    val active: Boolean = false
)

/** One class. Used for a student's enrolled classes and an admin's section list. */
@Serializable
data class ClassSummary(
    val id: String = "",
    val name: String = "",
    val subjectId: String = "",
    val subjectCode: String = "",
    val subjectTitle: String = "",
    val units: Double? = null,
    val semesterId: String = "",
    val semesterName: String = "",
    val instructor: String = "",
    val schedule: String = "",
    val room: String = "",
    val status: String = "Active",
    val enrolled: Int = 0,
    val lastSynced: String = ""
)

/** The trimmed section object returned inside student.class. */
@Serializable
data class ClassSection(
    val id: String = "",
    val name: String = "",
    val subjectCode: String = "",
    val subjectTitle: String = "",
    val units: Double? = null,
    val semesterName: String = "",
    val instructor: String = "",
    val schedule: String = "",
    val room: String = ""
)

/**
 * Output of computeGrade_. earned/possible/percent/grade are null when the
 * administrator has hidden running grades, or when nothing is recorded yet.
 */
@Serializable
data class Grade(
    val mode: String = "points",
    val earned: Double? = null,
    val possible: Double? = null,
    val percent: Double? = null,
    val grade: Double? = null,
    val remark: String = "",
    val recorded: Int = 0,
    val items: Int = 0,
    val weightCovered: Double? = null,
    val weightTotal: Double? = null
) {
    val hasResult: Boolean get() = percent != null
}

/** One assessment as a student sees it: their score plus optional class stats. */
@Serializable
data class ClassItem(
    val id: String = "",
    val name: String = "",
    val type: String = "Other",
    val max: Double = 100.0,
    val weight: Double = 0.0,
    val date: String = "",
    val score: Double? = null,
    val percent: Double? = null,
    val classAverage: Double? = null,
    val classHigh: Double? = null,
    val recordedCount: Int? = null
) {
    val isRecorded: Boolean get() = score != null
}

@Serializable
data class LoginResult(
    val token: String = "",
    val profile: Profile = Profile(),
    val mustChangePassword: Boolean = false,
    val mustVerifyEmail: Boolean = false
)

@Serializable
data class SessionInfo(
    val profile: Profile = Profile(),
    val mustChangePassword: Boolean = false,
    val mustVerifyEmail: Boolean = false
)

/** `account.email.send` reply: where the code went (masked) and how long it lasts. */
@Serializable
data class EmailCodeSent(
    val sentTo: String = "",
    val minutes: Int = 30
)

/** `account.email.verify` reply. */
@Serializable
data class EmailVerified(
    val done: Boolean = false,
    val profile: Profile = Profile()
)

@Serializable
data class TokenOnly(
    val token: String = ""
)

/* ------------------------------ new scores ------------------------------ */

/** Reply to "student.updates": every published score the student has. */
@Serializable
data class ScoreUpdates(
    val classes: List<UpdateClass> = emptyList(),
    val scores: List<ScoreEntry> = emptyList(),
    val failed: Int = 0
)

@Serializable
data class UpdateClass(
    val id: String = "",
    val subjectCode: String = "",
    val subjectTitle: String = "",
    val name: String = ""
) {
    /** "EE 124" if there is a code, else the title, else the section name. */
    val label: String
        get() = subjectCode.ifBlank { subjectTitle.ifBlank { name } }
}

@Serializable
data class ScoreEntry(
    val sectionId: String = "",
    val id: String = "",
    val name: String = "",
    val score: Double = 0.0,
    val max: Double = 100.0
)

/** One score the student has not been told about yet. */
data class NewScore(
    val sectionId: String,
    val classLabel: String,
    val name: String,
    val score: Double,
    val max: Double
)

/** Reply to "forgotPassword": a message that never says whether the account exists. */
@Serializable
data class MessageOnly(
    val message: String = ""
)

@Serializable
data class PortalInfo(
    val ready: Boolean = false,
    val title: String = "Electrical Engineering",
    val subtitle: String = "Student Grades Portal",
    val announcement: String = "",
    val contactEmail: String = "",
    val allowPasswordChange: Boolean = true,
    /** Portal setting REGISTRATION_OPEN: show "Create your account". */
    val registrationOpen: Boolean = false
)

/* ------------------------------ registration ------------------------------ */

/** `register.options`: what the new-student form may offer. */
@Serializable
data class RegistrationOptions(
    val open: Boolean = false,
    val programs: List<String> = emptyList(),
    val years: List<String> = emptyList(),
    val classes: List<RegClass> = emptyList(),
    val minPassword: Int = 6
)

/** One class of the active semester a new student may tick. */
@Serializable
data class RegClass(
    val id: String = "",
    val subject: String = "",
    val title: String = "",
    val section: String = "",
    val schedule: String = "",
    val instructor: String = ""
)

/** `register.verify` reply. */
@Serializable
data class RegistrationDone(
    val done: Boolean = false,
    val studentId: String = "",
    val enrolled: Int = 0,
    val problems: List<String> = emptyList()
)

@Serializable
data class StudentHome(
    val profile: Profile = Profile(),
    val semesters: List<Semester> = emptyList(),
    val activeSemesterId: String = "",
    val classes: List<ClassSummary> = emptyList(),
    val announcement: String = "",
    val contactEmail: String = "",
    val showRunningGrade: Boolean = true,
    val showClassAverage: Boolean = true,
    val allowPasswordChange: Boolean = true
)

@Serializable
data class StudentClass(
    val section: ClassSection = ClassSection(),
    val items: List<ClassItem> = emptyList(),
    val classSize: Int = 0,
    val grade: Grade = Grade(),
    val showRunningGrade: Boolean = true,
    val showClassAverage: Boolean = true,
    /** Course-outcome self-assessment survey, or null when the class has none. */
    val survey: Survey? = null,
    /** Syllabus / TOS PDFs the admin made visible (portal `courseFilesFor_`). */
    val files: List<CourseFile> = emptyList(),
    /** Open problem sets (portal `studentAssignments_`). */
    val assignments: List<ProblemSet> = emptyList(),
    /** The class's exams with this student's state (portal `studentExams_`). */
    val exams: List<ExamInfo> = emptyList()
)

/* ------------------------------ problem sets ------------------------------ */

@Serializable
data class ProblemSet(
    val id: String = "",
    val title: String = "",
    val instructions: String = "",
    /** Material ID of the problem-set PDF, or blank. Opened through `student.file`. */
    val materialId: String = "",
    /** 'yyyy-MM-dd HH:mm', Manila time. */
    val dueAt: String = "",
    val allowLate: Boolean = false,
    val pastDue: Boolean = false,
    val canSubmit: Boolean = true,
    val submission: Submission? = null,
    val score: Double? = null,
    val max: Double? = null
)

@Serializable
data class Submission(
    val id: String = "",
    val fileName: String = "",
    val size: Long = 0,
    val submittedAt: String = "",
    val late: Boolean = false,
    val fileDeleted: Boolean = false
)

/** `student.assignment.submit` reply. */
@Serializable
data class SubmitResult(
    val submittedAt: String = "",
    val late: Boolean = false,
    val fileName: String = "",
    val size: Long = 0,
    val replaced: Boolean = false
)

/* ---------------------------------- exams --------------------------------- */

@Serializable
data class ExamInfo(
    val id: String = "",
    val title: String = "",
    val items: Int = 0,
    val duration: Int = 0,
    val opensAt: String = "",
    val closesAt: String = "",
    /** upcoming | open | in_progress | submitted | missed */
    val state: String = "",
    val maxExits: Int = 0,
    val locked: Boolean = false,
    val score: Double? = null,
    val scoreItems: Int? = null
)

/**
 * `student.exam.start` reply: either the attempt (questions, deadline, saved
 * answers) or done = true with a message (already submitted / time over / locked).
 */
@Serializable
data class ExamAttempt(
    val done: Boolean = false,
    val locked: Boolean = false,
    val message: String = "",
    val attemptId: String = "",
    val title: String = "",
    val items: Int = 0,
    /** Server clock, epoch ms. */
    val deadline: Long = 0,
    val serverNow: Long = 0,
    val questions: List<ExamQuestion> = emptyList(),
    /** Chosen option per question (index into that question's shown choices), null = none. */
    val answers: List<Int?> = emptyList(),
    val away: Int = 0,
    val maxExits: Int = 0
)

@Serializable
data class ExamQuestion(
    val n: Int = 0,
    val text: String = "",
    val choices: List<String> = emptyList(),
    val image: Boolean = false
)

/** `student.exam.save` / `student.exam.submit` reply. */
@Serializable
data class ExamReply(
    val saved: Boolean = false,
    val done: Boolean = false,
    val locked: Boolean = false,
    val late: Boolean = false,
    val message: String = "",
    val serverNow: Long = 0,
    val away: Int = 0,
    val submittedAt: String = "",
    val items: Int = 0,
    val score: Double? = null,
    val showScore: Boolean = false
)

/** `student.exam.image` reply. */
@Serializable
data class ExamImage(
    val mimeType: String = "",
    val data: String = ""
)

@Serializable
data class CourseFile(
    val kind: String = "",
    val label: String = "",
    val name: String = "",
    val size: Long = 0,
    val uploaded: String = ""
)

/** `student.file` reply: the PDF as base64. */
@Serializable
data class CourseFileData(
    val kind: String = "",
    val label: String = "",
    val name: String = "",
    val mimeType: String = "application/pdf",
    /** False for modules, syllabus, TOS…: shown inside the app only. Problem sets are true. */
    val downloadable: Boolean = true,
    val data: String = ""
)

/** A course file on the phone, and whether the student may keep / share it. */
data class DownloadedFile(val file: java.io.File, val downloadable: Boolean)

/** `submitted` is null when the portal can't tell (no workbook linked). */
@Serializable
data class Survey(
    val url: String = "",
    val submitted: Boolean? = null
)

@Serializable
data class Counts(
    val students: Int = 0,
    val sections: Int = 0,
    val subjects: Int = 0,
    val semesters: Int = 0
)

@Serializable
data class AdminHome(
    val profile: Profile = Profile(),
    val semesters: List<Semester> = emptyList(),
    val semesterId: String = "",
    val sections: List<ClassSummary> = emptyList(),
    val counts: Counts = Counts(),
    val version: String = ""
)

@Serializable
data class Assessment(
    val id: String = "",
    val name: String = "",
    val type: String = "Other",
    val max: Double = 100.0,
    val weight: Double = 0.0,
    val date: String = "",
    val published: Boolean = true
)

/**
 * A roster line in the admin view. Deliberately does NOT model the per-item
 * `scores` map: the read-only mobile view shows the computed grade only.
 */
@Serializable
data class RosterEntry(
    val studentId: String = "",
    val lastName: String = "",
    val firstName: String = "",
    val grade: Grade = Grade(),
    /** PASSED, CONDITIONAL, FAILED, INC, DROPPED or NONE (portal `standingOf_`). */
    val standing: String = ""
) {
    val displayName: String
        get() = listOf(lastName, firstName)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { studentId }
}

@Serializable
data class AdminSectionData(
    val section: ClassSummary = ClassSummary(),
    val assessments: List<Assessment> = emptyList(),
    val students: List<RosterEntry> = emptyList(),
    val totalWeight: Double = 0.0
)

/** A value read back from disk, with the moment it was stored. */
data class Cached<T>(
    val value: T,
    val savedAt: Long,
    val fromCache: Boolean
)
