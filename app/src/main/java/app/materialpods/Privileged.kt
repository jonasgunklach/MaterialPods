package app.materialpods

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** Optional system integration through Shizuku: shell rights without root. */
object Privileged {
    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, STARTING, READY }

    const val SHIZUKU = "moe.shizuku.privileged.api"
    val status = MutableStateFlow(Status.NOT_RUNNING)
    @Volatile private var service: IPrivileged? = null
    private var app: Context? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName("app.materialpods", PrivilegedService::class.java.name))
            .processNameSuffix("privileged").daemon(false).version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPrivileged.Stub.asInterface(binder)
            status.value = Status.READY
            Pods.device.value?.let { pushBattery(it.bluetoothDevice.address, it.state.value) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; refresh() }
    }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { service = null; refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        val ctx = app ?: return
        status.value = when {
            runCatching { ctx.packageManager.getPackageInfo(SHIZUKU, 0) }.isFailure -> Status.NOT_INSTALLED
            !Shizuku.pingBinder() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            service != null -> Status.READY
            else -> { runCatching { Shizuku.bindUserService(args, connection) }; Status.STARTING }
        }
    }

    fun requestPermission() = runCatching { Shizuku.requestPermission(1) }

    private fun call(what: String, block: (IPrivileged) -> Unit) {
        val s = service ?: return
        Pods.scope.launch { runCatching { block(s) }.onFailure { Log.w("Privileged", "$what failed", it) } }
    }

    private var lastBattery: List<Int>? = null

    /** Mirror battery into Android's Bluetooth device (settings, output switcher). */
    fun pushBattery(mac: String, s: me.kavishdevar.librepods.core.devices.AppleState) {
        fun b(c: me.kavishdevar.librepods.core.devices.BatteryComponent) = Pods.battery(s, c)
        val l = b(me.kavishdevar.librepods.core.devices.BatteryComponent.LEFT)
        val r = b(me.kavishdevar.librepods.core.devices.BatteryComponent.RIGHT)
        val c = b(me.kavishdevar.librepods.core.devices.BatteryComponent.CASE)
        val charging = me.kavishdevar.librepods.core.devices.BatteryStatus.CHARGING
        val snapshot = listOf(l?.level ?: -1, r?.level ?: -1, c?.level ?: -1,
            if (l?.status == charging) 1 else 0, if (r?.status == charging) 1 else 0, if (c?.status == charging) 1 else 0)
        if (snapshot == lastBattery || service == null) return
        lastBattery = snapshot
        call("battery") { it.setBattery(mac, snapshot[0], snapshot[1], snapshot[2], snapshot[3] == 1, snapshot[4] == 1, snapshot[5] == 1) }
    }

    fun connectAudio(mac: String) = call("connect") { it.connectAudio(mac) }
    fun reconnectA2dp(mac: String) = call("reconnect") { it.reconnectA2dp(mac) }
}
