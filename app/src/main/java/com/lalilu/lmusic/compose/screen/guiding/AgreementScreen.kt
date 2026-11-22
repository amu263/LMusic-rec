package com.lalilu.lmusic.compose.screen.guiding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.lalilu.R
import com.lalilu.component.base.screen.ScreenInfo
import com.lalilu.component.base.screen.ScreenInfoFactory
import kotlin.system.exitProcess

class AgreementScreen(
    private val nextScreen: Screen
) : Screen, ScreenInfoFactory {

    @Composable
    override fun provideScreenInfo(): ScreenInfo {
        return remember {
            ScreenInfo(title = { stringResource(R.string.screen_title_agreement) })
        }
    }

    @Composable
    override fun Content() {
        AgreementPage(
            nextScreen = nextScreen
        )
    }
}

@Composable
private fun AgreementPage(
    nextScreen: Screen,
    navigator: Navigator = LocalNavigator.currentOrThrow
) {
    LazyColumn(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxSize()
    ) {
        item {
            ActionCard(
                onReject = { exitProcess(0) },
                onConfirm = { navigator.push(nextScreen) }
            ) {
                """
            LMusic 是一款本地音乐播放器。开始使用前，请了解以下内容：

            1. 本地音乐读取：仅在你授权后扫描设备中的音频文件，用于建立本地曲库；不会上传歌曲、歌单或播放记录。
            2. 网络歌词：仅在主动使用在线歌词功能时访问网络，用于匹配和下载歌词；可随时拒绝网络权限，不影响本地播放。
            3. 隐私：不收集个人身份信息，不接入广告 SDK，不向第三方出售或共享你的数据。
            4. 开源：LMusic 基于 AGPL-3.0 协议开源，你可以在遵守该协议的前提下使用、修改和分发。
            5. 变更：后续版本可能更新本协议，更新后会在应用内提示；继续使用即视为接受更新内容。

            感谢你选择 LMusic。
            """
            }
        }
    }
}
