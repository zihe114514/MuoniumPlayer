package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「苹果」：仿 Apple Music 全屏歌词。
 *
 * <h3>参考来源与它到底给了什么</h3>
 * <p>参照 LyricsBlossom 对 iPadOS 16.4 Apple Music 的逆向笔记（{@code Lyrics.swift}，
 * 由 Eplor 逆向/翻译，仅作保真研究参考）。需要说明的是：那份笔记还原的是 Apple Music 的
 * 歌词「数据模型与解析链」（{@code Lyrics.init(songInfo:)} → {@code buildLinesDictionary} →
 * {@code assembleProcessedLines}），并没有任何动画、缓动或绘制代码。所以这一套样式的
 * 「动画」是按 Apple Music 的实际观感复刻的，而「分层与时间轴的用法」才是照那份笔记来的：</p>
 * <ul>
 *   <li>{@code TextLine} 同时记 {@code startTime/endTime} 与 {@code firstWordStart/lastWordEnd}
 *       —— 行的窗口与「真正开口」是两回事，中间那段空档就是 Apple 拿来放等待圆点的。
 *       这里对应 {@link Row#gapStartMs}/{@link Row#gapEndMs}。</li>
 *   <li>{@code hasWordLevelSync} 决定有没有逐音节轴：有就逐音节推进，没有就整行一起亮。
 *       项目既有的 {@link FullscreenLyricStage#graphemeProgress} 正是这个语义
 *       （没有逐字轴时绝不按字数均分行时长去伪装逐字），这里直接沿用。</li>
 *   <li>{@code agent} 换人时翻转 {@code isBackground}，"group" 恒为主唱、"other" 恒为背景。
 *       项目这边的等价物是 {@link LyricLine#duetSlot}（由 {@link LyricDuetGroups} 写入），
 *       第二位歌手的行右对齐 —— 这也是 Apple 对唱段落的排版。</li>
 *   <li>{@code line.backgroundVocals} 与 {@code filteredWords} 是<b>两路数据</b>（连字典都各有
 *       一套 copy/resize），所以背景和声在 Apple 那儿从来不是主歌词的一部分，而是主歌词下面一条
 *       更小更暗的行。{@link LyricParser} 为了不产生时间重叠的行，把 {@code x-bg} 追加到主行词表
 *       末尾，现在它会同时给这些音节打上 {@link LyricLine.Word#background} 标记，这套样式据此把
 *       两路重新分开排版（其余样式不读这个标记，行为不变）。</li>
 *   <li>{@code firstWordStart}/{@code lastWordEnd} 才是「开口」与「唱完」，行窗口的
 *       {@code startTime}/{@code endTime} 不是 —— 等待圆点的空档必须按前者算，见 {@link #applyGap}。</li>
 * </ul>
 *
 * <h3>唯一没有照搬的一处</h3>
 * <p>{@code isRTL}（阿拉伯语/希伯来语从右往左排字）没做：项目的字形绘制是从左往右逐字推进的，
 * 换方向要动字体层，不在这一套样式的范围里。</p>
 *
 * <h3>与「逐行滚动」的区别</h3>
 * <p>经典样式是居中、整行 KTV 填涂、线性滚动；这一套是左对齐（对唱行右对齐）、逐音节点亮 +
 * 音节弹跳、弹簧滚动、长音节持续发光、前奏/间奏显示等待圆点。两者并列，互不替代。</p>
 */
final class AppleLyricRenderer extends AbstractStageLyricRenderer {

    /** 窗口：往前少往后多。Apple 的版式重心偏上，屏幕大半留给还没唱的。 */
    private static final int WINDOW_BEFORE = 3;
    private static final int WINDOW_AFTER = 6;

    /** 当前行的顶边停在视口高度的这个位置。 */
    private static final double ANCHOR = .34;

    /** 一句最多折几行；再多就降一档字号重排。 */
    private static final int MAX_VISUAL_ROWS = 4;

    /** 单句最多占视口多高。 */
    private static final double MAX_LINE_HEIGHT_RATIO = .52;

    /**
     * 滚动用的弹簧。
     *
     * <p>Apple 的换行不是线性插值，而是一条会「坐稳」的曲线：起步快、末段慢、几乎不过冲。
     * 取 {@code damping = 2*sqrt(stiffness) * .96}，比临界阻尼略欠一点，末段会有肉眼几乎
     * 看不出的一次回弹 —— 那一下正是它不显得死板的原因。</p>
     */
    private static final double SPRING_STIFFNESS = 190.0;
    private static final double SPRING_DAMPING = 2.0 * Math.sqrt(SPRING_STIFFNESS) * .96;

    /** 空档超过这么久才显示等待圆点。 */
    private static final long DOTS_MIN_GAP_MS = 5_000L;

    /** 圆点区域预留的高度倍率（相对字号）。 */
    private static final double DOTS_BLOCK_RATIO = 1.15;

    /** 逐字淡入窗口，毫秒。比其它样式短：Apple 的字是「啪」一下亮起来的。 */
    private static final double CHAR_FADE_MS = 165;

    /** 超过这么长的音节算「长音」，会持续发光并多浮起来一点。 */
    private static final double LONG_SYLLABLE_MS = 900;

    /** 音节弹跳的上浮量（相对字号）。 */
    private static final double POP_LIFT_RATIO = .085;

    /**
     * 背景和声（TTML 的 {@code x-bg}）单独排一条小字行。
     *
     * <p>Apple 的歌词模型里和声是独立于主人声的一路数据（逆向笔记里 {@code backgroundVocals}
     * 与 {@code filteredWords} 是两个字段），排版上也是主歌词下面一条更小更暗的行。字号取主歌词
     * 的 {@code .70}，透明度与动效幅度都收到七成上下 —— 它是垫在后面的，不该抢主歌词的戏。</p>
     */
    private static final double BACKGROUND_SCALE = .70;
    private static final double BACKGROUND_ALPHA = .72;
    private static final double BACKGROUND_MOTION = .55;
    /** 和声行与主歌词之间的间距（相对主歌词字号）。 */
    private static final double BACKGROUND_GAP_RATIO = .12;

    /** 一个音节块。 */
    private static final class Tok {
        List<String> chars;
        /** 这一块的第一个字形在整行里的下标，逐字点亮要按整行的字形序号查时间轴。 */
        int graphemeStart;
        long startMs;
        long endMs;
        /** 未缩放宽度：绘制时外层已经套过 fontScale，推进量必须用未缩放的。 */
        double rawWidth;
        /** 相对本句左上角的位置（已缩放）。 */
        double x;
        double y;
        /** 这一块落在本句的第几个折行上；对唱行要按折行分别右对齐。 */
        int visualRow;
        /** 自己所在折行的总宽（已缩放）。右对齐要按折行各自补偏移，不能整块一起推。 */
        double rowWidth;

        /**
         * 这一块自己的度量。
         *
         * <p>和声块比主歌词小一号，字体、缩放、行距、行高都跟主歌词不同。把度量挂在块上而不是
         * 读渲染器字段，主行与和声行就能在同一个循环里画完，也不用把这些量层层往下传。</p>
         */
        CFontRenderer font;
        double scale;
        double pitch;
        double rawHeight;
        double px;

        /** 来自 {@code ttm:role="x-bg"} 的和声块：小一号、暗一些、弹跳轻一些。 */
        boolean background;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    /** 一句歌词。 */
    private static final class Row {
        LyricLine line;
        boolean right;
        int graphemeTotal;
        /** 文档坐标里的顶边与总高（含翻译与等待圆点预留）。 */
        double y;
        double height;
        /** 正文块的顶边相对 {@link #y} 的偏移：等待圆点占位时正文要往下让。 */
        double textTop;
        /** 正文块（不含翻译与圆点预留）的高度。 */
        double textHeight;
        double width;
        final List<Tok> toks = new ArrayList<Tok>();
        String translation;
        double translationRawWidth;
        double translationY;
        /** 本句之前的空档；{@code gapEndMs > gapStartMs} 时显示等待圆点。 */
        long gapStartMs;
        long gapEndMs;
    }

    private final List<Row> rows = new ArrayList<Row>();

    private LyricLine laidOutCenter;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    private double cameraY;
    private double cameraVelocity;
    private boolean cameraReady;
    private LyricLine anchorLine;
    private double anchorY;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;
    /** 未缩放的行高，与折行步距。绘制时按未缩放量推进，见 {@link #drawToken}。 */
    private double rawFontHeight = 12;
    private double rowPitch = 13;
    private CFontRenderer translationFont = null;
    private double translationScale = 1;
    private double translationPx = 10;

    /** 字形按需异步生成，量不到宽度时排版不可信，见 {@link FullscreenLyricStage#glyphWidth}。 */
    private boolean metricsPending;
    private int metricsRetries;
    private static final int METRICS_MAX_RETRIES = 40;

    protected void onReset() {
        rows.clear();
        laidOutCenter = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        cameraY = 0;
        cameraVelocity = 0;
        cameraReady = false;
        anchorLine = null;
        anchorY = 0;
        metricsPending = false;
        metricsRetries = 0;
    }
    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        boolean viewportChanged = tuningDirty
                || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0;
        boolean relayout = currentLine != laidOutCenter || viewportChanged;
        // 字形没上传完就重排下一帧：这一帧照常绘制会把字形逐步上传，下一帧量出来的宽度才是真的。
        if (!relayout && metricsPending && metricsRetries < METRICS_MAX_RETRIES) {
            relayout = true;
            metricsRetries++;
        } else if (relayout) {
            metricsRetries = 0;
        }
        if (relayout) {
            double previousAnchorY = anchorY;
            LyricLine previousAnchor = anchorLine;
            rebuild(currentLine, positionMs, width, height);
            laidOutCenter = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
            // 窗口滑动会让文档坐标整体平移，靠上一次的锚点把镜头拉回原处，否则每次换行都会跳一下。
            if (previousAnchor != null) {
                double newY = docTopOf(previousAnchor);
                if (!Double.isNaN(newY)) {
                    cameraY += newY - previousAnchorY;
                }
            }
            anchorLine = currentLine;
            anchorY = focusTop(currentLine, positionMs);
        }
        if (rows.isEmpty() || font == null) return;

        double targetY = focusTop(currentLine, positionMs);
        if (!cameraReady) {
            cameraY = targetY;
            cameraVelocity = 0;
            cameraReady = true;
        } else {
            // 显式积分的弹簧，而不是每帧乘一个系数：后者的收敛速度会跟着帧率走。
            double dt = Math.min(frameSeconds, FullscreenLyricStage.MAX_FRAME_SECONDS);
            cameraVelocity += (targetY - cameraY) * SPRING_STIFFNESS * dt;
            cameraVelocity -= cameraVelocity * SPRING_DAMPING * dt;
            cameraY += cameraVelocity * dt;
            if (Math.abs(targetY - cameraY) < .05 && Math.abs(cameraVelocity) < .5) {
                cameraY = targetY;
                cameraVelocity = 0;
            }
        }

        double anchorScreenY = top + height * ANCHOR + (1.0 - opened) * height * .18;
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, 0x6C737C, .58) & 0xFFFFFF;
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        double glowStrength = FullscreenLyricStage.clamp(HudConfig.fullscreenGlowStrength, 0.0, 1.0);
        double popScale = FullscreenLyricStage.clamp(HudConfig.currentWordScale, 0.0, .40);
        double activeBoost = FullscreenLyricStage.clamp(HudConfig.currentLineScale, 1.0, 1.30);

        int activeIndex = activeRowIndex(currentLine, positionMs);
        double inset = Math.max(6.0, width * .06);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double blockY = anchorScreenY + (row.y - cameraY);
            if (blockY + row.height < top - 12 || blockY > top + height + 12) continue;

            int distance = i - activeIndex;
            boolean active = distance == 0;
            double enter = FullscreenLyricStage.clamp01(
                    (positionMs - (row.line.timestamp - 300.0)) / 460.0);
            double eased = FullscreenLyricStage.easeOutCubic(enter);

            double rowAlpha;
            if (active) {
                rowAlpha = 1.0;
            } else if (distance < 0) {
                // 唱过的行整体压暗（行内唱过的字仍是亮色，压的是整行）。
                rowAlpha = .28;
            } else {
                rowAlpha = Math.max(.14, .44 * Math.pow(.88, distance - 1));
            }
            rowAlpha *= alpha;
            if (active) rowAlpha *= .35 + .65 * eased;
            if (rowAlpha <= .012) continue;

            double blockX = row.right ? left + width - inset - row.width : left + inset;
            drawLeadDots(row, blockX, blockY, positionMs, accent, litColor, rowAlpha, alpha);

            double textTop = blockY + row.textTop;
            // 进场时整行从下方 6 px 抬上来，只有当前行做，避免整屏都在动。
            double lift = active ? (1.0 - eased) * 6.0 : 0.0;
            double lineScale = active ? 1.0 + (activeBoost - 1.0) * eased : 1.0;
            double pivotX = row.right ? blockX + row.width : blockX;
            double pivotY = textTop + row.textHeight * .5;

            for (int t = 0; t < row.toks.size(); t++) {
                Tok tok = row.toks.get(t);
                drawToken(row, tok, blockX, textTop - lift, pivotX, pivotY - lift, lineScale,
                        positionMs, active, distance < 0, rowAlpha, litColor, dimColor,
                        effects && active, glowStrength, popScale);
            }

            if (row.translation != null && translationFont != null) {
                double transWidth = row.translationRawWidth * translationScale;
                double transX = row.right ? blockX + row.width - transWidth : blockX;
                double transY = textTop + row.translationY - lift;
                FullscreenLyricStage.pushMatrix();
                try {
                    FullscreenLyricStage.scaleAt(transX, transY, translationScale);
                    translationFont.drawString(row.translation, transX, transY,
                            FullscreenLyricStage.withAlpha(dimColor, rowAlpha * .72));
                } finally {
                    FullscreenLyricStage.popMatrix();
                }
            }
        }
    }
    /**
     * 画一个音节块。
     *
     * <p>整行的缩放是绕「行锚点」做的（左对齐行取左端、对唱行取右端），这里把每一块的中心先按
     * 这个锚点缩放过去，再单独套上自己的弹跳倍率。之所以不用嵌套矩阵：两层缩放叠起来之后，
     * 块内推进量要除以外层倍率才对得上，出一点差错就是整行字距错乱。</p>
     */
    private void drawToken(Row row, Tok tok, double blockX, double textTop,
                           double pivotX, double pivotY, double lineScale,
                           double positionMs, boolean active, boolean passed,
                           double rowAlpha, int litColor, int dimColor,
                           boolean glow, double glowStrength, double popScale) {
        if (tok.chars == null || tok.chars.isEmpty() || tok.font == null) return;

        double centerX = blockX + tok.x + tok.rawWidth * tok.scale * .5;
        double centerY = textTop + tok.y + tok.pitch * .5;
        centerX = pivotX + (centerX - pivotX) * lineScale;
        centerY = pivotY + (centerY - pivotY) * lineScale;

        boolean lightUp = active || passed;
        double own = active
                ? FullscreenLyricStage.clamp01((positionMs - tok.startMs) / tok.durationMs())
                : (passed ? 1.0 : 0.0);

        // 音节弹跳：正在唱的这一块鼓起来又落回去。长音节鼓得更久更高，这是 Apple 的「拖腔」观感。
        // 和声块的弹跳与发光都收一半：Apple 的和声垫在主歌词后面，抢戏就不像和声了。
        boolean longSyllable = tok.durationMs() >= LONG_SYLLABLE_MS;
        double weight = tok.background ? BACKGROUND_MOTION : 1.0;
        double pop = active && own > 0 && own < 1 ? Math.sin(Math.PI * own) : 0.0;
        double scale = tok.scale * lineScale
                * (1.0 + popScale * .70 * weight * pop * (longSyllable ? 1.25 : 1.0));
        centerY -= tok.px * POP_LIFT_RATIO * weight * pop * (longSyllable ? 1.4 : 1.0);

        double layerAlpha = rowAlpha * (tok.background ? BACKGROUND_ALPHA : 1.0);
        double startX = centerX - tok.rawWidth * .5;
        double startY = centerY - tok.rawHeight * .5;

        FullscreenLyricStage.pushMatrix();
        try {
            FullscreenLyricStage.scaleAt(centerX, centerY, scale);
            double cursor = 0;
            for (int i = 0; i < tok.chars.size(); i++) {
                String glyph = tok.chars.get(i);
                double advance = rawGlyphWidth(tok.font, glyph);
                double p = lightUp
                        ? FullscreenLyricStage.graphemeProgress(row.line, tok.graphemeStart + i,
                                row.graphemeTotal, positionMs, CHAR_FADE_MS)
                        : 0.0;
                int color = p <= 0 ? dimColor
                        : FullscreenLyricStage.mixRgb(dimColor, litColor, p) & 0xFFFFFF;
                // 唱过的字停在亮色不回落，整行的压暗交给 rowAlpha —— Apple 就是这么分工的。
                double glyphAlpha = layerAlpha * (lightUp ? .62 + .38 * p : 1.0);
                if (glow && glowStrength > .01 && p > .02) {
                    double amount = (longSyllable
                            ? glowStrength * (.35 + .65 * Math.sin(Math.PI * p))
                            : glowStrength * Math.sin(Math.PI * p)) * weight;
                    if (amount > .02) {
                        FullscreenLyricStage.drawGlow(tok.font, glyph, startX + cursor, startY,
                                litColor, glyphAlpha, amount);
                    }
                }
                tok.font.drawString(glyph, startX + cursor, startY,
                        FullscreenLyricStage.withAlpha(color, glyphAlpha));
                cursor += advance;
            }
        } finally {
            FullscreenLyricStage.popMatrix();
        }
    }

    /**
     * 前奏/间奏的等待圆点。
     *
     * <p>Apple 的标志性一笔：下一句开口之前的空档里，三个点依次充能，临到开口时一起胀大淡出。
     * 空档的两端取自「上一句的末字唱完」到「这一句开口」—— 逆向笔记里 {@code TextLine} 把
     * {@code lastWordEnd} 与 {@code startTime} 分开记，就是为了拿到这一段。</p>
     */
    private void drawLeadDots(Row row, double blockX, double blockY, double positionMs,
                              int accent, int litColor, double rowAlpha, double layerAlpha) {
        if (row.gapEndMs <= row.gapStartMs) return;
        if (positionMs < row.gapStartMs || positionMs >= row.gapEndMs) return;

        double q = FullscreenLyricStage.clamp01(
                (positionMs - row.gapStartMs) / (double) (row.gapEndMs - row.gapStartMs));
        double radius = Math.max(1.6, fontPx * .13);
        double spacing = radius * 3.4;
        double centerY = blockY + fontPx * DOTS_BLOCK_RATIO * .5;
        double firstX = row.right
                ? blockX + row.width - spacing * 2.0 - radius
                : blockX + radius;
        double swell = FullscreenLyricStage.clamp01((q - .82) / .18);

        for (int i = 0; i < 3; i++) {
            double t = FullscreenLyricStage.clamp01((q - i / 3.0) * 3.0);
            double dotAlpha = (.16 + .84 * t) * layerAlpha * (1.0 - .35 * swell);
            if (dotAlpha <= .012) continue;
            double r = radius * (.68 + .32 * t) * (1.0 + .30 * swell + beat * .16);
            FullscreenLyricStage.dot(firstX + spacing * i, centerY, r,
                    t > .5 ? litColor : accent, dotAlpha);
        }
    }

    // ==================== 镜头与查找 ====================

    /** 这一句正文顶边在文档坐标里的位置；不在这批行里时返回 NaN。 */
    private double docTopOf(LyricLine line) {
        Row row = rowOf(line);
        return row == null ? Double.NaN : row.y + row.textTop;
    }

    /** 镜头该把哪一句的正文顶边贴到锚点上。 */
    private double focusTop(LyricLine currentLine, double positionMs) {
        int index = activeRowIndex(currentLine, positionMs);
        if (index < 0 || index >= rows.size()) return rows.isEmpty() ? 0 : rows.get(0).y;
        Row row = rows.get(index);
        return row.y + row.textTop;
    }

    /**
     * 当前是哪一行。
     *
     * <p>{@code currentLine} 可能是被 {@link #window} 过滤掉的空行，那时按播放时间退到最后一条
     * 已经开始的行，而不是一律退回第一条 —— 后者会让画面在间奏时甩回窗口开头。</p>
     */
    private int activeRowIndex(LyricLine currentLine, double positionMs) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).line == currentLine) return i;
        }
        int best = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).line.timestamp <= positionMs) best = i;
            else break;
        }
        return best;
    }

    private Row rowOf(LyricLine line) {
        if (line == null) return null;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).line == line) return rows.get(i);
        }
        return null;
    }

    // ==================== 布局 ====================

    /**
     * 一个字形的未缩放宽度。
     *
     * <p>推进量一律取未缩放值：{@link #drawToken} 外层已经套过 {@code fontScale}。字形是按需异步
     * 上传的，没上传时 {@link FullscreenLyricStage#glyphWidth} 会按字号给一个估值，配合
     * {@link #metricsPending} 的逐帧重排撑过这一两帧。</p>
     */
    private static double rawGlyphWidth(CFontRenderer glyphFont, String grapheme) {
        if (glyphFont == null) return 0;
        return FullscreenLyricStage.glyphWidth(glyphFont, grapheme, 1.0, glyphFont.sizePx);
    }

    /** 翻译行的未缩放宽度，逐字形累加（估算兜底只看首字符，整串扔进去会算错）。 */
    private double rawTranslationWidth(String text) {
        if (translationFont == null || text == null) return 0;
        double sum = 0;
        List<String> chars = FullscreenLyricStage.graphemes(text);
        for (int i = 0; i < chars.size(); i++) {
            sum += rawGlyphWidth(translationFont, chars.get(i));
        }
        return sum;
    }

    /** 逐字轴：每一块的起始字形下标 + 是不是和声块。 */
    private static final class Axis {
        int[] starts;
        boolean[] background;
    }

    /**
     * 照词表算出每一块的真实字形下标与和声标记。
     *
     * <p>不能直接累加各块的字形数：{@link FullscreenLyricStage#tokensOf} 会跳过「纯空白」的逐字
     * 单元（有些 TTML 把词间空格单独记成一个 word），而
     * {@link FullscreenLyricStage#wordAtGrapheme} 是照整份词表数的 —— 两边错开一位，逐字点亮就会
     * 整行偏移。这里走一遍和 {@code tokensOf} 完全相同的取舍（跳过空词与纯空白词），所以两个序列
     * 一一对应；对不上时返回 {@code null}，调用方退回累加下标并把整行当成没有和声。</p>
     */
    private static Axis axisOf(LyricLine line, int tokenCount) {
        if (line == null || !line.hasTimedWords()) return null;
        List<Integer> starts = new ArrayList<Integer>();
        List<Boolean> background = new ArrayList<Boolean>();
        int cursor = 0;
        for (int i = 0; i < line.words.size(); i++) {
            LyricLine.Word word = line.words.get(i);
            if (word == null || word.word == null) continue;
            int count = FullscreenLyricStage.graphemeCount(word.word);
            if (count <= 0) continue;
            if (!word.word.trim().isEmpty()) {
                starts.add(Integer.valueOf(cursor));
                background.add(Boolean.valueOf(word.background));
            }
            cursor += count;
        }
        if (starts.size() != tokenCount) return null;
        Axis axis = new Axis();
        axis.starts = new int[starts.size()];
        axis.background = new boolean[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            axis.starts[i] = starts.get(i).intValue();
            axis.background[i] = background.get(i).booleanValue();
        }
        return axis;
    }

    /**
     * 重排这一屏的行。
     *
     * <p>字号从「舞台基准 × .92」起，排不开就整体降一档重排 —— 与其它样式一致：宁可小一号，也不
     * 让一句折成五六行或者铺出视口。</p>
     */
    private void rebuild(LyricLine center, double positionMs, double viewWidth, double viewHeight) {
        rows.clear();
        font = null;
        metricsPending = false;
        List<LyricLine> lines = window(center, positionMs, WINDOW_BEFORE, WINDOW_AFTER);
        if (lines.isEmpty()) return;

        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * .92;
        for (int attempt = 0; attempt < 7; attempt++) {
            if (layout(lines, targetPx, viewWidth, viewHeight)) return;
            targetPx *= .87;
            if (targetPx < 9) break;
        }
        layout(lines, targetPx, viewWidth, viewHeight);
    }
    /**
     * 把这一批行摆进文档坐标。
     *
     * @return 这一档字号排不排得开：折行数超过 {@link #MAX_VISUAL_ROWS}、某一折行超出正文宽度、
     *         或单句高过视口的 {@link #MAX_LINE_HEIGHT_RATIO} 都算排不开
     */
    private boolean layout(List<LyricLine> lines, double targetPx,
                           double viewWidth, double viewHeight) {
        rows.clear();
        metricsPending = false;

        Metrics main = Metrics.of(targetPx, 1.08);
        Metrics background = Metrics.of(Math.max(8.0, targetPx * BACKGROUND_SCALE), 1.06);
        font = main.font;
        fontScale = main.scale;
        fontPx = main.px;
        rawFontHeight = main.rawHeight;
        rowPitch = main.pitch;
        // 这一档字号还没生成过任何字形时，行高被兜了底，但宽度仍不可信 —— 下一帧还要重排一次。
        if (main.pending() || background.pending()) metricsPending = true;

        translationPx = Math.max(8.0, targetPx * .52);
        translationFont = FullscreenLyricStage.fontAtMost(translationPx);
        translationScale = FullscreenLyricStage.scaleFor(translationFont, translationPx);

        // 与 draw 里同一条式子，两处必须一致，否则右对齐的对唱行会和它的等待圆点错开。
        double inset = Math.max(6.0, viewWidth * .06);
        double contentWidth = Math.max(fontPx * 4.0, viewWidth - inset * 2.0);
        double gapBetween = fontPx * .55;
        double heightLimit = viewHeight * MAX_LINE_HEIGHT_RATIO;

        boolean fits = true;
        double y = 0;
        long previousEnd = Long.MIN_VALUE;

        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            String text = line.lyric == null ? "" : line.lyric.trim();
            if (text.isEmpty()) continue;

            Row row = new Row();
            row.line = line;
            row.right = line.duetSlot == 1;
            row.graphemeTotal = Math.max(1, FullscreenLyricStage.graphemeCount(line.lyric));

            int visualRows = layoutTokens(row, line, contentWidth, main, background);
            if (row.toks.isEmpty()) continue;

            String translation = secondaryText(line);
            if (translation != null && translationFont != null) {
                if (!FullscreenLyricStage.glyphsReady(translationFont, translation)) {
                    metricsPending = true;
                }
                row.translation = translation;
                row.translationRawWidth = rawTranslationWidth(translation);
                row.translationY = row.textHeight + fontPx * .12;
            }

            applyGap(row, line, previousEnd);
            row.y = y;
            row.height = row.textTop + row.textHeight;
            if (row.translation != null) {
                row.height += fontPx * .12
                        + FullscreenLyricStage.lineHeightOf(translationFont, translationScale);
            }
            y += row.height + gapBetween;
            previousEnd = lastVocalEnd(line);
            rows.add(row);

            if (visualRows > MAX_VISUAL_ROWS) fits = false;
            if (row.width > contentWidth + .5) fits = false;
            if (row.height > heightLimit) fits = false;
        }
        if (rows.isEmpty()) return true;
        return fits;
    }
    /**
     * 把一句切块、折行、按对唱侧对齐，返回折了几行。
     *
     * <p>对唱行（{@code duetSlot == 1}）整块贴视口右侧，块内每一条折行还要各自右对齐 —— 只把
     * 整块右对齐的话，短的那条折行会孤零零地留在块的左边。</p>
     */
    private int layoutTokens(Row row, LyricLine line, double contentWidth,
                             Metrics main, Metrics background) {
        List<FullscreenLyricStage.Token> tokens = FullscreenLyricStage.tokensOf(line, 6);
        Axis axis = axisOf(line, tokens.size());

        List<Tok> mainRun = new ArrayList<Tok>();
        List<Tok> bgRun = new ArrayList<Tok>();
        int graphemeCursor = 0;

        for (int t = 0; t < tokens.size(); t++) {
            FullscreenLyricStage.Token token = tokens.get(t);
            boolean isBackground = axis != null && axis.background[t];
            Metrics metrics = isBackground ? background : main;
            Tok tok = new Tok();
            tok.chars = FullscreenLyricStage.graphemes(token.text);
            if (tok.chars.isEmpty()) continue;
            tok.graphemeStart = axis != null ? axis.starts[t] : graphemeCursor;
            graphemeCursor += tok.chars.size();
            tok.startMs = token.startMs;
            tok.endMs = token.endMs;
            tok.background = isBackground;
            measure(tok, metrics);
            // 按块用它自己那一档字体判字形是否齐备：和声块用小号字，拿主歌词的字体去问会答错。
            if (!FullscreenLyricStage.glyphsReady(metrics.font, token.text)) metricsPending = true;
            if (isBackground) bgRun.add(tok);
            else mainRun.add(tok);
        }

        // 整句只有和声（只含 x-bg 的 <p>，少见但存在）时把它提上来当主歌词，
        // 否则这一句会因为主歌词为空被整条丢掉。
        if (mainRun.isEmpty() && !bgRun.isEmpty()) {
            for (int i = 0; i < bgRun.size(); i++) {
                Tok tok = bgRun.get(i);
                tok.background = false;
                measure(tok, main);
            }
            mainRun.addAll(bgRun);
            bgRun.clear();
        }

        int mainRows = fold(mainRun, contentWidth, 0);
        double widest = widestOf(mainRun);
        double mainHeight = mainRows * main.pitch;
        double blockHeight = mainHeight;
        if (!bgRun.isEmpty()) {
            double bgTop = mainHeight + main.px * BACKGROUND_GAP_RATIO;
            int bgRows = fold(bgRun, contentWidth, bgTop);
            blockHeight = bgTop + bgRows * background.pitch;
            widest = Math.max(widest, widestOf(bgRun));
        }
        row.width = widest;
        row.textHeight = blockHeight;

        // 对唱行整块贴视口右侧，块内每一条折行还要各自右对齐 —— 只把整块右对齐的话，
        // 短的那条折行会孤零零地留在块的左边。
        if (row.right) {
            shiftRight(mainRun, widest);
            shiftRight(bgRun, widest);
        }
        row.toks.addAll(mainRun);
        row.toks.addAll(bgRun);
        return mainRows;
    }

    /** 把一档度量装到块上，并按这一档字体量出未缩放宽度。 */
    private static void measure(Tok tok, Metrics metrics) {
        tok.font = metrics.font;
        tok.scale = metrics.scale;
        tok.pitch = metrics.pitch;
        tok.rawHeight = metrics.rawHeight;
        tok.px = metrics.px;
        tok.rawWidth = 0;
        for (int c = 0; c < tok.chars.size(); c++) {
            tok.rawWidth += rawGlyphWidth(metrics.font, tok.chars.get(c));
        }
    }

    /**
     * 这一句之前的空档。
     *
     * <p>取「上一句末字唱完」到「这一句开口」：逆向笔记里 {@code TextLine} 把
     * {@code firstWordStart}/{@code lastWordEnd} 与行窗口的 {@code startTime}/{@code endTime}
     * 分开记，就是为了拿到这一段。用行窗口会算错 —— {@link LyricParser} 解析 TTML 时把行窗口
     * 顺延到了和声唱完（和声经常唱进下一行），拿行窗口相减，带和声的歌几乎永远没有空档。</p>
     *
     * <p>窗口里的第一句还要单独判一次前奏：它没有上一句，但整首歌开头那段空白同样该有圆点。</p>
     */
    private void applyGap(Row row, LyricLine line, long previousEnd) {
        long from;
        long to = firstVocalStart(line);
        if (previousEnd != Long.MIN_VALUE) {
            from = previousEnd;
        } else if (indexOf(line) == 0) {
            from = 0L;
        } else {
            return;
        }
        if (to - from < DOTS_MIN_GAP_MS) return;
        row.gapStartMs = from;
        row.gapEndMs = to;
        row.textTop = fontPx * DOTS_BLOCK_RATIO;
    }

    // ==================== 折行与度量 ====================

    /** 一档字体的度量：字体、缩放、折行步距、未缩放行高、目标像素。 */
    private static final class Metrics {
        CFontRenderer font;
        double scale;
        double pitch;
        double rawHeight;
        double px;

        static Metrics of(double targetPx, double lineFactor) {
            Metrics metrics = new Metrics();
            metrics.font = FullscreenLyricStage.fontAtMost(targetPx);
            metrics.scale = FullscreenLyricStage.scaleFor(metrics.font, targetPx);
            metrics.px = targetPx;
            metrics.rawHeight = FullscreenLyricStage.lineHeightOf(metrics.font, 1.0);
            metrics.pitch = metrics.rawHeight * metrics.scale * lineFactor;
            return metrics;
        }

        /** 这一档字号还没生成过任何字形：行高已经兜了底，但量出来的宽度不可信。 */
        boolean pending() {
            return font == null || font.getFontHeight() < font.sizePx * .4;
        }
    }

    /**
     * 折行：按各块自己的度量往右推，超宽换行。返回折了几行。
     *
     * <p>每一块所在折行的总宽要等这一路的块全摆完才知道，所以回填一遍记在块上 —— 右对齐要按
     * 折行各自补偏移，主歌词与和声两路的折行宽度又互不相同。</p>
     */
    private static int fold(List<Tok> run, double maxWidth, double topOffset) {
        if (run.isEmpty()) return 0;
        int visualRows = 1;
        double cursorX = 0;
        for (int i = 0; i < run.size(); i++) {
            Tok tok = run.get(i);
            double scaled = tok.rawWidth * tok.scale;
            if (cursorX > 0 && cursorX + scaled > maxWidth) {
                visualRows++;
                cursorX = 0;
            }
            tok.visualRow = visualRows - 1;
            tok.x = cursorX;
            tok.y = topOffset + tok.visualRow * tok.pitch;
            cursorX += scaled;
        }
        double[] widths = new double[visualRows];
        for (int i = 0; i < run.size(); i++) {
            Tok tok = run.get(i);
            double end = tok.x + tok.rawWidth * tok.scale;
            if (end > widths[tok.visualRow]) widths[tok.visualRow] = end;
        }
        for (int i = 0; i < run.size(); i++) {
            Tok tok = run.get(i);
            tok.rowWidth = widths[tok.visualRow];
        }
        return visualRows;
    }

    private static double widestOf(List<Tok> run) {
        double widest = 0;
        for (int i = 0; i < run.size(); i++) {
            if (run.get(i).rowWidth > widest) widest = run.get(i).rowWidth;
        }
        return widest;
    }

    /**
     * 右对齐：把每一条折行推到块的右边缘。
     *
     * <p>推完之后每条折行的右端都落在 {@code blockWidth} 上，所以 {@code rowWidth} 一并更新 ——
     * 留着旧值的话，「这一块有没有超出自己那条折行」就再也判不准了。</p>
     */
    private static void shiftRight(List<Tok> run, double blockWidth) {
        for (int i = 0; i < run.size(); i++) {
            Tok tok = run.get(i);
            tok.x += blockWidth - tok.rowWidth;
            tok.rowWidth = blockWidth;
        }
    }

    // ==================== 时间轴与副歌词 ====================

    /**
     * 这一句「真正开口」的时刻。
     *
     * <p>对应逆向笔记 §9 的 {@code firstWordStart}：有逐字轴时取第一个非空音节的起点，没有时退回
     * 行窗口起点（笔记里的 {@code else} 分支就是这么写的）。和声不算开口 —— Apple 的
     * {@code filteredWords} 只包含主人声，和声在 {@code backgroundVocals} 那一路。</p>
     */
    private static long firstVocalStart(LyricLine line) {
        if (line == null) return 0L;
        for (int i = 0; i < line.words.size(); i++) {
            LyricLine.Word word = line.words.get(i);
            if (word == null || word.word == null || word.background) continue;
            if (word.word.trim().isEmpty()) continue;
            return Math.max(line.timestamp, word.timestamp);
        }
        return line.timestamp;
    }

    /**
     * 这一句最后一个音节唱完的时刻；没有逐字轴时退回行窗口末端。
     *
     * <p>这里<b>连和声一起算</b>，与 {@link #firstVocalStart} 只认主人声不同：笔记里的
     * {@code lastWordEnd} 取自 {@code filteredWords}（不含和声），但那是给行状态用的，照搬到
     * 等待圆点上就会在和声还在唱的时候冒出圆点。圆点的语义是「这段没人在唱」，所以取全部音节。</p>
     */
    private static long lastVocalEnd(LyricLine line) {
        if (line == null) return 0L;
        long end = Long.MIN_VALUE;
        for (int i = 0; i < line.words.size(); i++) {
            LyricLine.Word word = line.words.get(i);
            if (word == null || word.word == null) continue;
            if (word.word.trim().isEmpty()) continue;
            end = Math.max(end, word.timestamp + Math.max(0L, word.duration));
        }
        return end == Long.MIN_VALUE ? FullscreenLyricStage.lineEndMs(line) : end;
    }

    /**
     * 副歌词行的内容：翻译或罗马音，跟随播放器既有的「显示翻译 / 显示罗马音」开关。
     *
     * <p>直接读 {@code line.translationText} 会绕过用户的开关，也拿不到罗马音；项目里判定这件事的
     * 地方只有 {@link CloudMusic#getSecondaryLyrics}（经典滚动歌词走的也是它），这里沿用同一条
     * 判定。Apple Music 的翻译行与发音行同样跟着设置走，行为正好一致。</p>
     */
    private static String secondaryText(LyricLine line) {
        if (line == null) return null;
        // 先看这一行到底有没有副歌词：绝大多数歌一行都没有，直接短路掉，省下每次重排的设置查询。
        boolean any = line.translationText != null && !line.translationText.trim().isEmpty();
        if (!any) any = line.romanizationText != null && !line.romanizationText.trim().isEmpty();
        if (!any) return null;
        if (!CloudMusic.hasSecondaryLyrics()) return null;
        String text = CloudMusic.getSecondaryLyrics(line);
        if (text == null) return null;
        text = text.trim();
        return text.isEmpty() ? null : text;
    }
}