package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.settings.HudConfig;

import java.awt.Color;
import java.util.Random;

/**
 * 「律动词云」样式的背景装饰层：缓慢漂浮的几何形状与向上飘的光点。
 *
 * <p>为什么不画三角形与十字星：项目里没有多边形填充图元，只有圆角矩形与圆角描边。
 * 与其为了装饰新增一条 GL 绘制路径（还要自己管顶点与状态），不如把形状限制在
 * 「圆 / 圆环 / 圆角方 / 十字 / 胶囊」——这五种都能用圆角矩形加旋转拼出来，
 * 视觉上也更接近现在播放器的圆润风格。</p>
 *
 * <p>形状参数在构造时用固定种子生成一次，因此每次打开歌词页的画面是一致的，
 * 不会因为重开而突然换一套排布。动画只吃「经过的秒数」与「已经平滑好的节拍强度」，
 * 没有任何逐帧累加的状态，所以掉帧不会让它跑飞。</p>
 *
 * <h3>配色</h3>
 * <p>默认跟随播放器主题强调色。用户也可以在全屏歌词页左上角的「A-Z」面板里切成自定义调色板，
 * 那时颜色按 {@link com.muoniumplayer.core.ncm.music.PlaybackPulse#beatCount()} 数出来的拍数
 * 每 N 拍换一个（N 与调色板都可配），换色之间做交叉淡入。歌词文字本身<b>始终</b>用主题强调色，
 * 不受这里影响——原因见 {@link com.muoniumplayer.core.settings.HudConfig#cadenzaDecorColorMode}。</p>
 *
 * <p>最底下还有一层<b>踩点光晕</b>（{@link #renderBackdrop}）：三团缓慢漂移的大光斑，
 * 亮度与半径跟着节拍冲击走、整体亮度跟着响度走。它只叠光，不给整块视口铺底色——
 * 铺底色的那一版把右半边染成了实色，原因见 {@link #renderBackdrop} 的注释。</p>
 */
final class CadenzaDecorLayer implements SharedRenderingConstants, SharedConstants {

    private static final int SHAPE_COUNT = 14;
    private static final int PARTICLE_COUNT = 22;

    private enum Kind {
        DISC, RING, SQUARE, CROSS, CAPSULE
    }

    private static final class Shape {
        Kind kind;
        double x;
        double y;
        double size;
        double duration;
        double delay;
        double opacity;
        double rotation;
        double pushAngle;
        boolean reverse;
    }

    private static final class Particle {
        double size;
        double left;
        double top;
        double opacity;
        double duration;
        double delay;
    }

    /** 背景光斑：数量刻意很少，每一团都占视口大半，靠低透明度叠出「一片光」而不是「几个圆」。 */
    private static final int BLOB_COUNT = 3;

    /** 每团光斑叠几层同心圆。层数越多边界越软，代价是填充率。 */
    private static final int BLOB_RINGS = 4;

    private static final class Blob {
        double x;
        double y;
        double radius;
        double duration;
        double delay;
        double driftX;
        double driftY;
        double tint;
    }

    /**
     * 换色的交叉淡入时长（秒）。
     *
     * <p>拍计数是离散的，直接换色会看到一次硬切；这一层是大面积的低透明度光，硬切比在小图标上更显眼。
     * 0.45 s 大约是 130 BPM 下一拍的长度，所以在「每 4 拍换一次」这类设置下，淡入只占换色间隔的
     * 四分之一，既看不出跳变也不会一直在过渡中。</p>
     */
    private static final double COLOR_FADE_SECONDS = .45;

    private final Shape[] shapes = new Shape[SHAPE_COUNT];
    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private final Blob[] blobs = new Blob[BLOB_COUNT];

    /** 当前实际画出去的颜色（含 alpha 位，恒为 0xFF）。淡入过程中它是两个调色板颜色的中间值。 */
    private int displayedColor;
    private int fadeFromColor;
    private int fadeTargetColor;
    private double colorFade = 1.0;
    private boolean colorReady;

    /** 上一帧的 {@code seconds}，用来在没有 dt 参数的情况下推进淡入。 */
    private double lastSeconds = Double.NaN;

    CadenzaDecorLayer() {
        Random random = new Random(0xCADE2A11L);
        Kind[] kinds = Kind.values();
        for (int i = 0; i < SHAPE_COUNT; i++) {
            Shape shape = new Shape();
            shape.kind = kinds[random.nextInt(kinds.length)];
            shape.x = random.nextDouble();
            shape.y = random.nextDouble();
            shape.size = 30.0 + random.nextDouble() * 78.0;
            shape.duration = 7.0 + random.nextDouble() * 8.0;
            shape.delay = random.nextDouble() * 5.0;
            shape.opacity = .09 + random.nextDouble() * .08;
            shape.reverse = random.nextBoolean();
            shape.rotation = random.nextDouble() * 360.0;
            shape.pushAngle = random.nextDouble() * Math.PI * 2;
            shapes[i] = shape;
        }
        for (int i = 0; i < BLOB_COUNT; i++) {
            Blob blob = new Blob();
            blob.x = .22 + random.nextDouble() * .56;
            blob.y = .20 + random.nextDouble() * .58;
            blob.radius = .34 + random.nextDouble() * .24;
            blob.duration = 11.0 + random.nextDouble() * 9.0;
            blob.delay = random.nextDouble() * 8.0;
            blob.driftX = .06 + random.nextDouble() * .07;
            blob.driftY = .05 + random.nextDouble() * .06;
            blob.tint = random.nextDouble();
            blobs[i] = blob;
        }
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle particle = new Particle();
            particle.size = 1.0 + random.nextDouble() * 3.0;
            particle.left = random.nextDouble();
            particle.top = random.nextDouble();
            particle.opacity = .10 + random.nextDouble() * .22;
            particle.duration = 15.0 + random.nextDouble() * 20.0;
            particle.delay = random.nextDouble() * 10.0;
            particles[i] = particle;
        }
    }

    /** 换歌 / 重开歌词页时清掉配色的淡入状态，避免新的一首从上一首的颜色淡过来。 */
    void reset() {
        colorReady = false;
        colorFade = 1.0;
        lastSeconds = Double.NaN;
    }

    /**
     * @param seconds  歌词页打开以来经过的秒数
     * @param pulse    已经平滑好的节拍强度（0..1），形状会随它扩张、加速旋转
     * @param loudness 平滑响度（0..1），只用来给背景光晕定整体亮度
     */
    void render(double left, double top, double width, double height,
                double seconds, double pulse, double loudness, float alpha) {
        if (alpha <= .004f) return;
        double beat = Math.max(0.0, Math.min(1.0, pulse));
        double energy = Math.max(0.0, Math.min(1.0, loudness));
        double scale = .95 + beat * .55;

        int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT) | 0xFF000000;
        // 装饰层的颜色可以脱离主题（HudConfig.cadenzaDecorColorMode），但歌词文字始终用 accent。
        int decorColor = advanceColor(accent, seconds);
        int dotColor = CadenzaLyricRenderer.mixRgb(decorColor, 0xFFFFFFFF, .55);

        renderBackdrop(left, top, width, height, seconds, beat, energy, decorColor, alpha);

        for (int i = 0; i < shapes.length; i++) {
            Shape shape = shapes[i];
            double phase = (seconds + shape.delay) / shape.duration * Math.PI * 2;
            double direction = shape.reverse ? -1 : 1;

            double dx = -direction * Math.sin(phase) * width * .07;
            double dy = direction * Math.cos(phase) * height * .09;
            double push = beat * Math.min(width, height) * .075;
            dx += Math.cos(shape.pushAngle) * push;
            dy += Math.sin(shape.pushAngle) * push;

            double rotation = shape.rotation
                    + direction * (seconds + shape.delay) / shape.duration * 360.0
                    + direction * beat * 40.0;

            double centerX = left + shape.x * width + dx;
            double centerY = top + shape.y * height + dy;
            double half = shape.size * scale * .5;
            float shapeAlpha = (float) (shape.opacity * alpha * (.85 + beat * .4));
            if (shapeAlpha <= .004f) continue;

            drawShape(shape.kind, centerX, centerY, half, rotation, reAlpha(decorColor, shapeAlpha));
        }

        for (int i = 0; i < particles.length; i++) {
            Particle particle = particles[i];
            double cycle = ((seconds + particle.delay) % particle.duration) / particle.duration;
            double y = top + particle.top * height - cycle * (height * .32 + beat * height * .18);
            double envelope = Math.sin(cycle * Math.PI);
            float dotAlpha = (float) (particle.opacity * envelope * alpha * (.8 + beat * .6));
            if (dotAlpha <= .004f) continue;
            double size = particle.size * (1.0 + beat * .6);
            double x = left + particle.left * width;
            // 上升到视口顶端之外就不画，避免裁剪边界上出现半个亮点。
            if (y + size < top) continue;
            roundedRect(x, y, size, size, size * .5, reAlpha(dotColor, dotAlpha));
        }
    }

    /**
     * 踩点光晕层：三团缓慢漂移的大光斑。冲击（{@code beat}）管「跳」——半径与亮度瞬间涨一档；
     * 响度（{@code energy}）管「底」——安静段落整体暗下来，高潮段落亮起来。两个信号分开是有意的：
     * 只用冲击的话安静段落会一片死黑，只用响度的话又完全踩不到点。
     *
     * <p>项目没有径向渐变图元，光斑是用四个同心的圆角矩形叠出来的阶梯衰减——四层已经足够看不出边界，
     * 再多纯属白烧填充率。</p>
     *
     * <h3>这里绝对不能用 roundedRectGradient* 铺底</h3>
     * <p>{@code rqg.frag} 的最后一行是
     * {@code gl_FragColor = vec4(createGradient(..., colorN.rgb), alp * alpha)}：<b>四个角的
     * alpha 通道被整个丢掉了</b>，实际透明度只来自 {@code alp} 这个全局 uniform，而
     * {@link com.muoniumplayer.core.interfaces.SharedRenderingConstants#roundedRectGradientVertical}
     * 走的重载把 {@code alp} 写死成 {@code 1.0f}。所以传进去的 {@code new Color(r, g, b, 12)}
     * 会被画成<b>完全不透明</b>的实色——铺满整个歌词视口就是「背景被异常染色」。
     * {@code roundedRect}（{@code rq.frag}）走的是 {@code u_color.a * alpha}，透明度正常，
     * 所以这一层只用它。</p>
     *
     * <p>另外<b>刻意没有</b>参考实现的整屏压暗层（{@code Color(0,0,0,165)}）：参考实现是整屏铺开的，
     * 而这里的歌词视口只是播放器的右半边，压暗一半会在正中间留下一道竖直硬边。</p>
     */
    private void renderBackdrop(double left, double top, double width, double height,
                                double seconds, double beat, double energy, int tint, float alpha) {
        double reach = Math.min(width, height);
        float ringAlpha = ringAlpha(beat, energy, alpha);
        if (ringAlpha <= .002f) return;

        for (int i = 0; i < blobs.length; i++) {
            Blob blob = blobs[i];
            double phase = (seconds + blob.delay) / blob.duration * Math.PI * 2;
            double centerX = left + width * (blob.x + Math.sin(phase) * blob.driftX);
            double centerY = top + height * (blob.y + Math.cos(phase * .8) * blob.driftY);
            double radius = reach * blob.radius * (1.0 + beat * .14);
            int blobTint = CadenzaLyricRenderer.mixRgb(tint, 0xFFFFFFFF, .18 + blob.tint * .34);
            // 每层用同一个透明度：叠 BLOB_RINGS 层之后中心累积到 peak 附近，边缘只有一层，
            // 于是得到一个阶梯式的径向衰减。逐层加深会让最外圈直接跳出一道硬边。
            int color = reAlpha(blobTint, ringAlpha);
            for (int ring = 0; ring < BLOB_RINGS; ring++) {
                double factor = 1.0 - ring * .22;
                double r = radius * factor;
                if (r <= 2.0) continue;
                roundedRect(centerX - r, centerY - r, r * 2, r * 2, r, color);
            }
        }
    }

    /**
     * 单层圆的透明度。
     *
     * <p>抽成静态方法是为了能离线断言 {@link #worstCaseOpacity} 这条上限——光晕的成败全在这几个数上，
     * 调大一点就从「一层光」变成「一层漆」，而这件事在游戏里只能靠肉眼判断。</p>
     */
    static float ringAlpha(double beat, double energy, float alpha) {
        double clampedBeat = beat < 0 ? 0 : (beat > 1 ? 1 : beat);
        double clampedEnergy = energy < 0 ? 0 : (energy > 1 ? 1 : energy);
        double peak = .022 + clampedEnergy * .030 + clampedBeat * .045;
        return (float) (peak / BLOB_RINGS * alpha);
    }

    /**
     * 三团光斑的中心全部重叠时，这一层总共盖住背景多少。
     *
     * <p>普通 alpha 混合是逐层累积的：{@code 1 - (1 - a)^n}。光斑的落点是随机散开的，真的全重叠只是
     * 上界，但上界才是「会不会把背景染成实色」的判据。</p>
     */
    static double worstCaseOpacity(float ringAlpha) {
        return 1.0 - Math.pow(1.0 - ringAlpha, BLOB_RINGS * (double) BLOB_COUNT);
    }

    private void drawShape(Kind kind, double centerX, double centerY, double half,
                           double rotation, int color) {
        boolean rotated = Math.abs(rotation % 360.0) > .05;
        if (rotated) {
            api.getGLStateManager().pushMatrix();
            rotateAtPos(centerX, centerY, (float) rotation);
        }
        try {
            switch (kind) {
                case DISC:
                    roundedRect(centerX - half, centerY - half, half * 2, half * 2, half, color);
                    break;
                case RING:
                    roundedOutline(centerX - half, centerY - half, half * 2, half * 2, half,
                            Math.max(1.0, half * .12), toColor(color));
                    break;
                case SQUARE:
                    roundedRect(centerX - half, centerY - half, half * 2, half * 2, half * .28, color);
                    break;
                case CROSS: {
                    double thickness = Math.max(2.0, half * .34);
                    roundedRect(centerX - half, centerY - thickness * .5, half * 2, thickness,
                            thickness * .5, color);
                    roundedRect(centerX - thickness * .5, centerY - half, thickness, half * 2,
                            thickness * .5, color);
                    break;
                }
                case CAPSULE: {
                    double capsuleHeight = Math.max(2.0, half * .58);
                    roundedRect(centerX - half, centerY - capsuleHeight * .5, half * 2, capsuleHeight,
                            capsuleHeight * .5, color);
                    break;
                }
                default:
                    break;
            }
        } finally {
            if (rotated) api.getGLStateManager().popMatrix();
        }
    }

    /**
     * 推进并返回这一帧装饰层该用的颜色。
     *
     * <p>dt 是从 {@code seconds} 差出来的而不是自己读时钟：这一层的所有动画都吃同一个
     * {@code seconds}，用同一个时基推进淡入才不会在掉帧时和形状的漂移脱节。时间倒退或跳变
     * （{@link #reset()} 之后、歌词页重开）时把 dt 当 0，宁可慢一帧也不要跳一大步。</p>
     */
    private int advanceColor(int accent, double seconds) {
        double dt = Double.isNaN(lastSeconds) ? 0.0 : seconds - lastSeconds;
        lastSeconds = seconds;
        if (dt < 0.0 || dt > .25) dt = 0.0;

        int target = targetColor(accent);
        if (!colorReady) {
            colorReady = true;
            displayedColor = target;
            fadeFromColor = target;
            fadeTargetColor = target;
            colorFade = 1.0;
            return displayedColor;
        }
        if (target != fadeTargetColor) {
            // 从「当前正在显示的中间色」起淡，而不是从上一个目标色起淡：
            // 上一次淡入还没走完就换色时，从目标色起淡会往回跳一下。
            fadeFromColor = displayedColor;
            fadeTargetColor = target;
            colorFade = 0.0;
        }
        if (colorFade < 1.0) {
            colorFade = Math.min(1.0, colorFade + dt / COLOR_FADE_SECONDS);
        }
        displayedColor = blend(fadeFromColor, fadeTargetColor, colorFade);
        return displayedColor;
    }

    /** 这一刻「应该」是什么颜色。跟随主题时恒为强调色；自定义时按拍数走调色板。 */
    private static int targetColor(int accent) {
        if (HudConfig.cadenzaDecorColorMode != 1) return accent;
        int beats = PlaybackPulse.isAvailable() ? PlaybackPulse.beatCount() : 0;
        int index = colorIndex(beats, HudConfig.cadenzaBeatsPerColor());
        return HudConfig.cadenzaPaletteColor(index) | 0xFF000000;
    }

    /**
     * 第几轮颜色。
     *
     * <p>抽成静态纯方法是为了能离线断言：换色频率只能靠肉眼在游戏里判断，而「每 N 拍恰好换一次、
     * 参数再离谱也不越界不抛」是可以断言的。取模留给
     * {@link HudConfig#cadenzaPaletteColor(int)}，这里只负责分组。</p>
     */
    static int colorIndex(int beatCount, int beatsPerColor) {
        int per = beatsPerColor < 1 ? 1 : beatsPerColor;
        int beats = beatCount < 0 ? 0 : beatCount;
        return beats / per;
    }

    /** 两色之间的交叉淡入。smoothstep 而不是线性：线性在起止两端能看出转折。 */
    static int blend(int from, int to, double fade) {
        double t = fade <= 0 ? 0 : (fade >= 1 ? 1 : fade);
        t = t * t * (3.0 - 2.0 * t);
        return CadenzaLyricRenderer.mixRgb(from, to, t) | 0xFF000000;
    }

    /** {@code roundedOutline} 只接受 {@link Color}；这里把 ARGB 整数转过去，含 alpha。 */
    private static Color toColor(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }
}
