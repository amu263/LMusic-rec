package com.lalilu.component.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingCategory(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    content: @Composable ColumnScope.() -> Unit = {}
) = SettingCategory(
    icon = painterResource(id = iconRes),
    title = stringResource(id = titleRes),
    content = content
)

@Composable
fun SettingCategory(
    icon: Painter,
    title: String,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Text(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
            text = title,
            fontSize = 13.sp,
            color = SaltStyle.secondary,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SaltStyle.card)
                .padding(vertical = 6.dp),
            content = content,
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}
