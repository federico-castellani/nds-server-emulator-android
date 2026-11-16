package io.github.federicocastellani.ndsstation.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.ScreenLayout
import io.github.federicocastellani.ndsstation.emulation.Gamepad
import io.github.federicocastellani.ndsstation.emulation.NativeCore
import io.github.federicocastellani.ndsstation.emulation.Phase
import io.github.federicocastellani.ndsstation.emulation.SyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date

private const val STATE_SLOTS = 3

@Composable
fun EmulatorScreen(onExit: () -> Unit, onSessionExpired: () -> Unit) {
    val app = NdsApp.instance
    val session = app.session
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val phase by session.phase.collectAsState()
    val question by session.question.collectAsState()
    val toast by session.toast.collectAsState()
    val sync by session.syncState.collectAsState()
    val paused by session.userPaused.collectAsState()
    val fastForward by session.fastForwardToggle.collectAsState()

    fun leave() = scope.launch { if (session.end()) onExit() }
    BackHandler { leave() }

    LaunchedEffect(phase) {
        val p = phase
        if (p is Phase.Idle) onExit()
        if (p is Phase.Failed && p.sessionExpired) {
            session.end()
            onSessionExpired()
        }
    }

    // Full screen while playing, and the screen never dims.
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    // --- Frames -----------------------------------------------------------------------
    val bitmap = remember { Bitmap.createBitmap(NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT * 2, Bitmap.Config.ARGB_8888) }
    val image = remember { bitmap.asImageBitmap() }
    val buffer = remember { ByteBuffer.allocateDirect(NativeCore.FRAME_BYTES).order(ByteOrder.nativeOrder()) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var hasFrame by remember { mutableStateOf(false) }
    var fps by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(phase == Phase.Running) {
        if (phase != Phase.Running) return@LaunchedEffect
        var lastId = 0L
        var lastFps = 0L
        while (true) {
            withFrameNanos { now ->
                session.tick()
                val id = NativeCore.copyFrame(buffer, lastId)
                if (id != 0L) {
                    lastId = id
                    buffer.rewind()
                    bitmap.copyPixelsFromBuffer(buffer)
                    hasFrame = true
                    frameTick++
                }
                if (now - lastFps > 500_000_000L) {
                    lastFps = now
                    fps = NativeCore.fps()
                }
            }
        }
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            delay(2500)
            session.toast.value = null
        }
    }

    question?.let { QuestionDialog(it) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                title = session.game?.name.orEmpty(),
                sync = sync, fps = if (paused) null else fps,
                paused = paused, fastForward = fastForward,
                running = phase == Phase.Running,
                onBack = { leave() },
                onPause = { session.setUserPaused(!paused) },
                onFastForward = { session.fastForwardToggle.value = !fastForward },
                onSaveState = session::saveState,
                onLoadState = session::loadState,
                stateTime = session::stateTime,
                onLayout = {
                    settings.screenLayout = when (settings.screenLayout) {
                        ScreenLayout.Auto, ScreenLayout.Vertical -> ScreenLayout.SideBySide
                        ScreenLayout.SideBySide -> ScreenLayout.Auto
                    }
                },
            )

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val dp = density.density
                val showControls = !(settings.hideControlsWithGamepad && Gamepad.connected)
                val geometry = remember(constraints.maxWidth, constraints.maxHeight, settings.screenLayout, showControls, settings.controlsScale) {
                    Geometry.compute(
                        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                        settings.screenLayout, showControls, settings.controlsScale, dp,
                    )
                }
                val textMeasurer = rememberTextMeasurer()
                var pressed by remember { mutableIntStateOf(0) }

                Canvas(
                    Modifier.fillMaxSize().pointerInput(geometry, phase == Phase.Running) {
                        if (phase != Phase.Running) return@pointerInput
                        var stylus: PointerId? = null
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                var mask = 0
                                for (change in event.changes) {
                                    val down = change.pressed
                                    val p = change.position
                                    if (change.id == stylus) {
                                        if (down) {
                                            val b = geometry.bottom
                                            NativeCore.setTouch(
                                                true,
                                                ((p.x - b.left) / b.width * NativeCore.SCREEN_WIDTH).toInt(),
                                                ((p.y - b.top) / b.height * NativeCore.SCREEN_HEIGHT).toInt(),
                                            )
                                        } else {
                                            stylus = null
                                            NativeCore.setTouch(false, 0, 0)
                                        }
                                        change.consume()
                                        continue
                                    }
                                    if (!down) continue
                                    val hit = geometry.maskAt(p)
                                    if (hit != 0) {
                                        mask = mask or hit
                                    } else if (stylus == null && !change.previousPressed && geometry.bottom.contains(p)) {
                                        // A touch that starts on the bottom screen (and not on a button) is the stylus.
                                        stylus = change.id
                                        val b = geometry.bottom
                                        NativeCore.setTouch(
                                            true,
                                            ((p.x - b.left) / b.width * NativeCore.SCREEN_WIDTH).toInt(),
                                            ((p.y - b.top) / b.height * NativeCore.SCREEN_HEIGHT).toInt(),
                                        )
                                    }
                                    change.consume()
                                }
                                if (settings.haptics && pressesNewKey(pressed, mask))
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                pressed = mask
                                session.virtualMask = mask
                            }
                        }
                    },
                ) {
                    frameTick   // redraw on every new frame
                    if (hasFrame) {
                        val src = IntSize(NativeCore.SCREEN_WIDTH, NativeCore.SCREEN_HEIGHT)
                        drawImage(
                            image, IntOffset(0, 0), src,
                            IntOffset(geometry.top.left.toInt(), geometry.top.top.toInt()),
                            IntSize(geometry.top.width.toInt(), geometry.top.height.toInt()),
                            filterQuality = FilterQuality.None,
                        )
                        drawImage(
                            image, IntOffset(0, NativeCore.SCREEN_HEIGHT), src,
                            IntOffset(geometry.bottom.left.toInt(), geometry.bottom.top.toInt()),
                            IntSize(geometry.bottom.width.toInt(), geometry.bottom.height.toInt()),
                            filterQuality = FilterQuality.None,
                        )
                    } else {
                        drawRect(Color(0xFF15161A), geometry.top.topLeft, geometry.top.size)
                        drawRect(Color(0xFF15161A), geometry.bottom.topLeft, geometry.bottom.size)
                    }
                    drawControls(geometry, pressed, settings.controlsOpacity, textMeasurer)
                }

                if (paused && phase == Phase.Running) {
                    Card(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Pause, null, Modifier.size(18.dp))
                            Text("  In pausa")
                        }
                    }
                }
            }
        }

        StatusOverlay(phase, onCancel = { session.cancelLoading() }, onDismissFailure = { leave() })

        toast?.let { message ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(24.dp)) { Text(message) }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    sync: SyncState,
    fps: Float?,
    paused: Boolean,
    fastForward: Boolean,
    running: Boolean,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onFastForward: () -> Unit,
    onSaveState: (Int) -> Unit,
    onLoadState: (Int) -> Unit,
    stateTime: (Int) -> Long?,
    onLayout: () -> Unit,
) {
    var statesMenu by remember { mutableStateOf(false) }
    val tint = Color.White
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Torna alla libreria", tint = tint) }
        Text(title, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (running) {
            val (icon, description) = when (sync) {
                SyncState.Synced -> Icons.Outlined.CloudDone to "Salvataggio sincronizzato"
                SyncState.Pending -> Icons.Outlined.CloudSync to "Salvataggio in attesa di invio"
                SyncState.Uploading -> Icons.Outlined.CloudUpload to "Invio del salvataggio"
                SyncState.Failed -> Icons.Outlined.CloudOff to "Salvataggio non inviato, al sicuro sul dispositivo"
                SyncState.Conflict -> Icons.Outlined.SyncProblem to "Salvataggio più recente sul server: uscendo potrai scegliere quale tenere"
            }
            Icon(icon, description, tint = if (sync == SyncState.Failed || sync == SyncState.Conflict) Color(0xFFFFB74D) else tint.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            Text(fps?.let { " %.0f".format(it) } ?: " —", color = tint.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 28.dp))
            IconToggleButton(checked = fastForward, onCheckedChange = { onFastForward() }) {
                Icon(Icons.Outlined.FastForward, "Avanti veloce", tint = if (fastForward) MaterialTheme.colorScheme.primary else tint)
            }
            IconButton(onClick = onPause) { Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) "Riprendi" else "Pausa", tint = tint) }
            IconButton(onClick = onLayout) { Icon(Icons.Outlined.ViewAgenda, "Disposizione schermi", tint = tint) }
            Box {
                IconButton(onClick = { statesMenu = true }) { Icon(Icons.Outlined.History, "Stati salvati", tint = tint) }
                DropdownMenu(expanded = statesMenu, onDismissRequest = { statesMenu = false }) {
                    for (slot in 1..STATE_SLOTS) {
                        DropdownMenuItem(
                            text = { Text("Salva nello slot $slot") },
                            leadingIcon = { Icon(Icons.Outlined.Save, null) },
                            onClick = { statesMenu = false; onSaveState(slot) },
                        )
                    }
                    HorizontalDivider()
                    for (slot in 1..STATE_SLOTS) {
                        val time = stateTime(slot)
                        DropdownMenuItem(
                            text = {
                                Text(time?.let { "Carica slot $slot · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" } ?: "Slot $slot: vuoto")
                            },
                            leadingIcon = { Icon(Icons.Outlined.History, null) },
                            enabled = time != null,
                            onClick = { statesMenu = false; onLoadState(slot) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusOverlay(phase: Phase, onCancel: () -> Unit, onDismissFailure: () -> Unit) {
    val content: (@Composable ColumnScope.() -> Unit)? = when (phase) {
        is Phase.Loading -> {
            {
                Text(phase.title, style = MaterialTheme.typography.titleMedium)
                Text(phase.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (phase.progress != null) LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (phase.cancellable) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Annulla") }
            }
        }
        is Phase.Ending -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.size(16.dp))
                    Text(phase.message)
                }
            }
        }
        is Phase.Failed -> {
            {
                Text("Impossibile avviare il gioco", style = MaterialTheme.typography.titleMedium)
                Text(phase.message)
                TextButton(onClick = onDismissFailure, modifier = Modifier.align(Alignment.End)) { Text("OK") }
            }
        }
        else -> null
    }
    content ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Card(Modifier.padding(24.dp).widthIn(max = 420.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

