package com.muoniumplayer.core.screens.ncm;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.shader.Shaders;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 全屏歌词各套「文字 PV」样式共用的舞台层。
 *
 * <p>这些样式移植自 folia-major 播放器（TypeScript + framer-motion）。那边每套样式都是一个 React
 * 组件，靠 DOM 布局与 CSS 变换实现；这里没有布局引擎，所有位置都要自己算，所以把公共部分收在这一个
 * 类里：确定性随机、缓动、字号求解、字形切分、带变换与发光的文本绘制、裁剪。</p>
 *
 * <h3>为什么随机数要「确定性」</h3>
 * <p>参考实现里每套样式的散布、错落、分行都掺了随机，但它是<b>以行为种子</b>算出来的伪随机
 * （{@code fract(sin(seed + offset) * 10000)}），同一行每次渲染必然得到同一个值。这里必须照抄这条
 * 公式而不是用 {@link java.util.Random}：渲染每帧都会调用，一旦每帧取到不同的值，字就会原地乱抖。</p>
 *
 * <h3>响应式字号</h3>
 * <p>参考实现用 {@code clamp(minRem, vw, maxRem)} 这类 CSS 单位。这里 {@link #fontAtMost} 先在字体
 * 梯子上挑一个不超过目标像素的字号，再由调用方用 {@link #scaleFor} 拿到补足的缩放系数，绘制时套一次
 * GL 缩放。这样任意目标像素都能精确命中，字形也不会因为整体缩放而糊掉太多。</p>
 */
final class FullscreenLyricStage {

    private FullscreenLyricStage() {
    }

    /** 一帧最多推进多少秒。掉帧或切出去再切回来时不让动画一次跳完。 */
    static final double MAX_FRAME_SECONDS = 0.05;

    /** 字体梯子，从大到小。除此之外的目标像素靠 GL 缩放补足。 */
    private static CFontRenderer[] ladder() {
        return new CFontRenderer[] {
                FontManager.pf65bold, FontManager.pf50bold, FontManager.pf40bold,
                FontManager.pf34bold, FontManager.pf28bold, FontManager.pf25bold,
                FontManager.pf20bold, FontManager.pf18bold, FontManager.pf16bold,
                FontManager.pf14bold, FontManager.pf12bold, FontManager.pf10bold
        };
    }

    /** 目标像素对应的字体：梯子上第一个不超过目标的字号，最小回落到 10px。 */
    static CFontRenderer fontAtMost(double px) {
        CFontRenderer[] fonts = ladder();
        for (int i = 0; i < fonts.length; i++) {
            CFontRenderer font = fonts[i];
            if (font != null && font.sizePx <= px + 0.01f) return font;
        }
        for (int i = fonts.length - 1; i >= 0; i--) {
            if (fonts[i] != null) return fonts[i];
        }
        return FontManager.pf14bold;
    }

    /** 把 {@link #fontAtMost} 选出的字号补到目标像素所需的缩放。 */
    static double scaleFor(CFontRenderer font, double px) {
        if (font == null || font.sizePx <= 0.01f) return 1.0;
        return Math.max(0.35, px / font.sizePx);
    }

    // ==================== 可信的文字度量 ====================

    /**
     * 一个字形按当前倍率占的宽度；字形还没上传时给一个估算值而不是 0。
     *
     * <p>字形是<b>按需异步生成</b>的：{@code CFontRenderer.getCharWidth} 对还没上传的字形返回 0，
     * 所以「刚换到这一行」的那一帧量出来的宽度会偏小甚至是 0。排版一旦按这个宽度定下来，字就会
     * 冲出气泡；右对齐的那一侧更糟 —— 起点算成了气泡右边缘，整句直接铺到视口外被裁掉。估算值只
     * 用于这一两帧的过渡，配合调用方的「字形没齐就重排」一起用。</p>
     */
    static double glyphWidth(CFontRenderer font, String grapheme, double scale, double targetPx) {
        if (font == null || grapheme == null || grapheme.isEmpty()) return 0;
        double measured = font.getStringWidthD(grapheme) * scale;
        if (measured > .01) return measured;
        return Math.max(1.0, targetPx) * estimateEmWidth(grapheme);
    }

    /** 字形没上传时按「全角 1 em / 半角 .55 em」估宽，够撑住一两帧的过渡。 */
    private static double estimateEmWidth(String grapheme) {
        int cp = grapheme.codePointAt(0);
        if (cp == ' ' || cp == '\u3000' || Character.isWhitespace(cp)) return .30;
        boolean wide = (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || cp > 0xFFFF;
        return wide ? 1.0 : .55;
    }

    /** 这一段文字的字形是否都已上传（也就是量出来的宽度可不可信）。 */
    static boolean glyphsReady(CFontRenderer font, String text) {
        if (font == null) return false;
        if (text == null || text.isEmpty()) return true;
        return font.areGlyphsLoaded(text);
    }

    /**
     * 一行文字占的高度。
     *
     * <p>{@code CFontRenderer.fontHeight} 在这个字号生成出第一个字形之前是负一，于是
     * {@code getFontHeight()} 会算出负数（{@code (fontHeight - 8.5) * .5}）。字体梯子上有几档只被歌词页用到，
     * 第一次用到时正好撞上这个状态，行高变负会让整块文字叠在一起、气泡塌成一条 —— 所以这里给一个
     * 「字号本身」的下限兜底。</p>
     */
    static double lineHeightOf(CFontRenderer font, double scale) {
        if (font == null) return 12.0;
        double height = font.getFontHeight();
        if (height < font.sizePx * .4) height = font.sizePx;
        return height * scale;
    }

    // ==================== 确定性随机 ====================

    /**
     * folia 通用确定性随机：{@code fract(sin(seed + offset) * 10000)}。
     *
     * <p>种子来自行的时间戳与文本，所以同一行每帧取到同一串值，布局稳定；不同行之间又足够分散。</p>
     */
    static double random(long seed, int offset) {
        double value = Math.sin((double) seed + offset) * 10000.0;
        return value - Math.floor(value);
    }

    /** 「倾诉」用的那一支：种子先乘 1000，分布比上面更散。参考实现同样是两支并存。 */
    static double randomWide(long seed, int offset) {
        double value = Math.sin((double) seed * 1000.0 + offset) * 10000.0;
        return value - Math.floor(value);
    }

    static long seedOf(LyricLine line) {
        if (line == null) return 0L;
        String text = line.lyric == null ? "" : line.lyric;
        return line.timestamp * 31L + text.hashCode();
    }

    // ==================== 缓动 ====================

    static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }

    static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    static double smoothstep(double t) {
        double x = clamp01(t);
        return x * x * (3.0 - 2.0 * x);
    }

    static double easeOutCubic(double t) {
        double u = 1.0 - clamp01(t);
        return 1.0 - u * u * u;
    }

    static double easeInCubic(double t) {
        double x = clamp01(t);
        return x * x * x;
    }

    static double easeInOutCubic(double t) {
        double x = clamp01(t);
        if (x < .5) return 4.0 * x * x * x;
        double u = -2.0 * x + 2.0;
        return 1.0 - u * u * u * .5;
    }

    static double easeOutBack(double t) {
        double x = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double u = x - 1.0;
        return 1.0 + c3 * u * u * u + c1 * u * u;
    }

    /** 参考实现里 framer-motion 的 {@code [0.16, 1, 0.3, 1]} 近似，用来做那种「窜出去再稳住」的进场。 */
    static double easeOutExpo(double t) {
        double x = clamp01(t);
        if (x >= 1) return 1;
        return 1.0 - Math.pow(2.0, -10.0 * x);
    }

    // ==================== 文本切分 ====================

    /**
     * 按字形切分（不是按 char）。
     *
     * <p>逐字动画必须以字形为单位：直接按 {@code char} 切会把代理对和组合符号劈开，画出来是乱码。</p>
     */
    static List<String> graphemes(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) return out;
        int i = 0;
        int length = text.length();
        while (i < length) {
            int codePoint = text.codePointAt(i);
            int end = i + Character.charCount(codePoint);
            while (end < length) {
                int next = text.codePointAt(end);
                int type = Character.getType(next);
                if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK) {
                    end += Character.charCount(next);
                } else {
                    break;
                }
            }
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }

    static int graphemeCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.codePointCount(0, text.length());
    }

    // ==================== 响应式字号 ====================

    /**
     * 视口对应的基准字号（像素）。
     *
     * <p>参考实现用 CSS 的 {@code clamp(rem, vw, rem)}，那边的视口是整个桌面窗口；这里的歌词区只是
     * 播放器的右半边，而播放器又跟着 GUI 缩放走，所以只能按<b>视口比例</b>算，再夹一对绝对下限/上限
     * 防止极端尺寸下字大到只剩两个或小到看不见。宽高都参与是因为窄而高、宽而扁两种形状都存在。</p>
     *
     * <p>最后乘一次 {@link HudConfig#lyricFontScale}：倍率作用在<b>夹取之后</b>，否则往大调会被
     * 48px 的上限吃掉、看起来像滑块失效。倍率之后再夹一次绝对边界，各样式自己还有「排不开就降档」
     * 的兜底，所以调到最大也不会叠字。</p>
     */
    static double baseFontPx(double viewWidth, double viewHeight) {
        double byWidth = viewWidth * .085;
        double byHeight = viewHeight * .115;
        double base = clamp(Math.min(byWidth, byHeight), 12.0, 48.0);
        double scale = clamp(HudConfig.lyricFontScale, .6, 1.8);
        // 绝对上限取 88 而不是 86：48 * 1.8 = 86.4，卡在 86 的话最大视口下最大倍率会被悄悄截掉一截。
        return clamp(base * scale, 8.0, 88.0);
    }

    // ==================== 分词 ====================

    /** 一块带时间轴的文字。 */
    static final class Token {
        String text = "";
        long startMs;
        long endMs;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    /**
     * 把一行切成若干块。
     *
     * <p>有逐字时间轴时直接用逐字单元 —— 那是「文字 PV」逐块点亮的来源。没有时按空白切，
     * 并把过长的块（中日韩歌词整行没有空格）再等分成不超过 {@code maxGraphemes} 个字形的小块，
     * 否则折行算法面对一个比舞台还宽的单块无处可折。</p>
     */
    static List<Token> tokensOf(LyricLine line, int maxGraphemes) {
        List<Token> out = new ArrayList<Token>();
        if (line == null) return out;
        int limit = Math.max(1, maxGraphemes);
        if (line.hasTimedWords()) {
            for (int i = 0; i < line.words.size(); i++) {
                LyricLine.Word word = line.words.get(i);
                if (word == null || word.word == null) continue;
                String text = word.word;
                if (text.trim().isEmpty()) continue;
                Token token = new Token();
                token.text = text;
                token.startMs = word.timestamp;
                token.endMs = word.duration > 0L ? word.timestamp + word.duration : word.timestamp + 320L;
                out.add(token);
            }
            if (!out.isEmpty()) return out;
        }

        String text = line.lyric == null ? "" : line.lyric.trim();
        if (text.isEmpty()) return out;
        long span = Math.max(1L, line.duration > 0L ? line.duration : 3000L);
        String[] parts = text.split("\\s+");
        List<String> pieces = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            List<String> chars = graphemes(parts[i]);
            if (chars.size() <= limit) {
                pieces.add(parts[i]);
                continue;
            }
            int chunks = (int) Math.ceil(chars.size() / (double) limit);
            int per = (int) Math.ceil(chars.size() / (double) chunks);
            StringBuilder builder = new StringBuilder();
            for (int c = 0; c < chars.size(); c++) {
                builder.append(chars.get(c));
                if (builder.length() > 0 && (c + 1) % per == 0) {
                    pieces.add(builder.toString());
                    builder.setLength(0);
                }
            }
            if (builder.length() > 0) pieces.add(builder.toString());
        }
        if (pieces.isEmpty()) return out;

        /*
         * 没有逐字时间轴时，每一块都用<b>整行</b>的时间区间。
         *
         * 之前是按字形数把行时长分摊给各块（参考实现的做法），但那等于凭一个估算出来的行时长
         * 编造逐字时间：块与块之间的入场、点亮、发光会依次错开，而它们的时刻全是猜的，唱到行尾
         * 已经差出一大截。整行共用一个区间之后，这一行的所有块同时入场、同时点亮，只剩「哪一行」
         * 这一个真实信息，不再假装知道「哪一个字」。切块本身保留，折行仍然需要它。
         */
        for (int i = 0; i < pieces.size(); i++) {
            Token token = new Token();
            token.text = pieces.get(i);
            token.startMs = line.timestamp;
            token.endMs = line.timestamp + span;
            out.add(token);
        }
        return out;
    }

    // ==================== 行状态 ====================

    static final int STATE_WAITING = 0;
    static final int STATE_ACTIVE = 1;
    static final int STATE_PASSED = 2;

    /** 参考实现的 {@code wordLookahead}：提前多少「行时长比例」开始点亮，让点亮不落后于听感。 */
    static final double WORD_LOOKAHEAD = .15;

    static long lineEndMs(LyricLine line) {
        if (line == null) return 0L;
        long duration = line.duration > 0L ? line.duration : 2400L;
        return line.timestamp + duration;
    }

    static int lineState(LyricLine line, double positionMs) {
        if (line == null) return STATE_WAITING;
        long duration = line.duration > 0L ? line.duration : 2400L;
        double lead = duration * WORD_LOOKAHEAD;
        if (positionMs < line.timestamp - lead) return STATE_WAITING;
        if (positionMs > line.timestamp + duration) return STATE_PASSED;
        return STATE_ACTIVE;
    }

    /** 这一行自己的演唱进度 0..1，没有逐字信息时按行时长线性推。 */
    static double lineProgress(LyricLine line, double positionMs) {
        if (line == null) return 0;
        long duration = line.duration > 0L ? line.duration : 2400L;
        return clamp01((positionMs - line.timestamp) / (double) duration);
    }

    /**
     * 一个字形的演唱进度。
     *
     * <p>有逐字时间轴就用真实时间轴，这是「文字 PV」观感的来源。</p>
     *
     * <p><b>没有逐字时间轴时整行一起亮</b>，不再按字形均分行时长。均分是一种伪装：普通 LRC 的
     * {@code duration} 只是「到下一行为止」的估算，把它切成 n 份去推第 k 个字的亮起时刻，误差会
     * 沿着行累积，唱到行尾时字与人声能差出半句 —— 表现就是「没有逐字歌词的歌，歌词不同步」。
     * 与其伪装成逐字，不如老老实实整行呈现。</p>
     */
    static double graphemeProgress(LyricLine line, int graphemeIndex, int graphemeTotal,
                                  double positionMs, double fadeMs) {
        if (line == null || graphemeTotal <= 0) return 0;
        LyricLine.Word word = wordAtGrapheme(line, graphemeIndex);
        if (word == null || word.duration <= 0L) {
            // 整行同一个进度：所有字形同时亮起，只保留一次整行淡入。
            return clamp01((positionMs - line.timestamp) / Math.max(40.0, fadeMs));
        }
        double window = Math.max(40.0, Math.min((double) word.duration, fadeMs));
        return clamp01((positionMs - word.timestamp) / window);
    }

    /** 第 n 个字形落在哪个逐字单元里。没有逐字信息时返回 {@code null}。 */
    static LyricLine.Word wordAtGrapheme(LyricLine line, int graphemeIndex) {
        if (line == null || line.words == null || line.words.isEmpty()) return null;
        int cursor = 0;
        for (int i = 0; i < line.words.size(); i++) {
            LyricLine.Word word = line.words.get(i);
            if (word == null || word.word == null) continue;
            int count = graphemeCount(word.word);
            if (count <= 0) continue;
            if (graphemeIndex < cursor + count) return word;
            cursor += count;
        }
        return null;
    }

    // ==================== 裁剪 ====================

    static void beginStageClip(final double left, final double top,
                               final double width, final double height) {
        StencilClipManager.beginClip(new Runnable() {
            public void run() {
                Rect.draw(left, top, width, height, -1);
            }
        });
        ScissorClipManager.begin(left, top, width, height);
    }

    static void endStageClip() {
        ScissorClipManager.end();
        StencilClipManager.restoreActiveClip();
        StencilClipManager.endClip();
    }

    // ==================== 变换 ====================

    static void pushMatrix() {
        SharedConstants.api.getGLStateManager().pushMatrix();
    }

    static void popMatrix() {
        SharedConstants.api.getGLStateManager().popMatrix();
    }

    static void translate(double x, double y) {
        SharedConstants.api.getGLStateManager().translate(x, y, 0);
    }

    static void scale(double scaleX, double scaleY) {
        SharedConstants.api.getGLStateManager().scale(scaleX, scaleY, 1);
    }

    static void scaleAt(double pivotX, double pivotY, double scale) {
        scaleAt(pivotX, pivotY, scale, scale);
    }

    static void scaleAt(double pivotX, double pivotY, double scaleX, double scaleY) {
        SharedConstants.api.getGLStateManager().translate(pivotX, pivotY, 0);
        SharedConstants.api.getGLStateManager().scale(scaleX, scaleY, 1);
        SharedConstants.api.getGLStateManager().translate(-pivotX, -pivotY, 0);
    }

    static void rotateAt(double pivotX, double pivotY, double degrees) {
        SharedConstants.api.getGLStateManager().translate(pivotX, pivotY, 0);
        SharedConstants.api.getGLStateManager().rotate((float) degrees, 0, 0, 1);
        SharedConstants.api.getGLStateManager().translate(-pivotX, -pivotY, 0);
    }

    private static final FloatBuffer SHEAR_BUFFER = BufferUtils.createFloatBuffer(16);

    /**
     * 绕给定点做水平剪切，用来模拟斜体。
     *
     * <p>1.8.9 的字体渲染没有斜体字形，参考实现里「倾诉」那套的斜体是 CSS {@code font-style: italic}。
     * 这里改成剪切矩阵：{@code x' = x - shear * (y - pivotY)}，视觉上就是字向右上倾。</p>
     */
    static void shearAt(double pivotX, double pivotY, double shear) {
        if (Math.abs(shear) < 1.0e-4) return;
        SHEAR_BUFFER.clear();
        // 列主序：m[col * 4 + row]
        SHEAR_BUFFER.put(0, 1f).put(1, 0f).put(2, 0f).put(3, 0f);
        SHEAR_BUFFER.put(4, (float) -shear).put(5, 1f).put(6, 0f).put(7, 0f);
        SHEAR_BUFFER.put(8, 0f).put(9, 0f).put(10, 1f).put(11, 0f);
        SHEAR_BUFFER.put(12, (float) (shear * pivotY)).put(13, 0f).put(14, 0f).put(15, 1f);
        SHEAR_BUFFER.position(0);
        GL11.glMultMatrix(SHEAR_BUFFER);
    }

    // ==================== 颜色 ====================

    static int accent() {
        return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
    }

    static int primaryText() {
        return NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    static int secondaryText() {
        return NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
    }

    static int withAlpha(int rgb, double alpha) {
        int a = (int) Math.round(clamp01(alpha) * 255.0);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    static int mixRgb(int left, int right, double weight) {
        double w = clamp01(weight);
        int r = (int) Math.round(((left >> 16) & 0xFF) * (1 - w) + ((right >> 16) & 0xFF) * w);
        int g = (int) Math.round(((left >> 8) & 0xFF) * (1 - w) + ((right >> 8) & 0xFF) * w);
        int b = (int) Math.round((left & 0xFF) * (1 - w) + (right & 0xFF) * w);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int brighten(int argb, double amount) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double k = clamp01(amount);
        r = (int) Math.round(r + (255 - r) * k);
        g = (int) Math.round(g + (255 - g) * k);
        b = (int) Math.round(b + (255 - b) * k);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ==================== 绘制 ====================

    /**
     * 文本发光。
     *
     * <p>1.8.9 没有可用的后处理管线，沿用项目里其它发光的做法：同一串文本按环形偏移多画几遍、
     * 逐层降透明度。上限走 {@link HudConfig#fullscreenGlowStrength}，与其它样式共用同一个滑块。</p>
     */
    static void drawGlow(CFontRenderer font, String text, double x, double y,
                         int rgb, double alpha, double strength) {
        if (font == null || text == null || text.isEmpty()) return;
        double limit = clamp01(HudConfig.fullscreenGlowStrength);
        double effective = limit * clamp01(strength);
        if (effective <= .004 || alpha <= .004) return;
        double radius = 1.0 + 2.6 * effective;
        drawGlowRing(font, text, x, y, radius, rgb, alpha * effective * .34);
        drawGlowRing(font, text, x, y, radius * 1.75, rgb, alpha * effective * .15);
    }

    private static void drawGlowRing(CFontRenderer font, String text, double x, double y,
                                     double radius, int rgb, double alpha) {
        if (alpha <= .002) return;
        int cardinal = withAlpha(rgb, alpha);
        int diagonal = withAlpha(rgb, alpha * .72);
        double d = radius * .7071;
        font.drawString(text, x - radius, y, cardinal);
        font.drawString(text, x + radius, y, cardinal);
        font.drawString(text, x, y - radius, cardinal);
        font.drawString(text, x, y + radius, cardinal);
        font.drawString(text, x - d, y - d, diagonal);
        font.drawString(text, x + d, y - d, diagonal);
        font.drawString(text, x - d, y + d, diagonal);
        font.drawString(text, x + d, y + d, diagonal);
    }

    /**
     * 逐字点亮地画一串字形，返回推进的宽度（未缩放）。
     *
     * <p>每个字形自己算亮度：有逐字时间轴就用真实时间轴，没有就按字形均分行时长
     * （见 {@link #graphemeProgress}）。正在亮起的那一个额外套一层发光 —— 这就是「文字 PV」
     * 里那种光随着人声走的观感。</p>
     *
     * @param globalStart 这一串的第一个字形在整行里的下标
     * @param lightUp     false 表示这一行还没到（或已经过去），整串按 {@code dimRgb} 平铺
     */
    static double drawLitRun(CFontRenderer font, List<String> chars, int globalStart,
                             LyricLine line, int totalGraphemes, double positionMs,
                             double leftX, double topY, int dimRgb, int litRgb,
                             double alpha, boolean lightUp, double fadeMs, boolean glow) {
        if (font == null || chars == null || chars.isEmpty() || alpha <= .004) return 0;
        double cursor = 0;
        for (int i = 0; i < chars.size(); i++) {
            String glyph = chars.get(i);
            double advance = font.getStringWidthD(glyph);
            double p = lightUp
                    ? graphemeProgress(line, globalStart + i, totalGraphemes, positionMs, fadeMs)
                    : 0.0;
            int color = p <= 0 ? dimRgb : mixRgb(dimRgb, litRgb, p) & 0xFFFFFF;
            double glyphAlpha = alpha * (lightUp ? .58 + .42 * p : 1.0);
            if (glow && p > .02 && p < .99) {
                drawGlow(font, glyph, leftX + cursor, topY, litRgb, glyphAlpha, Math.sin(Math.PI * p));
            }
            font.drawString(glyph, leftX + cursor, topY, withAlpha(color, glyphAlpha));
            cursor += advance;
        }
        return cursor;
    }

    /** 圆角块，各样式的装饰件共用。 */
    static void bar(double x, double y, double width, double height, double radius,
                    int rgb, double alpha) {
        if (width <= .2 || height <= .2 || alpha <= .004) return;
        double r = Math.max(0.0, Math.min(radius, Math.min(width, height) * .5));
        Shaders.RQ_SHADER.draw(x, y, width, height, r,
                ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f,
                (float) clamp01(alpha));
    }

    /** 纯色圆点。 */
    static void dot(double centerX, double centerY, double radius, int rgb, double alpha) {
        if (radius <= .2) return;
        bar(centerX - radius, centerY - radius, radius * 2, radius * 2, radius, rgb, alpha);
    }

    /** 直角矩形，比圆角块便宜，背景层用。 */
    static void rect(double x, double y, double width, double height, int rgb, double alpha) {
        if (width <= .2 || height <= .2 || alpha <= .004) return;
        Rect.draw(x, y, width, height, withAlpha(rgb, alpha));
    }

    static int rgbOf(int argb) {
        return argb & 0xFFFFFF;
    }
}
