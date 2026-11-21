package com.lalilu.lmusic.compose.component.playing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lalilu.R
import com.lalilu.component.extension.enableFor
import com.lalilu.lmusic.utils.monet.MonetColors
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lalilu.lplayer.MPlayer


@Composable
fun PlayingToolbar(
    modifier: Modifier = Modifier,
    isUserTouchEnable: () -> Boolean = { false },
    isItemPlaying: (mediaId: String) -> Boolean = { false },
    isExtraVisible: () -> Boolean = { true },
    onClick: () -> Unit = {},
    monet: MonetColors? = null,
    flowPhase: Float = 0f,
    fixContent: @Composable RowScope.() -> Unit = {},
    extraContent: @Composable AnimatedVisibilityScope.() -> Unit = {}
) {
    val metadata = MPlayer.currentMediaMetadata
    val currentPlaying = MPlayer.currentMediaItem
    val context = LocalContext.current
    val defaultSloganStr = stringResource(id = R.string.default_slogan)

    val enter = remember {
        fadeIn(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + expandHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + slideInHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { it / 2 }
    }
    val exit = remember {
        fadeOut(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + shrinkHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + slideOutHorizontally(
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) { it / 2 }
    }

    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .enableFor(isUserTouchEnable) {
                clickable(
                    onClick = { if (isUserTouchEnable()) onClick() },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
            }
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .then(
                if (monet != null) {
                    Modifier
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0B0B10).copy(alpha = 0.88f),
                                    Color(0xFF0B0B10).copy(alpha = 0.70f),
                                    monet.primary.copy(alpha = 0.22f),
                                )
                            ),
                            shape = shape
                        )
                        .border(
                            width = 0.8.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.42f),
                                    monet.primary.copy(alpha = 0.24f),
                                    Color.White.copy(alpha = 0.04f),
                                )
                            ),
                            shape = shape
                        )
                } else {
                    Modifier
                }
            )
    ) {
        if (monet != null) {
            // Real backdrop blur for the frosted bar, independent of the page background.
            AsyncImage(
                modifier = Modifier
                    .matchParentSize()
                    .blur(radius = 30.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                model = ImageRequest.Builder(context).data(currentPlaying).build(),
                contentScale = ContentScale.Crop,
                contentDescription = null,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0B0B10).copy(alpha = 0.88f),
                                Color(0xFF0B0B10).copy(alpha = 0.78f),
                                monet.primary.copy(alpha = 0.28f),
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
                                monet.tertiary.copy(alpha = 0.15f),
                                Color.Transparent,
                            ),
                            start = Offset(flowPhase * 900f - 300f, 0f),
                            end = Offset(flowPhase * 900f + 300f, 120f),
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(start = 17.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayingHeader(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                title = { metadata?.title?.toString()?.takeIf(String::isNotBlank) ?: defaultSloganStr },
                subTitle = { metadata?.subtitle?.toString()?.takeIf(String::isNotBlank) ?: defaultSloganStr },
                isPlaying = { MPlayer.isPlaying },
                monet = monet,
                flowPhase = flowPhase,
            )

            fixContent()

            AnimatedVisibility(
                visible = isExtraVisible(),
                enter = enter,
                exit = exit,
                content = extraContent
            )
        }
    }
}