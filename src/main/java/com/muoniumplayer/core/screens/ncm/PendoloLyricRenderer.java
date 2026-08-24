package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「时计」：歌词沿一段圆弧排布，像钟面刻度一样绕过来，左侧是齿轮装饰。
 *
 * <p>移植自 folia-major 的 {@code pendolo} 视觉，几何部分照参考实现的 {@code pendoloGeometry} 写：
 * 轮心在视口左缘、只画右半圆、相邻两行的角间距取「固定步长」与「按真实行高算出的最小间距」的较大值
 * —— 后者是防叠字的关键，否则长行在弧线内侧会互相压住。</p>
 *
 * <h3>平滑旋转怎么做</h3>
 * <p>参考实现是给整个轮子一个 spring 旋转量。这里每帧按当前行重新算目标角度，再<b>按行对象缓存上一帧的
 * 角度</b>并逼近目标：这样窗口滑动（当前行前进一格）时不会整块跳一下，新出现的行直接从目标角度起步。</p>
 */
final class PendoloLyricRenderer extends AbstractStageLyricRenderer {

    /** 可见窗口：当前行前后各几行。参考实现是 9 个窗口位。 */
    private static final int WINDOW_BEFORE = 4;
    private static final int WINDOW_AFTER = 4;

    /** 参考实现的可见弧与边缘淡出角度。 */
    private static final double VISIBLE_ARC_DEG = 110.0;
    private static final double EDGE_FADE_DEG = 28.0;

    /** 轮心的相对位置。参考实现默认 (0, 0.5)：贴左缘、竖直居中。 */
    private static final double WHEEL_CENTER_X = 0.0;
    private static final double WHEEL_CENTER_Y = .50;

    /** 旋转逼近的时间常数，秒。对应参考实现的 tickSnappiness。 */
    private static final double SNAP_TAU = .13;

    private final List<LyricLine> cachedLines = new ArrayList<LyricLine>();
    private final List<Double> cachedAngles = new ArrayList<Double>();

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    protected void onReset() {
        cachedLines.clear();
        cachedAngles.clear();
        laidOutWidth = -1;
        laidOutHeight = -1;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        if (tuningDirty || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0) {
            double targetPx = FullscreenLyricStage.baseFontPx(width, height) * .96;
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (font == null) return;

        List<LyricLine> lines = window(currentLine, positionMs, WINDOW_BEFORE, WINDOW_AFTER);
        if (lines.isEmpty()) return;
        int activeIndex = activeIndexIn(lines, currentLine, positionMs);

        double centerX = left + width * WHEEL_CENTER_X;
        double centerY = top + height * WHEEL_CENTER_Y;
        double radius = Math.min(width, height)
                * FullscreenLyricStage.clamp(HudConfig.pendoloArcRadius, .2, .85);
        if (radius <= 8) return;

        double totalArc = Math.toRadians(FullscreenLyricStage.clamp(HudConfig.pendoloArcAngle, 40, 170));
        double angleStep = totalArc / 8.0;
        double lineHeight = font.getFontHeight() * fontScale;
        double padding = fontPx * .55;

        // 相邻两行的角间距：固定步长与「两行真实高度撑开的最小角」取大者，靠内侧也不会叠。
        double gap = Math.max(angleStep, (lineHeight * 2 + padding) / (2.0 * radius));

        // 以当前行为 0，往后为正角（画在下方）、往前为负角。
        double[] targets = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            targets[i] = (i - activeIndex) * gap;
        }

        double approach = 1.0 - Math.exp(-frameSeconds / SNAP_TAU);
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, 0x8A93A0, .5) & 0xFFFFFF;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        double activeScale = FullscreenLyricStage.clamp(HudConfig.pendoloActiveScale, 1.0, 1.8);

        if (HudConfig.pendoloGearDecor) {
            drawGears(centerX, centerY, radius, angleStep, accent, alpha, opened);
        }

        List<LyricLine> nextLines = new ArrayList<LyricLine>();
        List<Double> nextAngles = new ArrayList<Double>();

        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            double angle = smoothAngle(line, targets[i], approach);
            nextLines.add(line);
            nextAngles.add(Double.valueOf(angle));

            if (Math.abs(angle) >= Math.PI * .5) continue;
            double angleDeg = Math.abs(Math.toDegrees(angle));
            if (angleDeg >= VISIBLE_ARC_DEG) continue;

            double distance = Math.abs(i - activeIndex);
            boolean active = i == activeIndex;
            double baseAlpha = Math.max(.12,
                    Math.pow(Math.cos(angle * .75), 2.5) * (1.0 - distance * .18));
            double edge = Math.min(1.0, (VISIBLE_ARC_DEG - angleDeg) / EDGE_FADE_DEG);
            double lineAlpha = baseAlpha * edge * alpha * opened;
            if (!active) lineAlpha *= .78;
            if (lineAlpha <= .012) continue;

            double scale = active ? activeScale : Math.max(.7, 1.0 - distance * .08);
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);

            String text = line.lyric == null ? "" : line.lyric;
            if (text.trim().isEmpty()) continue;
            List<String> chars = FullscreenLyricStage.graphemes(text);
            int totalGraphemes = Math.max(1, chars.size());

            // 这一行能用多宽：从落点到视口右缘。放不下就单独缩这一行，绝不越界。
            double available = Math.max(24.0, left + width - x - fontPx * .4);
            double naturalWidth = font.getStringWidthD(text) * fontScale * scale;
            double fitScale = naturalWidth > available ? available / naturalWidth : 1.0;
            double total = fontScale * scale * fitScale;

            FullscreenLyricStage.pushMatrix();
            try {
                FullscreenLyricStage.scaleAt(x, y, total);
                double startX = x;
                double startY = y - font.getFontHeight() * .5;
                if (active) {
                    FullscreenLyricStage.drawLitRun(font, chars, 0, line, totalGraphemes,
                            positionMs, startX, startY, dimColor, litColor, lineAlpha, true,
                            300.0, effects);
                } else {
                    int color = i < activeIndex ? dimColor
                            : FullscreenLyricStage.mixRgb(dimColor, accent, .18) & 0xFFFFFF;
                    font.drawString(text, startX, startY,
                            FullscreenLyricStage.withAlpha(color, lineAlpha));
                }
            } finally {
                FullscreenLyricStage.popMatrix();
            }
        }

        cachedLines.clear();
        cachedAngles.clear();
        cachedLines.addAll(nextLines);
        cachedAngles.addAll(nextAngles);
    }

    /** 按行对象取上一帧的角度并逼近目标；第一次出现的行直接站在目标角度上。 */
    private double smoothAngle(LyricLine line, double target, double approach) {
        for (int i = 0; i < cachedLines.size(); i++) {
            if (cachedLines.get(i) == line) {
                double previous = cachedAngles.get(i).doubleValue();
                double next = previous + (target - previous) * approach;
                if (Math.abs(target - next) < 1.0e-4) next = target;
                return next;
            }
        }
        return target;
    }

    /** 左侧的齿轮装饰：两圈同心弧 + 沿弧的刻度。 */
    private void drawGears(double centerX, double centerY, double radius, double angleStep,
                           int accent, float alpha, double opened) {
        double tickAlpha = alpha * opened * (.10 + beat * .12);
        if (tickAlpha <= .008) return;
        double tickLength = Math.max(2.0, fontPx * .28);
        double thickness = Math.max(.8, fontPx * .045);
        for (int i = -5; i <= 5; i++) {
            double angle = i * angleStep;
            if (Math.abs(angle) >= Math.PI * .5) continue;
            double inner = radius - tickLength - fontPx * .35;
            double x = centerX + inner * Math.cos(angle);
            double y = centerY + inner * Math.sin(angle);
            double fade = tickAlpha * Math.max(.25, 1.0 - Math.abs(i) * .14);
            FullscreenLyricStage.bar(x, y - thickness * .5, tickLength, thickness,
                    thickness * .5, accent, fade);
        }
        // 轮心的两圈弧线用离散点近似：1.8.9 没有画弧的原语，点够密就看不出来是点。
        for (int ring = 0; ring < 2; ring++) {
            double ringRadius = radius * (ring == 0 ? .34 : .58);
            int steps = 26;
            for (int i = 0; i <= steps; i++) {
                double angle = -Math.PI * .48 + Math.PI * .96 * i / steps;
                double x = centerX + ringRadius * Math.cos(angle);
                double y = centerY + ringRadius * Math.sin(angle);
                FullscreenLyricStage.dot(x, y, thickness * .8, accent, tickAlpha * .5);
            }
        }
    }
}
