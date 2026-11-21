package com.lalilu.lmusic.compose.new_screen

import android.hardware.usb.UsbManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.lalilu.R
import com.lalilu.RemixIcon
import com.lalilu.component.base.NavigatorHeader
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.rememberFixedStatusBarHeightDp
import com.lalilu.component.settings.SettingCategory
import com.lalilu.component.settings.SettingProgressSeekBar
import com.lalilu.component.settings.SettingStateSeekBar
import com.lalilu.component.settings.SettingSwitcher
import com.lalilu.lplayer.MPlayerKV
import com.lalilu.lplayer.audio.AudioSettings
import com.lalilu.lplayer.usb.UsbAudioManager
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.settings4Line
import com.zhangke.krouter.annotation.Destination
import kotlin.math.roundToInt

@Destination("/pages/audio_settings")
object AudioSettingsScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = AudioSettingsScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { "音频输出" },
            icon = RemixIcon.System.settings4Line,
        )
    }

    @Composable
    override fun Content() {
        AudioSettingsContent()
    }
}

@Composable
private fun AudioSettingsContent() {
    val context = LocalContext.current
    val decoderMode = MPlayerKV.decoderMode
    val outputSampleRate = MPlayerKV.outputSampleRate
    val outputBitDepth = MPlayerKV.outputBitDepth
    val dsdGain = MPlayerKV.dsdGainDb
    val eqEnabled = MPlayerKV.eqEnabled
    val eqBands = MPlayerKV.eqBands
    val gapless = MPlayerKV.gaplessEnabled
    val usbExclusive = MPlayerKV.usbExclusive

    val sampleRates = listOf(0, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    val bandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val bands = remember(eqBands.value) {
        (eqBands.value ?: "")
            .split(',')
            .mapNotNull { it.toFloatOrNull() }
            .let { if (it.size == 10) it else AudioSettings.eqBands.toList() }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = rememberFixedStatusBarHeightDp()
        )
    ) {
        item {
            NavigatorHeader(
                title = "音频输出",
                subTitle = "解码、采样率、位深与音效（Pro）"
            )
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.ic_loader_line),
                title = "解码引擎"
            ) {
                SettingStateSeekBar(
                    state = { decoderMode.value ?: 0 },
                    onStateUpdate = { decoderMode.value = it },
                    selection = listOf("自动（硬件优先）", "软解优先", "仅软解"),
                    title = "解码方式",
                    subTitle = "软解使用内置 FFmpeg，可播放 APE、DSD、WMA、WavPack、TTA、TAK、AIFF 等格式"
                )
                Text(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    text = "硬件解码由系统 MediaCodec 完成；软解优先会在硬件不支持时自动接管。修改后将在下次启动播放服务时生效。",
                    fontSize = 12.sp,
                    color = androidx.compose.material.MaterialTheme.colors.onBackground.copy(0.5f)
                )
            }
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.equalizer_line),
                title = "输出格式（Pro）"
            ) {
                SettingStateSeekBar(
                    state = { sampleRates.indexOf(outputSampleRate.value ?: 0).coerceAtLeast(0) },
                    onStateUpdate = { outputSampleRate.value = sampleRates[it] },
                    selection = listOf("自动", "44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz"),
                    title = "输出采样率",
                    subTitle = "自动模式保留源采样率，超过 192 kHz 的 DSD 会下采样到 192 kHz"
                )
                SettingStateSeekBar(
                    state = { if ((outputBitDepth.value ?: 16) == 32) 1 else 0 },
                    onStateUpdate = { outputBitDepth.value = if (it == 1) 32 else 16 },
                    selection = listOf("16-bit", "32-bit float"),
                    title = "输出位深",
                    subTitle = "32-bit float 需要设备支持浮点输出，可保留更多动态范围"
                )
                SettingProgressSeekBar(
                    value = { (dsdGain.value ?: 0).toFloat() },
                    onValueUpdate = { dsdGain.value = it.roundToInt() },
                    title = "DSD / 高采样率增益",
                    subTitle = "0 - 12 dB，用于 DSD 转 PCM 后的电平补偿",
                    valueRange = 0..12
                )
                SettingSwitcher(
                    state = { gapless.value != false },
                    onStateUpdate = { gapless.value = it },
                    title = "无间隙播放",
                    subTitle = "同专辑连续曲目之间不插入静音（取决于格式与解码器）"
                )
            }
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.equalizer_line),
                title = "均衡器（Pro）"
            ) {
                SettingSwitcher(
                    state = { eqEnabled.value == true },
                    onStateUpdate = { eqEnabled.value = it },
                    title = "启用 10 段均衡器",
                    subTitle = "实时生效，支持 ±12 dB 增益"
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("平直", "流行", "摇滚", "爵士", "古典", "低音", "人声", "轻音乐")
                        .forEachIndexed { index, label ->
                            TextButton(onClick = {
                                val gains = AudioSettings.presetGains(index)
                                eqBands.value = gains.joinToString(",")
                                eqEnabled.value = index != 0
                                Toast.makeText(context, "已应用「$label」预设", Toast.LENGTH_SHORT).show()
                            }) {
                                Text(text = label, fontSize = 12.sp)
                            }
                        }
                }
                bandLabels.forEachIndexed { index, label ->
                    SettingProgressSeekBar(
                        value = { bands.getOrElse(index) { 0f } },
                        onValueUpdate = { value ->
                            val updated = bands.toMutableList()
                            updated[index] = value
                            eqBands.value = updated.joinToString(",")
                        },
                        title = "$label Hz",
                        valueRange = -12..12
                    )
                }
            }
        }

        item {
            SettingCategory(
                icon = painterResource(id = R.drawable.ic_settings_4_line),
                title = "USB 音频（Pro）"
            ) {
                SettingSwitcher(
                    state = { usbExclusive.value == true },
                    onStateUpdate = { usbExclusive.value = it },
                    title = "USB 独占 / 直连模式",
                    subTitle = "优先将音频直接路由到 USB DAC；实际独占能力取决于设备与系统"
                )
                UsbAudioDeviceList()
            }
        }

        smartBarPadding()
    }
}

@Composable
private fun UsbAudioDeviceList() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(UsbAudioManager.listDevices(context)) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val selectedId = MPlayerKV.usbDeviceId.value

    val permissionReceiver = remember {
        UsbAudioManager.permissionReceiver { _, granted ->
            permissionTick++
            if (granted) Toast.makeText(context, "USB 权限已授予，请重新选择设备", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "USB 权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }
    val attachReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                devices = UsbAudioManager.listDevices(context)
            }
        }
    }

    DisposableEffect(Unit) {
        UsbAudioManager.registerPermissionReceiver(context, permissionReceiver)
        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching { context.registerReceiver(attachReceiver, filter) }
        onDispose {
            UsbAudioManager.unregisterReceiver(context, permissionReceiver)
            runCatching { context.unregisterReceiver(attachReceiver) }
        }
    }

    LaunchedEffect(permissionTick) {
        devices = UsbAudioManager.listDevices(context)
    }

    if (devices.isEmpty()) {
        Text(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            text = "未检测到 USB 音频输出设备，请连接 USB DAC 后重试。",
            fontSize = 12.sp,
            color = androidx.compose.material.MaterialTheme.colors.onBackground.copy(0.5f)
        )
        return
    }

    devices.forEach { device ->
        val isSelected = device.id == selectedId
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!UsbAudioManager.hasPermission(context, device.usbDevice)) {
                        UsbAudioManager.requestPermission(context, device.usbDevice)
                        Toast.makeText(context, "请在系统弹窗中允许访问 USB 设备", Toast.LENGTH_SHORT).show()
                    } else {
                        MPlayerKV.usbDeviceId.value = device.id
                        MPlayerKV.usbExclusive.value = true
                        Toast.makeText(context, "已选择 ${device.name}", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = (if (isSelected) "✓ " else "") + device.summary,
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF006E7C)
                else androidx.compose.material.MaterialTheme.colors.onBackground
            )
            Text(
                text = "VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)}",
                fontSize = 11.sp,
                color = androidx.compose.material.MaterialTheme.colors.onBackground.copy(0.5f)
            )
        }
    }
    Text(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        text = "说明：Android 公共 API 不提供等时 USB 音频传输，本模式会将 AudioTrack 直接路由到 USB DAC，绕过默认扬声器/耳机路径。真正的位完美独占需要设备厂商驱动或系统级 UAC 支持。",
        fontSize = 11.sp,
        color = androidx.compose.material.MaterialTheme.colors.onBackground.copy(0.4f)
    )
}
