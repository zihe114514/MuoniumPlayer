package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「镜台」：歌词卡片摆在不同景深上，镜头一层层往里推。
 *
 * <p><b>这是近似而非还原。</b>folia-major 的 {@code diorama} 用 three.js 建了一个真三维场景，
 * 还有一套 GPU 粒子 shader；1.8.9 的渲染管线没有这些东西，也不值得为一种歌词样式引入。
 * 这里保留它最核心的观感 —— <b>透视景深 + 镜头推进 + 远处淡出 + 浮尘</b>，
 * 用一次手写的透视投影（{@code scale = focal / (focal + z)}）在 2.5D 里实现。</p>
 */
final class DioramaLyricRenderer extends AbstractStageLyricRenderer {

    private static final int WINDOW_BEFORE = 2;
    private static final int WINDOW_AFTER = 6;

    /** 焦距。越小透视越夸张。按视口宽度取，于是窄视口下不会显得过分。 */
    private static final double FOCAL_RATIO = 1.15;

    /** 卡片之间的基础景深间隔，相对焦距。 */
    private static final double DEPTH_STEP = .34;

    /** 逼近时间常数，秒。 */
    private static final double APPROACH_TAU = .17;

    private static final int DUST_COUNT = 42;

    private final List<LyricLine> cachedLines = new ArrayList<LyricLine>();
    private final List<Double> cachedDepths = new ArrayList<Double>();

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    protected void onReset() {
        cachedLines.clear();
        cachedDepths.clear();
        laidOutWidth = -1;
        laidOutHeight = -1;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        if (tuningDirty || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0) {
            double targetPx = FullscreenLyricStage.baseFontPx(width, height) * 1.0;
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (font == null) return;

        double centerX = left + width * .5;
        double centerY = top + height * .5 + (1.0 - opened) * height * .22;
        double focal = Math.max(60.0, width * FOCAL_RATIO);
        double spread = FullscreenLyricStage.clamp(HudConfig.dioramaDepthSpread, .3, 2.5);
        double step = focal * DEPTH_STEP * spread;

        if (HudConfig.dioramaDust) {
            drawDust(left, top, width, height, focal, alpha);
        }

        List<LyricLine> lines = window(currentLine, positionMs, WINDOW_BEFORE, WINDOW_AFTER);
        if (lines.isEmpty()) return;
        int activeIndex = activeIndexIn(lines, currentLine, positionMs);

        double approach = 1.0 - Math.exp(-frameSeconds / APPROACH_TAU);
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, 0x7A8492, .5) & 0xFFFFFF;
        boolean effects = HudConfig.currentLyricEffectsEnabled;

        List<LyricLine> nextLines = new ArrayList<LyricLine>();
        List<Double> nextDepths = new ArrayList<Double>();

        // 从远往近画：近处的卡片要盖住远处的。
        int[] order = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) order[i] = lines.size() - 1 - i;

        double[] depths = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            depths[i] = smoothDepth(lines.get(i), (i - activeIndex) * step, approach);
            nextLines.add(lines.get(i));
            nextDepths.add(Double.valueOf(depths[i]));
        }

        for (int o = 0; o < order.length; o++) {
            int i = order[o];
            LyricLine line = lines.get(i);
            double z = depths[i];
            if (z <= -focal * .82) continue;

            String text = line.lyric == null ? "" : line.lyric;
            if (text.trim().isEmpty()) continue;
            List<String> chars = FullscreenLyricStage.graphemes(text);
            if (chars.isEmpty()) continue;

            double perspective = focal / (focal + z);
            if (perspective <= .04 || perspective > 6) continue;

            long seed = FullscreenLyricStage.seedOf(line);
            double lateral = (FullscreenLyricStage.random(seed, 5) - .5) * width * .26;
            double vertical = (FullscreenLyricStage.random(seed, 6) - .5) * height * .16;

            double naturalWidth = font.getStringWidthD(text) * fontScale;
            double naturalHeight = font.getFontHeight() * fontScale;
            // 卡片不能比视口还宽：先按可用宽度压一次，再乘透视。
            double fit = naturalWidth > width * .88 ? (width * .88) / naturalWidth : 1.0;
            double total = fontScale * fit * perspective;

            double screenX = centerX + lateral * perspective;
            double screenY = centerY + vertical * perspective;

            boolean active = i == activeIndex;
            double distance = Math.abs(z) / Math.max(1.0, step);
            double depthAlpha = z >= 0
                    ? Math.max(.06, 1.0 - distance * .19)
                    : Math.max(0.0, 1.0 - distance * .55);
            double drawAlpha = depthAlpha * alpha * (active ? 1.0 : .8);
            if (drawAlpha <= .014) continue;

            // 景深卡片：这是「镜台」那种一层层方块的观感来源。
            double panelWidth = naturalWidth * fit * perspective + fontPx * perspective * 1.1;
            double panelHeight = naturalHeight * fit * perspective + fontPx * perspective * .7;
            FullscreenLyricStage.bar(screenX - panelWidth * .5, screenY - panelHeight * .5,
                    panelWidth, panelHeight, fontPx * perspective * .28,
                    active ? accent : 0xFFFFFF, drawAlpha * (active ? .14 : .06));

            FullscreenLyricStage.pushMatrix();
            try {
                FullscreenLyricStage.scaleAt(screenX, screenY, total);
                double startX = screenX - font.getStringWidthD(text) * .5;
                double startY = screenY - font.getFontHeight() * .5;
                if (active) {
                    FullscreenLyricStage.drawLitRun(font, chars, 0, line,
                            Math.max(1, chars.size()), positionMs, startX, startY,
                            dimColor, litColor, drawAlpha, true, 280.0, effects);
                } else {
                    int color = z < 0 ? dimColor
                            : FullscreenLyricStage.mixRgb(dimColor, accent, .16) & 0xFFFFFF;
                    font.drawString(text, startX, startY,
                            FullscreenLyricStage.withAlpha(color, drawAlpha));
                }
            } finally {
                FullscreenLyricStage.popMatrix();
            }
        }

        cachedLines.clear();
        cachedDepths.clear();
        cachedLines.addAll(nextLines);
        cachedDepths.addAll(nextDepths);
    }

    private double smoothDepth(LyricLine line, double target, double approach) {
        for (int i = 0; i < cachedLines.size(); i++) {
            if (cachedLines.get(i) == line) {
                double previous = cachedDepths.get(i).doubleValue();
                double next = previous + (target - previous) * approach;
                if (Math.abs(target - next) < 1.0e-3) next = target;
                return next;
            }
        }
        return target;
    }

    /**
     * 浮尘。
     *
     * <p>参考实现是 GPU 粒子；这里是 CPU 上的确定性轨迹：位置由「粒子序号 + 已过时间」直接算出，
     * 不保存任何状态，所以既不会因为掉帧漂移，也不需要粒子池。</p>
     */
    private void drawDust(double left, double top, double width, double height,
                          double focal, float alpha) {
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        double baseAlpha = alpha * (.16 + beat * .2);
        if (baseAlpha <= .01) return;
        for (int i = 0; i < DUST_COUNT; i++) {
            double rx = FullscreenLyricStage.random(i * 37L + 11L, 1);
            double ry = FullscreenLyricStage.random(i * 37L + 11L, 2);
            double rz = FullscreenLyricStage.random(i * 37L + 11L, 3);
            // 沿深度缓慢向镜头飘，到头就绕回去。
            double cycle = 9.0 + rz * 11.0;
            double phase = (elapsedSeconds / cycle + rz) % 1.0;
            double z = focal * (1.9 - phase * 1.85);
            double perspective = focal / (focal + z);
            double x = left + width * .5 + (rx - .5) * width * 1.5 * perspective;
            double y = top + height * .5 + (ry - .5) * height * 1.5 * perspective
                    + Math.sin(elapsedSeconds * .6 + i) * 2.0 * perspective;
            if (x < left - 4 || x > left + width + 4 || y < top - 4 || y > top + height + 4) continue;
            double radius = Math.max(.4, fontPx * .06 * perspective);
            double fade = Math.min(1.0, phase * 4.0) * Math.min(1.0, (1.0 - phase) * 3.0);
            FullscreenLyricStage.dot(x, y, radius, accent, baseAlpha * fade);
        }
    }
}
