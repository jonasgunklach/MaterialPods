package app.materialpods

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState

private val AACP_UUID = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

// ponytail: process-scoped so fold/unfold recreation doesn't open a second socket.
// Moves into a foreground service once widgets/background features land.
private var pods: AppleDevice? = null

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        System.loadLibrary("hiddenapi")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialPodsTheme { App() } }
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

@SuppressLint("MissingPermission")
@Composable
fun App() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var device by remember { mutableStateOf(pods) }
    var searched by remember { mutableStateOf(pods != null) }

    LaunchedEffect(granted) {
        if (!granted || pods != null) return@LaunchedEffect
        pods = withContext(Dispatchers.IO) {
            val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
            adapter.bondedDevices.firstOrNull { it.uuids?.contains(AACP_UUID) == true }
                ?.let { AppleDevice(ctx.applicationContext, adapter, it, ConnectionState.AVAILABLE) } // init connects
        }
        device = pods
        searched = true
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(device?.metadata?.collectAsState()?.value?.name?.ifBlank { null } ?: "MaterialPods") },
                subtitle = { device?.let { d -> Text(AirPodsSpecs.getSpec(d.metadata.collectAsState().value.model).name) } },
                scrollBehavior = scroll
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !granted -> Button(onClick = { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth access") }
                !searched -> LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                device == null -> Text("No paired AirPods found. Pair them in Bluetooth settings first.")
                else -> PodsScreen(device!!)
            }
        }
    }
}

@Composable
fun PodsScreen(d: AppleDevice) {
    val conn by d.connectionState.collectAsState()
    val state by d.state.collectAsState()
    val scope = rememberCoroutineScope()

    if (conn != ConnectionState.CONNECTED) {
        if (conn == ConnectionState.CONNECTING) LoadingIndicator()
        else Button(onClick = { scope.launch(Dispatchers.IO) { d.connect() } }) { Text("Connect") }
        return
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(BatteryComponent.LEFT to "Left", BatteryComponent.RIGHT to "Right", BatteryComponent.CASE to "Case").forEach { (c, label) ->
                val b = state.battery.firstOrNull { it.component == c && it.status != BatteryStatus.DISCONNECTED }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularWavyProgressIndicator(progress = { (b?.level ?: 0) / 100f }, modifier = Modifier.size(72.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        b?.let { "${it.level}%" + if (it.status == BatteryStatus.CHARGING) " ⚡" else "" } ?: "—",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }

    // AACP listening mode values: 1 off, 2 noise cancellation, 3 transparency, 4 adaptive.
    val modes = listOf(1 to "Off", 2 to "Noise\nCancel", 3 to "Transp.", 4 to "Adaptive")
    val current = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt()
    Text("Listening mode", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = current == value,
                onCheckedChange = { scope.launch(Dispatchers.IO) { d.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, value.toByte()) } },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f)
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
