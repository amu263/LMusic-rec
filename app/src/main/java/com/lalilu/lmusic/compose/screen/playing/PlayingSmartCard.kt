package com.lalilu.lmusic.compose.screen.playing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lalilu.component.extension.requirePalette
import com.lalilu.lmusic.compose.component.card.FlowingText
import com.lalilu.lmusic.utils.monet.MonetColors
import com.lalilu.lmusic.utils.monet.rememberMonetColors
import com.lalilu.lplayer.MPlayer

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayingSmartCard(
    modifier: Modifier = Modifier,
) {
    val currentPlaying = MPlayer.currentMediaItem
    val context = LocalContext.current
    var palette by remember { mutableStateOf<Palette?>(null) }
    val monet = rememberMonetColors(palette)

    val transition = rememberInfiniteTransition(label = "mini-flow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mini-flow-phase"
    )

    LaunchedEffect(currentPlaying) {
        palette = null
    }

    val request = remember(currentPlaying, context) {
        ImageRequest.Builder(context)
            .data(currentPlaying)
            .requirePalette { palette = it }
            .build()
    }

    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
    ) {
        // Frosted backdrop: a blurred copy of the cover, a Monet veil and a slow sheen.
        AsyncImage(
            modifier = Modifier
                .matchParentSize()
                .blur(radius = 34.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            model = request,
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            monet.surface.copy(alpha = 0.78f),
                            monet.surface.copy(alpha = 0.58f),
                            monet.primary.copy(alpha = 0.24f),
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            monet.primary.copy(alpha = 0.20f),
                            monet.tertiary.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        start = Offset(phase * 900f - 300f, 0f),
                        end = Offset(phase * 900f + 300f, 240f),
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.04f),
                            Color.Black.copy(alpha = 0.10f),
                        )
                    ),
                    shape = shape,
                )
        )

        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                slideInVertically { -it } togetherWith slideOutVertically { it }
            },
            targetState = currentPlaying,
            label = ""
        ) { playing ->
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    model = ImageRequest.Builder(context).data(playing).build(),
                    contentScale = ContentScale.Crop,
                    contentDescription = null
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    FlowingText(
                        text = playing?.mediaMetadata?.title?.toString() ?: "Unknown",
                        monet = monet,
                        phase = phase,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    FlowingText(
                        text = playing?.mediaMetadata?.subtitle?.toString() ?: "Unknown",
                        monet = monet,
                        phase = phase,
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1,
                        muted = true,
                    )
                }
            }
        }
    }
}
