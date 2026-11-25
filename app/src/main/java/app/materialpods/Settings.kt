package app.materialpods

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier as Id
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = subtitle?.let { { Text(it) } },
    trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.fillMaxWidth()
)

@Composable
private fun Toggle(s: AppleState, id: Id, title: String, subtitle: String? = null) {
    // Only show settings this model reports; an unknown state rendered as "off" would be a lie.
    if (id in s.controlStates) SwitchRow(title, subtitle, Pods.flag(s, id)) { Pods.set(id, it) }
}

/** Adaptive strength + which modes press-and-hold cycles through. */
@Composable
fun ListeningExtras(s: AppleState) {
    if (Pods.mode(s) == 4) {
        val current = (Pods.value(s, Id.AUTO_ANC_STRENGTH) ?: 50).toFloat()
        var v by remember(current) { mutableFloatStateOf(current) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("Adaptive strength", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Less noise", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = v, onValueChange = { v = it }, valueRange = 0f..100f,
                    onValueChangeFinished = { Pods.set(Id.AUTO_ANC_STRENGTH, v.toInt()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text("More noise", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text("Press and hold switches between", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
    val cycle = Pods.cycle(s)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MODES.filter { it.first != 1 || Pods.flag(s, Id.ALLOW_OFF_OPTION) }.forEach { (mode, label) ->
            FilterChip(selected = mode in cycle, onClick = { Pods.toggleCycle(mode) }, label = { Text(label) })
        }
    }
}

@Composable
fun HeartRate(d: AppleDevice, s: AppleState) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val on = s.hrmState != BuddyState.INACTIVE
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Heart rate", style = MaterialTheme.typography.titleMedium)
                val bpm = s.currentHeartRate?.bpm
                when {
                    bpm != null -> Row(verticalAlignment = Alignment.Bottom) {
                        Text("$bpm", fontSize = 48.sp, fontWeight = FontWeight.Black)
                        Text(" BPM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    on -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator()
                        Text("Measuring… keep both AirPods in", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text("Measured by the AirPods Pro 3 sensor", style = MaterialTheme.typography.bodyMedium)
                }
            }
            ToggleButton(checked = on, onCheckedChange = { start ->
                scope.launch(Dispatchers.IO) { if (start) d.startHr() else d.stopHr() }
            }) { Text(if (on) "Stop" else "Measure") }
        }
    }
}

@Composable
fun SettingsSection(d: AppleDevice, s: AppleState) {
    val ctx = LocalContext.current

    Section("Features") {
        Toggle(s, Id.CONVERSATION_DETECT_CONFIG, "Conversation awareness", "Lowers media and lets voices through when you speak")
        Toggle(s, Id.ADAPTIVE_VOLUME_CONFIG, "Personalized volume", "Adjusts media volume to your environment")
        Toggle(s, Id.SLEEP_DETECTION_CONFIG, "Sleep detection", "Pauses media when you fall asleep")
        Toggle(s, Id.EAR_DETECTION_CONFIG, "Automatic ear detection")
        var autoConnect by remember { mutableStateOf(Nearby.autoConnect(ctx)) }
        SwitchRow("Connect automatically", "When you put an AirPod in your ear near the phone", autoConnect) {
            autoConnect = it
            Nearby.set(ctx, "auto_connect", it)
        }
        var style by remember { mutableStateOf(if (Settings.canDrawOverlays(ctx)) Nearby.style(ctx) else Nearby.OFF) }
        ListItem(
            headlineContent = { Text("Popup when AirPods are nearby") },
            supportingContent = { Text("Battery and a Connect button when you open the case") },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        val styles = listOf(Nearby.CARD to "Card", Nearby.ISLAND to "Island", Nearby.OFF to "Off")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            styles.forEachIndexed { i, (value, label) ->
                ToggleButton(
                    checked = style == value,
                    onCheckedChange = {
                        if (value != Nearby.OFF && !Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        }
                        style = value
                        Nearby.setStyle(ctx, value)
                    },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        styles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(label) }
            }
        }
        if (style != Nearby.OFF) TextButton(onClick = { Popup.show(ctx, preview = true) }, modifier = Modifier.padding(start = 8.dp)) { Text("Preview") }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        SwitchRow("Status notification", "Battery in the notification shade. Turn off if you use the widgets", nm.getNotificationChannel("pods")?.importance != NotificationManager.IMPORTANCE_NONE) {
            // The service needs its notification to stay alive; Android lets you hide it per channel.
            ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "pods"))
        }
        var autoPause by remember { mutableStateOf(Pods.autoPause(ctx)) }
        SwitchRow("Pause when an AirPod is removed", "Resumes when you put it back", autoPause) {
            autoPause = it
            Pods.prefs(ctx).edit { putBoolean("auto_pause", it) }
        }
    }

    Section("System integration") {
        val st by Privileged.status.collectAsState()
        val (text, action) = when (st) {
            Privileged.Status.NOT_INSTALLED -> "Install Shizuku to show AirPods battery in Android's Bluetooth settings and connect audio directly — no root." to "Get Shizuku"
            Privileged.Status.NOT_RUNNING -> "Shizuku is installed but not running. Start it in the Shizuku app (Wireless debugging)." to "Open Shizuku"
            Privileged.Status.NO_PERMISSION -> "Allow MaterialPods to use Shizuku." to "Allow"
            Privileged.Status.STARTING -> "Starting…" to null
            Privileged.Status.READY -> "Active: battery shows in Bluetooth settings; Connect brings music and calls over." to null
        }
        ListItem(
            headlineContent = { Text("Shizuku") },
            supportingContent = { Text(text) },
            trailingContent = action?.let { a -> {
                FilledTonalButton(onClick = {
                    when (st) {
                        Privileged.Status.NOT_INSTALLED -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Privileged.SHIZUKU}")))
                        Privileged.Status.NOT_RUNNING -> ctx.packageManager.getLaunchIntentForPackage(Privileged.SHIZUKU)?.let(ctx::startActivity)
                        else -> Privileged.requestPermission()
                    }
                }) { Text(a) }
            } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    Section("Controls") {
        Toggle(s, Id.VOLUME_SWIPE_MODE, "Swipe stem for volume")
        Toggle(s, Id.ONE_BUD_ANC_MODE, "Noise cancellation with one AirPod")
        Toggle(s, Id.ALLOW_OFF_OPTION, "Allow Off listening mode")
    }

    Section("Microphone") {
        val mic = Pods.value(s, Id.MIC_MODE) ?: 0
        val options = listOf(0 to "Automatic", 2 to "Always left", 1 to "Always right")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            options.forEachIndexed { i, (value, label) ->
                ToggleButton(
                    checked = mic == value,
                    onCheckedChange = { Pods.set(Id.MIC_MODE, value) },
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    Section("Name") {
        val current = d.metadata.collectAsState().value.name
        var name by remember(current) { mutableStateOf(current) }
        var saved by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it; saved = false }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { Pods.scope.launch { d.renameDevice(name.trim()) }; saved = true }, enabled = name.isNotBlank() && name != current) { Text("Rename") }
                if (saved) Text("Re-pair the AirPods for Android to show the new name.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
