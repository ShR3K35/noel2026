package fr.relance.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object C {
    val Ground = Color(0xFFF4F5F2)
    val Ink = Color(0xFF14181B)
    val Muted = Color(0xFF5B6268)
    val Accent = Color(0xFF0B6E4F)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFC9CEC6)
    val Soft = Color(0xFFE8EDE6)
    val Track = Color(0xFFE3E6E0)
    val Night = Color(0xFF0F1416)
    val NightCard = Color(0xFF1B2326)
    val NightLine = Color(0xFF333D40)
    val NightMuted = Color(0xFFA9B3AE)
    val Mint = Color(0xFF4ADE9A)
    val Orange = Color(0xFFFFA85C)
    val Sweat = Color(0xFF8FD3FF)
    val Zones = listOf(Color(0xFFF6D9BD), Color(0xFFEDAA6E), Color(0xFFCC6A1F), Color(0xFF8A3F0B), Color(0xFF4A2005))
}

object Dates {
    private val fr = Locale.FRANCE
    fun date(day: Long): LocalDate = LocalDate.ofEpochDay(day)
    fun short(day: Long): String = date(day).let {
        "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, fr)} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, fr)}"
    }
    fun long(day: Long): String = date(day).let {
        "${it.dayOfWeek.getDisplayName(TextStyle.FULL, fr)} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.FULL, fr)}"
    }.replaceFirstChar { it.uppercase() }
    fun dow(day: Long): String = date(day).dayOfWeek.getDisplayName(TextStyle.SHORT, fr).uppercase().removeSuffix(".")
    fun dom(day: Long): String = date(day).dayOfMonth.toString()
    fun relative(day: Long, today: Long): String = when (day - today) {
        0L -> "aujourd'hui"
        1L -> "demain"
        else -> date(day).dayOfWeek.getDisplayName(TextStyle.FULL, fr) + " " + date(day).dayOfMonth
    }
}

@Composable
fun Title(text: String, color: Color = C.Ink) =
    Text(text, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)

@Composable
fun Label(text: String, color: Color = C.Muted) =
    Text(text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

@Composable
fun Body(text: String, color: Color = C.Ink, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 15.sp, lineHeight = 21.sp, modifier = modifier)

@Composable
fun Small(text: String, color: Color = C.Muted, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier)

@Composable
fun Strong(text: String, color: Color = C.Ink, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

@Composable
fun Card(
    modifier: Modifier = Modifier, color: Color = C.Card, padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color)
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

/** Bouton : plein (action principale) ou contour. Zone tactile d'au moins 48 dp. */
@Composable
fun Btn(
    text: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true,
    fill: Color = C.Accent, onFill: Color = Color.White, outline: Color = C.Line, onOutline: Color = C.Ink,
    height: Dp = 52.dp, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (filled) (if (enabled) fill else C.Line) else Color.Transparent
    Box(
        modifier.heightIn(min = height).clip(shape).background(bg)
            .then(if (filled) Modifier else Modifier.border(1.5.dp, outline, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (filled) onFill else onOutline, fontSize = 16.sp,
            fontWeight = if (filled) FontWeight.Bold else FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
    }
}

/** Choix sélectionnable : la sélection se lit par le remplissage, pas seulement par la teinte. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).background(if (selected) C.Accent else C.Card)
            .border(1.5.dp, if (selected) C.Accent else C.Line, shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (selected) Color.White else C.Ink, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> Chip(o, i == selected, Modifier.weight(1f)) { onSelect(i) } }
    }
}

@Composable
fun Stepper(value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Btn("−", Modifier.width(56.dp), filled = false, onClick = onMinus)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(" $unit", fontSize = 15.sp, color = C.Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        Btn("+", Modifier.width(56.dp), filled = false, onClick = onPlus)
    }
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = C.Ink, labelColor: Color = C.Muted) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = valueColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = labelColor, fontSize = 12.sp)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color = C.Accent, track: Color = C.Track) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).heightIn(min = 8.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
fun SettingRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Strong(title)
        Text(value, color = if (onClick != null) C.Accent else C.Muted, fontSize = 15.sp, fontWeight = if (onClick != null) FontWeight.Bold else FontWeight.Normal)
    }
}
