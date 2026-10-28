package app.ultimate.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ultimate.core.ShellActions
import app.ultimate.core.ToolBackButton
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The latest values and a short history for one sensor. */
private class Live(val sensor: Sensor) {
    var values by mutableStateOf(FloatArray(0))
    val history = ArrayDeque<Float>()
    var tick by mutableIntStateOf(0)
}

private const val HISTORY = 120

/** Listens to every continuous sensor while the screen is visible, at UI rate, and stops when it leaves. */
@Composable
private fun rememberSensors(): List<Live> {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val live = remember {
        sm.getSensorList(Sensor.TYPE_ALL)
            .filter { it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
            .distinctBy { it.type to it.name }
            .map { Live(it) }
    }
    DisposableEffect(Unit) {
        val listeners = live.map { l ->
            object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    l.values = e.values.copyOf()
                    l.history.addLast(if (e.values.size >= 3 && l.sensor.type != Sensor.TYPE_ROTATION_VECTOR) SensorMath.magnitude(e.values.copyOf(3)) else e.values.firstOrNull() ?: 0f)
                    while (l.history.size > HISTORY) l.history.removeFirst()
                    l.tick++
                }
                override fun onAccuracyChanged(s: Sensor, a: Int) = Unit
            }.also { runCatching { sm.registerListener(it, l.sensor, SensorManager.SENSOR_DELAY_UI) } }
        }
        onDispose { listeners.forEach { sm.unregisterListener(it) } }
    }
    return live
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val live = rememberSensors()
    fun of(type: Int) = live.firstOrNull { it.sensor.type == type }
    Scaffold(topBar = { TopAppBar(title = { Text("Sensors") }, navigationIcon = { ToolBackButton(onExit) }, actions = { ShellActions() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    (of(Sensor.TYPE_ROTATION_VECTOR) ?: of(Sensor.TYPE_GAME_ROTATION_VECTOR))?.let { item { Compass(it) } }
                    of(Sensor.TYPE_ACCELEROMETER)?.let { item { Level(it) }; item { GForce(it) } }
                    of(Sensor.TYPE_LIGHT)?.let { item { Light(it) } }
                    of(Sensor.TYPE_PRESSURE)?.let { item { Barometer(it) } }
                    of(Sensor.TYPE_MAGNETIC_FIELD)?.let { item { Metal(it) } }
                }
            }
            item {
                Text("${live.size} sensors", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(live, key = { it.sensor.type.toString() + it.sensor.name }) { SensorCard(it) }
        }
    }
}

@Composable
private fun Hero(title: String, value: String, sub: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp), modifier = Modifier.width(200.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.size(150.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { content() }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Compass(l: Live) {
    val v = l.values
    var azimuth = 0f
    if (v.size >= 3) {
        val m = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(m, v.copyOf(min(v.size, 5)))
        SensorManager.getOrientation(m, o)
        azimuth = ((Math.toDegrees(o[0].toDouble()).toFloat()) + 360) % 360
    }
    val shown by animateFloatAsState(-azimuth, spring(stiffness = 60f), label = "dial")
    val ring = MaterialTheme.colorScheme.outlineVariant
    val north = MaterialTheme.colorScheme.error
    val text = MaterialTheme.colorScheme.onSurface
    Hero("Compass", "${azimuth.toInt()}° ${SensorMath.compassPoint(azimuth)}", "Hold the phone flat") {
        Canvas(Modifier.fillMaxSize().rotate(shown)) {
            val r = size.minDimension / 2
            drawCircle(ring, r, style = Stroke(2.dp.toPx()))
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0)
                val len = if (i % 18 == 0) 14.dp.toPx() else if (i % 2 == 0) 7.dp.toPx() else 4.dp.toPx()
                drawLine(text.copy(alpha = 0.6f), Offset(center.x + (r - len) * sin(a).toFloat(), center.y - (r - len) * cos(a).toFloat()),
                    Offset(center.x + r * sin(a).toFloat(), center.y - r * cos(a).toFloat()), 1.5.dp.toPx())
            }
            val needle = Path().apply { moveTo(center.x, center.y - r * 0.75f); lineTo(center.x - 8.dp.toPx(), center.y); lineTo(center.x + 8.dp.toPx(), center.y); close() }
            drawPath(needle, north)
        }
    }
}

@Composable
private fun Level(l: Live) {
    val (pitch, roll) = if (l.values.size >= 3) SensorMath.tilt(l.values) else 0f to 0f
    val bx by animateFloatAsState((-roll / 45f).coerceIn(-1f, 1f), label = "x")
    val by by animateFloatAsState((pitch / 45f).coerceIn(-1f, 1f), label = "y")
    val flat = kotlin.math.abs(pitch) < 1.5f && kotlin.math.abs(roll) < 1.5f
    val color = if (flat) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    Hero("Level", String.format(Locale.US, "%.1f° · %.1f°", pitch, roll), if (flat) "Perfectly level" else "Tilt to centre the bubble") {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(ring, r, style = Stroke(2.dp.toPx())); drawCircle(ring, r * 0.2f, style = Stroke(1.dp.toPx()))
            drawCircle(color, r * 0.16f, Offset(center.x + bx * r * 0.8f, center.y + by * r * 0.8f))
        }
    }
}

@Composable
private fun GForce(l: Live) {
    val g = if (l.values.size >= 3) SensorMath.gForce(l.values) else 0f
    val x by animateFloatAsState(if (l.values.size >= 3) (l.values[0] / SensorMath.G / 2).coerceIn(-1f, 1f) else 0f, label = "gx")
    val y by animateFloatAsState(if (l.values.size >= 3) (l.values[1] / SensorMath.G / 2).coerceIn(-1f, 1f) else 0f, label = "gy")
    val primary = MaterialTheme.colorScheme.tertiary
    val ring = MaterialTheme.colorScheme.outlineVariant
    Hero("G-force", String.format(Locale.US, "%.2f g", g), "1 g is gravity at rest") {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            listOf(1f, 0.5f).forEach { drawCircle(ring, r * it, style = Stroke(1.dp.toPx())) }
            drawLine(ring, Offset(0f, center.y), Offset(size.width, center.y)); drawLine(ring, Offset(center.x, 0f), Offset(center.x, size.height))
            drawCircle(primary, 9.dp.toPx(), Offset(center.x - x * r, center.y + y * r))
        }
    }
}

@Composable
private fun Light(l: Live) {
    val lux = l.values.firstOrNull() ?: 0f
    val frac by animateFloatAsState((kotlin.math.log10(lux + 1) / 5f).coerceIn(0f, 1f), label = "lux")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Hero("Light", "${lux.toInt()} lux", SensorMath.lightDescription(lux)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 14.dp.toPx()
            drawArc(track, 135f, 270f, false, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(Color(0xFFFFC107), 135f, 270f * frac, false, style = Stroke(s, cap = StrokeCap.Round))
            drawCircle(Color(0xFFFFC107).copy(alpha = 0.25f + 0.75f * frac), size.minDimension * 0.18f)
        }
    }
}

@Composable
private fun Barometer(l: Live) {
    val hPa = l.values.firstOrNull() ?: 0f
    Hero("Barometer", String.format(Locale.US, "%.1f hPa", hPa), "≈ ${SensorMath.altitude(hPa).toInt()} m above sea level") { Spark(l, MaterialTheme.colorScheme.primary) }
}

@Composable
private fun Metal(l: Live) {
    val ut = if (l.values.size >= 3) SensorMath.magnitude(l.values.copyOf(3)) else 0f
    val strong = ut > 100
    Hero("Metal detector", "${ut.toInt()} µT", if (strong) "Something magnetic is close" else "Earth's field is 25–65 µT") {
        Spark(l, if (strong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun Spark(l: Live, color: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    @Suppress("UNUSED_EXPRESSION") l.tick
    val points = l.history.toList()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min(); val hi = points.max(); val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / (HISTORY - 1); val y = size.height - (v - lo) / span * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun typeName(s: Sensor): String = s.stringType.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun unit(type: Int) = when (type) {
    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "m/s²"
    Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
    Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
    Sensor.TYPE_LIGHT -> "lux"
    Sensor.TYPE_PRESSURE -> "hPa"
    Sensor.TYPE_PROXIMITY -> "cm"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
    Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
    Sensor.TYPE_HEART_RATE -> "bpm"
    else -> ""
}

@Composable
private fun SensorCard(l: Live) {
    var open by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") l.tick
    val s = l.sensor
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { open = !open },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (l.history.isNotEmpty()) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeName(s), fontWeight = FontWeight.SemiBold)
                    Text(s.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spark(l, MaterialTheme.colorScheme.primary, Modifier.width(90.dp).height(32.dp))
            }
            Text(
                if (l.values.isEmpty()) "Waiting for a reading…" else l.values.take(3).joinToString("   ") { String.format(Locale.US, "%.2f", it) } + " " + unit(s.type),
                Modifier.padding(top = 6.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            )
            if (open) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(
                    "Maker" to s.vendor, "Range" to "${s.maximumRange} ${unit(s.type)}", "Resolution" to "${s.resolution}",
                    "Power" to "${s.power} mA", "Fastest rate" to if (s.minDelay > 0) "${1_000_000 / s.minDelay} Hz" else "on change",
                    "Wakes the phone" to if (s.isWakeUpSensor) "yes" else "no",
                ).forEach { (k, v) -> Row { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
            }
        }
    }
}
