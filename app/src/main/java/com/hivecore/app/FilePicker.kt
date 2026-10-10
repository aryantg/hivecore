package com.hivecore.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.hivecore.core.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Jobs screen's "Choose files" (spec section 17): the system document picker for the recipe's item kinds. Each
 * picked document is copied into `cache/picked/` (the job reads local paths and stores each file as a blob) and the
 * paths go to [onPicked] in the picker's order.
 */
@Composable
fun rememberFilePicker(onPicked: (List<String>) -> Unit): (List<String>) -> Unit {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val paths = withContext(Dispatchers.IO) { uris.mapIndexedNotNull { i, u -> copy(ctx, u, i) } }
            SLog.d("FilePicker", "picked ${uris.size}, copied ${paths.size}")
            if (paths.isNotEmpty()) onPicked(paths)
        }
    }
    return { kinds -> launcher.launch(mimeTypes(kinds)) }
}

private fun mimeTypes(kinds: List<String>): Array<String> = kinds.flatMap { k ->
    when (k) {
        "image" -> listOf("image/*")
        "pdf" -> listOf("application/pdf")
        "audio" -> listOf("audio/*")
        "video" -> listOf("video/*")
        "text" -> listOf("text/*")
        "doc" -> listOf("application/pdf", "text/*", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        else -> emptyList()
    }
}.distinct().ifEmpty { listOf("*/*") }.toTypedArray()

private fun copy(ctx: Context, uri: Uri, index: Int): String? = try {
    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "item$index"
    val dir = File(ctx.cacheDir, "picked").apply { mkdirs() }
    // the index keeps two documents with one display name apart
    val out = File(dir, "%04d_%s".format(index, name.replace(Regex("[^A-Za-z0-9._-]"), "_")))
    ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    out.path
} catch (e: Exception) {
    SLog.w("FilePicker", "could not copy $uri: ${e.message}")
    null
}
