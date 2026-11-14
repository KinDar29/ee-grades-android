package ph.edu.mmsu.ee.grades.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ph.edu.mmsu.ee.grades.data.Profile

private val Red = Color(0xFFB3261E)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Taking an exam in the app. Same server rules as the portal, plus what only
 * an app can do: screenshots and screen recording are blocked (FLAG_SECURE),
 * the app pins itself to the screen, the screen stays on, and leaving is
 * detected reliably (going home or to another app, split screen / pop-up
 * windows, unpinning, or another window holding focus for more than 3 s).
 * Each trip away counts once, covers the questions, and is sent to the
 * server with the answers; the server locks the exam at the class limit.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExamScreen(
    sectionId: String,
    examId: String,
    profile: Profile?,
    onDone: () -> Unit
) {
    val vm: ExamViewModel = viewModel()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(sectionId, examId) { vm.start(sectionId, examId) }

    val taking = vm.phase == ExamPhase.Taking
    ExamGuard(activity = activity, active = taking, onExit = { vm.recordExit(it) }, vm = vm)

    var askSubmit by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var backNote by remember { mutableStateOf(false) }

    BackHandler(enabled = taking) { backNote = true }

    when (vm.phase) {
        ExamPhase.Loading -> LoadingBlock()
        ExamPhase.Failed -> MessageBlock(
            title = "Could not open the exam",
            body = vm.error ?: "Please try again.",
            actionLabel = "Back to the class",
            onAction = onDone
        )
        ExamPhase.Finished -> FinishedBlock(vm.outcome ?: ExamOutcome(), onDone)
        ExamPhase.Taking -> {
            val a = vm.attempt ?: return
            val q = a.questions.getOrNull(vm.current) ?: return
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(a.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Question ${vm.current + 1} of ${a.questions.size} · ${vm.answeredCount} answered",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            val s = vm.secondsLeft
                            Text(
                                "%d:%02d".format(s / 60, s % 60),
                                fontWeight = FontWeight.Bold,
                                color = if (s < 300) Red else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { vm.current-- }, enabled = vm.current > 0) { Text("← Prev") }
                            TextButton(onClick = { showGrid = true }, modifier = Modifier.weight(1f)) { Text("All questions") }
                            if (vm.current < a.questions.size - 1) {
                                Button(onClick = { vm.current++ }) { Text("Next →") }
                            } else {
                                Button(onClick = { askSubmit = true }, enabled = !vm.submitting) { Text("Submit") }
                            }
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        vm.saveNote?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Red)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text("${vm.current + 1}. ${q.text}", style = MaterialTheme.typography.bodyLarge)
                        if (q.image) {
                            LaunchedEffect(vm.current) { vm.loadImage(q.n) }
                            Spacer(Modifier.height(10.dp))
                            val bmp = vm.images[q.n]
                            if (bmp == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Image(
                                bmp.asImageBitmap(), contentDescription = "Figure for question ${vm.current + 1}",
                                contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        q.choices.forEachIndexed { i, choice ->
                            val picked = vm.answers.getOrNull(vm.current) == i
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    if (picked) 2.dp else 1.dp,
                                    if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                color = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { vm.choose(vm.current, i) }
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = picked, onClick = { vm.choose(vm.current, i) })
                                    Text("${'A' + i}.  $choice", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                    Watermark(profile)
                    if (vm.out) ExitCover(vm = vm, activity = activity)
                }
            }

            if (showGrid) {
                AlertDialog(
                    onDismissRequest = { showGrid = false },
                    title = { Text("All questions") },
                    text = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.questions.indices.forEach { i ->
                                val done = vm.answers.getOrNull(i) != null
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(40.dp).clickable { vm.current = i; showGrid = false }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${i + 1}",
                                            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (i == vm.current) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showGrid = false; askSubmit = true }) { Text("Submit…") }
                    },
                    dismissButton = { TextButton(onClick = { showGrid = false }) { Text("Close") } }
                )
            }

            if (askSubmit) {
                val left = a.questions.size - vm.answeredCount
                AlertDialog(
                    onDismissRequest = { askSubmit = false },
                    title = { Text("Submit your exam?") },
                    text = {
                        Text(
                            if (left > 0) "You have $left unanswered question${if (left == 1) "" else "s"}. You can't change your answers after submitting."
                            else "All questions are answered. You can't change your answers after submitting."
                        )
                    },
                    confirmButton = {
                        Button(onClick = { askSubmit = false; vm.submit() }, enabled = !vm.submitting) {
                            Text(if (vm.submitting) "Submitting…" else "Submit")
                        }
                    },
                    dismissButton = { TextButton(onClick = { askSubmit = false }) { Text("Keep answering") } }
                )
            }

            if (backNote) {
                AlertDialog(
                    onDismissRequest = { backNote = false },
                    title = { Text("You're in the exam") },
                    text = { Text("Use Submit when you're done. Leaving the exam is recorded.") },
                    confirmButton = { TextButton(onClick = { backNote = false }) { Text("OK") } }
                )
            }
        }
    }
}

/**
 * Everything that watches for the student leaving. Only active while the
 * questions are on screen; turns itself off (and unpins) when the exam ends.
 */
@Composable
private fun ExamGuard(activity: Activity?, active: Boolean, onExit: (String) -> Unit, vm: ExamViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focused = LocalWindowInfo.current.isWindowFocused
    val context = LocalContext.current

    // No screenshots / screen recording, screen stays on, app pinned.
    DisposableEffect(activity, active) {
        val w = activity?.window
        if (active && w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pin(activity)
        }
        onDispose {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (active) unpin(activity)
        }
    }

    // Grace period: the "pin this app?" prompt and the first frames take focus.
    var graceUntil by remember { mutableStateOf(System.currentTimeMillis() + 8_000) }

    // Going home, to another app or to recents (not just the screen turning off).
    DisposableEffect(lifecycle, active) {
        val obs = LifecycleEventObserver { _, event ->
            if (!active) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isInteractive) onExit("You left the exam app.")
                }
                Lifecycle.Event.ON_RESUME -> graceUntil = maxOf(graceUntil, System.currentTimeMillis() + 1_500)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Split screen / pop-up window, and unpinning, checked twice a second.
    LaunchedEffect(active, activity) {
        if (!active || activity == null) return@LaunchedEffect
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var wasPinned = false
        while (true) {
            if (activity.isInMultiWindowMode || activity.isInPictureInPictureMode) {
                onExit("Split screen and pop-up windows aren't allowed during the exam. Make the app full screen again.")
            }
            val pinned = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            if (wasPinned && !pinned && System.currentTimeMillis() > graceUntil) {
                onExit("You unpinned the exam.")
            }
            wasPinned = pinned
            delay(500)
        }
    }

    // Another window holding focus (e.g. a floating app) for more than 3 s.
    LaunchedEffect(focused, active) {
        if (!active || focused) return@LaunchedEffect
        delay(3_000)
        if (System.currentTimeMillis() > graceUntil && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onExit("Another window was in front of the exam.")
        }
    }
}

private fun pin(activity: Activity?) {
    try { activity?.startLockTask() } catch (_: Exception) { /* not allowed on this phone */ }
}

private fun unpin(activity: Activity?) {
    try { activity?.stopLockTask() } catch (_: Exception) { }
}

@Composable
private fun ExitCover(vm: ExamViewModel, activity: Activity?) {
    val split = activity?.isInMultiWindowMode == true
    val max = vm.attempt?.maxExits ?: 0
    val left = max - vm.away
    Box(
        Modifier.fillMaxSize().background(Color(0xF2081E16)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(24.dp).widthIn(max = 420.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠", style = MaterialTheme.typography.headlineMedium)
                Text("You left the exam", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(vm.outReason, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "You have left the exam ${vm.away} time${if (vm.away == 1) "" else "s"}. Your teacher can see this." +
                        if (max > 0 && left > 0) " ${if (left == 1) "One more time" else "$left more times"} and the exam locks." else "",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { pin(activity); vm.resume() },
                    enabled = !split
                ) { Text(if (split) "Close split screen first" else "Back to the exam") }
            }
        }
    }
}

/** Name and student number across the questions, so photos of the screen show who took them. */
@Composable
private fun Watermark(profile: Profile?) {
    val who = listOfNotNull(profile?.name, profile?.id).filter { it.isNotBlank() }.joinToString(" · ")
    if (who.isBlank()) return
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(5) {
            Text(
                who,
                color = Color.Gray.copy(alpha = 0.16f),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.rotate(-24f)
            )
        }
    }
}

@Composable
private fun FinishedBlock(o: ExamOutcome, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (o.locked) "🔒" else "✓", style = MaterialTheme.typography.displaySmall)
        Text(
            when {
                o.locked -> "Exam locked"
                o.message.isNotBlank() && o.submittedAt.isBlank() -> o.message
                else -> "Exam submitted"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (o.locked && o.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(o.message, textAlign = TextAlign.Center)
        }
        if (o.submittedAt.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Received ${o.submittedAt}" + if (o.late) " (after the time limit — your last saved answers were used)." else ".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
        val score = o.score
        if (score != null) {
            Text(
                "${if (score % 1.0 == 0.0) score.toLong() else score} / ${o.items}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        } else if (o.submittedAt.isNotBlank() && !o.locked) {
            Text("Your score will appear once your teacher releases it.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone) { Text("Back to the class") }
    }
}
