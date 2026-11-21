package com.lalilu.lmusic.compose.component.card

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import com.lalilu.lmedia.entity.LSong
import com.lalilu.lmusic.utils.monet.MonetColors
import com.lalilu.lmusic.utils.monet.lightFlow
import com.lalilu.lmusic.utils.monet.rememberMonetColors

@Composable
fun RecommendCard2(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    item: () -> LSong,
    onClick: () -> Unit = {}
) {
    val song = remember { item() }

    RecommendCard2(
        modifier = modifier,
        contentModifier = contentModifier,
        imageData = { song },
        title = { song.name },
        subTitle = { song.metadata.artist },
        onClick = onClick
    )
}

@OptIn(ExperimentalTextApi::class, ExperimentalComposeUiApi::class)
@Composable
fun RecommendCard2(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    imageData: () -> Any?,
    title: () -> String,
    subTitle: () -> String,
    onClick: () -> Unit = {}
) {
    RecommendCardCover(
        modifier = modifier.width(IntrinsicSize.Min),
        contentModifier = contentModifier,
        imageData = imageData,
        onClick = onClick
    ) { palette ->
        val monet = rememberMonetColors(palette())
        val transition = rememberInfiniteTransition(label = "hero-flow")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hero-flow-phase"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            HeroGlassPanel(
                imageData = imageData,
                monet = monet,
                phase = phase,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .shadow(14.dp, RoundedCornerShape(20.dp), clip = false)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        width = 0.8.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.42f),
                                monet.primary.copy(alpha = 0.28f),
                                Color.White.copy(alpha = 0.05f),
                            )
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlowingText(
                        text = title(),
                        monet = monet,
                        phase = phase,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    FlowingText(
                        text = subTitle(),
                        monet = monet,
                        phase = phase,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        muted = true,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HeroGlassPanel(
    imageData: () -> Any?,
    monet: MonetColors,
    phase: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Always use a deep glass base. It reads as premium on both light and dark artwork and
    // guarantees contrast for the animated Monet text.
    val scrim = Color(0xFF0C0C11)
    Box(modifier = modifier.clipToBounds()) {
        // A second, blurred copy of the artwork gives the panel real frosted-glass depth.
        AsyncImage(
            modifier = Modifier
                .matchParentSize()
                .blur(radius = 36.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            model = imageData(),
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            scrim.copy(alpha = 0.80f),
                            scrim.copy(alpha = 0.92f),
                            scrim.copy(alpha = 0.97f),
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
                            monet.primary.copy(alpha = 0.22f),
                            Color.Transparent,
                            monet.tertiary.copy(alpha = 0.16f),
                        )
                    )
                )
        )
        // Slow moving Monet sheen, intentionally subtle so text stays readable.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            monet.primary.copy(alpha = 0.16f),
                            monet.tertiary.copy(alpha = 0.13f),
                            Color.Transparent,
                        ),
                        start = Offset(phase * 1100f - 350f, 0f),
                        end = Offset(phase * 1100f + 350f, 520f),
                    )
                )
        )
        content()
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun FlowingText(
    text: String,
    monet: MonetColors,
    phase: Float,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1,
    muted: Boolean = false,
) {
    val colors = remember(monet, muted) {
        val bright = monet.lightFlow
        if (muted) bright.map { it.copy(alpha = 0.72f) } else bright
    }
    val brush = Brush.linearGradient(
        colors = colors,
        start = Offset(phase * 650f - 120f, 0f),
        end = Offset(phase * 650f + 420f, 120f),
    )
    Text(
        modifier = modifier,
        text = text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.subtitle1.copy(
            fontSize = fontSize,
            fontWeight = fontWeight,
            letterSpacing = 0.2.sp,
            brush = brush,
            shadow = Shadow(
                color = Color.Black.copy(alpha = if (muted) 0.35f else 0.55f),
                offset = Offset(0f, 1.5f),
                blurRadius = 8f,
            )
        )
    )
}
