package com.muoniumplayer.core.screens.hud;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.Shaders;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD 设置的现代界面。
 *
 * <p>与经典界面（{@link GuiHudEditor}）功能完全一致 —— 两者的条目都来自
 * {@link HudSettingsModel}，所以不会出现「某一边少一项」。差别只在呈现：这里是
 * 「侧边栏分类 + 右侧卡片分组 + 每行带说明」的双栏布局，也就是 Windows 11 设置、
 * macOS 系统设置与 VS Code 设置面板共同的那套结构。相比经典界面把二十多个滑块
 * 平铺在一列 236px 宽的长条里，分类之后每屏只需要滚一小段，而且每一项都能带上
 * 一句「这到底影响什么」。</p>
 *
 * <p>三个刻意的工程约束，改动这个类之前请先读完：</p>
 * <ol>
 *   <li><b>渲染与命中测试共用同一份行矩形列表。</b>每帧先 {@link #layoutRows} 把当前可见的行算成
 *   {@link Row}，之后画图与鼠标判定都只读它。经典界面是绘制一套坐标、{@code mouseClicked} 里再抄
 *   一套，两边靠人工保持同步 —— 那是它最容易出错的地方（加一行忘了同步偏移，点击就会错位到相邻项）。</li>
 *   <li><b>面板矩形只能从 {@link #panelBounds()} 拿。</b>面板可以被拖动、可以折叠、入场时还带一段
 *   位移动画，位置不再是一个常量表达式。任何地方自己算一遍都会在动画中的那几帧和真实位置错开。</li>
 *   <li><b>动画只允许改「颜色 / 透明度 / 偏移」，不允许改命中矩形。</b>唯一的例外是面板位移与滚动，
 *   它们都被折进 {@code panelBounds()} 与 {@link #displayScroll}，绘制与命中读到的是同一个值。
 *   特别地，入场动画不用 {@code glScale} —— {@code GL_SCISSOR} 是屏幕坐标，缩放会让裁剪区错位。</li>
 * </ol>
 *
 * <p>面板默认靠右摆放：屏幕左侧要留给两块 HUD 的实时预览与拖拽，这和经典界面的取舍一致。
 * 拖动标题栏可以把它挪到任意位置（会被记进配置），双击标题栏或按标题栏上的复位按钮回到默认位置。
 * 屏幕太窄时面板会自动收窄，也可以用折叠按钮把它收成一条，露出下面的 HUD。</p>
 */
public class GuiHudEditorPro extends GuiScreen {

    // ==================== 面板尺寸 ====================

    private static final int PANEL_MARGIN = 12;
    private static final int PANEL_MAX_W = 604;
    private static final int PANEL_MAX_H = 492;
    private static final int PANEL_MIN_W = 300;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 34;
    private static final int NAV_W = 156;
    private static final int NAV_ROW_H = 36;
    private static final int COLLAPSED_H = 46;

    private static final int CONTENT_PAD = 14;
    private static final int SEARCH_H = 22;
    private static final int GROUP_HEADER_H = 24;
    private static final int ROW_H = 40;
    private static final int CARD_GAP = 8;
    private static final int CARD_RADIUS = 10;
    private static final int EMPTY_STATE_H = 78;

    private static final int TRACK_W = 116;
    private static final int VALUE_W = 48;
    private static final int SWITCH_W = 38;
    private static final int SWITCH_H = 20;
    private static final int SWATCH_W = 32;
    private static final int SWATCH_H = 20;

    private static final int THEME_CHIP_SIZE = 22;
    private static final int THEME_CHIP_GAP = 5;

    private static final int PICKER_W = 210;
    private static final int PICKER_H = 190;
    private static final int SV_W = 186;
    private static final int SV_H = 88;
    private static final int HUE_H = 10;
    private static final int HEX_INPUT_W = 86;
    private static final int HEX_INPUT_H = 18;

    private static final int SNAP_THRESHOLD = 20;
    private static final int INFO_BASE_W = 230;
    private static final int INFO_BASE_H = 56;

    /** 入场时面板从「低 10 像素」升上来。位移量刻意小：大幅度滑入在只有半秒的界面里显得拖沓。 */
    private static final int INTRO_RISE = 10;
    /** 切分类时内容从右侧 8 像素滑入。只作用于绘制，不动命中矩形。 */
    private static final int CONTENT_SLIDE = 8;
    /** 双击标题栏复位的判定窗口（毫秒）。 */
    private static final long DOUBLE_CLICK_MS = 320L;

    // ==================== 配色 ====================

    private static final int PANEL_BG = 0xF2141922;
    private static final int PANEL_EDGE = 0x24FFFFFF;
    private static final int NAV_BG = 0x22000000;
    private static final int NAV_HOVER = 0x14FFFFFF;
    private static final int NAV_SELECTED = 0x22FFFFFF;
    private static final int CARD_BG = 0x12FFFFFF;
    private static final int CARD_EDGE = 0x0EFFFFFF;
    private static final int ROW_HOVER = 0x1AFFFFFF;
    private static final int TEXT_PRIMARY = 0xFFF2F5FA;
    private static final int TEXT_SECONDARY = 0xFF98A3B4;
    private static final int TEXT_TERTIARY = 0xFF6E7A8C;
    private static final int TRACK_BG = 0x36FFFFFF;
    private static final int SWITCH_OFF = 0x33FFFFFF;
    private static final int BUTTON_BG = 0x1FFFFFFF;
    private static final int BUTTON_HOVER = 0x38FFFFFF;

    // ==================== 一行的几何 ====================

    /** 一条可见的行。分组标题与条目共用这个结构，{@code entry == null} 即为分组标题或空态。 */
    private static final class Row {
        final HudSettingsModel.Entry entry;
        final String title;
        /** 分组标题左侧的图标码位；条目行没有图标，为 {@code null}。 */
        final String icon;
        final int accent;
        final int x;
        final int y;
        final int w;
        final int h;
        /** 分组里的位置。卡片背景本身由 {@link #cards} 统一画，这里只用来决定要不要画分隔线。 */
        final boolean firstInCard;
        final boolean lastInCard;
        /** 搜索无结果时的那一行：画法完全不同（大图标 + 居中提示）。 */
        final boolean emptyState;

        Row(HudSettingsModel.Entry entry, String title, String icon, int accent,
            int x, int y, int w, int h, boolean firstInCard, boolean lastInCard, boolean emptyState) {
            this.entry = entry;
            this.title = title;
            this.icon = icon;
            this.accent = accent;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.firstInCard = firstInCard;
            this.lastInCard = lastInCard;
            this.emptyState = emptyState;
        }

        boolean isGroupHeader() {
            return entry == null;
        }
    }

    /**
     * 一个分组的卡片背景。
     *
     * <p>为什么要单独一层：之前是每行自己画一次满矩形、首尾行再叠一个同色圆角矩形，于是首尾那
     * 20 像素高的带子被画了两遍，半透明白叠加成明显更亮的色块，看起来像渲染残留。卡片一次画完、
     * 行只负责画分隔线与内容，颜色就只叠一层，圆角也不会出现「中间方角、首尾圆角」的割裂感。</p>
     */
    private static final class Card {
        final int x;
        final int y;
        final int w;
        final int h;

        Card(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    private int contentHeight;

    // ==================== 状态 ====================

    private int categoryIndex;
    private int[] scrollByCategory;
    private int searchScroll;
    /** 侧边栏选中指示器的当前 y，向目标插值，切分类时是滑过去而不是跳过去。 */
    private float navIndicator = -1f;
    private boolean collapsed;

    private GuiTextField searchInput;
    private String searchQuery = "";

    private HudSetting draggingSlider;
    private int draggingSliderTrackX;
    private HudSettingsModel.ColorTarget editingColor = HudSettingsModel.ColorTarget.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean settingsDirty;
    private boolean resetConfirmationVisible;

    private boolean draggingInfo;
    private boolean draggingLyrics;
    private int dragOffX;
    private int dragOffY;
    private boolean showSnapGuide;

    /** 面板自身的拖动。和两块 HUD 的拖动互斥（面板在最上层，命中它就不会落到 HUD 上）。 */
    private boolean draggingPanel;
    private int panelDragOffX;
    private int panelDragOffY;
    private long lastHeaderClick;

    // ==================== 动画状态 ====================

    /**
     * 绘制时统一乘上的不透明度。
     *
     * <p>由 {@link #drawRoundedRect}、{@link #drawText} 与 {@link #drawIcon} 直接读，而不是让每个
     * 调用点自己传 —— 面板入场淡入、内容淡入涉及上百个绘制调用，手动传参漏一处就会出现「有一块
     * 不跟着淡入」的破口。用完必须在 {@code finally} 里恢复 1，否则会泄漏到后面的 HUD 预览上。</p>
     */
    private float layerAlpha = 1f;

    private boolean introStarted;
    /** 面板入场进度 0..1。既做淡入也做位移，位移折进 {@link #panelBounds()} 所以裁剪区跟着走。 */
    private float introProgress;

    /** 内容区淡入进度 0..1。切分类或切换搜索状态时归零。 */
    private float contentFade = 1f;
    private int contentOwner = Integer.MIN_VALUE;

    /**
     * 平滑滚动用的显示位置。
     *
     * <p>{@link #layoutRows} 用它而不是用 {@link #scroll()} 的整数目标值，滚轮才会是滑过去的。
     * 它只在 {@code drawScreen} 里变，所以 {@code mouseClicked} 读到的是上一帧绘制时的同一个值，
     * 命中与所见依然一致。</p>
     */
    private float displayScroll;
    private int scrollOwner = Integer.MIN_VALUE;

    private float searchFocus;

    /** 命名部件（按钮、搜索框……）的悬停进度。每帧最多查十几次，用普通 HashMap 就够。 */
    private final Map<String, Float> namedProgress = new HashMap<String, Float>();
    /**
     * 条目行的动画状态：{@code [0]} 悬停、{@code [1]} 开关位置、{@code [2]} 滑块把手放大。
     *
     * <p>按 {@code Entry} 的身份存：条目声明是静态常量，身份天然稳定，不需要额外的 id。</p>
     */
    private final Map<HudSettingsModel.Entry, float[]> rowProgress =
            new IdentityHashMap<HudSettingsModel.Entry, float[]>();
    /** 每个图标字号的 em 方格度量缓存，见 {@link #drawIcon}。 */
    private static final Map<CFontRenderer, GlyphInkMetrics> GRID_CACHE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    // ==================== 生命周期 ====================

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (scrollByCategory == null || scrollByCategory.length != HudSettingsModel.categories().length) {
            scrollByCategory = new int[HudSettingsModel.categories().length];
        }
        ensureSearchInput();
        ensureHexColorInput();
        // initGui 在窗口缩放时也会被调用。入场动画只在真正打开时播一次，否则每次改分辨率都要重新淡入。
        if (!introStarted) {
            introStarted = true;
            introProgress = 0f;
        }
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) {
            HudConfig.save();
            settingsDirty = false;
        }
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================== 主绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 圆角矩形着色器会关掉 alpha test。这个界面是叠在真实游戏画面上渲染的，任何泄漏的 GL 状态
        // 都会带进 Forge 覆盖层和下一帧世界（树叶/玻璃变透明、计分板发虚）。整段绘制包在一次状态边界里。
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            RenderSystem.refreshResolution();
            CFontRenderer fr = FontManager.pf12;
            if (fr == null) return;

            introProgress = Interpolations.interpolate(introProgress, 1f, 17f);
            if (introProgress > 0.995f) introProgress = 1f;

            MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
            MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;

            // 面板尺寸与位置无关，先更新拖动再取矩形，这一帧的绘制与命中就都是新位置。
            updatePanelDrag(mouseX, mouseY, panelWidth(), panelHeight());
            int[] panel = panelBounds();
            int panelX = panel[0];
            int panelY = panel[1];
            int panelW = panel[2];
            int panelH = panel[3];

            DownloadDynamicIsland.renderEditorPreview();

            int wheel = resetConfirmationVisible ? 0 : Mouse.getDWheel();
            boolean overPanel = isInside(mouseX, mouseY, panelX, panelY, panelW, panelH);
            boolean overPicker = editingColor != HudSettingsModel.ColorTarget.NONE
                    && isInside(mouseX, mouseY, pickerX(panelX, panelW), pickerY(panelY, panelH), PICKER_W, PICKER_H);
            boolean uiBusy = resetConfirmationVisible || overPanel || overPicker || draggingPanel;

            if (!resetConfirmationVisible) {
                updateColorPickerWhileDragging(mouseX, mouseY, panelX, panelY, panelW, panelH);
                updateSliderWhileDragging(mouseX);
            }

            boolean lmb = !resetConfirmationVisible && !draggingPanel && Mouse.isButtonDown(0);

            // ==== 两块 HUD 的实时预览与拖拽（面板之外的区域） ====
            int infoW = (int) (INFO_BASE_W * HudConfig.infoScale);
            int infoH = (int) (INFO_BASE_H * HudConfig.infoScale);
            int infoX = (int) (HudConfig.infoX * (width - infoW));
            int infoY = (int) (HudConfig.infoY * (height - infoH));
            handleDrag(lmb && !uiBusy, uiBusy ? 0 : wheel, mouseX, mouseY, infoX, infoY, infoW, infoH, true);
            if (hasLiveInfoPreview()) {
                renderLiveInfoPreview(info);
            } else {
                drawInfoPlaceholder(fr, infoX, infoY, infoW, infoH);
            }
            drawHudSelection(fr, "歌曲信息", infoX, infoY, infoW, infoH, 0xFF7DD3FC);

            int lyricW = (int) (lyrics.width.getValue().floatValue() * HudConfig.lyricScale);
            int lyricH = (int) (lyrics.height.getValue().floatValue() * HudConfig.lyricScale);
            int lyricX = (int) (HudConfig.lyricX * (width - lyricW));
            int lyricY = (int) (HudConfig.lyricY * (height - lyricH));
            handleDrag(lmb && !uiBusy, uiBusy ? 0 : wheel, mouseX, mouseY, lyricX, lyricY, lyricW, lyricH, false);
            if (hasLiveLyricsPreview()) {
                renderLiveLyricsPreview(lyrics, lyricX, lyricY);
            } else {
                drawLyricsPlaceholder(fr, lyricX, lyricY, lyricW, lyricH);
            }
            drawHudSelection(fr, "桌面歌词", lyricX, lyricY, lyricW, lyricH, 0xFFA78BFA);

            // ==== 设置面板 ====
            layerAlpha = introProgress;
            try {
                drawPanel(fr, panelX, panelY, panelW, panelH, mouseX, mouseY, wheel, overPanel);
            } finally {
                layerAlpha = 1f;
            }

            if (editingColor != HudSettingsModel.ColorTarget.NONE) {
                drawColorPicker(fr, panelX, panelY, panelW, panelH, mouseX, mouseY);
            }

            if (showSnapGuide) {
                Gui.drawRect(0, height / 2, width, height / 2 + 1, 0x558DDCFF);
                Gui.drawRect(width / 2, 0, width / 2 + 1, height, 0x558DDCFF);
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
            if (resetConfirmationVisible) {
                drawResetConfirmation(fr, mouseX, mouseY);
            }
        } finally {
            layerAlpha = 1f;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private int panelWidth() {
        return Math.max(PANEL_MIN_W, Math.min(PANEL_MAX_W, width - PANEL_MARGIN * 2));
    }

    private int panelHeight() {
        if (collapsed) return COLLAPSED_H;
        return Math.max(220, Math.min(PANEL_MAX_H, height - PANEL_MARGIN * 2));
    }

    /**
     * 面板的当前矩形 {@code [x, y, w, h]}。<b>唯一的位置来源</b>，绘制与命中测试都必须走它。
     *
     * <p>位置有三种情况：没被拖过（{@link HudConfig#hudEditorPanelX} 是哨兵负值）走默认摆位；
     * 拖过就按归一化比例还原；再叠上入场动画的位移。存归一化比例而不是绝对像素，是为了换分辨率后
     * 面板仍在屏内 —— 绝对像素在小窗口下会整块跑到屏幕外，再也点不到。</p>
     */
    private int[] panelBounds() {
        int panelW = panelWidth();
        int panelH = panelHeight();
        int maxX = Math.max(0, width - panelW);
        int maxY = Math.max(0, height - panelH);
        int panelX;
        int panelY;
        if (!panelMoved()) {
            panelX = maxX > PANEL_MARGIN ? maxX - PANEL_MARGIN : maxX;
            panelY = collapsed ? Math.min(maxY, PANEL_MARGIN) : (height - panelH) / 2;
        } else {
            panelX = Math.round(HudConfig.hudEditorPanelX * maxX);
            panelY = Math.round(HudConfig.hudEditorPanelY * maxY);
        }
        panelX = Math.max(0, Math.min(maxX, panelX));
        panelY = Math.max(0, Math.min(maxY, panelY));
        if (introProgress < 1f) {
            panelY += Math.round((1f - introProgress) * INTRO_RISE);
        }
        return new int[] { panelX, panelY, panelW, panelH };
    }

    private static boolean panelMoved() {
        return HudConfig.hudEditorPanelX >= 0f && HudConfig.hudEditorPanelY >= 0f;
    }

    /** 标题栏可拖动区的高度。折叠时整个面板就是标题栏。 */
    private int headerHeight(int panelH) {
        return Math.min(collapsed ? COLLAPSED_H : HEADER_H, panelH);
    }

    private void updatePanelDrag(int mouseX, int mouseY, int panelW, int panelH) {
        if (!draggingPanel) return;
        if (!Mouse.isButtonDown(0)) {
            draggingPanel = false;
            HudConfig.save();
            return;
        }
        float[] normalized = normalizePanelPosition(width, height, panelW, panelH,
                mouseX - panelDragOffX, mouseY - panelDragOffY);
        HudConfig.hudEditorPanelX = normalized[0];
        HudConfig.hudEditorPanelY = normalized[1];
    }

    /**
     * 把「想要的左上角」钳到屏内，再归一化成 0..1。
     *
     * <p>先钳位后归一化，面板就永远不可能被拖出屏幕 —— 一旦标题栏出界，用户就再也抓不回来，
     * 只能去改配置文件。抽成静态方法是为了能离线验证这个不变式（拖动路径本身要 LWJGL 的
     * {@code Mouse}，进不了单测）。</p>
     */
    static float[] normalizePanelPosition(int screenW, int screenH, int panelW, int panelH,
                                          int desiredX, int desiredY) {
        int maxX = Math.max(0, screenW - panelW);
        int maxY = Math.max(0, screenH - panelH);
        int nx = Math.max(0, Math.min(maxX, desiredX));
        int ny = Math.max(0, Math.min(maxY, desiredY));
        return new float[] { maxX > 0 ? nx / (float) maxX : 0f, maxY > 0 ? ny / (float) maxY : 0f };
    }

    /** 屏幕很窄时侧边栏会挤掉内容列，这时把它收成一条只有图标的窄栏。 */
    private int navWidth(int panelW) {
        return panelW - NAV_W < 210 ? 44 : NAV_W;
    }

    private void drawPanel(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                           int mouseX, int mouseY, int wheel, boolean overPanel) {
        drawSoftShadow(panelX, panelY, panelW, panelH, 16);
        drawRoundedRect(panelX, panelY, panelW, panelH, 16, PANEL_BG);
        // 顶部一条高光边：纯色圆角矩形在游戏画面上会显得「贴」上去，一道 1px 亮边就有玻璃的厚度感。
        drawRoundedRect(panelX + 6, panelY, panelW - 12, 1, 1, PANEL_EDGE);

        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        HudSettingsModel.Category active = categories[clampCategory()];

        drawHeader(fr, panelX, panelY, panelW, panelH, active, mouseX, mouseY);
        if (collapsed) {
            return;
        }

        int navW = navWidth(panelW);
        int bodyY = panelY + HEADER_H;
        int bodyH = panelH - HEADER_H - FOOTER_H;
        drawNav(fr, panelX, bodyY, navW, bodyH, categories, mouseX, mouseY);

        int contentX = panelX + navW;
        int contentW = panelW - navW;
        drawSearch(fr, contentX, bodyY + 8, contentW, active.accent, mouseX, mouseY);

        int listY = bodyY + 8 + SEARCH_H + 8;
        int listH = Math.max(24, bodyY + bodyH - listY);

        // 切分类或进出搜索时，滚动位置与内容淡入都要重新起算，否则会看到上一个分类的滚动残影。
        int owner = scrollKey();
        if (owner != scrollOwner) {
            scrollOwner = owner;
            displayScroll = scroll();
        }
        if (owner != contentOwner) {
            contentOwner = owner;
            contentFade = 0f;
        }
        contentFade = Interpolations.interpolate(contentFade, 1f, 15f);
        if (contentFade > 0.995f) contentFade = 1f;

        layoutRows(contentX, listY, contentW, active);
        int maxScroll = Math.max(0, contentHeight - listH);
        if (wheel != 0 && overPanel && mouseX >= contentX) {
            setScroll(scroll() + (wheel > 0 ? -32 : 32), maxScroll);
        } else {
            setScroll(scroll(), maxScroll);
        }
        displayScroll = Interpolations.interpolate(displayScroll, scroll(), 19f);
        if (Math.abs(displayScroll - scroll()) < 0.4f) displayScroll = scroll();
        // 用更新后的 displayScroll 再排一次：本帧画的与下一次点击命中的是同一份矩形。
        layoutRows(contentX, listY, contentW, active);

        beginScissor(contentX, listY, contentW, listH);
        float outerAlpha = layerAlpha;
        try {
            layerAlpha = outerAlpha * contentFade;
            float slide = (1f - contentFade) * CONTENT_SLIDE;
            GL11.glPushMatrix();
            GL11.glTranslatef(slide, 0f, 0f);
            try {
                for (int index = 0; index < cards.size(); index++) {
                    Card card = cards.get(index);
                    if (card.y + card.h < listY - 4 || card.y > listY + listH + 4) continue;
                    drawRoundedRect(card.x, card.y, card.w, card.h, CARD_RADIUS, CARD_BG);
                    drawRoundedRect(card.x + CARD_RADIUS, card.y, card.w - CARD_RADIUS * 2, 1, 1, CARD_EDGE);
                }
                for (int index = 0; index < rows.size(); index++) {
                    Row row = rows.get(index);
                    if (row.y + row.h < listY - 4 || row.y > listY + listH + 4) continue;
                    drawRow(fr, row, mouseX - Math.round(slide), mouseY);
                }
            } finally {
                GL11.glPopMatrix();
            }
        } finally {
            layerAlpha = outerAlpha;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        if (maxScroll > 0) {
            int trackTop = listY + 2;
            int trackH = listH - 4;
            int thumbH = Math.max(24, trackH * listH / Math.max(1, contentHeight));
            float thumbY = trackTop + (trackH - thumbH) * clamp01(displayScroll / maxScroll);
            drawRoundedRect(contentX + contentW - 7, trackTop, 3, trackH, 2, 0x22FFFFFF);
            drawRoundedRect(contentX + contentW - 8, thumbY, 5, thumbH, 3, 0x66FFFFFF);
        }

        drawFooter(fr, panelX, panelY + panelH - FOOTER_H, panelW, mouseX, mouseY);
    }

    // ==================== 头部 / 侧栏 / 搜索 / 底栏 ====================

    private void drawHeader(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                            HudSettingsModel.Category active, int mouseX, int mouseY) {
        int headerH = headerHeight(panelH);
        boolean overHeader = isInside(mouseX, mouseY, panelX, panelY, panelW, headerH);
        float gripGlow = named("header.grip", overHeader || draggingPanel);
        // 把手图标而不是一句「可拖动」提示：标题栏只有 52 像素高，文字挤不进去，
        // 而六点栅格是各家窗口/列表统一的「这里能抓」的记号。
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.DRAG_HANDLE,
                panelX + 15, panelY + headerH / 2.0,
                mix(0x40FFFFFF, 0xA6FFFFFF, gripGlow));

        int titleY = panelY + (collapsed ? 12 : 16);
        drawText(FontManager.pf14bold, "音乐 HUD 设置", panelX + 28, titleY, TEXT_PRIMARY);
        drawText(fr, collapsed ? "已折叠 · 点右上角展开" : "实时预览 · 自动保存 · 拖动标题栏可移动",
                panelX + 28, titleY + 14, TEXT_SECONDARY);

        int[] toggle = collapseButton(panelX, panelY, panelW, panelH);
        drawGhostButton("header.toggle", fr, collapsed ? HudSettingsIcons.EXPAND : HudSettingsIcons.COLLAPSE,
                null, toggle, mouseX, mouseY, TEXT_PRIMARY, active.accent);
        if (collapsed) return;

        int[] classic = classicButton(panelX, panelY, panelW, panelH);
        drawGhostButton("header.classic", fr, HudSettingsIcons.CLASSIC_UI, "经典",
                classic, mouseX, mouseY, TEXT_PRIMARY, active.accent);

        if (panelMoved()) {
            int[] restore = restorePositionButton(panelX, panelY, panelW, panelH);
            drawGhostButton("header.home", fr, HudSettingsIcons.RESTORE, null,
                    restore, mouseX, mouseY, TEXT_SECONDARY, active.accent);
        }

        drawRoundedRect(panelX + 14, panelY + HEADER_H - 1, panelW - 28, 1, 1, 0x1AFFFFFF);
    }

    /** 标题栏上那一排 22×22 按钮的纵向位置：按标题栏实际高度居中，折叠时也不会贴边。 */
    private int buttonRowY(int panelY, int panelH) {
        return panelY + (headerHeight(panelH) - 22) / 2;
    }

    private int[] collapseButton(int panelX, int panelY, int panelW, int panelH) {
        return new int[] { panelX + panelW - 14 - 22, buttonRowY(panelY, panelH), 22, 22 };
    }

    private int[] classicButton(int panelX, int panelY, int panelW, int panelH) {
        int w = ghostWidth(FontManager.pf12, "经典");
        return new int[] { panelX + panelW - 14 - 22 - 6 - w, buttonRowY(panelY, panelH), w, 22 };
    }

    /** 只在面板被拖动过之后出现，用来把它送回默认摆位。 */
    private int[] restorePositionButton(int panelX, int panelY, int panelW, int panelH) {
        int[] classic = classicButton(panelX, panelY, panelW, panelH);
        return new int[] { classic[0] - 6 - 22, classic[1], 22, 22 };
    }

    /** 图标 + 文字的胶囊按钮宽度。绘制与命中都从这里取，改字号也不会两边错开。 */
    private static int ghostWidth(CFontRenderer fr, String label) {
        int text = fr == null || label == null ? 0 : fr.getStringWidth(label);
        return 22 + text + 12;
    }

    /**
     * 侧边栏每行的高度。
     *
     * <p>侧边栏不滚动，行高又原本是常量，所以窗口一矮（GUI 缩放调大时 {@code height} 会很小）
     * 排在后面的分类就整行画不出来，连点都点不到。这里在放不下时按可用高度均分，
     * 保证任何面板高度下每个分类都可见可点；行高被压到放不下两行文字时只画标题。</p>
     */
    private static int navRowHeight(int navH, int count) {
        if (count <= 0) return NAV_ROW_H;
        int available = navH - 12;
        if (count * NAV_ROW_H <= available) return NAV_ROW_H;
        return Math.max(18, available / count);
    }

    private void drawNav(CFontRenderer fr, int navX, int navY, int navW, int navH,
                         HudSettingsModel.Category[] categories, int mouseX, int mouseY) {
        drawRoundedRect(navX, navY, navW, navH, 0, NAV_BG);
        boolean compact = navW < 100;
        int rowH = navRowHeight(navH, categories.length);
        boolean twoLines = rowH >= 32;

        int selected = clampCategory();
        float targetY = navY + 6 + selected * rowH;
        if (navIndicator < 0f) navIndicator = targetY;
        navIndicator = Interpolations.interpolate(navIndicator, targetY, 14f);

        // 指示器先画，条目图标与文字画在它上面：切分类时看到的是一块背景滑过去。
        drawRoundedRect(navX + 6, navIndicator, navW - 12, rowH - 4, 9, NAV_SELECTED);
        drawRoundedRect(navX + 6, navIndicator + Math.max(3, rowH / 4.5),
                3, Math.max(4, rowH - 20), 2, categories[selected].accent);

        for (int index = 0; index < categories.length; index++) {
            HudSettingsModel.Category category = categories[index];
            int rowY = navY + 6 + index * rowH;
            boolean hovered = isInside(mouseX, mouseY, navX + 6, rowY, navW - 12, rowH - 4);
            float hp = named("nav." + index, hovered && index != selected);
            if (hp > 0.02f) {
                drawRoundedRect(navX + 6, rowY, navW - 12, rowH - 4, 9, fade(NAV_HOVER, hp));
            }
            boolean current = index == selected;
            // 图标替代原来的色点：色点只能表达「这一类是紫色的」，图标能表达「这一类是歌词」。
            double iconCx = compact ? navX + navW / 2.0 : navX + 21;
            drawIcon(FontManager.settingsIcon32, category.icon, iconCx, rowY + (rowH - 4) / 2.0,
                    current ? category.accent : mix(0xFF7E8A9C, category.accent, hp));
            if (compact) continue;
            int titleColor = current ? TEXT_PRIMARY : mix(TEXT_SECONDARY, TEXT_PRIMARY, hp);
            if (twoLines) {
                drawText(FontManager.pf12bold, category.title, navX + 36, rowY + 9, titleColor);
                drawText(fr, category.entryCount() + " 项", navX + 36, rowY + 21, TEXT_TERTIARY);
            } else {
                drawText(FontManager.pf12bold, category.title, navX + 36, rowY + (rowH - 4) / 2 - 5, titleColor);
            }
        }
    }

    private void drawSearch(CFontRenderer fr, int x, int y, int w, int accent, int mouseX, int mouseY) {
        int boxX = x + CONTENT_PAD;
        int boxW = w - CONTENT_PAD * 2;
        ensureSearchInput();
        boolean focused = searchInput.isFocused();
        searchFocus = Interpolations.interpolate(searchFocus, focused ? 1f : 0f, 20f);

        float hp = named("search.box", isInside(mouseX, mouseY, boxX, y, boxW, SEARCH_H));
        drawRoundedRect(boxX, y, boxW, SEARCH_H, SEARCH_H * 0.5,
                mix(mix(0x18FFFFFF, 0x22FFFFFF, hp), 0x2BFFFFFF, searchFocus));
        // 聚焦下划线从中间向两侧展开，而不是整条突然出现：这是最省事又能明确表达「焦点在这里」的动效。
        int lineW = Math.round((boxW - 20) * searchFocus);
        if (lineW > 1) {
            drawRoundedRect(boxX + (boxW - lineW) / 2.0, y + SEARCH_H - 1.5, lineW, 1.5, 1, fade(accent, 0.85f));
        }
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.SEARCH, boxX + 13, y + SEARCH_H / 2.0,
                mix(TEXT_TERTIARY, TEXT_SECONDARY, Math.max(hp, searchFocus)));

        String text = searchInput.getText();
        boolean hasText = !text.isEmpty();
        if (hasText) {
            int[] clear = searchClearButton(x, y, w);
            float cp = named("search.clear", isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3]));
            drawRoundedRect(clear[0], clear[1], clear[2], clear[3], clear[3] * 0.5, fade(0x33FFFFFF, 0.35f + cp * 0.65f));
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CLOSE,
                    clear[0] + clear[2] / 2.0, clear[1] + clear[3] / 2.0,
                    mix(TEXT_SECONDARY, TEXT_PRIMARY, cp));
        }

        int textLeft = boxX + 24;
        int textRight = boxX + boxW - (hasText ? 26 : 10);
        searchInput.xPosition = textLeft;
        searchInput.yPosition = y + 6;
        searchInput.width = Math.max(10, textRight - textLeft);
        searchInput.height = SEARCH_H - 8;
        if (!hasText && !focused) {
            drawText(fr, "搜索设置项…", textLeft, y + 8, TEXT_TERTIARY);
        } else {
            drawText(fr, fr.trim(text, Math.max(10, textRight - textLeft)), textLeft, y + 8, TEXT_PRIMARY);
            if (focused) {
                double caretX = textLeft + 1 + Math.min(fr.getStringWidthD(text), textRight - textLeft);
                // 光标跟着 searchFocus 淡入，避免刚点上去就闪一下。
                drawRoundedRect(caretX, y + 6, 1, SEARCH_H - 12, 1, fade(0xFFEAF3FF, searchFocus));
            }
        }
    }

    /** 搜索框右端的清空按钮，只在有输入时存在；绘制与命中共用这一份矩形。 */
    private int[] searchClearButton(int contentX, int searchY, int contentW) {
        int boxX = contentX + CONTENT_PAD;
        int boxW = contentW - CONTENT_PAD * 2;
        return new int[] { boxX + boxW - 22, searchY + 3, 16, 16 };
    }

    private void drawFooter(CFontRenderer fr, int panelX, int footerY, int panelW,
                            int mouseX, int mouseY) {
        drawRoundedRect(panelX + 14, footerY, panelW - 28, 1, 1, 0x1AFFFFFF);
        int[] reset = resetButton(panelX, footerY, panelW);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.INFO, panelX + 21, footerY + 17, TEXT_TERTIARY);
        String placement = "信息栏 " + pct(HudConfig.infoX, HudConfig.infoY, HudConfig.infoScale)
                + "   歌词 " + pct(HudConfig.lyricX, HudConfig.lyricY, HudConfig.lyricScale);
        double room = Math.max(20, reset[0] - (panelX + 30) - 10);
        drawText(fr, fr.trim(placement, room), panelX + 30, footerY + 13, TEXT_TERTIARY);

        drawGhostButton("footer.reset", fr, HudSettingsIcons.RESTORE, "恢复默认",
                reset, mouseX, mouseY, TEXT_PRIMARY, 0xFFE58B7B);
    }

    private int[] resetButton(int panelX, int footerY, int panelW) {
        int w = ghostWidth(FontManager.pf12, "恢复默认");
        return new int[] { panelX + panelW - 16 - w, footerY + 7, w, 21 };
    }

    // ==================== 行布局（渲染与命中共用） ====================

    /**
     * 把当前分类（或搜索结果）算成一串行矩形与分组卡片。
     *
     * <p>这是整个界面唯一的坐标来源：绘制读它，{@link #mouseClicked} 也读它。纵向起点用
     * {@link #displayScroll} 而不是滚动目标值，滚轮才有惯性；两边读的是同一个字段，所以
     * 滚动过程中点击也不会错位。</p>
     */
    private void layoutRows(int contentX, int listY, int contentW, HudSettingsModel.Category active) {
        rows.clear();
        cards.clear();
        int rowX = contentX + CONTENT_PAD;
        int rowW = contentW - CONTENT_PAD * 2 - 4;
        int cursor = listY - Math.round(displayScroll);
        int top = cursor;

        String query = searchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            for (int g = 0; g < active.groups.length; g++) {
                cursor = appendGroup(active.groups[g], active.title, active.accent,
                        rowX, rowW, cursor, query, false);
            }
        } else {
            boolean any = false;
            for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
                for (HudSettingsModel.Group group : category.groups) {
                    int before = rows.size();
                    cursor = appendGroup(group, category.title, category.accent,
                            rowX, rowW, cursor, query, true);
                    if (rows.size() > before) any = true;
                }
            }
            if (!any) {
                rows.add(new Row(null, "没有匹配「" + searchQuery.trim() + "」的设置项",
                        HudSettingsIcons.SEARCH, active.accent,
                        rowX, cursor, rowW, EMPTY_STATE_H, true, true, true));
                cursor += EMPTY_STATE_H;
            }
        }
        contentHeight = cursor - top + 6;
    }

    /** 追加一个分组（标题行 + 一张卡片里的若干条目）。返回新的纵向游标。搜索模式下没有命中的条目会整组跳过。 */
    private int appendGroup(HudSettingsModel.Group group, String categoryTitle, int accent,
                            int rowX, int rowW, int cursor, String query, boolean searching) {
        List<HudSettingsModel.Entry> visible = new ArrayList<HudSettingsModel.Entry>();
        for (HudSettingsModel.Entry entry : group.entries) {
            if (!searching || entry.matches(query)) visible.add(entry);
        }
        if (visible.isEmpty()) return cursor;

        String title = searching ? categoryTitle + " · " + group.title : group.title;
        rows.add(new Row(null, title, group.icon, accent, rowX, cursor, rowW, GROUP_HEADER_H,
                false, false, false));
        cursor += GROUP_HEADER_H;

        int cardTop = cursor;
        for (int index = 0; index < visible.size(); index++) {
            HudSettingsModel.Entry entry = visible.get(index);
            int h = entry.kind == HudSettingsModel.Kind.THEME_GRID ? themeGridHeight() + 14 : ROW_H;
            rows.add(new Row(entry, entry.getLabel(), null, accent, rowX, cursor, rowW, h,
                    index == 0, index == visible.size() - 1, false));
            cursor += h;
        }
        cards.add(new Card(rowX, cardTop, rowW, cursor - cardTop));
        return cursor + CARD_GAP;
    }

    private void drawRow(CFontRenderer fr, Row row, int mouseX, int mouseY) {
        if (row.emptyState) {
            drawIcon(FontManager.settingsIcon40, row.icon, row.x + row.w / 2.0, row.y + 26, 0x59FFFFFF);
            drawCenteredText(FontManager.pf12bold, row.title, row.x + row.w / 2.0, row.y + 44, TEXT_SECONDARY);
            drawCenteredText(fr, "换个关键词，或者按 ESC 清空搜索", row.x + row.w / 2.0, row.y + 58, TEXT_TERTIARY);
            return;
        }
        if (row.isGroupHeader()) {
            drawIcon(FontManager.settingsIcon24, row.icon, row.x + 9, row.y + GROUP_HEADER_H / 2.0 + 1, row.accent);
            drawText(FontManager.pf12bold, row.title, row.x + 21, row.y + 8, TEXT_SECONDARY);
            return;
        }

        boolean hovered = isInside(mouseX, mouseY, row.x, row.y, row.w, row.h);
        boolean dragging = row.entry.kind == HudSettingsModel.Kind.SLIDER
                && draggingSlider == row.entry.setting;
        float[] state = progressOf(row.entry, row.entry.kind == HudSettingsModel.Kind.TOGGLE && row.entry.isOn());
        state[0] = Interpolations.interpolate(state[0], hovered || dragging ? 1f : 0f, 20f);
        state[2] = Interpolations.interpolate(state[2], dragging ? 1f : (hovered ? 0.6f : 0f), 22f);
        float hp = state[0];

        // 分隔线画在卡片上、悬停高亮之下：卡片背景已经由 drawPanel 一次画完，这里不再补背景，
        // 否则半透明白会叠成更亮的一条带子（这正是之前首尾行看起来有色块的原因）。
        if (!row.firstInCard) {
            drawRoundedRect(row.x + 12, row.y, row.w - 24, 1, 1, fade(0x14FFFFFF, 1f - hp));
        }
        if (hp > 0.01f) {
            // 内缩 2px：卡片自己是 10px 圆角，高亮如果画满整行，方角会顶出卡片的圆角。
            drawRoundedRect(row.x + 2, row.y + 1, row.w - 4, row.h - 2, CARD_RADIUS - 3, fade(ROW_HOVER, hp));
            int barH = Math.round((row.h - 16) * hp);
            if (barH > 1) {
                drawRoundedRect(row.x + 3, row.y + (row.h - barH) / 2.0, 2, barH, 1, fade(row.accent, hp));
            }
        }

        if (row.entry.kind == HudSettingsModel.Kind.THEME_GRID) {
            drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 9, TEXT_PRIMARY);
            drawThemeChips(row.x + 14, row.y + 24, mouseX, mouseY);
            return;
        }

        int controlLeft = controlLeft(row);
        drawText(FontManager.pf12bold, row.entry.getLabel(), row.x + 14, row.y + 8, TEXT_PRIMARY);
        String description = row.entry.description;
        if (description != null && !description.isEmpty()) {
            double available = Math.max(20, controlLeft - (row.x + 14) - 10);
            drawText(fr, fr.trim(description, available), row.x + 14, row.y + 22,
                    mix(TEXT_TERTIARY, TEXT_SECONDARY, hp));
        }

        int centerY = row.y + row.h / 2;
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                drawSliderControl(fr, row.entry.setting, rowRight, centerY, row.accent, state[2]);
                break;
            case TOGGLE: {
                state[1] = Interpolations.interpolate(state[1], row.entry.isOn() ? 1f : 0f, 21f);
                drawSwitch(rowRight - SWITCH_W, centerY - SWITCH_H / 2, state[1], row.accent, hp);
                break;
            }
            case COLOR:
                drawSwatch(rowRight - SWATCH_W, centerY - SWATCH_H / 2, row.entry.getColor(),
                        editingColor == row.entry.colorTarget, hp);
                break;
            case CHOICE: {
                String value = row.entry.getValueText();
                int pillW = choicePillWidth(fr, value);
                drawRoundedRect(rowRight - pillW, centerY - 10, pillW, 20, 10, mix(BUTTON_BG, BUTTON_HOVER, hp));
                drawText(fr, fr.trim(value, pillW - 30), rowRight - pillW + 11, centerY - 3, TEXT_PRIMARY);
                // 右折角图标替代原来的 「›」 字符：这个字号下标点会被字形图集压成一个模糊的点。
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHEVRON_RIGHT,
                        rowRight - 11 + hp * 1.5, centerY, mix(TEXT_SECONDARY, TEXT_PRIMARY, hp));
                break;
            }
            case ACTION: {
                int w = actionButtonWidth(fr, row.entry.getLabel());
                drawRoundedRect(rowRight - w, centerY - 10, w, 20, 10, mix(BUTTON_BG, BUTTON_HOVER, hp));
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.REFRESH,
                        rowRight - w + 14, centerY, mix(TEXT_SECONDARY, TEXT_PRIMARY, hp));
                drawText(fr, "执行", rowRight - w + 23, centerY - 3, TEXT_PRIMARY);
                break;
            }
            default:
                break;
        }
    }

    /** 控件区的左边界。说明文字要在它之前收住，否则长句子会压到控件底下。 */
    private int controlLeft(Row row) {
        int rowRight = row.x + row.w - 14;
        switch (row.entry.kind) {
            case SLIDER:
                return rowRight - VALUE_W - TRACK_W - 12;
            case TOGGLE:
                return rowRight - SWITCH_W - 12;
            case COLOR:
                return rowRight - SWATCH_W - 12;
            case CHOICE:
                return rowRight - choicePillWidth(FontManager.pf12, row.entry.getValueText()) - 12;
            case ACTION:
                return rowRight - actionButtonWidth(FontManager.pf12, row.entry.getLabel()) - 12;
            default:
                return rowRight;
        }
    }

    private static int choicePillWidth(CFontRenderer fr, String value) {
        int textWidth = fr == null || value == null ? 0 : fr.getStringWidth(value);
        return Math.max(62, Math.min(148, textWidth + 34));
    }

    private static int actionButtonWidth(CFontRenderer fr, String label) {
        return 60;
    }

    private void drawSliderControl(CFontRenderer fr, HudSetting setting, int rowRight,
                                   int centerY, int accent, float knobProgress) {
        String value = HudSettingsModel.formatValue(setting, setting.getValue());
        drawText(fr, value, rowRight - fr.getStringWidth(value), centerY - 3,
                mix(TEXT_SECONDARY, TEXT_PRIMARY, knobProgress));

        int trackX = trackX(rowRight);
        double trackY = centerY - 2;
        float span = setting.getMax() - setting.getMin();
        float progress = span <= 0f ? 0f : clamp01((setting.getValue() - setting.getMin()) / span);
        double fillW = TRACK_W * progress;
        drawRoundedRect(trackX, trackY, TRACK_W, 4, 2, TRACK_BG);
        if (fillW > 0.5) {
            drawRoundedRect(trackX, trackY, fillW, 4, 2, accent);
        }
        // 把手悬停/拖动时略微放大，并把光晕铺开：这是「可以抓住它」最直接的反馈。
        double knobX = trackX + fillW;
        double radius = 5.0 + 1.7 * knobProgress;
        if (knobProgress > 0.02f) {
            drawRoundedRect(knobX - radius - 3, trackY + 2 - radius - 3, (radius + 3) * 2, (radius + 3) * 2,
                    radius + 3, fade(accent, 0.24f * knobProgress));
        }
        drawRoundedRect(knobX - radius, trackY + 2 - radius + 1, radius * 2, radius * 2, radius, 0x55000000);
        drawRoundedRect(knobX - radius, trackY + 2 - radius, radius * 2, radius * 2, radius, 0xFFF6F8FC);
    }

    private static int trackX(int rowRight) {
        return rowRight - VALUE_W - TRACK_W;
    }

    private void drawSwitch(int x, int y, float progress, int accent, float hover) {
        drawRoundedRect(x, y, SWITCH_W, SWITCH_H, SWITCH_H * 0.5,
                mix(mix(SWITCH_OFF, 0x45FFFFFF, hover), accent, progress));
        int knob = SWITCH_H - 6;
        // 拇指按进度平移而不是二选一跳位，配合轨道颜色插值就是 iOS/Windows 11 那种切换手感。
        double knobX = x + 3 + (SWITCH_W - knob - 6) * progress;
        drawRoundedRect(knobX, y + 3.5, knob, knob, knob * 0.5, 0x40000000);
        drawRoundedRect(knobX, y + 3, knob + 2.0 * hover * progress, knob, knob * 0.5, 0xFFF8FAFD);
    }

    private void drawSwatch(int x, int y, Color color, boolean selected, float hover) {
        drawRoundedRect(x - 1, y - 1, SWATCH_W + 2, SWATCH_H + 2, 8,
                selected ? 0xFFFFD978 : mix(0x33FFFFFF, 0x7AFFFFFF, hover));
        drawRoundedRect(x, y, SWATCH_W, SWATCH_H, 7,
                (color == null ? 0x9AA6B8 : color.getRGB()) | 0xFF000000);
        if (hover > 0.05f || selected) {
            // 图标颜色按底色亮度取：色块本身就是用户选的颜色，写死深色在深紫上会看不见。
            int ink = contrastOn(color == null ? 0xFF9AA6B8 : color.getRGB() | 0xFF000000);
            drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE,
                    x + SWATCH_W / 2.0, y + SWATCH_H / 2.0, fade(ink, Math.max(hover, selected ? 1f : 0f)));
        }
    }

    /**
     * 图标胶囊按钮。{@code label} 为 {@code null} 时只画图标（22×22 的方按钮）。
     *
     * <p>{@code key} 用来存悬停进度，必须每帧稳定 —— 用常量字符串，别拼可变内容。</p>
     */
    private void drawGhostButton(String key, CFontRenderer fr, String glyph, String label,
                                 int[] box, int mouseX, int mouseY, int tint, int accent) {
        int x = box[0];
        int y = box[1];
        int w = box[2];
        int h = box[3];
        float hp = named(key, isInside(mouseX, mouseY, x, y, w, h));
        drawRoundedRect(x, y, w, h, h * 0.5, mix(BUTTON_BG, BUTTON_HOVER, hp));
        if (hp > 0.02f) {
            // 悬停时沿上沿描一道 accent：只靠背景变亮，在半透明面板上几乎看不出来。
            drawRoundedRect(x + h * 0.35, y, w - h * 0.7, 1, 1, fade(accent, hp * 0.9f));
        }
        int color = mix(tint, 0xFFFFFFFF, hp * 0.6f);
        if (label == null || label.isEmpty()) {
            drawIcon(FontManager.settingsIcon24, glyph, x + w / 2.0, y + h / 2.0, color);
        } else {
            drawIcon(FontManager.settingsIcon24, glyph, x + 13, y + h / 2.0, color);
            drawText(fr, label, x + 22, y + h / 2.0 - 3.5, color);
        }
    }

    // ==================== 主题色卡 ====================

    private int themeChipColumns() {
        return 9;
    }

    private int themeGridHeight() {
        int count = NCMTheme.ThemePreset.values().length;
        int lines = Math.max(1, (count + themeChipColumns() - 1) / themeChipColumns());
        return lines * (THEME_CHIP_SIZE + THEME_CHIP_GAP) + 2;
    }

    private void drawThemeChips(int x, int y, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            boolean selected = presets[i] == current;
            float hp = named("theme." + i, isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE));
            if (selected || hp > 0.02f) {
                drawRoundedRect(chipX - 2, chipY - 2, THEME_CHIP_SIZE + 4, THEME_CHIP_SIZE + 4, 9,
                        selected ? 0xFFF4F7FB : fade(0x88A9BCD6, hp));
            }
            drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 7,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            drawRoundedRect(chipX + 5, chipY + 5, THEME_CHIP_SIZE - 10, THEME_CHIP_SIZE - 10, 5,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT));
            if (selected) {
                drawIcon(FontManager.settingsIcon24, HudSettingsIcons.CHECK,
                        chipX + THEME_CHIP_SIZE / 2.0, chipY + THEME_CHIP_SIZE / 2.0,
                        contrastOn(0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT)));
            }
        }
    }

    /** 命中测试与 {@link #drawThemeChips} 共用同一套网格推导。 */
    private int themeChipIndexAt(int mouseX, int mouseY, int x, int y) {
        int count = NCMTheme.ThemePreset.values().length;
        for (int i = 0; i < count; i++) {
            int chipX = x + (i % themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / themeChipColumns()) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            if (isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE)) return i;
        }
        return -1;
    }

    // ==================== 取色器 ====================

    private int pickerX(int panelX, int panelW) {
        int navW = navWidth(panelW);
        return panelX + navW + Math.max(6, (panelW - navW - PICKER_W) / 2);
    }

    private int pickerY(int panelY, int panelH) {
        return panelY + Math.max(HEADER_H + 6, (panelH - PICKER_H) / 2);
    }

    private void drawColorPicker(CFontRenderer fr, int panelX, int panelY, int panelW, int panelH,
                                 int mouseX, int mouseY) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        drawSoftShadow(x, y, PICKER_W, PICKER_H, 13);
        drawRoundedRect(x, y, PICKER_W, PICKER_H, 13, 0xF71A202A);
        drawRoundedRect(x + 6, y, PICKER_W - 12, 1, 1, PANEL_EDGE);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.COLORIZE, x + 20, y + 17, 0xFFB6C4D8);
        drawText(FontManager.pf12bold,
                editingColor == HudSettingsModel.ColorTarget.CURRENT ? "当前歌词颜色" : "普通歌词颜色",
                x + 29, y + 13, TEXT_PRIMARY);
        drawGhostButton("picker.close", fr, HudSettingsIcons.CLOSE, null,
                pickerCloseButton(x, y), mouseX, mouseY, TEXT_SECONDARY, 0xFFE58B7B);

        int svX = x + 12;
        int svY = y + 34;
        drawSaturationBrightnessArea(svX, svY);
        int hueY = svY + SV_H + 12;
        drawHueSlider(svX, hueY);

        Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        drawRoundedRect(x + 12, hueY + 24, 28, HEX_INPUT_H, 7, preview.getRGB() | 0xFF000000);

        ensureHexColorInput();
        int inputX = x + 48;
        int inputY = hueY + 24;
        hexColorInput.xPosition = inputX + 6;
        hexColorInput.yPosition = inputY + 4;
        hexColorInput.width = HEX_INPUT_W - 12;
        hexColorInput.height = HEX_INPUT_H - 8;
        drawRoundedRect(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 7,
                hexColorInput.isFocused() ? 0x33FFFFFF : 0x1FFFFFFF);
        drawText(fr, hexColorInput.getText(), inputX + 7, inputY + 5, TEXT_PRIMARY);
        if (hexColorInput.isFocused()) {
            int caretX = inputX + 8 + fr.getStringWidth(hexColorInput.getText());
            drawRoundedRect(caretX, inputY + 4, 1, HEX_INPUT_H - 8, 1, 0xFFEAF3FF);
        }
        drawText(fr, "输入 HEX", inputX + HEX_INPUT_W + 9, inputY + 5, TEXT_TERTIARY);
    }

    private int[] pickerCloseButton(int pickerX, int pickerY) {
        return new int[] { pickerX + PICKER_W - 30, pickerY + 6, 20, 20 };
    }

    private void drawSaturationBrightnessArea(int x, int y) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        Gui.drawRect(x, y, x + SV_W, y + SV_H, hueRgb);
        for (int col = 0; col < SV_W; col++) {
            int alpha = (int) (255.0f * (1.0f - col / (float) (SV_W - 1)));
            Gui.drawRect(x + col, y, x + col + 1, y + SV_H, alpha << 24 | 0xFFFFFF);
        }
        for (int rowIndex = 0; rowIndex < SV_H; rowIndex++) {
            int alpha = (int) (255.0f * (rowIndex / (float) (SV_H - 1)));
            Gui.drawRect(x, y + rowIndex, x + SV_W, y + rowIndex + 1, alpha << 24);
        }
        drawPickerMarker(x + Math.round(pickerSaturation * (SV_W - 1)),
                y + Math.round((1.0f - pickerBrightness) * (SV_H - 1)));
    }

    private void drawHueSlider(int x, int y) {
        for (int col = 0; col < SV_W; col++) {
            Gui.drawRect(x + col, y, x + col + 1, y + HUE_H,
                    Color.HSBtoRGB(col / (float) (SV_W - 1), 1.0f, 1.0f) | 0xFF000000);
        }
        int markerX = x + Math.round(pickerHue * (SV_W - 1));
        Gui.drawRect(markerX - 1, y - 2, markerX + 2, y + HUE_H + 2, 0xFF000000);
        Gui.drawRect(markerX, y - 1, markerX + 1, y + HUE_H + 1, 0xFFFFFFFF);
    }

    private static void drawPickerMarker(int x, int y) {
        Gui.drawRect(x - 3, y - 3, x + 4, y - 2, 0xFF000000);
        Gui.drawRect(x - 3, y + 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 3, y - 3, x - 2, y + 4, 0xFF000000);
        Gui.drawRect(x + 3, y - 3, x + 4, y + 4, 0xFF000000);
        Gui.drawRect(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y + 2, x + 3, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x - 2, y - 2, x - 1, y + 3, 0xFFFFFFFF);
        Gui.drawRect(x + 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
    }

    // ==================== 鼠标 ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (resetConfirmationVisible) {
            if (button == 0) handleResetConfirmationClick(mouseX, mouseY);
            return;
        }
        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        int[] panel = panelBounds();
        int panelX = panel[0];
        int panelY = panel[1];
        int panelW = panel[2];
        int panelH = panel[3];

        if (editingColor != HudSettingsModel.ColorTarget.NONE
                && handlePickerClick(mouseX, mouseY, button, panelX, panelY, panelW, panelH)) {
            return;
        }

        if (!isInside(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
            // 点击面板之外：关掉取色器与搜索焦点，剩下的交给 HUD 拖拽（在 drawScreen 里按鼠标状态处理）。
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }

        int[] toggle = collapseButton(panelX, panelY, panelW, panelH);
        if (isInside(mouseX, mouseY, toggle[0], toggle[1], toggle[2], toggle[3])) {
            collapsed = !collapsed;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            return;
        }

        if (!collapsed) {
            int[] classic = classicButton(panelX, panelY, panelW, panelH);
            if (isInside(mouseX, mouseY, classic[0], classic[1], classic[2], classic[3])) {
                switchToClassic();
                return;
            }
            if (panelMoved()) {
                int[] home = restorePositionButton(panelX, panelY, panelW, panelH);
                if (isInside(mouseX, mouseY, home[0], home[1], home[2], home[3])) {
                    resetPanelPosition();
                    return;
                }
            }
        }

        // 标题栏剩下的空白处即拖动区。放在按钮判定之后，按钮才不会被拖动吞掉。
        if (isInside(mouseX, mouseY, panelX, panelY, panelW, headerHeight(panelH))) {
            long now = System.currentTimeMillis();
            if (now - lastHeaderClick < DOUBLE_CLICK_MS) {
                lastHeaderClick = 0L;
                resetPanelPosition();
                return;
            }
            lastHeaderClick = now;
            draggingPanel = true;
            panelDragOffX = mouseX - panelX;
            panelDragOffY = mouseY - panelY;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (searchInput != null) searchInput.setFocused(false);
            return;
        }
        if (collapsed) return;

        int[] reset = resetButton(panelX, panelY + panelH - FOOTER_H, panelW);
        if (isInside(mouseX, mouseY, reset[0], reset[1], reset[2], reset[3])) {
            resetConfirmationVisible = true;
            editingColor = HudSettingsModel.ColorTarget.NONE;
            draggingSlider = null;
            return;
        }

        int navW = navWidth(panelW);
        int bodyY = panelY + HEADER_H;
        int bodyH = panelH - HEADER_H - FOOTER_H;
        HudSettingsModel.Category[] categories = HudSettingsModel.categories();
        if (mouseX < panelX + navW) {
            // 行高必须和 drawNav 用同一个算法，否则窗口矮的时候点击会落到别的分类上。
            int navRowH = navRowHeight(bodyH, categories.length);
            for (int index = 0; index < categories.length; index++) {
                int rowY = bodyY + 6 + index * navRowH;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, navW - 12, navRowH - 4)) {
                    categoryIndex = index;
                    editingColor = HudSettingsModel.ColorTarget.NONE;
                    clearSearch();
                    return;
                }
            }
            return;
        }

        int contentX = panelX + navW;
        int contentW = panelW - navW;
        int searchY = bodyY + 8;
        ensureSearchInput();
        if (!searchInput.getText().isEmpty()) {
            int[] clear = searchClearButton(contentX, searchY, contentW);
            if (isInside(mouseX, mouseY, clear[0], clear[1], clear[2], clear[3])) {
                clearSearch();
                return;
            }
        }
        if (isInside(mouseX, mouseY, contentX + CONTENT_PAD, searchY, contentW - CONTENT_PAD * 2, SEARCH_H)) {
            searchInput.setFocused(true);
            searchInput.mouseClicked(mouseX, mouseY, button);
            return;
        }
        searchInput.setFocused(false);

        int listY = searchY + SEARCH_H + 8;
        int listH = Math.max(24, bodyY + bodyH - listY);
        if (!isInside(mouseX, mouseY, contentX, listY, contentW, listH)) return;

        layoutRows(contentX, listY, contentW, categories[clampCategory()]);
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.isGroupHeader()) continue;
            if (!isInside(mouseX, mouseY, row.x, row.y, row.w, row.h)) continue;
            activateRow(row, mouseX, mouseY);
            return;
        }
    }

    private void resetPanelPosition() {
        HudConfig.hudEditorPanelX = -1.0f;
        HudConfig.hudEditorPanelY = -1.0f;
        HudConfig.save();
        draggingPanel = false;
    }

    private void clearSearch() {
        if (searchInput != null) {
            searchInput.setText("");
            searchInput.setFocused(false);
        }
        searchQuery = "";
        searchScroll = 0;
    }

    private void activateRow(Row row, int mouseX, int mouseY) {
        switch (row.entry.kind) {
            case SLIDER: {
                int trackX = trackX(row.x + row.w - 14);
                // 只有点在轨道附近才动数值。之前整行都算命中，于是点标签或说明文字会把滑块
                // 直接甩到最小值 —— 一个很容易误触又不可撤销的操作。
                if (mouseX < trackX - 8 || mouseX > trackX + TRACK_W + 8) break;
                draggingSlider = row.entry.setting;
                draggingSliderTrackX = trackX;
                setSliderFromMouse(row.entry.setting, mouseX, trackX);
                break;
            }
            case TOGGLE:
            case CHOICE:
            case ACTION:
                row.entry.activate();
                break;
            case COLOR:
                beginColorEdit(row.entry);
                break;
            case THEME_GRID: {
                int chip = themeChipIndexAt(mouseX, mouseY, row.x + 14, row.y + 24);
                if (chip >= 0) {
                    NCMTheme.ThemePreset preset = NCMTheme.ThemePreset.values()[chip];
                    DownloadDynamicIsland.showTheme(NCMTheme.setCurrent(preset) == null
                            ? NCMTheme.getCurrentName() : preset.getDisplayName());
                }
                break;
            }
            default:
                break;
        }
    }

    private boolean handlePickerClick(int mouseX, int mouseY, int button,
                                      int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int[] close = pickerCloseButton(x, y);
        if (isInside(mouseX, mouseY, close[0], close[1], close[2], close[3])) {
            editingColor = HudSettingsModel.ColorTarget.NONE;
            if (hexColorInput != null) hexColorInput.setFocused(false);
            return true;
        }
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        int inputX = x + 48;
        int inputY = hueY + 24;
        ensureHexColorInput();
        if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
            hexColorInput.setFocused(true);
            hexColorInput.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        hexColorInput.setFocused(false);
        if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
            draggingSaturationBrightness = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        if (isInside(mouseX, mouseY, svX, hueY, SV_W, HUE_H)) {
            draggingHue = true;
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
            return true;
        }
        return false;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && (draggingSaturationBrightness || draggingHue
                || draggingSlider != null || settingsDirty)) {
            HudConfig.save();
            settingsDirty = false;
        }
        if (state == 0 && draggingPanel) {
            draggingPanel = false;
            HudConfig.save();
        }
        draggingSaturationBrightness = false;
        draggingHue = false;
        draggingSlider = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void updateSliderWhileDragging(int mouseX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            setSliderFromMouse(draggingSlider, mouseX, draggingSliderTrackX);
        }
    }

    private void setSliderFromMouse(HudSetting setting, int mouseX, int trackX) {
        float progress = clamp01((mouseX - trackX) / (float) TRACK_W);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
        settingsDirty = true;
    }

    private void updateColorPickerWhileDragging(int mouseX, int mouseY,
                                                int panelX, int panelY, int panelW, int panelH) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, panelX, panelY, panelW, panelH);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, int panelX, int panelY, int panelW, int panelH) {
        int x = pickerX(panelX, panelW);
        int y = pickerY(panelY, panelH);
        int svX = x + 12;
        int svY = y + 34;
        int hueY = svY + SV_H + 12;
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = clamp01((mouseX - svX) / (float) (SV_W - 1));
        }
        Color selected = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        applySelectedColor(selected);
        if (hexColorInput != null && !hexColorInput.isFocused()) {
            hexColorInput.setText(toHex(selected));
        }
    }

    private void beginColorEdit(HudSettingsModel.Entry entry) {
        editingColor = entry.colorTarget;
        Color color = entry.getColor();
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        ensureHexColorInput();
        hexColorInput.setText(toHex(color));
        hexColorInput.setFocused(false);
    }

    /** 把颜色写回当前正在编辑的那一项。声明里的 setter 只改内存，落盘在鼠标松开时统一做。 */
    private void applySelectedColor(Color selected) {
        for (HudSettingsModel.Category category : HudSettingsModel.categories()) {
            for (HudSettingsModel.Group group : category.groups) {
                for (HudSettingsModel.Entry entry : group.entries) {
                    if (entry.kind == HudSettingsModel.Kind.COLOR && entry.colorTarget == editingColor) {
                        entry.setColor(selected);
                        settingsDirty = true;
                        return;
                    }
                }
            }
        }
    }

    // ==================== 键盘 ====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (resetConfirmationVisible) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
                resetConfirmationVisible = false;
            }
            return;
        }
        if (editingColor != HudSettingsModel.ColorTarget.NONE && hexColorInput != null
                && hexColorInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                hexColorInput.setFocused(false);
                if (settingsDirty) {
                    HudConfig.save();
                    settingsDirty = false;
                }
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                hexColorInput.setFocused(false);
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput();
            }
            return;
        }
        if (searchInput != null && searchInput.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                clearSearch();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchInput.setFocused(false);
                return;
            }
            if (searchInput.textboxKeyTyped(typedChar, keyCode)) {
                searchQuery = searchInput.getText();
                searchScroll = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void applyHexColorInput() {
        if (hexColorInput == null) return;
        Color selected = parseHexColor(hexColorInput.getText());
        if (selected == null) return;
        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        applySelectedColor(selected);
    }

    private static Color parseHexColor(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        if (!normalized.matches("[0-9a-fA-F]{6}")) return null;
        try {
            return new Color(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ==================== 恢复默认确认 ====================

    private void drawResetConfirmation(CFontRenderer fr, int mouseX, int mouseY) {
        Gui.drawRect(0, 0, width, height, 0x6D000000);
        int[] box = resetDialogBounds();
        drawSoftShadow(box[0], box[1], box[2], box[3], 13);
        drawRoundedRect(box[0], box[1], box[2], box[3], 13, 0xF41A202A);
        drawRoundedRect(box[0] + 6, box[1], box[2] - 12, 1, 1, PANEL_EDGE);
        drawRoundedRect(box[0] + 16, box[1] + 18, 22, 22, 11, 0x33D96B53);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.WARNING,
                box[0] + 27, box[1] + 29, 0xFFE8836A);
        drawText(FontManager.pf12bold, "恢复默认设置？", box[0] + 46, box[1] + 20, TEXT_PRIMARY);
        drawText(fr, "歌词、灵动岛与封面的外观会恢复为默认值。", box[0] + 17, box[1] + 50, TEXT_SECONDARY);
        drawText(fr, "HUD 的位置与缩放不受影响。", box[0] + 17, box[1] + 64, TEXT_TERTIARY);

        int[] cancel = resetDialogCancel(box);
        int[] confirm = resetDialogConfirm(box);
        float cancelHover = named("dialog.cancel",
                isInside(mouseX, mouseY, cancel[0], cancel[1], cancel[2], cancel[3]));
        float confirmHover = named("dialog.confirm",
                isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3]));
        drawRoundedRect(cancel[0], cancel[1], cancel[2], cancel[3], 9, mix(BUTTON_BG, BUTTON_HOVER, cancelHover));
        drawRoundedRect(confirm[0], confirm[1], confirm[2], confirm[3], 9,
                mix(0xFFD94F4D, 0xFFF2756B, confirmHover));
        drawCenteredText(fr, "取消", cancel[0] + cancel[2] / 2.0, cancel[1] + 8, TEXT_PRIMARY);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.RESTORE,
                confirm[0] + 16, confirm[1] + confirm[3] / 2.0, 0xFFFFFFFF);
        drawText(fr, "恢复默认", confirm[0] + 26, confirm[1] + 8, 0xFFFFFFFF);
    }

    private int[] resetDialogBounds() {
        int w = Math.min(320, Math.max(244, width - 32));
        int h = 132;
        return new int[] { (width - w) / 2, Math.max(18, (height - h) / 2), w, h };
    }

    private int[] resetDialogCancel(int[] box) {
        return new int[] { box[0] + box[2] - 74 - 108 - 18, box[1] + box[3] - 34, 74, 23 };
    }

    private int[] resetDialogConfirm(int[] box) {
        return new int[] { box[0] + box[2] - 108 - 10, box[1] + box[3] - 34, 108, 23 };
    }

    private void handleResetConfirmationClick(int mouseX, int mouseY) {
        int[] box = resetDialogBounds();
        int[] confirm = resetDialogConfirm(box);
        if (isInside(mouseX, mouseY, confirm[0], confirm[1], confirm[2], confirm[3])) {
            HudConfig.resetLyricAppearance();
            HudConfig.resetDynamicIslandAppearance();
            HudConfig.resetCoverAppearance();
            HudSettingsModel.syncAnimatedCoverModuleValue();
            try {
                MuoniumPlayerExtension.getInstance().musicLyrics.loadHudEditorSettings();
            } catch (Throwable ignored) {
                // 歌词组件还没就绪时无所谓：HudConfig 已经是新值，下次加载会跟上。
            }
            settingsDirty = false;
            HudConfig.save();
        }
        resetConfirmationVisible = false;
    }

    // ==================== HUD 拖拽与预览 ====================

    private void handleDrag(boolean lmb, int dWheel, int mx, int my,
                            int px, int py, int pw, int ph, boolean isInfo) {
        if (lmb && isInside(mx, my, px, py, pw, ph)
                && !draggingSaturationBrightness && !draggingHue && draggingSlider == null) {
            if (!draggingInfo && !draggingLyrics) {
                if (isInfo) {
                    draggingInfo = true;
                } else {
                    draggingLyrics = true;
                }
                dragOffX = mx - px;
                dragOffY = my - py;
            }
        }
        if (!lmb) {
            if ((isInfo && draggingInfo) || (!isInfo && draggingLyrics)) {
                HudConfig.save();
            }
            if (isInfo) {
                draggingInfo = false;
            } else {
                draggingLyrics = false;
            }
            showSnapGuide = false;
        }
        boolean active = isInfo ? draggingInfo : draggingLyrics;
        if (active) {
            int nx = Math.max(0, Math.min(Math.max(0, width - pw), mx - dragOffX));
            int ny = Math.max(0, Math.min(Math.max(0, height - ph), my - dragOffY));
            int maxX = Math.max(0, width - pw);
            int maxY = Math.max(0, height - ph);
            int centerX = maxX / 2;
            int centerY = maxY / 2;
            boolean snapX = Math.abs(nx - centerX) < SNAP_THRESHOLD;
            boolean snapY = Math.abs(ny - centerY) < SNAP_THRESHOLD;
            showSnapGuide = snapX || snapY;
            if (snapX) nx = centerX;
            if (snapY) ny = centerY;
            if (isInfo) {
                HudConfig.infoX = maxX > 0 ? (float) nx / maxX : 0;
                HudConfig.infoY = maxY > 0 ? (float) ny / maxY : 0;
            } else {
                HudConfig.lyricX = maxX > 0 ? (float) nx / maxX : 0;
                HudConfig.lyricY = maxY > 0 ? (float) ny / maxY : 0;
            }
        }
        if (dWheel != 0 && isInside(mx, my, px, py, pw, ph)) {
            float delta = dWheel > 0 ? 0.05f : -0.05f;
            if (isInfo) {
                HudConfig.infoScale = clampHudScale(HudConfig.infoScale + delta);
            } else {
                HudConfig.lyricScale = clampHudScale(HudConfig.lyricScale + delta);
            }
            HudConfig.save();
        }
    }

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null && CloudMusic.player != null
                && !CloudMusic.player.isFinished();
    }

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.player != null && !CloudMusic.player.isFinished()
                && !CloudMusic.lyrics.isEmpty();
    }

    private void renderLiveInfoPreview(MusicInfoWidget info) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);
            info.widget.render();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void renderLiveLyricsPreview(MusicLyricsWidget lyrics, int x, int y) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);
            lyrics.renderEditorPreview(x, y, HudConfig.lyricScale);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawInfoPlaceholder(CFontRenderer fr, int x, int y, int w, int h) {
        drawSoftShadow(x, y, w, h, 10);
        drawRoundedRect(x, y, w, h, 10, 0xC52B3543);
        drawRoundedRect(x + 12, y + 17, 26, 26, 7, 0x554C617B);
        drawIcon(FontManager.settingsIcon32, HudSettingsIcons.NOTE, x + 25, y + 30, 0xCCD5DEEC);
        drawText(FontManager.pf12bold, "歌曲信息", x + 49, y + 17, 0xFFE7ECF4);
        drawText(fr, "播放歌曲后实时展示", x + 49, y + 33, TEXT_SECONDARY);
    }

    private void drawLyricsPlaceholder(CFontRenderer fr, int x, int y, int w, int h) {
        drawSoftShadow(x, y, w, h, 12);
        drawRoundedRect(x, y, w, h, 12, 0x8C31283F);
        int center = x + w / 2;
        drawCenteredText(FontManager.pf12bold, "桌面歌词", center, y + Math.max(8, h / 2 - 13), 0xFFDCCFFF);
        drawCenteredText(fr, "平滑动画与逐字染色预览", center, y + Math.max(23, h / 2 + 3), 0xFF9C91B8);
    }

    private void drawHudSelection(CFontRenderer fr, String title, int x, int y, int w, int h, int accent) {
        drawDashedBorder(x, y, w, h, accent);
        int labelY = Math.max(14, y - 5);
        drawIcon(FontManager.settingsIcon24, HudSettingsIcons.MOVE, x + 13, labelY + 4, accent);
        drawText(fr, title, x + 21, labelY, 0xFFE8EDF6);
    }

    // ==================== 新旧界面切换 ====================

    /** 切到经典界面，并把选择记到配置里，下次打开直接是这一套。 */
    private void switchToClassic() {
        HudConfig.hudEditorModernUi = false;
        HudConfig.save();
        mc.displayGuiScreen(new GuiHudEditor());
    }

    // ==================== 杂项 ====================

    private int clampCategory() {
        int count = HudSettingsModel.categories().length;
        return Math.max(0, Math.min(count - 1, categoryIndex));
    }

    /** 滚动 / 淡入的归属键：搜索态是 {@code -1}，否则是分类下标。切换它就重新起算动画。 */
    private int scrollKey() {
        return searchQuery.trim().isEmpty() ? clampCategory() : -1;
    }

    /** 搜索模式下所有分类共用一个滚动位置：结果是一张平铺的列表，跟分类无关。 */
    private int scroll() {
        if (!searchQuery.trim().isEmpty()) return searchScroll;
        if (scrollByCategory == null) return 0;
        return scrollByCategory[clampCategory()];
    }

    private void setScroll(int value, int maxScroll) {
        int clamped = Math.max(0, Math.min(maxScroll, value));
        if (!searchQuery.trim().isEmpty()) {
            searchScroll = clamped;
            return;
        }
        if (scrollByCategory != null) scrollByCategory[clampCategory()] = clamped;
    }

    private void ensureSearchInput() {
        if (searchInput == null) {
            searchInput = new GuiTextField(1, fontRendererObj, 0, 0, 100, 12);
            searchInput.setEnableBackgroundDrawing(false);
            searchInput.setMaxStringLength(28);
        }
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0, HEX_INPUT_W - 12, HEX_INPUT_H - 8);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
        }
    }

    // ==================== 动画工具 ====================

    /**
     * 命名部件的悬停进度，帧率无关地趋向 0 或 1。
     *
     * <p>{@code key} 必须每帧稳定，否则每帧都会从头开始动。表里只会攒下几十个键（按钮 + 侧栏行 +
     * 色卡），随界面生命周期一起释放，不需要清理。</p>
     */
    private float named(String key, boolean active) {
        Float stored = namedProgress.get(key);
        float now = stored == null ? (active ? 1f : 0f) : stored.floatValue();
        now = Interpolations.interpolate(now, active ? 1f : 0f, 22f);
        namedProgress.put(key, Float.valueOf(now));
        return now;
    }

    /**
     * 条目行的动画状态，见 {@link #rowProgress}。
     *
     * <p>{@code on} 只在第一次见到这个条目时用：开关的初始位置要等于它当前的状态，
     * 否则每次打开界面都会看到所有已开启的开关一起从左滑到右。</p>
     */
    private float[] progressOf(HudSettingsModel.Entry entry, boolean on) {
        float[] state = rowProgress.get(entry);
        if (state == null) {
            state = new float[] { 0f, on ? 1f : 0f, 0f };
            rowProgress.put(entry, state);
        }
        return state;
    }

    /** 把颜色的 alpha 按比例压低（保留原有 alpha 作为上限）。 */
    private static int fade(int color, float alpha) {
        int base = (color >>> 24) & 0xFF;
        int a = Math.round(base * clamp01(alpha));
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    /** 在两个 ARGB 之间线性插值，alpha 也一起插。 */
    private static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = channel(from, 24) + Math.round((channel(to, 24) - channel(from, 24)) * k);
        int r = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * k);
        int g = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * k);
        int b = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int color, int shift) {
        return (color >>> shift) & 0xFF;
    }

    /**
     * 在给定底色上可读的墨色：亮底给近黑、暗底给近白。
     *
     * <p>用 Rec.601 的感知亮度而不是三通道平均：纯蓝（平均 85）其实看起来比纯绿（平均 85）暗得多，
     * 按平均值挑会在蓝色主题上给出对比度不足的深色图标。</p>
     */
    private static int contrastOn(int background) {
        float luma = (channel(background, 16) * 0.299f
                + channel(background, 8) * 0.587f
                + channel(background, 0) * 0.114f) / 255f;
        return luma > 0.58f ? 0xFF101720 : 0xFFF4F7FB;
    }

    // ==================== 绘制原语 ====================

    /**
     * 画一枚图标，按 em 方格居中。
     *
     * <p>为什么不按字形自己的墨迹盒居中：这份图标字体保留了 Material 24 网格里的原始位置，
     * 细长的 chevron 与满格的实心图形墨迹高度差好几倍，各自居中会让 chevron 被「撑」到和实心块
     * 一样大。改成量 {@link HudSettingsIcons#LOCATOR}（一枚满格实心方块）的墨迹盒 —— 它就等于
     * em 方格相对 {@code drawString} 原点的位置 —— 再让这个方格居中，所有图标于是落在与 Material
     * 设计一致的对齐网格上。量测按字号缓存，量不出来就退回行盒居中（位置略偏，但图标仍在）。</p>
     */
    private void drawIcon(CFontRenderer font, String glyph, double centerX, double centerY, int color) {
        if (font == null || glyph == null || glyph.isEmpty()) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        GlyphInkMetrics square = emSquare(font);
        double x;
        double y;
        if (square != null) {
            x = square.alignCenterX(centerX);
            y = square.alignCenterY(centerY);
        } else {
            x = centerX - font.getStringWidthD(glyph) * .5;
            y = centerY - font.getFontHeight() * .5;
        }
        font.drawString(glyph, x, y, tinted);
    }

    private static GlyphInkMetrics emSquare(CFontRenderer font) {
        synchronized (GRID_CACHE) {
            if (GRID_CACHE.containsKey(font)) {
                return GRID_CACHE.get(font);
            }
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, HudSettingsIcons.LOCATOR.charAt(0));
        synchronized (GRID_CACHE) {
            GRID_CACHE.put(font, measured);
        }
        return measured;
    }

    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;
        if (alpha <= 0.002f) return;
        Shaders.RQ_SHADER.draw((float) x, (float) y, (float) w, (float) h,
                (float) Math.min(radius, Math.min(w, h) * 0.5), ((color >>> 16) & 0xFF) / 255.0F,
                ((color >>> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, alpha);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawSoftShadow(int x, int y, int w, int h, int radius) {
        drawRoundedRect(x + 1, y + 2, w, h, radius, 0x30000000);
        drawRoundedRect(x + 2, y + 3, w, h, radius, 0x18000000);
    }

    private void drawText(CFontRenderer font, String text, double x, double y, int color) {
        if (font == null || text == null) return;
        int tinted = layerAlpha >= 0.999f ? color : fade(color, layerAlpha);
        if (((tinted >>> 24) & 0xFF) == 0) return;
        font.drawString(text, x, y, tinted);
    }

    private void drawCenteredText(CFontRenderer font, String text, double centerX, double y, int color) {
        if (font == null || text == null) return;
        drawText(font, text, centerX - font.getStringWidthD(text) * .5, y, color);
    }

    private void beginScissor(int x, int y, int w, int h) {
        // GL_SCISSOR 对负宽高会抛 GL_INVALID_VALUE。面板尺寸是动态的，还可能在窗口缩放/最小化的
        // 那一帧被渲染，所以进入裁剪区之前先钳位，而不是把错误留给后续的 HUD 绘制。
        if (w <= 0 || h <= 0 || mc == null || mc.displayHeight <= 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        int scale = Math.max(1, new ScaledResolution(mc).getScaleFactor());
        int scissorWidth = Math.max(0, w * scale);
        int scissorHeight = Math.max(0, h * scale);
        if (scissorWidth == 0 || scissorHeight == 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, mc.displayHeight - (y + h) * scale, scissorWidth, scissorHeight);
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static void drawDashedBorder(int x, int y, int w, int h, int color) {
        int seg = 6;
        int gap = 4;
        for (int i = x; i < x + w; i += seg + gap) {
            Gui.drawRect(i, y, Math.min(i + seg, x + w), y + 1, color);
            Gui.drawRect(i, y + h - 1, Math.min(i + seg, x + w), y + h, color);
        }
        for (int i = y; i < y + h; i += seg + gap) {
            Gui.drawRect(x, i, x + 1, Math.min(i + seg, y + h), color);
            Gui.drawRect(x + w - 1, i, x + w, Math.min(i + seg, y + h), color);
        }
    }

    private static float clampHudScale(float value) {
        return Math.max(HudConfig.SCALE_MIN, Math.min(HudConfig.SCALE_MAX, value));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String pct(float x, float y, float scale) {
        return (int) (x * 100) + "%, " + (int) (y * 100) + "%, " + (int) (scale * 100) + "%";
    }
}
