package ph.edu.mmsu.ee.grades.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen, view-only PDF viewer for modules, syllabus, TOS… (the portal
 * says `downloadable: false`). Pages are drawn inside the app with Android's
 * PdfRenderer; the file sits in cacheDir/view-only (not shared by the
 * FileProvider), no other app is offered it, screenshots and screen
 * recording are blocked while it is open, and the file is deleted on close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewOnlyPdf(file: File, title: String, onClose: () -> Unit) {
    val doc = remember(file) { openPdf(file) }
    val lock = remember { Mutex() }

    DisposableEffect(file) {
        onDispose {
            try { doc?.renderer?.close() } catch (_: Exception) { }
            try { doc?.fd?.close() } catch (_: Exception) { }
            file.delete()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "View only" + (doc?.let { " · ${it.pages} page" + if (it.pages == 1) "" else "s" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    }
                )
            }
        ) { padding ->
            if (doc == null) {
                MessageBlock(
                    title = "This file can't be shown",
                    body = "Close it and try again.",
                    actionLabel = "Close",
                    onAction = onClose
                )
                return@Scaffold
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(600, 1600)
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(doc.pages) { index ->
                        PdfPage(doc, index, widthPx, lock)
                    }
                }
            }
        }
    }
}

private class OpenPdf(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
    val pages: Int = renderer.pageCount
    /** width / height of each page, read once up front so rows keep their size. */
    val ratios: FloatArray = FloatArray(pages) { i ->
        renderer.openPage(i).use { p -> p.width.toFloat() / maxOf(1, p.height) }
    }
}

private fun openPdf(file: File): OpenPdf? = try {
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    OpenPdf(fd, PdfRenderer(fd))
} catch (e: Exception) {
    null
}

@Composable
private fun PdfPage(doc: OpenPdf, index: Int, widthPx: Int, lock: Mutex) {
    val ratio = doc.ratios[index].takeIf { it > 0f } ?: 0.7f
    // Rendered when the row scrolls into view, dropped when it leaves, so long
    // modules don't hold every page in memory at once.
    val bitmap by produceState<Bitmap?>(initialValue = null, doc, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            lock.withLock {   // PdfRenderer allows one open page at a time
                try {
                    doc.renderer.openPage(index).use { page ->
                        val h = (widthPx / ratio).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** The pages of a PDF in a scrolling list (no dialog), for the grading screen. Deletes the file when it leaves. */
@Composable
fun PdfPages(file: File, modifier: Modifier = Modifier) {
    val doc = remember(file) { openPdf(file) }
    val lock = remember { Mutex() }
    DisposableEffect(file) {
        onDispose {
            try { doc?.renderer?.close() } catch (_: Exception) { }
            try { doc?.fd?.close() } catch (_: Exception) { }
            file.delete()
        }
    }
    if (doc == null) {
        Text("This file can't be shown.", modifier = modifier.padding(16.dp))
        return
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(600, 1600)
        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(doc.pages) { index -> PdfPage(doc, index, widthPx, lock) }
        }
    }
}
