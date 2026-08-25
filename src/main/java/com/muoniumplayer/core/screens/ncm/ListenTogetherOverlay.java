package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseListenTogetherService;
import com.muoniumplayer.core.ncm.music.QRCodeGenerator;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.screens.ncm.panels.PanelLoadingWidget;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * 网易云「一起听」二级弹窗：建房 / 加入 / 房间态三合一。
 *
 * <p>房间状态由 {@link NeteaseListenTogetherService} 的后台线程推进，随时可能变化，因此这里
 * <b>不按状态重建控件树</b>：四段内容一次性建好，每帧只切 {@code setHidden}。重建控件只发生在
 * 「总览 ↔ 加入房间」这一次用户主动换页时（在点击回调里，不在遍历子组件的过程中）。</p>
 *
 * <p>可见性由 {@code createDialog} 的每帧回调统一驱动，而不是各段自己判断 —— 被隐藏的组件不会
 * 再执行自己的回调，也就再没有机会把自己显示回来。</p>
 */
public final class ListenTogetherOverlay extends NCMPanel {

    /** 成员行数上限。房间人数超过这个数时，标题里的总人数仍然是真实值。 */
    private static final int MEMBER_ROWS = 4;

    private static final Location QR_TEXTURE = Location.of("muonium/textures/listen/together_qr.png");

    private static final int OK_COLOR = 0x53C68C;
    private static final int WARN_COLOR = 0xD5A44A;
    private static final int ERROR_COLOR = 0xF1767D;

    /** 「已复制」提示的存在时长。 */
    private static final long COPIED_HINT_MILLIS = 1400L;

    private boolean closing;
    private boolean joinPage;

    /** 弹窗入场进度；同时驱动上浮位移与整体淡入。 */
    private double presentation;

    /** 弹窗高度的当帧值。四段内容高矮不同，直接跳变会很生硬，所以朝目标高度插值。 */
    private double dialogHeight;

    private String bakedQrContent = "";
    private volatile boolean bakingQr;
    private double qrReveal;

    private long copiedAtMillis;
    private String copiedLabel = "";

    /**
     * 每行成员的淡入进度。放在数组里而不是控件上，是因为控件在隐藏帧不跑回调，
     * 而进度需要在「显示的第一帧」就从 0 开始重新累积。
     */
    private final double[][] memberReveal = new double[MEMBER_ROWS][1];

    private TextFieldWidget inviteField;
    private String joinHint = "支持整条分享链接、房间号#邀请人ID，或只有房间号";
    private int joinHintColor = 0xAEB5C4;

    public void onInit() {
        showOverview();
    }

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
    }

    public void handleEscape() {
        if (joinPage) {
            showOverview();
        } else {
            dispose();
        }
    }

    @Override
    public boolean onKeyTypedReceived(char typedChar, int keyCode) {
        // ESC 必须留给 NCMScreen 的模态链路（回总览 / 关弹窗），不能被输入框吃掉。
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) return false;
        if (joinPage && inviteField != null && inviteField.isFocused()) {
            inviteField.onKeyTyped(typedChar, keyCode);
            return true;
        }
        return false;
    }
    // ──────────────────────────── 总览页 ────────────────────────────

    private void showOverview() {
        joinPage = false;
        inviteField = null;
        getChildren().clear();
        for (double[] slot : memberReveal) slot[0] = 0.0;
        qrReveal = 0.0;

        final Panel[] sections = new Panel[4];
        Panel dialog = createDialog(474, this::targetOverviewHeight, () -> {
            boolean available = NeteaseListenTogetherService.isAvailable();
            NeteaseListenTogetherService.Phase phase = NeteaseListenTogetherService.getPhase();
            boolean busy = phase == NeteaseListenTogetherService.Phase.CONNECTING;
            boolean active = phase == NeteaseListenTogetherService.Phase.ACTIVE;
            sections[0].setHidden(available);
            sections[1].setHidden(!available || !busy);
            sections[2].setHidden(!available || busy || active);
            sections[3].setHidden(!available || busy || !active);
            if (!active) {
                for (double[] slot : memberReveal) slot[0] = 0.0;
                qrReveal = 0.0;
            }
            ensureQrTexture();
        });
        addTitle(dialog, "一起听", "与好友共享同一条播放进度 · 仅网易云官方源");

        sections[0] = section(dialog);
        buildUnavailable(sections[0]);
        sections[1] = section(dialog);
        buildConnecting(sections[1]);
        sections[2] = section(dialog);
        buildIdle(sections[2]);
        sections[3] = section(dialog);
        buildActive(sections[3]);

        addStatusLine(dialog);
    }

    /** 四段内容高矮不同；高度由状态决定，由 {@code createDialog} 每帧插值过去。 */
    private double targetOverviewHeight() {
        if (!NeteaseListenTogetherService.isAvailable()) return 300.0;
        NeteaseListenTogetherService.Phase phase = NeteaseListenTogetherService.getPhase();
        if (phase == NeteaseListenTogetherService.Phase.CONNECTING) return 244.0;
        if (phase == NeteaseListenTogetherService.Phase.ACTIVE) return 440.0;
        return 344.0;
    }

    /** 一段内容的容器：本身不画任何东西，只是用来整段切显隐。 */
    private Panel section(final Panel dialog) {
        Panel panel = new Panel();
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            panel.setBounds(Math.max(1, dialog.getWidth()), Math.max(1, dialog.getHeight()));
            panel.setPosition(0, 0);
        });
        return panel;
    }

    private void addStatusLine(final Panel dialog) {
        LabelWidget status = new LabelWidget(NeteaseListenTogetherService::getStatusText, FontManager.pf12);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(NeteaseListenTogetherService.isStatusError() ? ERROR_COLOR
                    : NeteaseListenTogetherService.isActive() ? OK_COLOR
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, dialog.getHeight() - 22);
        });
    }

    // ──────────────────────────── 不可用 ────────────────────────────

    private void buildUnavailable(final Panel host) {
        RoundedRectWidget card = new RoundedRectWidget();
        host.addChild(card);
        card.setClickable(false);
        card.setRadius(10);
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, host.getWidth() - 32), 76);
            card.setPosition(16, 68);
            card.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        LabelWidget icon = new LabelWidget(FontelloIcons.WARNING, FontManager.fontello22);
        host.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(WARN_COLOR);
            icon.setPosition(32, 92);
        });

        LabelWidget title = new LabelWidget("一起听暂不可用", FontManager.pf14bold);
        host.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(68, 86);
        });

        LabelWidget detail = new LabelWidget(this::unavailableReason, FontManager.pf12);
        host.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, host.getWidth() - 100));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(68, 106);
        });

        final double[] hover = new double[1];
        RoundedButtonWidget action = new RoundedButtonWidget(this::unavailableActionLabel, FontManager.pf12bold);
        host.addChild(action);
        action.setRadius(8);
        action.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dispose();
            if (CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
                NCMScreen.getInstance().openMusicSourceManager();
            } else {
                NCMScreen.getInstance().openAccountManager();
            }
            return true;
        });
        action.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], action.isHovering() ? 1.0 : 0.0, .22f);
            action.setBounds(Math.max(1, host.getWidth() - 32), 34);
            action.setPosition(16, 158);
            action.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .16));
            action.setTextColor(0xFFFFFF);
        });
    }

    private String unavailableReason() {
        if (CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
            return "当前音乐来源不是网易云音乐，一起听只在网易云官方源下可用。";
        }
        return "需要先登录网易云音乐账号，房间与邀请都依赖登录态。";
    }

    private String unavailableActionLabel() {
        return CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE
                ? "切换到网易云音乐" : "登录网易云账号";
    }

    // ──────────────────────────── 连接中 ────────────────────────────

    private void buildConnecting(final Panel host) {
        PanelLoadingWidget loading = new PanelLoadingWidget(NeteaseListenTogetherService::getStatusText);
        host.addChild(loading);
        loading.setClickable(false);
        loading.setBeforeRenderCallback(() -> {
            loading.setBounds(Math.max(1, host.getWidth()), 120);
            loading.setPosition(0, 74);
        });
    }
    // ──────────────────────────── 空闲（建房 / 加入） ────────────────────────────

    private void buildIdle(final Panel host) {
        addActionCard(host, 68, FontelloIcons.ACCOUNT_ADD, "创建房间",
                "生成房间与分享链接，你的播放、暂停、切歌会同步给好友", true,
                NeteaseListenTogetherService::createRoom);
        addActionCard(host, 168, FontelloIcons.LINK, "加入房间",
                "粘贴好友发来的分享链接或邀请码", false, this::showJoinPage);

        addFootnote(host, "受官方接口限制：加入方无法自动跟随主机的暂停与切歌，", 274);
        addFootnote(host, "需要在房间页点「同步一次」手动对齐当前曲目与进度。", 290);
    }

    private void addFootnote(final Panel host, String text, final double y) {
        LabelWidget note = new LabelWidget(text, FontManager.pf12);
        host.addChild(note);
        note.setClickable(false);
        note.setBeforeRenderCallback(() -> {
            note.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            note.setMaxWidth(Math.max(1, host.getWidth() - 32));
            note.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            note.setPosition(16, y);
        });
    }

    private void addActionCard(final Panel host, final double y, final String glyph,
                               String titleText, String detailText,
                               final boolean primary, final Runnable action) {
        final double[] hover = new double[1];

        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        host.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], card.isHovering() ? 1.0 : 0.0, .2f);
            card.setBounds(Math.max(1, host.getWidth() - 32), 88);
            // 悬停时整块卡片轻微上抬 2px：比单纯变色更能说明"这是个可以点的东西"。
            card.setPosition(16, y - hover[0] * 2.0);
            if (primary) {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .18));
            } else {
                card.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
            }
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(glyph, FontManager.fontello22);
        card.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            icon.setPosition(20, 30);
        });

        LabelWidget title = new LabelWidget(titleText, FontManager.pf16bold);
        card.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(primary ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(60, 24);
        });

        LabelWidget detail = new LabelWidget(detailText, FontManager.pf12);
        card.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, card.getWidth() - 84));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(60, 50);
        });

        LabelWidget chevron = new LabelWidget("›", FontManager.pf20bold);
        card.addChild(chevron);
        chevron.setClickable(false);
        chevron.setBeforeRenderCallback(() -> {
            chevron.setColor(primary ? 0xE0FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 悬停时向右滑 3px，配合卡片上抬形成"被推开"的方向感。
            chevron.setPosition(card.getWidth() - 22 + hover[0] * 3.0, 34);
        });
    }
    // ──────────────────────────── 房间进行中 ────────────────────────────

    /** 右侧二维码底板的边长；左侧文本列的可用宽度按它反推。 */
    private static final double QR_PLATE = 96.0;

    private void buildActive(final Panel host) {
        RoundedRectWidget card = new RoundedRectWidget();
        host.addChild(card);
        card.setClickable(false);
        card.setRadius(10);
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, host.getWidth() - 32), 58);
            card.setPosition(16, 64);
            card.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        PulseDot dot = new PulseDot();
        host.addChild(dot);
        dot.setClickable(false);
        dot.setBeforeRenderCallback(() -> {
            dot.setBounds(16, 16);
            dot.setPosition(32, 85);
        });

        LabelWidget roomTitle = new LabelWidget(
                () -> "房间 " + shortRoom(NeteaseListenTogetherService.getRoomId()), FontManager.pf14bold);
        host.addChild(roomTitle);
        roomTitle.setClickable(false);
        roomTitle.setBeforeRenderCallback(() -> {
            roomTitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            roomTitle.setMaxWidth(Math.max(1, host.getWidth() - 72));
            roomTitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            roomTitle.setPosition(56, 74);
        });

        LabelWidget role = new LabelWidget(this::roleDescription, FontManager.pf12);
        host.addChild(role);
        role.setClickable(false);
        role.setBeforeRenderCallback(() -> {
            role.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            role.setMaxWidth(Math.max(1, host.getWidth() - 72));
            role.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            role.setPosition(56, 96);
        });

        // 二维码：底板先 add、二维码后 add，靠声明顺序保证底板画在下面。
        RoundedRectWidget plate = new RoundedRectWidget();
        host.addChild(plate);
        plate.setClickable(false);
        plate.setRadius(10);
        plate.setBeforeRenderCallback(() -> {
            plate.setBounds(QR_PLATE, QR_PLATE);
            plate.setPosition(host.getWidth() - 16 - QR_PLATE, 132);
            plate.setColor(0xFFFFFF);
            plate.setAlpha(host.getAlpha() * (float) (.10 + .90 * qrReveal));
        });

        RoundedImageWidget qr = new RoundedImageWidget(() -> QR_TEXTURE, 0, 0, QR_PLATE - 8, QR_PLATE - 8);
        host.addChild(qr);
        qr.setClickable(false);
        qr.setRadius(6);
        qr.setLinearFilter(true);
        qr.setBeforeRenderCallback(() -> {
            // 淡入时同步做 .94 → 1.0 的轻微放大，二维码是从底板里"长"出来的。
            double scale = .94 + .06 * qrReveal;
            double size = (QR_PLATE - 8) * scale;
            qr.setBounds(size, size);
            qr.setPosition(host.getWidth() - 16 - QR_PLATE + (QR_PLATE - size) * .5, 132 + (QR_PLATE - size) * .5);
            qr.setAlpha(host.getAlpha() * (float) qrReveal);
        });

        // 左侧文本列必须在二维码底板之前收住，否则长链接会压到二维码底下。
        double textRight = 16 + QR_PLATE + 12;
        addCopyRow(host, 132, textRight, "分享链接", NeteaseListenTogetherService::getShareLink, "复制链接");
        addCopyRow(host, 178, textRight, "邀请码", NeteaseListenTogetherService::getInviteCode, "复制邀请码");

        LabelWidget membersLabel = new LabelWidget(() -> {
            int count = NeteaseListenTogetherService.getMembers().size();
            return count > MEMBER_ROWS ? "房间成员 · " + count + "（仅显示前 " + MEMBER_ROWS + " 位）"
                    : "房间成员 · " + count;
        }, FontManager.pf12bold);
        host.addChild(membersLabel);
        membersLabel.setClickable(false);
        membersLabel.setBeforeRenderCallback(() -> {
            membersLabel.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            membersLabel.setPosition(16, 232);
        });

        for (int row = 0; row < MEMBER_ROWS; row++) addMemberRow(host, row);

        LabelWidget empty = new LabelWidget("还没有人加入，把链接或邀请码发给好友吧。", FontManager.pf12);
        host.addChild(empty);
        empty.setClickable(false);
        empty.setBeforeRenderCallback(() -> {
            empty.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            empty.setPosition(16, 256);
            empty.setAlpha(NeteaseListenTogetherService.getMembers().isEmpty() ? host.getAlpha() : 0f);
        });

        addFooterButtons(host);
    }

    private String shortRoom(String roomId) {
        if (roomId == null || roomId.isEmpty()) return "—";
        return roomId.length() <= 18 ? roomId : roomId.substring(0, 18) + "…";
    }

    private String roleDescription() {
        NeteaseListenTogetherService.Role role = NeteaseListenTogetherService.getRole();
        if (role == NeteaseListenTogetherService.Role.HOST) return "主机 · 你的播放进度正在同步给房间成员";
        if (role == NeteaseListenTogetherService.Role.GUEST) return "参与者 · 需要手动「同步一次」对齐主机";
        return "房间已就绪";
    }
    private void addCopyRow(final Panel host, final double y, final double reservedRight,
                            final String labelText, final Supplier<String> valueSupplier,
                            final String buttonText) {
        LabelWidget caption = new LabelWidget(labelText, FontManager.pf10bold);
        host.addChild(caption);
        caption.setClickable(false);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(16, y);
        });

        LabelWidget value = new LabelWidget(valueSupplier, FontManager.pf12);
        host.addChild(value);
        value.setClickable(false);
        value.setBeforeRenderCallback(() -> {
            value.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            value.setMaxWidth(Math.max(1, host.getWidth() - 16 - reservedRight - 92));
            value.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            value.setPosition(16, y + 14);
        });

        final double[] hover = new double[1];
        RoundedButtonWidget copy = new RoundedButtonWidget(
                () -> isCopiedHintVisible(labelText) ? "已复制" : buttonText, FontManager.pf12bold);
        host.addChild(copy);
        copy.setRadius(7);
        copy.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            String text = valueSupplier.get();
            if (text == null || text.isEmpty()) return true;
            KeyboardUtils.setClipboardString(text);
            copiedLabel = labelText;
            copiedAtMillis = System.currentTimeMillis();
            return true;
        });
        copy.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], copy.isHovering() ? 1.0 : 0.0, .22f);
            copy.setBounds(84, 26);
            copy.setPosition(host.getWidth() - reservedRight - 84, y + 4);
            boolean copied = isCopiedHintVisible(labelText);
            if (copied) {
                copy.setColor(mix(OK_COLOR, 0xFFFFFF, hover[0] * .12));
                copy.setTextColor(0xFFFFFF);
            } else {
                copy.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                        NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), hover[0]));
                copy.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            }
        });
    }

    private boolean isCopiedHintVisible(String labelText) {
        return labelText.equals(copiedLabel)
                && System.currentTimeMillis() - copiedAtMillis < COPIED_HINT_MILLIS;
    }

    private void addMemberRow(final Panel host, final int row) {
        final double y = 252 + row * 24;
        final double[] reveal = memberReveal[row];

        final Monogram avatar = new Monogram();
        host.addChild(avatar);
        avatar.setClickable(false);
        avatar.setBeforeRenderCallback(() -> {
            List<NeteaseListenTogetherService.Member> members = NeteaseListenTogetherService.getMembers();
            boolean present = row < members.size();
            // 错峰淡入：上一行露出 35% 之后本行才开始，自上而下形成瀑布感。
            boolean gate = row == 0 || memberReveal[row - 1][0] > .35;
            reveal[0] = Interpolations.interpolate(reveal[0], present && gate ? 1.0 : 0.0, .18f);
            if (!present) {
                avatar.setAlpha(0f);
                return;
            }
            NeteaseListenTogetherService.Member member = members.get(row);
            avatar.setIdentity(member.userId, member.nickname);
            avatar.setBounds(18, 18);
            avatar.setPosition(16, y + (1.0 - reveal[0]) * 6.0);
            avatar.setAlpha(host.getAlpha() * (float) reveal[0]);
        });

        final LabelWidget name = new LabelWidget("", FontManager.pf12);
        host.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            List<NeteaseListenTogetherService.Member> members = NeteaseListenTogetherService.getMembers();
            if (row >= members.size()) {
                name.setAlpha(0f);
                return;
            }
            NeteaseListenTogetherService.Member member = members.get(row);
            String nickname = member.nickname == null || member.nickname.trim().isEmpty()
                    ? "用户 " + member.userId : member.nickname;
            name.setLabel(nickname);
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setMaxWidth(Math.max(1, host.getWidth() - 60));
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setPosition(40, y + 5 + (1.0 - reveal[0]) * 6.0);
            name.setAlpha(host.getAlpha() * (float) reveal[0]);
        });
    }

    private void addFooterButtons(final Panel host) {
        final double[] syncHover = new double[1];
        RoundedButtonWidget sync = new RoundedButtonWidget("同步一次", FontManager.pf12bold);
        host.addChild(sync);
        sync.setRadius(8);
        sync.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NeteaseListenTogetherService.syncOnce();
            return true;
        });
        sync.setBeforeRenderCallback(() -> {
            boolean visible = NeteaseListenTogetherService.getRole() == NeteaseListenTogetherService.Role.GUEST;
            syncHover[0] = Interpolations.interpolate(syncHover[0], visible && sync.isHovering() ? 1.0 : 0.0, .22f);
            double total = Math.max(1, host.getWidth() - 32);
            double width = visible ? (total - 8) * .5 : total;
            sync.setBounds(width, 32);
            sync.setPosition(16, host.getHeight() - 62);
            sync.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND),
                    NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), syncHover[0]));
            sync.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            // 主机没有"同步"这个动作。用 alpha + clickable 而不是 setHidden：
            // 被隐藏的组件不再执行自己的回调，也就没机会把自己显示回来。
            sync.setAlpha(visible ? host.getAlpha() : 0f);
            sync.setClickable(visible);
        });

        final double[] leaveHover = new double[1];
        RoundedButtonWidget leave = new RoundedButtonWidget(
                () -> NeteaseListenTogetherService.getRole() == NeteaseListenTogetherService.Role.HOST
                        ? "结束房间" : "退出房间", FontManager.pf12bold);
        host.addChild(leave);
        leave.setRadius(8);
        leave.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NeteaseListenTogetherService.leave();
            return true;
        });
        leave.setBeforeRenderCallback(() -> {
            boolean guest = NeteaseListenTogetherService.getRole() == NeteaseListenTogetherService.Role.GUEST;
            leaveHover[0] = Interpolations.interpolate(leaveHover[0], leave.isHovering() ? 1.0 : 0.0, .22f);
            double total = Math.max(1, host.getWidth() - 32);
            double width = guest ? (total - 8) * .5 : total;
            leave.setBounds(width, 32);
            leave.setPosition(guest ? 16 + (total - 8) * .5 + 8 : 16, host.getHeight() - 62);
            leave.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), ERROR_COLOR,
                    .3 + leaveHover[0] * .55));
            leave.setTextColor(0xFFFFFF);
        });
    }
    // ──────────────────────────── 加入房间页 ────────────────────────────

    private void showJoinPage() {
        joinPage = true;
        joinHint = "支持整条分享链接、房间号#邀请人ID，或只有房间号";
        joinHintColor = 0xAEB5C4;
        getChildren().clear();

        final Panel dialog = createDialog(452, () -> 262.0, null);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "加入房间", "粘贴好友发来的一起听分享链接或邀请码");

        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setClickable(false);
        surface.setRadius(7);
        surface.setBeforeRenderCallback(() -> {
            surface.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            surface.setPosition(16, 86);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        final TextFieldWidget field = new TextFieldWidget(FontManager.pf12);
        dialog.addChild(field);
        field.getTextField().setMaxStringLength(512);
        field.setEnabled(true).setFocused(true);
        field.setPlaceholder("https://st.music.163.com/listen-together/share/?… 或 房间号#邀请人ID");
        field.drawUnderline(false);
        field.setBeforeRenderCallback(() -> {
            field.setBounds(Math.max(1, surface.getWidth() - 12), Math.max(1, surface.getHeight() - 8));
            field.setPosition(surface.getRelativeX() + 6, surface.getRelativeY() + 4);
            field.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            field.setDisabledTextColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        inviteField = field;

        final LabelWidget hint = new LabelWidget(() -> joinHint, FontManager.pf12);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(joinHintColor);
            hint.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(16, 132);
        });

        final double[] hover = new double[1];
        final RoundedButtonWidget confirm = new RoundedButtonWidget("加入房间", FontManager.pf12bold);
        dialog.addChild(confirm);
        confirm.setRadius(8);
        confirm.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            String invite = field.getText() == null ? "" : field.getText().trim();
            if (invite.isEmpty()) {
                joinHint = "请先粘贴分享链接或邀请码";
                joinHintColor = ERROR_COLOR;
                return true;
            }
            NeteaseListenTogetherService.joinRoom(invite, 0L);
            showOverview();
            return true;
        });
        confirm.setBeforeRenderCallback(() -> {
            hover[0] = Interpolations.interpolate(hover[0], confirm.isHovering() ? 1.0 : 0.0, .22f);
            confirm.setBounds(Math.max(1, dialog.getWidth() - 32), 34);
            confirm.setPosition(16, dialog.getHeight() - 56);
            confirm.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), 0xFFFFFF, hover[0] * .16));
            confirm.setTextColor(0xFFFFFF);
        });
    }

    // ──────────────────────────── 对话框骨架 ────────────────────────────

    private Panel createDialog(final double preferredWidth, final DoubleSupplier preferredHeight,
                               final Runnable perFrame) {
        presentation = 0.0;
        dialogHeight = 0.0;

        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0).setAlpha(.48f);
        mask.setClickable(false);
        mask.setBeforeRenderCallback(() -> mask.setMargin(0));
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        final Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            presentation = Interpolations.interpolate(presentation, 1.0, .18f);
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double target = Math.max(1, Math.min(preferredHeight.getAsDouble(), getHeight() - 24));
            // 第一帧直接落在目标高度，之后才插值；否则入场会从 1px 拉开，像卷帘门。
            dialogHeight = dialogHeight <= 0.0 ? target : Interpolations.interpolate(dialogHeight, target, .2f);
            dialog.setBounds(width, dialogHeight);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - dialogHeight * .5 + (1.0 - presentation) * 10.0);
            if (perFrame != null) perFrame.run();
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(13);
        background.setBeforeRenderCallback(() -> {
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            background.setMargin(0);
        });
        return dialog;
    }

    private void addTitle(final Panel dialog, String titleText, String subtitleText) {
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
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16, 39);
        });
    }

    private void addBackButton(final Panel dialog, final Runnable action) {
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
            back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private void addDetailTitle(final Panel dialog, String titleText, String subtitleText) {
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
    // ──────────────────────────── 二维码 ────────────────────────────

    /**
     * 分享链接变了才重新烘一张二维码。
     *
     * <p>由对话框的每帧回调调用，因此不需要单独的轮询线程；生成放后台，纹理提交回主线程。
     * 失败静默 —— 二维码只是便利入口，链接与邀请码照样能复制。</p>
     */
    private void ensureQrTexture() {
        if (!NeteaseListenTogetherService.isActive()) return;
        final String link = NeteaseListenTogetherService.getShareLink();
        if (link == null || link.isEmpty()) return;
        if (link.equals(bakedQrContent)) {
            qrReveal = Interpolations.interpolate(qrReveal, 1.0, .16f);
            return;
        }
        if (bakingQr) return;
        bakingQr = true;
        qrReveal = 0.0;
        MultiThreadingUtil.runAsync(() -> {
            try {
                final BufferedImage image = QRCodeGenerator.generateQRCode(link, 128, 128);
                if (image == null) throw new IllegalStateException("二维码图片为空");
                MultiThreadingUtil.runOnMainThread(() -> {
                    try {
                        TextureManager manager = TextureManager.getInstance();
                        if (manager.getTexture(QR_TEXTURE) != null) manager.deleteTexture(QR_TEXTURE);
                        manager.loadTexture(QR_TEXTURE, new DynamicTexture(image));
                        bakedQrContent = link;
                    } finally {
                        bakingQr = false;
                    }
                });
            } catch (Throwable throwable) {
                bakingQr = false;
            }
        });
    }

    // ──────────────────────────── 配色 ────────────────────────────

    /**
     * 在两个 {@code 0xRRGGBB} 之间线性插值。
     *
     * <p>只混 RGB：{@code AbstractWidget.setColor(int)} 走 {@code new Color(int)}，高位 alpha
     * 会被丢掉（透明度由组件自己的 alpha 决定），传半透明色没有意义。</p>
     */
    private static int mix(int from, int to, double amount) {
        double t = Math.max(0.0, Math.min(1.0, amount));
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, double t) {
        return Math.max(0, Math.min(255, (int) Math.round(from + (to - from) * t)));
    }

    // ──────────────────────────── 自绘控件 ────────────────────────────

    /** 房间在线指示：内圈常亮，外圈按正弦向外扩散并淡出。 */
    private static final class PulseDot extends AbstractWidget<PulseDot> {

        private double phase;

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            if (alpha <= .015f) return;
            // 与帧率无关：掉帧时走大步而不是变慢。
            phase = (phase + RenderSystem.getFrameDeltaTime() * .012) % 1.0;

            double centerX = this.getX() + this.getWidth() * .5;
            double centerY = this.getY() + this.getHeight() * .5;
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);

            double wave = Math.sin(phase * Math.PI * 2.0) * .5 + .5;
            double ring = 3.2 + wave * 4.6;
            this.roundedRect(centerX - ring, centerY - ring, ring * 2.0, ring * 2.0, ring,
                    reAlpha(accent, (float) ((1.0 - wave) * .42) * alpha));
            this.roundedRect(centerX - 3.0, centerY - 3.0, 6.0, 6.0, 3.0, reAlpha(accent, alpha));
        }
    }

    /**
     * 成员头像用字标圆点，<b>刻意不下载真头像</b>：每人一张贴图等于一次网络请求加一次 GL 上传，
     * 而这里只有 18px。底色按 userId 取模选，同一个人每次进房间颜色一致。
     */
    private static final class Monogram extends AbstractWidget<Monogram> {

        private static final int[] PALETTE = {0xE05B6A, 0xE0885B, 0x5BA0E0, 0x5BC9A6, 0x9A7BE0, 0xE0B25B};

        private long userId;
        private String nickname = "";

        void setIdentity(long userId, String nickname) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = this.getAlpha();
            if (alpha <= .015f) return;
            double size = Math.max(1.0, Math.min(this.getWidth(), this.getHeight()));
            double radius = size * .5;
            int color = PALETTE[(int) Math.abs(userId % PALETTE.length)];
            this.roundedRect(this.getX(), this.getY(), size, size, radius, reAlpha(color, alpha));

            String initial = nickname.isEmpty() ? "?" : nickname.substring(0, 1);
            double textWidth = FontManager.pf10bold.getStringWidthD(initial);
            FontManager.pf10bold.drawString(initial,
                    this.getX() + radius - textWidth * .5,
                    this.getY() + radius - FontManager.pf10bold.getHeight() * .5 + 1.0,
                    reAlpha(0xFFFFFF, alpha));
        }
    }
}