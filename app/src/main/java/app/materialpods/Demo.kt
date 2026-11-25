package app.materialpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier as Id
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.ConnectionState
import java.io.File

/**
 * Sample-data mode for screenshots (README, emulator). Not reachable from the UI:
 *   adb shell am start -n app.materialpods/.MainActivity --ez demo true
 *   adb shell am start -n app.materialpods/.MainActivity --ez render_widgets true
 */
object Demo {
    private val state = PREVIEW.copy(
        controlStates = PREVIEW.controlStates + mapOf(
            Id.CONVERSATION_DETECT_CONFIG to byteArrayOf(1), Id.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(2),
            Id.SLEEP_DETECTION_CONFIG to byteArrayOf(1), Id.VOLUME_SWIPE_MODE to byteArrayOf(1),
            Id.ONE_BUD_ANC_MODE to byteArrayOf(2), Id.ALLOW_OFF_OPTION to byteArrayOf(2),
            Id.MIC_MODE to byteArrayOf(0), Id.LISTENING_MODE_CONFIGS to byteArrayOf(0x0E),
        )
    )

    @SuppressLint("MissingPermission")
    fun enable(ctx: Context) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val d = AppleDevice(ctx.applicationContext, adapter, adapter.getRemoteDevice("02:00:00:00:00:01"), ConnectionState.DISCONNECTED)
        d.loadInitialState(state, AppleSettings(), AppleMetadata(name = "AirPods Pro", model = AirPodsModel.AIRPODS_PRO_3))
        Pods.device.value = d
        Pods.connected.value = true
    }

    /** Writes every widget, light and dark, to <external files>/widgets/. */
    fun renderWidgets(ctx: Context) {
        val out = File(ctx.getExternalFilesDir(null), "widgets").apply { mkdirs() }
        val l = cell(state, BatteryComponent.LEFT, "Left", Icon.LEFT)
        val r = cell(state, BatteryComponent.RIGHT, "Right", Icon.RIGHT)
        val case = cell(state, BatteryComponent.CASE, "Case", Icon.CASE)
        for ((theme, cs) in listOf("dark" to dynamicDarkColorScheme(ctx), "light" to dynamicLightColorScheme(ctx))) {
            val p = palOf(cs)
            mapOf(
                "cookie" to Art.cookie(600, 600, buds(state), p),
                "gauges" to Art.gauges(1280, 600, listOf(l, r, case), p),
                "pill" to Art.pill(960, 300, buds(state), case, p),
                "modes" to Art.modes(1200, 300, listOf(2, 3, 4), 2, p),
            ).forEach { (name, b) -> File(out, "$name-$theme.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
