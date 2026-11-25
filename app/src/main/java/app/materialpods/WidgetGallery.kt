package app.materialpods

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import kotlin.reflect.KClass

fun palOf(cs: ColorScheme) = Pal(
    cs.primary.toArgb(), cs.onPrimary.toArgb(), cs.primaryContainer.toArgb(), cs.onPrimaryContainer.toArgb(),
    cs.secondaryContainer.toArgb(), cs.onSecondaryContainer.toArgb(), cs.tertiary.toArgb(), cs.onTertiary.toArgb(),
    cs.inverseSurface.toArgb(), cs.inverseOnSurface.toArgb(), cs.inversePrimary.toArgb(),
)

/** Live previews of the home-screen widgets (same drawing code) with one-tap pinning. */
@Composable
fun WidgetGallery(s: AppleState) {
    val ctx = LocalContext.current
    val p = palOf(MaterialTheme.colorScheme)
    fun cell(c: BatteryComponent, label: String, icon: Icon) = Pods.battery(s, c).let { Cell(label, it?.level, it?.status == BatteryStatus.CHARGING, icon) }
    val l = cell(BatteryComponent.LEFT, "Left", Icon.LEFT)
    val r = cell(BatteryComponent.RIGHT, "Right", Icon.RIGHT)
    val case = cell(BatteryComponent.CASE, Pods.caseLabel(s), Icon.CASE)
    val buds = Cell("AirPods", listOfNotNull(l.level, r.level).minOrNull(), l.charging || r.charging, Icon.BUDS)
    val modes = MODES.map { it.first }.filter { it != 1 || Pods.flag(s, me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ALLOW_OFF_OPTION) }

    // (receiver, width dp, height dp, bitmap at 3x)
    val items: List<Triple<KClass<*>, Pair<Int, Int>, android.graphics.Bitmap>> = listOf(
        Triple(CookieWidgetReceiver::class, 150 to 150, Art.cookie(450, 450, buds, p)),
        Triple(GaugeWidgetReceiver::class, 320 to 150, Art.gauges(960, 450, listOf(l, r, case), p)),
        Triple(PillWidgetReceiver::class, 240 to 76, Art.pill(720, 228, buds, case, p)),
        Triple(ModeWidgetReceiver::class, 300 to 76, Art.modes(900, 228, modes, Pods.mode(s), p)),
    )

    Text("Widgets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        items.forEach { (receiver, size, bmp) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.width(size.first.dp).height(size.second.dp))
                FilledTonalButton(onClick = {
                    ctx.getSystemService(AppWidgetManager::class.java).requestPinAppWidget(ComponentName(ctx, receiver.java), null, null)
                }) { Text("Add") }
            }
        }
    }
}
