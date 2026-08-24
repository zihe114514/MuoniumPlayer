package com.muoniumplayer.core.screens.ncm;

import lombok.Getter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import today.opai.api.features.ExtensionScreen;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.screens.ncm.panels.ControlsBar;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.screens.ncm.panels.NavigateBar;
import com.muoniumplayer.core.screens.ncm.panels.NavigationLoadingOverlay;
import com.muoniumplayer.core.screens.ncm.panels.PlaylistPanel;
import com.muoniumplayer.core.screens.ncm.panels.PersonalFmPanel;
import com.muoniumplayer.core.screens.ncm.panels.PlayQueuePanel;
import com.muoniumplayer.core.utils.cursor.CursorUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:47
 */
public class NCMScreen extends ExtensionScreen implements SharedConstants, SharedRenderingConstants {

    /** 统一的播放器外框线宽；内容裁剪使用同一尺寸，避免模糊污染外框。 */
    public static final double PLAYER_BORDER_THICKNESS = .8;

    @Getter
    private static NCMScreen instance = new NCMScreen();

    float alpha = 0f;
    boolean closing = false;

    Panel basePanel = new Panel();

    @Getter
    NavigateBar playlistsPanel;

    RectWidget currentPanelBg = new RectWidget();

    float prevAnimatingPanelAlpha = 0f;
    NCMPanel prevAnimatingPanel = null;
    NCMPanel currentPanel = null;
    float curPanelAlphaAnimation = 0f;

    @Getter
    ControlsBar controlsBar;

    /** 底部“下一首播放”抽屉；与 controlsBar 一样由本屏幕直接渲染，不属于 basePanel。 */
    @Getter
    PlayQueuePanel playQueuePanel;

    public MusicLyricsPanel musicLyricsPanel = null;

    /** 主题色插值由 NCMTheme 负责；此渲染器只负责按钮起点的波浪与玻璃高光。 */
    private final ThemeTransitionRenderer themeTransitionRenderer = new ThemeTransitionRenderer();

    /**
     * 播放器尺寸通过真实布局边界动画，而不是在最外层叠加 GL scale。
     * 这样鼠标命中、Stencil 裁剪、Framebuffer 和文字换行始终共享同一坐标系。
     */
    private double animatedPanelScale = -1.0;
    private double panelWidth = -1.0;
    private double panelHeight = -1.0;

    /**
     * 加入歌单弹窗（模态覆盖层）。为 null 表示未打开。
     */
    public AddToPlaylistOverlay addToPlaylistOverlay = null;

    /** 双平台账号管理（含二维码登录二级页面）的模态覆盖层。 */
    public AccountManagerOverlay accountManagerOverlay = null;

    /** 二级音乐来源菜单：固定内容平台与 GD音乐台 聚合音源统一在此处管理。 */
    public MusicSourceOverlay musicSourceOverlay = null;

    /** 主题调色板：主题按钮右键打开的二级菜单，实时预览主题颜色配置项。 */
    public ThemePaletteOverlay themePaletteOverlay = null;

    /** 对取消收藏等破坏性操作的二次确认层。 */
    public ConfirmationOverlay confirmationOverlay = null;

    /**
     * 表示是否需要重新布局, 当用户信息和用户歌单加载完设置为 true,
     * 然后会自动进行重新布局并设为 false
     */
    private boolean dirty = true;

    public NCMScreen() {

    }

    @Override
    public void initGui() {
        alpha = 0f;
        closing = false;

        this.checkDirty();

        if (this.musicLyricsPanel != null)
            this.musicLyricsPanel.onInit();

        Keyboard.enableRepeatEvents(true);
        CursorUtils.setCursor(CursorUtils.ARROW);
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void checkDirty() {
        if (this.dirty) {
            this.dirty = false;

            /*
             * 刷新帐号/歌单会重建侧栏和控制栏，但内容面板并不属于 basePanel，
             * 因此可以安全保留。此前这里无条件 new HomePanel()，导致用户在
             * 歌单详情、搜索结果等页面点击刷新后被强制送回主页。
             */
            NCMPanel panelBeforeLayout = this.currentPanel;
            this.layout();

            if (panelBeforeLayout == null) {
                // 首次打开播放器仍默认进入主页。
                this.setCurrentPanel(new HomePanel());
            } else if (this.playlistsPanel != null) {
                // 新侧栏需要重新映射当前面板的选中状态；内容页面保持原样。
                this.playlistsPanel.selectCurrentPanel(panelBeforeLayout);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    public void layout() {
        this.basePanel.getChildren().clear();

        RoundedRectWidget bg = new RoundedRectWidget();
        bg.setBeforeRenderCallback(() -> {
            bg.setBounds(0, 0, bg.getParentWidth(), bg.getParentHeight());
            bg.setRadius(this.getPlayerCornerRadius());
            bg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            // 液态玻璃保留足够实体感，同时让游戏画面能轻微透入形成材质层次。
            bg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.14f);
        });
        this.basePanel.addChild(bg);

        ThemeGlassWidget glassSurface = new ThemeGlassWidget();
        glassSurface.setBeforeRenderCallback(() ->
                glassSurface.setBounds(0, 0, glassSurface.getParentWidth(), glassSurface.getParentHeight()));
        this.basePanel.addChild(glassSurface);

        this.basePanel.setBeforeRenderCallback(() -> this.basePanel.center());

        this.playlistsPanel = new NavigateBar();
        this.basePanel.addChild(this.playlistsPanel);

        this.basePanel.addChild(this.currentPanelBg);

        this.currentPanelBg.setBeforeRenderCallback(() -> {
            double controlsHeight = Math.min(this.getPanelHeight(), Math.max(28.0, this.getPanelHeight() * .07));
            this.currentPanelBg.setBounds(
                    playlistsPanel.getWidth(),
                    0,
                    Math.max(0.0, this.currentPanelBg.getParentWidth() - playlistsPanel.getWidth()),
                    Math.max(0.0, this.getPanelHeight() - controlsHeight)
            );
            this.currentPanelBg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            this.currentPanelBg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.24f);
        });

        this.controlsBar = new ControlsBar();
        this.controlsBar.onInit();

        this.playQueuePanel = new PlayQueuePanel();
        this.playQueuePanel.onInit();
    }

    public double getSpacing() {
        return 16.0;
    }

    public double getPlayerBorderThickness() {
        return PLAYER_BORDER_THICKNESS;
    }

    private double getPlayerCornerRadius() {
        return Math.max(8.0, Math.min(18.0, this.getPanelHeight() * .028));
    }

    public double getPanelWidth() {
        if (this.panelWidth > 0) return this.panelWidth;
        return this.getAvailablePanelWidth() * this.getEffectiveTargetScale();
    }

    public double getPanelHeight() {
        if (this.panelHeight > 0) return this.panelHeight;
        return this.getAvailablePanelHeight() * this.getEffectiveTargetScale();
    }

    public double getPanelX() {
        return RenderSystem.getWidth() * .5 - this.getPanelWidth() * .5;
    }

    public double getPanelY() {
        return RenderSystem.getHeight() * .5 - this.getPanelHeight() * .5;
    }

    private double getAvailablePanelWidth() {
        return Math.max(1.0, RenderSystem.getWidth() - this.getSpacing() * 2);
    }

    private double getAvailablePanelHeight() {
        return Math.max(1.0, RenderSystem.getHeight() - this.getSpacing() * 2);
    }

    /** 返回用户选择的真实尺寸；响应式子组件负责在较小面板中重新排版。 */
    private double getEffectiveTargetScale() {
        return NCMPlayerConfig.getPlayerScale();
    }

    private void updatePanelScaleAnimation() {
        double targetScale = this.getEffectiveTargetScale();
        if (this.animatedPanelScale < 0) {
            this.animatedPanelScale = targetScale;
        } else {
            this.animatedPanelScale = Interpolations.interpolate(this.animatedPanelScale, targetScale, .18f);
            if (Math.abs(this.animatedPanelScale - targetScale) < .0005) {
                this.animatedPanelScale = targetScale;
            }
        }

        this.panelWidth = this.getAvailablePanelWidth() * this.animatedPanelScale;
        this.panelHeight = this.getAvailablePanelHeight() * this.animatedPanelScale;
    }

    @Override
    public void drawScreen(int mX, int mY) {
        // 响应式布局：GuiScreen 打开期间 RenderGameOverlayEvent 不触发，width/height/scaleFactor
        // 停留在上一次游戏帧值。每帧刷新 ScaledResolution，保证窗口 resize / GUI Scale 变化后
        // 布局与鼠标坐标映射立即正确。
        RenderSystem.refreshResolution();
        // The root rounded clip needs the main Minecraft framebuffer's stencil
        // attachment before any player pixels are drawn (DrawScreenEvent.Post is
        // too late for the first frame).
        Framebuffer.updateMcFramebuffer();
        this.updatePanelScaleAnimation();

        if (closing && alpha <= 0.02f)
            api.displayScreen(null);

        alpha = Interpolations.interpolate(alpha, closing ? 0f : 1f, 0.4f);

        CursorUtils.resetOverride();

//        Shaders.GAUSSIAN_BLUR_SHADER.run(Collections.singletonList(() -> {
//            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), hexColor(1, 1, 1, alpha));
//        }));

        this.checkDirty();

        int dWheel = Mouse.getDWheel();

        RenderSystem.FIXED_SCALE = true;

        // —— GL 状态隔离（关键修复：播放器打开/关闭时半透明元素发白）——
        // NCMScreen 同样用裸 GL11 绘制（GLStateManagerImpl 直接透传 GL11，绕过 Minecraft 的
        // GlStateManager 缓存），且内部 StencilClipManager 会改 stencil/colorMask/depthMask、
        // ScrollText 与面板可能改投影矩阵。若不在边界保存/恢复，会与 Minecraft 互相污染状态。
        // 此处保存全部 GL 属性与投影矩阵，并在 drawScreen 结束时恢复。
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        // 重置为已知 2D GUI 状态，避免继承游戏世界/HUD 渲染遗留的 blend/alpha/color。
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);

        api.getGLStateManager().pushMatrix();
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;
        api.getGLStateManager().scale(xScale, yScale, 1);

        // 弹窗为模态：滚轮只应作用于弹窗自己的列表，不能穿透到下层的 basePanel/controlsBar/歌词页。
        // 弹窗是居中绘制在歌单之上的，鼠标停在弹窗列表上时同样落在下层列表里，不拦截就会两层一起滚。
        // 队列抽屉同理：它盖在内容页上，滚它的时候下面的歌单必须停住。
        boolean queueCapturesWheel = this.playQueuePanel != null
                && this.playQueuePanel.capturesWheel(mouseX, mouseY);
        int baseDWheel = (isModalOverlayActive() || queueCapturesWheel) ? 0 : dWheel;
        int lyricsDWheel = isModalOverlayActive() ? 0 : dWheel;
        int queueDWheel = isModalOverlayActive() ? 0 : dWheel;

        this.scaleAtPos(RenderSystem.getWidth() * .5, RenderSystem.getHeight() * .5, 0.9 + (alpha * 0.1));

        this.basePanel.setBounds(this.getPanelWidth(), this.getPanelHeight());
        this.basePanel.setPosition(this.getPanelX(), this.getPanelY());
        final double cornerRadius = this.getPlayerCornerRadius();

        StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
        if (this.musicLyricsPanel == null || this.musicLyricsPanel.alpha <= .9f) {
            this.basePanel.setAlpha(alpha);
            this.basePanel.renderWidget(mouseX, mouseY, baseDWheel);
            // Re-assert the root rounded clip after child/framebuffer rendering.
            StencilClipManager.restoreActiveClip();

            float alphaInterpolateSpeed = 0.4f;
            if (this.prevAnimatingPanel != null) {
                this.prevAnimatingPanel.setAlpha(this.prevAnimatingPanelAlpha = Interpolations.interpolate(this.prevAnimatingPanelAlpha, 0f, alphaInterpolateSpeed));
                this.prevAnimatingPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 0.9 + (this.prevAnimatingPanel.getAlpha() * 0.1));
                this.prevAnimatingPanel.renderWidget(mouseX, mouseY, baseDWheel);
                api.getGLStateManager().popMatrix();

                if (this.prevAnimatingPanelAlpha <= 0.02f)
                    this.prevAnimatingPanel = null;
            } else if (this.currentPanel != null) {
                curPanelAlphaAnimation = Interpolations.interpolate(curPanelAlphaAnimation, 1f, alphaInterpolateSpeed);
                this.currentPanel.setAlpha(Math.min(this.basePanel.getAlpha(), curPanelAlphaAnimation));
                this.currentPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 1.1 - (curPanelAlphaAnimation * 0.1));

                this.currentPanel.renderWidget(mouseX, mouseY, baseDWheel);
                api.getGLStateManager().popMatrix();

                StencilClipManager.endClip();
            }

            // 导航等待遮罩：盖在内容页之上，并且裁在内容区里 —— 失败提示可能比内容区还宽，
            // 不裁会糊到左侧导航栏或控制栏上。
            if (this.navigationLoading.isVisible()) {
                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                        this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));
                this.navigationLoading.render(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                        this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), alpha);
                StencilClipManager.restoreActiveClip();
                StencilClipManager.endClip();
            }

            // 队列抽屉盖在内容页之上、控制栏之下，并且被裁剪在内容区域内，
            // 因此它的滑入滑出只会在播放器里发生，不会溢到外框上。
            if (this.playQueuePanel != null && this.playQueuePanel.isVisible()) {
                this.playQueuePanel.setAlpha(alpha);
                this.playQueuePanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                        this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());
                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(),
                        this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));
                this.playQueuePanel.renderWidget(mouseX, mouseY, queueDWheel);
                StencilClipManager.restoreActiveClip();
                StencilClipManager.endClip();
            }

            this.controlsBar.setAlpha(alpha);
            this.controlsBar.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY() + this.currentPanelBg.getHeight(), this.currentPanelBg.getWidth(), this.getPanelHeight() - this.currentPanelBg.getHeight());
            this.controlsBar.renderWidget(mouseX, mouseY, baseDWheel);
            StencilClipManager.restoreActiveClip();
        }
        StencilClipManager.endClip();

        if (this.musicLyricsPanel != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            roundedRect(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, getColor(ColorType.GENERIC_BACKGROUND)
                            | ((int) (this.musicLyricsPanel.alpha * 255)) << 24);
            this.musicLyricsPanel.onRender(mouseX, mouseY, basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), lyricsDWheel);
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();

            if (this.musicLyricsPanel.shouldClose())
                this.musicLyricsPanel = null;
        }

        // 波浪绘制在播放器内容之上、模态弹窗之下，并复用播放器圆角 Stencil。
        if (this.themeTransitionRenderer.isActive()) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.themeTransitionRenderer.renderWave(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), alpha);
            StencilClipManager.endClip();
        }
        // 加入歌单弹窗：渲染在面板之上、与 basePanel 同一坐标空间，作为模态覆盖层。
        if (this.addToPlaylistOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.addToPlaylistOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.addToPlaylistOverlay.setAlpha(alpha);
            this.addToPlaylistOverlay.renderWidget(mouseX, mouseY, dWheel);

            if (this.addToPlaylistOverlay.shouldClose())
                this.addToPlaylistOverlay = null;
            StencilClipManager.endClip();
        }

        // 音乐来源菜单是侧栏来源按钮的二级入口，阻止其下方歌单和搜索控件接收输入。
        if (this.musicSourceOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.musicSourceOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.musicSourceOverlay.setAlpha(alpha);
            this.musicSourceOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.musicSourceOverlay.shouldClose()) {
                this.musicSourceOverlay.dispose();
                this.musicSourceOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 主题调色板与其它二级菜单同层，阻止下方歌单与控件接收输入。
        if (this.themePaletteOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.themePaletteOverlay.setBounds(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight());
            this.themePaletteOverlay.setAlpha(alpha);
            this.themePaletteOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.themePaletteOverlay.shouldClose()) {
                this.themePaletteOverlay.dispose();
                this.themePaletteOverlay = null;
            }
            StencilClipManager.endClip();
        }
        // 账号管理始终位于最上层，并独占鼠标/滚轮输入。
        if (this.accountManagerOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.accountManagerOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.accountManagerOverlay.setAlpha(alpha);
            this.accountManagerOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.accountManagerOverlay.shouldClose()) {
                this.accountManagerOverlay.dispose();
                this.accountManagerOverlay = null;
            }
            StencilClipManager.endClip();
        }

        // 二次确认始终位于所有播放器内容之上，并独占鼠标/滚轮输入。
        if (this.confirmationOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.confirmationOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.confirmationOverlay.setAlpha(alpha);
            this.confirmationOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.confirmationOverlay.shouldClose()) {
                this.confirmationOverlay = null;
            }
            StencilClipManager.endClip();
        }

        roundedOutline(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                cornerRadius, getPlayerBorderThickness(), new Color(255, 255, 255,
                        Math.max(0, Math.min(255, (int) (alpha * 25)))));
        float glassAmount = NCMTheme.getLiquidGlassAmount();
        if (glassAmount > 0.001f) {
            int glassBorderAlpha = Math.max(0, Math.min(255, (int) (alpha * glassAmount * 88)));
            roundedOutlineGradient(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, .72,
                    new Color(92, 138, 196, glassBorderAlpha / 3),
                    new Color(255, 255, 255, glassBorderAlpha),
                    new Color(115, 164, 230, glassBorderAlpha / 2),
                    new Color(225, 244, 255, glassBorderAlpha));
            roundedRect(basePanel.getX() + cornerRadius * 1.25, basePanel.getY() + .75,
                    Math.max(1.0, basePanel.getWidth() - cornerRadius * 2.5), .7, .35,
                    1.0f, 1.0f, 1.0f, alpha * glassAmount * .30f);
        }
        api.getGLStateManager().popMatrix();
        RenderSystem.FIXED_SCALE = false;
        CursorUtils.setOverride();

        // 恢复投影矩阵与全部 GL 属性（替代原先只 enableTexture2D 的不完整恢复）。
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();

    }

    /** 由主题按钮传入绝对 GUI 坐标，保证涟漪确实从按钮处开始。 */
    public void cycleThemeFrom(double originX, double originY) {
        NCMTheme.next();
        this.themeTransitionRenderer.begin(originX, originY, NCMTheme.getTargetColor(ColorType.ACCENT));
    }

    private final class ThemeGlassWidget extends AbstractWidget<ThemeGlassWidget> {
        private ThemeGlassWidget() {
            this.setClickable(false);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float amount = NCMTheme.getLiquidGlassAmount() * this.getAlpha();
            if (amount <= 0.001f) return;
            themeTransitionRenderer.renderLiquidGlassSurface(this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), amount);
        }
    }
    public LoginRenderer loginRenderer = null;

    int currentActionPointer = 0;
    List<Runnable> actions = new ArrayList<>();

    /** 导航等待遮罩。见 {@link #navigateAsync}。 */
    private final NavigationLoadingOverlay navigationLoading = new NavigationLoadingOverlay();

    public void setCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);

        if (panel != null) {
            Runnable action = () -> this.innerSetCurrentPanel(panel, false);

            if (actions.isEmpty()) {
                currentActionPointer = 0;
                actions.add(action);
            } else {
                ++ currentActionPointer;

                while (actions.size() > currentActionPointer + 1)
                    actions.remove(actions.size() - 1);

                if (currentActionPointer < actions.size()) {
                    actions.set(currentActionPointer, action);
                } else {
                    actions.add(action);
                }
            }
        }
    }

    /**
     * 换页但<b>不新增历史记录</b>，而是覆盖当前这一条。
     *
     * <p>给"中转页"用：一个页面加载完就把用户送到别处时，历史里不该留下它。留下来的后果是按返回
     * 会退回那个中转页，它的 {@code onInit} 又重新加载一次再把人送走 —— 返回键看起来失效。</p>
     */
    public void replaceCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);
        if (panel == null) return;

        Runnable action = () -> this.innerSetCurrentPanel(panel, false);
        if (actions.isEmpty()) {
            currentActionPointer = 0;
            actions.add(action);
            return;
        }
        while (actions.size() > currentActionPointer + 1)
            actions.remove(actions.size() - 1);
        actions.set(currentActionPointer, action);
    }

    /**
     * 先在当前页面之上转一枚加载转子，在工作线程取数据，最后<b>一次</b>切到目标页面。
     *
     * <p>导航栏里「排行榜」「最近播放」这类入口的目标页面取决于网络结果（有内容才有页面可去），
     * 原来的做法是先切到一个空的中转面板显示"正在获取…"、数据回来再切一次 —— 用户看到两次页面
     * 淡入，历史栈里还多了一条回不去的记录。改成这样以后只有一次切换、一条历史。</p>
     *
     * @param label  给用户看的目标名，例如「排行榜」；只用于文案
     * @param loader <b>在工作线程</b>调用，返回要切去的面板；返回 {@code null} 表示没内容可展示，
     *               此时留在当前页面并给一条会自动消失的提示。它的 {@code onInit} 仍由主线程调用，
     *               所以 loader 里只该取数据、不要碰 GL
     */
    public void navigateAsync(String label, Supplier<NCMPanel> loader) {
        if (loader == null) return;
        // 等待期间忽略重复点击：连点会并发发出好几个同样的请求，最后还会互相覆盖切页。
        if (this.navigationLoading.isLoading()) return;

        final String targetName = label == null ? "" : label;
        this.navigationLoading.beginLoading(targetName);
        MultiThreadingUtil.runAsync(() -> {
            NCMPanel loaded = null;
            String failure = null;
            try {
                loaded = loader.get();
            } catch (Throwable throwable) {
                failure = throwable.getMessage();
                System.err.println("[Music] 导航加载失败（" + targetName + "）: " + throwable);
            }
            final NCMPanel panel = loaded;
            final String error = failure;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (panel != null) {
                    this.navigationLoading.clear();
                    this.setCurrentPanel(panel);
                    return;
                }
                this.navigationLoading.showMessage(error == null || error.trim().isEmpty()
                        ? (targetName.isEmpty() ? "暂无可显示内容" : targetName + "暂无可显示内容")
                        : "获取失败：" + error.trim());
            });
        });
    }

    /**
     * Rebuilds the visible playlist panel after asynchronously loaded metadata
     * changes its row badges. Other panels are intentionally left untouched so
     * an account refresh cannot trigger duplicate home/search requests.
     */
    public void reloadCurrentPanel() {
        if (this.currentPanel instanceof PlaylistPanel || this.currentPanel instanceof PersonalFmPanel) {
            this.currentPanel.onInit();
        }
    }
    private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit) {
        this.prevAnimatingPanel = this.currentPanel;
        this.prevAnimatingPanelAlpha = 1.0f;
        this.currentPanel = panel;
        if (panel != null) {
            if (shouldCallInit)
                this.currentPanel.onInit();
            this.currentPanel.setAlpha(0);
            this.curPanelAlphaAnimation = 0f;
        }
        if (this.playlistsPanel != null) {
            this.playlistsPanel.selectCurrentPanel(panel);
        }
    }

    /**
     * Returns to the immediately preceding content page. The same history is
     * used by the existing mouse side-button navigation, so the page animation
     * and lifecycle remain consistent with the rest of the player.
     */
    public void navigateBack() {
        if (currentActionPointer > 0) {
            --currentActionPointer;
            actions.get(currentActionPointer).run();
            return;
        }

        // A standalone secondary panel can still offer a useful escape route.
        if (!(this.currentPanel instanceof HomePanel)) {
            this.setCurrentPanel(new HomePanel());
        }
    }

    /**
     * 打开“加入歌单”弹窗。由 MusicWidget 的「+」按钮调用。
     * 复用原项目 PlayList.addToList(musicId) → CloudMusicApi.playlistTracks("add", pid, musicId)。
     */
    public void openAddToPlaylist(Music music) {
        if (music != null && music.isQQ()) {
            return; // QQ 曲目不可提交到网易云歌单。
        }
        this.addToPlaylistOverlay = new AddToPlaylistOverlay(music);
        this.addToPlaylistOverlay.onInit();
    }

    /** Opens a modal confirmation without running the action until the user explicitly confirms. */
    public void openConfirmation(String title, String message, String confirmText, Runnable onConfirm) {
        this.confirmationOverlay = new ConfirmationOverlay(title, message, confirmText, onConfirm);
        this.confirmationOverlay.onInit();
    }

    public void openAccountManager() {
        if (this.accountManagerOverlay != null) this.accountManagerOverlay.dispose();
        this.accountManagerOverlay = new AccountManagerOverlay();
        this.accountManagerOverlay.onInit();
    }

    /** 打开主题调色板；重复打开时先回收上一次的实例，避免两层叠在一起。 */
    public void openThemePalette() {
        if (this.themePaletteOverlay != null) this.themePaletteOverlay.dispose();
        this.themePaletteOverlay = new ThemePaletteOverlay();
        this.themePaletteOverlay.onInit();
    }

    /** Opens the second-level source center without changing the current content page. */
    public void openMusicSourceManager() {
        if (this.musicSourceOverlay != null) this.musicSourceOverlay.dispose();
        this.musicSourceOverlay = new MusicSourceOverlay();
        this.musicSourceOverlay.onInit();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {

        if (this.confirmationOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.confirmationOverlay.cancel();
            }
            return;
        }

        if (this.themePaletteOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.themePaletteOverlay.handleEscape();
            }
            return;
        }

        if (this.musicSourceOverlay != null) {
            if (this.musicSourceOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.musicSourceOverlay.handleEscape();
            }
            return;
        }
        if (this.accountManagerOverlay != null) {
            // The account overlay contains editable Cookie fields. Previously this
            // modal consumed every key before its widget tree could see it, so both
            // normal typing and Ctrl+V paste appeared to do nothing.
            if (this.accountManagerOverlay.onKeyTypedReceived(typedChar, keyCode)) {
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.accountManagerOverlay.handleEscape();
            }
            return;
        }

        // 加入歌单弹窗为模态：仅处理 ESC，其余按键不穿透到下层面板。
        if (this.addToPlaylistOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE)
                this.addToPlaylistOverlay = null;
            return;
        }

        if (this.basePanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (this.currentPanel != null && this.currentPanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {

            // 先收起最上层的临时界面，和其它覆盖层的 ESC 行为保持一致。
            if (this.playQueuePanel != null && this.playQueuePanel.isOpen()) {
                this.playQueuePanel.setOpen(false);
                return;
            }

            if (this.musicLyricsPanel != null)
                this.musicLyricsPanel.close();
            else
                closing = true;

        }

        // 与全局快捷键共用 CloudMusic.togglePlayPause()，两处的判空与暂停语义始终一致。
        if (keyCode == Keyboard.KEY_SPACE) {
            CloudMusic.togglePlayPause();
        }

    }

    /**
     * 是否有模态弹窗正在显示。点击链路（{@link #mouseClicked}）本来就是逐层 return 的模态处理，
     * 滚轮必须用同一套判断，否则弹窗里滚动会连带下层一起滚。
     */
    private boolean isModalOverlayActive() {
        return this.confirmationOverlay != null
                || this.themePaletteOverlay != null
                || this.musicSourceOverlay != null
                || this.accountManagerOverlay != null
                || this.addToPlaylistOverlay != null;
    }

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {

        double xScale = RenderSystem.getWidthNotScaled() / (RenderSystem.getFixedWidth() * .5);
        double yScale = RenderSystem.getHeightNotScaled() / (RenderSystem.getFixedHeight() * .5);
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;

        if (this.confirmationOverlay != null) {
            this.confirmationOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.themePaletteOverlay != null) {
            this.themePaletteOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.musicSourceOverlay != null) {
            this.musicSourceOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }
        if (this.accountManagerOverlay != null) {
            this.accountManagerOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        // 加入歌单弹窗优先捕获鼠标，阻止点击穿透到下层歌单/歌曲列表。
        if (this.addToPlaylistOverlay != null) {
            this.addToPlaylistOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (musicLyricsPanel == null) {
            // The quality pop-up visually overlaps the song list. Consume its
            // full bounds before dispatching to the list to prevent click-through.
            if (this.controlsBar.consumeQualityMenuClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            // 队列抽屉同样遮住了歌曲列表：它范围内的点击必须由它吃掉，否则会穿透触发播放。
            if (this.playQueuePanel != null && this.playQueuePanel.consumeClick(mouseX, mouseY, mouseButton)) {
                return;
            }
            this.basePanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            if (this.currentPanel != null)
                this.currentPanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            this.controlsBar.onMouseClickReceived(mouseX, mouseY, mouseButton);

            // forward
            if (mouseButton == 4) {

                // is last
                if (currentActionPointer >= actions.size() - 1) {
                    currentActionPointer = actions.size() - 1;
                } else {
                    currentActionPointer ++;
                    actions.get(currentActionPointer).run();
                }

            }
            // go back
            else if (mouseButton == 3) {
                this.navigateBack();
            }

        } else {
            this.musicLyricsPanel.mouseClicked(mouseX, mouseY, mouseButton);
        }

    }

    /** 播放器主题的颜色语义；具体颜色由 {@link NCMTheme} 提供。 */
    public enum ColorType {

        GENERIC_BACKGROUND("页面底色"),
        ELEMENT_BACKGROUND("控件底色"),
        ELEMENT_HOVER("控件悬停"),
        PRIMARY_TEXT("主要文字"),
        SECONDARY_TEXT("次要文字"),
        ACCENT("强调色"),
        ACCENT_HOVER("强调悬停"),
        INPUT_BACKGROUND("输入框底色"),
        NAVIGATION_BACKGROUND("导航底色"),
        BORDER("描边");

        private final String displayName;

        ColorType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return this.displayName;
        }

    }

    public static int getColor(ColorType type) {
        return NCMTheme.getColor(type);
    }
}
