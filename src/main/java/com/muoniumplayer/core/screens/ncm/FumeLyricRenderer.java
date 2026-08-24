package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「浮名」：把整段歌词排成一篇文章，镜头在文章里移动，正在唱的那一行被推到画面中央并逐字显影。
 *
 * <p>移植自 folia-major 的 {@code fume} 视觉。参考实现按字形数把行分成「主行 / 正文行」两种字号，
 * 镜头带缩放地在整篇里移动，唱过的段落淡到几乎看不见但仍留在画面上当上下文。</p>
 *
 * <h3>两处必要的适配</h3>
 * <ul>
 *   <li>参考实现让主行<b>故意溢出画面</b>。这里的歌词区只有播放器的一半宽，直接溢出会把字裁掉看不见，
 *       所以改成<b>镜头自动缩放</b>：按当前行的实际宽度把缩放调到「刚好占满可用宽度」，
 *       既保留了「主行大、正文小」的层次，又保证整行可读。</li>
 *   <li>整首歌上百行不会全部排出来，只取当前行前后各若干行。窗口滑动时会重排，这时按「上一帧的当前行
 *       在新排版里的位置」把镜头一起平移过去，所以看不到跳动。</li>
 * </ul>
 */
final class FumeLyricRenderer extends AbstractStageLyricRenderer {

    private static final int WINDOW_BEFORE = 5;
    private static final int WINDOW_AFTER = 6;

    /** 参考实现的镜头缩放上下限。 */
    private static final double CAMERA_SCALE_MIN = .45;
    private static final double CAMERA_SCALE_MAX = 1.85;

    /** 参考实现的主行字形数区间：太短撑不起主行，太长会挤成一团。 */
    private static final int HERO_MIN_GRAPHEMES = 4;
    private static final int HERO_MAX_GRAPHEMES = 28;

    /** 唱过的段落留多暗。参考实现暗色主题下是 0.11 / 0.075。 */
    private static final double PASSED_HERO_ALPHA = .13;
    private static final double PASSED_BODY_ALPHA = .085;

    private static final double UPCOMING_ALPHA = .26;

    private static final class Row {
        LyricLine line;
        List<String> chars;
        int graphemeStart;
        int lineGraphemeTotal;
        boolean hero;
        boolean firstRow;
        double x;
        double y;
        double width;
        double height;
        double fontScale;
        CFontRenderer font;
    }

    private final List<Row> rows = new ArrayList<Row>();

    private LyricLine laidOutCenter;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    private double cameraX;
    private double cameraY;
    private double cameraScale = 1;
    private boolean cameraReady;

    /** 上一次排版时用来对位的锚：窗口滑动重排后靠它把镜头平移回同一处。 */
    private LyricLine anchorLine;
    private double anchorY;

    private double heroPx = 20;
    private double bodyPx = 12;

    protected void onReset() {
        rows.clear();
        laidOutCenter = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        cameraX = 0;
        cameraY = 0;
        cameraScale = 1;
        cameraReady = false;
        anchorLine = null;
        anchorY = 0;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        boolean viewportChanged = tuningDirty
                || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0;
        if (currentLine != laidOutCenter || viewportChanged) {
            double previousAnchorY = anchorY;
            LyricLine previousAnchor = anchorLine;
            rebuild(currentLine, positionMs, width, height);
            laidOutCenter = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
            if (previousAnchor != null) {
                double newY = docYOf(previousAnchor);
                if (!Double.isNaN(newY)) cameraY += newY - previousAnchorY;
            }
            anchorLine = currentLine;
            anchorY = docYOf(currentLine);
            if (Double.isNaN(anchorY)) anchorY = 0;
        }
        if (rows.isEmpty()) return;

        Row focus = focusRow(currentLine, positionMs);
        double targetScale = 1.0;
        double targetY = 0;
        double targetX = 0;
        if (focus != null) {
            double available = width * .86;
            targetScale = FullscreenLyricStage.clamp(
                    focus.width <= 1 ? 1.0 : available / focus.width,
                    CAMERA_SCALE_MIN, CAMERA_SCALE_MAX);
            targetY = focus.y + focus.height * .5;
            targetX = focus.x + focus.width * .5;
        }

        // 镜头逼近：时间常数由「镜头速度」控制，与帧率无关。
        double speed = FullscreenLyricStage.clamp(HudConfig.fumeCameraSpeed, .3, 3.0);
        double approach = 1.0 - Math.exp(-frameSeconds * 3.2 * speed);
        if (!cameraReady) {
            cameraX = targetX;
            cameraY = targetY;
            cameraScale = targetScale;
            cameraReady = true;
        } else {
            cameraX += (targetX - cameraX) * approach;
            cameraY += (targetY - cameraY) * approach;
            cameraScale += (targetScale - cameraScale) * approach;
        }

        double viewCenterX = left + width * .5;
        double viewCenterY = top + height * .5 + (1.0 - opened) * height * .25;

        if (HudConfig.fumeGeometricBackground) {
            drawBackground(left, top, width, height, alpha);
        }

        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, 0x808A98, .55) & 0xFFFFFF;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        double hold = FullscreenLyricStage.clamp(HudConfig.fumeTextHoldRatio, .3, 2.5);

        // 只画镜头附近的行：一首歌上百行，画满一屏之外的部分纯属浪费。
        double halfSpan = height / Math.max(.2, cameraScale) * .75 + heroPx * 3;

        FullscreenLyricStage.pushMatrix();
        try {
            FullscreenLyricStage.translate(viewCenterX, viewCenterY);
            FullscreenLyricStage.scale(cameraScale, cameraScale);
            FullscreenLyricStage.translate(-cameraX, -cameraY);

            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.chars == null || row.chars.isEmpty()) continue;
                if (Math.abs(row.y + row.height * .5 - cameraY) > halfSpan) continue;

                int state = FullscreenLyricStage.lineState(row.line, positionMs);
                double rowAlpha;
                if (state == FullscreenLyricStage.STATE_ACTIVE) {
                    rowAlpha = 1.0;
                } else if (state == FullscreenLyricStage.STATE_PASSED) {
                    // 唱完后按 textHoldRatio 慢慢暗下去，越久越淡，但永不消失（要当上下文）。
                    double since = positionMs - FullscreenLyricStage.lineEndMs(row.line);
                    double fade = FullscreenLyricStage.clamp01(since / (2400.0 * hold));
                    double target = row.hero ? PASSED_HERO_ALPHA : PASSED_BODY_ALPHA;
                    rowAlpha = FullscreenLyricStage.lerp(.5, target, fade);
                } else {
                    rowAlpha = UPCOMING_ALPHA * (row.hero ? 1.0 : .8);
                }
                rowAlpha *= alpha;
                if (rowAlpha <= .012) continue;

                boolean lit = state != FullscreenLyricStage.STATE_WAITING;
                FullscreenLyricStage.pushMatrix();
                try {
                    FullscreenLyricStage.scaleAt(row.x, row.y, row.fontScale);
                    if (state == FullscreenLyricStage.STATE_ACTIVE) {
                        FullscreenLyricStage.drawLitRun(row.font, row.chars, row.graphemeStart,
                                row.line, row.lineGraphemeTotal, positionMs, row.x, row.y,
                                dimColor, litColor, rowAlpha, true, 280.0, effects);
                    } else {
                        int color = lit ? dimColor
                                : FullscreenLyricStage.mixRgb(dimColor, accent, .2) & 0xFFFFFF;
                        double cursor = 0;
                        for (int c = 0; c < row.chars.size(); c++) {
                            String glyph = row.chars.get(c);
                            row.font.drawString(glyph, row.x + cursor, row.y,
                                    FullscreenLyricStage.withAlpha(color, rowAlpha));
                            cursor += row.font.getStringWidthD(glyph);
                        }
                    }
                } finally {
                    FullscreenLyricStage.popMatrix();
                }
            }
        } finally {
            FullscreenLyricStage.popMatrix();
        }
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine center, double positionMs,
                         double viewWidth, double viewHeight) {
        rows.clear();
        List<LyricLine> lines = window(center, positionMs, WINDOW_BEFORE, WINDOW_AFTER);
        if (lines.isEmpty()) return;

        double base = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight);
        heroPx = FullscreenLyricStage.clamp(
                base * 1.30 * FullscreenLyricStage.clamp(HudConfig.fumeHeroScale, .6, 1.6), 14, 58);
        bodyPx = FullscreenLyricStage.clamp(base * .74, 10, 30);

        CFontRenderer heroFont = FullscreenLyricStage.fontAtMost(heroPx);
        double heroScale = FullscreenLyricStage.scaleFor(heroFont, heroPx);
        CFontRenderer bodyFont = FullscreenLyricStage.fontAtMost(bodyPx);
        double bodyScale = FullscreenLyricStage.scaleFor(bodyFont, bodyPx);

        double wrapWidth = Math.max(60.0, viewWidth * 1.02);
        double y = 0;

        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            String text = line.lyric == null ? "" : line.lyric;
            if (text.trim().isEmpty()) continue;
            List<String> chars = FullscreenLyricStage.graphemes(text);
            if (chars.isEmpty()) continue;

            boolean hero = chars.size() >= HERO_MIN_GRAPHEMES && chars.size() <= HERO_MAX_GRAPHEMES;
            CFontRenderer font = hero ? heroFont : bodyFont;
            double scale = hero ? heroScale : bodyScale;
            double lineHeight = font.getFontHeight() * scale * 1.16;

            // 折行：宽度按缩放后的实际像素算。
            int cursor = 0;
            boolean first = true;
            while (cursor < chars.size()) {
                int take = 0;
                double width = 0;
                while (cursor + take < chars.size()) {
                    double advance = font.getStringWidthD(chars.get(cursor + take)) * scale;
                    if (take > 0 && width + advance > wrapWidth) break;
                    width += advance;
                    take++;
                }
                if (take <= 0) take = 1;
                Row row = new Row();
                row.line = line;
                row.chars = new ArrayList<String>(chars.subList(cursor, cursor + take));
                row.graphemeStart = cursor;
                row.lineGraphemeTotal = chars.size();
                row.hero = hero;
                row.firstRow = first;
                row.font = font;
                row.fontScale = scale;
                row.height = font.getFontHeight() * scale;
                row.width = width;
                // 段落缩进：每一行按种子取一个小偏移，整篇看起来像手排的文章而不是居中标题。
                double jitter = (FullscreenLyricStage.random(
                        FullscreenLyricStage.seedOf(line), 17 + cursor) - .5) * viewWidth * .08;
                row.x = -width * .5 + jitter;
                row.y = y;
                rows.add(row);
                y += lineHeight;
                cursor += take;
                first = false;
            }
            y += lineHeight * .35;
        }
    }

    private double docYOf(LyricLine line) {
        if (line == null) return Double.NaN;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.line == line && row.firstRow) return row.y + row.height * .5;
        }
        return Double.NaN;
    }

    /** 镜头对准哪一行：当前行里正在唱的那一折行，没有当前行时对准最近的一行。 */
    private Row focusRow(LyricLine currentLine, double positionMs) {
        Row fallback = null;
        Row best = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.line != currentLine) continue;
            if (fallback == null) fallback = row;
            double from = FullscreenLyricStage.graphemeProgress(row.line, row.graphemeStart,
                    row.lineGraphemeTotal, positionMs, 1.0);
            if (from > 0) best = row;
        }
        if (best != null) return best;
        if (fallback != null) return fallback;
        // 当前行不在这批排版里（间奏、空行）：对准最后一条已经开始的行，而不是退回第一行 ——
        // 退回第一行会让镜头在间奏时突然甩到开头。
        Row nearest = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.line != null && row.line.timestamp <= positionMs) nearest = row;
        }
        if (nearest != null) return nearest;
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 背景几何体：几块缓慢漂移的半透明矩形，带视差。参考实现默认是关掉的。 */
    private void drawBackground(double left, double top, double width, double height, float alpha) {
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        double opacity = alpha * .5 * (.35 + beat * .4);
        if (opacity <= .01) return;
        for (int i = 0; i < 4; i++) {
            double phase = elapsedSeconds * (.06 + i * .017) + i * 1.7;
            double px = left + width * (.2 + .6 * (.5 + .5 * Math.sin(phase)));
            double py = top + height * (.18 + .64 * (.5 + .5 * Math.cos(phase * .83 + i)));
            double size = Math.min(width, height) * (.10 + i * .035);
            FullscreenLyricStage.bar(px - size * .5, py - size * .5, size, size,
                    size * .22, accent, opacity * (.10 - i * .015));
        }
    }
}
