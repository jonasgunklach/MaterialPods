package app.materialpods

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus

private val PERMS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pods.init(this)
        enableEdgeToEdge()
        setContent { MaterialPodsTheme { App() } }
        // adb: am start -n app.materialpods/.MainActivity --ez preview_popup true [--es popup_style island]
        intent.getStringExtra("popup_style")?.let { Nearby.setStyle(this, it) }
        if (intent.getBooleanExtra("demo", false)) Demo.enable(this)
        if (intent.getBooleanExtra("render_widgets", false)) Demo.renderWidgets(this)
        if (intent.getBooleanExtra("preview_popup", false)) Popup.show(this, preview = true)
    }

    // Scan for case/bud adverts only while the app is visible: live battery without a connection.
    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) Pods.startBle(this)
        Nearby.start(this)
    }

    override fun onStop() {
        Pods.stopBle()
        super.onStop()
    }
}

@Composable
fun MaterialPodsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(PERMS[0]) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[PERMS[0]] == true
    }
    val device by Pods.device.collectAsState()

    // The receiver starts the service when the AirPods connect; this covers "already connected".
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        Pods.ensureDevice(ctx)
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) Pods.startBle(ctx)
        if (!Pods.connected.value) ctx.startForegroundService(Intent(ctx, PodsService::class.java))
    }
    // Generated widget-picker previews (Android 15+); rate-limited by the system, so best effort.
    LaunchedEffect(Unit) {
        runCatching {
            val m = GlanceAppWidgetManager(ctx)
            WIDGET_RECEIVERS.forEach { m.setWidgetPreviews(it) }
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            val meta = device?.metadata?.collectAsState()?.value
            LargeFlexibleTopAppBar(
                title = { Text(meta?.name?.ifBlank { null } ?: "MaterialPods") },
                subtitle = { meta?.takeIf { it.model != AirPodsModel.UNKNOWN }?.let { Text(AirPodsSpecs.getSpec(it.model).name) } },
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !granted -> Button(onClick = { ask.launch(PERMS) }) { Text("Allow Bluetooth access") }
                device == null -> Text("No paired AirPods found. Pair them in Bluetooth settings first.", style = MaterialTheme.typography.bodyLarge)
                else -> PodsScreen(device!!)
            }
        }
    }
}

@Composable
fun PodsScreen(d: AppleDevice) {
    val state by d.state.collectAsState()
    val connected by Pods.connected.collectAsState()

    Card(Modifier.fillMaxWidth()) { BatteryRings(state, Modifier.padding(20.dp)) }

    if (!connected) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator()
            Text(
                "Not connected. Open the case near your phone for live battery; put the AirPods in to control them.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val ctx = LocalContext.current
        Button(onClick = { Pods.connectNow(ctx) }) { Text("Connect") }
        WidgetGallery(state)
        return
    }

    val current = Pods.mode(state)
    Text("Listening mode", style = MaterialTheme.typography.titleMedium)
    val modes = MODES.filter { it.first != 1 || Pods.flag(state, ControlCommandIdentifier.ALLOW_OFF_OPTION) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = current == value,
                onCheckedChange = { Pods.setMode(value) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f)
            ) { Text(label.replace(' ', '\n'), style = MaterialTheme.typography.labelMedium) }
        }
    }

    ListeningExtras(state)
    WidgetGallery(state)
    HeartRate(d, state)
    Recorder()
    SettingsSection(d, state)
    Spacer(Modifier.height(24.dp))
}

@Composable
fun Recorder() {
    val ctx = LocalContext.current
    val rec by Pods.rec.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.active) { while (rec.active) { now = System.currentTimeMillis(); delay(250) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("High-quality AirPods mic", style = MaterialTheme.typography.titleMedium)
            Text(
                "Records the AirPods mic at 64 kHz while music keeps playing in full quality.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (rec.active) {
                val secs = (now - rec.startedAt) / 1000
                Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displaySmall)
                LinearWavyProgressIndicator(progress = { rec.level }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleButton(
                    checked = rec.active,
                    onCheckedChange = { if (it) Pods.startRecording(ctx) else Pods.stopRecording(ctx) }
                ) { Text(if (rec.active) "Stop" else "Record") }
                rec.saved?.let { uri ->
                    FilledTonalButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Play last recording") }
                }
            }
        }
    }
}

@Composable
fun BatteryRings(state: AppleState, modifier: Modifier = Modifier, size: Int = 72) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(BatteryComponent.LEFT to "Left", BatteryComponent.RIGHT to "Right", BatteryComponent.CASE to Pods.caseLabel(state)).forEach { (c, label) ->
            val b = Pods.battery(state, c)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularWavyProgressIndicator(progress = { (b?.level ?: 0) / 100f }, modifier = Modifier.size(size.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    b?.let { "${it.level}%" + if (it.status == BatteryStatus.CHARGING) " ⚡" else "" } ?: "—",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
