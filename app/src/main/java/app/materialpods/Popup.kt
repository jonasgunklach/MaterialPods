package app.materialpods

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import me.kavishdevar.librepods.core.devices.BatteryComponent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/** iOS-style "your AirPods are nearby" card, drawn over other apps (needs "Display over other apps"). */
object Popup {
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    /** [preview]: from settings — stays up and shows the buttons even while connected. */
    fun show(ctx: Context, preview: Boolean = false) = main.post { showNow(ctx.applicationContext, preview) }
    fun hide(ctx: Context) = main.post { hideNow(ctx.applicationContext) }

    private fun showNow(ctx: Context, preview: Boolean) {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val island = Nearby.style(ctx) == Nearby.ISLAND
        Pods.ensureDevice(ctx)
        Pods.startBle(ctx) // live battery from adverts while it's up
        val o = OverlayOwner()
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { MaterialPodsTheme { if (island) Island(preview) { hide(ctx) } else PopupCard(preview) { hide(ctx) } } }
        }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val lp = if (island) islandParams(wm) else WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(v, lp)
        view = v; owner = o
    }

    /** Small window centred on the front camera (display cutout), drawn over the status bar. */
    private fun islandParams(wm: WindowManager) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val m = wm.currentWindowMetrics
        val cam = m.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
        x = cam?.let { it.centerX() - m.bounds.width() / 2 } ?: 0
        y = 0
        cameraY = cam?.centerY() ?: 0
    }

    /** Camera centre (px from the top) so the closed island can sit on it. */
    var cameraY = 0
        private set

    private fun hideNow(ctx: Context) {
        view?.let { runCatching { ctx.getSystemService(WindowManager::class.java).removeView(it) } }
        owner?.destroy()
        view = null; owner = null
        Pods.stopBle()
    }
}

/** Dynamic-island style: a black pill at the camera that springs open into the pill widget. */
@Composable
private fun Island(preview: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val d by Pods.device.collectAsState()
    val state = d?.state?.collectAsState()?.value ?: if (preview) PREVIEW else return
    val linked by Pods.connected.collectAsState()
    val connected = linked && !preview
    var connecting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    suspend fun collapse() { open = false; delay(500); close() }

    LaunchedEffect(Unit) { delay(150); open = true }
    LaunchedEffect(connected) { if (connected) { delay(1500); collapse() } }
    LaunchedEffect(connecting) {
        if (!connecting) { delay(20_000); collapse() }
        else { delay(15_000); if (!Pods.connected.value) { failed = true; connecting = false } }
    }

    val cs = MaterialTheme.colorScheme
    val spring = spring<Dp>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val res = LocalContext.current.resources
    val dens = res.displayMetrics.density
    // Closed: a pill around the camera. Open: just below the status bar, which always draws above overlays.
    val statusBar = res.getDimensionPixelSize(res.getIdentifier("status_bar_height", "dimen", "android")) / dens
    val closedTop = (Popup.cameraY / dens - 15f).coerceAtLeast(2f)
    val w by animateDpAsState(if (open) 344.dp else 104.dp, spring)
    val h by animateDpAsState(if (open) 72.dp else 30.dp, spring)
    val top by animateDpAsState(if (open) (statusBar + 6f).dp else closedTop.dp, spring)
    val bg by animateColorAsState(if (open) cs.secondaryContainer else Color.Black)
    val density = LocalContext.current.resources.displayMetrics.density
    val pill = remember(state, cs) {
        val p = palOf(cs)
        Art.pill((214 * density).toInt(), (72 * density).toInt(), buds(state), cell(state, BatteryComponent.CASE, Pods.caseLabel(state), Icon.CASE), p).asImageBitmap()
    }

    Box(Modifier.padding(top = top).size(w, h).clip(RoundedCornerShape(50)).background(bg)) {
        AnimatedVisibility(open, enter = fadeIn(tween(250, delayMillis = 120)), exit = fadeOut(tween(120))) {
            Row(Modifier.fillMaxSize().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(pill, contentDescription = null, modifier = Modifier.width(214.dp).fillMaxHeight())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        connected -> Text("Connected", style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
                        connecting -> LoadingIndicator(Modifier.size(44.dp))
                        failed -> FilledTonalButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close()
                        }) { Text("Settings") }
                        else -> Button(
                            onClick = { connecting = true; Pods.connectNow(ctx) },
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) { Text("Connect", maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** Minimal lifecycle so Compose can run in a window that has no Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

@Composable
private fun PopupCard(preview: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    val d by Pods.device.collectAsState()
    val state = d?.state?.collectAsState()?.value ?: if (preview) PREVIEW else return
    val name = d?.metadata?.collectAsState()?.value?.name?.ifBlank { null } ?: "AirPods"
    val linked by Pods.connected.collectAsState()
    val connected = linked && !preview
    var connecting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(connected) { if (connected) { delay(1500); close() } }
    LaunchedEffect(connecting) {
        if (!connecting) { delay(20_000); close() }       // ignored → go away
        else { delay(15_000); if (!Pods.connected.value) { failed = true; connecting = false } }
    }

    AnimatedVisibility(shown, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(36.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                BatteryRings(state, size = 64)
                when {
                    connected -> Text("Connected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Couldn't connect. They may be busy with another device.", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = close) { Text("Close") }
                            Button(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                close()
                            }) { Text("Bluetooth settings") }
                        }
                    }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (connecting) LoadingIndicator() else TextButton(onClick = close) { Text("Not now") }
                        Button(enabled = !connecting, onClick = { connecting = true; Pods.connectNow(ctx) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}
