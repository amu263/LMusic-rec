package com.lalilu.component.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingStateSeekBar(
    state: MutableState<Int>,
    selection: List<String>,
    @StringRes titleRes: Int,
    @StringRes subTitleRes: Int? = null,
    paddingValues: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp)
) = SettingStateSeekBar(
    state = state,
    selection = selection,
    title = stringResource(id = titleRes),
    subTitle = subTitleRes?.let { stringResource(id = it) },
    paddingValues = paddingValues
)

@Composable
fun SettingStateSeekBar(
    state: MutableState<Int>,
    selection: List<String>,
    title: String,
    subTitle: String? = null,
    paddingValues: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp)
) = SettingStateSeekBar(
    state = { state.value },
    onStateUpdate = { state.value = it },
    selection = selection,
    title = title,
    subTitle = subTitle,
    paddingValues = paddingValues
)

@Composable
fun SettingStateSeekBar(
    state: () -> Int,
    onStateUpdate: (Int) -> Unit,
    selection: List<String>,
    title: String,
    subTitle: String? = null,
    paddingValues: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp)
) {
    var expanded by remember { mutableStateOf(false) }
    val textColor = contentColorFor(backgroundColor = MaterialTheme.colors.background)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(paddingValues),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = textColor, fontSize = 17.sp)
                if (subTitle != null) Text(subTitle, fontSize = 12.sp, color = SaltStyle.secondary)
            }
            Text(
                text = selection.getOrElse(state().coerceIn(0, selection.lastIndex)) { "" } + "  ⌄",
                color = SaltStyle.secondary,
                fontSize = 15.sp,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            selection.forEachIndexed { index, label ->
                DropdownMenuItem(onClick = {
                    onStateUpdate(index)
                    expanded = false
                }) {
                    Text(label, fontSize = 15.sp)
                }
            }
        }
    }
}
