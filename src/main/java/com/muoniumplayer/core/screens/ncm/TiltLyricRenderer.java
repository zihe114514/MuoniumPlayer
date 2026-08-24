package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「倾诉」：当前行按字数拆成一到四段竖排，其中一部分段落改用斜体 + 字距拉开 + 逐字上下错动。
 *
 * <p>移植自 folia-major 的 {@code tilt} 视觉。这里有两个容易搞错的点：</p>
 * <ol>
 *   <li><b>分几段不是固定的</b>，而是按字数取对数再乘一个随机系数与「分行概率」，落在三个阈值上
 *       得到 1..4 段。字少的行大概率不拆，字多的行才拆开 —— 这就是那种「说话断句」的观感来源。</li>
 *   <li><b>「倾斜段」并不是把整段旋转</b>。参考实现是 {@code font-style: italic} 加大字距、
 *       再让每个字上下交替错开。旋转整段会把基线一起转歪，观感完全不同。1.8.9 没有斜体字形，
 *       所以斜体用剪切矩阵（{@link FullscreenLyricStage#shearAt}）模拟。</li>
 * </ol>
 */
final class TiltLyricRenderer extends AbstractStageLyricRenderer {

    private static final double ENTER_LEAD_MS = 220;
    private static final double ENTER_MS = 480;
    private static final double EXIT_SECONDS = .3;

    /** 参考实现的分段阈值与对数参数。 */
    private static final double[] LINE_THRESHOLDS = {.45, 1.05, 1.7};
    private static final double LOG_OFFSET = 4.0;
    private static final double LOG_BASE = 24.0;

    /** 斜体的剪切量。0.22 对应约 12 度，和常见斜体角度接近。 */
    private static final double ITALIC_SHEAR = .22;

    private static final class Segment {
        List<String> chars;
        String text = "";
        int graphemeStart;
        long startMs;
        long endMs;
        boolean tilt;
        double width;
        double y;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    private final List<Segment> segments = new ArrayList<Segment>();
    private final List<Segment> outgoing = new ArrayList<Segment>();

    private LyricLine laidOutLine;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;
    private int totalGraphemes;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;

    private long exitStartNanos;

    protected void onReset() {
        segments.clear();
        outgoing.clear();
        laidOutLine = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        exitStartNanos = 0L;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        long now = System.nanoTime();
        boolean viewportChanged = tuningDirty
                || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0;
        if (currentLine != laidOutLine || viewportChanged) {
            if (currentLine != laidOutLine) {
                outgoing.clear();
                outgoing.addAll(segments);
                exitStartNanos = now;
            }
            rebuild(currentLine, width, height);
            laidOutLine = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (segments.isEmpty() && outgoing.isEmpty()) return;

        double centerX = left + width * .5;
        double centerY = top + height * .5 + (1.0 - opened) * height * .28;

        if (!outgoing.isEmpty()) {
            double exit = (now - exitStartNanos) / 1.0e9 / EXIT_SECONDS;
            if (exit >= 1) {
                outgoing.clear();
            } else {
                drawSegments(outgoing, laidOutLine, positionMs, centerX, centerY, alpha, exit);
            }
        }
        drawSegments(segments, currentLine, positionMs, centerX, centerY, alpha, 0);
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine line, double viewWidth, double viewHeight) {
        segments.clear();
        totalGraphemes = 0;
        if (line == null) return;

        List<FullscreenLyricStage.Token> tokens = FullscreenLyricStage.tokensOf(line, 4);
        if (tokens.isEmpty()) return;

        List<String> chars = new ArrayList<String>();
        for (int i = 0; i < tokens.size(); i++) {
            chars.addAll(FullscreenLyricStage.graphemes(tokens.get(i).text));
        }
        if (chars.isEmpty()) return;
        totalGraphemes = chars.size();

        long seed = FullscreenLyricStage.seedOf(line);
        int lineCount = pickLineCount(chars.size(), seed);

        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * 1.42;
        for (int attempt = 0; attempt < 8; attempt++) {
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            if (layout(chars, tokens, line, seed, lineCount, viewWidth, viewHeight)) return;
            // 参考实现的 RESPLIT_THRESHOLD：溢出时先多拆一段，拆到上限再降字号。
            if (lineCount < 4) {
                lineCount++;
            } else {
                targetPx *= .84;
            }
            if (targetPx < 11) break;
        }
        layout(chars, tokens, line, seed, lineCount, viewWidth, viewHeight);
    }

    /** 参考实现的分段数启发式。 */
    private int pickLineCount(int charCount, long seed) {
        double split = FullscreenLyricStage.clamp01(HudConfig.tiltSplitProbability);
        double jitter = FullscreenLyricStage.randomWide(seed, 11) * .6 + .7;
        double score = (Math.log(charCount + LOG_OFFSET) / Math.log(LOG_BASE)) * jitter * split;
        int count = 1;
        for (int i = 0; i < LINE_THRESHOLDS.length; i++) {
            if (score >= LINE_THRESHOLDS[i]) count = i + 2;
        }
        return Math.max(1, Math.min(4, Math.min(count, charCount)));
    }

    private boolean layout(List<String> chars, List<FullscreenLyricStage.Token> tokens,
                           LyricLine line, long seed, int lineCount,
                           double viewWidth, double viewHeight) {
        segments.clear();
        double tiltChance = FullscreenLyricStage.clamp01(HudConfig.tiltStyleProbability);
        double stageWidth = Math.max(48.0, viewWidth - 14.0);

        int[] bounds = splitBounds(chars, lineCount);
        double lineHeight = fontPx * 1.42;
        double blockHeight = bounds.length - 1 == 0 ? 0 : (bounds.length - 1) * lineHeight;
        double blockTop = -blockHeight * .5;
        boolean fits = true;

        for (int s = 0; s + 1 < bounds.length; s++) {
            int from = bounds[s];
            int to = bounds[s + 1];
            if (to <= from) continue;
            Segment segment = new Segment();
            segment.chars = new ArrayList<String>(chars.subList(from, to));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < segment.chars.size(); i++) builder.append(segment.chars.get(i));
            segment.text = builder.toString();
            segment.graphemeStart = from;
            segment.tilt = FullscreenLyricStage.randomWide(seed, 100 + s) < tiltChance;
            long[] span = spanOf(tokens, from, to);
            segment.startMs = span[0];
            segment.endMs = span[1];
            segment.width = measure(segment);
            segment.y = blockTop + s * lineHeight;
            if (segment.width > stageWidth) fits = false;
            segments.add(segment);
        }
        return fits && blockHeight <= viewHeight - 10;
    }

    /** 尽量在空白处断开，断不了就按等分硬断。 */
    private static int[] splitBounds(List<String> chars, int lineCount) {
        int count = Math.max(1, Math.min(lineCount, chars.size()));
        int[] bounds = new int[count + 1];
        bounds[0] = 0;
        bounds[count] = chars.size();
        for (int i = 1; i < count; i++) {
            int ideal = (int) Math.round(chars.size() * i / (double) count);
            int best = ideal;
            for (int offset = 0; offset <= 3; offset++) {
                int forward = ideal + offset;
                int backward = ideal - offset;
                if (forward < chars.size() && " ".equals(chars.get(forward))) {
                    best = forward;
                    break;
                }
                if (backward > bounds[i - 1] && " ".equals(chars.get(backward))) {
                    best = backward;
                    break;
                }
            }
            bounds[i] = Math.max(bounds[i - 1] + 1, Math.min(best, chars.size() - (count - i)));
        }
        return bounds;
    }

    /** 一段覆盖的时间范围：取它包含的第一个与最后一个块的时间。 */
    private static long[] spanOf(List<FullscreenLyricStage.Token> tokens, int from, int to) {
        int cursor = 0;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            FullscreenLyricStage.Token token = tokens.get(i);
            int length = FullscreenLyricStage.graphemeCount(token.text);
            int tokenFrom = cursor;
            int tokenTo = cursor + length;
            cursor = tokenTo;
            if (tokenTo <= from || tokenFrom >= to) continue;
            start = Math.min(start, token.startMs);
            end = Math.max(end, token.endMs);
        }
        if (start == Long.MAX_VALUE) {
            start = tokens.isEmpty() ? 0L : tokens.get(0).startMs;
            end = start + 1L;
        }
        return new long[] {start, Math.max(end, start + 1L)};
    }

    private double measure(Segment segment) {
        double spacing = fontPx * (segment.tilt ? .15 : .08);
        double width = 0;
        for (int i = 0; i < segment.chars.size(); i++) {
            width += font.getStringWidthD(segment.chars.get(i)) * fontScale;
            if (i + 1 < segment.chars.size()) width += spacing;
        }
        return width;
    }

    // ==================== 绘制 ====================

    private void drawSegments(List<Segment> list, LyricLine line, double positionMs,
                              double centerX, double centerY, float alpha, double exitProgress) {
        if (font == null) return;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        int primary = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int normalLit = primary;
        int tiltLit = accent;
        switch (HudConfig.tiltColorScheme) {
            case 1:
                normalLit = accent;
                tiltLit = primary;
                break;
            case 2:
                normalLit = accent;
                tiltLit = accent;
                break;
            case 3:
                normalLit = primary;
                tiltLit = primary;
                break;
            default:
                break;
        }

        double exitFade = 1.0 - FullscreenLyricStage.easeInCubic(exitProgress);
        double exitShift = FullscreenLyricStage.easeInCubic(exitProgress) * fontPx * .7;

        for (int s = 0; s < list.size(); s++) {
            Segment segment = list.get(s);
            if (segment.chars == null || segment.chars.isEmpty()) continue;

            double enter = FullscreenLyricStage.clamp01(
                    (positionMs - (segment.startMs - ENTER_LEAD_MS)) / ENTER_MS);
            double eased = FullscreenLyricStage.easeOutCubic(enter);
            boolean active = positionMs >= segment.startMs && positionMs <= segment.endMs;
            boolean passed = positionMs > segment.endMs;

            double segmentAlpha = (passed ? .32 : (active ? 1.0 : .16)) * eased * exitFade * alpha;
            if (segmentAlpha <= .01) continue;

            int lit = segment.tilt ? tiltLit : normalLit;
            int dim = FullscreenLyricStage.mixRgb(lit, 0x9AA3AE, .55) & 0xFFFFFF;

            double baseX = centerX - segment.width * .5;
            double baseY = centerY + segment.y - exitShift;
            double stagger = segment.tilt ? fontPx * .085 : 0;
            double spacing = fontPx * (segment.tilt ? .15 : .08);

            FullscreenLyricStage.pushMatrix();
            try {
                if (segment.tilt) {
                    FullscreenLyricStage.shearAt(centerX, baseY, ITALIC_SHEAR);
                }
                double cursor = 0;
                int count = segment.chars.size();
                for (int i = 0; i < count; i++) {
                    String glyph = segment.chars.get(i);
                    double glyphWidth = font.getStringWidthD(glyph) * fontScale;
                    double p = active || passed
                            ? FullscreenLyricStage.graphemeProgress(line,
                                    segment.graphemeStart + i, totalGraphemes, positionMs, 300.0)
                            : 0.0;
                    // 参考实现的逐字延迟：每个字比前一个晚 0.04 秒进场。
                    double charEnter = FullscreenLyricStage.clamp01((enter * ENTER_MS - i * 40.0) / 260.0);
                    double charEased = FullscreenLyricStage.easeOutCubic(charEnter);
                    double y = baseY + (segment.tilt ? (i % 2 == 0 ? -stagger : stagger) : 0)
                            * (2.0 - charEased);
                    double glyphAlpha = segmentAlpha * (.35 + .65 * charEased)
                            * (active || passed ? .58 + .42 * p : 1.0);
                    if (glyphAlpha <= .008) {
                        cursor += glyphWidth + spacing;
                        continue;
                    }
                    int color = p <= 0 ? dim : FullscreenLyricStage.mixRgb(dim, lit, p) & 0xFFFFFF;
                    double drawX = baseX + cursor;
                    double scale = fontScale;
                    if (active && effects) {
                        scale *= 1.0 + HudConfig.currentWordScale * Math.sin(Math.PI * p);
                    }
                    FullscreenLyricStage.pushMatrix();
                    try {
                        double pivotX = drawX + glyphWidth * .5;
                        double pivotY = y + font.getFontHeight() * fontScale * .5;
                        if (Math.abs(scale - 1.0) > 1.0e-4) {
                            FullscreenLyricStage.scaleAt(pivotX, pivotY, scale);
                        }
                        double gx = pivotX - font.getStringWidthD(glyph) * .5;
                        double gy = pivotY - font.getFontHeight() * .5;
                        if (effects && p > .02 && p < .99) {
                            FullscreenLyricStage.drawGlow(font, glyph, gx, gy, lit, glyphAlpha,
                                    Math.sin(Math.PI * p));
                        }
                        font.drawString(glyph, gx, gy,
                                FullscreenLyricStage.withAlpha(color, glyphAlpha));
                    } finally {
                        FullscreenLyricStage.popMatrix();
                    }
                    cursor += glyphWidth + spacing;
                }
            } finally {
                FullscreenLyricStage.popMatrix();
            }
        }
    }
}
