package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.utils.math.Mth;

/**
 * Stateless easing and progress calculations shared by the OSD KTV renderer.
 *
 * <p>Keeping this math separate prevents rendering-state changes from altering
 * the configured karaoke timing curve.</p>
 */
final class KaraokeAnimationMath {

    private KaraokeAnimationMath() {
    }

    static double smoothStep(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double characterProgress(double characterTimeline, int characterIndex, float smoothing) {
        double raw = Math.max(0.0, Math.min(1.0, characterTimeline - characterIndex));
        double eased = smoothStep(raw);
        double clampedSmoothing = Math.max(0.0, Math.min(1.0, smoothing));
        return raw + (eased - raw) * clampedSmoothing;
    }

    static double wordProgress(LyricLine.Word word, float songProgress) {
        return smoothStep(Mth.limit(word.getProgress(songProgress), 0.0, 1.0));
    }

    /**
     * 单个字内「实心已唱段」的右边界。
     *
     * <p>KTV 填涂是逐字绘制的，但羽化窗口按整个 timed word 片段的前沿计算，所以前沿的软边
     * 可以横跨字与字的边界，不会在每个字里各自重新淡入一次。</p>
     *
     * @param front        填涂前沿相对本字左边缘的位置；小于 0 表示还没唱到，
     *                     大于 {@code textWidth} 表示已经唱过去了
     * @param featherWidth 羽化窗口宽度
     * @param textWidth    本字的布局宽度（未放大）
     */
    static double solidRight(double front, double featherWidth, double textWidth) {
        return Math.max(0.0, Math.min(front - featherWidth, textWidth));
    }

    /**
     * 把「当前行缩放」夹到放大后仍装得下的上限。
     *
     * <p>放大量 = 块宽 × (倍率-1)，全部落在锚点两侧。如果块宽本来就接近可用宽度，再按配置倍率
     * 放大就一定会有一部分被裁掉——而且对唱行被强制贴左/贴右，被裁掉的那一份全在同一侧。</p>
     *
     * <p>返回值永不小于 1（不会把该放大的行反过来缩小），倍率本来 ≤ 1 时原样返回。</p>
     */
    static double clampScaleToWidth(double blockWidth, double scale, double availableWidth) {
        if (blockWidth <= .01 || scale <= 1.0001) {
            return scale;
        }
        double available = Math.max(1.0, availableWidth - SCALE_FIT_GUARD);
        return Math.min(scale, Math.max(1.0, available / blockWidth));
    }

    /**
     * 夹取之后仍要留出的余量。
     *
     * <p>锚点夹取（{@code LyricsPanelGeometry.clampScaleAnchorX}）需要一段可行区间：如果放大后的
     * 宽度正好等于可用宽度，区间退化成一个点，浮点误差会让它判成"无解"并回退到中心放大——于是
     * 又溢出一像素。留一个逻辑像素的余量，区间必然非空。</p>
     */
    private static final double SCALE_FIT_GUARD = 1.0;

    /** 内圈光晕的半径。系数与改动之前的 OSD 光晕完全一致，手感不变。 */
    static double glowRingRadius(double baseRadius, double glowIntensity) {
        return baseRadius * (.32 + Math.min(1.0, Math.max(0.0, glowIntensity)) * .68);
    }

    /** 中圈晕染半径。 */
    static double bloomInnerRadius(double baseRadius) {
        return baseRadius * 1.35 + .8;
    }

    /** 最外圈晕染半径，同时决定离屏纹理四周要留多少白。 */
    static double bloomOuterRadius(double baseRadius) {
        return baseRadius * 2.15 + 1.2;
    }

    /**
     * 离屏纹理四周的发光留白。
     *
     * <p>光晕是围绕字形向外扩的，留白不够就会被纹理边界切成一个方块。因此留白必须 ≥ 最外圈光晕
     * 的半径；再加 1 像素是给字形本身最外圈的抗锯齿和合成时的线性过滤留余量。留白是整数，
     * 合成四边形与纹理才能保持一一对应、不引入额外重采样。</p>
     */
    static int glowPadding(double maxRingRadius) {
        if (!(maxRingRadius > .004)) {
            return 1;
        }
        return (int) Math.min(16.0, Math.ceil(maxRingRadius) + 1.0);
    }

    /**
     * 离屏遮罩里「实心已唱段」的右边界。
     *
     * <p>渐变只允许位于已播放区域<b>内部</b>，因此实心段的右边界是
     * {@code front - min(gradientWidth, front)}：前沿刚出现时整段都是渐变，
     * 越唱越多之后渐变宽度固定、实心段跟着长。这样填涂永远不会越过真实播放前沿。</p>
     */
    static double maskSolidRight(double front, double gradientWidth) {
        double clampedFront = Math.max(0.0, front);
        double clampedGradient = Math.max(0.0, gradientWidth);
        return Math.max(0.0, clampedFront - Math.min(clampedGradient, clampedFront));
    }

    /**
     * 羽化渐变的宽度。
     *
     * <p>{@code configuredWidth} 的量程(4~32)是照全屏 pf65bold 定的，直接套在 OSD 的 pf28bold 上时
     * 一个窗口就盖住一到两个汉字，整段已唱文本会永远停在半透明的渐变里。所以这里先按 OSD 自己的
     * 字号收敛，再按字词宽度收敛一次——否则短字词（单字）会整个泡在渐变里、永远到不了实心色。</p>
     */
    static double maskGradientWidth(double configuredWidth, double fontHeight, double segmentWidth) {
        double byFont = Math.min(Math.max(1.0, configuredWidth), Math.max(1.0, fontHeight * .42));
        double byWidth = Math.max(1.0, segmentWidth * .45);
        return Math.max(.75, Math.min(byFont, byWidth));
    }

    /**
     * 离屏纹理的逻辑尺寸，向上对齐到 {@code alignment} 的整数倍。
     *
     * <p>{@code RenderSystem.createFrameBuffer} 只要宽高有一点变化就会删掉并重建纹理，而 KTV 的
     * 字词宽度每换一个字就变。对齐到固定台阶之后，绝大多数字词共用同一个尺寸，一首歌下来几乎
     * 不会再重建 framebuffer。多出来的那点宽度是全透明的，不影响画面。</p>
     */
    static int alignTextureSize(double logicalSize, int alignment) {
        int step = Math.max(1, alignment);
        int size = (int) Math.ceil(Math.max(1.0, logicalSize) - 1e-6);
        return ((size + step - 1) / step) * step;
    }

    /**
     * 离屏纹理的超采样倍率：一个逻辑单位在屏幕上占几个像素，就用几倍。
     *
     * <p>字形图集本身是 2 倍超采样、精确 0.5 倍缩小的，所以离屏纹理的分辨率必须跟屏幕上的实际
     * 像素密度对上，合成四边形才能与纹理接近 1:1，不会引入第二次重采样。</p>
     */
    static int superSample(double screenPixelsPerUnit) {
        long rounded = Math.round(Math.max(1.0, screenPixelsPerUnit));
        return (int) Math.max(1L, Math.min(4L, rounded));
    }

    /**
     * 羽化带该切成几条。
     *
     * <p>{@code ScissorClipManager} 把裁剪框向外取整到整屏幕像素，所以一条不足一个屏幕像素宽的
     * 羽化带会被撑大到一个像素。固定切十条时，前沿附近那十条会被撑成同一个一两像素宽的窄条并
     * 反复叠加同一个字形，叠出来的结果既过曝又糊。按"每条至少占两个屏幕像素"来定条数，就能让
     * 各条在取整之后仍然基本互不重叠。</p>
     *
     * @param screenScale 一个逻辑单位对应多少屏幕像素（GUI 缩放 × HUD 缩放 × 当前行缩放）
     */
    static int featherSteps(double featherWidth, double screenScale, int maxSteps) {
        if (featherWidth <= .01 || maxSteps <= 1) {
            return 1;
        }
        double pixels = featherWidth * Math.max(.5, screenScale);
        int steps = (int) Math.floor(pixels / 2.0);
        return Math.max(1, Math.min(maxSteps, steps));
    }

    /**
     * 求第 {@code step} 条羽化带落在本字内的区间，结果写入 {@code out[0]=left, out[1]=right}。
     *
     * <p>区间同时被实心段右边界和真实前沿夹住，因此所有实心段与羽化带在物理坐标里既不重叠
     * （不会叠加出更暗的接缝）也不留缝隙（覆盖恰好等于已唱区域）。</p>
     *
     * @return 该条是否有落在本字内的可见部分
     */
    static boolean featherStrip(double front, double featherWidth, double textWidth,
                                int step, int steps, double[] out) {
        double painted = Math.min(front, textWidth);
        double solid = solidRight(front, featherWidth, textWidth);
        double featherStart = front - featherWidth;
        out[0] = Math.max(featherStart + featherWidth * step / steps, solid);
        out[1] = Math.min(featherStart + featherWidth * (step + 1) / steps, painted);
        return out[1] - out[0] > .01;
    }
}