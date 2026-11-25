package app.materialpods

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelUuid
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.BLEManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatterySource
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val TAG = "MaterialPods"

data class RecState(val active: Boolean = false, val startedAt: Long = 0, val level: Float = 0f, val saved: Uri? = null)

/** Process-wide state shared by the activity, the service and the widget. */
@SuppressLint("MissingPermission")
object Pods {
    init {
        System.loadLibrary("hiddenapi") // exempts the hidden BluetoothSocket constructor core uses
    }

    private val AACP_UUID = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The paired AirPods; kept while disconnected so BLE adverts can still update battery. */
    val device = MutableStateFlow<AppleDevice?>(null)
    /** AACP (control) link is up — needed for modes/settings, not for battery. */
    val connected = MutableStateFlow(false)
    val rec = MutableStateFlow(RecState())

    /** Create the device object for the bonded AirPods without connecting. */
    fun ensureDevice(ctx: Context): AppleDevice? = device.value ?: if (
        ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) null else synchronized(this) {
        device.value ?: bonded(ctx)?.let { bt ->
            val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
            AppleDevice(ctx.applicationContext, adapter, bt, ConnectionState.DISCONNECTED).also { d ->
                init(ctx)
                device.value = d
                // Whatever updates state (AACP or BLE adverts): cache case, keep keys, refresh widgets.
                scope.launch {
                    d.state.onEach { rememberCase(it); saveKeys(ctx.applicationContext, it) }
                        .map { st -> st.battery.map { it.level to it.status } to mode(st) }
                        .distinctUntilChanged()
                        .collect { updateWidgets(ctx.applicationContext) }
                }
            }
        }
    }

    /** Page the AirPods by opening the control link; on success the service takes over. */
    fun connectNow(ctx: Context) = scope.launch {
        val d = ensureDevice(ctx) ?: return@launch
        d.aacp.disconnect()
        if (d.connect() && d.connectionState.value == ConnectionState.CONNECTED)
            ctx.startForegroundService(Intent(ctx, PodsService::class.java))
    }

    // --- BLE adverts: battery + lid without a connection; decrypted with keys the AirPods share over AACP ---

    private var ble: BLEManager? = null

    fun saveKeys(ctx: Context, s: AppleState) = listOf(MagicKeyType.IRK to "irk", MagicKeyType.ENC_KEY to "enc").forEach { (t, k) ->
        s.magicKeys[t]?.let { v -> prefs(ctx).edit().putString(k, v.toHexString()).apply() }
    }

    private fun key(ctx: Context, k: String) = prefs(ctx).getString(k, null)?.hexToByteArray()

    @Synchronized
    fun startBle(ctx: Context) {
        if (ble != null) return
        val d = ensureDevice(ctx) ?: return
        val irk = key(ctx, "irk"); val enc = key(ctx, "enc")
        if (irk == null || enc == null) return // keys arrive on first AACP connection
        ble = BLEManager(
            context = ctx.applicationContext, scope = scope,
            scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY, reportDelay = 500,
            getDevices = { mapOf(d.macAddress to d) },
            irkMap = mapOf(d.macAddress to irk), encKeyMap = mapOf(d.macAddress to enc),
        ).also { it.start() }
    }

    @Synchronized
    fun stopBle() { ble?.stop(); ble = null }

    fun isAirPods(bt: BluetoothDevice) = bt.uuids?.contains(AACP_UUID) == true

    // ponytail: first bonded AirPods only; a picker when someone has two pairs.
    fun bonded(ctx: Context): BluetoothDevice? =
        ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.firstOrNull(::isAirPods)

    fun aclConnected(bt: BluetoothDevice) =
        BluetoothDevice::class.java.getMethod("isConnected").invoke(bt) as Boolean

    private fun live(s: AppleState, c: BatteryComponent) =
        s.battery.firstOrNull { it.component == c && it.status != BatteryStatus.DISCONNECTED }

    /** Live level; for the case, falls back to the last known reading (see [caseAge]). */
    fun battery(s: AppleState, c: BatteryComponent) =
        live(s, c) ?: if (c == BatteryComponent.CASE) lastCase.value else null

    // --- last known case battery: AirPods only report it while a bud is in the case ---

    val lastCase = MutableStateFlow<Battery?>(null) // timestamp = when it was read
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = prefs(ctx)
        val level = p.getInt("case_level", -1)
        if (level >= 0) lastCase.value = Battery(BatteryComponent.CASE, level, BatteryStatus.NOT_CHARGING, BatterySource.AACP, p.getLong("case_at", 0))
    }

    fun rememberCase(s: AppleState) {
        val b = live(s, BatteryComponent.CASE) ?: return
        val now = System.currentTimeMillis()
        lastCase.value = b.copy(timestamp = now)
        app?.let { prefs(it).edit().putInt("case_level", b.level).putLong("case_at", now).apply() }
    }

    /** null when the case reading is live, else how old the cached one is ("5m", "2h", "3d"). */
    fun caseAge(s: AppleState): String? {
        if (live(s, BatteryComponent.CASE) != null) return null
        val at = lastCase.value?.timestamp ?: return null
        val min = (System.currentTimeMillis() - at) / 60_000
        return when {
            min < 1 -> "now"
            min < 60 -> "${min}m"
            min < 60 * 24 -> "${min / 60}h"
            else -> "${min / (60 * 24)}d"
        }
    }

    fun caseLabel(s: AppleState?) = "Case" + (s?.let(::caseAge)?.let { " · $it" } ?: "")

    fun mode(s: AppleState) = s.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt()

    fun setMode(value: Int) = scope.launch {
        device.value?.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, value.toByte())
    }

    // AACP booleans: 0x01 on, 0x02 off.
    fun flag(s: AppleState, id: ControlCommandIdentifier) = s.controlStates[id]?.getOrNull(0)?.toInt() == 1
    fun value(s: AppleState, id: ControlCommandIdentifier) = s.controlStates[id]?.getOrNull(0)?.toInt()?.and(0xFF)
    fun set(id: ControlCommandIdentifier, on: Boolean) = scope.launch { device.value?.setControlCommand(id, on) }
    fun set(id: ControlCommandIdentifier, v: Int) = scope.launch { device.value?.setControlCommand(id, v) }

    /** Modes the stem press-and-hold cycles through (bit n-1 for mode n). */
    fun cycle(s: AppleState): List<Int> {
        val bits = value(s, ControlCommandIdentifier.LISTENING_MODE_CONFIGS) ?: 0
        return (1..4).filter { bits and (1 shl (it - 1)) != 0 }.ifEmpty { listOf(2, 3) }
    }
    fun toggleCycle(mode: Int) = scope.launch { device.value?.toggleListeningMode(1 shl (mode - 1)) }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("materialpods", Context.MODE_PRIVATE)
    fun autoPause(ctx: Context) = prefs(ctx).getBoolean("auto_pause", true)

    // --- high quality mic: AAC-ELD over AACP, so A2DP music keeps playing (no HFP/SCO switch) ---

    private var recorder: MicRecorder? = null
    private var micJob: Job? = null

    fun startRecording(ctx: Context) {
        val d = device.value ?: return
        if (rec.value.active) return
        val r = MicRecorder(File(ctx.cacheDir, "rec.wav")).also { recorder = it }
        rec.value = RecState(active = true, startedAt = System.currentTimeMillis())
        micJob = scope.launch {
            d.microphoneFrames.collect { f ->
                r.feed(f.accessUnit)
                rec.update { it.copy(level = r.level) }
            }
        }
        d.requestMicrophoneStream()
    }

    fun stopRecording(ctx: Context) = scope.launch {
        val r = recorder ?: return@launch
        recorder = null
        device.value?.aacp?.endMicrophoneStream()
        micJob?.cancel()
        r.close()
        rec.value = RecState(saved = r.saveToMediaStore(ctx))
    }
}

/** Owns the AACP connection while the AirPods are connected; started by [PodsReceiver] or the app. */
@SuppressLint("MissingPermission")
class PodsService : Service() {
    private var watch: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Pods.init(this)
        Nearby.start(this)
        startForeground(1, notification("Connecting…", ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // Always reconnect: core never notices a remote socket close (e.g. Bluetooth toggled),
        // so an old device can look CONNECTED while its socket is dead.
        connecting?.cancel()
        connecting = connect()
        return START_NOT_STICKY
    }

    private var connecting: Job? = null

    private fun drop() {
        watch?.cancel()
        Pods.device.value?.let { if (Pods.rec.value.active) Pods.stopRecording(this); it.aacp.disconnect() }
        Pods.connected.value = false
    }

    private fun connect() = Pods.scope.launch {
        drop()
        val d = Pods.ensureDevice(this@PodsService)
        if (d == null || !Pods.aclConnected(d.bluetoothDevice)) return@launch stopSelf()
        // AACP sometimes isn't ready right after ACL comes up.
        repeat(4) {
            if (d.connectionState.value == ConnectionState.CONNECTED) return@repeat
            if (it > 0) delay(2000)
            d.connect()
        }
        if (d.connectionState.value != ConnectionState.CONNECTED) return@launch stopSelf()
        Pods.connected.value = true
        watch = launch {
            launch {
                combineState(d).collect { (title, text) ->
                    getSystemService(NotificationManager::class.java).notify(1, notification(title, text))
                }
            }
            launch { autoPause(d) }
        }
    }

    /** Pause when a bud comes out (only if something is playing), resume when it goes back in. */
    private suspend fun autoPause(d: AppleDevice) {
        val am = getSystemService(AudioManager::class.java)
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        var prev = -1
        var pausedAt = -1 // in-ear count before we paused
        d.state.map { s -> s.componentState.count { it.status == ComponentStatus.IN_EAR } }.distinctUntilChanged().collect { inEar ->
            Log.i(TAG, "in ear: $prev -> $inEar, music active: ${am.isMusicActive}, pausedAt: $pausedAt")
            if (Pods.autoPause(this)) when {
                prev > 0 && inEar < prev && am.isMusicActive -> { key(KeyEvent.KEYCODE_MEDIA_PAUSE); pausedAt = prev }
                pausedAt > 0 && inEar >= pausedAt -> { key(KeyEvent.KEYCODE_MEDIA_PLAY); pausedAt = -1 }
                inEar == 0 -> pausedAt = -1 // both out: don't surprise-resume later
            }
            prev = inEar
        }
    }

    private fun combineState(d: AppleDevice) = d.state.map { s ->
        fun pct(c: BatteryComponent) = Pods.battery(s, c)?.let { "${it.level}%" + if (it.status == BatteryStatus.CHARGING) "⚡" else "" } ?: "–"
        val mode = MODES.firstOrNull { it.first == Pods.mode(s) }?.second ?: ""
        d.metadata.value.name to "L ${pct(BatteryComponent.LEFT)}  ·  R ${pct(BatteryComponent.RIGHT)}  ·  ${Pods.caseLabel(s)} ${pct(BatteryComponent.CASE)}  ·  $mode"
    }.distinctUntilChanged()

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pods", "AirPods status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "pods")
            .setSmallIcon(R.drawable.ic_stat_pods)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        connecting?.cancel()
        drop()
        Pods.scope.launch { updateWidgets(applicationContext) }
        super.onDestroy()
    }
}

/** ACL broadcasts are exempt from implicit-broadcast and background-FGS-start limits. */
class PodsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val bt = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!Pods.isAirPods(bt)) return
        val svc = Intent(ctx, PodsService::class.java)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> ctx.startForegroundService(svc)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ctx.stopService(svc)
        }
    }
}

/** Quick Settings tile: tap cycles through the press-and-hold listening modes. */
class ModeTile : TileService() {
    override fun onStartListening() = refresh(null)

    override fun onClick() {
        if (!Pods.connected.value) return
        val s = Pods.device.value?.state?.value ?: return
        val cycle = Pods.cycle(s)
        val next = cycle[(cycle.indexOf(Pods.mode(s)) + 1) % cycle.size]
        Pods.setMode(next)
        refresh(next)
    }

    private fun refresh(mode: Int?) {
        val t = qsTile ?: return
        val s = Pods.device.value?.state?.value?.takeIf { Pods.connected.value }
        val m = mode ?: s?.let { Pods.mode(it) }
        t.state = when {
            s == null -> Tile.STATE_UNAVAILABLE
            m == 1 -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        t.label = MODES.firstOrNull { it.first == m }?.second ?: "AirPods"
        t.subtitle = if (s == null) "Not connected" else "Listening mode"
        t.updateTile()
    }
}

// AACP listening mode values.
val MODES = listOf(1 to "Off", 2 to "Noise Cancel", 3 to "Transparency", 4 to "Adaptive")

/** Decodes the AirPods' AAC-ELD mic frames with the platform decoder and writes a 16-bit WAV. */
class MicRecorder(private val file: File) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 64_000, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        // AudioSpecificConfig: ER AAC ELD, sampling index 2 = 64 kHz, mono.
        // LibrePods uses index 3 (48 kHz), but measured sample throughput vs. wall clock is 64 kHz.
        f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE4.toByte(), 0x30, 0x00)))
        configure(f, null, null, 0)
        start()
    }
    private val info = MediaCodec.BufferInfo()
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var rate = 64_000
    private var channels = 1
    private var bytes = 0L
    var level = 0f
        private set

    @Synchronized
    fun feed(au: ByteArray) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            codec.getInputBuffer(i)!!.apply { clear(); put(au) }
            codec.queueInputBuffer(i, 0, au.size, 0, 0)
        }
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    val pcm = ByteArray(info.size)
                    codec.getOutputBuffer(o)!!.apply { position(info.offset); get(pcm) }
                    codec.releaseOutputBuffer(o, false)
                    raf.write(pcm)
                    bytes += pcm.size
                    level = rms(pcm)
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                    rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.i(TAG, "mic stream: $rate Hz, $channels ch")
                }
                else -> break
            }
        }
    }

    private fun rms(pcm: ByteArray): Float {
        val s = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (!s.hasRemaining()) return 0f
        var sum = 0.0
        val n = s.remaining()
        repeat(n) { val v = s.get() / 32768.0; sum += v * v }
        return (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    override fun close() {
        runCatching { codec.stop(); codec.release() }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(bytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }

    fun saveToMediaStore(ctx: Context): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "AirPods ${System.currentTimeMillis()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/MaterialPods")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        return uri
    }
}
