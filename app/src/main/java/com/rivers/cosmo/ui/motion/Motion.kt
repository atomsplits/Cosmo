package com.rivers.cosmo.ui.motion

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.rivers.cosmo.settings.EntranceStyle
import com.rivers.cosmo.settings.MotionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The motion preferences in effect, provided at the root of the app so any element can follow them. */
data class Motion(val enabled: Boolean, val entrance: EntranceStyle, val speedFactor: Float) {
    val entering: Boolean get() = enabled && entrance != EntranceStyle.None
    val durationMs: Int get() = (BASE_DURATION_MS * speedFactor).toInt()
    val staggerMs: Long get() = (BASE_STAGGER_MS * speedFactor).toLong()

    companion object {
        private const val BASE_DURATION_MS = 380
        private const val BASE_STAGGER_MS = 45L
        val Off = Motion(enabled = false, entrance = EntranceStyle.None, speedFactor = 1f)
    }
}

val LocalMotion = staticCompositionLocalOf { Motion.Off }

/** Resolves the user's [settings], turning everything off when Android's "Remove animations" is on. */
@Composable
fun rememberMotion(settings: MotionSettings): Motion {
    val context = LocalContext.current
    val systemAllows = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    return remember(settings, systemAllows) {
        Motion(settings.enabled && systemAllows, settings.entrance, settings.speed.factor)
    }
}

private const val MAX_STAGGERED = 10
private val ENTRANCE_DISTANCE = 24.dp

/**
 * Plays the user's entrance animation when this element first appears: a fade, combined with a
 * slide from the chosen direction or a zoom. [index] staggers items in a list so they cascade in.
 */
fun Modifier.entrance(index: Int = 0): Modifier = composed {
    val motion = LocalMotion.current
    if (!motion.entering) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(MAX_STAGGERED) * motion.staggerMs)
        progress.animateTo(1f, tween(motion.durationMs, easing = FastOutSlowInEasing))
    }
    val distance = with(LocalDensity.current) { ENTRANCE_DISTANCE.toPx() }
    graphicsLayer {
        val remaining = 1f - progress.value
        alpha = progress.value
        when (motion.entrance) {
            EntranceStyle.FadeUp -> translationY = distance * remaining
            EntranceStyle.FadeDown -> translationY = -distance * remaining
            EntranceStyle.FadeLeft -> translationX = distance * remaining
            EntranceStyle.FadeRight -> translationX = -distance * remaining
            EntranceStyle.Zoom -> {
                scaleX = 1f - 0.08f * remaining
                scaleY = 1f - 0.08f * remaining
            }
            EntranceStyle.Fade, EntranceStyle.None -> Unit
        }
    }
}

/**
 * Glides the element to its new spot with a spring whenever the layout moves it, instead of
 * jumping. While [follow] is false (e.g. the element is being dragged) it tracks its position exactly.
 */
fun Modifier.animatePlacement(follow: Boolean = true): Modifier = composed {
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    val stiffness = Spring.StiffnessMediumLow / motion.speedFactor
    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val anim = animatable ?: Animatable(target, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != target) {
                scope.launch {
                    if (motion.enabled && follow) anim.animateTo(target, spring(dampingRatio = 0.85f, stiffness = stiffness)) else anim.snapTo(target)
                }
            }
            anim.value - target
        }
}

/** The transition between screens (tabs and sub-screens), following the user's entrance style. */
fun <S> AnimatedContentTransitionScope<S>.screenTransition(motion: Motion): ContentTransform {
    if (!motion.entering) return EnterTransition.None togetherWith ExitTransition.None
    val duration = motion.durationMs
    val enterFade = fadeIn(tween(duration, easing = FastOutSlowInEasing))
    val enter = when (motion.entrance) {
        EntranceStyle.FadeUp -> enterFade + slideIn(tween(duration)) { IntOffset(0, it.height / 12) }
        EntranceStyle.FadeDown -> enterFade + slideIn(tween(duration)) { IntOffset(0, -it.height / 12) }
        EntranceStyle.FadeLeft -> enterFade + slideIn(tween(duration)) { IntOffset(it.width / 8, 0) }
        EntranceStyle.FadeRight -> enterFade + slideIn(tween(duration)) { IntOffset(-it.width / 8, 0) }
        EntranceStyle.Zoom -> enterFade + scaleIn(tween(duration), initialScale = 0.94f)
        EntranceStyle.Fade, EntranceStyle.None -> enterFade
    }
    return enter togetherWith fadeOut(tween(duration / 2))
}
