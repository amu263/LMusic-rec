package com.lalilu.lmusic.compose.new_screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import com.lalilu.R
import com.lalilu.component.settings.SaltSlider
import com.lalilu.component.settings.SaltStyle
import com.lalilu.component.settings.SaltSwitch
import com.lalilu.lplayer.MPlayer
import com.lalilu.lplayer.action.PlayerAction
import com.lalilu.remixicon.Arrows
import com.lalilu.remixicon.arrows.arrowLeftSLine
import com.lalilu.RemixIcon

@Composable
fun SaltPageHeader(
    title: String,
    subtitle: String? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { navigator?.pop() }) {
            Icon(
                imageVector = RemixIcon.Arrows.arrowLeftSLine,
                contentDescription = "返回",
                tint = SaltStyle.title,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 22.sp, color = SaltStyle.title)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SaltStyle.secondary)
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh_line),
                    contentDescription = "刷新",
                    tint = SaltStyle.title,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
fun SaltSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp),
            fontSize = 13.sp,
            color = SaltStyle.secondary,
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SaltStyle.card)
                .padding(vertical = 6.dp),
            content = content,
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun SaltCard(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (dark) Color(0xFF2B2B2E) else SaltStyle.card)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun SaltDropdownRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 17.sp, color = SaltStyle.title)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SaltStyle.secondary)
        }
        Text("$value  ⌄", fontSize = 15.sp, color = SaltStyle.secondary)
    }
}

@Composable
fun SaltDropdown(
    values: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    content: @Composable (open: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        content { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEachIndexed { index, label ->
                DropdownMenuItem(onClick = {
                    onSelect(index)
                    expanded = false
                }) { Text(label, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
fun SaltSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 17.sp, color = SaltStyle.title)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = SaltStyle.secondary)
        }
        SaltSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SaltValueLabel(label: String, value: String, valueColor: Color = SaltStyle.title) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = SaltStyle.secondary)
        Text(value, fontSize = 13.sp, color = valueColor)
    }
}

@Composable
fun SaltMiniPlayer(
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata = MPlayer.currentMediaMetadata
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (MaterialTheme.colors.isLight) Color(0xFFE3E7F0) else Color(0xFF24252A))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = metadata?.artworkUri ?: R.drawable.cover_placeholder,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                metadata?.title?.toString() ?: "等待播放",
                fontSize = 15.sp,
                color = SaltStyle.title,
                maxLines = 1,
            )
            Text(
                metadata?.artist?.toString() ?: "LMusic",
                fontSize = 12.sp,
                color = SaltStyle.secondary,
                maxLines = 1,
            )
        }
        IconButton(onClick = { MPlayer.doAction(PlayerAction.PlayOrPause) }) {
            Icon(
                painter = painterResource(if (MPlayer.isPlaying) R.drawable.ic_pause_line else R.drawable.ic_play_line),
                contentDescription = "播放/暂停",
                tint = SaltStyle.title,
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = onQueue) {
            Icon(
                painter = painterResource(R.drawable.ic_play_list_2_fill),
                contentDescription = "播放队列",
                tint = SaltStyle.title,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
fun HighResBadge() {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111)).padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text("Hi-Res", fontSize = 9.sp, color = Color(0xFFE7C873))
    }
}
