package app.materialpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Looper
import kotlin.system.exitProcess

/**
 * Runs as the shell user (uid 2000) via app_process — from adb now, from Shizuku later.
 * Shell holds BLUETOOTH_PRIVILEGED and MODIFY_AUDIO_ROUTING, which normal apps can't get.
 *
 *   hfp on|off            allow/forbid the AirPods' hands-free (call) profile
 *   route <uid>...        render these apps' audio on A2DP, even in communication mode
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
object ShellHelper {
    private const val SHELL = "com.android.shell"

    private fun log(s: String) = println("[helper] $s").also { System.out.flush() }

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val ctx = shellContext()
        when (args.getOrNull(0)) {
            "hfp" -> setHfp(ctx, args[1] == "on")
            "route" -> route(ctx, args.drop(1).map(String::toInt))
            else -> { log("usage: hfp on|off | route <uid>..."); exitProcess(1) }
        }
        Looper.loop()
    }

    // --- a Context that claims to be com.android.shell (pattern from scrcpy's FakeContext) ---

    private fun shellContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        // Wires up service lookups for mainline modules (Bluetooth is one) — normally done by app startup.
        at.getDeclaredMethod("initializeMainlineModules").invoke(null)
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        // Samsung's CompatSandbox reads the configuration while creating the system context.
        runCatching {
            val cc = Class.forName("android.app.ConfigurationController")
                .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                .apply { isAccessible = true }.newInstance(thread)
            at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, cc)
        }
        val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        return object : ContextWrapper(system) {
            private val audio by lazy { newManager(AudioManager::class.java, this) }
            private val bt by lazy { newManager(BluetoothManager::class.java, this) }
            override fun getPackageName() = SHELL
            override fun getOpPackageName() = SHELL
            override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName(SHELL).build()
            override fun getApplicationContext() = this
            override fun getSystemService(name: String): Any? = when (name) {
                AUDIO_SERVICE -> audio
                BLUETOOTH_SERVICE -> bt
                else -> super.getSystemService(name)
            }
        }
    }

    // Managers built on the system context would carry package "android"; build them on ours.
    private fun <T> newManager(cls: Class<T>, ctx: Context): T =
        cls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(ctx)

    // --- HFP connection policy ---

    private fun setHfp(ctx: Context, on: Boolean) {
        val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val aacp = android.os.ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        val dev = adapter.bondedDevices.firstOrNull { it.uuids?.contains(aacp) == true }
            ?: run { log("no bonded AirPods"); exitProcess(2) }
        log("AirPods: ${dev.name}")
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val policy = if (on) 100 else 0 // CONNECTION_POLICY_ALLOWED / _FORBIDDEN
                val ok = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    .invoke(proxy, dev, policy)
                log("HFP policy ${if (on) "allowed" else "forbidden"}: $ok")
                if (on) log("HFP connect: " + proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, dev))
                exitProcess(0)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    // --- dynamic audio policy: render matching apps on A2DP ---

    private fun route(ctx: Context, uids: List<Int>) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val a2dp = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: run { log("no A2DP output connected"); exitProcess(2) }

        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
        val matchUid = ruleCls.getField("RULE_MATCH_UID").getInt(null)
        val addRule = ruleB.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        uids.forEach { addRule.invoke(ruleB, matchUid, it) }
        val rule = ruleB.javaClass.getMethod("build").invoke(ruleB)

        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(ruleCls).newInstance(rule)
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        mixB.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixB, fmt)
        mixB.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixB, mixCls.getField("ROUTE_FLAG_RENDER").getInt(null))
        mixB.javaClass.getMethod("setDevice", AudioDeviceInfo::class.java).invoke(mixB, a2dp)
        val mix = mixB.javaClass.getMethod("build").invoke(mixB)

        val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(ctx)
        polB.javaClass.getMethod("addMix", mixCls).invoke(polB, mix)
        val policy = polB.javaClass.getMethod("build").invoke(polB)
        val res = AudioManager::class.java.getMethod("registerAudioPolicy", policyCls).invoke(am, policy)
        log("route uids=$uids -> A2DP '${a2dp.productName}': ${if (res == 0) "registered" else "FAILED ($res)"}")
        if (res != 0) exitProcess(3)
        // Policy lives as long as this process; kill it to undo.
    }
}
