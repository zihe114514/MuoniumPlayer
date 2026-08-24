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
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.MusicAudioCache;
import com.muoniumplayer.core.rendering.shader.Shaders;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.io.IOException;

/**
 * Position and scale editor for the music HUDs, including live desktop-lyric effects.
 */
public class GuiHudEditor extends GuiScreen {

    private static final int SNAP_THRESHOLD = 20;
    private static final int INFO_BASE_W = 230;
    private static final int INFO_BASE_H = 56;
    private static final int SETTINGS_W = 236;
    private static final int SETTINGS_MARGIN = 8;
    private static final int SETTINGS_HEADER_H = 28;
    private static final int COLLAPSED_SETTINGS_H = 42;
    private static final int SETTINGS_TOGGLE_W = 24;
    private static final int SECTION_H = 22;
    private static final int COLOR_ROW_H = 26;
    private static final int SLIDER_ROW_H = 28;
    private static final int THEME_CHIP_SIZE = 20;
    private static final int THEME_CHIP_GAP = 4;
    private static final int THEME_CHIP_COLUMNS = 9;
    private static final int PICKER_W = 204;
    private static final int PICKER_H = 184;
    private static final int SV_W = 180;
    private static final int SV_H = 86;
    private static final int HUE_H = 10;
    private static final int HEX_INPUT_W = 84;
    private static final int HEX_INPUT_H = 18;

    private enum EditingColor {
        NONE, NORMAL, CURRENT
    }

    private static final HudSetting[] CURRENT_SLIDERS = {
            HudSetting.CURRENT_LINE_SCALE,
            HudSetting.CURRENT_WORD_SCALE,
            HudSetting.CURRENT_GLOW,
            HudSetting.CURRENT_GLOW_RADIUS,
            HudSetting.CURRENT_BLOOM,
            HudSetting.CURRENT_TRANSITION,
            HudSetting.CURRENT_BREATH,
            HudSetting.FULLSCREEN_GLOW,
            HudSetting.OSD_TRANSITION,
            HudSetting.OSD_GLOW,
            HudSetting.OSD_BLOOM,
            HudSetting.OSD_PULSE,
            HudSetting.OSD_SMOOTHNESS,
            HudSetting.OSD_DUET_SPREAD
    };

    private static final HudSetting[] NORMAL_SLIDERS = {
            HudSetting.NORMAL_OPACITY,
            HudSetting.NORMAL_SCALE,
            HudSetting.NORMAL_GLOW,
            HudSetting.NORMAL_BLOOM,
            HudSetting.NORMAL_SPACING,
            HudSetting.EDGE_FADE,
            HudSetting.SCROLL_SMOOTHNESS,
            HudSetting.SECONDARY_OPACITY
    };

    private static final HudSetting[] ISLAND_SLIDERS = {
            HudSetting.DYNAMIC_ISLAND_SCALE,
            HudSetting.DYNAMIC_ISLAND_TEXT_SCALE,
            HudSetting.DYNAMIC_ISLAND_MAX_WIDTH,
            HudSetting.DYNAMIC_ISLAND_PROGRESS_HEIGHT,
            HudSetting.DYNAMIC_ISLAND_COMPLETION_HOLD,
            HudSetting.DYNAMIC_ISLAND_QUEUE_INTERVAL,
            HudSetting.DYNAMIC_ISLAND_EXPAND_SPEED,
            HudSetting.DYNAMIC_ISLAND_COLLAPSE_SPEED,
            HudSetting.DYNAMIC_ISLAND_CONTENT_SPEED,
            HudSetting.DYNAMIC_ISLAND_ENTRANCE_DURATION,
            HudSetting.DYNAMIC_ISLAND_OVERSHOOT,
            HudSetting.DYNAMIC_ISLAND_SPINNER_SPEED,
            HudSetting.DYNAMIC_ISLAND_AMBIENT_STATUS,
            HudSetting.DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING
    };

    private boolean draggingInfo;
    private boolean draggingLyrics;
    private int dragOffX;
    private int dragOffY;
    private boolean showSnapGuide;

    private boolean currentExpanded = true;
    private boolean normalExpanded = true;
    private boolean islandExpanded = true;
    private boolean coverExpanded = true;
    private boolean automixExpanded = true;
    private boolean storageExpanded = true;
    private boolean themeExpanded = true;
    private boolean settingsCollapsed;
    private int settingsScroll;
    private HudSetting draggingSlider;

    private EditingColor editingColor = EditingColor.NONE;
    private float pickerHue;
    private float pickerSaturation;
    private float pickerBrightness;
    private boolean draggingSaturationBrightness;
    private boolean draggingHue;
    private GuiTextField hexColorInput;
    private boolean lyricSettingsDirty;
    private boolean resetConfirmationVisible;

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ensureHexColorInput();
        settingsScroll = getSettingsLayout().clampScroll(settingsScroll, getSettingsLayout().panelHeight);
    }

    @Override
    public void onGuiClosed() {
        if (lyricSettingsDirty) {
            HudConfig.save();
            lyricSettingsDirty = false;
        }
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // The rounded-rectangle shader intentionally disables alpha testing.
        // This editor is rendered while the world remains visible, so any leaked GL
        // state would carry into Forge overlays and the next world frame (transparent
        // leaves, glass, scoreboards and third-party HUDs). Keep the complete editor,
        // including live previews, inside one state boundary.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            RenderSystem.refreshResolution();
            // Keep the current Minecraft frame visible behind the editor. GuiScreen already
            // renders on top of the game because doesGuiPauseGame() is false; drawing the
            // vanilla background here would replace that frame with an opaque gradient.

            MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
            MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
            CFontRenderer fr = getHudFont();
            if (fr == null) {
                return;
            }
            int sw = width;
            int sh = height;
            int panelX = sw - SETTINGS_W - SETTINGS_MARGIN;
            int panelY = SETTINGS_MARGIN;
            int panelH = getSettingsLayout().panelHeight;

            drawEditorBackdrop(panelX, panelY, panelH);
            DownloadDynamicIsland.renderEditorPreview();

            int wheel = resetConfirmationVisible ? 0 : Mouse.getDWheel();
            boolean overPanel = isInside(mouseX, mouseY, panelX, panelY, SETTINGS_W, panelH);
            boolean overPicker = editingColor != EditingColor.NONE && isInside(mouseX, mouseY,
                    getSettingsLayout().pickerX(panelX), getSettingsLayout().pickerY(panelY), PICKER_W, PICKER_H);
            if (wheel != 0 && overPanel) {
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll + (wheel > 0 ? -24 : 24), panelH);
                wheel = 0;
            }

            if (!resetConfirmationVisible) {
                updateColorPickerWhileDragging(mouseX, mouseY, lyrics, panelX, panelY);
                updateSliderWhileDragging(mouseX, panelX);
            }

            boolean lmb = !resetConfirmationVisible && Mouse.isButtonDown(0);
            boolean mouseOverSettings = resetConfirmationVisible || overPanel || overPicker;

            // ==== Song information HUD: draw the real card whenever a song is active. ====
            int infoW = (int) (INFO_BASE_W * HudConfig.infoScale);
            int infoH = (int) (INFO_BASE_H * HudConfig.infoScale);
            int infoX = (int) (HudConfig.infoX * (sw - infoW));
            int infoY = (int) (HudConfig.infoY * (sh - infoH));
            handleDrag(lmb && !mouseOverSettings, mouseOverSettings ? 0 : wheel,
                    mouseX, mouseY, infoX, infoY, infoW, infoH, true);
            boolean liveInfoPreview = hasLiveInfoPreview();
            if (liveInfoPreview) {
                renderLiveInfoPreview(info);
            } else {
                drawInfoPlaceholder(fr, infoX, infoY, infoW, infoH);
            }
            drawHudSelection(fr, "歌曲信息", infoX, infoY, infoW, infoH, 0xFF7DD3FC);

            // ==== Lyrics HUD ====
            float lrBaseW = lyrics.width.getValue().floatValue();
            float lrBaseH = lyrics.height.getValue().floatValue();
            int lrW = (int) (lrBaseW * HudConfig.lyricScale);
            int lrH = (int) (lrBaseH * HudConfig.lyricScale);
            int lrX = (int) (HudConfig.lyricX * (sw - lrW));
            int lrY = (int) (HudConfig.lyricY * (sh - lrH));
            handleDrag(lmb && !mouseOverSettings, mouseOverSettings ? 0 : wheel,
                    mouseX, mouseY, lrX, lrY, lrW, lrH, false);
            boolean liveLyricsPreview = hasLiveLyricsPreview();
            if (liveLyricsPreview) {
                renderLiveLyricsPreview(lyrics, lrX, lrY);
            } else {
                drawLyricsPreview(fr, lrX, lrY, lrW, lrH);
            }
            drawHudSelection(fr, "桌面歌词", lrX, lrY, lrW, lrH, 0xFFA78BFA);

            drawLyricsSettings(fr, lyrics, panelX, panelY, panelH, mouseX, mouseY);
            if (editingColor != EditingColor.NONE) {
                drawColorPicker(fr, panelX, panelY, mouseX, mouseY);
            }

            drawBottomToolbar(fr, info, lyrics, sw, sh, mouseX, mouseY);

            if (showSnapGuide) {
                int cx = sw / 2;
                int cy = sh / 2;
                Gui.drawRect(0, cy, sw, cy + 1, 0x558DDCFF);
                Gui.drawRect(cx, 0, cx + 1, sh, 0x558DDCFF);
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (resetConfirmationVisible) {
                drawResetConfirmation(fr, mouseX, mouseY);
            }
        } finally {
            // Scissor is normally closed by drawLyricsSettings, but explicitly closing it
            // before restoring attributes also covers an interrupted drawing path.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
    private void drawEditorBackdrop(int panelX, int panelY, int panelH) {
        // The game world is the editor canvas. Keep the shadow local, circular and on
        // the lower-right only; the old left-shifted black duplicate looked like a canvas.
        drawSoftCardShadow(panelX, panelY, SETTINGS_W, panelH, 13);
    }

    private void drawSoftCardShadow(int x, int y, int w, int h, int radius) {
        drawRoundedRect(x + 1, y + 2, w, h, radius, 0x30000000);
        drawRoundedRect(x + 2, y + 3, w, h, radius, 0x18000000);
    }

    private boolean hasLiveInfoPreview() {
        return CloudMusic.currentlyPlaying != null
                && CloudMusic.player != null
                && !CloudMusic.player.isFinished();
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

    private boolean hasLiveLyricsPreview() {
        return CloudMusic.player != null
                && !CloudMusic.player.isFinished()
                && !CloudMusic.lyrics.isEmpty();
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
        drawSoftCardShadow(x, y, w, h, 10);
        drawRoundedRect(x, y, w, h, 10, 0xC52B3543);
        drawRoundedRect(x + 1, y + 1, w - 2, 1, 1, 0x445E7188);
        drawRoundedRect(x + 12, y + 17, 26, 26, 7, 0x554C617B);
        drawRoundedRect(x + 18, y + 23, 14, 14, 7, 0x6689A8C9);
        drawExternalText(fr, "歌曲信息", x + 49, y + 17, 0xFFE7ECF4);
        drawExternalText(fr, "播放歌曲后实时展示", x + 49, y + 33, 0xFF8996A8);
    }

    private void drawLyricsPreview(CFontRenderer fr, int x, int y, int w, int h) {
        drawSoftCardShadow(x, y, w, h, 12);
        drawRoundedRect(x, y, w, h, 12, 0x8C31283F);
        int center = x + w / 2;
        drawCenteredExternalText(fr, "桌面歌词", center, y + Math.max(8, h / 2 - 13), 0xFFDCCFFF);
        drawCenteredExternalText(fr, "平滑动画与逐字染色预览", center, y + Math.max(23, h / 2 + 3), 0xFF9C91B8);
    }

    private void drawHudSelection(CFontRenderer fr, String title, int x, int y, int w, int h,
                                  int accent) {
        drawDashedBorder(x, y, w, h, accent);
        // Use a small marker and plain text instead of status/title pills. The actual
        // HUD preview remains unchanged, while the editor no longer adds LIVE bubbles
        // or other floating badges on top of the game view.
        int labelY = Math.max(14, y - 5);
        drawRoundedRect(x + 10, labelY + 3, 5, 5, 2, accent);
        drawExternalText(fr, title, x + 21, labelY, 0xFFE8EDF6);
    }

    private void drawBottomToolbar(CFontRenderer fr, MusicInfoWidget info, MusicLyricsWidget lyrics,
                                   int sw, int sh, int mouseX, int mouseY) {
        int barY = sh - 28;
        Gui.drawRect(0, barY, sw, sh, 0xD90D1119);
        drawRoundedRect(10, barY + 4, sw - 20, 20, 10, 0xBB1B222E);

        int resetW = 72;
        int resetH = 20;
        int resetX = sw / 2 - resetW / 2;
        boolean hoverReset = isInside(mouseX, mouseY, resetX, barY + 4, resetW, resetH);
        drawRoundedRect(resetX, barY + 4, resetW, resetH, 10,
                hoverReset ? 0xFF48617D : 0xFF303B4A);
        drawCenteredExternalText(fr, "重置位置", resetX + resetW / 2, barY + 9, 0xFFF3F6FA);

        int togW = 14;
        int togH = 14;
        int tog1X = resetX + resetW + 16;
        drawRoundedRect(tog1X, barY + 6, togW, togH, 7,
                info.isEnabled() ? 0xFF5AC18E : 0xFF4C5563);
        drawExternalText(fr, "信息栏", tog1X + togW + 4, barY + 8,
                info.isEnabled() ? 0xFFBFFFE0 : 0xFF8B95A5);
        int tog2X = tog1X + togW + 4 + fr.getStringWidth("信息栏") + 16;
        drawRoundedRect(tog2X, barY + 6, togW, togH, 7,
                lyrics.isEnabled() ? 0xFF5AC18E : 0xFF4C5563);
        drawExternalText(fr, "歌词", tog2X + togW + 4, barY + 8,
                lyrics.isEnabled() ? 0xFFBFFFE0 : 0xFF8B95A5);

        int[] modern = modernSwitchButton(sw, barY);
        drawRoundedRect(modern[0], modern[1], modern[2], modern[3], 10,
                isInside(mouseX, mouseY, modern[0], modern[1], modern[2], modern[3])
                        ? 0xFF48617D : 0xFF303B4A);
        drawCenteredExternalText(fr, "新界面", modern[0] + modern[2] / 2, barY + 9, 0xFFF3F6FA);

        String infoText = "信息栏: " + pct(HudConfig.infoX, HudConfig.infoY, HudConfig.infoScale)
                + "  歌词: " + pct(HudConfig.lyricX, HudConfig.lyricY, HudConfig.lyricScale)
                + "  · 拖拽移动  · 滚轮缩放  · 设置实时预览  · ESC 退出";
        drawExternalText(fr, infoText, sw / 2 - fr.getStringWidth(infoText) / 2, barY - 12, 0xFF9EAABD);
    }

    /**
     * 底部工具栏右侧的「新界面」入口。
     *
     * <p>刻意放在底栏而不是顶部标题栏:标题栏只有 236px 宽,已经排了折叠与恢复默认两个按钮,
     * 再塞一个就会压到「实时调整 · 自动保存」那行副标题上。底栏本来就有整条空白。</p>
     */
    private static int[] modernSwitchButton(int sw, int barY) {
        int w = 74;
        return new int[] { sw - 18 - w, barY + 4, w, 20 };
    }

    /** 切到现代界面,并把选择记住,下次打开直接是这一套。 */
    private void switchToModern() {
        if (lyricSettingsDirty) {
            lyricSettingsDirty = false;
        }
        HudConfig.hudEditorModernUi = true;
        HudConfig.save();
        mc.displayGuiScreen(new GuiHudEditorPro());
    }

    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0) return;
        float alpha = ((color >>> 24) & 0xFF) / 255.0F;
        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        Shaders.RQ_SHADER.draw((float) x, (float) y, (float) w, (float) h,
                (float) Math.min(radius, Math.min(w, h) * 0.5), red, green, blue, alpha);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
    private void drawLyricsSettings(CFontRenderer fr, MusicLyricsWidget lyrics,
                                     int x, int y, int h, int mouseX, int mouseY) {
        drawRoundedRect(x, y, SETTINGS_W, h, 13, 0xF0171C26);
        drawRoundedRect(x + 1, y + 1, SETTINGS_W - 2, SETTINGS_HEADER_H, 11, 0xFF202A37);
        drawRoundedRect(x + 12, y + 9, 6, 6, 3, 0xFF8AD7FF);
        drawExternalText(fr, "音乐 HUD 设置", x + 27, y + 8, 0xFFF4F7FB);
        drawExternalText(fr, "实时调整 · 自动保存", x + 27, y + 18, 0xFF8E9BAE);
        int toggleX = x + SETTINGS_W - SETTINGS_TOGGLE_W - 6;
        drawSmallButton(fr, settingsCollapsed ? "+" : "−", toggleX, y + 6, SETTINGS_TOGGLE_W, 17, mouseX, mouseY);
        drawSmallButton(fr, "恢复默认", x + SETTINGS_W - 96, y + 6, 60, 17, mouseX, mouseY);

        if (settingsCollapsed) {
            drawExternalText(fr, "设置面板已隐藏 · 点击右上角展开", x + 12, y + 31, 0xFF8996A8);
            return;
        }

        int contentTop = y + SETTINGS_HEADER_H;
        int contentBottom = y + h - 2;
        beginScissor(x + 1, contentTop, SETTINGS_W - 2, contentBottom - contentTop);
        try {
            int rowY = contentTop - settingsScroll;
            drawSectionHeader(fr, "当前部分", currentExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFF8C7CFF);
            rowY += SECTION_H;
            if (currentExpanded) {
                drawColorRow(fr, "当前歌词颜色", lyrics.currentLyricColor.getValue(),
                        editingColor == EditingColor.CURRENT, x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "当前歌词特效", HudConfig.currentLyricEffectsEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "OSD 逐字强调", HudConfig.osdKaraokeEmphasisEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                for (HudSetting setting : CURRENT_SLIDERS) {
                    drawSlider(fr, setting, x + 6, rowY, SETTINGS_W - 12, mouseX, mouseY);
                    rowY += SLIDER_ROW_H;
                }
            }

            drawSectionHeader(fr, "普通部分", normalExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFF65C9B5);
            rowY += SECTION_H;
            if (normalExpanded) {
                drawColorRow(fr, "普通歌词颜色", lyrics.lyricColor.getValue(),
                        editingColor == EditingColor.NORMAL, x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "普通歌词特效", HudConfig.normalLyricEffectsEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "边缘淡出", HudConfig.lyricEdgeFadeEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                for (HudSetting setting : NORMAL_SLIDERS) {
                    drawSlider(fr, setting, x + 6, rowY, SETTINGS_W - 12, mouseX, mouseY);
                    rowY += SLIDER_ROW_H;
                }
            }

            drawSectionHeader(fr, "灵动岛", islandExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFF65A9FF);
            rowY += SECTION_H;
            if (islandExpanded) {
                drawToggleRow(fr, "下载灵动岛", HudConfig.dynamicIslandEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawChoiceRow(fr, "灵动岛样式", DownloadDynamicIsland.getStyleName(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻灵动岛", HudConfig.dynamicIslandAlwaysOn,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻 · 帧率", HudConfig.dynamicIslandAmbientFps,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻 · 延迟", HudConfig.dynamicIslandAmbientPing,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻 · 时间", HudConfig.dynamicIslandAmbientClock,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻 · 正在播放", HudConfig.dynamicIslandAmbientNowPlaying,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "常驻 · 轮换显示", HudConfig.dynamicIslandAmbientRotate,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                for (HudSetting setting : ISLAND_SLIDERS) {
                    drawSlider(fr, setting, x + 6, rowY, SETTINGS_W - 12, mouseX, mouseY);
                    rowY += SLIDER_ROW_H;
                }
            }

            drawSectionHeader(fr, "封面", coverExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFFFFA971);
            rowY += SECTION_H;
            if (coverExpanded) {
                drawToggleRow(fr, animatedCoverLabel(), HudConfig.animatedCoverEnabled,
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
            }

            drawSectionHeader(fr, "无缝切换", automixExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFF5FD3E8);
            rowY += SECTION_H;
            if (automixExpanded) {
                drawToggleRow(fr, "无缝切换", AutomixSettings.isEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawChoiceRow(fr, "重叠时长", AutomixSettings.getOverlapLabel(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "跳过前奏", AutomixSettings.isSkipIntroEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "小节对齐", AutomixSettings.isBeatAlignEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "低频交换", AutomixSettings.isBassSwapEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "去人声淡入", AutomixSettings.isVocalDuckEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "节奏锁定", AutomixSettings.isTempoLockEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawToggleRow(fr, "音高跳跃", AutomixSettings.isPitchMatchEnabled(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
            }

            drawSectionHeader(fr, "存储", storageExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFFD8A657);
            rowY += SECTION_H;
            if (storageExpanded) {
                drawChoiceRow(fr, "缓存上限", NCMPlayerConfig.getMusicCacheLimitLabel(),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawChoiceRow(fr, "立即清理",
                        MusicAudioCache.formatBytes(MusicAudioCache.cachedTotalBytes()),
                        x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
            }

            drawSectionHeader(fr, "主题", themeExpanded, x + 5, rowY,
                    SETTINGS_W - 10, mouseX, mouseY, 0xFFB68CFF);
            rowY += SECTION_H;
            if (themeExpanded) {
                int hovered = themeChipIndexAt(mouseX, mouseY, x + 6, rowY + COLOR_ROW_H);
                NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
                String name = hovered >= 0 && hovered < presets.length
                        ? presets[hovered].getDisplayName()
                        : NCMTheme.getCurrentName();
                drawChoiceRow(fr, "播放器主题", name, x + 6, rowY, mouseX, mouseY);
                rowY += COLOR_ROW_H;
                drawThemeChips(x + 6, rowY, mouseX, mouseY);
                rowY += themeGridHeight();
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        int maxScroll = getSettingsLayout().maxScroll(h);
        if (maxScroll > 0) {
            int trackTop = contentTop + 3;
            int trackH = contentBottom - contentTop - 6;
            int thumbH = Math.max(22, trackH * (contentBottom - contentTop) / getSettingsLayout().contentHeight);
            int thumbY = trackTop + (trackH - thumbH) * settingsScroll / maxScroll;
            drawRoundedRect(x + SETTINGS_W - 6, trackTop, 3, trackH, 2, 0x663A4655);
            drawRoundedRect(x + SETTINGS_W - 7, thumbY, 5, thumbH, 3, 0xFF70839A);
        }
    }
    private void drawSectionHeader(CFontRenderer fr, String title, boolean expanded,
                                   int x, int y, int w, int mouseX, int mouseY, int accent) {
        boolean hover = isInside(mouseX, mouseY, x, y, w, SECTION_H);
        drawRoundedRect(x, y + 2, w, SECTION_H - 2, 8,
                hover ? 0xFF2B3543 : 0xFF222A35);
        drawRoundedRect(x, y + 5, 3, SECTION_H - 8, 2, accent);
        drawExternalText(fr, expanded ? "⌄" : "›", x + 10, y + 7, 0xFFE9EEF6);
        drawExternalText(fr, title, x + 25, y + 7, 0xFFF4F6FA);
    }
    private void drawColorRow(CFontRenderer fr, String label, Color color, boolean selected,
                              int x, int y, int mouseX, int mouseY) {
        int w = SETTINGS_W - 12;
        boolean hover = isInside(mouseX, mouseY, x, y, w, COLOR_ROW_H);
        if (hover) {
            drawRoundedRect(x, y, w, COLOR_ROW_H, 7, 0x332F4050);
        }
        drawExternalText(fr, label, x + 6, y + 9, 0xFFD7DEE8);
        drawColorSwatch(x + w - 64, y + 5, 58, 17, color, selected);
    }
    private void drawToggleRow(CFontRenderer fr, String label, boolean enabled,
                               int x, int y, int mouseX, int mouseY) {
        int width = SETTINGS_W - 12;
        boolean hover = isInside(mouseX, mouseY, x, y, width, COLOR_ROW_H);
        if (hover) {
            drawRoundedRect(x, y, width, COLOR_ROW_H, 7, 0x332F4050);
        }
        drawExternalText(fr, label, x + 6, y + 9, 0xFFD7DEE8);
        int switchX = x + width - 43;
        int switchY = y + 6;
        drawRoundedRect(switchX, switchY, 34, 14, 7,
                enabled ? 0xFF4EAA83 : 0xFF46515F);
        int knobX = enabled ? switchX + 21 : switchX + 2;
        drawRoundedRect(knobX, switchY + 2, 11, 10, 5, 0xFFF5F7FA);
    }
    private void drawChoiceRow(CFontRenderer fr, String label, String value,
                               int x, int y, int mouseX, int mouseY) {
        int width = SETTINGS_W - 12;
        boolean hover = isInside(mouseX, mouseY, x, y, width, COLOR_ROW_H);
        if (hover) {
            drawRoundedRect(x, y, width, COLOR_ROW_H, 7, 0x332F4050);
        }
        drawExternalText(fr, label, x + 6, y + 9, 0xFFD7DEE8);
        String safeValue = value == null ? "默认" : value;
        int pillW = Math.max(52, fr.getStringWidth(safeValue) + 20);
        int pillX = x + width - pillW - 6;
        drawRoundedRect(pillX, y + 5, pillW, 17, 8, hover ? 0xFF5A7391 : 0xFF435367);
        drawCenteredExternalText(fr, safeValue, pillX + pillW / 2, y + 9, 0xFFF7F9FC);
    }
    private void drawSlider(CFontRenderer fr, HudSetting setting, int x, int y, int w,
                            int mouseX, int mouseY) {
        boolean hover = isInside(mouseX, mouseY, x, y, w, SLIDER_ROW_H);
        if (hover || draggingSlider == setting) {
            drawRoundedRect(x, y, w, SLIDER_ROW_H, 7, 0x332F4050);
        }
        drawExternalText(fr, setting.getLabel(), x + 6, y + 5, 0xFFD4DCE7);
        String valueText = formatSliderValue(setting, setting.getValue());
        drawExternalText(fr, valueText, x + w - 6 - fr.getStringWidth(valueText), y + 5, 0xFFACB8C7);

        int trackX = x + 7;
        int trackY = y + 20;
        int trackW = w - 14;
        float progress = (setting.getValue() - setting.getMin()) / (setting.getMax() - setting.getMin());
        int fillX = trackX + Math.round(trackW * clamp01(progress));
        drawRoundedRect(trackX, trackY, trackW, 3, 2, 0xFF3A4553);
        drawRoundedRect(trackX, trackY, Math.max(1, fillX - trackX), 3, 2, 0xFF8A7CFF);
        drawRoundedRect(fillX - 3, trackY - 3, 7, 9, 4, 0xFFF5F7FC);
        drawRoundedRect(fillX - 1, trackY - 1, 3, 5, 2, 0xFFBDAFFF);
    }
    private void drawColorPicker(CFontRenderer fr, int panelX, int panelY, int mouseX, int mouseY) {
        int x = getSettingsLayout().pickerX(panelX);
        int y = getSettingsLayout().pickerY(panelY);
        drawSoftCardShadow(x, y, PICKER_W, PICKER_H, 12);
        drawRoundedRect(x, y, PICKER_W, PICKER_H, 12, 0xF51A202A);
        drawRoundedRect(x + 1, y + 1, PICKER_W - 2, 27, 10, 0xFF252E3B);
        drawExternalText(fr, editingColor == EditingColor.CURRENT ? "当前歌词颜色" : "普通歌词颜色",
                x + 12, y + 10, 0xFFF5F7FA);
        drawSmallButton(fr, "×", x + PICKER_W - 26, y + 6, 18, 16, mouseX, mouseY);

        int svX = x + 12;
        int svY = y + 32;
        drawSaturationBrightnessArea(svX, svY);
        int hueY = svY + SV_H + 10;
        drawHueSlider(svX, hueY);
        Color preview = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        drawRoundedRect(x + 12, hueY + 23, 26, 18, 7, preview.getRGB() | 0xFF000000);

        // Hex preview is also an editor: type #RRGGBB and apply the colour to the
        // live lyric preview as soon as the six hexadecimal digits are valid.
        ensureHexColorInput();
        int inputX = x + 46;
        int inputY = hueY + 23;
        hexColorInput.xPosition = inputX + 5;
        hexColorInput.yPosition = inputY + 4;
        hexColorInput.width = HEX_INPUT_W - 10;
        hexColorInput.height = HEX_INPUT_H - 8;
        hexColorInput.setTextColor(0xFFF4F7FB);
        drawRoundedRect(inputX, inputY, HEX_INPUT_W, HEX_INPUT_H, 6,
                hexColorInput.isFocused() ? 0xFF39495E : 0xFF2B3645);
        drawRoundedRect(inputX, inputY, HEX_INPUT_W, 1, 1,
                hexColorInput.isFocused() ? 0xFF8AB9FF : 0xFF617187);
        drawExternalText(fr, hexColorInput.getText(), inputX + 5, inputY + 4, 0xFFF4F7FB);
        if (hexColorInput.isFocused()) {
            int caretX = inputX + 6 + fr.getStringWidth(hexColorInput.getText());
            drawRoundedRect(caretX, inputY + 4, 1, Math.max(8, (int) fr.getFontHeight() - 2), 1, 0xFFEAF3FF);
        }
        drawExternalText(fr, "输入 HEX", inputX + HEX_INPUT_W + 8, hueY + 28, 0xFF8F9BAD);
    }
    private void drawSmallButton(CFontRenderer fr, String text, int x, int y, int w, int h,
                                 int mouseX, int mouseY) {
        drawRoundedRect(x, y, w, h, h * 0.5, isInside(mouseX, mouseY, x, y, w, h)
                ? 0xFF526680 : 0xFF364253);
        drawCenteredExternalText(fr, text, x + w / 2, y + 4, 0xFFF7F9FC);
    }
    /**
     * The HUD editor intentionally uses the same external font pipeline as the player UI.
     * GuiTextField is retained only for keyboard/cursor handling; its vanilla draw pass is bypassed.
     */
    private static void drawExternalText(CFontRenderer font, String text, double x, double y, int color) {
        if (font != null && text != null) {
            font.drawString(text, x, y, color);
        }
    }

    private static void drawCenteredExternalText(CFontRenderer font, String text, double centerX, double y, int color) {
        if (font != null && text != null) {
            font.drawString(text, centerX - font.getStringWidthD(text) * .5, y, color);
        }
    }

    private static CFontRenderer getHudFont() {
        return FontManager.pf12 != null ? FontManager.pf12 : FontManager.pf14;
    }

    private static int getHudFontWidth(String text) {
        CFontRenderer font = getHudFont();
        return font == null ? 0 : font.getStringWidth(text == null ? "" : text);
    }
    private void drawColorSwatch(int x, int y, int w, int h, Color color, boolean selected) {
        drawRoundedRect(x, y, w, h, 7, color.getRGB() | 0xFF000000);
        int border = selected ? 0xFFFFD978 : 0xFF8A97A8;
        drawRoundedRect(x - 1, y - 1, w + 2, 2, 1, border);
        drawRoundedRect(x - 1, y + h - 1, w + 2, 2, 1, border);
        drawRoundedRect(x - 1, y, 2, h, 1, border);
        drawRoundedRect(x + w - 1, y, 2, h, 1, border);
    }
    private void drawSaturationBrightnessArea(int x, int y) {
        int hueRgb = Color.HSBtoRGB(pickerHue, 1.0f, 1.0f) | 0xFF000000;
        Gui.drawRect(x, y, x + SV_W, y + SV_H, hueRgb);
        for (int col = 0; col < SV_W; col++) {
            int alpha = (int) (255.0f * (1.0f - col / (float) (SV_W - 1)));
            Gui.drawRect(x + col, y, x + col + 1, y + SV_H, alpha << 24 | 0xFFFFFF);
        }
        for (int row = 0; row < SV_H; row++) {
            int alpha = (int) (255.0f * (row / (float) (SV_H - 1)));
            Gui.drawRect(x, y + row, x + SV_W, y + row + 1, alpha << 24);
        }
        int markerX = x + Math.round(pickerSaturation * (SV_W - 1));
        int markerY = y + Math.round((1.0f - pickerBrightness) * (SV_H - 1));
        drawPickerMarker(markerX, markerY);
    }

    private void drawHueSlider(int x, int y) {
        for (int col = 0; col < SV_W; col++) {
            float hue = col / (float) (SV_W - 1);
            Gui.drawRect(x + col, y, x + col + 1, y + HUE_H,
                    Color.HSBtoRGB(hue, 1.0f, 1.0f) | 0xFF000000);
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
            int nx = mx - dragOffX;
            int ny = my - dragOffY;
            int maxX = Math.max(0, width - pw);
            int maxY = Math.max(0, height - ph);
            nx = Math.max(0, Math.min(maxX, nx));
            ny = Math.max(0, Math.min(maxY, ny));
            int centerX = (width - pw) / 2;
            int centerY = (height - ph) / 2;
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

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (resetConfirmationVisible) {
            if (button == 0) {
                handleResetConfirmationClick(mouseX, mouseY);
            }
            return;
        }

        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
        int panelX = width - SETTINGS_W - SETTINGS_MARGIN;
        int panelY = SETTINGS_MARGIN;
        int panelH = getSettingsLayout().panelHeight;

        if (editingColor != EditingColor.NONE) {
            int pickerX = getSettingsLayout().pickerX(panelX);
            int pickerY = getSettingsLayout().pickerY(panelY);
            if (isInside(mouseX, mouseY, pickerX + PICKER_W - 24, pickerY + 5, 16, 16)) {
                editingColor = EditingColor.NONE;
                if (hexColorInput != null) hexColorInput.setFocused(false);
                return;
            }
            int svX = pickerX + 12;
            int svY = pickerY + 32;
            int hueY = svY + SV_H + 10;
            int inputX = pickerX + 46;
            int inputY = hueY + 23;
            ensureHexColorInput();
            if (isInside(mouseX, mouseY, inputX, inputY, HEX_INPUT_W, HEX_INPUT_H)) {
                hexColorInput.mouseClicked(mouseX, mouseY, button);
                return;
            }
            hexColorInput.setFocused(false);
            if (isInside(mouseX, mouseY, svX, svY, SV_W, SV_H)) {
                draggingSaturationBrightness = true;
                applyPickerAt(mouseX, mouseY, lyrics, panelX, panelY);
                return;
            }
            if (isInside(mouseX, mouseY, svX, hueY, SV_W, HUE_H)) {
                draggingHue = true;
                applyPickerAt(mouseX, mouseY, lyrics, panelX, panelY);
                return;
            }
        }

        if (isInside(mouseX, mouseY, panelX, panelY, SETTINGS_W, panelH)) {
            // Header actions must be checked before the header itself consumes the click.
            int toggleX = panelX + SETTINGS_W - SETTINGS_TOGGLE_W - 6;
            if (isInside(mouseX, mouseY, toggleX, panelY + 6, SETTINGS_TOGGLE_W, 17)) {
                settingsCollapsed = !settingsCollapsed;
                settingsScroll = 0;
                editingColor = EditingColor.NONE;
                draggingSlider = null;
                draggingSaturationBrightness = false;
                draggingHue = false;
                return;
            }

            if (settingsCollapsed) {
                return;
            }

            if (isInside(mouseX, mouseY, panelX + SETTINGS_W - 96, panelY + 6, 60, 17)) {
                resetConfirmationVisible = true;
                editingColor = EditingColor.NONE;
                draggingSlider = null;
                draggingSaturationBrightness = false;
                draggingHue = false;
                return;
            }

            if (mouseY < panelY + SETTINGS_HEADER_H) {
                return;
            }

            int rowY = panelY + SETTINGS_HEADER_H - settingsScroll;
            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                currentExpanded = !currentExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (currentExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    beginColorEdit(EditingColor.CURRENT, lyrics.currentLyricColor.getValue());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.currentLyricEffectsEnabled = !HudConfig.currentLyricEffectsEnabled;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.osdKaraokeEmphasisEnabled = !HudConfig.osdKaraokeEmphasisEnabled;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                for (HudSetting setting : CURRENT_SLIDERS) {
                    if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, SLIDER_ROW_H)) {
                        draggingSlider = setting;
                        setSliderFromMouse(setting, mouseX, panelX);
                        return;
                    }
                    rowY += SLIDER_ROW_H;
                }
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                normalExpanded = !normalExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (normalExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    beginColorEdit(EditingColor.NORMAL, lyrics.lyricColor.getValue());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.normalLyricEffectsEnabled = !HudConfig.normalLyricEffectsEnabled;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.lyricEdgeFadeEnabled = !HudConfig.lyricEdgeFadeEnabled;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                for (HudSetting setting : NORMAL_SLIDERS) {
                    if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, SLIDER_ROW_H)) {
                        draggingSlider = setting;
                        setSliderFromMouse(setting, mouseX, panelX);
                        return;
                    }
                    rowY += SLIDER_ROW_H;
                }
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                islandExpanded = !islandExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (islandExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandEnabled = !HudConfig.dynamicIslandEnabled;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    DownloadDynamicIsland.cycleStyle();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAlwaysOn = !HudConfig.dynamicIslandAlwaysOn;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAmbientFps = !HudConfig.dynamicIslandAmbientFps;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAmbientPing = !HudConfig.dynamicIslandAmbientPing;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAmbientClock = !HudConfig.dynamicIslandAmbientClock;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAmbientNowPlaying = !HudConfig.dynamicIslandAmbientNowPlaying;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudConfig.dynamicIslandAmbientRotate = !HudConfig.dynamicIslandAmbientRotate;
                    HudConfig.save();
                    return;
                }
                rowY += COLOR_ROW_H;
                for (HudSetting setting : ISLAND_SLIDERS) {
                    if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, SLIDER_ROW_H)) {
                        draggingSlider = setting;
                        setSliderFromMouse(setting, mouseX, panelX);
                        return;
                    }
                    rowY += SLIDER_ROW_H;
                }
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                coverExpanded = !coverExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (coverExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    setAnimatedCoverEnabled(!HudConfig.animatedCoverEnabled);
                    return;
                }
                rowY += COLOR_ROW_H;
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                automixExpanded = !automixExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (automixExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setEnabled(!AutomixSettings.isEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.cycleOverlapSeconds();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setSkipIntroEnabled(!AutomixSettings.isSkipIntroEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setBeatAlignEnabled(!AutomixSettings.isBeatAlignEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setBassSwapEnabled(!AutomixSettings.isBassSwapEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setVocalDuckEnabled(!AutomixSettings.isVocalDuckEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setTempoLockEnabled(!AutomixSettings.isTempoLockEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    AutomixSettings.setPitchMatchEnabled(!AutomixSettings.isPitchMatchEnabled());
                    return;
                }
                rowY += COLOR_ROW_H;
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                storageExpanded = !storageExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (storageExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    NCMPlayerConfig.cycleMusicCacheLimit();
                    MusicAudioCache.enforceLimitAsync();
                    return;
                }
                rowY += COLOR_ROW_H;
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    HudSettingsModel.sweepAudioCache();
                    return;
                }
                rowY += COLOR_ROW_H;
            }

            if (isInside(mouseX, mouseY, panelX + 5, rowY, SETTINGS_W - 10, SECTION_H)) {
                themeExpanded = !themeExpanded;
                settingsScroll = getSettingsLayout().clampScroll(settingsScroll, panelH);
                return;
            }
            rowY += SECTION_H;
            if (themeExpanded) {
                if (isInside(mouseX, mouseY, panelX + 6, rowY, SETTINGS_W - 12, COLOR_ROW_H)) {
                    applyTheme(NCMTheme.next());
                    return;
                }
                rowY += COLOR_ROW_H;
                int chip = themeChipIndexAt(mouseX, mouseY, panelX + 6, rowY);
                if (chip >= 0) {
                    applyTheme(NCMTheme.setCurrent(NCMTheme.ThemePreset.values()[chip]));
                    return;
                }
                rowY += themeGridHeight();
            }
            return;
        }

        if (editingColor != EditingColor.NONE) {
            editingColor = EditingColor.NONE;
        }

        int barY = height - 28;
        int[] modern = modernSwitchButton(width, barY);
        if (isInside(mouseX, mouseY, modern[0], modern[1], modern[2], modern[3])) {
            switchToModern();
            return;
        }
        int resetW = 72;
        int resetX = width / 2 - resetW / 2;
        if (isInside(mouseX, mouseY, resetX, barY + 4, resetW, 20)) {
            HudConfig.infoX = 0.02f;
            HudConfig.infoY = 0.02f;
            HudConfig.infoScale = 1.0f;
            HudConfig.lyricX = 0.5f;
            HudConfig.lyricY = 0.85f;
            HudConfig.lyricScale = 1.0f;
            HudConfig.save();
            return;
        }
        int togW = 14;
        int togH = 14;
        int tog1X = resetX + resetW + 16;
        MusicInfoWidget info = MuoniumPlayerExtension.getInstance().musicInfo;
        if (isInside(mouseX, mouseY, tog1X, barY + 6, togW, togH)) {
            info.setEnabled(!info.isEnabled());
        }
        int tog2X = tog1X + togW + 4 + getHudFontWidth("信息栏") + 16;
        if (isInside(mouseX, mouseY, tog2X, barY + 6, togW, togH)) {
            lyrics.setEnabled(!lyrics.isEnabled());
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && (draggingSaturationBrightness || draggingHue
                || draggingSlider != null || lyricSettingsDirty)) {
            HudConfig.save();
            lyricSettingsDirty = false;
        }
        draggingSaturationBrightness = false;
        draggingHue = false;
        draggingSlider = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (resetConfirmationVisible) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
                resetConfirmationVisible = false;
            }
            return;
        }
        if (editingColor != EditingColor.NONE && hexColorInput != null && hexColorInput.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                hexColorInput.setFocused(false);
                if (lyricSettingsDirty) {
                    HudConfig.save();
                    lyricSettingsDirty = false;
                }
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                hexColorInput.setFocused(false);
                return;
            }
            if (hexColorInput.textboxKeyTyped(typedChar, keyCode)) {
                applyHexColorInput(MuoniumPlayerExtension.getInstance().musicLyrics);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RCONTROL) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void drawResetConfirmation(CFontRenderer fr, int mouseX, int mouseY) {
        // A translucent dark mask preserves the live game preview without introducing the old white canvas.
        Gui.drawRect(0, 0, width, height, 0x6D000000);
        int dialogW = Math.min(310, Math.max(238, width - 32));
        int dialogH = 126;
        int dialogX = (width - dialogW) / 2;
        int dialogY = Math.max(18, (height - dialogH) / 2);
        drawSoftCardShadow(dialogX, dialogY, dialogW, dialogH, 12);
        drawRoundedRect(dialogX, dialogY, dialogW, dialogH, 12, 0xF01A202A);
        drawRoundedRect(dialogX + 16, dialogY + 17, 20, 20, 10, 0xFFD96B53);
        drawCenteredExternalText(fr, "!", dialogX + 26, dialogY + 22, 0xFFFFFFFF);
        drawExternalText(fr, "恢复默认设置？", dialogX + 47, dialogY + 18, 0xFFF4F7FB);
        drawExternalText(fr, "歌词与灵动岛外观将恢复为默认值。", dialogX + 17, dialogY + 49, 0xFFABB6C5);

        int cancelW = 74;
        int confirmW = 106;
        int buttonY = dialogY + dialogH - 34;
        int cancelX = dialogX + dialogW - cancelW - confirmW - 18;
        int confirmX = dialogX + dialogW - confirmW - 10;
        boolean hoverCancel = isInside(mouseX, mouseY, cancelX, buttonY, cancelW, 23);
        boolean hoverConfirm = isInside(mouseX, mouseY, confirmX, buttonY, confirmW, 23);
        drawRoundedRect(cancelX, buttonY, cancelW, 23, 8, hoverCancel ? 0xFF465363 : 0xFF303B49);
        drawRoundedRect(confirmX, buttonY, confirmW, 23, 8, hoverConfirm ? 0xFFEC665D : 0xFFD94F4D);
        drawCenteredExternalText(fr, "取消", cancelX + cancelW / 2, buttonY + 7, 0xFFF4F7FB);
        drawCenteredExternalText(fr, "恢复默认", confirmX + confirmW / 2, buttonY + 7, 0xFFFFFFFF);
    }

    private void handleResetConfirmationClick(int mouseX, int mouseY) {
        int dialogW = Math.min(310, Math.max(238, width - 32));
        int dialogH = 126;
        int dialogX = (width - dialogW) / 2;
        int dialogY = Math.max(18, (height - dialogH) / 2);
        int cancelW = 74;
        int confirmW = 106;
        int buttonY = dialogY + dialogH - 34;
        int cancelX = dialogX + dialogW - cancelW - confirmW - 18;
        int confirmX = dialogX + dialogW - confirmW - 10;

        if (isInside(mouseX, mouseY, confirmX, buttonY, confirmW, 23)) {
            applyAppearanceDefaults();
        }
        // Cancel button and clicking outside the card both safely dismiss the confirmation.
        resetConfirmationVisible = false;
    }

    private void applyAppearanceDefaults() {
        MusicLyricsWidget lyrics = MuoniumPlayerExtension.getInstance().musicLyrics;
        HudConfig.resetLyricAppearance();
        HudConfig.resetDynamicIslandAppearance();
        HudConfig.resetCoverAppearance();
        syncAnimatedCoverModuleValue();
        lyrics.loadHudEditorSettings();
        lyricSettingsDirty = false;
        HudConfig.save();
    }

    /** 委托给 {@link HudSettingsModel#animatedCoverLabel()},两套界面显示同一段文案。 */
    private static String animatedCoverLabel() {
        return HudSettingsModel.animatedCoverLabel();
    }

    private void setAnimatedCoverEnabled(boolean enabled) {
        HudConfig.animatedCoverEnabled = enabled;
        syncAnimatedCoverModuleValue();
        HudConfig.save();
    }

    /** 委托给 {@link HudSettingsModel#syncAnimatedCoverModuleValue()}。 */
    private static void syncAnimatedCoverModuleValue() {
        HudSettingsModel.syncAnimatedCoverModuleValue();
    }

    private void beginColorEdit(EditingColor type, Color color) {
        editingColor = type;
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        ensureHexColorInput();
        hexColorInput.setText(toHex(color));
        hexColorInput.setFocused(false);
    }

    private void ensureHexColorInput() {
        if (hexColorInput == null) {
            hexColorInput = new GuiTextField(0, fontRendererObj, 0, 0, HEX_INPUT_W - 10, HEX_INPUT_H - 8);
            hexColorInput.setEnableBackgroundDrawing(false);
            hexColorInput.setMaxStringLength(7);
            hexColorInput.setTextColor(0xFFF4F7FB);
        }
    }

    private void applyHexColorInput(MusicLyricsWidget lyrics) {
        if (hexColorInput == null) return;
        Color selected = parseHexColor(hexColorInput.getText());
        if (selected == null) return;

        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        pickerHue = hsb[0];
        pickerSaturation = hsb[1];
        pickerBrightness = hsb[2];
        applySelectedColor(selected, lyrics);
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

    private void applySelectedColor(Color selected, MusicLyricsWidget lyrics) {
        if (editingColor == EditingColor.NORMAL) {
            lyrics.lyricColor.setValue(selected);
            HudConfig.lyricColorRgb = selected.getRGB();
        } else if (editingColor == EditingColor.CURRENT) {
            lyrics.currentLyricColor.setValue(selected);
            HudConfig.currentLyricColorRgb = selected.getRGB();
        }
        lyricSettingsDirty = true;
    }

    private void updateColorPickerWhileDragging(int mouseX, int mouseY, MusicLyricsWidget lyrics,
                                                int panelX, int panelY) {
        if (!Mouse.isButtonDown(0)) return;
        if (draggingSaturationBrightness || draggingHue) {
            applyPickerAt(mouseX, mouseY, lyrics, panelX, panelY);
        }
    }

    private void applyPickerAt(int mouseX, int mouseY, MusicLyricsWidget lyrics,
                               int panelX, int panelY) {
        int pickerX = getSettingsLayout().pickerX(panelX);
        int pickerY = getSettingsLayout().pickerY(panelY);
        int svX = pickerX + 12;
        int svY = pickerY + 32;
        int hueY = svY + SV_H + 10;
        if (draggingSaturationBrightness) {
            pickerSaturation = clamp01((mouseX - svX) / (float) (SV_W - 1));
            pickerBrightness = 1.0f - clamp01((mouseY - svY) / (float) (SV_H - 1));
        }
        if (draggingHue) {
            pickerHue = clamp01((mouseX - svX) / (float) (SV_W - 1));
        }
        Color selected = Color.getHSBColor(pickerHue, pickerSaturation, pickerBrightness);
        applySelectedColor(selected, lyrics);
        if (hexColorInput != null && !hexColorInput.isFocused()) {
            hexColorInput.setText(toHex(selected));
        }
    }

    private void updateSliderWhileDragging(int mouseX, int panelX) {
        if (draggingSlider != null && Mouse.isButtonDown(0)) {
            setSliderFromMouse(draggingSlider, mouseX, panelX);
        }
    }

    private void setSliderFromMouse(HudSetting setting, int mouseX, int panelX) {
        int trackX = panelX + 13;
        int trackW = SETTINGS_W - 26;
        float progress = clamp01((mouseX - trackX) / (float) trackW);
        setting.setValue(setting.getMin() + (setting.getMax() - setting.getMin()) * progress);
        lyricSettingsDirty = true;
    }

    /**
     * 滑块右侧的数值文本。
     *
     * <p>委托给 {@link HudSettingsModel#formatValue}:同一项在经典界面与现代界面必须显示同样的
     * 单位与精度。原来这里自己写了一份 switch,新增滑块时只要漏改一处,那一边就会退回裸小数。</p>
     */
    private String formatSliderValue(HudSetting setting, float value) {
        return HudSettingsModel.formatValue(setting, value);
    }

    /** 主题色块网格：每格显示预设的页面底色与强调色，点击立即切换。 */
    private void drawThemeChips(int x, int y, int mouseX, int mouseY) {
        NCMTheme.ThemePreset[] presets = NCMTheme.ThemePreset.values();
        NCMTheme.ThemePreset current = NCMTheme.getCurrent();
        for (int i = 0; i < presets.length; i++) {
            int chipX = x + (i % THEME_CHIP_COLUMNS) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / THEME_CHIP_COLUMNS) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            boolean selected = presets[i] == current;
            boolean hover = isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE);
            if (selected || hover) {
                drawRoundedRect(chipX - 1, chipY - 1, THEME_CHIP_SIZE + 2, THEME_CHIP_SIZE + 2, 7,
                        selected ? 0xFFF4F7FB : 0x66A9BCD6);
            }
            drawRoundedRect(chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE, 6,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            drawRoundedRect(chipX + 4, chipY + 4, THEME_CHIP_SIZE - 8, THEME_CHIP_SIZE - 8, 4,
                    0xFF000000 | presets[i].getColor(NCMScreen.ColorType.ACCENT));
        }
    }

    /** 命中测试与 {@link #drawThemeChips} 共用同一套网格推导，避免两处走形。 */
    private static int themeChipIndexAt(int mouseX, int mouseY, int x, int y) {
        int count = NCMTheme.ThemePreset.values().length;
        for (int i = 0; i < count; i++) {
            int chipX = x + (i % THEME_CHIP_COLUMNS) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            int chipY = y + (i / THEME_CHIP_COLUMNS) * (THEME_CHIP_SIZE + THEME_CHIP_GAP);
            if (isInside(mouseX, mouseY, chipX, chipY, THEME_CHIP_SIZE, THEME_CHIP_SIZE)) {
                return i;
            }
        }
        return -1;
    }

    private static int themeGridHeight() {
        int count = NCMTheme.ThemePreset.values().length;
        int rows = Math.max(1, (count + THEME_CHIP_COLUMNS - 1) / THEME_CHIP_COLUMNS);
        return rows * (THEME_CHIP_SIZE + THEME_CHIP_GAP) + 2;
    }

    private static void applyTheme(NCMTheme.ThemePreset preset) {
        DownloadDynamicIsland.showTheme(preset == null ? NCMTheme.getCurrentName() : preset.getDisplayName());
    }

    private HudEditorLayout.Metrics getSettingsLayout() {
        return HudEditorLayout.calculate(height, settingsCollapsed, currentExpanded, normalExpanded,
                islandExpanded, coverExpanded, automixExpanded, storageExpanded, themeExpanded, SETTINGS_MARGIN,
                COLLAPSED_SETTINGS_H, SETTINGS_HEADER_H, SECTION_H, COLOR_ROW_H, SLIDER_ROW_H,
                CURRENT_SLIDERS.length, NORMAL_SLIDERS.length, ISLAND_SLIDERS.length,
                themeGridHeight(), PICKER_W);
    }
    private void beginScissor(int x, int y, int w, int h) {
        // GL_SCISSOR rejects negative width/height with GL_INVALID_VALUE (1281). The editor is
        // dynamically sized and can be rendered during a resize/minimize frame, so clamp before
        // entering the clipped settings region rather than leaking an error into later HUD passes.
        if (w <= 0 || h <= 0 || mc == null || mc.displayHeight <= 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        ScaledResolution scaled = new ScaledResolution(mc);
        int scale = Math.max(1, scaled.getScaleFactor());
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
