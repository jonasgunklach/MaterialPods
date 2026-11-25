package app.materialpods

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatterySource
import me.kavishdevar.librepods.core.devices.BatteryStatus

private val ModeKey = ActionParameters.Key<Int>("mode")

class SetModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[ModeKey]?.let { Pods.setMode(it) }
    }
}

/** Sample shown in the widget picker and the popup preview. */
val PREVIEW = AppleState(
    battery = setOf(
        Battery(BatteryComponent.LEFT, 82, BatteryStatus.NOT_CHARGING, BatterySource.AACP, 0),
        Battery(BatteryComponent.RIGHT, 78, BatteryStatus.NOT_CHARGING, BatterySource.AACP, 0),
        Battery(BatteryComponent.CASE, 64, BatteryStatus.CHARGING, BatterySource.AACP, 0),
    ),
    controlStates = mapOf(ControlCommandIdentifier.LISTENING_MODE to byteArrayOf(2)),
)

// --- state -> drawing inputs ---

fun cell(s: AppleState?, c: BatteryComponent, label: String, icon: Icon): Cell {
    val b = s?.let { Pods.battery(it, c) }
    return Cell(label, b?.level, b?.status == BatteryStatus.CHARGING, icon)
}

/** Buds as one number: the emptier bud is what runs out first. */
fun buds(s: AppleState?): Cell {
    val both = listOfNotNull(s?.let { Pods.battery(it, BatteryComponent.LEFT) }, s?.let { Pods.battery(it, BatteryComponent.RIGHT) })
    return Cell(if (s == null) "Not connected" else "AirPods", both.minOfOrNull { it.level }, both.any { it.status == BatteryStatus.CHARGING }, Icon.BUDS)
}

private fun modesOf(s: AppleState?) = MODES.map { it.first }
    .filter { it != 1 || (s != null && Pods.flag(s, ControlCommandIdentifier.ALLOW_OFF_OPTION)) }

@Composable
private fun pal(): Pal {
    val c = GlanceTheme.colors
    val ctx = LocalContext.current
    fun ColorProvider.i() = getColor(ctx).toArgb()
    return Pal(
        c.primary.i(), c.onPrimary.i(), c.primaryContainer.i(), c.onPrimaryContainer.i(),
        c.secondaryContainer.i(), c.onSecondaryContainer.i(), c.tertiary.i(), c.onTertiary.i(),
        c.inverseSurface.i(), c.inverseOnSurface.i(), c.inversePrimary.i(),
    )
}

/** Widget size in px (capped so the bitmap stays small); falls back to [fallbackDp] in previews. */
@Composable
private fun sizePx(fallbackW: Float, fallbackH: Float): Pair<Int, Int> {
    val s = LocalSize.current
    val w = s.width.value.takeIf { it > 1f } ?: fallbackW
    val h = s.height.value.takeIf { it > 1f } ?: fallbackH
    val density = LocalContext.current.resources.displayMetrics.density
    val scale = minOf(density, 900f / maxOf(w, h))
    return (w * scale).toInt() to (h * scale).toInt()
}

/** Base: live state, dynamic colors, one drawn bitmap; tap opens the app unless [Overlay] adds targets. */
abstract class PodsGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = Pods.init(context).let { provideContent {
        val d by Pods.device.collectAsState()
        val s = d?.state?.collectAsState()?.value
        GlanceTheme { Root(s) }
    } }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent {
        GlanceTheme { Root(PREVIEW) }
    }

    @Composable
    private fun Root(s: AppleState?) {
        val (w, h) = sizePx(fallback.first, fallback.second)
        Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
            Image(ImageProvider(draw(w, h, s, pal())), contentDescription = describe(s), contentScale = ContentScale.Fit, modifier = GlanceModifier.fillMaxSize())
            Overlay(s)
        }
    }

    open val fallback = 180f to 180f
    abstract fun draw(w: Int, h: Int, s: AppleState?, p: Pal): Bitmap
    abstract fun describe(s: AppleState?): String
    @Composable open fun Overlay(s: AppleState?) {}
}

class CookieWidget : PodsGlanceWidget() {
    override fun draw(w: Int, h: Int, s: AppleState?, p: Pal) = Art.cookie(w, h, buds(s), p)
    override fun describe(s: AppleState?) = "AirPods battery ${buds(s).level ?: "unknown"} percent"
}

class GaugeWidget : PodsGlanceWidget() {
    override val fallback = 320f to 150f
    private fun cells(s: AppleState?) = listOf(
        cell(s, BatteryComponent.LEFT, "Left", Icon.LEFT),
        cell(s, BatteryComponent.RIGHT, "Right", Icon.RIGHT),
        cell(s, BatteryComponent.CASE, Pods.caseLabel(s), Icon.CASE),
    )
    override fun draw(w: Int, h: Int, s: AppleState?, p: Pal) = Art.gauges(w, h, cells(s), p)
    override fun describe(s: AppleState?) = cells(s).joinToString { "${it.label} ${it.level ?: "unknown"} percent" }
}

class PillWidget : PodsGlanceWidget() {
    override val fallback = 240f to 80f
    override fun draw(w: Int, h: Int, s: AppleState?, p: Pal) =
        Art.pill(w, h, buds(s), cell(s, BatteryComponent.CASE, Pods.caseLabel(s), Icon.CASE), p)
    override fun describe(s: AppleState?) = "AirPods ${buds(s).level ?: "unknown"} percent"
}

class ModeWidget : PodsGlanceWidget() {
    override val fallback = 300f to 80f
    override fun draw(w: Int, h: Int, s: AppleState?, p: Pal) = Art.modes(w, h, modesOf(s), s?.let { Pods.mode(it) }, p)
    override fun describe(s: AppleState?) = "Listening mode: " + (MODES.firstOrNull { it.first == s?.let(Pods::mode) }?.second ?: "unknown")

    @Composable
    override fun Overlay(s: AppleState?) {
        if (s == null) return
        val h = LocalSize.current.height.value.takeIf { it > 1f } ?: fallback.second
        Row(GlanceModifier.fillMaxSize().padding(horizontal = Art.modesPad(h).dp)) {
            modesOf(s).forEach { m ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionRunCallback<SetModeAction>(actionParametersOf(ModeKey to m)))) {}
            }
        }
    }
}

class CookieWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CookieWidget() }
class GaugeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = GaugeWidget() }
class PillWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PillWidget() }
class ModeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = ModeWidget() }

val WIDGET_RECEIVERS = listOf(CookieWidgetReceiver::class, GaugeWidgetReceiver::class, PillWidgetReceiver::class, ModeWidgetReceiver::class)

/** Called whenever state changes. */
suspend fun updateWidgets(context: Context) {
    CookieWidget().updateAll(context)
    GaugeWidget().updateAll(context)
    PillWidget().updateAll(context)
    ModeWidget().updateAll(context)
}
