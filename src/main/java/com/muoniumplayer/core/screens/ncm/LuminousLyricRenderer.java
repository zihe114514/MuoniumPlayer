package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「流光」：单行居中、逐字点亮。
 *
 * <p>移植自 folia-major 的 {@code classic} 视觉。只画当前一行，把它切成若干块横排（必要时折行），
 * 每一块有自己的时间轴：等待时暗、点亮时逐字亮起并微微放大、唱过之后留一层余晖。</p>
 *
 * <h3>与项目原有「逐行滚动」的区别</h3>
 * <p>原有样式是整首歌滚动 + 当前行 KTV 填涂；这一套<b>不滚动、不填涂</b>，只有当前行，
 * 靠逐字的亮度与缩放推进。两者是并列的两种样式，不是替代关系。</p>
 */
final class LuminousLyricRenderer extends AbstractStageLyricRenderer {

    /** 进场提前量与时长，毫秒。提前一点开始进场，点亮时刚好站稳。 */
    private static final double ENTER_LEAD_MS = 260;
    private static final double ENTER_MS = 460;

    /** 退场时长，秒。用墙上时钟，与播放进度无关。 */
    private static final double EXIT_SECONDS = .34;

    /** 一行最多折几行。再多就说明字号该降了。 */
    private static final int MAX_ROWS = 4;

    /** 参考实现 normal 情绪档的散布与旋转幅度。 */
    private static final double BASE_SPREAD = 20.0;
    private static final double BASE_ROTATE = 5.0;

    /** 涟漪时长，秒。 */
    private static final double RIPPLE_SECONDS = .5;

    private static final class Word {
        String text = "";
        List<String> chars;
        long startMs;
        long endMs;
        double x;
        double y;
        double width;
        double height;
        double baseScale = 1;
        double rotate;
        double spreadX;
        double spreadY;
        double ripple;
        int seedOffset;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    private final List<Word> words = new ArrayList<Word>();
    private final List<Word> outgoing = new ArrayList<Word>();

    private LyricLine laidOutLine;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;
    private double blockHeight;
    private double blockWidth;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;

    private long exitStartNanos;

    protected void onReset() {
        words.clear();
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
                outgoing.addAll(words);
                exitStartNanos = now;
            }
            rebuild(currentLine, width, height);
            laidOutLine = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (words.isEmpty() && outgoing.isEmpty()) return;

        // 整块随节拍轻微上下浮动。倍率为 0 时完全静止。
        double floatAmount = Math.max(0f, HudConfig.luminousFloatAmount);
        double bob = Math.sin(elapsedSeconds * 1.15) * 2.4 * floatAmount
                + beat * 3.0 * floatAmount;

        double centerX = left + width * .5;
        double centerY = top + height * .5 + bob + (1.0 - opened) * height * .35;

        if (!outgoing.isEmpty()) {
            double exit = (now - exitStartNanos) / 1.0e9 / EXIT_SECONDS;
            if (exit >= 1) {
                outgoing.clear();
            } else {
                drawWords(outgoing, positionMs, centerX, centerY, alpha, exit);
            }
        }
        drawWords(words, positionMs, centerX, centerY, alpha, 0);
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine line, double viewWidth, double viewHeight) {
        words.clear();
        blockHeight = 0;
        blockWidth = 0;
        if (line == null) return;

        double stageWidth = Math.max(48.0, viewWidth - 16.0);
        double stageHeight = Math.max(40.0, viewHeight - 24.0);
        long seed = FullscreenLyricStage.seedOf(line);

        List<FullscreenLyricStage.Token> tokens = FullscreenLyricStage.tokensOf(line, 4);
        if (tokens.isEmpty()) return;

        // 字号：从基准往下找第一个能在 MAX_ROWS 行内排完、且总高度装得进舞台的档位。
        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * 1.35;
        for (int attempt = 0; attempt < 8; attempt++) {
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            if (layout(tokens, seed, stageWidth, stageHeight)) return;
            targetPx *= .82;
            if (targetPx < 11) break;
        }
        // 到这一步说明再小也排不开（极窄视口 + 极长的一行）。保留最后一次布局，宁可字小也不叠。
        layout(tokens, seed, stageWidth, stageHeight);
    }

    /** @return true 表示这一档字号排得开 */
    private boolean layout(List<FullscreenLyricStage.Token> tokens, long seed,
                           double stageWidth, double stageHeight) {
        words.clear();
        double gap = fontPx * (.05 + .30 * Math.max(.05f, HudConfig.luminousWordSpacing));
        double lineHeight = fontPx * 1.26;
        boolean rotation = HudConfig.luminousWordRotation;

        List<List<Word>> rows = new ArrayList<List<Word>>();
        List<Word> row = new ArrayList<Word>();
        double rowWidth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            FullscreenLyricStage.Token token = tokens.get(i);
            Word word = new Word();
            word.text = token.text;
            word.chars = FullscreenLyricStage.graphemes(token.text);
            word.startMs = token.startMs;
            word.endMs = token.endMs;
            word.seedOffset = i * 7 + 3;
            word.width = font.getStringWidthD(token.text) * fontScale;
            word.height = font.getFontHeight() * fontScale;

            double r1 = FullscreenLyricStage.random(seed, word.seedOffset);
            double r2 = FullscreenLyricStage.random(seed, word.seedOffset + 1);
            double r3 = FullscreenLyricStage.random(seed, word.seedOffset + 2);
            word.baseScale = 1.0 + r1 * .12;
            word.rotate = rotation ? (r2 - .5) * BASE_ROTATE : 0;
            word.spreadX = (r2 - .5) * BASE_SPREAD;
            word.spreadY = (r3 - .5) * BASE_SPREAD * .6 + lineHeight * .35;
            word.ripple = r3;

            double advance = word.width + (row.isEmpty() ? 0 : gap);
            if (!row.isEmpty() && rowWidth + advance > stageWidth) {
                rows.add(row);
                row = new ArrayList<Word>();
                rowWidth = 0;
                advance = word.width;
            }
            rowWidth += advance;
            row.add(word);
        }
        if (!row.isEmpty()) rows.add(row);

        blockHeight = rows.size() * lineHeight;
        blockWidth = 0;

        double blockTop = -blockHeight * .5;
        for (int r = 0; r < rows.size(); r++) {
            List<Word> current = rows.get(r);
            double total = 0;
            for (int i = 0; i < current.size(); i++) {
                total += current.get(i).width + (i == 0 ? 0 : gap);
            }
            blockWidth = Math.max(blockWidth, total);
            double cursor = -total * .5;
            double rowTop = blockTop + r * lineHeight + (lineHeight - font.getFontHeight() * fontScale) * .5;
            for (int i = 0; i < current.size(); i++) {
                Word word = current.get(i);
                if (i > 0) cursor += gap;
                word.x = cursor;
                word.y = rowTop;
                cursor += word.width;
                words.add(word);
            }
        }
        return rows.size() <= MAX_ROWS && blockHeight <= stageHeight && blockWidth <= stageWidth + .5;
    }

    // ==================== 绘制 ====================

    private void drawWords(List<Word> list, double positionMs, double centerX, double centerY,
                           float alpha, double exitProgress) {
        if (font == null) return;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        double wordScale = effects ? Math.max(0.0, HudConfig.currentWordScale) : 0.0;
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, FullscreenLyricStage.accent() & 0xFFFFFF, .45)
                & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;

        double exitFade = 1.0 - FullscreenLyricStage.easeInCubic(exitProgress);
        double exitRise = FullscreenLyricStage.easeInCubic(exitProgress) * fontPx * .8;

        for (int i = 0; i < list.size(); i++) {
            Word word = list.get(i);
            if (word.chars == null || word.chars.isEmpty()) continue;

            double enter = FullscreenLyricStage.clamp01(
                    (positionMs - (word.startMs - ENTER_LEAD_MS)) / ENTER_MS);
            double eased = FullscreenLyricStage.easeOutExpo(enter);
            boolean active = positionMs >= word.startMs && positionMs <= word.endMs;
            boolean passed = positionMs > word.endMs;

            double own = active ? FullscreenLyricStage.clamp01(
                    (positionMs - word.startMs) / word.durationMs()) : (passed ? 1 : 0);

            double bodyAlpha = passed ? .40 : (active ? 1.0 : .18);
            bodyAlpha *= eased * exitFade * alpha;
            if (bodyAlpha <= .01) continue;

            double scale = word.baseScale;
            if (active && wordScale > 0) scale *= 1.0 + (.18 + wordScale) * Math.sin(Math.PI * own);
            double dx = word.spreadX * (1.0 - eased);
            double dy = word.spreadY * (1.0 - eased) - exitRise;

            double drawX = centerX + word.x + dx;
            double drawY = centerY + word.y + dy;
            double pivotX = drawX + word.width * .5;
            double pivotY = drawY + word.height * .5;

            FullscreenLyricStage.pushMatrix();
            try {
                if (Math.abs(word.rotate) > .05 || enter < 1) {
                    FullscreenLyricStage.rotateAt(pivotX, pivotY, word.rotate * (2.0 - eased));
                }
                double total = scale * fontScale;
                if (Math.abs(total - 1.0) > 1.0e-4) FullscreenLyricStage.scaleAt(pivotX, pivotY, total);

                // 涟漪：字词刚被点亮的半秒里从中心扩散一圈。参考实现只在副歌开，这里改成跟节拍走，
                // 安静段落不会一直冒圈。
                if (active && beat > .28) {
                    double t = FullscreenLyricStage.clamp01(
                            (positionMs - word.startMs) / (RIPPLE_SECONDS * 1000.0));
                    if (t < 1) {
                        double radius = word.height / fontScale
                                * (.2 + (1.5 + word.ripple * 2.0) * FullscreenLyricStage.easeOutCubic(t));
                        FullscreenLyricStage.dot(pivotX, pivotY, radius * .5, accent,
                                .18 * (1.0 - t) * alpha * beat);
                    }
                }

                drawGraphemes(word, drawX, drawY, litColor, dimColor, bodyAlpha,
                        active || passed, own);
            } finally {
                FullscreenLyricStage.popMatrix();
            }
        }
    }

    /**
     * 逐字绘制。
     *
     * <p>绘制时外层已经套过 {@code fontScale}，所以这里的推进量必须用<b>未缩放</b>的字宽，
     * 否则字距会被乘两次。</p>
     */
    private void drawGraphemes(Word word, double x, double y,
                               int litColor, int dimColor, double alpha,
                               boolean lightUp, double own) {
        // 变换是绕字块中心做的：起点按未缩放尺寸摆在中心两侧，缩放才会「以中心为轴」放大，
        // 而不是把整块往右推。
        double pivotX = x + word.width * .5;
        double pivotY = y + word.height * .5;
        double startX = pivotX - font.getStringWidthD(word.text) * .5;
        double startY = pivotY - font.getFontHeight() * .5;

        int count = word.chars.size();
        double cursor = 0;
        for (int i = 0; i < count; i++) {
            String glyph = word.chars.get(i);
            double glyphWidth = font.getStringWidthD(glyph);
            double p = 0;
            if (lightUp) {
                double slot = 1.0 / count;
                p = FullscreenLyricStage.clamp01((own - i * slot) / Math.max(.04, slot * .85));
            }
            int color = FullscreenLyricStage.mixRgb(dimColor, litColor, p) & 0xFFFFFF;
            double glyphAlpha = alpha * (lightUp ? .55 + .45 * p : 1.0);
            if (p > .02 && p < .98) {
                FullscreenLyricStage.drawGlow(font, glyph, startX + cursor, startY, litColor,
                        glyphAlpha, Math.sin(Math.PI * p));
            }
            font.drawString(glyph, startX + cursor, startY,
                    FullscreenLyricStage.withAlpha(color, glyphAlpha));
            cursor += glyphWidth;
        }
    }
}
