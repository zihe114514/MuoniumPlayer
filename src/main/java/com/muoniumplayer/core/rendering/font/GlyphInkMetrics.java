package com.muoniumplayer.core.rendering.font;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个字形「墨迹盒」的度量：真正被画出像素的那一块矩形，而不是字体的行盒。
 *
 * <p>为什么需要它：图标字体（fontello / music-brand-icons / qq-music-icons ……）里每个字形占满
 * em 方格的比例差别很大（实测 0.53 ~ 0.95 都有），行盒高度还额外受 {@link CFontRenderer} 的中文
 * 回退字体影响 —— {@code GlyphGenerator} 把「主字体与所有回退字体的 ascent+descent 的最大值」
 * 当作字形贴图高度，基线则按实际绘制字体算。于是「按 {@code getHeight()} 把标签居中」得到的
 * 结果既不是视觉居中，各图标之间也对不齐。这里直接用 AWT 量出字形轮廓，再换算到
 * {@link CFontRenderer#drawString(String, double, double, int)} 的坐标系。</p>
 *
 * <p>坐标系：所有字段都是「逻辑像素」，原点是 {@code drawString(text, x, y, ...)} 里的
 * {@code (x, y)}。drawString 内部先 {@code y -= 2} 再 {@code scale(0.5)}，字形贴图的左上角落在
 * {@code (x, y - 2)}，所以贴图内的偏移都要乘 0.5、纵向再减 2。</p>
 *
 * <p>量测结果按「字体 + 字号 + 回退字体 + 字符」缓存，量不出来（空白字形、字体缺失、AWT 抛错）
 * 时缓存 {@code null}，调用方需要自己保留按行盒定位的兜底路径。</p>
 */
public final class GlyphInkMetrics {

    /** 墨迹左边缘相对 {@code drawString} 的 x 参数的偏移。 */
    public final double offsetX;
    /** 墨迹上边缘相对 {@code drawString} 的 y 参数的偏移（通常是负数）。 */
    public final double offsetY;
    /** 墨迹宽度（逻辑像素）。 */
    public final double width;
    /** 墨迹高度（逻辑像素）。 */
    public final double height;

    private GlyphInkMetrics(double offsetX, double offsetY, double width, double height) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.width = width;
        this.height = height;
    }

    /**
     * 为了让墨迹中心落在 {@code centerX} 上，应该传给 {@code drawString} 的 x。
     */
    public double alignCenterX(double centerX) {
        return centerX - offsetX - width * .5;
    }

    /**
     * 为了让墨迹中心落在 {@code centerY} 上，应该传给 {@code drawString} 的 y。
     */
    public double alignCenterY(double centerY) {
        return centerY - offsetY - height * .5;
    }

    private static final BufferedImage PROBE_IMAGE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private static final Graphics2D PROBE_GRAPHICS = (Graphics2D) PROBE_IMAGE.getGraphics();
    private static final FontRenderContext PROBE_CONTEXT =
            new FontRenderContext(new AffineTransform(), true, true);
    private static final Map<String, GlyphInkMetrics> CACHE = new HashMap<String, GlyphInkMetrics>();

    /** 量测一个字形，失败返回 {@code null}。结果会被缓存，可以放心在每帧的回调里调用。 */
    public static GlyphInkMetrics of(CFontRenderer renderer, char glyph) {
        if (renderer == null || renderer.font == null) {
            return null;
        }
        String key = cacheKey(renderer, glyph);
        synchronized (CACHE) {
            if (CACHE.containsKey(key)) {
                return CACHE.get(key);
            }
        }
        GlyphInkMetrics measured;
        try {
            measured = measure(renderer, glyph);
        } catch (RuntimeException failed) {
            // 量测只是为了把图标摆正，任何异常都不该把整个界面带下去。
            measured = null;
        }
        synchronized (CACHE) {
            CACHE.put(key, measured);
        }
        return measured;
    }

    private static String cacheKey(CFontRenderer renderer, char glyph) {
        StringBuilder key = new StringBuilder();
        key.append(renderer.font.getFontName()).append('#').append(renderer.font.getSize2D());
        if (renderer.fallBackFonts != null) {
            for (Font candidate : renderer.fallBackFonts) {
                key.append('|').append(candidate == null ? "-" : candidate.getFontName());
            }
        }
        return key.append('#').append((int) glyph).toString();
    }

    private static GlyphInkMetrics measure(CFontRenderer renderer, char glyph) {
        // AWT 的 FontMetrics / GlyphVector 都不是线程安全的，而这里共享一个探针 Graphics2D。
        synchronized (PROBE_GRAPHICS) {
            Font drawFont = resolveFont(renderer, glyph);
            GlyphVector vector = drawFont.createGlyphVector(PROBE_CONTEXT, String.valueOf(glyph));
            Rectangle2D ink = vector.getGlyphOutline(0).getBounds2D();
            if (ink.isEmpty()) {
                return null;
            }
            // 与 GlyphGenerator 保持一致：负的 LSB 会把字形整体右移，贴图也跟着变宽。
            double drawOffsetX = Math.max(0.0, -vector.getGlyphMetrics(0).getLSB());
            FontMetrics drawMetrics = PROBE_GRAPHICS.getFontMetrics(drawFont);
            int baseline = (atlasHeight(renderer) + drawMetrics.getAscent() - drawMetrics.getDescent()) / 2;
            return new GlyphInkMetrics((drawOffsetX + ink.getX()) * .5,
                    -2.0 + (baseline + ink.getY()) * .5,
                    ink.getWidth() * .5, ink.getHeight() * .5);
        }
    }

    /** 复刻 {@code GlyphGenerator.getFontForGlyph}：主字体画不出来才落到回退字体。 */
    private static Font resolveFont(CFontRenderer renderer, char glyph) {
        if (renderer.font.canDisplay(glyph)) {
            return renderer.font;
        }
        if (renderer.fallBackFonts != null) {
            for (Font candidate : renderer.fallBackFonts) {
                if (candidate != null && candidate.canDisplay(glyph)) {
                    return candidate;
                }
            }
        }
        return renderer.font;
    }

    /** 复刻 {@code GlyphGenerator.getMaxFontHeight}：贴图高度取主字体与全部回退字体的最大行盒。 */
    private static int atlasHeight(CFontRenderer renderer) {
        int max = lineBox(renderer.font);
        if (renderer.fallBackFonts != null) {
            for (Font candidate : renderer.fallBackFonts) {
                if (candidate != null) {
                    max = Math.max(max, lineBox(candidate));
                }
            }
        }
        return max;
    }

    private static int lineBox(Font font) {
        FontMetrics metrics = PROBE_GRAPHICS.getFontMetrics(font);
        return metrics.getAscent() + metrics.getDescent();
    }
}
