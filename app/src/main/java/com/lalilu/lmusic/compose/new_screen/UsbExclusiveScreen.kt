package com.lalilu.lmusic.compose.new_screen

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.lalilu.R
import com.lalilu.RemixIcon
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import com.lalilu.component.base.smartBarPadding
import com.lalilu.component.extension.rememberFixedStatusBarHeightDp
import com.lalilu.component.settings.SaltSlider
import com.lalilu.component.settings.SaltStyle
import com.lalilu.component.settings.SaltSwitch
import com.lalilu.lplayer.MPlayer
import com.lalilu.lplayer.MPlayerKV
import com.lalilu.lplayer.action.PlayerAction
import com.lalilu.lplayer.usb.UsbAudioManager
import com.lalilu.lplayer.usb.UsbAudioSettings
import com.lalilu.lplayer.usb.UsbBitPerfectAudioSink
import com.lalilu.lplayer.usb.UsbOutputDiagnostics
import com.lalilu.lplayer.usb.UsbRuntime
import com.lalilu.remixicon.System
import com.lalilu.remixicon.system.settings4Line
import com.lalilu.lmusic.receiver.UsbAttachReceiver
import com.zhangke.krouter.annotation.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Destination("/pages/usb_exclusive")
object UsbExclusiveScreen : Screen, ScreenInfoFactory {
    private fun readResolve(): Any = UsbExclusiveScreen

    @Composable
    override fun provideScreenInfo(): ScreenInfo = remember {
        ScreenInfo(
            title = { "USB 独占模式" },
            icon = RemixIcon.System.settings4Line,
        )
    }

    @Composable
    override fun Content() {
        UsbExclusiveContent()
    }
}

@Composable
private fun UsbExclusiveContent() {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(UsbAudioManager.listDevices(context)) }
    var permissionTick by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showQueue by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val selectedId = MPlayerKV.usbDeviceId.value
    val selected = UsbAudioManager.selectExclusiveDevice(devices, selectedId)
    val runtime by UsbOutputDiagnostics.runtime.collectAsState()
    val enabled = MPlayerKV.usbBitPerfect.value == true
    val permissionReceiver = remember {
        UsbAudioManager.permissionReceiver { _, granted ->
            permissionTick++
            if (granted) Toast.makeText(context, "USB 权限已授予", Toast.LENGTH_SHORT).show()
        }
    }
    val attachReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                devices = UsbAudioManager.listDevices(context)
            }
        }
    }

    DisposableEffect(Unit) {
        UsbAudioManager.registerPermissionReceiver(context, permissionReceiver)
        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbAudioManager.ACTION_USB_PERMISSION)
        }
        runCatching { context.registerReceiver(attachReceiver, filter) }
        onDispose {
            UsbAudioManager.unregisterReceiver(context, permissionReceiver)
            runCatching { context.unregisterReceiver(attachReceiver) }
        }
    }

    LaunchedEffect(permissionTick, refreshTick) {
        devices = UsbAudioManager.listDevices(context)
    }

    LaunchedEffect(Unit) {
        setBackgroundUsbPermission(context, UsbAudioSettings.backgroundPermission)
    }

    Box(Modifier.fillMaxSize().background(SaltStyle.page)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = rememberFixedStatusBarHeightDp(),
                bottom = 110.dp,
            )
        ) {
            item { SaltPageHeader("USB 独占模式", onRefresh = { refreshTick++ }) }

            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    UsbStatusCard(selected, runtime, enabled)
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TransferCard(runtime, enabled)
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Column(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp)).background(SaltStyle.card)
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HighResBadge()
                        Spacer(Modifier.padding(6.dp))
                        Text("USB 独占模式", fontSize = 17.sp, color = SaltStyle.title, modifier = Modifier.weight(1f))
                        SaltSwitch(enabled, onCheckedChange = { turnOn ->
                            if (turnOn) {
                                MPlayerKV.usbExclusive.value = false
                                MPlayerKV.usbBitPerfect.value = true
                                selected?.usbDevice?.let { usb ->
                                    if (!UsbAudioManager.hasPermission(context, usb)) {
                                        UsbAudioManager.requestPermission(context, usb)
                                    }
                                }
                            } else {
                                MPlayerKV.usbBitPerfect.value = false
                            }
                            permissionTick++
                        })
                    }
                }
                if (enabled && !runtime.active) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        WarningBanner(
                            if (runtime.status.contains("fallback", true) || runtime.status.contains("AudioTrack", true)) {
                                runtime.status
                            } else {
                                "USB 独占已就绪，开始播放后接管输出"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                SaltSection("输出格式") {
                    ChoiceRow("输出采样率", listOf("自适应", "44.1 kHz", "48 kHz", "88.2 kHz", "96 kHz", "176.4 kHz", "192 kHz"),
                        currentRateIndex(), { index ->
                            MPlayerKV.usbOutputSampleRate.value = RATE_VALUES.getOrElse(index) { 0 }
                            UsbBitPerfectAudioSink.active?.rebuild()
                            refreshTick++
                        })
                    ChoiceRow("输出位深度", listOf("自适应", "16-bit", "24-bit"),
                        currentDepthIndex(), { index ->
                            MPlayerKV.usbOutputBitDepth.value = DEPTH_VALUES.getOrElse(index) { 0 }
                            Toast.makeText(context, "位深将在下一次播放时生效", Toast.LENGTH_SHORT).show()
                            refreshTick++
                        })
                }
            }

            item {
                SaltSection("信号路径") {
                    PathLine("源文件", if (runtime.active) "${runtime.sourceRate / 1000.0} kHz · ${runtime.sourceBits}-bit" else "等待播放")
                    PathLine("播放流", if (runtime.active) "${runtime.streamRate / 1000.0} kHz · PCM ${runtime.streamBits}-bit" else "等待播放")
                    PathLine("DAC 端点", if (runtime.active) runtime.endpoint else "等待播放")
                    Text(
                        "USB 独占使用浮点解码与处理",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                        color = SaltStyle.secondary, fontSize = 12.sp,
                    )
                }
            }

            item {
                SaltSection("音量") {
                    VolumeRow(context, runtime)
                    ChoiceRow("音量控制", listOf("自动", "DAC 硬件音量", "数字音量"),
                        when (UsbAudioSettings.volumeMode) {
                            UsbAudioSettings.VOLUME_MODE_AUTO -> 0
                            UsbAudioSettings.VOLUME_MODE_HARDWARE -> 1
                            else -> 2
                        }, { index ->
                            MPlayerKV.usbVolumeMode.value = when (index) {
                                1 -> UsbAudioSettings.VOLUME_MODE_HARDWARE
                                2 -> UsbAudioSettings.VOLUME_MODE_DIGITAL
                                else -> UsbAudioSettings.VOLUME_MODE_AUTO
                            }
                            UsbBitPerfectAudioSink.active?.refreshSettings()
                        })
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("DAC 硬件音量", fontSize = 14.sp, color = SaltStyle.secondary)
                        Text(
                            runtime.hardwareVolumeDb?.let { String.format("%.1f dB", it) }
                                ?: if (runtime.hardwareVolumeAvailable) "已就绪" else "播放开始后检测",
                            fontSize = 14.sp, color = SaltStyle.title,
                        )
                    }
                    SaltSwitchRow(
                        "音量平滑交接",
                        "切换数字音量与 DAC 硬件音量时保持响度连续",
                        UsbAudioSettings.smoothHandoff,
                    ) { MPlayerKV.usbSmoothHandoff.value = it }
                    Text(
                        "USB 音量独立于系统音量。默认从设备安全低音量开始，避免爆音。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = SaltStyle.secondary, fontSize = 12.sp,
                    )
                }
            }

            item {
                SaltSection("数字处理") {
                    ChoiceRow("数字余量", listOf("0 dB", "-1 dB", "-2 dB", "-4 dB", "-6 dB"),
                        MARGIN_VALUES.indexOf(UsbAudioSettings.marginDb).coerceAtLeast(0), { index ->
                            MPlayerKV.usbMarginDb.value = MARGIN_VALUES[index]
                            UsbBitPerfectAudioSink.active?.refreshSettings()
                        })
                    SaltSwitchRow(
                        "TPDF 抖动",
                        "降低 16-bit 重新量化失真",
                        UsbAudioSettings.tpdf,
                    ) { MPlayerKV.usbTpdf.value = it }
                    Text(
                        "量化前衰减，降低 DSP 和重采样过载风险",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = SaltStyle.secondary, fontSize = 12.sp,
                    )
                }
            }

            item {
                SaltSection("传输缓冲") {
                    BufferRow("前台缓冲区", UsbAudioSettings.frontBufferMs) {
                        MPlayerKV.usbFrontBufferMs.value = it
                    }
                    BufferRow("后台缓冲区", UsbAudioSettings.backBufferMs) {
                        MPlayerKV.usbBackBufferMs.value = it
                    }
                    Text(
                        "后台打开大型 App 出现卡顿时优先提高后台缓冲区；数值越大越稳定，切歌与拖动响应可能稍慢。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = SaltStyle.secondary, fontSize = 12.sp,
                    )
                }
            }

            item {
                SaltSection("兼容性") {
                    SaltSwitchRow(
                        "位深兼容",
                        "设备不支持源位深时自动回退",
                        UsbAudioSettings.bitDepthCompatibility,
                    ) { MPlayerKV.usbBitDepthCompat.value = it }
                    SaltSwitchRow(
                        "声道兼容",
                        "设备不支持源声道时自动回退",
                        UsbAudioSettings.channelCompatibility,
                    ) { MPlayerKV.usbChannelCompat.value = it }
                    SaltSwitchRow(
                        "延迟建立 USB 输出链路",
                        "播放开始时建立输出会话，适合带屏 DAC 卡死或控制界面异常时使用",
                        UsbAudioSettings.delayedOpen,
                    ) { MPlayerKV.usbDelayedOpen.value = it }
                    SaltSwitchRow(
                        "未打开时 USB 授权入口",
                        "开启后，即使 LMusic 未打开，插入 USB DAC 时也会出现系统授权提示",
                        UsbAudioSettings.backgroundPermission,
                    ) { enabled -> setBackgroundUsbPermission(context, enabled) }
                }
            }

            item {
                SaltSection("信号监测") {
                    MonitorLine("采样峰值保持", runtime.peakDb?.let { String.format("%.1f dBFS", it) } ?: "等待播放")
                    MonitorLine("削波采样", runtime.clippedSamples.toString())
                    MonitorLine("异常采样", runtime.anomalySamples.toString())
                    Text(
                        "重置统计",
                        modifier = Modifier.clickable {
                            UsbBitPerfectAudioSink.active?.resetStats()
                            Toast.makeText(context, "统计已重置", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = SaltStyle.secondary, fontSize = 15.sp,
                    )
                    Text(
                        "会话统计，测量位置为整数 PCM 量化前",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = SaltStyle.secondary, fontSize = 12.sp,
                    )
                }
            }

            item {
                SaltSection("支持") {
                    SupportRow("USB 独占后台诊断") { showDiagnostics = true }
                    SupportRow("USB 独占模式反馈") { shareDiagnostics(context, runtime) }
                }
            }

            smartBarPadding()
        }

        SaltMiniPlayer(
            onQueue = { showQueue = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(rememberFixedStatusBarHeightDp())
                .background(SaltStyle.page)
        )
    }

    if (showQueue) QueueDialog(onDismiss = { showQueue = false })
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("USB 独占后台诊断") },
            text = { Text(runtime.status.ifBlank { "暂无诊断信息" }, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun UsbStatusCard(
    selected: UsbAudioManager.Device?,
    runtime: UsbRuntime,
    enabled: Boolean,
) {
    SaltCard(dark = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_usb_line),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("USB EXCLUSIVE", color = Color.White.copy(0.55f), fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (runtime.active) Color(0xFF79C98B) else Color(0xFFE9B45D)))
            Spacer(Modifier.size(6.dp))
            Text(if (runtime.active) "已接管" else if (enabled) "等待接管" else "等待连接", color = Color.White.copy(0.75f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            selected?.name ?: "未识别 USB 设备",
            color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            if (selected == null) "连接 DAC 后显示设备信息" else "USB ${selected.vendorId.toString(16)}:${selected.productId.toString(16)}",
            color = Color.White.copy(0.55f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (runtime.active) Color(0xFF79C98B) else Color(0xFFE9B45D)))
            Spacer(Modifier.size(7.dp))
            Text("OUTPUT LINK", color = Color.White.copy(0.5f), fontSize = 11.sp)
            Spacer(Modifier.size(10.dp))
            Text(if (runtime.active) "已接管" else "未接管", color = Color.White.copy(0.85f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusColumn("FORMAT", runtime.sourceRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" } ?: "待协商")
            StatusColumn("DEPTH", runtime.sourceBits.takeIf { it > 0 }?.let { "$it-bit" } ?: "待协商")
            StatusColumn("USB ID", selected?.let { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" } ?: "等待连接")
        }
    }
}

@Composable
private fun StatusColumn(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(0.4f), fontSize = 11.sp)
        Text(value, color = Color.White.copy(0.9f), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun TransferCard(runtime: UsbRuntime, enabled: Boolean) {
    SaltCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("传输状态", fontSize = 17.sp, color = SaltStyle.title)
            Text(if (runtime.active) "运行中" else if (enabled) "待机" else "待机", fontSize = 14.sp, color = SaltStyle.secondary)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${runtime.bufferMs}", fontSize = 34.sp, color = SaltStyle.title)
            Text(" ms", fontSize = 16.sp, color = SaltStyle.secondary, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text("ISO ${runtime.isoTransfers}", fontSize = 15.sp, color = SaltStyle.secondary)
        }
        Text("缓冲区水位", fontSize = 12.sp, color = SaltStyle.secondary)
        Spacer(Modifier.height(8.dp))
        val target = maxOf(1, UsbAudioSettings.targetBufferMs)
        val fraction = (runtime.bufferMs.toFloat() / target).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(SaltStyle.track)) {
            Box(Modifier.fillMaxWidth(fraction).height(14.dp).clip(CircleShape).background(SaltStyle.accent))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("播放时建立目标水位", fontSize = 11.sp, color = SaltStyle.secondary)
            Text("最低 --", fontSize = 11.sp, color = SaltStyle.secondary)
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFFFF3C8))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", fontSize = 24.sp, color = Color(0xFFB07B14))
        Spacer(Modifier.size(10.dp))
        Text(text, color = Color(0xFF6F5417), fontSize = 16.sp)
    }
}

@Composable
private fun PathLine(label: String, value: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 9.dp)) {
        Text(label, fontSize = 12.sp, color = SaltStyle.secondary)
        Text(value, fontSize = 17.sp, color = SaltStyle.title, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun VolumeRow(context: Context, runtime: UsbRuntime) {
    val percent = UsbAudioSettings.volumePercent
    val hwDb = runtime.hardwareVolumeDb
    val db = hwDb ?: (20 * kotlin.math.log10(UsbAudioSettings.digitalGain(percent).coerceAtLeast(0.0001f)))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("USB 音量", fontSize = 17.sp, color = SaltStyle.title)
        Text("$percent%  ${String.format("%.1f dB", db)}", fontSize = 15.sp, color = SaltStyle.title)
    }
    SaltSlider(
        value = percent.toFloat(),
        onValueChange = {
            val next = it.toInt().coerceIn(0, 100)
            MPlayerKV.usbVolumePercent.value = next
            UsbBitPerfectAudioSink.active?.refreshSettings()
        },
        modifier = Modifier.padding(horizontal = 18.dp),
        valueRange = 0f..100f,
    )
}

@Composable
private fun BufferRow(title: String, value: Int, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableIntStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 17.sp, color = SaltStyle.title)
        Text("$local ms", fontSize = 15.sp, color = SaltStyle.title)
    }
    SaltSlider(
        value = local.toFloat(),
        onValueChange = {
            local = (it / 50).toInt() * 50
            onChange(local.coerceIn(100, 1500))
        },
        modifier = Modifier.padding(horizontal = 18.dp),
        valueRange = 100f..1000f,
    )
}

@Composable
private fun SupportRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, color = SaltStyle.title)
        Text("›", fontSize = 26.sp, color = SaltStyle.secondary)
    }
}

@Composable
private fun MonitorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 17.sp, color = SaltStyle.secondary)
        Text(value, fontSize = 16.sp, color = SaltStyle.title)
    }
}

@Composable
private fun ChoiceRow(title: String, values: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SaltDropdownRow(title, values.getOrElse(selected.coerceIn(0, values.lastIndex)) { "" }, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEachIndexed { index, label ->
                DropdownMenuItem(onClick = { onSelect(index); expanded = false }) { Text(label) }
            }
        }
    }
}

@Composable
private fun QueueDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放队列") },
        text = {
            LazyColumn {
                items(MPlayer.currentTimelineItems) { item ->
                    Text(
                        item.mediaMetadata.title?.toString() ?: "未知曲目",
                        modifier = Modifier.fillMaxWidth().clickable {
                            MPlayer.doAction(PlayerAction.PlayById(item.mediaId))
                            onDismiss()
                        }.padding(vertical = 8.dp),
                        fontSize = 15.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private val RATE_VALUES = listOf(0, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
private val DEPTH_VALUES = listOf(0, 16, 24)
private val MARGIN_VALUES = listOf(0, -1, -2, -4, -6)

private fun currentRateIndex(): Int = RATE_VALUES.indexOf(UsbAudioSettings.sampleRate).coerceAtLeast(0)
private fun currentDepthIndex(): Int = DEPTH_VALUES.indexOf(UsbAudioSettings.bitDepth).coerceAtLeast(0)

private fun setBackgroundUsbPermission(context: Context, enabled: Boolean) {
    MPlayerKV.usbBackgroundPermission.value = enabled
    val component = android.content.ComponentName(context, UsbAttachReceiver::class.java)
    context.packageManager.setComponentEnabledSetting(
        component,
        if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
}

private fun shareDiagnostics(context: Context, runtime: UsbRuntime) {
    val text = buildString {
        appendLine("LMusic USB 独占诊断")
        appendLine("设备: ${runtime.deviceName}")
        appendLine("USB: ${runtime.vendorId.toString(16)}:${runtime.productId.toString(16)}")
        appendLine("状态: ${runtime.status}")
        appendLine("格式: ${runtime.sourceRate}Hz/${runtime.sourceBits}bit → ${runtime.streamRate}Hz/${runtime.streamBits}bit")
        appendLine("缓冲: ${runtime.bufferMs}ms  ISO:${runtime.isoTransfers}")
        appendLine("underruns=${runtime.underruns} errors=${runtime.errors} feedback=${runtime.feedbackPackets}")
        appendLine("peak=${runtime.peakDb}dBFS clipped=${runtime.clippedSamples} anomalies=${runtime.anomalySamples}")
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, "分享诊断"))
}
