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
    val mustChangePassword: Boolean = false
)

@Serializable
data class SessionInfo(
    val profile: Profile = Profile(),
    val mustChangePassword: Boolean = false
)

@Serializable
data class TokenOnly(
    val token: String = ""
)

@Serializable
data class PortalInfo(
    val ready: Boolean = false,
    val title: String = "Electrical Engineering",
    val subtitle: String = "Student Grades Portal",
    val announcement: String = "",
    val contactEmail: String = "",
    val allowPasswordChange: Boolean = true
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
    val showClassAverage: Boolean = true
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
    val grade: Grade = Grade()
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
