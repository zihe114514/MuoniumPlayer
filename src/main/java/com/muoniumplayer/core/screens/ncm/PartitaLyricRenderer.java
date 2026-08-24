package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「云阶」：当前行切成若干块，左右交替错落成一段向下的阶梯，每一级配一条指示线。
 *
 * <p>移植自 folia-major 的 {@code partita} 视觉。参考实现有一份中文说明，核心是三件事：
 * <b>行数按可用高度算</b>、<b>每级的长度故意不均匀</b>、<b>左右交替的错落量在区间里随机</b>。
 * 均匀切分会让画面像表格，不均匀才有手写谱面的呼吸感。</p>
 */
final class PartitaLyricRenderer extends AbstractStageLyricRenderer {

    /** 进场提前量与时长，毫秒。 */
    private static final double ENTER_LEAD_MS = 240;
    private static final double ENTER_MS = 420;

    private static final double EXIT_SECONDS = .32;

    /** 阶梯最多几级。再多每级就只剩一两个字，反而看不出层次。 */
    private static final int MAX_ROWS = 5;

    /** 唱过之后每一级的倾角，度。左右两侧朝相反方向倒。 */
    private static final double PASSED_ROTATE = 3.0;

    /** 还没到的那几级额外往两侧退开多少（参考实现是 40px，按视口比例缩放）。 */
    private static final double WAITING_PUSH = 40.0;

    private static final class Row {
        String text = "";
        List<String> chars;
        int graphemeStart;
        long startMs;
        long endMs;
        double x;
        double y;
        double width;
        double height;
        double scale = 1;
        boolean left;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Row> outgoing = new ArrayList<Row>();

    private LyricLine laidOutLine;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;
    private double blockHeight;
    private int totalGraphemes;
    private double viewportScale = 1;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;

    private long exitStartNanos;

    protected void onReset() {
        rows.clear();
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
                outgoing.addAll(rows);
                exitStartNanos = now;
            }
            rebuild(currentLine, width, height);
            laidOutLine = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (rows.isEmpty() && outgoing.isEmpty()) return;

        double centerX = left + width * .5;
        double centerY = top + height * .5 + (1.0 - opened) * height * .3;

        if (!outgoing.isEmpty()) {
            double exit = (now - exitStartNanos) / 1.0e9 / EXIT_SECONDS;
            if (exit >= 1) {
                outgoing.clear();
            } else {
                drawRows(outgoing, laidOutLine, positionMs, centerX, centerY, alpha, exit, width);
            }
        }
        drawRows(rows, currentLine, positionMs, centerX, centerY, alpha, 0, width);
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine line, double viewWidth, double viewHeight) {
        rows.clear();
        blockHeight = 0;
        totalGraphemes = 0;
        if (line == null) return;

        viewportScale = FullscreenLyricStage.clamp(viewWidth / 640.0, .34, 1.25);
        List<FullscreenLyricStage.Token> tokens = FullscreenLyricStage.tokensOf(line, 5);
        if (tokens.isEmpty()) return;

        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * 1.06;
        for (int attempt = 0; attempt < 8; attempt++) {
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            if (layout(tokens, line, viewWidth, viewHeight)) return;
            targetPx *= .84;
            if (targetPx < 11) break;
        }
        layout(tokens, line, viewWidth, viewHeight);
    }

    private boolean layout(List<FullscreenLyricStage.Token> tokens, LyricLine line,
                           double viewWidth, double viewHeight) {
        rows.clear();
        totalGraphemes = 0;
        long seed = FullscreenLyricStage.seedOf(line);

        double rowHeight = fontPx * 1.58;
        double availableHeight = viewHeight * .68;
        int targetRowCount = Math.max(1, (int) Math.floor(availableHeight / Math.max(1.0, rowHeight)));
        int rowCount = Math.max(1, Math.min(Math.min(tokens.size(), targetRowCount), MAX_ROWS));

        // 故意不均匀的切分：块数在平均值上下浮动，最后一级吃掉剩下的全部。
        List<List<FullscreenLyricStage.Token>> chunks = new ArrayList<List<FullscreenLyricStage.Token>>();
        double average = tokens.size() / (double) rowCount;
        int cursor = 0;
        for (int r = 0; r < rowCount; r++) {
            int length;
            if (r == rowCount - 1) {
                length = tokens.size() - cursor;
            } else {
                double jitter = (FullscreenLyricStage.random(seed, 200 + r) - .5) * average;
                length = (int) Math.round(average + jitter);
                length = (int) FullscreenLyricStage.clamp(length, 1, Math.ceil(average * 1.5));
                length = Math.min(length, tokens.size() - cursor - (rowCount - 1 - r));
            }
            if (length <= 0) length = 1;
            List<FullscreenLyricStage.Token> chunk = new ArrayList<FullscreenLyricStage.Token>();
            for (int i = 0; i < length && cursor < tokens.size(); i++) {
                chunk.add(tokens.get(cursor++));
            }
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (cursor >= tokens.size()) break;
        }
        if (chunks.isEmpty()) return true;

        double staggerMin = Math.min(HudConfig.partitaStaggerMin, HudConfig.partitaStaggerMax);
        double staggerMax = Math.max(HudConfig.partitaStaggerMin, HudConfig.partitaStaggerMax);
        double stageWidth = Math.max(48.0, viewWidth - 12.0);
        boolean fits = true;

        int count = chunks.size();
        blockHeight = count * rowHeight;
        double blockTop = -blockHeight * .5;
        int graphemeCursor = 0;

        for (int r = 0; r < count; r++) {
            List<FullscreenLyricStage.Token> chunk = chunks.get(r);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) builder.append(chunk.get(i).text);

            Row row = new Row();
            row.text = builder.toString();
            row.chars = FullscreenLyricStage.graphemes(row.text);
            row.graphemeStart = graphemeCursor;
            graphemeCursor += row.chars.size();
            row.startMs = chunk.get(0).startMs;
            row.endMs = chunk.get(chunk.size() - 1).endMs;
            row.left = r % 2 == 0;

            double magnitude = (staggerMin + FullscreenLyricStage.random(seed, 300 + r)
                    * (staggerMax - staggerMin)) * viewportScale;
            row.scale = .93 + FullscreenLyricStage.random(seed, 400 + r) * .18;
            row.width = font.getStringWidthD(row.text) * fontScale * row.scale;
            row.height = font.getFontHeight() * fontScale * row.scale;

            double rowBias = r - (count - 1) * .5;
            row.y = blockTop + r * rowHeight + rowBias * 2.5 * viewportScale;
            row.x = row.left ? -magnitude : magnitude;

            // 错落量再大也不能把这一级推出视口：先夹住偏移，真的还是超宽才判定这一档字号排不开。
            double half = row.width * .5;
            double limit = stageWidth * .5 - half;
            if (limit < 0) {
                fits = false;
                row.x = 0;
            } else {
                row.x = FullscreenLyricStage.clamp(row.x, -limit, limit);
            }
            rows.add(row);
        }
        totalGraphemes = Math.max(1, graphemeCursor);
        return fits && blockHeight <= viewHeight - 8;
    }

    // ==================== 绘制 ====================

    private void drawRows(List<Row> list, LyricLine line, double positionMs,
                          double centerX, double centerY, float alpha, double exitProgress,
                          double viewWidth) {
        if (font == null) return;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, accent, .55) & 0xFFFFFF;
        boolean guides = HudConfig.partitaGuideLines;

        double exitFade = 1.0 - FullscreenLyricStage.easeInCubic(exitProgress);
        double exitDrop = FullscreenLyricStage.easeInCubic(exitProgress) * fontPx;

        for (int r = 0; r < list.size(); r++) {
            Row row = list.get(r);
            if (row.chars == null || row.chars.isEmpty()) continue;

            double enter = FullscreenLyricStage.clamp01(
                    (positionMs - (row.startMs - ENTER_LEAD_MS)) / ENTER_MS);
            double eased = FullscreenLyricStage.easeOutCubic(enter);
            boolean active = positionMs >= row.startMs && positionMs <= row.endMs;
            boolean passed = positionMs > row.endMs;
            double own = active
                    ? FullscreenLyricStage.clamp01((positionMs - row.startMs) / row.durationMs())
                    : (passed ? 1 : 0);

            double rowAlpha = passed ? .34 : (active ? 1.0 : .18);
            rowAlpha *= exitFade * alpha;
            if (rowAlpha <= .01) continue;

            double push = WAITING_PUSH * viewportScale * (1.0 - eased) * (row.left ? -1 : 1);
            double scale = row.scale * FullscreenLyricStage.lerp(.85, 1.0, eased);
            if (active && effects) scale *= 1.0 + HudConfig.currentWordScale * .5 * Math.sin(Math.PI * own);

            double x = centerX + row.x + push;
            double y = centerY + row.y + exitDrop;

            if (guides) {
                drawGuide(row, x, y, accent, rowAlpha, eased, active, own);
            }

            FullscreenLyricStage.pushMatrix();
            try {
                if (passed) FullscreenLyricStage.rotateAt(x, y, (row.left ? 1 : -1) * PASSED_ROTATE);
                double total = fontScale * scale;
                if (Math.abs(total - 1.0) > 1.0e-4) FullscreenLyricStage.scaleAt(x, y, total);
                double startX = x - font.getStringWidthD(row.text) * .5;
                double startY = y - font.getFontHeight() * .5;
                FullscreenLyricStage.drawLitRun(font, row.chars, row.graphemeStart, line,
                        totalGraphemes, positionMs, startX, startY, dimColor, litColor,
                        rowAlpha, active || passed, 260.0, effects && active);
            } finally {
                FullscreenLyricStage.popMatrix();
            }
        }
    }

    /** 每一级外侧的指示线：等待时长度为 0，随这一级进场展开。 */
    private void drawGuide(Row row, double x, double y, int accent, double alpha,
                           double eased, boolean active, double own) {
        double full = Math.max(10.0, row.width * .5 + fontPx * .8);
        double length = full * eased * (active ? .55 + .45 * own : (own >= 1 ? .8 : .35));
        if (length <= 1.5) return;
        double thickness = Math.max(1.0, fontPx * .055);
        double gap = fontPx * .45;
        double lineY = y + row.height * .5 + gap * .35;
        double lineX = row.left ? x - row.width * .5 : x + row.width * .5 - length;
        FullscreenLyricStage.bar(lineX, lineY, length, thickness, thickness * .5, accent,
                alpha * (active ? .55 : .28));
    }
}
