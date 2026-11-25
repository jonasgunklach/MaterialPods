package app.materialpods

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import me.kavishdevar.librepods.core.bluetooth.verifyRPA

/**
 * "AirPods nearby": an OS-managed low-power BLE scan (keeps running when the app is closed)
 * watches for our AirPods' proximity adverts. When they show up after a quiet spell — lid opened,
 * taken out — and the phone isn't connected, we show the connect popup.
 */
object Nearby {
    private const val TAG = "Nearby"
    private const val QUIET_MS = 60_000L

    const val CARD = "card"; const val ISLAND = "island"; const val OFF = "off"
    fun style(ctx: Context) = Pods.prefs(ctx).getString("popup_style", CARD) ?: CARD
    fun popup(ctx: Context) = style(ctx) != OFF
    fun setStyle(ctx: Context, style: String) {
        Pods.prefs(ctx).edit { putString("popup_style", style) }
        if (enabled(ctx)) start(ctx) else stop(ctx)
    }
    fun autoConnect(ctx: Context) = Pods.prefs(ctx).getBoolean("auto_connect", false)
    private fun enabled(ctx: Context) = popup(ctx) || autoConnect(ctx)

    fun set(ctx: Context, key: String, on: Boolean) {
        Pods.prefs(ctx).edit { putBoolean(key, on) }
        if (enabled(ctx)) start(ctx) else stop(ctx)
    }

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, BleReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun scanner(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    /** Idempotent: re-registering the same PendingIntent replaces the old scan. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (!enabled(ctx) || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val buds = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x07, 0x00, 0x01), byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner(ctx)?.startScan(listOf(buds), settings, intent(ctx)) }
            .onFailure { Log.w(TAG, "background scan failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        runCatching { scanner(ctx)?.stopScan(intent(ctx)) }
    }

    /**
     * Called for every batch of adverts from our AirPods.
     * Auto-connect: a bud is in an ear but the phone isn't connected (at most once a minute).
     * Popup: they appeared after a quiet spell (lid opened, taken out) and aren't connected.
     */
    fun onSeen(ctx: Context, inEar: Boolean) {
        val p = Pods.prefs(ctx)
        val now = System.currentTimeMillis()
        val appeared = now - p.getLong("ble_seen", 0) > QUIET_MS
        p.edit { putLong("ble_seen", now) }
        if (Pods.connected.value) return
        val bt = Pods.bonded(ctx) ?: return
        if (Pods.aclConnected(bt)) return // Android is already reconnecting them by itself
        when {
            autoConnect(ctx) && inEar && now - p.getLong("ac_last", 0) > QUIET_MS -> {
                p.edit { putLong("ac_last", now) }
                Log.i(TAG, "AirPods in ear nearby, not connected: connecting")
                Pods.connectNow(ctx.applicationContext)
            }
            popup(ctx) && !autoConnect(ctx) && appeared && Settings.canDrawOverlays(ctx) -> {
                Log.i(TAG, "AirPods appeared nearby, not connected: showing popup")
                Popup.show(ctx)
            }
        }
    }
}

class BleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java) ?: return
        val irk = Pods.prefs(ctx).getString("irk", null)?.hexToByteArray() ?: return
        val ours = results.filter { verifyRPA(it.device.address, irk) }
        Log.d("Nearby", "${results.size} adverts, ${ours.size} from our AirPods")
        if (ours.isEmpty()) return
        // Status byte: bits 0x02/0x08 = a bud in an ear (same layout core's BLEManager parses).
        val inEar = ours.any { r -> r.scanRecord?.getManufacturerSpecificData(0x004C)?.let { it.size > 5 && (it[5].toInt() and 0x0A) != 0 } == true }
        Nearby.onSeen(ctx, inEar)
    }
}

/** The OS drops PendingIntent scans on reboot and app update; re-arm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Nearby.start(ctx)
}
