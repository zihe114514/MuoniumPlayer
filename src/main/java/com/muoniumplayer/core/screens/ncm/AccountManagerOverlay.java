package com.muoniumplayer.core.screens.ncm;

import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;
import com.muoniumplayer.core.management.FontManager;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseAccountProfiles;
import com.muoniumplayer.core.ncm.music.QRCodeGenerator;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网易云 / QQ 音乐账号管理模态框。一级页面展示账号，二级页面负责二维码登录和退出。
 * 网络请求与二维码解码在后台执行，纹理提交回 Minecraft 主线程。
 */
public class AccountManagerOverlay extends NCMPanel {

    private static final Location NETEASE_QR = Location.of("muonium/textures/account/netease_qr.png");
    private static final Location QQ_QR = Location.of("muonium/textures/account/qq_qr.png");
    private static final Location KUGOU_QR = Location.of("muonium/textures/account/kugou_qr.png");

    private final AtomicLong pageGeneration = new AtomicLong();
    private volatile boolean closing;
    private volatile MusicPlatform detailPlatform;
    private volatile String statusText = "";
    private volatile int statusColor = 0xAEB5C4;
    private volatile boolean requestRunning;
    /**
     * QQ 音乐有两条互不相干的扫码链路（QQ 的 ptlogin、微信开放平台的 OAuth）。这个选择必须显式保存，
     * 才能保证选了微信之后走的是微信那条会话、而不是误用 QQ 的二维码。
     */
    private volatile QQLoginChannel qqLoginChannel = QQLoginChannel.QQ;
    private double dialogPresentation;

    private enum QQLoginChannel {
        QQ("QQ 扫码"),
        WECHAT("微信登录");

        private final String displayName;

        QQLoginChannel(String displayName) {
            this.displayName = displayName;
        }
    }

    @Override
    public void onInit() {
        showOverview();
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
        pageGeneration.incrementAndGet();
    }

    public void handleEscape() {
        if (detailPlatform != null) {
            showOverview();
        } else {
            dispose();
        }
    }

    private void showOverview() {
        pageGeneration.incrementAndGet();
        detailPlatform = null;
        requestRunning = false;
        getChildren().clear();
        // 三张账号卡：卡高 74、间距 14，所以纵向节奏是 78 / 166 / 254。
        // 对话框在原来 326 的基础上 +88（一张卡 + 一个间距），底部的隐私说明按 height-58 贴底，不用另挪。
        Panel dialog = createDialog(468, 414);

        addTitle(dialog, "账号与登录", "本地保存登录凭据；可分别管理音乐来源");
        addAccountCard(dialog, MusicPlatform.NETEASE, 78);
        addAccountCard(dialog, MusicPlatform.QQ, 166);
        addAccountCard(dialog, MusicPlatform.KUGOU, 254);

        RoundedRectWidget privacySurface = new RoundedRectWidget();
        dialog.addChild(privacySurface);
        privacySurface.setClickable(false);
        privacySurface.setRadius(8);
        privacySurface.setBeforeRenderCallback(() -> {
            privacySurface.setBounds(Math.max(1, dialog.getWidth() - 32), 42);
            privacySurface.setPosition(16, dialog.getHeight() - 58);
            privacySurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        LabelWidget hint = new LabelWidget("登录信息仅保存在本地配置中，不会显示 Cookie 内容", FontManager.pf12);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            hint.setPosition(28, dialog.getHeight() - 42);
            hint.setMaxWidth(Math.max(1, dialog.getWidth() - 56));
        });
    }

    private void addAccountCard(Panel dialog, MusicPlatform platform, double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setBounds(16, y, 436, 74);
        card.setRadius(11);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showDetail(platform);
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - 32), 74);
            card.setPosition(16, y);
            card.setColor(card.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            card.setTextColor(0x00FFFFFF);
        });

        RoundedRectWidget iconPlate = new RoundedRectWidget();
        dialog.addChild(iconPlate);
        iconPlate.setClickable(false);
        iconPlate.setRadius(13);
        iconPlate.setBeforeRenderCallback(() -> {
            iconPlate.setBounds(40, 40);
            iconPlate.setPosition(30, y + 17);
            iconPlate.setColor(platform.getBrandColor());
        });

        // 酷狗两份图标字体里都没有品牌字形，用正文字体画一枚汉字字标（见 MusicBrandIcons.KUGOU_MONOGRAM）。
        LabelWidget platformIcon = new LabelWidget(
                platform == MusicPlatform.KUGOU ? MusicBrandIcons.KUGOU_MONOGRAM
                        : platform == MusicPlatform.QQ ? FontelloIcons.QQ : MusicBrandIcons.NETEASE_CLOUD_MUSIC,
                platform == MusicPlatform.KUGOU ? FontManager.pf14bold
                        : platform == MusicPlatform.QQ ? FontManager.fontello18 : FontManager.musicBrand18);
        dialog.addChild(platformIcon);
        platformIcon.setClickable(false);
        platformIcon.setBeforeRenderCallback(() -> {
            platformIcon.setColor(0xFFFFFF);
            platformIcon.setPosition(iconPlate.getRelativeX() + iconPlate.getWidth() * .5 - platformIcon.getWidth() * .5,
                    iconPlate.getRelativeY() + iconPlate.getHeight() * .5 - platformIcon.getHeight() * .5 + 1);
        });

        LabelWidget name = new LabelWidget(platform.getDisplayName(), FontManager.pf14bold);
        dialog.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setPosition(84, y + 16);
        });

        LabelWidget account = new LabelWidget(() -> CadenceMusicService.isLoggedIn(platform)
                ? CadenceMusicService.getAccountName(platform)
                : "未登录 · 点击配置登录方式", FontManager.pf12);
        dialog.addChild(account);
        account.setClickable(false);
        account.setBeforeRenderCallback(() -> {
            account.setColor(CadenceMusicService.isLoggedIn(platform)
                    ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT)
                    : 0x9DA6B7);
            account.setMaxWidth(Math.max(1, dialog.getWidth() - 196));
            account.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            account.setPosition(84, y + 39);
        });

        RoundedRectWidget state = new RoundedRectWidget();
        dialog.addChild(state);
        state.setClickable(false);
        state.setRadius(7);
        state.setBeforeRenderCallback(() -> {
            boolean loggedIn = CadenceMusicService.isLoggedIn(platform);
            state.setBounds(loggedIn ? 52 : 48, 18);
            state.setPosition(dialog.getWidth() - state.getWidth() - 32, y + 14);
            state.setColor(loggedIn ? 0x2A8F63 : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        LabelWidget stateText = new LabelWidget(() -> CadenceMusicService.isLoggedIn(platform) ? "已登录" : "未登录", FontManager.pf12bold);
        dialog.addChild(stateText);
        stateText.setClickable(false);
        stateText.setBeforeRenderCallback(() -> {
            boolean loggedIn = CadenceMusicService.isLoggedIn(platform);
            stateText.setColor(loggedIn ? 0xE9FFF4 : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            stateText.setPosition(state.getRelativeX() + state.getWidth() * .5 - stateText.getWidth() * .5,
                    state.getRelativeY() + 4);
        });

        LabelWidget chevron = new LabelWidget("›", FontManager.pf18bold);
        dialog.addChild(chevron);
        chevron.setClickable(false);
        chevron.setBeforeRenderCallback(() -> {
            chevron.setColor(card.isHovering() ? platform.getBrandColor() : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            chevron.setPosition(dialog.getWidth() - 39, y + 42);
        });
    }

    private void showDetail(MusicPlatform platform) {
        showDetail(platform, false);
    }

    /** Opens the QR flow even when another NetEase account is already active. */
    private void showDetail(MusicPlatform platform, boolean addAccountByQr) {
        long token = pageGeneration.incrementAndGet();
        detailPlatform = platform;
        requestRunning = false;
        boolean loggedIn = CadenceMusicService.isLoggedIn(platform);
        boolean qrLoginVisible = !loggedIn || addAccountByQr;
        boolean qqWechatSelected = platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT;
        statusText = addAccountByQr
                ? "正在准备扫码添加账号…"
                : loggedIn
                ? "当前账号已登录"
                : (platform == MusicPlatform.QQ
                ? (qqWechatSelected ? "已选择微信登录，点击下方按钮获取二维码" : "请选择登录方式后继续")
                : "正在获取登录二维码…");
        statusColor = addAccountByQr || !loggedIn ? 0xAEB5C4 : 0x53C68C;
        getChildren().clear();
        Panel dialog = createDialog(420, 390);

        addBackButton(dialog, addAccountByQr ? this::showSavedNeteaseAccounts : this::showOverview);

        LabelWidget title = new LabelWidget(addAccountByQr ? "扫码添加网易云账号" : platform.getDisplayName() + "账号", FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(platform.getBrandColor());
            title.setPosition(58, 15);
        });

        RoundedRectWidget qrPlate = new RoundedRectWidget();
        dialog.addChild(qrPlate);
        qrPlate.setClickable(false);
        qrPlate.setRadius(8);
        qrPlate.setColor(0xF5F7FA);
        qrPlate.setBeforeRenderCallback(() -> {
            double size = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            qrPlate.setBounds(size, size);
            qrPlate.setPosition(dialog.getWidth() * .5 - size * .5, 48);
            qrPlate.setHidden(!qrLoginVisible);
        });

        RoundedImageWidget qrImage = new RoundedImageWidget(() -> getQrLocation(platform), 0, 0, 120, 120);
        dialog.addChild(qrImage);
        qrImage.setClickable(false);
        qrImage.setRadius(4);
        qrImage.setLinearFilter(true);
        qrImage.setBeforeRenderCallback(() -> {
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double imageSize = Math.max(64, plateSize - 16);
            qrImage.setBounds(imageSize, imageSize);
            qrImage.setPosition(dialog.getWidth() * .5 - imageSize * .5, 56);
            qrImage.setHidden(!qrLoginVisible);
        });

        // 扫码提示要说清用哪个客户端扫。原来这一支写死"网易云"，QQ / 微信 / 酷狗未登录时都会看到
        // 一句指错客户端的提示（用户没有点出这个问题，是顺带修的，可单独回退这一处）。
        LabelWidget account = new LabelWidget(
                () -> qrLoginVisible
                        ? "请使用" + scanClientName(platform) + "扫码并确认"
                        : CadenceMusicService.isLoggedIn(platform)
                        ? "已登录：" + CadenceMusicService.getAccountName(platform)
                        : "请使用" + scanClientName(platform) + "扫码并确认",
                FontManager.pf14bold);
        dialog.addChild(account);
        account.setClickable(false);
        account.setBeforeRenderCallback(() -> {
            account.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            account.setMaxWidth(dialog.getWidth() - 36);
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double accountY = qrLoginVisible ? 48 + plateSize + 12 : 92;
            account.setPosition(dialog.getWidth() * .5 - account.getWidth() * .5, accountY);
        });

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12bold);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(dialog.getWidth() - 36);
            double plateSize = Math.max(80, Math.min(136, dialog.getHeight() - 150));
            double accountY = qrLoginVisible ? 48 + plateSize + 12 : 92;
            status.setPosition(dialog.getWidth() * .5 - status.getWidth() * .5 + (hasConnectionFeedback() ? 9 : 0), accountY + 25);
        });
        addConnectionFeedbackIcon(dialog, status);

        RoundedButtonWidget primary = new RoundedButtonWidget(
                () -> addAccountByQr
                        ? (requestRunning ? "等待扫码…" : "重新获取二维码")
                        : CadenceMusicService.isLoggedIn(platform)
                        ? "退出登录"
                        : (requestRunning ? "等待扫码…" : "开始扫码登录"),
                FontManager.pf12bold);
        dialog.addChild(primary);
        primary.setBounds(16, 286, 388, 30);
        primary.setRadius(7);
        primary.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (addAccountByQr) {
                if (requestRunning) {
                    statusText = "等待扫码中，正在持续检查登录状态…";
                    statusColor = 0xAEB5C4;
                } else {
                    startQrLogin(platform, pageGeneration.get(), true);
                }
            } else if (CadenceMusicService.isLoggedIn(platform)) {
                logout(platform);
            } else if (!requestRunning) {
                startQrLogin(platform, pageGeneration.get());
            }
            return true;
        });
        primary.setBeforeRenderCallback(() -> {
            primary.setBounds(Math.max(1, dialog.getWidth() - 32), 30);
            primary.setPosition(16, dialog.getHeight() - 48);
            primary.setColor(!addAccountByQr && CadenceMusicService.isLoggedIn(platform)
                    ? 0xA94A52
                    : (primary.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER) : platform.getBrandColor()));
            primary.setTextColor(0xFFFFFF);
        });

        if (!addAccountByQr && platform == MusicPlatform.NETEASE && !CadenceMusicService.isLoggedIn(platform)) {
            RoundedButtonWidget cookieLogin = new RoundedButtonWidget("Cookie 登录", FontManager.pf12bold);
            dialog.addChild(cookieLogin);
            cookieLogin.setRadius(6);
            cookieLogin.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                showCookieLogin();
                return true;
            });
            cookieLogin.setBeforeRenderCallback(() -> {
                cookieLogin.setBounds(Math.max(1, dialog.getWidth() - 32), 26);
                cookieLogin.setPosition(16, dialog.getHeight() - 80);
                cookieLogin.setColor(cookieLogin.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                cookieLogin.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
        }
        if (!addAccountByQr && platform == MusicPlatform.NETEASE && CadenceMusicService.isLoggedIn(platform)) {
            RoundedButtonWidget switchAccount = new RoundedButtonWidget(
                    () -> "切换已保存账号（" + NeteaseAccountProfiles.load().size() + "）", FontManager.pf12bold);
            dialog.addChild(switchAccount);
            switchAccount.setRadius(6);
            switchAccount.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                showSavedNeteaseAccounts();
                return true;
            });
            switchAccount.setBeforeRenderCallback(() -> {
                switchAccount.setBounds(Math.max(1, dialog.getWidth() - 32), 26);
                switchAccount.setPosition(16, dialog.getHeight() - 80);
                switchAccount.setColor(switchAccount.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                switchAccount.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
            RoundedButtonWidget listeningHistorySync = new RoundedButtonWidget(
                    () -> "听歌历史同步：" + (NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled() ? "已开启" : "已关闭"),
                    FontManager.pf12bold);
            dialog.addChild(listeningHistorySync);
            listeningHistorySync.setRadius(6);
            listeningHistorySync.setOnClickCallback((x, y, button) -> {
                if (button != 0) return false;
                if (NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()) {
                    // 关闭是收回授权，立即生效，不需要再确认一次。
                    applyListeningHistorySync(false);
                    return true;
                }
                // 开启等于一次隐私授权：压暗页面、居中弹出模态确认，用户明确同意之前不写配置、
                // 也不允许操作下层界面（确认层在所有播放器内容之上，并独占鼠标与键盘）。
                NCMScreen.getInstance().openConfirmation(
                        "开启后会上报数据到网易云",
                        "开启后会收集你的电脑唯一标识符，包括但不限于 CPU、系统 OS、网卡、硬盘序列号、"
                                + "IP 地址等信息。信息全部用于上报网易云客户端。",
                        "我已了解，开启",
                        () -> applyListeningHistorySync(true));
                return true;
            });
            listeningHistorySync.setBeforeRenderCallback(() -> {
                listeningHistorySync.setBounds(Math.max(1, dialog.getWidth() - 32), 26);
                listeningHistorySync.setPosition(16, dialog.getHeight() - 112);
                listeningHistorySync.setColor(listeningHistorySync.isHovering()
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                listeningHistorySync.setTextColor(NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()
                        ? 0x6EDAA0 : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
        }
        if (platform == MusicPlatform.QQ && !CadenceMusicService.isLoggedIn(platform)) {
            addQQLoginChannelSelector(dialog);
        }
        // NetEase preserves the original direct QR acquisition behavior. QQ exposes an
        // explicit channel selector so WeChat can never be treated as a QQ QR session.
        // 酷狗只有一条扫码链路，没有可选项，所以和网易云一样进页面就直接取码。
        if ((platform == MusicPlatform.NETEASE || platform == MusicPlatform.KUGOU) && qrLoginVisible) {
            startQrLogin(platform, token, addAccountByQr);
        }
    }

    /**
     * 写入听歌历史同步开关并刷新状态行。开启只会从确认框的回调进来，
     * 因此"用户已明确同意"是这个方法被调用的前提。
     */
    private void applyListeningHistorySync(boolean enabled) {
        NCMPlayerConfig.setNeteaseListeningHistorySyncEnabled(enabled);
        statusText = enabled
                ? "已开启：仅同步本地实际播放达到阈值的网易云歌曲"
                : "已关闭：后续播放仅保留在本地播放器";
        statusColor = enabled ? 0x53C68C : 0xAEB5C4;
    }

    private void addQQLoginChannelSelector(Panel dialog) {
        LabelWidget label = new LabelWidget("登录方式", FontManager.pf12bold);
        dialog.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> {
            label.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            label.setPosition(16, dialog.getHeight() - 113);
        });

        RoundedButtonWidget qq = new RoundedButtonWidget("扫码", FontManager.pf12bold);
        RoundedButtonWidget wechat = new RoundedButtonWidget("登录", FontManager.pf12bold);
        dialog.addChild(qq);
        dialog.addChild(wechat);

        LabelWidget qqIcon = new LabelWidget(FontelloIcons.QQ, FontManager.fontello16);
        LabelWidget wechatIcon = new LabelWidget(FontelloIcons.WECHAT, FontManager.fontello16);
        dialog.addChild(qqIcon);
        dialog.addChild(wechatIcon);
        qqIcon.setClickable(false);
        wechatIcon.setClickable(false);

        qq.setRadius(6);
        wechat.setRadius(6);
        qq.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            qqLoginChannel = QQLoginChannel.QQ;
            statusText = "已选择 QQ 扫码，点击下方按钮获取二维码";
            statusColor = MusicPlatform.QQ.getBrandColor();
            return true;
        });
        wechat.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            qqLoginChannel = QQLoginChannel.WECHAT;
            statusText = "已选择微信登录，点击下方按钮获取二维码";
            statusColor = 0x4CAF72;
            return true;
        });

        qq.setBeforeRenderCallback(() -> configureQQLoginChannelButton(qq, dialog, 16, QQLoginChannel.QQ));
        wechat.setBeforeRenderCallback(() -> configureQQLoginChannelButton(wechat, dialog,
                dialog.getWidth() * .5 + 2, QQLoginChannel.WECHAT));
        qqIcon.setBeforeRenderCallback(() -> layoutLoginChannelIcon(qqIcon, qq, QQLoginChannel.QQ));
        wechatIcon.setBeforeRenderCallback(() -> layoutLoginChannelIcon(wechatIcon, wechat, QQLoginChannel.WECHAT));
    }

    private void configureQQLoginChannelButton(RoundedButtonWidget button, Panel dialog, double x, QQLoginChannel channel) {
        double width = Math.max(1, dialog.getWidth() * .5 - 18);
        button.setBounds(width, 24);
        button.setPosition(x, dialog.getHeight() - 91);
        boolean selected = qqLoginChannel == channel;
        button.setColor(selected
                ? MusicPlatform.QQ.getBrandColor()
                : (button.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)));
        button.setTextColor(selected ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
    }

    private void layoutLoginChannelIcon(LabelWidget icon, RoundedButtonWidget button, QQLoginChannel channel) {
        boolean selected = qqLoginChannel == channel;
        icon.setColor(selected ? 0xFFFFFF : (channel == QQLoginChannel.QQ
                ? MusicPlatform.QQ.getBrandColor() : 0x4CAF72));
        icon.setPosition(button.getRelativeX() + button.getWidth() * .5 - 30,
                button.getRelativeY() + button.getHeight() * .5 - icon.getHeight() * .5 + 1);
    }

    /** Cookie import validates against login-status before replacing the existing local session. */
    private void showCookieLogin() {
        final long token = pageGeneration.incrementAndGet();
        detailPlatform = MusicPlatform.NETEASE;
        requestRunning = false;
        statusText = "粘贴网易云 Cookie 后进行验证";
        statusColor = 0xAEB5C4;
        getChildren().clear();
        Panel dialog = createDialog(440, 260);

        addBackButton(dialog, () -> showDetail(MusicPlatform.NETEASE));
        addDetailTitle(dialog, "Cookie 登录", "仅本地保存；校验失败不会覆盖当前登录状态");

        RoundedRectWidget fieldSurface = new RoundedRectWidget();
        dialog.addChild(fieldSurface);
        fieldSurface.setClickable(false);
        fieldSurface.setRadius(6);
        fieldSurface.setBeforeRenderCallback(() -> {
            fieldSurface.setBounds(Math.max(1, dialog.getWidth() - 32), 32);
            fieldSurface.setPosition(16, 83);
            fieldSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        TextFieldWidget cookieField = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(cookieField);
        cookieField.getTextField().setMaxStringLength(4096);
        cookieField.getTextField().isPassword = true;
        cookieField.setEnabled(true).setFocused(true);
        cookieField.setPlaceholder("粘贴 MUSIC_U、MUSIC_A 或完整 Cookie 字符串");
        cookieField.drawUnderline(false);
        cookieField.setBeforeRenderCallback(() -> {
            cookieField.setBounds(Math.max(1, fieldSurface.getWidth() - 12), Math.max(1, fieldSurface.getHeight() - 8));
            cookieField.setPosition(fieldSurface.getRelativeX() + 6, fieldSurface.getRelativeY() + 4);
            cookieField.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            cookieField.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12bold);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(dialog.getWidth() - 32);
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16 + (hasConnectionFeedback() ? 17 : 0), 128);
        });
        addConnectionFeedbackIcon(dialog, status);

        RoundedButtonWidget confirm = new RoundedButtonWidget(() -> requestRunning ? "正在校验…" : "验证并登录", FontManager.pf12bold);
        dialog.addChild(confirm);
        confirm.setRadius(7);
        confirm.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            final String cookie = cookieField.getText() == null ? "" : cookieField.getText().trim();
            if (cookie.isEmpty()) {
                statusText = "请先粘贴 Cookie";
                statusColor = 0xF1767D;
                return true;
            }
            requestRunning = true;
            statusText = "正在验证 Cookie…";
            statusColor = 0xAEB5C4;
            MultiThreadingUtil.runAsync(() -> {
                try {
                    JsonObject result = CloudMusicApi.loginStatusWithCookie(cookie).toJsonObject();
                    if (!hasProfile(result)) {
                        throw new IllegalStateException("Cookie 无效、已过期或缺少登录凭据");
                    }
                    // The validation request above uses a per-request Cookie and therefore has not
                    // touched OptionsUtil.  Commit to the active session only after it succeeds.
                    CloudMusic.loadNCM(cookie);
                    if (CloudMusic.profile == null) {
                        throw new IllegalStateException("登录资料加载失败");
                    }
                    NeteaseAccountProfiles.saveCurrent();
                    statusText = "Cookie 登录成功";
                    statusColor = 0x53C68C;
                    DownloadDynamicIsland.showNetworkConnectionSuccess("网易云账号");
                    MultiThreadingUtil.runOnMainThread(() -> {
                        requestRunning = false;
                        if (isCurrent(MusicPlatform.NETEASE, token)) {
                            NCMScreen.getInstance().markDirty();
                            showDetail(MusicPlatform.NETEASE);
                        }
                    });
                } catch (Throwable throwable) {
                    MultiThreadingUtil.runOnMainThread(() -> {
                        requestRunning = false;
                        if (!isCurrent(MusicPlatform.NETEASE, token)) return;
                        statusText = "Cookie 登录失败：" + safeMessage(throwable);
                        statusColor = 0xF1767D;
                        DownloadDynamicIsland.showNetworkConnectionFailure("网易云账号", safeMessage(throwable));
                    });
                }
            });
            return true;
        });
        confirm.setBeforeRenderCallback(() -> {
            confirm.setBounds(Math.max(1, dialog.getWidth() - 32), 30);
            confirm.setPosition(16, dialog.getHeight() - 48);
            confirm.setColor(confirm.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            confirm.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private static boolean hasProfile(JsonObject result) {
        if (result == null) return false;
        if (result.has("profile") && result.get("profile").isJsonObject()) return true;
        return result.has("data") && result.get("data").isJsonObject()
                && result.getAsJsonObject("data").has("profile")
                && result.getAsJsonObject("data").get("profile").isJsonObject();
    }

    private void showSavedNeteaseAccounts() {
        final long token = pageGeneration.incrementAndGet();
        detailPlatform = MusicPlatform.NETEASE;
        requestRunning = false;
        getChildren().clear();
        Panel dialog = createDialog(440, 336);
        addBackButton(dialog, () -> showDetail(MusicPlatform.NETEASE));
        addDetailTitle(dialog, "已保存账号", "可切换本地账号，或主动添加新的网易云 Cookie");

        RoundedButtonWidget scanAddAccount = new RoundedButtonWidget("扫码添加", FontManager.pf12bold);
        dialog.addChild(scanAddAccount);
        LabelWidget scanAccountIcon = new LabelWidget(FontelloIcons.ACCOUNT_ADD, FontManager.fontello16);
        scanAddAccount.addChild(scanAccountIcon);
        scanAccountIcon.setClickable(false);
        scanAccountIcon.setBeforeRenderCallback(() -> {
            scanAccountIcon.setColor(0xFFFFFF);
            scanAccountIcon.setPosition(scanAddAccount.getWidth() * .5 - 42,
                    scanAddAccount.getHeight() * .5 - scanAccountIcon.getHeight() * .5 + 1);
        });
        scanAddAccount.setRadius(7);
        scanAddAccount.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            showDetail(MusicPlatform.NETEASE, true);
            return true;
        });
        scanAddAccount.setBeforeRenderCallback(() -> {
            double width = Math.max(1, (dialog.getWidth() - 36) * .58);
            scanAddAccount.setBounds(width, 28);
            scanAddAccount.setPosition(16, dialog.getHeight() - 46);
            scanAddAccount.setColor(scanAddAccount.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            scanAddAccount.setTextColor(0xFFFFFF);
        });

        RoundedButtonWidget cookieAddAccount = new RoundedButtonWidget("Cookie 添加", FontManager.pf12bold);
        dialog.addChild(cookieAddAccount);
        cookieAddAccount.setRadius(7);
        cookieAddAccount.setOnClickCallback((x, y, button) -> {
            if (button != 0 || requestRunning) return false;
            showCookieLogin();
            return true;
        });
        cookieAddAccount.setBeforeRenderCallback(() -> {
            double scanWidth = Math.max(1, (dialog.getWidth() - 36) * .58);
            cookieAddAccount.setBounds(Math.max(1, dialog.getWidth() - 20 - scanWidth), 28);
            cookieAddAccount.setPosition(20 + scanWidth, dialog.getHeight() - 46);
            cookieAddAccount.setColor(cookieAddAccount.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            cookieAddAccount.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        List<NeteaseAccountProfiles.Account> accounts = NeteaseAccountProfiles.load();
        if (accounts.isEmpty()) {
            LabelWidget empty = new LabelWidget("暂无已保存账号，可通过下方扫码或 Cookie 添加", FontManager.pf12);
            dialog.addChild(empty); empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> {
                empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                empty.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
                empty.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
                empty.setPosition(16, 96);
            });
            return;
        }
        int limit = Math.min(6, accounts.size());
        for (int i = 0; i < limit; i++) {
            final NeteaseAccountProfiles.Account account = accounts.get(i);
            final double y = 74 + i * 31;
            RoundedButtonWidget item = new RoundedButtonWidget(account.getDisplayName() + (account.id.isEmpty() ? "" : "  ·  " + account.id), FontManager.pf12bold);
            dialog.addChild(item); item.setRadius(6);
            item.setBeforeRenderCallback(() -> { item.setBounds(Math.max(1, dialog.getWidth() - 32), 26); item.setPosition(16, y); item.setColor(item.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER) : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)); item.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)); });
            item.setOnClickCallback((x, yy, button) -> {
                if (button != 0 || requestRunning) return false;
                requestRunning = true; statusText = "正在切换账号…"; statusColor = 0xAEB5C4;
                MultiThreadingUtil.runAsync(() -> {
                    boolean success = NeteaseAccountProfiles.switchTo(account);
                    MultiThreadingUtil.runOnMainThread(() -> {
                        requestRunning = false;
                        if (!isCurrent(MusicPlatform.NETEASE, token)) return;
                        statusText = success ? "账号已切换" : "账号切换失败，Cookie 可能已过期";
                        statusColor = success ? 0x53C68C : 0xF1767D;
                        NCMScreen.getInstance().markDirty();
                        showDetail(MusicPlatform.NETEASE);
                    });
                });
                return true;
            });
        }
    }
    private Panel createDialog(double preferredWidth, double preferredHeight) {
        dialogPresentation = 0.0;
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0x000000).setAlpha(.48f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));

        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            dialogPresentation = Interpolations.interpolate(dialogPresentation, 1.0, .18f);
            double w = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double h = Math.max(1, Math.min(preferredHeight, getHeight() - 24));
            dialog.setBounds(w, h);
            dialog.setAlpha((float) dialogPresentation);
            dialog.setPosition(getWidth() * .5 - w * .5,
                    getHeight() * .5 - h * .5 + (1.0 - dialogPresentation) * 10.0);
        });

        RoundedRectWidget bg = new RoundedRectWidget();
        dialog.addChild(bg);
        bg.setClickable(false);
        bg.setRadius(13);
        bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        bg.setBeforeRenderCallback(() -> bg.setMargin(0));
        return dialog;
    }

    private void addTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(16, 14);
        });

        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setPosition(16, 39);
        });
    }

    /** Shared detail-page header: icon back control never overlaps the title. */
    private void addBackButton(Panel dialog, final Runnable action) {
        RoundedButtonWidget back = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        dialog.addChild(back);
        back.setRadius(7);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            back.setBounds(28, 24);
            back.setPosition(16, 13);
            back.setColor(back.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void addDetailTitle(Panel dialog, String titleText, String subtitleText) {
        LabelWidget title = new LabelWidget(titleText, FontManager.pf18bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(56, 14);
        });

        LabelWidget subtitle = new LabelWidget(subtitleText, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 72));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(56, 39);
        });
    }

    private boolean hasConnectionFeedback() {
        return statusColor == 0x53C68C || statusColor == 0xF1767D;
    }

    private void addConnectionFeedbackIcon(Panel dialog, LabelWidget status) {
        LabelWidget feedback = new LabelWidget(() -> statusColor == 0x53C68C ? FontelloIcons.LINK : FontelloIcons.UNLINK,
                FontManager.fontello14);
        dialog.addChild(feedback);
        feedback.setClickable(false);
        feedback.setBeforeRenderCallback(() -> {
            boolean visible = hasConnectionFeedback();
            feedback.setHidden(!visible);
            feedback.setColor(statusColor == 0x53C68C ? 0x53C68C : 0xF1767D);
            feedback.setPosition(status.getRelativeX() - 15, status.getRelativeY() + 1);
        });
    }

    private void startQrLogin(MusicPlatform platform, long token) {
        startQrLogin(platform, token, false);
    }

    /**
     * Keeps a QR session alive until it reaches a terminal provider state.  UI
     * status changes are posted onto the Minecraft thread, so "waiting" and
     * "scanned" never disappear while the polling worker is still active.
     */
    private void startQrLogin(MusicPlatform platform, long token, boolean addAccountByQr) {
        if (requestRunning || closing || detailPlatform != platform || token != pageGeneration.get()) return;
        requestRunning = true;
        publishQrStatus(platform, token, "正在获取登录二维码…", 0xAEB5C4);

        // 微信是一条独立的会话：取码与轮询都必须成对地走微信那两个方法。在这里一次性定下来，
        // 循环里就不会出现"用 QQ 的码去问微信的状态"这类跨链路误用。
        final boolean wechat = platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT;
        MultiThreadingUtil.runAsync(() -> {
            try {
                QrCode qr = wechat
                        ? CadenceMusicService.createQqWechatQrCode()
                        : CadenceMusicService.createQrCode(platform);
                if (!isCurrent(platform, token)) return;
                loadQrTexture(platform, qr.getQrContent(), token);
                publishQrStatus(platform, token, "等待扫码 · 正在持续检查登录状态…", 0xAEB5C4);
                int transientErrors = 0;

                while (isCurrent(platform, token)) {
                        // The QR code has already initialized Cadence's login session.  Do not
                        // reapply the old Netease cookie on every poll: doing so invalidates a
                        // newly scanned account before Cadence can persist it.
                        QrLoginState state = wechat
                                ? CadenceMusicService.checkQqWechatQrCode(qr)
                                : CadenceMusicService.checkQrCodeSession(platform, qr);
                    if (!isCurrent(platform, token)) return;
                    if (state == QrLoginState.WAITING) {
                        transientErrors = 0;
                        publishQrStatus(platform, token, "等待扫码 · 正在持续检查登录状态…", 0xAEB5C4);
                    } else if (state == QrLoginState.SCANNED) {
                        transientErrors = 0;
                        publishQrStatus(platform, token, "已扫码，请在手机上确认…", platform.getBrandColor());
                    } else if (state == QrLoginState.CONFIRMED) {
                        if (platform == MusicPlatform.NETEASE) {
                            String confirmedCookie = OptionsUtil.getCookie();
                            if (confirmedCookie == null || confirmedCookie.trim().isEmpty()) {
                                throw new IllegalStateException("服务端未返回有效登录凭据");
                            }
                            CloudMusic.loadNCM(confirmedCookie);
                            if (CloudMusic.profile == null) {
                                throw new IllegalStateException("登录资料加载失败");
                            }
                            NeteaseAccountProfiles.saveCurrent();
                        } else if (platform == MusicPlatform.QQ) {
                            CadenceMusicService.refreshQQAccountData();
                        }
                        // 酷狗的资料与歌单已经在 checkQrCodeSession 确认那一刻异步拉起，这里不再重复。
                        requestRunning = false;
                        publishQrStatus(platform, token, addAccountByQr ? "扫码成功，账号已添加" : "登录成功", 0x53C68C);
                        DownloadDynamicIsland.showNetworkConnectionSuccess(platform.getDisplayName() + "账号");
                        MultiThreadingUtil.runOnMainThread(() -> {
                            if (!isCurrent(platform, token)) return;
                            NCMScreen.getInstance().markDirty();
                            if (addAccountByQr && platform == MusicPlatform.NETEASE) {
                                showSavedNeteaseAccounts();
                            }
                        });
                        return;
                    } else if (state == QrLoginState.EXPIRED) {
                        publishQrStatus(platform, token, "二维码已过期，点击重新获取二维码", 0xF1767D);
                        DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", "二维码已过期");
                        requestRunning = false;
                        return;
                    } else if (state == QrLoginState.ERROR) {
                        transientErrors++;
                        if (transientErrors >= 3) {
                            publishQrStatus(platform, token, "登录状态检查失败，点击重新获取二维码", 0xF1767D);
                            DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", "状态检查失败");
                            requestRunning = false;
                            return;
                        }
                        publishQrStatus(platform, token, "检查连接波动，正在继续检查（" + transientErrors + "/3）…", 0xD5A44A);
                    }
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                if (isCurrent(platform, token)) {
                    publishQrStatus(platform, token, "二维码登录失败：" + safeMessage(throwable), 0xF1767D);
                    DownloadDynamicIsland.showNetworkConnectionFailure(platform.getDisplayName() + "登录", safeMessage(throwable));
                    requestRunning = false;
                }
            }
        });
    }

    private void publishQrStatus(MusicPlatform platform, long token, String text, int color) {
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!isCurrent(platform, token)) return;
            statusText = text;
            statusColor = color;
        });
    }

    private void logout(MusicPlatform platform) {
        long token = pageGeneration.incrementAndGet();
        requestRunning = true;
        statusText = "正在退出登录…";
        statusColor = 0xAEB5C4;
        MultiThreadingUtil.runAsync(() -> {
            try {
                CadenceMusicService.logout(platform);
                statusText = "已退出登录";
                statusColor = 0x53C68C;
            } catch (Throwable throwable) {
                statusText = "退出失败：" + safeMessage(throwable);
                statusColor = 0xF1767D;
            } finally {
                requestRunning = false;
                MultiThreadingUtil.runOnMainThread(() -> {
                    NCMScreen.getInstance().markDirty();
                    if (!closing && detailPlatform == platform && token == pageGeneration.get()) {
                        showDetail(platform);
                    }
                });
            }
        });
    }

    private void loadQrTexture(MusicPlatform platform, String content, long token) throws Exception {
        BufferedImage image;
        if (platform == MusicPlatform.QQ) {
            String encoded = content == null ? "" : content;
            int comma = encoded.indexOf(',');
            if (comma >= 0) encoded = encoded.substring(comma + 1);
            image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        } else {
            image = QRCodeGenerator.generateQRCode(content, 128, 128);
        }
        if (image == null || !isCurrent(platform, token)) throw new IllegalStateException("二维码图片为空");
        final BufferedImage finalImage = image;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!isCurrent(platform, token)) return;
            TextureManager manager = TextureManager.getInstance();
            Location location = getQrLocation(platform);
            if (manager.getTexture(location) != null) manager.deleteTexture(location);
            manager.loadTexture(location, new DynamicTexture(finalImage));
        });
    }

    private boolean isCurrent(MusicPlatform platform, long token) {
        return !closing && detailPlatform == platform && pageGeneration.get() == token;
    }

    private Location getQrLocation(MusicPlatform platform) {
        if (platform == MusicPlatform.KUGOU) return KUGOU_QR;
        return platform == MusicPlatform.QQ ? QQ_QR : NETEASE_QR;
    }

    /** 扫码提示里该出现的客户端名。微信那条链路要扫的是微信，不是 QQ 音乐。 */
    private String scanClientName(MusicPlatform platform) {
        if (platform == MusicPlatform.QQ && qqLoginChannel == QQLoginChannel.WECHAT) return "微信";
        if (platform == MusicPlatform.KUGOU) return "酷狗音乐客户端";
        if (platform == MusicPlatform.QQ) return "手机 QQ";
        return "网易云音乐客户端";
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
    }
}
