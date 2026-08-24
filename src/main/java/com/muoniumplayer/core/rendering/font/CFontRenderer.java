package com.muoniumplayer.core.rendering.font;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.math.Mth;
import com.muoniumplayer.core.utils.other.StringUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.util.*;
import java.util.List;


public class CFontRenderer implements Closeable, SharedConstants {

    // 所有字形的数据
    // 65536 个 但是没占多少内存
    public Glyph[] allGlyphs = new Glyph['\uFFFF' + 1];

    public Font font;
    public Font[] fallBackFonts;
    public float sizePx;
    private TextureAtlas atlas;
    private FontKerning fontKerning;

    // Oracle JDK 8 对 CFF(PostScript outline，即 .otf) 字体存在 2x 缩放 bug：
    // deriveFont(size) 报告的 getSize2D() 正确，但实际 ascent/descent/advance 都被放成应有的 2 倍。
    // 主字体(SF Pro 用 sfregular.otf/sfbold.otf)受影响，回退字体(PingFang 用 .ttf)不受影响，
    // 导致半角字符(数字/字母)宽高约为中文的 2 倍。这里在加载时检测并按 0.5 修正。
    private static final java.util.Map<Font, Float> cffScaleFactorCache = new java.util.HashMap<>();
    private static final BufferedImage cffMetricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private static final Graphics2D cffMetricsGraphics = (Graphics2D) cffMetricsImage.getGraphics();

    private static float cffScaleFactor(Font rawFont) {
        Float cached = cffScaleFactorCache.get(rawFont);
        if (cached != null) {
            return cached;
        }
        FontMetrics fm = cffMetricsGraphics.getFontMetrics(rawFont.deriveFont(100f));
        float ratio = (fm.getAscent() + fm.getDescent()) / 100f;
        // 正常字体 ascent+descent ≈ 1.2~1.5 em；CFF 被 Oracle JDK 放大后 ≈ 2.5 em
        float factor = ratio > 2.0f ? 0.5f : 1.0f;
        cffScaleFactorCache.put(rawFont, factor);
        return factor;
    }

    public CFontRenderer(Font font, float sizePx) {
        this.sizePx = sizePx;
        this.atlas = new TextureAtlas();
        init(font, sizePx);
    }

    @SneakyThrows
    public CFontRenderer(Font font, float sizePx, Font... fallBackFonts) {
        this(font, sizePx);

        this.fallBackFonts = new Font[fallBackFonts.length];

        for (int i = 0; i < fallBackFonts.length; i++) {
            this.fallBackFonts[i] = fallBackFonts[i].deriveFont(sizePx * 2 * cffScaleFactor(fallBackFonts[i]));
        }

//        this.fallBackFonts[fallBackFonts.length] = Font.createFont(Font.TRUETYPE_FONT, FontManager.class.getResourceAsStream("/muonium/fonts/NotoColorEmoji.ttf")).deriveFont(sizePx * 2);
//        this.fallBackFonts[fallBackFonts.length + 1] = Font.createFont(Font.TRUETYPE_FONT, FontManager.class.getResourceAsStream("/muonium/fonts/Symbola.ttf")).deriveFont(sizePx * 2);
    }

    public CFontRenderer(Font font, float sizePx, FontKerning fontKerning, Font... fallBackFonts) {
        this(font, sizePx, fallBackFonts);
        this.fontKerning = fontKerning;
    }

    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '§') {
                i++;
                continue;
            }
            f.append(c);
        }
        return f.toString();
    }

    private void init(Font font, float sizePx) {
        this.font = font.deriveFont(sizePx * 2 * cffScaleFactor(font));
    }

    public double fontHeight = -1;
    final Object fontHeightLock = new Object();

    private Glyph locateGlyph(char ch) {
        Glyph gly = allGlyphs[ch];
        if (gly != null && gly.uploaded) return gly;

        if (gly == null) {
            GlyphGenerator.generate(this, ch, this.font, atlas, fontHeight -> {
                synchronized (fontHeightLock) {
                    this.fontHeight = Math.max(this.fontHeight, fontHeight);
                }
            });
        }

        return null;
    }

    public float drawString(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;
        drawString(s,  x,  y, r, g, b, a);
        return (float) getStringWidthD(s);
    }

    public int drawStringWithShadow(String text, double x, double y, int color) {

        int a = (color >> 24) & 0xff;

        drawString(stripControlCodes(text), x + 1, y + 1, RGBA.color(0, 0, 0, a));
        drawString(text, x, y, color);

        return this.getStringWidth(text);
    }

    public void drawString(String s, double x, double y, Color color) {
        drawString(s,  x,  y, color.getRed() * RenderSystem.DIVIDE_BY_255, color.getGreen() * RenderSystem.DIVIDE_BY_255, color.getBlue() * RenderSystem.DIVIDE_BY_255, color.getAlpha());
    }

    public void drawCenteredStringVertical(String text, double x, double y, int color) {
        drawString(text, x, y - this.getFontHeight() * .5, color);
    }

    private int getColorCode(char c) {
        switch (c) {
            case '0': return 0x000000;
            case '1': return 0x0000AA;
            case '2': return 0x00AA00;
            case '3': return 0x00AAAA;
            case '4': return 0xAA0000;
            case '5': return 0xAA00AA;
            case '6': return 0xFFAA00;
            case '7': return 0xAAAAAA;
            case '8': return 0x555555;
            case '9': return 0x5555FF;
            case 'a': return 0x55FF55;
            case 'b': return 0x55FFFF;
            case 'c': return 0xFF5555;
            case 'd': return 0xFF55FF;
            case 'e': return 0xFFFF55;
            case 'f': return 0xFFFFFF;
            default: return Integer.MIN_VALUE; // Default color or throw an exception
        }
    }

//    @EqualsAndHashCode
//    @AllArgsConstructor
//    private static class StringRenderCall {
//        public String s;
//        public float r, g, b, a;
//
//        public static StringRenderCall of(String s, float r, float g, float b, float a) {
//            return new StringRenderCall(s, r, g, b, a);
//        }
//    }

    public boolean drawString(String s, double x, double y, float r, float g, float b, float a) {
        api.getGLStateManager().pushMatrix();

        y -= 2.0f;

        api.getGLStateManager().translate(x, y, 0);
        api.getGLStateManager().scale(0.5f, 0.5f, 1f);
        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableTexture2D();

        api.getGLStateManager().color(r, g, b, a);

        api.getGLStateManager().bindTexture(atlas.getTextureId());

        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);

        boolean callLists = false;
        boolean allLoaded = callLists ? drawStringWithCallList(s, x, y, r, g, b, a) : drawStringImmediateMode(s, x, y, r, g, b, a);

        api.getGLStateManager().popMatrix();
        return allLoaded;
    }

    private boolean drawStringImmediateMode(String s, double x, double y, float r, float g, float b, float a) {
        boolean allLoaded = true;
        double xOffset = 0;
        double yOffset = 0;
        boolean inSel = false;

        GL11.glBegin(GL11.GL_TRIANGLES);

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char nextChar = i + 1 < s.length() ? s.charAt(i + 1) : '\0';

            if (inSel) {
                inSel = false;
                if (c == 'r') {
                    api.getGLStateManager().color(r, g, b, a);
                } else {
                    int colorCode = this.getColorCode(c);
                    if (colorCode != Integer.MIN_VALUE) {
                        int red = colorCode >> 8 * 2 & 0xFF;
                        int green = colorCode >> 8 & 0xFF;
                        int blue = colorCode & 0xFF;
                        api.getGLStateManager().color(red * RenderSystem.DIVIDE_BY_255, green * RenderSystem.DIVIDE_BY_255, blue * RenderSystem.DIVIDE_BY_255, a);
                    }
                }
                continue;
            }

            if (c == '§') {
                inSel = true;
                continue;
            }
            if (c == '\n') {
                yOffset += this.getHeight() * 2 + 4;
                xOffset = 0;
                continue;
            }

            if (c == '（') c = '(';
            if (c == '）') c = ')';
            if (c == '・') c = '·';

            Glyph glyph = locateGlyph(c);
            if (glyph != null && glyph.uploaded) {
                float x0 = (float) xOffset;
                float y0 = (float) yOffset;
                float x1 = x0 + glyph.width;
                float y1 = y0 + glyph.height;

                // first triangle
                {
                    GL11.glTexCoord2f(glyph.u0, glyph.v0);
                    GL11.glVertex2f(x0, y0);

                    GL11.glTexCoord2f(glyph.u0, glyph.v1);
                    GL11.glVertex2f(x0, y1);

                    GL11.glTexCoord2f(glyph.u1, glyph.v0);
                    GL11.glVertex2f(x1, y0);
                }

                // second
                {
                    GL11.glTexCoord2f(glyph.u1, glyph.v0);
                    GL11.glVertex2f(x1, y0);

                    GL11.glTexCoord2f(glyph.u0, glyph.v1);
                    GL11.glVertex2f(x0, y1);

                    GL11.glTexCoord2f(glyph.u1, glyph.v1);
                    GL11.glVertex2f(x1, y1);
                }

                xOffset += glyph.width;
                
                // 添加字间距
                if (fontKerning != null && nextChar != '\0' && nextChar != '§' && nextChar != '\n') {
                    xOffset += fontKerning.getKerning(c, nextChar, sizePx) * 2;
                }
            } else {
                allLoaded = false;
            }
        }

        GL11.glEnd();
        return allLoaded;
    }

    private final Map<String, StringRenderCall> callListMap = new HashMap<>();

    @RequiredArgsConstructor
    private class StringRenderCall {
        public final String s;

        private int[] callLists;
        private int[] colors;
        double xOffset = 0;
        double yOffset = 0;

        public boolean render(float r, float g, float b, float a) {

            if (callLists != null) {
                for (int i = 0; i < callLists.length; i++) {
                    int color = colors[i];

                    if (color != Integer.MIN_VALUE)
                        RenderSystem.color(RenderSystem.reAlpha(color, a));
                    else
                        api.getGLStateManager().color(r, g, b, a);
                    GL11.glCallList(callLists[i]);
                }

                return true;
            }

            if (this.compile(r, g, b)) {
                this.render(r, g, b, a);
                return true;
            }

            return false;
        }

        public boolean compile(float r, float g, float b) {
            int length = s.length();
            for (int i = 0; i < length; i++) {
                char c = s.charAt(i);

                if (c == '§') {
                    i++;
                    continue;
                }

                Glyph gly = locateGlyph(c);
                if (gly == null) {
                    return false;
                }
            }

            List<Tuple<Integer, Integer>> callLists = new ArrayList<>();
            StringBuilder builder = new StringBuilder();
            float curR = r, curG = g, curB = b;
            for (int i = 0; i < length; i++) {
                char c = s.charAt(i);

                if (c == '§' && i < length - 1) {
                    char next = s.charAt(i + 1);
                    if (next == 'r') {
                        int color = (curR == r && curG == g && curB == b) ? Integer.MIN_VALUE : RGBA.color(curR, curG, curB);
                        callLists.add(Tuple.of(this.compile(builder.toString()), color));
                        builder.setLength(0);
                        curR = r;
                        curG = g;
                        curB = b;
                    } else {
                        int colorCode = CFontRenderer.this.getColorCode(next);
                        if (colorCode != Integer.MIN_VALUE) {
                            int color = (curR == r && curG == g && curB == b) ? Integer.MIN_VALUE : RGBA.color(curR, curG, curB);
                            callLists.add(Tuple.of(this.compile(builder.toString()), color));
                            builder.setLength(0);
                            curR = RGBA.redFloat(colorCode);
                            curG = RGBA.greenFloat(colorCode);
                            curB = RGBA.blueFloat(colorCode);
                        }
                    }
                    i++;
                    continue;
                }

                builder.append(c);
            }
            callLists.add(Tuple.of(this.compile(builder.toString()), (curR == r && curG == g && curB == b) ? Integer.MIN_VALUE : RGBA.color(curR, curG, curB)));
            this.callLists = callLists.stream().mapToInt(Tuple::getA).toArray();
            this.colors = callLists.stream().mapToInt(Tuple::getB).toArray();
            return true;
        }

        private int compile(String string) {
            int callList = GL11.glGenLists(1);

            GL11.glNewList(callList, GL11.GL_COMPILE);
            GL11.glBegin(GL11.GL_TRIANGLES);

            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                char nextChar = i + 1 < string.length() ? string.charAt(i + 1) : '\0';

                if (c == '\n') {
                    yOffset += CFontRenderer.this.getHeight() * 2 + 4;
                    xOffset = 0;
                    continue;
                }

                if (c == '（') c = '(';
                if (c == '）') c = ')';
                if (c == '・') c = '·';

                Glyph glyph = locateGlyph(c);
                if (glyph != null && glyph.uploaded) {
                    float x0 = (float) xOffset;
                    float y0 = (float) yOffset;
                    float x1 = x0 + glyph.width;
                    float y1 = y0 + glyph.height;

                    // first triangle
                    {
                        GL11.glTexCoord2f(glyph.u0, glyph.v0);
                        GL11.glVertex2f(x0, y0);

                        GL11.glTexCoord2f(glyph.u0, glyph.v1);
                        GL11.glVertex2f(x0, y1);

                        GL11.glTexCoord2f(glyph.u1, glyph.v0);
                        GL11.glVertex2f(x1, y0);
                    }

                    // second
                    {
                        GL11.glTexCoord2f(glyph.u1, glyph.v0);
                        GL11.glVertex2f(x1, y0);

                        GL11.glTexCoord2f(glyph.u0, glyph.v1);
                        GL11.glVertex2f(x0, y1);

                        GL11.glTexCoord2f(glyph.u1, glyph.v1);
                        GL11.glVertex2f(x1, y1);
                    }

                    xOffset += glyph.width;
                    
                    // 添加字间距
                    if (fontKerning != null && nextChar != '\0' && nextChar != '§' && nextChar != '\n') {
                        xOffset += fontKerning.getKerning(c, nextChar, sizePx) * 2;
                    }
                }
            }

            GL11.glEnd();
            GL11.glEndList();

            return callList;
        }
    }

    private boolean drawStringWithCallList(String s, double x, double y, float r, float g, float b, float a) {

        StringRenderCall value = callListMap.computeIfAbsent(s, StringRenderCall::new);

        if (value.render(r, g, b, a)) {
//            api.getGLStateManager().resetColor();
//            api.getGLStateManager().textureState[api.getGLStateManager().activeTextureUnit].textureName = -1;
            api.getGLStateManager().bindTexture(-1);
            return true;
        }

        this.drawStringImmediateMode(s, x, y, r, g, b, a);

        return false;
    }

    public void drawCenteredString(String s, double x, double y, int color) {
        _drawCenteredString(s, x, y, color);
    }

    /**
     * @return true if all the chars in the string are loaded
     */
    public boolean _drawCenteredString(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;

        return drawString(s,  (x - getStringWidthD(s) * .5),  y, r, g, b, a);
    }

    public void drawCenteredStringWithShadow(String s, double x, double y, int color) {
        drawStringWithShadow(s,  (x - getStringWidthD(s) * .5),  y, color);
    }

    public void drawCenteredStringMultiLine(String s, double x, double y, int color) {
        float r = ((color >> 16) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float g = ((color >> 8) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float b = ((color) & 0xff) * RenderSystem.DIVIDE_BY_255;
        float a = ((color >> 24) & 0xff) * RenderSystem.DIVIDE_BY_255;

        double offsetY = y;
        for (String string : s.split("\n")) {
            drawString(string,  (x - getStringWidthD(string) / 2.0),  offsetY, r, g, b, a);
            offsetY += this.getFontHeight();
        }

    }

    public String trim(String text, double width) {
        String name = text == null ? "" : text;

        // A zero/negative content area can legitimately occur while responsive UI controls
        // collapse.  There is no valid substring to draw in that case.
        if (width <= 0.0 || name.isEmpty()) {
            return "";
        }

        if (this.getStringWidthD(name) > width) {
            int idx = name.length();
            while (idx > 0) {
                String substring = name.substring(0, idx);

                if (this.getStringWidthD(substring + "...") <= width) {
                    return substring + "...";
                }

                idx--;
            }

            // Even an ellipsis cannot fit; keep the renderer safe and draw nothing rather
            // than letting the substring index become negative.
            return "";
        }

        return name;
    }

    public int getStringWidth(String text) {
        return Mth.floor(getStringWidthD(text));
    }

    /**
     * 字宽记忆表。
     *
     * <p>上限是必须的：键是任意文本，而调用方里有「歌词逐字前缀」这类会随歌曲不断产生新串的用法，
     * 一局游戏放上百首歌就会稳定地往里堆几万条，且永远不会被回收。超过上限时整表清掉而不是做 LRU：
     * 这件事罕见到不值得为它维护访问顺序，清掉之后当前这一屏的文本会在同一帧内重新量测填回来。</p>
     */
    private static final int STRING_WIDTH_CACHE_LIMIT = 20_000;

    private final Map<String, Double> stringWidthMapD = new HashMap<>();

    public boolean areGlyphsLoaded(String text) {

        for (char c : text.toCharArray()) {

            if (c == '（')
                c = '(';

            if (c == '）')
                c = ')';

            if (c == '\n')
                continue;

            if (c == ' ')
                continue;

            if (c == '\r')
                continue;

            if (c == '\t')
                continue;

            if (c == '\247')
                continue;

            Glyph gly = allGlyphs[c];
            if (gly == null || !gly.uploaded)
                return false;
        }

        return true;
    }

    public double getStringWidthD(String text) {
        Double f = this.stringWidthMapD.get(text);
        if (f != null)
            return f;

        boolean shouldntAdd = false;

        char[] c = stripControlCodes(text).toCharArray();
        double currentLine = 0;
        double maxPreviousLines = 0;
        for (int i = 0; i < c.length; i++) {
            char c1 = c[i];
            char c2 = i + 1 < c.length ? c[i + 1] : '\0';
            
            if (c1 == '\n') {
                maxPreviousLines = Math.max(currentLine, maxPreviousLines);
                currentLine = 0;
                continue;
            }

            if (c1 == '（')
                c1 = '(';

            if (c1 == '）')
                c1 = ')';

            float charWidth = getCharWidth(c1, c2);

            if (!shouldntAdd) {
                shouldntAdd = charWidth == 0;
            }

            currentLine += charWidth;
        }

        if (!shouldntAdd) {
            if (this.stringWidthMapD.size() >= STRING_WIDTH_CACHE_LIMIT) {
                this.stringWidthMapD.clear();
            }
            this.stringWidthMapD.put(text, Math.max(currentLine, maxPreviousLines));
        }

        return Math.max(currentLine, maxPreviousLines);
    }

    public int getHeight() {
        return (int) this.getFontHeight();
    }

    public double getFontHeight() {
        return (this.fontHeight - 8.5) * .5;
    }

    @Override
    public void close() {
        atlas.destroy();
        atlas.init();
        allGlyphs = new Glyph['\uFFFF' + 1];
        stringWidthMapD.clear();
        callListMap.values().forEach(cl -> {
            if (cl.callLists != null) {
                for (int callList : cl.callLists) {
                    GL11.glDeleteLists(callList, 1);
                }
            }
        });
        callListMap.clear();
        fontHeight = -1;
    }

    public static int[] RGBIntToRGB(int in) {
        int red = in >> 8 * 2 & 0xFF;
        int green = in >> 8 & 0xFF;
        int blue = in & 0xFF;
        return new int[]{red, green, blue};
    }

    float getCharWidth(char ch) {
        return getCharWidth(ch, '\0');
    }

    public float getCharWidth(char ch, char nextChar) {
        Glyph glyph = allGlyphs[ch];

        if (glyph == null)
            return .0f;

        float width = glyph.width * .5f;
        
        // 添加字间距
        if (fontKerning != null && nextChar != '\0') {
            width += fontKerning.getKerning(ch, nextChar, sizePx);
        }
        
        return width;
    }

    public double getStringHeight(String text) {
        return text.split("\n").length * (getFontHeight() + 4) - 4;
    }

    /**
     * 将给定的字符串 trim 到给定的 width。
     * 如果有空格则从空格开始分割
     * 否则从最后一个字符开始
     * 所有的分割点详见 {@link #findLineBreak(String, int, double)} 中的 breakable
     */
    public String[] fitWidth(String text, double width) {
        List<String> lines = new ArrayList<>();

        int i = 0;
        while (i < text.length()) {
            int previousI = i;
            LineBreakResult result = findLineBreak(text, i, width);

            lines.add(text.substring(i, result.endIndex));

            i = result.nextStartIndex;

            // avoid infinite loop
            if (i == previousI) {
                i++;
            }
        }

        //        System.out.println("Breaking \"" + text + "\" into [" + String.join("\", \"", array) + "]");
        return lines.toArray(new String[0]);
    }

    @Getter
    static final char[] breakableChars = new char['\uFFFF' + 1];
    static String breakable  =  " .。,，!！?？;；、";
    static String wrapStarts =  "(（「『{[【<";
    static String wrapEnds   =  ")）」』}]】>";
    static {
        for (char c : breakable.toCharArray()) {
            breakableChars[c] = 2;
        }

        for (char c : wrapStarts.toCharArray()) {
            breakableChars[c] = 3;
        }

        for (char c : wrapEnds.toCharArray()) {
            breakableChars[c] = 1;
        }
    }

    private int findMatchingOpenBracket(String text, int closeIndex, int startIndex) {
        char closeChar = text.charAt(closeIndex);
        int closeCharType = wrapEnds.indexOf(closeChar);
        if (closeCharType == -1) {
            return -1;
        }

        char openChar = wrapStarts.charAt(closeCharType);
        int depth = 1;

        for (int i = closeIndex - 1; i >= startIndex; i--) {
            char c = text.charAt(i);

            if (i > startIndex && text.charAt(i - 1) == '\247') {
                i--;
                continue;
            }

            if (c == closeChar) {
                depth++;
            } else if (c == openChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private LineBreakResult findLineBreak(String text, int startIndex, double maxWidth) {
        double currentWidth = 0;
        int lastBreakableIndex = -1;
        int lastBreakableIndexPriority = 0;
        boolean lastBreakableTrimThisChar = false;

        for (int i = startIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            char nextChar = i + 1 < text.length() ? text.charAt(i + 1) : '\0';

            if (c == '\247' && i + 1 < text.length()) {
                i++;
                continue;
            }

            if (c == '\n') {
                return new LineBreakResult(i, i + 1);
            }

            int breakableCharValue = breakableChars[c];
            if (breakableCharValue > 0) {
                if (breakableCharValue == 1) {
                    int openIndex = findMatchingOpenBracket(text, i, startIndex);
                    if (openIndex != -1 && openIndex == lastBreakableIndex) {
                        lastBreakableIndexPriority = 2;
                        lastBreakableIndex = i;
                        lastBreakableTrimThisChar = false;
                    } else if (breakableCharValue >= lastBreakableIndexPriority) {
                        lastBreakableIndexPriority = breakableCharValue;
                        lastBreakableIndex = i;
                    }
                } else if (breakableCharValue >= lastBreakableIndexPriority) {
                    lastBreakableIndexPriority = breakableCharValue;
                    lastBreakableIndex = i;
                    lastBreakableTrimThisChar = (c == ' ');
                }
            }

            double charWidth = getCharWidth(c, nextChar);

            if (currentWidth + charWidth > maxWidth) {
                if (breakableCharValue > 0 && breakableCharValue >= lastBreakableIndexPriority) {
                    return handleBreakAtCurrentChar(i, breakableCharValue, lastBreakableTrimThisChar);
                }

                if (lastBreakableIndex != -1) {
                    return handleBreakAtLastBreakable(text, lastBreakableIndex, lastBreakableTrimThisChar, startIndex, i);
                }

                if (i == startIndex) {
                    return new LineBreakResult(startIndex + 1, startIndex + 1);
                }
                return new LineBreakResult(i, i);
            }

            currentWidth += charWidth;
        }

        return new LineBreakResult(text.length(), text.length());
    }

    private LineBreakResult handleBreakAtCurrentChar(int index, int breakableCharValue, boolean trimThisChar) {
        if (trimThisChar) {
            return new LineBreakResult(index, index + 1);
        }

        if (breakableCharValue == 3) {
            return new LineBreakResult(index, index);
        }

        return new LineBreakResult(index + 1, index + 1);
    }

    private LineBreakResult handleBreakAtLastBreakable(String text, int lastBreakableIndex,
                                                       boolean trimThisChar, int startIndex, int currentIndex) {
        if (trimThisChar) {
            return new LineBreakResult(lastBreakableIndex, lastBreakableIndex + 1);
        }

        char lastBreakableChar = text.charAt(lastBreakableIndex);
        int lastBreakableCharValue = breakableChars[lastBreakableChar];

        if (lastBreakableCharValue == 3) {
            if (lastBreakableIndex == startIndex) {
                if (currentIndex == startIndex) {
                    return new LineBreakResult(startIndex + 1, startIndex + 1);
                }
                return new LineBreakResult(currentIndex, currentIndex);
            }
            return new LineBreakResult(lastBreakableIndex, lastBreakableIndex);
        }

        return new LineBreakResult(lastBreakableIndex + 1, lastBreakableIndex + 1);
    }

    private static class LineBreakResult {
        final int endIndex;
        final int nextStartIndex;

        LineBreakResult(int endIndex, int nextStartIndex) {
            this.endIndex = endIndex;
            this.nextStartIndex = nextStartIndex;
        }
    }

    public void drawStringWithBetterShadow(String text, double x, double y, int color) {
        drawString(text, x, y, color);
    }

    public void drawOutlineCenteredString(String text, double x, double y, int color, int outlineColor) {
        drawOutlineString(text, x - getStringWidthD(text) * .5, y, color, outlineColor);
    }

    public void drawOutlineString(String text, double x, double y, int color, int outlineColor) {
        String outlinetext = StringUtils.removeFormattingCodes(text);
        drawString(outlinetext, x + 0.5, y, outlineColor);
        drawString(outlinetext, x - 0.5, y, outlineColor);
        drawString(outlinetext, x, y + 0.5, outlineColor);
        drawString(outlinetext, x, y - 0.5, outlineColor);
        drawString(text, x, y, color);
    }

    public int getWidth(String text) {
        return this.getStringWidth(text);
    }

    public float drawString(String text, double x, double y, double scale, int color) {

        api.getGLStateManager().pushMatrix();

        api.getGLStateManager().translate(x, y, 0);
        api.getGLStateManager().scale(scale, scale, 1);

        float f = drawString(text, 0, 0, color);

        api.getGLStateManager().popMatrix();
        return f;
    }

}
