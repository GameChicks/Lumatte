package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.lumixfilmmirror.camera.CameraMedia

@Composable
internal fun CameraPhotoThumbnail(entry: CameraMedia, version: Long, loadVersion: Long, enabled: Boolean,
    workspace: WorkspaceViewModel, modifier: Modifier = Modifier.size(64.dp)) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var bitmap by remember(entry, version) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(entry, version, loadVersion) { mutableStateOf(false) }
    var loading by remember(entry, version) { mutableStateOf(false) }
    LaunchedEffect(entry, version, loadVersion, active, workspace) {
        if (active && entry.thumbnail != null && bitmap == null && !failed) {
            loading = true
            try {
                bitmap = workspace.cameraThumbnail(entry, version)
                failed = bitmap == null
            } finally { loading = false }
        }
    }
    Box(modifier
        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = "${entry.title}预览",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(when {
            entry.thumbnail == null -> "无预览"
            failed -> "预览不可用"
            loading -> "预览…"
            else -> "待预览"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
