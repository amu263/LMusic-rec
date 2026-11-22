package com.lalilu.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun SettingProgressSeekBar(
    value: () -> Float,
    onValueUpdate: (Float) -> Unit = {},
    onFinishedUpdate: (Float) -> Unit = {},
    title: String,
    subTitle: String? = null,
    valueRange: IntRange
) {
    var tempValue by remember(value()) { mutableFloatStateOf(value()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, color = SaltStyle.title, fontSize = 17.sp)
            Text(
                text = if (abs(tempValue - tempValue.toInt()) < 0.01f) "${tempValue.toInt()}"
                else String.format("%.1f", tempValue),
                color = SaltStyle.secondary,
                fontSize = 15.sp
            )
        }
        SaltSlider(
            value = tempValue,
            onValueChange = {
                tempValue = it
                onValueUpdate(it)
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        )
        if (subTitle != null) {
            Text(text = subTitle, fontSize = 12.sp, color = SaltStyle.secondary)
        }
    }
}
