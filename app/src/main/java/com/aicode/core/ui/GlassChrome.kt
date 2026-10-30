package com.aicode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

/**
 * Liquid Glass 脚手架（基于 PrismalAGSL）。
 *
 * 三条使用纪律，改动时请保持：
 *
 * 1. **只有一个 backdrop**：一个界面里只允许创建一层 [rememberChatGlassLayer]，并用
 *    [Modifier.glassBackdrop] 挂在「玻璃背后的内容」上（聊天页是消息列表）。
 *    多个全屏 backdrop capture 会显著抬高 GPU 与内存开销——这正是需求里点名要避免的。
 *    所有玻璃元素（标题栏 / 输入区 / 待办浮层）共用这一层，而不是各自采样。
 *
 * 2. **只做局部 surface**：玻璃只出现在上述几处悬浮层上，页面主体仍是普通内容。
 *
 * 3. **不自己判断 API 级别**：PrismalAGSL 自带三档降级
 *    （API 25-30 磨砂 → 31-32 RenderEffect 模糊 → 33+ 完整 AGSL 折射/色散），
 *    我们不在业务代码里调用 RuntimeShader，也不写 Build.VERSION 分支，
 *    否则会破坏 Android 8.0（minSdk 26）的兼容范围。
 *    折射/色散参数在低版本上会被库内部忽略，仅高光叠层（自绘渐变）始终生效。
 */

/** 界面唯一的背景采样层；创建后需用 [Modifier.glassBackdrop] 挂到背景内容上。 */
@Composable
fun rememberChatGlassLayer(): PrismalGlassLayer = rememberPrismalGlassLayer()

/** 把某个子树登记为玻璃的背景源（每个界面只应挂一处）。 */
fun Modifier.glassBackdrop(layer: PrismalGlassLayer): Modifier = this.prismalGlassLayer(layer)

/** 玻璃亮度状态：[luminance] 交给玻璃做自适应调优，[contentColor] 作为玻璃上文字的前景色。 */
@androidx.compose.runtime.Stable
class GlassLuminanceState(
    val luminance: Float,
    val contentColor: Color
)

/**
 * 玻璃亮度与前景色（主题推导，零采样）。
 *
 * 历史：最初用 PrismalAGSL 的 rememberPrismalAdaptiveLuminance——它每 600~1500ms 对
 * 背景层做一次 toImageBitmap + readPixels 的 GPU→CPU 读回（滚动时采样更密），
 * 每次读回都强制渲染管线 flush，是滚动/流式期间周期性小卡顿的来源。
 * 聊天页背景在深色/浅色主题下本来就是稳定的暗/亮，主题推导值与实测亮度几乎一致，
 * 故改为零成本静态值；PrismalGlassSurface 内部的 adaptiveTuning 仍会基于该值工作。
 * [source] / [enabled] 仅为保持签名兼容保留，不再参与采样。
 */
@Composable
fun rememberGlassLuminance(
    source: PrismalGlassLayer,
    enabled: Boolean = true
): GlassLuminanceState {
    val surface = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.onSurface
    return remember(surface, contentColor) {
        val dark = surface.luminance() <= 0.5f
        GlassLuminanceState(
            luminance = if (dark) 0.12f else 0.92f,
            contentColor = contentColor
        )
    }
}

/**
 * 统一的液态玻璃容器。
 *
 * 光感来源分三层，缺一不可：
 * 1. **AGSL 折射带**（[refractionHeight] / [refractionAmount] / [chromaticAberration] /
 *    [depthEffect]）：边缘弧形隆起 + RGB 色散，内容滑到玻璃边缘时被真实地「掰弯」，
 *    这是「液态」感的核心——之前没传折射参数，玻璃只是一块磨砂板；
 * 2. **vibrancy**（[saturation] / [contrast]）：提升采样背景的饱和度与微对比，
 *    让背后的颜色「透」上来而不是糊成灰；
 * 3. **自绘高光**（[specularHighlight]）：斜向渐变 sheen + 顶部 specular 亮带 +
 *    上亮下暗的 rim 光描边，模拟抛边反射；全 API 生效，不依赖 AGSL。
 *
 * - [cornerRadius]：大圆角，液态玻璃的基本形态；
 * - [tintAlpha]：极低，只做很轻的 surface tint，避免变成「大面积半透明白块」；
 * - [adaptiveLuminance] + [luminance]：让模糊与色彩随背景亮度自适应，并保住对比度下限。
 */
@Composable
fun GlassSurface(
    /**
     * 背景采样层；为 null 时降级为「半透明 surface + 极轻描边」的普通容器。
     * 这样调用方不必写两套布局，玻璃开关只需传 null / 非 null。
     */
    backdrop: PrismalBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 12.dp,
    tintAlpha: Float = 0.10f,
    adaptiveLuminance: Boolean = true,
    luminance: () -> Float = { 0.5f },
    /** 折射带厚度（自边缘向内的弧形隆起宽度）；0.dp 关闭折射。 */
    refractionHeight: Dp = 14.dp,
    /** 折射弯曲强度（像素级位移量）；越大边缘扭曲越明显。 */
    refractionAmount: Dp = 30.dp,
    /** RGB 色散强度 [0,1]，折射带边缘的彩虹色边；0 关闭。 */
    chromaticAberration: Float = 0.16f,
    /** 采样背景饱和度提升（iOS vibrancy）；1f = 不提升。 */
    saturation: Float = 1.35f,
    /** 顶部镜面高光与 rim 光描边（自绘，全 API 生效）。 */
    specularHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    if (backdrop == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(cornerRadius)
                ),
            content = content
        )
        return
    }
    val density = LocalDensity.current
    PrismalGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = { PrismalRoundedRectangle(cornerRadius) },
        adaptiveLuminance = adaptiveLuminance,
        luminance = luminance,
        blurRadius = blurRadius,
        tint = MaterialTheme.colorScheme.surface,
        tintAlpha = tintAlpha,
        saturation = saturation,
        contrast = 1.04f,
        refractionHeightPx = with(density) { refractionHeight.toPx() },
        refractionAmountPx = with(density) { refractionAmount.toPx() },
        depthEffect = true,
        chromaticAberration = chromaticAberration
    ) {
        if (specularHighlight) {
            GlassSheenOverlay(cornerRadius = cornerRadius)
        }
        content()
        if (specularHighlight) {
            GlassRimOverlay(cornerRadius = cornerRadius)
        }
    }
}

/**
 * 玻璃体积光：斜向 sheen（左上微亮、底部极轻反光）+ 顶部 specular 高光带。
 * 画在内容之下，只提供「光穿过玻璃」的体积感，不干扰阅读。
 *
 * Brush remember 化：GlassSurface 重组时不再每帧分配 3 个新 Brush 对象，
 * 减少滚动/流式输出期间的 GC 压力与 GPU 着色器管线重建。
 */
@Composable
private fun BoxScope.GlassSheenOverlay(cornerRadius: Dp) {
    val shape = RoundedCornerShape(cornerRadius)
    // 浅色主题下背景本身亮，高光要更收敛，避免一片白
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.55f else 1f
    val sheenBrush = remember(scale) {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.10f * scale),
            0.22f to Color.White.copy(alpha = 0.03f * scale),
            0.5f to Color.Transparent,
            1.0f to Color.White.copy(alpha = 0.05f * scale),
            start = Offset.Zero,
            end = Offset.Infinite
        )
    }
    val specularBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.22f * scale),
            1.0f to Color.Transparent
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(sheenBrush)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .align(Alignment.TopCenter)
            .clip(shape)
            .background(specularBrush)
    )
}

/**
 * Rim 光描边：上缘亮、侧缘弱、下缘回弹一点反光——抛边玻璃的边缘反射。
 * 画在内容之上（仅 1dp，不遮内容），让玻璃轮廓在复杂背景上依然清晰。
 */
@Composable
private fun BoxScope.GlassRimOverlay(cornerRadius: Dp) {
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.5f else 1f
    val rimBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.55f * scale),
            0.12f to Color.White.copy(alpha = 0.20f * scale),
            0.5f to Color.White.copy(alpha = 0.07f * scale),
            1.0f to Color.White.copy(alpha = 0.30f * scale)
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(
                width = 1.dp,
                brush = rimBrush,
                shape = RoundedCornerShape(cornerRadius)
            )
    )
}
