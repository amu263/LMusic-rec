package com.lalilu.lplayer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.AppUtils
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lalilu.lmedia.LMedia
import com.lalilu.lplayer.MPlayerKV
import com.lalilu.lplayer.audio.AudioSettings
import com.lalilu.lplayer.audio.EqAudioProcessor
import com.lalilu.lplayer.audio.GainAudioProcessor
import com.lalilu.lplayer.audio.ResampleAudioProcessor
import com.lalilu.lplayer.extensions.LMusicRenderersFactory
import com.lalilu.lplayer.extensions.PlayMode
import com.lalilu.lplayer.extensions.UniversalMediaSourceFactory
import com.lalilu.lplayer.extensions.playMode
import com.lalilu.lplayer.extensions.setUpQueueControl
import com.lalilu.lplayer.usb.UsbAudioManager
import com.lalilu.lplayer.usb.UsbOutputDiagnostics
import com.lalilu.lplayer.service.CustomCommand.SeekToNext
import com.lalilu.lplayer.service.CustomCommand.SeekToPrevious
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.getKoin
import org.koin.core.qualifier.named
import kotlin.coroutines.CoroutineContext

@OptIn(UnstableApi::class)
class MService : MediaLibraryService(), CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()
    private val historyAnalyticsListener by getKoin().injectOrNull<AnalyticsListener>(named("history_analytics_listener"))

    private var exoPlayer: Player? = null
    private var mediaSession: MediaLibrarySession? = null
    private var renderersFactory: LMusicRenderersFactory? = null

    private var usbRoute: Pair<Boolean, String?>? = null
    private val requestedUsbPermissions = mutableSetOf<String>()
    private val usbPermissionReceiver = UsbAudioManager.permissionReceiver { _, _ -> applyUsbRouting() }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                applyUsbRouting()
            }
        }
    }
    private val defaultAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            MNotificationProvider(this)
        )

        syncAudioSettings()
        val decoderMode = AudioSettings.decoderMode
        val processors: Array<androidx.media3.common.audio.AudioProcessor> = arrayOf(
            GainAudioProcessor(),
            ResampleAudioProcessor { AudioSettings.outputSampleRate },
            EqAudioProcessor(),
        )
        val factory = LMusicRenderersFactory(
            context = this,
            scope = this,
            softwareOnly = decoderMode == AudioSettings.DECODER_SOFTWARE_ONLY,
            softwareFirst = decoderMode == AudioSettings.DECODER_SOFTWARE_FIRST,
            providers = processors,
        )
        renderersFactory = factory

        exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(factory)
            .setMediaSourceFactory(UniversalMediaSourceFactory(this))
            .setHandleAudioBecomingNoisy(MPlayerKV.handleBecomeNoisy.value != false)
            .setAudioAttributes(defaultAudioAttributes, MPlayerKV.handleAudioFocus.value != false)
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE) // 避免播放上一首需要点两次
            .build()
            .apply {
                historyAnalyticsListener?.let { addAnalyticsListener(it) }
                addListener(MPlayerListener(this))
            }
            .setUpQueueControl()

        mediaSession = MediaLibrarySession
            .Builder(this, exoPlayer!!, MServiceCallback(exoPlayer!!))
            .setSessionActivity(getLauncherPendingIntent())
            .build()

        startListenForValuesUpdate()
        UsbAudioManager.registerPermissionReceiver(this, usbPermissionReceiver)
        runCatching {
            registerReceiver(
                usbReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
            )
        }
        applyUsbRouting()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        UsbAudioManager.unregisterReceiver(this, usbPermissionReceiver)
        // 释放相关实例
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = mediaSession

    private fun applyUsbRouting() = launch(Dispatchers.Main) {
        val factory = renderersFactory ?: return@launch
        val exclusive = MPlayerKV.usbBitPerfect.value == true
        val platformUsb = MPlayerKV.usbExclusive.value == true
        val selectedId = MPlayerKV.usbDeviceId.value
        val devices = if (exclusive || platformUsb) UsbAudioManager.listDevices(this@MService) else emptyList()
        requestedUsbPermissions.retainAll(devices.map { it.id }.toSet())
        val selected = if (exclusive) {
            UsbAudioManager.selectExclusiveDevice(devices, selectedId)
        } else {
            devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
        }
        val usb = selected?.usbDevice
        val permitted = usb != null && UsbAudioManager.hasPermission(this@MService, usb)
        if (exclusive && usb != null && !permitted && requestedUsbPermissions.add(selected.id)) {
            runCatching { UsbAudioManager.requestPermission(this@MService, usb) }
                .onFailure { UsbOutputDiagnostics.route("AudioTrack fallback · ${it.message}") }
        }

        factory.usbDevice = if (exclusive && permitted) usb else null
        AudioSettings.usbBitPerfect = exclusive
        val audioDevice = if (exclusive) {
            usb?.let { UsbAudioManager.findAudioDeviceInfo(this@MService, it) }
        } else if (platformUsb) {
            UsbAudioManager.findAudioDeviceInfo(this@MService, usb)
        } else null
        factory.setPreferredDevice(audioDevice)

        val route = exclusive to (if (exclusive && permitted) selected?.id else null)
        if (route != usbRoute) {
            if (exclusive) {
                UsbOutputDiagnostics.route(when {
                    selected == null -> "AudioTrack fallback · No selected USB DAC connected"
                    !permitted -> "AudioTrack fallback · USB permission required"
                    else -> "USB DAC ready · ${selected.name}"
                })
            } else {
                UsbOutputDiagnostics.route(if (platformUsb) "AudioTrack USB routing" else "Android audio output")
            }
            val player = exoPlayer
            if (usbRoute != null && player != null && player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE && player.isCurrentMediaItemSeekable) {
                player.seekTo(player.currentPosition)
            }
        }
        usbRoute = route
    }

    private fun syncAudioSettings() {
        AudioSettings.decoderMode = MPlayerKV.decoderMode.value ?: AudioSettings.DECODER_AUTO
        AudioSettings.outputSampleRate = MPlayerKV.outputSampleRate.value ?: AudioSettings.SAMPLE_RATE_AUTO
        AudioSettings.outputBitDepth = AudioSettings.resolveOutputBitDepth(this, MPlayerKV.outputBitDepth.value)
        AudioSettings.dsdGainDb = MPlayerKV.dsdGainDb.value ?: 0
        AudioSettings.eqEnabled = MPlayerKV.eqEnabled.value ?: false
        AudioSettings.eqBands = MPlayerKV.eqBands.value
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.toFloatArray()
            ?.takeIf { it.size == 10 }
            ?: AudioSettings.eqBands
        AudioSettings.usbExclusive = MPlayerKV.usbExclusive.value ?: false
        AudioSettings.usbBitPerfect = MPlayerKV.usbBitPerfect.value ?: false
        AudioSettings.gaplessEnabled = MPlayerKV.gaplessEnabled.value ?: true
    }

    private fun startListenForValuesUpdate() = launch {
        MPlayerKV.handleAudioFocus.flow().onEach {
            withContext(Dispatchers.Main) {
                exoPlayer?.setAudioAttributes(defaultAudioAttributes, it != false)
            }
        }.launchIn(this)

        MPlayerKV.handleBecomeNoisy.flow().onEach {
            withContext(Dispatchers.Main) {
                (exoPlayer as? ExoPlayer)
                    ?.setHandleAudioBecomingNoisy(it != false)
            }
        }.launchIn(this)

        MPlayerKV.playMode.flow().onEach {
            withContext(Dispatchers.Main) {
                exoPlayer?.playMode = PlayMode.from(it)
            }
        }.launchIn(this)

        MPlayerKV.decoderMode.flow().onEach {
            AudioSettings.decoderMode = it ?: AudioSettings.DECODER_AUTO
        }.launchIn(this)

        MPlayerKV.outputSampleRate.flow().onEach {
            AudioSettings.outputSampleRate = it ?: AudioSettings.SAMPLE_RATE_AUTO
        }.launchIn(this)

        MPlayerKV.outputBitDepth.flow().onEach {
            AudioSettings.outputBitDepth = AudioSettings.resolveOutputBitDepth(this@MService, it)
        }.launchIn(this)

        MPlayerKV.dsdGainDb.flow().onEach {
            AudioSettings.dsdGainDb = it ?: 0
        }.launchIn(this)

        MPlayerKV.eqEnabled.flow().onEach {
            AudioSettings.eqEnabled = it ?: false
        }.launchIn(this)

        MPlayerKV.eqBands.flow().onEach {
            val values = it?.split(',')?.mapNotNull { value -> value.toFloatOrNull() }
            if (values?.size == 10) AudioSettings.updateEqBands(values.toFloatArray())
        }.launchIn(this)

        MPlayerKV.usbExclusive.flow().onEach {
            AudioSettings.usbExclusive = it ?: false
            applyUsbRouting()
        }.launchIn(this)

        MPlayerKV.usbBitPerfect.flow().onEach {
            applyUsbRouting()
        }.launchIn(this)

        MPlayerKV.usbDeviceId.flow().onEach {
            applyUsbRouting()
        }.launchIn(this)

        MPlayerKV.gaplessEnabled.flow().onEach {
            AudioSettings.gaplessEnabled = it ?: true
        }.launchIn(this)
    }
}

private class MPlayerListener(val player: Player) : Player.Listener {
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        com.blankj.utilcode.util.LogUtils.e("PlaybackError", error)
        android.util.Log.println(
            android.util.Log.ERROR,
            "LMusicPlaybackError",
            error.stackTraceToString()
        )
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val playMode = PlayMode.of(
            repeatMode = player.repeatMode,
            shuffleModeEnabled = shuffleModeEnabled
        )
        MPlayerKV.playMode.value = playMode.name
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        val playMode = PlayMode.of(
            repeatMode = repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled
        )
        MPlayerKV.playMode.value = playMode.name
    }
}

@OptIn(UnstableApi::class)
private class MServiceCallback(private val player: Player) : MediaLibrarySession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult
            .DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .registerCustomCommands()
            .build()

        return AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val action = customCommand.toCustomCommendOrNull()
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))

        when (action) {
            SeekToNext -> player.seekToNext()
            SeekToPrevious -> player.seekToPrevious()
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun buildBrowsableItem(id: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        return mediaItems.mapNotNull { item -> LMedia.getItem(item.mediaId) }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(buildBrowsableItem(LMedia.ROOT, "LMedia Library"), params)
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId == LMedia.ROOT) {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    listOf(
                        buildBrowsableItem(LMedia.ALL_SONGS, "All Songs"),
                        buildBrowsableItem(LMedia.ALL_ARTISTS, "All Artists"),
                        buildBrowsableItem(LMedia.ALL_ALBUMS, "All Albums")
                    ),
                    params
                )
            )
        }

        return Futures.immediateFuture(
            LibraryResult.ofItemList(LMedia.getChildren(parentId), params)
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = LMedia.getItem(mediaId)

        return Futures.immediateFuture(
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        )
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(
            resolveMediaItems(mediaItems),
            startIndex,
            startPositionMs
        )
    )

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
        resolveMediaItems(mediaItems).toMutableList()
    )

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return Futures.submitAsync({
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(getHistoryItems(), 0, 0L)
            )
        }, Dispatchers.IO.asExecutor())
    }
}

private fun Context.getLauncherPendingIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this, 0, Intent().apply {
            setClassName(
                AppUtils.getAppPackageName(),
                ActivityUtils.getLauncherActivity()
            )
        }, PendingIntent.FLAG_IMMUTABLE
    )
}

internal fun getHistoryItems(): List<MediaItem> {
    val history = MPlayerKV.historyPlaylistIds.get()

    return if (!history.isNullOrEmpty()) LMedia.mapItems(history)
    else LMedia.getChildren(LMedia.ALL_SONGS)
}

internal fun saveHistoryIds(mediaIds: List<String>) {
    MPlayerKV.historyPlaylistIds.set(mediaIds)
}