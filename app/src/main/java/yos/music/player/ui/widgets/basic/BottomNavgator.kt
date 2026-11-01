package yos.music.player.ui.widgets.basic

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.data.libraries.SettingsLibrary
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

@Stable
data class NavItem(val label: String, val iconResId: Int)

/** 气泡实时位置（浮点 tab 下标）。拖动过程中图标/标题跟随即时换色。 */
private val LocalLiquidTabPosition = staticCompositionLocalOf { 0f }

/** 内容层缩放（按下时轻微放大）。 */
private val LocalLiquidBottomTabScale = staticCompositionLocalOf { 1f }

/**
 * 底栏：完全按 c001apk(Coo1) 的液态玻璃底栏移植。
 * - 尺寸：92%（由调用点约束）、容器 64dp + 4dp padding（内部 56dp）、气泡 56dp
 * - 玻璃：vibrancy + blur(8dp) + lens(24,24)，表面色固定 0.55（“遮罩感”来源）
 * - 气泡：叠在内容之上，只用 lens 折射（不加 blur，避免糊住图标）
 * - 交互：拖动阻尼跟随；拖到哪，哪的图标/标题就高亮；点击正常响应
 */
@Composable
fun BottomNavigator(
    initialIndex: Int,
    externalIndex: () -> Int,
    onIndexChange: (Int) -> Unit,
    onTabReselected: (Int) -> Unit = {},
    items: List<NavItem>,
    modifier: Modifier,
    backdrop: Backdrop,
    enableInteractiveHighlight: Boolean = true,
    containerGlassEnabled: Boolean = true,
    hiddenProducerEnabled: Boolean = true,
    tabGlassEnabled: Boolean = true,
    containerTintProvider: (() -> Color)? = null
) {
    if (items.isEmpty()) return

    var selectedIndex by rememberSaveable(items) {
        mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex))
    }
    LaunchedEffect(externalIndex()) {
        val target = externalIndex()
        if (target in 0..items.lastIndex && target != selectedIndex) {
            selectedIndex = target
        }
    }

    val selectTab: (Int) -> Unit = { index ->
        if (selectedIndex != index) {
            selectedIndex = index
            onIndexChange(index)
        } else {
            onTabReselected(index)
        }
    }

    val solidCapsule = !SettingsLibrary.BarBlurEffect

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        LiquidGlassTabs(
            items = items,
            selectedIndex = selectedIndex,
            onTabSelected = selectTab,
            backdrop = backdrop,
            solidCapsule = solidCapsule,
            enableInteractiveHighlight = enableInteractiveHighlight,
            containerGlassEnabled = containerGlassEnabled,
            tabGlassEnabled = tabGlassEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun LiquidGlassTabs(
    items: List<NavItem>,
    selectedIndex: Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    solidCapsule: Boolean,
    enableInteractiveHighlight: Boolean,
    containerGlassEnabled: Boolean,
    tabGlassEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tabsCount = items.size.coerceAtLeast(1)
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    // 固定 0.55 表面色（与 Coo1 一致）。不使用外部 tintProvider，否则遮罩感会被冲淡。
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(alpha = 0.55f)
        else Color(0xFF121212).copy(alpha = 0.55f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val dampedDragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                    onTabSelected(targetIndex)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        LaunchedEffect(selectedIndex) {
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f,
                    )
                },
            )
        }

        val livePosition = dampedDragAnimation.value
        val liveIndex = livePosition.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)

        // ① 容器：液态玻璃胶囊 + 0.55 表面色（内含可点击的 Tab）
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                    val progress = dampedDragAnimation.pressProgress
                    val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                    scaleX = scale
                    scaleY = scale
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        if (containerGlassEnabled && !solidCapsule) {
                            vibrancy()
                            blur(8f.dp.toPx())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                lens(24f.dp.toPx(), 24f.dp.toPx())
                            }
                        } else {
                            // 关闭液态玻璃：仍保留磨砂模糊
                            blur(12f.dp.toPx())
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(if (enableInteractiveHighlight) interactiveHighlight.modifier else Modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalLiquidTabPosition provides livePosition,
                LocalLiquidBottomTabScale provides lerp(1f, 1.2f, dampedDragAnimation.pressProgress),
            ) {
                items.forEachIndexed { index, item ->
                    LiquidTabItem(
                        item = item,
                        index = index,
                        liveIndex = liveIndex,
                        interactive = true,
                        onClick = { onTabSelected(index) },
                    )
                }
            }
        }

        // ② 隐藏内容层：仅作气泡折射源，不可点击（否则会吃掉真实点击）
        Row(
            Modifier
                .clearAndSetSemantics { }
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        vibrancy()
                        blur(8f.dp.toPx())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .height(56f.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalLiquidTabPosition provides livePosition) {
                items.forEachIndexed { index, item ->
                    LiquidTabItem(
                        item = item,
                        index = index,
                        liveIndex = liveIndex,
                        interactive = false,
                        onClick = {},
                    )
                }
            }
        }

        // ③ 选中气泡：叠在内容之上，只用 lens（不加 blur）
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .pointerInput(Unit) {
                    detectTapGestures { onTabSelected(liveIndex) }
                }
                .then(if (enableInteractiveHighlight) interactiveHighlight.gestureModifier else Modifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        if (tabGlassEnabled && !solidCapsule &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        InnerShadow(
                            radius = 8f.dp * dampedDragAnimation.pressProgress,
                            alpha = dampedDragAnimation.pressProgress
                        )
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(alpha = 0.10f)
                            else Color.White.copy(alpha = 0.10f),
                            alpha = 1f - progress,
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

@Composable
private fun RowScope.LiquidTabItem(
    item: NavItem,
    index: Int,
    liveIndex: Int,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    val selectedColor = Color(0xFF0088FF)
    val unselectedColor = Color(0xFF9AA8BB)
    val tint = if (index == liveIndex) selectedColor else unselectedColor

    Row(
        modifier = Modifier
            .weight(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(23.dp)
                    .height(23.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(item.iconResId),
                    contentDescription = item.label,
                    tint = tint,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(text = item.label, fontSize = 11.sp, color = tint, maxLines = 1)
        }
    }
}

/* ===== 工具函数 ===== */

private fun Float.fastCoerceIn(min: Float, max: Float): Float = coerceIn(min, max)
private fun Int.fastCoerceIn(min: Int, max: Int): Int = coerceIn(min, max)
private fun Float.fastRoundToInt(): Int = roundToInt()

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

private fun GraphicsLayerScope.fastCoerceIn(v: Float, min: Float, max: Float): Float =
    v.coerceIn(min, max)

/** 拖拽阻尼动画（Coo1 同款）。 */
private class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { down ->
                onDragStarted(down)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            kotlinx.coroutines.delay(16)
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val targetVelocity =
            velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/** 交互高亮（Coo1 同款）。 */
private class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: androidx.compose.ui.geometry.Size, offset: Offset) -> Offset = { _, o -> o },
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                drawRect(Color.White.copy(alpha = 0.12f * progress), blendMode = BlendMode.Plus)
            }
            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            detectDragGestures(
                onDragStart = { down ->
                    startPosition = down
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}
