package com.muoniumplayer.core.screens;

import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 「没装 ffmpeg」的全屏提示。
 *
 * <p>刻意用<b>原版</b> {@link GuiScreen} + {@link GuiButton} + {@code fontRendererObj} 实现，而不是
 * 复用播放器界面里那套 {@code NCMPanel} 弹窗：这个提示要在<b>主菜单</b>就弹出来，那时播放器界面、
 * 主题色、离屏 framebuffer 都还没有进入过工作状态；原版控件不依赖 Mod 自己的渲染栈，也不会把
 * 自定义 shader 的 GL 状态泄漏给主菜单的全景背景。中文由原版的 unicode 字体页渲染。</p>
 *
 * <p>只是提示，不是拦路：三条出口（前往下载 / 我才不管呢喵 / 永不展示）都会回到原来的界面，
 * ESC 也等价于「我才不管呢喵」。缺 ffmpeg 完全可以正常用播放器，只是动态封面与 B 站音频解析会受影响。</p>
 */
public class GuiFfmpegNotice extends GuiScreen {

    private static final int BUTTON_DOWNLOAD = 1;
    private static final int BUTTON_IGNORE = 2;

    /** 标题：用户给的原话的第一句。 */
    private static final String HEADING = "您的电脑似乎没有ffmpeg喵~要不要安装一个喵？！";
    /** 正文：用户给的原话的其余部分。 */
    private static final String BODY =
            "当然如果不安装也是可以使用的喵！ 但是会影响一些功能喵！ 例如动态封面，B站解析可能会受到影响的喵喵喵";
    private static final String NEVER_SHOW = "永不展示";

    /** 正文块的最大宽度。再宽一行字就太长、读起来要来回扫。 */
    private static final int MAX_TEXT_WIDTH = 340;
    /** 标题最大放大倍数。原版字体在 1.5 倍以内仍然是整数像素对齐的。 */
    private static final float HEADING_MAX_SCALE = 1.5F;
    /** 两个按钮的宽度差就是需求里的「稍微大」和「稍微小」。高度必须是 20：原版按钮贴图就是 20 像素高。 */
    private static final int BUTTON_HEIGHT = 20;
    private static final int DOWNLOAD_BUTTON_WIDTH = 200;
    private static final int IGNORE_BUTTON_WIDTH = 128;

    // 竖向排版的各段留白。全部集中在这里，contentHeight 与实际摆放用的是同一批常量，
    // 否则面板高度和里面的东西会各算一套、在某些窗口尺寸下互相压住。
    private static final int PANEL_TOP_PAD = 18;
    private static final int HEADING_GAP = 12;
    private static final int BODY_GAP = 16;
    private static final int BUTTON_GAP = 6;
    private static final int LINK_GAP = 8;
    private static final int PANEL_BOTTOM_PAD = 8;
    private static final int LINE_GAP = 2;

    /** 返回目标。主菜单弹出时是主菜单本身，游戏内弹出时是 {@code null}（即关闭界面）。 */
    private final GuiScreen parent;

    /** 换行后的正文。宽度不变时不重复换行。 */
    private List<String> bodyLines = new ArrayList<String>();
    private int wrappedForWidth = -1;

    private GuiButton downloadButton;
    private GuiButton ignoreButton;

    private int textWidth;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int headingY;
    private float headingScale = 1.0F;
    private int bodyTop;
    private int statusTop;
    private int downloadButtonTop;
    private int ignoreButtonTop;
    private int linkTop;
    private int neverShowLeft;
    private int neverShowRight;

    /** 浏览器打不开时显示的兜底说明（地址已复制到剪贴板）。 */
    private String status;

    public GuiFfmpegNotice(GuiScreen parent) {
        this.parent = parent;
    }

    /**
     * 每 tick 调一次：条件满足就把提示顶到最前面。
     *
     * <p>不用 {@code GuiOpenEvent} 换掉主菜单，因为 ffmpeg 探测是在后台线程上跑的，主菜单出现的那
     * 一刻结论往往还没出来——换 GUI 只有一次机会，轮询则是下一 tick 再问一次就行。</p>
     *
     * <p>两个触发点：主菜单（正常启动路径，也就是需求里的「游戏启动后」与「游戏主页面」），以及"已经在
     * 游戏里且没有打开任何界面"（直进存档/直连服务器的启动参数会跳过主菜单）。后者实际上只在主菜单
     * 没能弹的时候才会走到：弹过一次就标记本次启动已提示。</p>
     */
    public static void tickAutoShow(Minecraft mc) {
        if (mc == null) return;
        if (!FfmpegNotice.shouldShow()) return;

        GuiScreen current = mc.currentScreen;
        boolean atMainMenu = current instanceof GuiMainMenu;
        boolean inGameIdle = current == null && mc.theWorld != null && mc.thePlayer != null;
        if (!atMainMenu && !inGameIdle) return;

        FfmpegNotice.markShownThisSession();
        mc.displayGuiScreen(new GuiFfmpegNotice(atMainMenu ? current : null));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        layout();

        int centerX = this.width / 2;
        this.downloadButton = new GuiButton(BUTTON_DOWNLOAD,
                centerX - DOWNLOAD_BUTTON_WIDTH / 2, this.downloadButtonTop,
                DOWNLOAD_BUTTON_WIDTH, BUTTON_HEIGHT, "前往下载");
        this.ignoreButton = new GuiButton(BUTTON_IGNORE,
                centerX - IGNORE_BUTTON_WIDTH / 2, this.ignoreButtonTop,
                IGNORE_BUTTON_WIDTH, BUTTON_HEIGHT, "我才不管呢喵！");
        this.buttonList.add(this.downloadButton);
        this.buttonList.add(this.ignoreButton);
    }

    /**
     * 按当前窗口尺寸算出正文换行、面板范围、两个按钮与「永不展示」的位置。
     *
     * <p>自下而上摆按钮和「永不展示」，自上而下摆标题和正文，面板高度取两边加起来的和：这样任何一段
     * 文字变长（例如换行多了一行、或者出现了打开浏览器失败的说明）时，面板会跟着变高，而不会让按钮
     * 压在正文上。</p>
     */
    private void layout() {
        this.textWidth = Math.max(120, Math.min(MAX_TEXT_WIDTH, this.width - 60));
        if (this.wrappedForWidth != this.textWidth) {
            this.wrappedForWidth = this.textWidth;
            List<String> lines;
            try {
                lines = this.fontRendererObj.listFormattedStringToWidth(BODY, this.textWidth);
            } catch (Throwable ignored) {
                // 换行只是排版：出问题也要让用户看得到出口按钮。
                lines = null;
            }
            if (lines == null || lines.isEmpty()) {
                lines = new ArrayList<String>();
                lines.add(BODY);
            }
            this.bodyLines = lines;
        }

        int lineHeight = this.fontRendererObj.FONT_HEIGHT + LINE_GAP;
        int headingWidth = Math.max(1, this.fontRendererObj.getStringWidth(HEADING));
        this.headingScale = Math.min(HEADING_MAX_SCALE, (float) this.textWidth / (float) headingWidth);
        if (this.headingScale < .5F) this.headingScale = .5F;

        int headingHeight = (int) Math.ceil(this.fontRendererObj.FONT_HEIGHT * this.headingScale);
        int bodyHeight = this.bodyLines.size() * lineHeight;
        int statusHeight = this.status == null ? 0 : lineHeight + 2;
        int linkHeight = this.fontRendererObj.FONT_HEIGHT + 4;

        int contentHeight = PANEL_TOP_PAD + headingHeight + HEADING_GAP + bodyHeight + statusHeight
                + BODY_GAP + BUTTON_HEIGHT + BUTTON_GAP + BUTTON_HEIGHT
                + LINK_GAP + linkHeight + PANEL_BOTTOM_PAD;

        // 面板至少要装得下最宽的那个按钮（前往下载 200px），否则窄窗口下按钮会横着穿出底板。
        int panelWidth = Math.min(this.textWidth + 40, Math.max(80, this.width - 8));
        panelWidth = Math.max(panelWidth, Math.min(Math.max(80, this.width - 8), DOWNLOAD_BUTTON_WIDTH + 16));
        // 上限用「窗口高度 - 8」而不是一个写死的数：这样 panelTop 一定 >= 4、panelBottom 一定 <= height - 4，
        // 面板永远不会顶出屏幕（原版把缩放后的分辨率保证在 320x240 以上，正常情况下根本到不了这个上限）。
        int panelHeight = Math.min(Math.max(40, this.height - 8), contentHeight);

        this.panelLeft = this.width / 2 - panelWidth / 2;
        this.panelRight = this.panelLeft + panelWidth;
        this.panelTop = this.height / 2 - panelHeight / 2;
        this.panelBottom = this.panelTop + panelHeight;

        this.headingY = this.panelTop + PANEL_TOP_PAD;
        this.bodyTop = this.headingY + headingHeight + HEADING_GAP;
        this.statusTop = this.bodyTop + bodyHeight;

        this.linkTop = this.panelBottom - PANEL_BOTTOM_PAD - linkHeight;
        this.ignoreButtonTop = this.linkTop - LINK_GAP - BUTTON_HEIGHT;
        this.downloadButtonTop = this.ignoreButtonTop - BUTTON_GAP - BUTTON_HEIGHT;

        int neverWidth = this.fontRendererObj.getStringWidth(NEVER_SHOW);
        this.neverShowLeft = this.width / 2 - neverWidth / 2 - 4;
        this.neverShowRight = this.neverShowLeft + neverWidth + 8;

        if (this.downloadButton != null) {
            this.downloadButton.xPosition = this.width / 2 - DOWNLOAD_BUTTON_WIDTH / 2;
            this.downloadButton.yPosition = this.downloadButtonTop;
        }
        if (this.ignoreButton != null) {
            this.ignoreButton.xPosition = this.width / 2 - IGNORE_BUTTON_WIDTH / 2;
            this.ignoreButton.yPosition = this.ignoreButtonTop;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        // 每帧重算：出现「打不开浏览器」那一行说明时面板要变高，按钮也要跟着往下走。
        layout();

        // 半透明底板：主菜单的全景背景很花，正文直接压在上面读不清。
        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, 0xC0101010);
        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + 1, 0x40FFFFFF);
        drawRect(this.panelLeft, this.panelBottom - 1, this.panelRight, this.panelBottom, 0x40FFFFFF);

        GlStateManager.pushMatrix();
        GlStateManager.translate(this.width / 2.0F, this.headingY, 0.0F);
        GlStateManager.scale(this.headingScale, this.headingScale, 1.0F);
        drawCenteredString(this.fontRendererObj, HEADING, 0, 0, 0xFFFFFF);
        GlStateManager.popMatrix();

        int lineHeight = this.fontRendererObj.FONT_HEIGHT + LINE_GAP;
        int y = this.bodyTop;
        for (int index = 0; index < this.bodyLines.size(); index++) {
            drawCenteredString(this.fontRendererObj, this.bodyLines.get(index), this.width / 2, y, 0xC8C8C8);
            y += lineHeight;
        }

        if (this.status != null) {
            drawCenteredString(this.fontRendererObj, this.status, this.width / 2, this.statusTop + 2, 0xFFB74D);
        }

        boolean hovering = isOverNeverShow(mouseX, mouseY);
        drawCenteredString(this.fontRendererObj, NEVER_SHOW, this.width / 2,
                this.linkTop + 2, hovering ? 0xFFFFA0 : 0x909090);
        if (hovering) {
            // 原版没有「文字链接」控件，用一条下划线表示这一行可以点。
            int underline = this.fontRendererObj.getStringWidth(NEVER_SHOW);
            int left = this.width / 2 - underline / 2;
            drawRect(left, this.linkTop + 2 + this.fontRendererObj.FONT_HEIGHT,
                    left + underline, this.linkTop + 3 + this.fontRendererObj.FONT_HEIGHT, 0xFFFFFFA0);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private boolean isOverNeverShow(int mouseX, int mouseY) {
        int linkHeight = this.fontRendererObj.FONT_HEIGHT + 4;
        return mouseX >= this.neverShowLeft && mouseX <= this.neverShowRight
                && mouseY >= this.linkTop && mouseY <= this.linkTop + linkHeight;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && isOverNeverShow(mouseX, mouseY)) {
            // 原版按钮自己会发这声，手写的文字链接得补一次，否则点上去毫无反馈。
            this.mc.getSoundHandler().playSound(
                    PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
            FfmpegNotice.setNeverShow();
            dismiss();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BUTTON_DOWNLOAD) {
            if (openDownloadPage()) {
                dismiss();
            } else {
                // 打不开浏览器（无桌面环境 / 被沙箱拦住）时把地址塞进剪贴板，别让用户照着抄。
                setClipboardString(FfmpegNotice.DOWNLOAD_URL);
                this.status = "打不开浏览器喵…地址已复制到剪贴板：" + FfmpegNotice.DOWNLOAD_URL;
            }
            return;
        }
        if (button.id == BUTTON_IGNORE) {
            dismiss();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 必须覆盖：原版的 ESC 是 displayGuiScreen(null)，在主菜单上那会把主菜单一起关掉、剩一个黑屏。
        // 这里 ESC 等价于「我才不管呢喵」，回到原来的界面。
        if (keyCode == Keyboard.KEY_ESCAPE) {
            dismiss();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void dismiss() {
        this.mc.displayGuiScreen(this.parent);
    }

    /**
     * 打开 ffmpeg 下载页。
     *
     * <p>先用反射走 {@code java.awt.Desktop}（和原版 {@code GuiScreen.openWebLink} 同一条路：反射是
     * 为了不在错误的时机把 AWT 类加载进来），失败再退到 LWJGL 的 {@code Sys.openURL}。</p>
     */
    private boolean openDownloadPage() {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            desktopClass.getMethod("browse", URI.class).invoke(desktop, new URI(FfmpegNotice.DOWNLOAD_URL));
            return true;
        } catch (Throwable ignored) {
        }
        try {
            return org.lwjgl.Sys.openURL(FfmpegNotice.DOWNLOAD_URL);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
