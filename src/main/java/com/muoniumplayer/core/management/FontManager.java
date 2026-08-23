package com.muoniumplayer.core.management;

import lombok.SneakyThrows;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.FontKerning;

import java.awt.*;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * @since 4/8/2023 11:09 AM
 */
public class FontManager extends AbstractManager {

    /**
     * Java 8u51 的 Java2D CFF/OpenType 光栅化会让 SF Pro 的半角字形使用
     * 与实际绘制不一致的尺寸。中文由 PingFang TrueType 回退字体绘制，
     * 因而只有英文、数字和半角标点出现比例异常。
     *
     * 仅在受影响的早期 Java 8 运行时切换半角主字体到已内置的 Arial
     * TrueType；中文回退字体、UI 逻辑及其它字体系统均保持不变。
     */
    private static final boolean USE_LEGACY_JAVA8_TTF_FONTS = isLegacyJava8CffRuntime();

    public FontManager() {
        super("FontManager");
    }

    public static CFontRenderer pf10bold, pf12bold, pf14bold, pf16bold, pf18bold, pf20bold, pf25bold, pf28bold, pf34bold, pf40bold, pf65bold, pf50bold;
    public static CFontRenderer pf12, pf14, pf18, pf20, pf25, pf32;
    public static CFontRenderer icon30;
    public static CFontRenderer music14, music18, music40;
    /** Fontello icon font bundled with the player UI. */
    public static CFontRenderer fontello14, fontello16, fontello18, fontello22;
    /** Provider-brand Fontello glyphs use a separate font to avoid private-use code collisions. */
    public static CFontRenderer musicBrand16, musicBrand18;
    /**
     * 「下一首播放」图标字体（player-queue-icons，fontello U+E309）的两档字号。
     *
     * <p>两档而不是一档：这枚图标出现在两种大小的按钮里（歌曲行 20 像素、播放条 18 像素），而
     * 字形是按字号栅格化的，同一个渲染器画进不同大小的按钮就会一大一小。{@code PlayerQueueIcons}
     * 会按按钮边长挑墨迹最接近目标比例的那一档，把两处的观感对齐到相邻贴图图标的水平。</p>
     */
    public static CFontRenderer queueIcon34, queueIcon38;
    /**
     * 播放器动作图标字体（player-action-icons，fontello U+E113 添加到歌单 / U+E144 下一首播放列表）的两档字号。
     *
     * <p>和 {@code queueIcon*} 同样的理由要两档：同一枚字形出现在 20 像素与 18 像素的按钮里。
     * 单独一份字体文件而不是并入 player-queue-icons：两份是不同批次的 fontello 导出，各自独立
     * 才不会在私有区码位上互相覆盖。</p>
     */
    public static CFontRenderer actionIcon34, actionIcon38;
    /** QQ Music brand icon font; deliberately separate from Fontello login glyphs. */
    public static CFontRenderer qqMusicIcon16, qqMusicIcon20;
    /**
     * 灵动岛状态/结果图标字体（island-status-icons，fontello U+E901~E904 与 U+E803/E807/E808）。
     *
     * <p>再开一份字体而不是并进 {@code fontello}：这是另一批 fontello 导出，私用区码位和旧包
     * （U+E800~E802）会互相覆盖。两档字号分别给常驻状态栏的小图标（12）和左侧通知托盘（16）用，
     * 字形是按字号栅格化的，共用一档会在其中一处明显发虚。</p>
     */
    public static CFontRenderer islandIcon12, islandIcon16;

    public static List<CFontRenderer> getAllFontRenderers() {

        return Arrays.stream(FontManager.class.getDeclaredFields())
                .filter(field -> field.getType() == CFontRenderer.class)
                .map(method -> {
            try {
                return (CFontRenderer) method.get(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

    }

    public static void deleteLoadedTextures() {
        getAllFontRenderers().forEach(c -> {
            if (c != null) {
                c.close();
            }
        });
    }

    public static void loadFonts() {
        if (USE_LEGACY_JAVA8_TTF_FONTS) {
            System.out.println("[MuoniumPlayer] Detected Java 8u51-compatible runtime; using TrueType UI fonts for stable half-width glyph sizing.");
        }

        String normalName = "pf_normal";
        String boldName = "pf_middleblack";

        pf12 = create(12, normalName);
        pf14 = create(14, normalName);
        pf20 = create(20, normalName);
        pf25 = create(25, normalName);
        pf32 = create(32, normalName);
        pf18 = create(18, normalName);

        pf10bold = create(10, boldName);
        pf12bold = create(12, boldName);
        pf14bold = create(14, boldName);
        pf16bold = create(16, boldName);
        pf18bold = create(18, boldName);
        pf20bold = create(20, boldName);
        pf25bold = create(25, boldName);
        pf28bold = create(28, boldName);
        pf34bold = create(34, boldName);
        pf40bold = create(40, boldName);
        pf50bold = create(50, boldName);
        pf65bold = create(65, boldName);

        icon30 = create(30, "icomoon");

        music14 = create(14, "music");
        music18 = create(18, "music");
        music40 = create(40, "music");

        fontello14 = create(14, "fontello");
        fontello16 = create(16, "fontello");
        fontello18 = create(18, "fontello");
        fontello22 = create(22, "fontello");

        musicBrand16 = create(16, "music-brand-icons");
        musicBrand18 = create(18, "music-brand-icons");
        qqMusicIcon16 = create(16, "qq-music-icons");
        // The QQ Music brand glyph has more internal whitespace than the NetEase glyph.
        // Use a dedicated larger renderer in the source switcher so both providers read at the same visual size.
        qqMusicIcon20 = create(20, "qq-music-icons");
        queueIcon34 = create(34, "player-queue-icons");
        queueIcon38 = create(38, "player-queue-icons");
        actionIcon34 = create(34, "player-action-icons");
        actionIcon38 = create(38, "player-action-icons");
        islandIcon12 = create(12, "island-status-icons");
        islandIcon16 = create(16, "island-status-icons");
    }

    /**
     * Font renderers are constructed synchronously in {@link #loadFonts()}; none
     * are initialized by a later worker. The former endless polling loop therefore
     * could never repair a missing assignment/resource and froze Forge's loading
     * screen while spamming the log. Validate once and continue startup instead.
     */
    public static void waitUntilAllLoaded() {
        List<String> missing = Arrays.stream(FontManager.class.getDeclaredFields())
                .filter(field -> field.getType() == CFontRenderer.class)
                .filter(field -> {
                    try {
                        return field.get(null) == null;
                    } catch (IllegalAccessException ignored) {
                        return true;
                    }
                })
                .map(field -> field.getName())
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            System.err.println("[MuoniumPlayer] Font initialization incomplete: " + missing
                    + ". Continuing without a blocking retry loop.");
        }
    }

    @Override
    public void init() {
        loadFonts();
        waitUntilAllLoaded();
    }
    
    private static final HashMap<String, Font> fonts = new HashMap<>();
    private static final HashMap<String, FontKerning> fontKernings = new HashMap<>();
    
    private static Font readFont(String path) {
        return fonts.computeIfAbsent(path, p -> {
            try {
                InputStream resourceAsStream = FontManager.class.getResourceAsStream(p);
                Font font = Font.createFont(Font.TRUETYPE_FONT, resourceAsStream);
                resourceAsStream.close();
                return font;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }
    
    private static FontKerning readFontKerning(String path) {

        return fontKernings.computeIfAbsent(path, p -> {
            try {
                return new FontKerning(path);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream) {
        Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);

        Font fallback = readFont("/muonium/fonts/pf_normal.ttf");
        FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fallbackKerning, fallback);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream, InputStream fallBackStream) {
        Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
        Font fallBack = Font.createFont(Font.TRUETYPE_FONT, fallBackStream);

        FontKerning fontKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fontKerning, fallBack);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, String name) {

        Font font = readFont("/muonium/fonts/" + name + ".ttf");
        FontKerning kerning = readFontKerning("/muonium/fonts/" + name + ".ttf");

        // 中文字体默认使用 SF Pro 作为主字体
        // 因为它们的英文字母太他妈难看了
        // 丑陋不堪，，
        if ("googlesans".equals(name) || "product".equals(name) || "tahoma".equals(name)) {
            Font fallback = readFont("/muonium/fonts/pf_normal.ttf");
            FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("googlesansbold".equals(name)) {
            Font fallback = readFont("/muonium/fonts/pf_middleblack.ttf");
            FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_middleblack.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("pf_normal".equals(name)) {
            String mainPath = USE_LEGACY_JAVA8_TTF_FONTS
                    ? "/muonium/fonts/arial.ttf"
                    : "/muonium/fonts/sfregular.otf";
            Font main = readFont(mainPath);
            FontKerning mainKerning = readFontKerning(mainPath);
            return new CFontRenderer(main, size * 0.5f, mainKerning, font);
        } else if ("pf_middleblack".equals(name)) {
            String mainPath = USE_LEGACY_JAVA8_TTF_FONTS
                    ? "/muonium/fonts/arialBold.ttf"
                    : "/muonium/fonts/sfbold.otf";
            Font main = readFont(mainPath);
            FontKerning mainKerning = readFontKerning(mainPath);
            return new CFontRenderer(main, size * 0.5f, mainKerning, font);
        }
        return new CFontRenderer(font, size * 0.5f, kerning, font);

    }

    private static boolean isLegacyJava8CffRuntime() {
        int update = parseJava8Update(System.getProperty("java.version", ""));
        if (update < 0) {
            update = parseJava8Update(System.getProperty("java.runtime.version", ""));
        }

        // Java 8u51 is the affected target environment.  Keep newer Java 8
        // runtimes on the original SF Pro appearance.
        return update >= 0 && update <= 60;
    }

    static int parseJava8Update(String version) {
        if (version == null) {
            return -1;
        }

        String normalized = version.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return -1;
        }

        int updateStart;
        if (normalized.startsWith("1.8.0_")) {
            updateStart = "1.8.0_".length();
        } else if (normalized.startsWith("8u")) {
            updateStart = 2;
        } else if (normalized.startsWith("8.0.")) {
            updateStart = "8.0.".length();
        } else {
            return -1;
        }

        int updateEnd = updateStart;
        while (updateEnd < normalized.length() && Character.isDigit(normalized.charAt(updateEnd))) {
            updateEnd++;
        }
        if (updateEnd == updateStart) {
            return -1;
        }

        try {
            return Integer.parseInt(normalized.substring(updateStart, updateEnd));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public void stop() {

    }
}
