package app.materialpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Runs as the shell user (uid 2000) inside Shizuku's process — or from [ShellHelper] over adb.
 * Shell holds BLUETOOTH_PRIVILEGED, which normal apps can't get.
 */
@SuppressLint("MissingPermission", "PrivateApi")
class PrivilegedService private constructor(private val ctx: Context) : IPrivileged.Stub() {
    /**
     * What Shizuku calls. The Context constructor stays private: Shizuku would otherwise pick it and
     * pass its own context, which has no Bluetooth access in this process.
     */
    constructor() : this(ShellHelper.shellContext())

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()

    init {
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { proxies[p] = proxy }
                override fun onServiceDisconnected(p: Int) { proxies.remove(p) }
            }, profile)
        }
    }

    private fun proxy(profile: Int): BluetoothProfile? {
        repeat(30) { proxies[profile]?.let { return it }; Thread.sleep(100) } // callbacks arrive on the main looper
        return null
    }

    private fun BluetoothProfile.call(name: String, d: BluetoothDevice, vararg extra: Int): Boolean = runCatching {
        val types = arrayOf<Class<*>>(BluetoothDevice::class.java) + extra.map { Int::class.javaPrimitiveType!! }
        javaClass.getMethod(name, *types).invoke(this, d, *extra.toTypedArray()) as Boolean
    }.onFailure { Log.w(TAG, "$name failed", it) }.getOrDefault(false)

    /** Android's own "untethered headset" metadata: battery in Bluetooth settings and the output switcher. */
    override fun setBattery(mac: String, left: Int, right: Int, caseLevel: Int, leftCharging: Boolean, rightCharging: Boolean, caseCharging: Boolean) {
        val d = adapter.getRemoteDevice(mac)
        val set = BluetoothDevice::class.java.getMethod("setMetadata", Int::class.javaPrimitiveType, ByteArray::class.java)
        fun put(key: String, value: String) = runCatching {
            set.invoke(d, BluetoothDevice::class.java.getField(key).getInt(null), value.toByteArray())
        }.onFailure { Log.w(TAG, "metadata $key failed", it) }
        put("METADATA_IS_UNTETHERED_HEADSET", "true")
        put("METADATA_DEVICE_TYPE", "Untethered Headset")
        put("METADATA_MANUFACTURER_NAME", "Apple")
        put("METADATA_COMPANION_APP", "app.materialpods")
        // -1 = unknown (bud or case not reporting)
        put("METADATA_UNTETHERED_LEFT_BATTERY", "$left"); put("METADATA_UNTETHERED_LEFT_CHARGING", "$leftCharging")
        put("METADATA_UNTETHERED_RIGHT_BATTERY", "$right"); put("METADATA_UNTETHERED_RIGHT_CHARGING", "$rightCharging")
        put("METADATA_UNTETHERED_CASE_BATTERY", "$caseLevel"); put("METADATA_UNTETHERED_CASE_CHARGING", "$caseCharging")
    }

    /** Allow and connect both music (A2DP) and calls (HFP) — what the Bluetooth settings "connect" does. */
    override fun connectAudio(mac: String): Boolean {
        val d = adapter.getRemoteDevice(mac)
        return listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).map { p ->
            val proxy = proxy(p) ?: return@map false
            proxy.call("setConnectionPolicy", d, 100) // CONNECTION_POLICY_ALLOWED
            proxy.call("connect", d)
        }.any { it }
    }

    /** After the AirPods mic stream ends they stay in a one-sided mode until A2DP reconnects. */
    override fun reconnectA2dp(mac: String): Boolean {
        val d = adapter.getRemoteDevice(mac)
        val a2dp = proxy(BluetoothProfile.A2DP) ?: return false
        a2dp.call("disconnect", d)
        Thread.sleep(700)
        return a2dp.call("connect", d)
    }

    override fun destroy() = exitProcess(0)

    companion object {
        private const val TAG = "MaterialPodsPriv"
        /** For [ShellHelper] over adb, which already has a shell context. */
        fun with(ctx: Context) = PrivilegedService(ctx)
    }
}
