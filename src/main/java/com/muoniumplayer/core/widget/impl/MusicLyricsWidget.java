package com.muoniumplayer.core.widget.impl;

import today.opai.api.enums.EnumChatColor;
import today.opai.api.enums.EnumModuleCategory;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.interfaces.EventHandler;
import today.opai.api.interfaces.modules.values.BooleanValue;
import today.opai.api.interfaces.modules.values.ColorValue;
import today.opai.api.interfaces.modules.values.ModeValue;
import today.opai.api.interfaces.modules.values.NumberValue;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.reflection.Reflection;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.LyricDuetGroups;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricsPanelGeometry;
import com.muoniumplayer.core.settings.ClientSettings;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.WidgetWrapper;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/2/14 20:34
 */
public class MusicLyricsWidget extends ExtensionModule implements SharedConstants, SharedRenderingConstants, EventHandler {
    public ModeValue alignMode = api.getValueManager().createModes("Align Mode", "Center", new String[]{ "Left", "Center", "Right" });

    public enum AlignMode {
        Left,
        Center,
        Right
    }

    public NumberValue width = api.getValueManager().createDouble("Width", 450, 225, 900, 5);
    public NumberValue height = api.getValueManager().createDouble("Height", 120, 60, 480, 5);
    public NumberValue lyricHeight = api.getValueManager().createDouble("Lyric Height", 20.0, 14.0, 50.0, 0.5);
    public ColorValue lyricColor = api.getValueManager().createColor("Lyric Color", Color.WHITE);
    public ColorValue currentLyricColor = api.getValueManager().createColor("Current Lyric Color", Color.WHITE);
    public BooleanValue shadow = api.getValueManager().createBoolean("Shadow", false);
    public BooleanValue singleLine = api.getValueManager().createBoolean("Single Line Mode", false);
    public BooleanValue showTranslation = api.getValueManager().createBoolean("Show Translation", true);
    public BooleanValue graceScroll = api.getValueManager().createBoolean("Elegant Scrolling", true);
    public BooleanValue showRoman = api.getValueManager().createBoolean("Show Romanization in Japanese songs", false);
    public BooleanValue dynIsland = api.getValueManager().createBoolean("Dynamic Island Lyrics", false);
    /** AMLL TTML 词库(人工校对的逐字歌词)优先于任何平台歌词。关掉后完全回到旧的解析链。 */
    public BooleanValue preferAmllLyrics = api.getValueManager().createBoolean("Prefer AMLL Word-by-Word Lyrics", true);
    /** AMLL 词库没有收录时,再用 QQ 音乐的 QRC 逐字歌词;仍然优先于任何行级歌词。 */
    public BooleanValue preferQqQrcLyrics = api.getValueManager().createBoolean("Prefer QQ Word-by-Word Lyrics", true);

    public ExtensionWidget widget;
    WidgetWrapper.WidgetPosSizeInterface wpsInterface;

    // The HUD editor reuses the production renderer at an editor-provided position.
    // Keeping this state local avoids changing the persisted HUD configuration while previewing.
    private boolean editorPreviewActive;
    private float editorPreviewX;
    private float editorPreviewY;
    private float editorPreviewScale;

    /**
     * 桌面歌词的 KTV 离屏合成器（与全屏歌词页同一套做法）。
     *
     * <p>持有两张复用的离屏纹理，所以必须是实例字段而不是每帧新建。</p>
     */
    private final KaraokeTextCompositor karaokeCompositor = new KaraokeTextCompositor();

    public MusicLyricsWidget() {
        super("Music Lyrics", "Show lyrics.", EnumModuleCategory.VISUAL);

        graceScroll.setHiddenPredicate(() -> singleLine.getValue());
        showRoman.setHiddenPredicate(() -> !showTranslation.getValue());
        dynIsland.setHiddenPredicate(() -> !Reflection.DYNAMIC_ISLAND_SUPPORTED);
        
        this.addValues(alignMode, width, height, lyricHeight, lyricColor, currentLyricColor, shadow, singleLine, graceScroll, showRoman, dynIsland, preferAmllLyrics, preferQqQrcLyrics);

        Tuple<ExtensionWidget, WidgetWrapper.WidgetPosSizeInterface> wrapped = WidgetWrapper.createWrapper(this, this::onRender);
        this.widget = wrapped.getA();
        this.wpsInterface = wrapped.getB();
        this.setEventHandler(this);
    }

    /** Applies the lyric appearance settings stored by the built-in HUD editor. */
    public void loadHudEditorSettings() {
        lyricColor.setValue(new Color(HudConfig.lyricColorRgb, true));
        currentLyricColor.setValue(new Color(HudConfig.currentLyricColorRgb, true));
    }

    /**
     * Renders the same animated desktop-lyric path used in game, but at the
     * position selected in the HUD editor. The persisted HudConfig position is
     * intentionally not touched by this preview.
     */
    public void renderEditorPreview(float x, float y, float scale) {
        if (this.widget == null) {
            return;
        }

        editorPreviewActive = true;
        editorPreviewX = x;
        editorPreviewY = y;
        editorPreviewScale = scale;
        try {
            this.widget.render();
        } finally {
            editorPreviewActive = false;
        }
    }

    private static final double CONTENT_HORIZONTAL_PADDING = 12.0;
    private static final double PRIMARY_LINE_SPACING = 3.0;
    private static final double SECONDARY_LINE_SPACING = 2.0;
    private static final double PRIMARY_TO_SECONDARY_SPACING = 5.0;
    private static final double ROW_SPACING = 10.0;
    private static final int KARAOKE_FEATHER_STEPS = 10;
    /** 块高超过 HUD 时，当前行（对唱是整组）的不透明度下限，避免整行被边缘淡出吃掉。 */
    private static final double MIN_OVERFLOW_BLOCK_ALPHA = .55;
    /** KTV 光晕的基础强度（还没唱到这个字时也留一点点底光）。 */
    private static final double KARAOKE_GLOW_BASE_DRIVE = .18;
    /** KTV 光晕随「这个字已经唱了多久」增加的强度。 */
    private static final double KARAOKE_GLOW_PROGRESS_DRIVE = .34;
    /** 上面两项之和，用于反推离屏纹理需要留多少发光白边。 */
    private static final double KARAOKE_GLOW_MAX_DRIVE =
            KARAOKE_GLOW_BASE_DRIVE + KARAOKE_GLOW_PROGRESS_DRIVE;

    /**
     * Seeks must not reuse the animated positions from an unrelated timestamp.
     * Marking the cached positions invalid lets the next frame lay every visible line
     * out at its new location before it resumes the smooth follow animation.
     */
    public static void resetProgress(float progress) {
        if (CloudMusic.lyrics.isEmpty()) return;

        try {
            CloudMusic.setLyricsProgress(progress);
            synchronized (CloudMusic.lyrics) {
                for (LyricLine line : CloudMusic.lyrics) {
                    line.offsetY = Double.MIN_VALUE;
                    line.lineAlpha = .4f;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static boolean hasSecondaryLyrics() {
        return CloudMusic.hasSecondaryLyrics();
    }

    public static String getSecondaryLyrics(LyricLine bean) {
        return CloudMusic.getSecondaryLyrics(bean);
    }

    public void onRender() {

        if (!shouldRender()) {
            return;
        }

        // HUD 位置 + 缩放（HudConfig，由 GuiHudEditor 拖拽/滚轮调整）。
        // 位置公式与参考实现一致：pixel = pos * (screen - baseSize * scale)；缩放围绕左上锚点等比进行。
        float hudScale = editorPreviewActive ? editorPreviewScale : HudConfig.lyricScale;
        float baseW = this.width.getValue().floatValue();
        float baseH = this.height.getValue().floatValue();
        float hudX = editorPreviewActive
                ? editorPreviewX
                : (float) (HudConfig.lyricX * (getWidth() - baseW * hudScale));
        float hudY = editorPreviewActive
                ? editorPreviewY
                : (float) (HudConfig.lyricY * (getHeight() - baseH * hudScale));
        wpsInterface.setX(hudX);
        wpsInterface.setY(hudY);

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(hudX, hudY, 0);
        api.getGLStateManager().scale(hudScale, hudScale, 1);
        api.getGLStateManager().translate(-hudX, -hudY, 0);

        float songProgress = CloudMusic.player.getCurrentTimeMillis();

        boolean shouldNotDisplayOtherLyrics = this.singleLine.getValue();

        handleSingleLineMode(shouldNotDisplayOtherLyrics);
        if (Reflection.DYNAMIC_ISLAND_SUPPORTED && dynIsland.getValue()) {
//            String property = System.getProperty("ncm.dynIslandLyrics");
            LyricLine currentLine = CloudMusic.currentLyric;
            if (currentLine != null) {

                if (currentLine.isBreakLine) {
                    int i = CloudMusic.lyrics.indexOf(currentLine);

                    if (i > 0)
                        currentLine = CloudMusic.lyrics.get(i - 1);
                    else if (i + 1 < CloudMusic.lyrics.size())
                        currentLine = CloudMusic.lyrics.get(i + 1);
                }

                if (CloudMusic.haveNoWords) {
                    System.setProperty("ncm.dynIslandLyrics", EnumChatColor.WHITE + currentLine.getLyric());
                } else {
                    WordInfo wordInfo = calculateCurrentWordInfo(currentLine, songProgress);

                    String left = EnumChatColor.WHITE + "";
                    String right = EnumChatColor.GRAY + "";

                    int leftEndIdx = wordInfo.currentIndex;
                    int rightStartIdx = wordInfo.currentIndex + 1;

//                    if (rightStartIdx == 1) {
//                        LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
//                        double value = (songProgress - current.timestamp) / (double) (current.duration);
//
//                        if (value < 0) {
//                            rightStartIdx -= 1;
//                            leftEndIdx -= 1;
//                        }
//                    }

                    for (int i = 0; i <= leftEndIdx - 1; i++) {
                        left += currentLine.words.get(i).word;
                    }

                    LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
                    double value = Math.max(0, Math.min(1, (songProgress - current.timestamp) / (double) (current.duration)));

                    String word = current.word;

                    if (value > 0) {
                        int endIndex = word.length() > 1 ? (int) (word.length() * value) : 1;
                        left += word.substring(0, endIndex);
                        right += word.substring(endIndex);
                    } else {
                        right += word;
                    }

                    for (int i = rightStartIdx; i < currentLine.words.size(); i++) {
                        right += currentLine.words.get(i).word;
                    }

                    System.setProperty("ncm.dynIslandLyrics", left + right);
                }
            } else {
                System.setProperty("ncm.dynIslandLyrics", "");
            }
        } else {
            System.setProperty("ncm.dynIslandLyrics", "");

            api.getGLStateManager().pushMatrix();

            StencilClipManager.beginClip(() -> Rect.draw(wpsInterface.getX() - 2, wpsInterface.getY(), wpsInterface.getWidth() + 4, wpsInterface.getHeight(), -1));

            renderAllLyrics(shouldNotDisplayOtherLyrics, songProgress);

            cleanupRender();
            StencilClipManager.endClip();
        }

        if (ClientSettings.DEBUG_MODE) {
            LyricLine currentLine = CloudMusic.currentLyric;
            if (currentLine != null && !CloudMusic.haveNoWords) {
                WordInfo wordInfo = calculateCurrentWordInfo(currentLine, songProgress);

                LyricLine.Word current = currentLine.words.get(wordInfo.currentIndex);
                FontManager.pf28bold.drawStringWithShadow("Current word: " + current.word, 100, 100, -1);
                double value = (songProgress - current.timestamp) / (double) (current.duration);
                FontManager.pf28bold.drawStringWithShadow("Perc: " + value, 100, 120, -1);
                FontManager.pf28bold.drawStringWithShadow("Dur: " + current.duration, 100, 140, -1);
                FontManager.pf28bold.drawStringWithShadow("Pos: " + (songProgress - current.timestamp), 100, 160, -1);
            }
        }

        api.getGLStateManager().popMatrix();
    }

    private boolean shouldRender() {
        return CloudMusic.player != null && !CloudMusic.player.isFinished() && !CloudMusic.lyrics.isEmpty();
    }

    private void handleSingleLineMode(boolean shouldNotDisplayOtherLyrics) {
        if (shouldNotDisplayOtherLyrics && CloudMusic.currentLyric == null) {
            if (!CloudMusic.lyrics.isEmpty()) {
                CloudMusic.currentLyric = CloudMusic.lyrics.get(0);
            }
        }
    }

    private void renderAllLyrics(boolean singleLineMode, float songProgress) {
        synchronized (CloudMusic.lyrics) {
            this.currentBlockOverflows = false;
            if (CloudMusic.lyrics.isEmpty()) {
                return;
            }

            int currentIndex = CloudMusic.lyrics.indexOf(CloudMusic.currentLyric);
            if (currentIndex < 0) {
                return;
            }

            // A duet section is two (rarely three) lines whose timelines genuinely overlap. All of
            // them are the current line at once, so they are laid out and centered as one block:
            // highlighting only one of them makes the other look already-sung, and the "current"
            // line would visibly bounce between the two singers.
            int groupStart = LyricDuetGroups.groupStart(CloudMusic.lyrics, currentIndex);
            int groupEnd = LyricDuetGroups.groupEnd(CloudMusic.lyrics, currentIndex);
            int groupSize = groupEnd - groupStart + 1;

            LyricLayout[] groupLayouts = buildGroupLayouts(groupStart, groupSize, true);
            double blockHeight = measureBlockHeight(groupLayouts);

            double viewportTop = wpsInterface.getY();
            double viewportHeight = wpsInterface.getHeight();
            double viewportBottom = viewportTop + viewportHeight;

            /*
             * 对唱把"当前行"从一行变成两三行，块高很容易超过 HUD 高度：两行各自折行、各自还带一行
             * 译文。块高一旦超过视口，居中摆放会让首行顶端和末行底端同时落在 stencil 之外，再加上
             * calculateEdgeAlpha 会把贴边的行淡到 0（alpha <= .01 直接不画），表现就是"对唱溢出、
             * 有一行整行不见了"。
             *
             * 第一级：丢掉这一组的译文（原文优先）。译文本来就是辅助信息，比整行原文消失好得多。
             * 第二级：仍然放不下就把块顶对齐到视口顶部（而不是让它跑到视口上方），并给这一组的行
             *        一个不透明度下限，保证两位歌手的行都还看得见。
             */
            if (blockHeight > viewportHeight && groupSize > 1) {
                LyricLayout[] compact = buildGroupLayouts(groupStart, groupSize, false);
                double compactHeight = measureBlockHeight(compact);
                if (compactHeight < blockHeight) {
                    groupLayouts = compact;
                    blockHeight = compactHeight;
                }
            }
            this.currentBlockOverflows = blockHeight > viewportHeight;

            // Keep the active lyric block centered. This is especially important when
            // a narrow HUD wraps it into multiple physical text rows.
            double blockTop = this.currentBlockOverflows
                    ? viewportTop
                    : viewportTop + (viewportHeight - blockHeight) * .5;

            if (singleLineMode) {
                // Single-line mode still shows a whole duet: the point of the mode is to hide the
                // lines that are not being sung, and in a duet both of them are.
                double y = blockTop;
                for (int i = 0; i < groupSize; i++) {
                    renderLyricLine(CloudMusic.lyrics.get(groupStart + i), true, y,
                            groupLayouts[i], songProgress);
                    y += groupLayouts[i].rowHeight;
                }
                return;
            }

            // The viewport height, not a fixed number setting, decides how many rows are
            // visible. Lines just outside the clip are kept in the layout pass so their
            // motion and edge fade remain continuous while scrolling.
            double fadePadding = getEdgeFadeSize();
            double y = blockTop;
            for (int i = groupStart - 1; i >= 0; i--) {
                LyricLayout layout = createLyricLayout(CloudMusic.lyrics.get(i));
                y -= layout.rowHeight;
                if (y + layout.visualHeight < viewportTop - fadePadding) {
                    break;
                }
                renderLyricLine(CloudMusic.lyrics.get(i), false, y, layout, songProgress);
            }

            y = blockTop;
            for (int i = 0; i < groupSize; i++) {
                renderLyricLine(CloudMusic.lyrics.get(groupStart + i), true, y,
                        groupLayouts[i], songProgress);
                y += groupLayouts[i].rowHeight;
            }

            for (int i = groupEnd + 1; i < CloudMusic.lyrics.size(); i++) {
                if (y > viewportBottom + fadePadding) {
                    break;
                }
                LyricLine line = CloudMusic.lyrics.get(i);
                LyricLayout layout = createLyricLayout(line);
                renderLyricLine(line, false, y, layout, songProgress);
                y += layout.rowHeight;
            }
        }
    }

    private void renderLyricLine(LyricLine line, boolean isCurrent,
                                 double targetY, LyricLayout layout, float songProgress) {
        double animatedY = updateLineY(line, targetY);
        double edgeAlpha = calculateEdgeAlpha(animatedY, layout.visualHeight);
        if (isCurrent && this.currentBlockOverflows) {
            // 块比 HUD 还高时，靠边那一行的中心必然落在淡出带里，边缘淡出会把它整行淡到不画。
            // 对唱段落里那就是"另一位歌手的整句消失"，比让它半透明地挤在边上糟糕得多。
            edgeAlpha = Math.max(edgeAlpha, MIN_OVERFLOW_BLOCK_ALPHA);
        }
        if (edgeAlpha <= .01) {
            return;
        }

        LyricRenderInfo renderInfo = new LyricRenderInfo();
        renderInfo.yPosition = animatedY;
        renderInfo.visibilityAlpha = edgeAlpha;
        updateLyricAnimation(line, isCurrent, edgeAlpha);
        renderLyricText(line, renderInfo, isCurrent, layout, songProgress);
    }

    private double updateLineY(LyricLine line, double targetY) {
        double resetDistance = Math.max(wpsInterface.getHeight() * 1.5, 160.0);
        if (line.offsetY == Double.MIN_VALUE || Math.abs(line.offsetY - targetY) > resetDistance) {
            line.offsetY = targetY;
        } else if (this.graceScroll.getValue()) {
            // Higher smoothness follows more gently while still converging after a line change.
            float follow = 0.35f - HudConfig.scrollSmoothness * 0.26f;
            line.offsetY = Interpolations.interpolate(line.offsetY, targetY, follow);
        } else {
            line.offsetY = targetY;
        }
        return line.offsetY;
    }

    private double calculateEdgeAlpha(double y, double visualHeight) {
        if (!HudConfig.lyricEdgeFadeEnabled) {
            return 1.0;
        }
        double top = wpsInterface.getY();
        double bottom = top + wpsInterface.getHeight();
        double center = y + visualHeight * .5;
        double distanceToEdge = Math.min(center - top, bottom - center);
        double progress = Math.max(0.0, Math.min(1.0, distanceToEdge / getEdgeFadeSize()));
        // smoothstep removes the visible boundary created by a linear alpha ramp.
        return progress * progress * (3.0 - 2.0 * progress);
    }

    private double getEdgeFadeSize() {
        // Do not let the fade consume more than half of a compact viewport.
        return Math.max(6.0, Math.min(HudConfig.edgeFadeSize, wpsInterface.getHeight() * .48));
    }

    /**
     * 当前歌词块（对唱时是整组）是否高于 HUD 视口。
     *
     * <p>为真时块顶对齐视口顶部，并且这一组的行不允许被边缘淡出吃掉。用字段而不是给
     * {@link #renderLyricLine} 加参数：它有四个调用点，其中三个与这件事无关。渲染只在渲染线程上
     * 发生，每帧在 {@link #renderAllLyrics} 开头重置。</p>
     */
    private boolean currentBlockOverflows;

    /** 对唱组内每一行的排版。{@code allowSecondary} 为假时不给译文留行，用于压缩过高的块。 */
    private LyricLayout[] buildGroupLayouts(int groupStart, int groupSize, boolean allowSecondary) {
        LyricLayout[] layouts = new LyricLayout[groupSize];
        for (int i = 0; i < groupSize; i++) {
            layouts[i] = createLyricLayout(CloudMusic.lyrics.get(groupStart + i), allowSecondary);
        }
        return layouts;
    }

    /**
     * 一组行叠起来的可视高度。
     *
     * <p>最后一行只贡献字形高度：它后面那段行距不属于眼睛读到的"块"。</p>
     */
    private static double measureBlockHeight(LyricLayout[] layouts) {
        double height = 0.0;
        double advance = 0.0;
        for (LyricLayout layout : layouts) {
            height = advance + layout.visualHeight;
            advance += layout.rowHeight;
        }
        return height;
    }

    private LyricLayout createLyricLayout(LyricLine line) {
        return createLyricLayout(line, true);
    }

    private LyricLayout createLyricLayout(LyricLine line, boolean allowSecondary) {
        // Only real word timestamps may enter the KTV renderer. A plain LRC can carry
        // word tokens after parsing, but without durations every token resolves at once and
        // would incorrectly recolour the whole line.
        KaraokeLayoutBuilder.Layout karaokeLayout = line.hasTimedWords() ? createKaraokeLayout(line) : null;
        String[] primaryLines = karaokeLayout == null
                ? fitText(getFontRenderer(), line.getLyric()) : karaokeLayout.primaryLines;
        String secondaryLyric = allowSecondary && hasSecondaryLyrics() ? getSecondaryLyrics(line) : "";
        String[] secondaryLines = secondaryLyric.isEmpty()
                ? new String[0] : fitText(getSmallFontRenderer(), secondaryLyric);
        return new LyricLayout(primaryLines, secondaryLines, karaokeLayout);
    }

    /**
     * Keeps every YRC word's timing while producing the same responsive physical rows
     * used by the HUD. A word that is wider than the HUD is split into timed fragments,
     * so KTV highlighting never overflows when the widget is resized.
     */
    private KaraokeLayoutBuilder.Layout createKaraokeLayout(LyricLine line) {
        return KaraokeLayoutBuilder.build(getFontRenderer(), line, getContentWidth());
    }

    private String[] fitText(CFontRenderer font, String text) {
        return KaraokeLayoutBuilder.fitText(font, text, getContentWidth());
    }
    private double getContentWidth() {
        return Math.max(40.0, wpsInterface.getWidth() - CONTENT_HORIZONTAL_PADDING * 2.0);
    }

    private double getContentLeft() {
        return wpsInterface.getX() + CONTENT_HORIZONTAL_PADDING;
    }
    private void updateLyricAnimation(LyricLine line, boolean isCurrent, double edgeAlpha) {
        float idleAlpha = (float) (HudConfig.normalOpacity * (.72 + .28 * edgeAlpha));
        line.lineAlpha = Interpolations.interpolate(
                line.lineAlpha,
                isCurrent ? 1f : idleAlpha,
                isCurrent ? .14f : .10f
        );
    }

    /**
     * 正在绘制的这一行的对齐覆盖。对唱行左右分列时为 {@code "Left"} / {@code "Right"}，
     * 其余行为 {@code null}（沿用用户在配置里选的对齐方式，行为与改动前完全一致）。
     *
     * <p>用字段而不是给下游一串方法都加参数：OSD 的对齐值在 KTV 逐字、整行文本、译文三条
     * 绘制路径里各取一次，而这三条路径下游都拿不到 {@link LyricLine}。渲染只在渲染线程上发生，
     * 且在 {@link #renderLyricText} 里成对设置/清除，不存在跨行泄漏。</p>
     */
    private String duetAlignOverride;

    /** 本帧应当使用的对齐方式。 */
    private String activeAlignMode() {
        return duetAlignOverride != null ? duetAlignOverride : this.alignMode.getValue();
    }

    /**
     * 对唱行的对齐覆盖：偶数序号偏左、奇数序号偏右；独唱行返回 null。
     *
     * <p>返回的仍然是 {@code "Left"} / {@code "Right"}，真正偏多少由 {@link #applyDuetSpread}
     * 按配置往中间收，所以这里不需要知道分列幅度。</p>
     */
    private static String duetAlignMode(LyricLine line) {
        if (line == null || line.duetSlot < 0) return null;
        return LyricDuetGroups.rightAligned(line.duetSlot) ? "Right" : "Left";
    }

    private void renderLyricText(LyricLine line, LyricRenderInfo renderInfo,
                                 boolean isCurrent, LyricLayout layout,
                                 float songProgress) {
        // A KaraokeLayout is created only from parsed word-level timing. Whenever that
        // timing is available the current OSD line must use the one KTV renderer; an
        // optional legacy effect must never be able to replace its left-to-right fill.
        boolean useKaraoke = isCurrent && layout.karaokeLayout != null;
        int effectAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha);

        // 对唱段落：两位歌手同时在唱，两行分别偏左、偏右，一眼能分出这一句是谁的。
        // 偏移幅度由 HudConfig.osdDuetSpread 决定（见 applyDuetSpread），默认往中间收一些。
        this.duetAlignOverride = duetAlignMode(line);
        try {
            if (useKaraoke) {
                // Base text, glow, stencil clips and moving highlight must share one transform.
                // Otherwise current-line scaling makes the coloured layer drift away from the glyphs.
                renderKaraokeBlock(layout, renderInfo.yPosition, songProgress, effectAlpha);
            } else {
                renderWrappedPrimary(layout.primaryLines, renderInfo.yPosition,
                        getConfiguredLyricColor(line, effectAlpha), isCurrent, songProgress, true);
            }

            if (layout.secondaryLines.length > 0) {
                int secondaryAlpha = (int) (255 * line.lineAlpha * renderInfo.visibilityAlpha
                        * HudConfig.secondaryOpacity);
                double secondaryY = renderInfo.yPosition + layout.primaryVisualHeight
                        + PRIMARY_TO_SECONDARY_SPACING;
                renderWrappedSecondary(layout.secondaryLines, secondaryY,
                        getConfiguredLyricColor(line, secondaryAlpha), isCurrent, songProgress);
            }
        } finally {
            this.duetAlignOverride = null;
        }
    }

    /*
     * ===== 当前行缩放必须留在裁剪框内 =====
     *
     * onRender 里那层 stencil 只覆盖 [widgetX - 2, widgetX + width + 2]，而内容区还要各让出
     * CONTENT_HORIZONTAL_PADDING(12)，所以内容区外侧只剩十来个逻辑像素的余量。
     *
     * 「当前行缩放」(currentLineScale 0.95~1.16) 是绕某个锚点等比放大的，放大量 = 块宽 ×
     * (倍率-1)。原来的锚点是内容区中心 / 文本自身中心：
     *   · 居中对齐的行本来就落在中间，放大量向两侧各分一半，勉强还在余量里；
     *   · <b>对唱行被强制贴左或贴右</b>，放大量全部落到同一侧。HUD 宽度 900 时内容区 876，
     *     倍率 1.16 就是 70 像素的单侧外扩，远超余量 —— 表现正是"对唱行溢出、外侧的字不显示"。
     *
     * 修法与全屏歌词页一致（LyricsPanelGeometry.clampScaleAnchorX）：先把倍率夹到"放大后仍装得下"
     * 的上限，再把锚点夹到能让整块完整留在裁剪框内的区间。贴边的行只向内侧生长，居中的行行为不变。
     */

    /** stencil 裁剪框的左边界，内缩 1 像素留给字形抗锯齿。 */
    private double clipLeftBound() {
        return wpsInterface.getX() - 1.0;
    }

    /** stencil 裁剪框的右边界，内缩 1 像素。 */
    private double clipRightBound() {
        return wpsInterface.getX() + wpsInterface.getWidth() + 3.0;
    }

    /** 放大后仍能整块留在裁剪框内的最大倍率；永不小于 1，也永不放大原本要缩小的行。 */
    private double clampScaleToClip(double blockWidth, double scale) {
        return KaraokeAnimationMath.clampScaleToWidth(blockWidth, scale,
                clipRightBound() - clipLeftBound());
    }

    private double clampScaleAnchorX(double blockLeft, double blockWidth, double scale) {
        return LyricsPanelGeometry.clampScaleAnchorX(blockLeft, blockWidth, scale,
                clipLeftBound(), clipRightBound());
    }

    private void renderKaraokeBlock(LyricLayout layout, double y,
                                     float songProgress, int effectAlpha) {
        LyricVisualStyle visualStyle = getLyricVisualStyle(true, songProgress);
        // 整块的真实横向范围（含对齐后的落点），而不是整个内容区：居中对齐的短行不该被
        // 当成占满整行来限制倍率。
        double blockLeft = karaokeBlockLeft(layout);
        double blockWidth = karaokeBlockWidth(layout);
        double scale = clampScaleToClip(blockWidth, visualStyle.scale);
        visualStyle = visualStyle.withScale(scale);

        double centerX = clampScaleAnchorX(blockLeft, blockWidth, scale);
        double centerY = y + layout.primaryVisualHeight * .5;

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(scale, scale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderKaraokeCharacters(layout, y, songProgress, effectAlpha, visualStyle);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /** KTV 块中最靠左的物理行左边缘。 */
    private double karaokeBlockLeft(LyricLayout layout) {
        KaraokeLayoutBuilder.Layout karaokeLayout = layout.karaokeLayout;
        if (karaokeLayout == null || karaokeLayout.primaryLines.length == 0) {
            return getContentLeft();
        }
        String alignMode = activeAlignMode();
        double left = Double.MAX_VALUE;
        for (int i = 0; i < karaokeLayout.primaryLines.length; i++) {
            left = Math.min(left, alignmentX(karaokeLayout.lineAdvance(i), alignMode));
        }
        return left == Double.MAX_VALUE ? getContentLeft() : left;
    }

    /** KTV 块的横向跨度：最右行的右边缘减去最左行的左边缘。 */
    private double karaokeBlockWidth(LyricLayout layout) {
        KaraokeLayoutBuilder.Layout karaokeLayout = layout.karaokeLayout;
        if (karaokeLayout == null || karaokeLayout.primaryLines.length == 0) {
            return getContentWidth();
        }
        String alignMode = activeAlignMode();
        double left = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        for (int i = 0; i < karaokeLayout.primaryLines.length; i++) {
            double advance = karaokeLayout.lineAdvance(i);
            double lineLeft = alignmentX(advance, alignMode);
            left = Math.min(left, lineLeft);
            right = Math.max(right, lineLeft + advance);
        }
        return right <= left ? 0.0 : right - left;
    }

    /**
     * 当前 OSD 歌词的 KTV 渲染入口：按「字词」为单位走离屏合成，与全屏歌词页同一套做法。
     *
     * <p>为什么要换成离屏合成，见 {@link KaraokeTextCompositor} 的类注释：旧的逐条羽化画法
     * 会把同一个抗锯齿字形往屏幕上叠十几遍，而且每一条羽化带的 scissor 裁剪框都被向外取整到整
     * 屏幕像素，于是必然又糊又带硬边竖块（就是肉眼看到的"叠加层模糊、马赛克感"）。</p>
     *
     * <p>同一时刻只有一个字词正在被唱：它前面的字词已经唱完（直接一次高亮 + 一次光晕），
     * 后面的还没唱（直接一次未唱底色）。因此每帧最多只有一个字词需要离屏纹理，成本与全屏歌词
     * 页完全一致。</p>
     */
    private void renderKaraokeCharacters(LyricLayout layout, double y, float songProgress,
                                         int effectAlpha, LyricVisualStyle visualStyle) {
        if (KaraokeTextCompositor.isUnavailable()) {
            renderKaraokeCharactersFallback(layout, y, songProgress, effectAlpha, visualStyle);
            return;
        }

        KaraokeLayoutBuilder.Layout karaokeLayout = layout.karaokeLayout;
        // 未唱底色始终使用普通歌词色；激活色只由已唱部分负责，否则整句看起来都像已经唱过了。
        int baseColor = getBaseLyricColor((int) (effectAlpha * .70f));
        int highlightColor = getCurrentLyricColor(effectAlpha);
        double lineStride = getFontRenderer().getHeight() + PRIMARY_LINE_SPACING;

        for (KaraokeLayoutBuilder.Segment segment : karaokeLayout.segments) {
            if (segment.text.isEmpty()) {
                continue;
            }

            double segmentX = alignmentX(karaokeLayout.lineAdvance(segment.lineIndex),
                    activeAlignMode()) + segment.offsetX;
            double segmentY = y + segment.lineIndex * lineStride;

            double rawProgress = getRawKaraokeProgress(segment.word, songProgress);
            /*
             * 一个 timed word 可能被折行拆成多个 Segment。characterOffset 把物理片段映射回原始
             * timed word，因此填涂前沿跨字、跨换行都保持连续。
             */
            double wordWidth = Math.max(.05, getFontRenderer().getStringWidthD(segment.word.word));
            double segmentWordOffset = getWordPrefixWidth(segment.word.word, segment.characterOffset);
            double segmentFront = clampKaraokeWidth(wordWidth * rawProgress - segmentWordOffset,
                    0.0, segment.width);
            double characterTimeline = Math.max(0.0, Math.min(1.0, rawProgress))
                    * segment.totalCharacterCount;

            renderKaraokeSegment(segment, segmentX, segmentY, segmentFront, characterTimeline,
                    baseColor, highlightColor, visualStyle);
        }
    }

    /**
     * 画一个字词：未唱 / 正在唱 / 已唱完三种状态各自只有必要的那几次绘制。
     *
     * <p>逐字放大在这里是「按字词鼓一下」：倍率与锚点在进入之前一次算好，本字词的未唱底色层和
     * 已唱合成层共用同一份变换。以前是每个字各自绕自身中心放大，遮罩与字形分属两份不同的变换，
     * 于是既有重影又在已唱/未唱交界处脱节。布局推进量仍是未放大的，换行不会抖动。</p>
     */
    private void renderKaraokeSegment(KaraokeLayoutBuilder.Segment segment, double x, double y,
                                      double front, double characterTimeline,
                                      int baseColor, int highlightColor,
                                      LyricVisualStyle visualStyle) {
        if (segment.width <= .05) {
            return;
        }

        double progress = clampKaraokeWidth(front / segment.width, 0.0, 1.0);
        boolean sung = front >= segment.width - .02;
        boolean unsung = front <= .02;

        boolean pulseEnabled = HudConfig.osdKaraokeEmphasisEnabled
                && HudConfig.osdKaraokePulseStrength > .001f;
        // 唱到这个字词的一瞬间鼓一下，唱完立刻回到 1.0 倍：长期停在一个非整数倍率上会让字形
        // 一直处于重采样状态（字形图集是按 2 倍超采样、精确 0.5 倍缩小来保证锐利的）。
        double pulse = pulseEnabled ? Math.sin(Math.PI * smoothStep(progress)) : 0.0;
        double scale = 1.0 + Math.min(.15, Math.max(0.0, HudConfig.osdKaraokePulseStrength)) * pulse;
        boolean scaled = scale > 1.0005;
        double anchorX = x + segment.width * .5;
        // 用 getFontHeight() 而不是取整过的 getHeight()：锚点偏离字形真实中心会让放大后的字形
        // 与合成四边形在纵向上错开半个像素。
        double anchorY = y + getFontRenderer().getFontHeight() * .5;

        if (scaled) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(anchorX, anchorY, 0);
            api.getGLStateManager().scale(scale, scale, 1);
            api.getGLStateManager().translate(-anchorX, -anchorY, 0);
        }
        try {
            if (!sung) {
                // 唱完的字词不再画未唱底色：底色与激活色不同时，抗锯齿边缘会透出一圈底色（描边感）。
                bigFrString(segment.text, x, y, baseColor);
            }

            if (sung) {
                // 已经唱完，不需要遮罩：整段一次光晕 + 一次字形，一共两次绘制。
                drawKaraokeSegmentGlow(segment.text, x, y, highlightColor, visualStyle, 1.0);
                bigFrString(segment.text, x, y, highlightColor);
            } else if (!unsung
                    && !compositeKaraokeSegment(segment, x, y, front, characterTimeline,
                            highlightColor, visualStyle)) {
                // 离屏路径在当前显卡/Mod 组合下不可用：这一帧用单次 scissor 硬边填涂顶一下，
                // 下一帧起 isUnavailable() 为真，整体回到原来的逐条羽化画法。
                renderKaraokeLayer(segment.text, x, y, 0.0, front, highlightColor);
            }
        } finally {
            if (scaled) {
                api.getGLStateManager().popMatrix();
            }
        }
    }

    /**
     * 把正在唱的字词合成到屏幕上。
     *
     * <p>纹理尺寸 = 字词宽度 + 两侧发光留白，并向上对齐到固定台阶（见
     * {@link KaraokeAnimationMath#alignTextureSize}），这样换字词时几乎不会重建 framebuffer。
     * 合成四边形的尺寸就等于纹理的逻辑尺寸，纹理与四边形一一对应，不引入额外重采样。</p>
     *
     * @return {@code false} 表示离屏路径不可用，调用方需要兜底
     */
    private boolean compositeKaraokeSegment(KaraokeLayoutBuilder.Segment segment, double x, double y,
                                            double front, double characterTimeline,
                                            int highlightColor, LyricVisualStyle visualStyle) {
        final int glowPad = karaokeGlowPadding(visualStyle);
        int logicalWidth = KaraokeAnimationMath.alignTextureSize(segment.width + glowPad * 2 + 2.0, 8);
        int logicalHeight = KaraokeAnimationMath.alignTextureSize(
                getFontRenderer().getFontHeight() + 6.0 + glowPad * 2, 4);
        int superSample = KaraokeAnimationMath.superSample(
                karaokeScreenScale() * Math.max(.1, visualStyle.scale));

        double gradientWidth = KaraokeAnimationMath.maskGradientWidth(HudConfig.osdKaraokeTransitionWidth,
                getFontRenderer().getFontHeight(), segment.width);
        // 遮罩整体右移 glowPad（与 base 纹理里的字形同步），左侧那段留白一并放行，
        // 否则字词最左边那个字向左扩散的光晕会被遮罩切掉。
        double solidRight = glowPad + KaraokeAnimationMath.maskSolidRight(front, gradientWidth);
        double frontRight = glowPad + Math.max(0.0, front);

        /*
         * CFontRenderer.drawString 内部先 y -= 2 再 scale(0.5)，所以字形墨迹的顶端在 y - 2。
         * 要让墨迹顶端落在纹理内的 glowPad 处，drawString 的 y 就得是 glowPad + 2；
         * 对应地，合成四边形的左上角要反向平移到 (x - glowPad, y - 2 - glowPad)。
         */
        return karaokeCompositor.composite(x - glowPad, y - 2.0 - glowPad,
                logicalWidth, logicalHeight, superSample, highlightColor, solidRight, frontRight,
                () -> paintKaraokeSegmentInk(segment, glowPad, glowPad + 2.0, characterTimeline,
                        highlightColor, visualStyle));
    }

    /**
     * 在离屏纹理里画字词的墨迹：逐字亮起的光晕 + 一次整段字形。
     *
     * <p>字形只画一遍（而不是逐字画）：{@code drawString} 自带 kerning，逐字画会丢掉字距并沿行
     * 累积偏移，最终让字形与遮罩错开。光晕则必须逐字画，因为它的亮度只跟"这个字已经唱了多久"
     * 有关；它和字形一起进 base 纹理，最终被同一张遮罩裁掉未唱的部分，所以光晕是随着填涂前沿
     * 一个字一个字亮起来的。</p>
     */
    private void paintKaraokeSegmentInk(KaraokeLayoutBuilder.Segment segment, double originX,
                                        double originY, double characterTimeline,
                                        int highlightColor, LyricVisualStyle visualStyle) {
        int offset = 0;
        int characterIndex = 0;
        double prefixWidth = 0.0;
        while (offset < segment.text.length()) {
            int next = segment.text.offsetByCodePoints(offset, 1);
            // 用「前缀宽度之差」步进，理由同上：逐字宽度直接相加会丢掉 kerning。
            double nextPrefixWidth = getFontRenderer().getStringWidthD(segment.text.substring(0, next));
            double characterProgress = getCharacterKaraokeProgress(characterTimeline,
                    segment.characterOffset + characterIndex);
            drawKaraokeSegmentGlow(segment.text.substring(offset, next), originX + prefixWidth,
                    originY, highlightColor, visualStyle, characterProgress);
            prefixWidth = nextPrefixWidth;
            offset = next;
            characterIndex++;
        }

        // 离屏纹理里不画阴影：base 纹理的清屏色与墨色同色，深色阴影会在纹理边缘混出脏边。
        // 未唱底色层仍然带阴影，而它就压在合成层下面，所以阴影不会消失。
        getFontRenderer().drawString(segment.text, originX, originY, highlightColor);
    }

    /**
     * 离屏纹理四周需要留出的发光留白。
     *
     * <p>光晕是围绕字形向外扩的，不留白就会被纹理边界切成一个方块。留白必须是整数，这样
     * 合成四边形与纹理仍然一一对应。发光完全关闭时留 1 像素，只为避免最外圈的抗锯齿被纹理
     * 边界和线性过滤啃掉。</p>
     */
    private int karaokeGlowPadding(LyricVisualStyle visualStyle) {
        if (!HudConfig.osdKaraokeEmphasisEnabled) {
            return 1;
        }
        // KARAOKE_GLOW_MAX_DRIVE 是 drawKaraokeSegmentGlow 里 drive 的最大值（characterProgress = 1）。
        double glow = KARAOKE_GLOW_MAX_DRIVE * visualStyle.glowStrength
                * getKaraokeAccent(HudConfig.osdKaraokeGlowStrength);
        double bloom = KARAOKE_GLOW_MAX_DRIVE * visualStyle.bloomStrength
                * getKaraokeAccent(HudConfig.osdKaraokeBloomStrength);
        double baseRadius = Math.max(.35, visualStyle.glowRadius);
        double radius = 0.0;
        if (glow > .004) {
            radius = Math.max(radius, KaraokeAnimationMath.glowRingRadius(baseRadius, glow));
        }
        if (bloom > .004) {
            radius = Math.max(radius, KaraokeAnimationMath.bloomOuterRadius(baseRadius));
        }
        return KaraokeAnimationMath.glowPadding(radius);
    }

    /**
     * 一个字（或整段）的光晕，亮度只取决于它已经唱了多久。
     *
     * <p>系数与原来的 {@code renderKaraokeGlow} 完全一致（{@code strength = .18 + .34 * intensity}），
     * 所以 OSD 发光/晕染两个滑块的手感没有变化；区别只在于它现在画进离屏纹理、由遮罩负责显隐，
     * 不再需要为每一条羽化带各画一圈。</p>
     */
    private void drawKaraokeSegmentGlow(String text, double x, double y, int color,
                                        LyricVisualStyle visualStyle, double intensity) {
        if (!HudConfig.osdKaraokeEmphasisEnabled || RGBA.alpha(color) <= 0) {
            return;
        }
        double drive = KARAOKE_GLOW_BASE_DRIVE
                + KARAOKE_GLOW_PROGRESS_DRIVE * Math.max(0.0, Math.min(1.0, intensity));
        double glow = drive * visualStyle.glowStrength * getKaraokeAccent(HudConfig.osdKaraokeGlowStrength);
        double bloom = drive * visualStyle.bloomStrength * getKaraokeAccent(HudConfig.osdKaraokeBloomStrength);
        if (glow <= .004 && bloom <= .004) {
            return;
        }

        double baseRadius = Math.max(.35, visualStyle.glowRadius);
        if (glow > .004) {
            drawKaraokeGlowRing(text, x, y, color,
                    KaraokeAnimationMath.glowRingRadius(baseRadius, glow), glow * 1.12);
        }
        if (bloom > .004) {
            drawKaraokeGlowRing(text, x, y, color,
                    KaraokeAnimationMath.bloomInnerRadius(baseRadius), bloom * .52);
            drawKaraokeGlowRing(text, x, y, color,
                    KaraokeAnimationMath.bloomOuterRadius(baseRadius), bloom * .24);
        }
    }

    /**
     * 兜底：原来的逐字渲染（未唱底色 + 按前沿裁剪的羽化高亮）。
     *
     * <p>只在离屏合成不可用时使用。它的画质问题（叠加层模糊、前沿马赛克）是 scissor 向外取整到
     * 整屏幕像素造成的、无法在这条链路里消除，所以只作为"功能不丢"的保底，不再是默认路径。</p>
     *
     * <p>它的底色、高亮和裁剪矩形都在同一个逐字缩放矩阵内产生，遮罩会跟着字形一起放大，
     * 所以不存在"遮罩放大、字形没放大"的重影与脱节；剩下的只是画质问题。</p>
     */
    private void renderKaraokeCharactersFallback(LyricLayout layout, double y, float songProgress,
                                                 int effectAlpha, LyricVisualStyle visualStyle) {
        KaraokeLayoutBuilder.Layout karaokeLayout = layout.karaokeLayout;
        // 未唱底色始终使用普通歌词色；激活色只由下面裁剪过的高亮层负责，
        // 否则整句看起来都像已经唱过了。
        int baseColor = getBaseLyricColor((int) (effectAlpha * .70f));
        int highlightColor = getCurrentLyricColor(effectAlpha);
        double lineStride = getFontRenderer().getHeight() + PRIMARY_LINE_SPACING;
        // 羽化窗口必须按 OSD 自己的字号收敛。osdKaraokeTransitionWidth 的量程(4~32)是照全屏
        // pf65bold 定的，直接套在 pf28bold 上时一个窗口就盖住整整一到两个汉字：整段已唱文本
        // 于是全部落在半透明的羽化带里、永远到不了实心色，看上去就是"KTV 染色只染了一半"。
        double featherLimit = Math.min(Math.max(1.5, HudConfig.osdKaraokeTransitionWidth),
                Math.max(1.5, getFontRenderer().getFontHeight() * .42));

        for (KaraokeLayoutBuilder.Segment segment : karaokeLayout.segments) {
            if (segment.text.isEmpty()) {
                continue;
            }

            double segmentX = alignmentX(karaokeLayout.lineAdvance(segment.lineIndex),
                    activeAlignMode()) + segment.offsetX;
            double segmentY = y + segment.lineIndex * lineStride;

            double rawProgress = getRawKaraokeProgress(segment.word, songProgress);
            double characterTimeline = Math.max(0.0, Math.min(1.0, rawProgress))
                    * segment.totalCharacterCount;

            /*
             * 一个 timed word 可能被拆成多个 KaraokeSegment。characterOffset 把物理片段映射
             * 回原始 timed word，因此填涂前沿跨字、跨换行都保持连续。
             */
            double wordWidth = Math.max(.05, getFontRenderer().getStringWidthD(segment.word.word));
            double segmentWordOffset = getWordPrefixWidth(segment.word.word, segment.characterOffset);
            double segmentFront = clampKaraokeWidth(wordWidth * rawProgress - segmentWordOffset,
                    0.0, segment.width);
            // 羽化窗口按整个片段的前沿计算，这样它可以跨越字与字的边界，
            // 不会因为改成逐字绘制而在每个字里各自重新淡入一次。
            double featherWidth = Math.min(featherLimit, segmentFront);

            int offset = 0;
            int characterIndex = 0;
            double prefixWidth = 0.0;
            while (offset < segment.text.length()) {
                int next = segment.text.offsetByCodePoints(offset, 1);
                String character = segment.text.substring(offset, next);
                // 用「前缀宽度之差」步进：drawString 会加入 kerning，逐字宽度直接相加会丢掉
                // 字距调整并沿行累积偏移，最终让高亮层与字形错开。
                double nextPrefixWidth = getFontRenderer().getStringWidthD(segment.text.substring(0, next));
                double characterWidth = nextPrefixWidth - prefixWidth;
                double characterProgress = getCharacterKaraokeProgress(characterTimeline,
                        segment.characterOffset + characterIndex);
                renderKaraokeCharacter(character, segmentX + prefixWidth, segmentY, characterWidth,
                        segmentFront - prefixWidth, featherWidth, characterProgress,
                        baseColor, highlightColor, visualStyle);

                prefixWidth = nextPrefixWidth;
                offset = next;
                characterIndex++;
            }
        }
    }

    /**
     * 单个字的完整绘制：一次未唱底色 + 一次裁剪过的已唱高亮，两者共用同一个逐字缩放矩阵。
     *
     * @param front        填涂前沿相对本字左边缘的位置，可能为负（还没到）或大于字宽（已唱完）
     * @param featherWidth 羽化窗口宽度，按整个片段的前沿计算，允许跨字边界
     */
    private void renderKaraokeCharacter(String character, double x, double y, double width,
                                        double front, double featherWidth, double characterProgress,
                                        int baseColor, int highlightColor,
                                        LyricVisualStyle visualStyle) {
        if (width <= .05) {
            return;
        }

        boolean emphasisEnabled = HudConfig.osdKaraokeEmphasisEnabled
                && HudConfig.osdKaraokePulseStrength > .001f;
        /*
         * 强调是"唱到这个字的一瞬间鼓一下"，而不是"唱过就一直保持放大"。
         *
         * 保持放大会让整段已唱文本长期停在一个非整数倍率上。字形图集是按 2 倍超采样、再以
         * 精确 0.5 倍缩小来保证锐利的，任何额外的非整数倍率都会重新采样成一团发虚的墨；而且
         * 每个字是绕自身中心放大的，放大后必然压到相邻字上，于是既模糊又带重影。改成脉冲后
         * 只有正在唱的那一个字被放大，唱完立刻回到 1.0 倍恢复锐利。
         */
        double emphasis = emphasisEnabled
                ? Math.sin(Math.PI * smoothStep(characterProgress))
                : 0.0;
        // 这一点放大量量化到 1/128，可以稳定 1.8.9 HUD 上的次像素采样。
        double requestedScale = 1.0
                + Math.min(.085, Math.max(0.0, HudConfig.osdKaraokePulseStrength)) * emphasis;
        double characterScale = Math.round(requestedScale * 128.0) / 128.0;
        boolean scaled = characterScale > 1.0005;

        if (scaled) {
            double centerX = x + width * .5;
            // 用 getFontHeight() 而不是取整过的 getHeight()：锚点偏离字形真实中心会让
            // 放大后的字形与裁剪框在纵向上错开半个像素。
            double centerY = y + getFontRenderer().getFontHeight() * .5;
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().scale(characterScale, characterScale, 1);
            api.getGLStateManager().translate(-centerX, -centerY, 0);
        }
        try {
            bigFrString(character, x, y, baseColor);
            renderKaraokeSweep(character, x, y, front, featherWidth, width,
                    highlightColor, visualStyle, emphasis);
        } finally {
            if (scaled) {
                api.getGLStateManager().popMatrix();
            }
        }
    }

    /**
     * 把本字已唱的部分画成实心 + 前沿羽化。羽化完全位于真实播放前沿的内侧，
     * 并且窗口是按整个片段算出来的，所以前沿的软边可以横跨字与字的边界。
     */
    private void renderKaraokeSweep(String text, double x, double y, double front,
                                    double featherWidth, double textWidth,
                                    int highlightColor, LyricVisualStyle visualStyle,
                                    double emphasis) {
        double paintedWidth = Math.min(front, textWidth);
        if (paintedWidth <= .05 || textWidth <= .05) {
            return;
        }

        // 光晕整段只画一次，裁剪到真实已唱区域。以前每条羽化带都自带一圈光晕(8 次偏移绘制)，
        // 十条就是八十次同字形叠加，前沿必然过曝糊成一团。
        renderKaraokeGlowLayer(text, x, y, 0.0, paintedWidth, highlightColor,
                .18 + .30 * emphasis, visualStyle);

        double solidRight = KaraokeAnimationMath.solidRight(front, featherWidth, textWidth);
        if (solidRight > .05) {
            renderKaraokeLayer(text, x, y, 0.0, solidRight, highlightColor);
        }
        if (featherWidth <= .01) {
            return;
        }

        // 羽化带条数按它在屏幕上真正占几个像素来定，避免亚像素窄条被取整撑开后互相重叠。
        int steps = KaraokeAnimationMath.featherSteps(featherWidth,
                karaokeScreenScale() * visualStyle.scale, KARAOKE_FEATHER_STEPS);
        double[] strip = new double[2];
        for (int step = 0; step < steps; step++) {
            if (!KaraokeAnimationMath.featherStrip(front, featherWidth, textWidth,
                    step, steps, strip)) {
                continue;
            }
            double distanceBehindFront = 1.0 - step / (double) steps;
            double opacity = .16 + .84 * smoothStep(distanceBehindFront);
            renderKaraokeLayer(text, x, y, strip[0], strip[1],
                    multiplyAlpha(highlightColor, opacity));
        }
    }

    /** 一个逻辑单位在屏幕上对应几个像素：GUI 缩放 × 本 HUD 的缩放。 */
    private double karaokeScreenScale() {
        double hudScale = editorPreviewActive ? editorPreviewScale : HudConfig.lyricScale;
        return Math.max(.5, RenderSystem.getScaleFactor() * Math.max(.1, hudScale));
    }

    /** Returns the rendered advance from the start of a timed word to a code-point offset. */
    private double getWordPrefixWidth(String text, int codePointOffset) {
        if (text == null || text.isEmpty() || codePointOffset <= 0) {
            return 0.0;
        }
        int codePointCount = text.codePointCount(0, text.length());
        int safeOffset = Math.min(codePointOffset, codePointCount);
        int end = text.offsetByCodePoints(0, safeOffset);
        return getFontRenderer().getStringWidthD(text.substring(0, end));
    }

    private double clampKaraokeWidth(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 已唱高亮层的纵向裁剪范围。
     *
     * <p>{@code CFontRenderer.drawString} 内部先 {@code y -= 2} 再 {@code scale(0.5)}，因此一个
     * 字形实际占据 {@code [y - 2, y - 2 + fontHeight * 0.5]}，也就是
     * {@code [y - 2, y + getFontHeight() + 2.25]}。旧的裁剪框是
     * {@code [y - 1, y - 1 + getHeight() + 4]}——上边界比墨迹顶端低了一个逻辑像素，而且行高还被
     * {@code getHeight()} 向下取整过。结果是已唱高亮层的顶部被削平、未唱底色层却是完整的，
     * 两层叠在一起就表现为"KTV 染色显示不全"。这里按真实墨迹盒给出上下各约一像素的余量。</p>
     */
    private static final double KARAOKE_CLIP_TOP_OFFSET = -3.0;

    private double karaokeClipHeight() {
        return getFontRenderer().getFontHeight() + 7.0;
    }

    /** 只画光晕的一层，裁剪到给定区间。 */
    private void renderKaraokeGlowLayer(String text, double x, double y, double clipLeft,
                                        double clipRight, int color, double glowStrength,
                                        LyricVisualStyle visualStyle) {
        if (clipRight - clipLeft <= .05 || glowStrength <= .001) {
            return;
        }

        ScissorClipManager.begin(x + clipLeft, y + KARAOKE_CLIP_TOP_OFFSET,
                clipRight - clipLeft, karaokeClipHeight());
        try {
            renderKaraokeGlow(text, x, y, color, glowStrength, visualStyle);
        } finally {
            ScissorClipManager.end();
        }
    }

    private void renderKaraokeLayer(String text, double x, double y, double clipLeft,
                                     double clipRight, int color) {
        if (clipRight - clipLeft <= .05) {
            return;
        }

        /*
         * Do not use a nested stencil here. The desktop OSD is rendered into Minecraft's
         * shared HUD framebuffer, where an OptiFine/other-mod pass can leave the stencil
         * attachment or its test state unavailable. In that case the full highlighted word
         * reaches the screen and looks like an instant whole-line colour swap.
         *
         * The projected scissor follows the current HUD scale matrix and is intersected with
         * any parent scissor. Therefore it clips the glow and glyph draw to the actual moving
         * KTV fill width without changing the outer viewport clip or foreign render state.
         */
        ScissorClipManager.begin(x + clipLeft, y + KARAOKE_CLIP_TOP_OFFSET,
                clipRight - clipLeft, karaokeClipHeight());
        try {
            bigFrString(text, x, y, color);
        } finally {
            ScissorClipManager.end();
        }
    }

    /**
     * Soft aura for the already-clipped KTV fill. The shared current-line style is
     * deliberately resolved before entering this branch, so the HUD editor's
     * current glow, bloom and radius sliders affect both the timed original text
     * and its translation. OSD-specific sliders remain a gentle KTV accent rather
     * than a separate replacement configuration path.
     */
    private void renderKaraokeGlow(String text, double x, double y, int color, double strength,
                                   LyricVisualStyle visualStyle) {
        if (!HudConfig.osdKaraokeEmphasisEnabled || strength <= .001 || RGBA.alpha(color) <= 0) {
            return;
        }

        double glowAccent = getKaraokeAccent(HudConfig.osdKaraokeGlowStrength);
        double bloomAccent = getKaraokeAccent(HudConfig.osdKaraokeBloomStrength);
        double configuredGlow = strength * visualStyle.glowStrength * glowAccent;
        double configuredBloom = strength * visualStyle.bloomStrength * bloomAccent;
        if (configuredGlow <= .001 && configuredBloom <= .001) {
            return;
        }

        double baseRadius = Math.max(.35, visualStyle.glowRadius);
        if (configuredGlow > .001) {
            double glowRadius = baseRadius * (.32 + Math.min(1.0, configuredGlow) * .68);
            drawKaraokeGlowRing(text, x, y, color, glowRadius, configuredGlow * 1.12);
        }
        if (configuredBloom > .001) {
            drawKaraokeGlowRing(text, x, y, color, baseRadius * 1.35 + .8,
                    configuredBloom * .52);
            drawKaraokeGlowRing(text, x, y, color, baseRadius * 2.15 + 1.2,
                    configuredBloom * .24);
        }
    }

    /** OSD-specific values enhance the KTV foreground without disabling shared current-line styling. */
    private double getKaraokeAccent(float configuredValue) {
        double clamped = Math.max(0.0, Math.min(1.0, configuredValue));
        return .35 + clamped * .65;
    }

    private void drawKaraokeGlowRing(String text, double x, double y, int color,
                                     double radius, double alpha) {
        int cardinalColor = multiplyAlpha(color, Math.min(1.0, alpha));
        int diagonalColor = multiplyAlpha(color, Math.min(1.0, alpha * .72));
        double diagonalRadius = radius * .72;
        getFontRenderer().drawString(text, x - radius, y, cardinalColor);
        getFontRenderer().drawString(text, x + radius, y, cardinalColor);
        getFontRenderer().drawString(text, x, y - radius, cardinalColor);
        getFontRenderer().drawString(text, x, y + radius, cardinalColor);
        getFontRenderer().drawString(text, x - diagonalRadius, y - diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x + diagonalRadius, y - diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x - diagonalRadius, y + diagonalRadius, diagonalColor);
        getFontRenderer().drawString(text, x + diagonalRadius, y + diagonalRadius, diagonalColor);
    }

    private int multiplyAlpha(int color, double multiplier) {
        int alpha = (int) Math.max(0, Math.min(255, Math.round(RGBA.alpha(color) * multiplier)));
        return RGBA.color(color & 0xFFFFFF, alpha);
    }

    private double smoothStep(double value) {
        return KaraokeAnimationMath.smoothStep(value);
    }

    private double getCharacterKaraokeProgress(double characterTimeline, int characterIndex) {
        return KaraokeAnimationMath.characterProgress(characterTimeline, characterIndex,
                HudConfig.osdKaraokeSmoothing);
    }

    private double getRawKaraokeProgress(LyricLine.Word word, float songProgress) {
        return word.getProgress(songProgress);
    }

    private double getKaraokeProgress(LyricLine.Word word, float songProgress) {
        return KaraokeAnimationMath.wordProgress(word, songProgress);
    }

    private void renderWrappedPrimary(String[] lines, double y, int color, boolean isCurrent,
                                      float songProgress, boolean applyGlow) {
        for (int i = 0; i < lines.length; i++) {
            renderPrimaryLine(lines[i], y + i * (getFontRenderer().getHeight() + PRIMARY_LINE_SPACING),
                    color, isCurrent, songProgress, applyGlow);
        }
    }

    private void renderWrappedSecondary(String[] lines, double y, int color, boolean isCurrent,
                                        float songProgress) {
        for (int i = 0; i < lines.length; i++) {
            renderSecondaryLine(lines[i], y + i * (getSmallFontRenderer().getHeight() + SECONDARY_LINE_SPACING),
                    color, isCurrent, songProgress);
        }
    }

    private void renderPrimaryLine(String text, double y, int color, boolean isCurrent,
                                   float songProgress, boolean applyGlow) {
        double x = calculateTextX(text, getFontRenderer(), activeAlignMode());
        LyricVisualStyle visualStyle = getLyricVisualStyle(isCurrent, songProgress);
        renderScaledText(text, x, y, color, getFontRenderer(), visualStyle.scale,
                applyGlow ? visualStyle.glowStrength : 0.0,
                applyGlow ? visualStyle.bloomStrength : 0.0, visualStyle.glowRadius, false);
    }

    private void renderSecondaryLine(String text, double y, int color, boolean isCurrent,
                                     float songProgress) {
        double x = calculateTextX(text, getSmallFontRenderer(), activeAlignMode());
        // The translation intentionally uses the same resolved style as the original.
        // Its smaller font remains the only visual distinction; no hidden 0.45/0.34
        // multipliers may split the HUD editor sliders into two unrelated effects.
        LyricVisualStyle visualStyle = getLyricVisualStyle(isCurrent, songProgress);
        renderScaledText(text, x, y, color, getSmallFontRenderer(), visualStyle.scale,
                visualStyle.glowStrength, visualStyle.bloomStrength, visualStyle.glowRadius, false);
    }

    /** Resolves the shared current/ordinary visual configuration for original and translated text. */
    private LyricVisualStyle getLyricVisualStyle(boolean isCurrent, float songProgress) {
        if (isCurrent) {
            double currentGlow = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentGlowStrength : 0.0;
            double currentBloom = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentBloomStrength : 0.0;
            return new LyricVisualStyle(getCurrentLineScale(songProgress), currentGlow, currentBloom,
                    HudConfig.currentGlowRadius);
        }
        double normalGlow = HudConfig.normalLyricEffectsEnabled ? HudConfig.normalGlowStrength : 0.0;
        double normalBloom = HudConfig.normalLyricEffectsEnabled ? HudConfig.normalBloomStrength : 0.0;
        return new LyricVisualStyle(HudConfig.normalScale, normalGlow,
                normalBloom, 1.2 + normalGlow * 1.8);
    }

    private double getCurrentLineScale(float songProgress) {
        double breathStrength = HudConfig.currentLyricEffectsEnabled ? HudConfig.currentBreathStrength : 0.0;
        double breath = Math.sin(songProgress * .0032) * breathStrength;
        return HudConfig.currentLineScale * (1.0 + breath);
    }

    private void renderScaledText(String text, double x, double y, int color, CFontRenderer font,
                                  double scale, double glowStrength, double bloomStrength,
                                  double glowRadius, boolean forceNoShadow) {
        // 缩放锚点必须夹在裁剪框内，理由见 renderKaraokeBlock 上方的说明：贴边的行（对唱被强制
        // 贴左/贴右，译文跟随同一列）按自身中心放大时，放大量整份落到外侧，会被裁剪框切掉。
        double textWidth = font.getStringWidthD(text);
        double safeScale = clampScaleToClip(textWidth, scale);
        double centerX = clampScaleAnchorX(x, textWidth, safeScale);
        double centerY = y + font.getHeight() * .5;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(safeScale, safeScale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderTextGlow(font, text, x, y, color, glowStrength, bloomStrength, glowRadius);
            if (!forceNoShadow && this.shadow.getValue()) {
                font.drawStringWithShadow(text, x, y, color);
            } else {
                font.drawString(text, x, y, color);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderScaledGlow(String text, double x, double y, int color, CFontRenderer font,
                                  double scale, double glowStrength, double bloomStrength,
                                  double glowRadius) {
        double textWidth = font.getStringWidthD(text);
        double safeScale = clampScaleToClip(textWidth, scale);
        double centerX = clampScaleAnchorX(x, textWidth, safeScale);
        double centerY = y + font.getHeight() * .5;
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().scale(safeScale, safeScale, 1);
        api.getGLStateManager().translate(-centerX, -centerY, 0);
        try {
            renderTextGlow(font, text, x, y, color, glowStrength, bloomStrength, glowRadius);
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    /**
     * Shader-free soft glow for Forge 1.8.9. Sparse rings produce a smooth falloff
     * without allocating a framebuffer per glyph or leaking OpenGL state.
     */
    private void renderTextGlow(CFontRenderer font, String text, double x, double y, int color,
                                double glowStrength, double bloomStrength, double glowRadius) {
        if (RGBA.alpha(color) <= 0 || (glowStrength <= .001 && bloomStrength <= .001)) {
            return;
        }

        double baseRadius = Math.max(.35, glowRadius);
        if (glowStrength > .001) {
            drawGlowRing(font, text, x, y, color, .45 + baseRadius * .48, glowStrength * .32);
        }
        if (bloomStrength > .001) {
            drawGlowRing(font, text, x, y, color, baseRadius * 1.35 + .8, bloomStrength * .16);
            drawGlowRing(font, text, x, y, color, baseRadius * 2.15 + 1.2, bloomStrength * .075);
        }
    }

    private void drawGlowRing(CFontRenderer font, String text, double x, double y, int color,
                              double radius, double alpha) {
        int cardinal = multiplyAlpha(color, Math.min(1.0, alpha));
        int diagonal = multiplyAlpha(color, Math.min(1.0, alpha * .72));
        double diagonalRadius = radius * .7071;
        font.drawString(text, x - radius, y, cardinal);
        font.drawString(text, x + radius, y, cardinal);
        font.drawString(text, x, y - radius, cardinal);
        font.drawString(text, x, y + radius, cardinal);
        font.drawString(text, x - diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(text, x + diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(text, x - diagonalRadius, y + diagonalRadius, diagonal);
        font.drawString(text, x + diagonalRadius, y + diagonalRadius, diagonal);
    }

    private double calculateTextX(String text, CFontRenderer font, String alignMode) {
        double textWidth = font.getStringWidthD(text);
        if ("Right".equals(alignMode)) {
            return applyDuetSpread(getContentLeft() + getContentWidth() - textWidth, textWidth);
        }
        if ("Center".equals(alignMode)) {
            return applyDuetSpread(getContentLeft() + (getContentWidth() - textWidth) * .5, textWidth);
        }
        return applyDuetSpread(getContentLeft(), textWidth);
    }
    /**
     * The HUD editor owns lyric appearance persistence.  Resolve colours from HudConfig
     * at paint time so the current-colour picker can never mutate the ordinary lyric
     * colour through the legacy ClickGUI ColorValue instances.
     */
    private int getBaseLyricColor(int alpha) {
        return getHudConfiguredColor(HudConfig.lyricColorRgb, alpha);
    }

    /** Current colour is reserved exclusively for clipped, timed KTV foreground glyphs. */
    private int getCurrentLyricColor(int alpha) {
        return getHudConfiguredColor(HudConfig.currentLyricColorRgb, alpha);
    }

    private int getHudConfiguredColor(int configuredArgb, int requestedAlpha) {
        int configuredAlpha = (configuredArgb >>> 24) & 0xFF;
        int combinedAlpha = (int) Math.max(0, Math.min(255,
                Math.round(Math.max(0, Math.min(255, requestedAlpha)) * configuredAlpha / 255.0)));
        return RGBA.color(configuredArgb & 0x00FFFFFF, combinedAlpha);
    }

    /**
     * Resolves the timed word currently under playback. This is retained for the
     * Dynamic Island text bridge and diagnostics; OSD painting itself uses the
     * per-code-point KTV path above.
     */
    private WordInfo calculateCurrentWordInfo(LyricLine line, float songProgress) {
        WordInfo info = new WordInfo();
        if (line.words.isEmpty()) {
            return info;
        }

        for (int k = 0; k < line.words.size(); k++) {
            LyricLine.Word word = line.words.get(k);
            if (word.timestamp > songProgress) {
                info.currentIndex = Math.max(0, k - 1);
                return info;
            }
        }
        info.currentIndex = line.words.size() - 1;
        return info;
    }

    private double calculateAlignmentX(String text, String alignMode) {
        return alignmentX(getFontRenderer().getStringWidthD(text), alignMode);
    }

    /**
     * 已知渲染宽度时的对齐起点。
     *
     * <p>KTV 逐字路径必须走这一版并传入{@link KaraokeLayoutBuilder.Layout#lineAdvance 行步进宽度}，
     * 不能用整行 {@code getStringWidthD}：后者含字间 kerning，而字词落点是逐词宽度累加出来的。
     * 右对齐时两个基准不一致，行尾就正好溢出这个差值。</p>
     */
    private double alignmentX(double textWidth, String alignMode) {
        switch (alignMode) {
            case "Left":
                return applyDuetSpread(getContentLeft(), textWidth);
            case "Center":
                return applyDuetSpread(getContentLeft() + (getContentWidth() - textWidth) * .5, textWidth);
            case "Right":
                return applyDuetSpread(getContentLeft() + getContentWidth() - textWidth, textWidth);
            default:
                throw new IllegalStateException("Unexpected value: " + alignMode);
        }
    }

    /**
     * 对唱行的横向收拢。
     *
     * <p>对唱段落原来是一行完全贴内容区左边缘、一行完全贴右边缘，在窄一点的 HUD 上两行之间空出
     * 一大块、读起来很突兀。这里把落点在「完全居中」和「完全贴边」之间按
     * {@link HudConfig#osdDuetSpread} 线性插值：{@code 1} 就是改动之前的贴边，{@code 0} 是两行都
     * 摆正中间（仍然分两行渲染），默认 {@code 0.55} 是往中间收一半多一点。</p>
     *
     * <p>做成插值而不是「贴边再往里缩固定像素」有两个原因：一是无论文本多宽、HUD 多窄，两个端点
     * 本身都在内容区里，插值结果必然也在内容区里，不会重新引入对唱行横向溢出；二是幅度与 HUD 宽度
     * 自动成比例，换个宽度不需要重新调参。</p>
     *
     * <p>只在 {@link #duetAlignOverride} 生效时才动。用户自己在配置里选了「靠左」/「靠右」的普通
     * 行照旧完全贴边——那是他明确要求的对齐方式，不该被这一项偷偷改掉。</p>
     */
    private double applyDuetSpread(double alignedX, double textWidth) {
        if (this.duetAlignOverride == null) return alignedX;
        double spread = Math.max(0.0, Math.min(1.0, HudConfig.osdDuetSpread));
        if (spread >= .9995) return alignedX;
        double centered = getContentLeft() + (getContentWidth() - textWidth) * .5;
        return centered + (alignedX - centered) * spread;
    }

    /**
     * The active colour is reserved for the clipped KTV foreground layer. Rendering
     * an entire current line with it would bypass word timing and look like a hard
     * colour switch, so the unfilled base always uses the ordinary lyric colour.
     */
    private int getConfiguredLyricColor(LyricLine line, int alpha) {
        return getBaseLyricColor(alpha);
    }

    private void cleanupRender() {
        api.getGLStateManager().popMatrix();
        wpsInterface.setWidth(this.width.getValue().floatValue());
        wpsInterface.setHeight(this.height.getValue().floatValue());
    }

    /** Immutable resolved style shared by primary and translation lyric rendering. */
    private static class LyricVisualStyle {
        final double scale;
        final double glowStrength;
        final double bloomStrength;
        final double glowRadius;

        LyricVisualStyle(double scale, double glowStrength, double bloomStrength, double glowRadius) {
            this.scale = scale;
            this.glowStrength = glowStrength;
            this.bloomStrength = bloomStrength;
            this.glowRadius = glowRadius;
        }

        /**
         * 换一个缩放倍率，其余项不变。
         *
         * <p>缩放倍率被裁剪框夹小之后，必须把夹过的那一份传给下游：KTV 离屏合成用它决定纹理的
         * 超采样倍率，兜底路径用它决定羽化条数。只改变换矩阵却不改样式，两者就会对不上。</p>
         */
        LyricVisualStyle withScale(double newScale) {
            return Math.abs(newScale - this.scale) < 1e-9 ? this
                    : new LyricVisualStyle(newScale, glowStrength, bloomStrength, glowRadius);
        }
    }

    private static class LyricRenderInfo {
        double yPosition;
        double visibilityAlpha = 1.0;
    }

    /** Cached geometry for one logical lyric row after responsive wrapping. */
    private static class LyricLayout {
        final String[] primaryLines;
        final String[] secondaryLines;
        final KaraokeLayoutBuilder.Layout karaokeLayout;
        final double primaryVisualHeight;
        final double secondaryVisualHeight;
        final double visualHeight;
        final double rowHeight;

        LyricLayout(String[] primaryLines, String[] secondaryLines, KaraokeLayoutBuilder.Layout karaokeLayout) {
            this.primaryLines = primaryLines;
            this.secondaryLines = secondaryLines;
            this.karaokeLayout = karaokeLayout;

            double primaryLineHeight = getFontRenderer().getHeight();
            this.primaryVisualHeight = primaryLines.length * primaryLineHeight
                    + Math.max(0, primaryLines.length - 1) * PRIMARY_LINE_SPACING;

            if (secondaryLines.length > 0) {
                double secondaryLineHeight = getSmallFontRenderer().getHeight();
                this.secondaryVisualHeight = secondaryLines.length * secondaryLineHeight
                        + Math.max(0, secondaryLines.length - 1) * SECONDARY_LINE_SPACING;
            } else {
                this.secondaryVisualHeight = 0.0;
            }

            this.visualHeight = primaryVisualHeight + (secondaryLines.length > 0
                    ? PRIMARY_TO_SECONDARY_SPACING + secondaryVisualHeight : 0.0);
            this.rowHeight = visualHeight + HudConfig.normalLineSpacing;
        }
    }

    private static class WordInfo {
        int currentIndex;
    }

    private static CFontRenderer getFontRenderer() {
        return FontManager.pf28bold;
    }

    private static CFontRenderer getSmallFontRenderer() {
        return FontManager.pf18bold;
    }

    private void bigFrString(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getFontRenderer().drawStringWithShadow(text, x, y, color);
        } else {
            getFontRenderer().drawString(text, x, y, color);
        }
    }

    private void bigFrStringCentered(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getFontRenderer().drawCenteredStringWithShadow(text, x, y, color);
        } else {
            getFontRenderer().drawCenteredString(text, x, y, color);
        }
    }

    private void smallFrString(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getSmallFontRenderer().drawStringWithShadow(text, x, y, color);
        } else {
            getSmallFontRenderer().drawString(text, x, y, color);
        }
    }

    private void smallFrStringCentered(String text, double x, double y, int color) {
        if (this.shadow.getValue()) {
            getSmallFontRenderer().drawCenteredStringWithShadow(text, x, y, color);
        } else {
            getSmallFontRenderer().drawCenteredString(text, x, y, color);
        }
    }

    private static LyricLine.Word getPrevWord(int cur, int j, LyricLine line) {
        LyricLine.Word prev;
        if (cur - 1 < 0) {
            if (j - 1 < 0) {
                prev = line.words.get(0);
            } else {
                prev = CloudMusic.lyrics.get(j - 1).words.get(CloudMusic.lyrics.get(j - 1).words.size() - 1);
            }
        } else {
            prev = line.words.get(cur - 1);
        }
        return prev;
    }

}
