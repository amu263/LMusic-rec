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
import com.lalilu.component.navigation.AppRouter
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
            SaltPageHeader(
                title = "音频输出",
                subtitle = "解码、采样率、位深与音效",
                onRefresh = null,
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
            SaltSection("USB 音频") {
                SaltSwitchRow(
                    title = "USB 直连（AudioTrack 路由）",
                    subtitle = "优先将 Android 音频路由到 USB DAC",
                    checked = MPlayerKV.usbExclusive.value == true,
                ) {
                    MPlayerKV.usbExclusive.value = it
                    if (it) MPlayerKV.usbBitPerfect.value = false
                }
                SaltDropdownRow(
                    title = "USB 独占模式",
                    value = if (MPlayerKV.usbBitPerfect.value == true) "已开启" else "未开启",
                    subtitle = "UAC 等时传输、DAC 硬件音量与安全默认音量",
                    onClick = { AppRouter.route("/pages/usb_exclusive").push() },
                )
            }
        }

        smartBarPadding()
    }
}
