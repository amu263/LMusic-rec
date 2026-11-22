package com.lalilu.lmusic.compose.new_screen

import android.content.Context
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.lalilu.lplayer.audio.Pcm24AudioSink
import com.lalilu.lplayer.usb.UsbAudioManager
import com.lalilu.lplayer.usb.UsbOutputDiagnostics
import com.lalilu.usbuac.UsbAudioSession
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.settings4Line
import com.zhangke.krouter.annotation.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val packedOutput = produceState(false, context) {
        value = withContext(Dispatchers.IO) { Pcm24AudioSink.isSupported(context) }
    }
    val bitDepths = buildList {
        add(AudioSettings.BIT_DEPTH_16 to "16-bit")
        add(AudioSettings.BIT_DEPTH_FLOAT to "32-bit float")
        if (packedOutput.value) add(AudioSettings.BIT_DEPTH_24 to "24-bit（实验性）")
    }
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
                    state = {
                        val storedDepth = outputBitDepth.value
                        val displayedDepth = if (storedDepth == AudioSettings.BIT_DEPTH_24 && !packedOutput.value) {
                            AudioSettings.BIT_DEPTH_FLOAT
                        } else {
                            storedDepth
                        }
                        bitDepths.indexOfFirst { it.first == displayedDepth }.coerceAtLeast(0)
                    },
                    onStateUpdate = { index -> bitDepths.getOrNull(index)?.let { outputBitDepth.value = it.first } },
                    selection = bitDepths.map { it.second },
                    title = "输出位深",
                    subTitle = if (packedOutput.value) {
                        "24-bit packed 为实验性输出，初始化失败时自动回退。修改后将在下次启动播放服务时生效。"
                    } else {
                        "32-bit float 需要设备支持浮点输出。修改后将在下次启动播放服务时生效。"
                    }
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
                    onStateUpdate = {
                        if (it) MPlayerKV.usbBitPerfect.value = false
                        usbExclusive.value = it
                    },
                    title = "USB 直连（AudioTrack routing）",
                    subTitle = "优先将 Android 音频路由到 USB DAC"
                )
                SettingSwitcher(
                    state = { MPlayerKV.usbBitPerfect.value == true },
                    onStateUpdate = {
                        MPlayerKV.usbBitPerfect.value = it
                        if (it) usbExclusive.value = false
                    },
                    title = "USB 独占（bit-perfect，实验性）",
                    subTitle = "等时 USB 传输，保留解码后 PCM；设备或格式不支持时切回 AudioTrack"
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
    val status by UsbOutputDiagnostics.status.collectAsState()
    var descriptors by remember { mutableStateOf("") }
    val selected = UsbAudioManager.selectExclusiveDevice(devices, selectedId)
    LaunchedEffect(selected?.id, permissionTick) {
        descriptors = ""
        val usb = selected?.usbDevice ?: return@LaunchedEffect
        if (!UsbAudioManager.hasPermission(context, usb)) return@LaunchedEffect
        descriptors = withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            runCatching { UsbAudioSession.describe(manager, usb).joinToString("\n") }
                .getOrElse { "读取 USB 描述符失败 · ${it.message}" }
        }
    }

    val permissionReceiver = remember {
        UsbAudioManager.permissionReceiver { _, granted ->
            permissionTick++
            if (granted) Toast.makeText(context, "USB 权限已授予", Toast.LENGTH_SHORT).show()
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

    if (status.isNotEmpty()) {
        Text(
            text = status,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            fontSize = 12.sp
        )
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
        val isSelected = device.id == selectedId ||
            (MPlayerKV.usbBitPerfect.value == true && device.id == selected?.id)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    MPlayerKV.usbDeviceId.value = device.id
                    if (MPlayerKV.usbBitPerfect.value != true) MPlayerKV.usbExclusive.value = true
                    if (!UsbAudioManager.hasPermission(context, device.usbDevice)) {
                        UsbAudioManager.requestPermission(context, device.usbDevice)
                    } else {
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
    if (descriptors.isNotEmpty()) {
        Text(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            text = descriptors,
            fontSize = 11.sp
        )
    }
}
