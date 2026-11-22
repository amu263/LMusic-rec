package com.lalilu.component.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

object SaltStyle {
    val accent: Color @Composable get() = Color(0xFF4C5F87)
    val card: Color @Composable get() = if (MaterialTheme.colors.isLight) Color(0xFFE9EDF6) else Color(0xFF24252A)
    val page: Color @Composable get() = if (MaterialTheme.colors.isLight) Color(0xFFF3F4F8) else Color(0xFF111214)
    val title: Color @Composable get() = if (MaterialTheme.colors.isLight) Color(0xFF17181C) else Color(0xFFF2F2F4)
    val secondary: Color @Composable get() = title.copy(alpha = 0.52f)
    val track: Color @Composable get() = if (MaterialTheme.colors.isLight) Color(0xFFD9DDE7) else Color(0xFF3A3C44)
}

@Composable
fun SaltSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "saltSwitch")
    Box(
        modifier = modifier
            .size(width = 42.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) SaltStyle.accent else SaltStyle.track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = (3 + progress * 19).dp, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun SaltSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(valueRange) {
                detectTapGestures { position ->
                    val next = valueRange.start + (position.x / size.width) *
                        (valueRange.endInclusive - valueRange.start)
                    onValueChange(next.coerceIn(valueRange.start, valueRange.endInclusive))
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    val next = valueRange.start + (change.position.x / size.width) *
                        (valueRange.endInclusive - valueRange.start)
                    onValueChange(next.coerceIn(valueRange.start, valueRange.endInclusive))
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(SaltStyle.track)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(14.dp)
                .clip(CircleShape)
                .background(SaltStyle.accent)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - 24.dp) * fraction)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun SaltSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    subtitle: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 17.sp, color = SaltStyle.title)
            Text(valueText, fontSize = 17.sp, color = SaltStyle.title)
        }
        SaltSlider(value = value, onValueChange = onValueChange, valueRange = valueRange)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SaltStyle.secondary)
    }
}

@Composable
fun SaltValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 17.sp, color = SaltStyle.title)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SaltStyle.secondary)
        }
        Text(value, fontSize = 15.sp, color = SaltStyle.secondary)
        if (trailing != null) trailing()
    }
}

fun formatPercent(value: Float): String = "${value.roundToInt()}%"
