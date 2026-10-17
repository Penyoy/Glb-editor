package id.animasi.anima3d.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.animasi.anima3d.gltf.JointEntry
import id.animasi.anima3d.gltf.ModelSession
import id.animasi.anima3d.render.BindingConfidence
import id.animasi.anima3d.render.GlbViewport
import id.animasi.anima3d.render.NodeBinding
import kotlinx.coroutines.delay

private val PanelBackground = Color(0xFF16181D)
private val PanelBackgroundAlt = Color(0xFF1E2128)
private val VerifiedColor = Color(0xFF66BB6A)
private val FallbackColor = Color(0xFFFFB74D)
private val UnreliableColor = Color(0xFFEF5350)

@Composable
fun ViewerScreen(
    bytes: ByteArray,
    session: ModelSession,
    displayName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // Viewport dibuat sekali dan dimiliki composable ini. Pelepasan engine terjadi
    // lewat DisposableEffect, bukan lewat deteksi pelepasan view dari dalam Filament,
    // supaya urutan pembongkaran sumber daya bisa dipastikan.
    val viewport = remember(context) { GlbViewport(context) }

    var binding by remember { mutableStateOf<NodeBinding?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewport) {
        try {
            val loaded = viewport.loadModel(bytes)
            if (!loaded) {
                loadError = "Filament tidak bisa memuat berkas ini."
                return@LaunchedEffect
            }
            binding = viewport.buildNodeBinding(session.document, session.skeleton)
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.simpleName ?: "Gagal memuat model"
        }
    }

    DisposableEffect(viewport) {
        onDispose { viewport.release() }
    }

    var tab by remember { mutableStateOf(Tab.CLIP) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1f) }
    var clipIndex by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Playhead disegarkan beberapa kali per detik, bukan setiap frame: cukup halus
    // untuk dibaca mata, dan tidak memaksa seluruh layar digambar ulang 60 kali
    // per detik hanya untuk menggerakkan satu slider.
    LaunchedEffect(viewport, playing) {
        while (true) {
            if (!dragging) position = viewport.currentTimeSeconds
            delay(80)
        }
    }

    LaunchedEffect(playing, speed) {
        viewport.isPlaying = playing
        viewport.playbackSpeed = speed
    }

    val joints = remember(session) { session.skeleton.jointTree() }
    val clipCount = viewport.clipCount
    val duration = if (clipCount > 0) viewport.clipDuration(clipIndex) else 0f

    Column(modifier = Modifier.fillMaxSize()) {

        // --- Kepala ---------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBackgroundAlt)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${session.nodeCount} node")
                        if (session.jointCount > 0) append(" · ${session.jointCount} bone")
                        append(" · ${session.clips.size} klip")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConfidenceBadge(binding?.confidence)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClose) { Text("Tutup") }
        }

        // --- Viewport -------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { viewport },
                modifier = Modifier.fillMaxSize()
            )
            if (loadError != null) {
                Text(
                    text = loadError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
        }

        // --- Panel ----------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(232.dp)
                .background(PanelBackground)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Tab.entries.forEach { entry ->
                    TextButton(
                        onClick = { tab = entry },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = entry.label,
                            color = if (tab == entry) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            when (tab) {
                Tab.CLIP -> ClipPanel(
                    viewport = viewport,
                    clipCount = clipCount,
                    clipIndex = clipIndex,
                    duration = duration,
                    position = position,
                    playing = playing,
                    speed = speed,
                    onClipSelected = { index ->
                        clipIndex = index
                        viewport.selectClip(index)
                        position = 0f
                    },
                    onPlayingChange = { playing = it },
                    onSpeedChange = { speed = it },
                    onSeekFraction = { fraction ->
                        if (duration > 0f) {
                            val target = fraction * duration
                            viewport.seekTo(target)
                            position = target
                        }
                    },
                    onDragChange = { dragging = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Tab.BONES -> BonePanel(
                    joints = joints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Tab.DIAGNOSTICS -> DiagnosticPanel(
                    session = session,
                    binding = binding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

private enum class Tab(val label: String) {
    CLIP("Klip"),
    BONES("Bone"),
    DIAGNOSTICS("Diagnostik")
}

@Composable
private fun ConfidenceBadge(confidence: BindingConfidence?) {
    val (text, color) = when (confidence) {
        BindingConfidence.VERIFIED_BY_INDEX -> "pemetaan: terverifikasi" to VerifiedColor
        BindingConfidence.FALLBACK_BY_NAME -> "pemetaan: lewat nama" to FallbackColor
        BindingConfidence.UNRELIABLE -> "pemetaan: tidak pasti" to UnreliableColor
        null -> "pemetaan: belum diperiksa" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun ClipPanel(
    viewport: GlbViewport,
    clipCount: Int,
    clipIndex: Int,
    duration: Float,
    position: Float,
    playing: Boolean,
    speed: Float,
    onClipSelected: (Int) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onDragChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        if (clipCount == 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Model ini tidak punya klip animasi bawaan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPlayingChange(!playing) }) {
                    Text(if (playing) "Jeda" else "Putar")
                }
                Text(
                    text = formatTime(position) + " / " + formatTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                listOf(0.25f, 1f, 2f).forEach { option ->
                    TextButton(onClick = { onSpeedChange(option) }) {
                        Text(
                            text = if (option == 1f) "1x" else "${option}x",
                            color = if (speed == option) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Slider(
                value = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    onDragChange(true)
                    onSeekFraction(fraction)
                },
                onValueChangeFinished = { onDragChange(false) }
            )

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = (0 until clipCount).toList(), key = { it }) { index ->
                    val selected = index == clipIndex
                    Text(
                        text = (index + 1).toString() + ". " +
                            viewport.clipName(index).ifBlank { "klip_$index" } +
                            "  (" + formatTime(viewport.clipDuration(index)) + ")",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) PanelBackgroundAlt else PanelBackground)
                            .clickable { onClipSelected(index) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BonePanel(
    joints: List<JointEntry>,
    modifier: Modifier = Modifier
) {
    if (joints.isEmpty()) {
        Column(modifier = modifier.padding(12.dp)) {
            Text(
                text = "Model ini tidak punya kerangka, jadi tidak ada bone yang bisa dianimasikan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = modifier) {
            items(items = joints, key = { it.nodeIndex }) { joint ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (8 + joint.depth * 14).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = joint.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#${joint.nodeIndex}" +
                            if (joint.childJointCount > 0) " · ${joint.childJointCount} anak" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticPanel(
    session: ModelSession,
    binding: NodeBinding?,
    modifier: Modifier = Modifier
) {
    val text = remember(session, binding) {
        buildString {
            appendLine("== Pemetaan node glTF ke entity Filament ==")
            if (binding == null) {
                appendLine("Belum diperiksa (model belum termuat).")
            } else {
                appendLine(binding.report.toMultilineString())
            }
            appendLine()
            appendLine("== Ringkasan berkas ==")
            session.summaryLines().forEach { appendLine(it) }
            if (session.warnings.isNotEmpty()) {
                appendLine()
                appendLine("== Peringatan (${session.warnings.size}) ==")
                session.warnings.forEach { appendLine("· $it") }
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** Membungkus format waktu supaya konsisten di seluruh panel. */
private fun formatTime(seconds: Float): String {
    if (seconds <= 0f) return "0.00s"
    return String.format(java.util.Locale.US, "%.2fs", seconds)
}
