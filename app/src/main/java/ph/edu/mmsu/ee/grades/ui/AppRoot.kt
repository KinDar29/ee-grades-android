package ph.edu.mmsu.ee.grades.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ph.edu.mmsu.ee.grades.ServiceLocator
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private object Route {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CHANGE_PASSWORD = "change-password"
    const val CLASS = "class"
    const val SECTION = "section"
}

/**
 * The whole app in one place: which of the four phases we are in, and, once
 * signed in, which stack of screens the person's role gets.
 */
@Composable
fun AppRoot() {
    val app: AppViewModel = viewModel()

    when (app.phase) {
        AuthPhase.Checking -> LoadingBlock()

        AuthPhase.SignedOut -> LoginScreen(
            notice = app.notice,
            onSignedIn = { app.onSignedIn(it) }
        )

        AuthPhase.MustChangePassword -> ChangePasswordScreen(
            forced = true,
            onDone = { app.onPasswordChanged() },
            onSignOut = { app.signOut() }
        )

        AuthPhase.Ready -> SignedInNav(app)
    }
}

@Composable
private fun SignedInNav(app: AppViewModel) {
    val nav = rememberNavController()
    val profile = app.profile
    val isAdmin = profile?.isAdmin == true

    val onAuthLost: (String?) -> Unit = { message ->
        app.signOut(message ?: "Your session has expired. Please sign in again.")
    }

    if (profile != null && !isAdmin) NewScoreNotificationsSetup()

    NavHost(navController = nav, startDestination = Route.HOME) {

        composable(Route.HOME) {
            if (isAdmin) {
                AdminHomeScreen(
                    onOpenSection = { nav.navigate("${Route.SECTION}/${it.id}") },
                    onOpenSettings = { nav.navigate(Route.SETTINGS) },
                    onAuthLost = onAuthLost
                )
            } else {
                HomeScreen(
                    profile = profile,
                    onOpenClass = { nav.navigate("${Route.CLASS}/${it.id}") },
                    onOpenSettings = { nav.navigate(Route.SETTINGS) },
                    onAuthLost = onAuthLost
                )
            }
        }

        composable(
            route = "${Route.CLASS}/{sectionId}",
            arguments = listOf(navArgument("sectionId") { type = NavType.StringType })
        ) { entry ->
            ClassScreen(
                sectionId = entry.arguments?.getString("sectionId").orEmpty(),
                onBack = { nav.popBackStack() },
                onAuthLost = onAuthLost
            )
        }

        composable(
            route = "${Route.SECTION}/{sectionId}",
            arguments = listOf(navArgument("sectionId") { type = NavType.StringType })
        ) { entry ->
            AdminSectionScreen(
                sectionId = entry.arguments?.getString("sectionId").orEmpty(),
                onBack = { nav.popBackStack() },
                onAuthLost = onAuthLost
            )
        }

        composable(Route.SETTINGS) {
            SettingsScreen(
                profile = profile,
                onBack = { nav.popBackStack() },
                onChangePassword = { nav.navigate(Route.CHANGE_PASSWORD) },
                onSignOut = { app.signOut() }
            )
        }

        composable(Route.CHANGE_PASSWORD) {
            ChangePasswordScreen(
                forced = false,
                onDone = { nav.popBackStack() },
                onCancel = { nav.popBackStack() }
            )
        }
    }
}

/**
 * For students: ask once for permission to post "new score" notifications
 * (Android 13+), and record which scores they already have so the first
 * background check doesn't report all of them as new.
 */
@Composable
private fun NewScoreNotificationsSetup() {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        val repo = ServiceLocator.repo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !repo.askedForNotifications()
        ) {
            repo.markAskedForNotifications()
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        repo.ensureScoreBaseline()
    }
}
