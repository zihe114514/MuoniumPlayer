package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.ncm.panels.HomePanel;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

/** Second-level music-source menu for the official providers and GD Studio API sources. */
public final class MusicSourceOverlay extends NCMPanel {

    private boolean closing;
    private boolean choosingGdPlatform;
    private String statusText = "选择一个 GD 音乐台平台后，可直接搜索与播放该平台的曲目";
    private int statusColor = 0xAEB5C4;
    private double presentation;

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
        if (choosingGdPlatform) {
            showOverview();
        } else {
            dispose();
        }
    }

    private void showOverview() {
        choosingGdPlatform = false;
        getChildren().clear();

        // 高度比原来多 34：底部多了一行解灰开关。底部说明文字按 height-26 贴底，会自动跟着下移。
        Panel dialog = createDialog(452, 452);
        addTitle(dialog, "音乐来源", "官方音乐源与 GD音乐台 在线聚合源可独立切换");

        // 分组标题 / 卡片的纵向节奏：标题下 20 起第一张卡，两张卡之间留 8，换分组前留 14。
        // 原来两张官方卡之间只有 4，挨在一起像一整块；分组之间反而比卡片间距还紧。
        // 加入酷狗之后官方分组从两张变三张，下面的分组标题与 GD 卡片整体下移 66（= 卡高 58 + 间距 8），
        // 对话框高度同步 +66，底部那行说明文字仍按 height-26 贴底，不需要另外挪。
        addSectionLabel(dialog, "官方音乐源", 68);
        addContentSource(dialog, MusicPlatform.NETEASE, 88);
        addContentSource(dialog, MusicPlatform.QQ, 154);
        addContentSource(dialog, MusicPlatform.KUGOU, 220);

        addSectionLabel(dialog, "在线聚合源", 292);
        addGdContentSource(dialog, 312);

        addUnblockToggle(dialog, 380);

        LabelWidget detail = new LabelWidget("GD音乐台来源由 music-api.gdstudio.xyz 提供；请按上游服务的使用要求使用。", FontManager.pf12);
        dialog.addChild(detail);
        detail.setClickable(false);
        detail.setBeforeRenderCallback(() -> {
            detail.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            detail.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            detail.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            detail.setPosition(16, dialog.getHeight() - 26);
        });
    }

    private void addGdContentSource(Panel dialog, final double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - CARD_INSET * 2.0), CARD_HEIGHT);
            card.setPosition(CARD_INSET, y);
            card.setColor(gdCardColor(card.isHovering()));
            card.setTextColor(0x00FFFFFF);
        });

        LabelWidget icon = new LabelWidget(FontelloIcons.LINK, FontManager.fontello40);
        dialog.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(gdTextColor());
            centerIcon(icon, FontManager.fontello40, FontelloIcons.LINK.charAt(0), y);
        });

        LabelWidget title = new LabelWidget("GD音乐台 · 在线聚合", FontManager.pf14bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(gdTextColor());
            title.setPosition(TEXT_LEFT, titleTop(y));
        });

        LabelWidget subtitle = new LabelWidget(this::buildGdSummary, FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(isGdActive() ? 0xE5FFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            // 副标题要在右侧胶囊之前收住，否则长的额度文案会压到胶囊底下。
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - TEXT_LEFT
                    - CARD_INSET - CHIP_INSET - CHIP_WIDTH - 8.0));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(TEXT_LEFT, subtitleTop(y));
        });

        RoundedButtonWidget manage = new RoundedButtonWidget("选择平台", FontManager.pf12bold);
        dialog.addChild(manage);
        manage.setRadius(CHIP_HEIGHT * .5);
        manage.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            showGdPlatforms();
            return true;
        });
        manage.setBeforeRenderCallback(() -> {
            manage.setBounds(CHIP_WIDTH, CHIP_HEIGHT);
            // 右边缘留出 CHIP_INSET 的内边距。原来它的右边缘正好和卡片右边缘重合，胶囊贴在圆角上，
            // 看起来像是从卡片里挤出来的。
            manage.setPosition(Math.max(CARD_INSET,
                            dialog.getWidth() - CARD_INSET - CHIP_INSET - CHIP_WIDTH),
                    y + (CARD_HEIGHT - CHIP_HEIGHT) * .5);
            int text = gdTextColor();
            // 胶囊颜色 = 卡片当前颜色朝「这张卡片上的文字色」推一小步。原来它固定用输入框底色，
            // 卡片选中变成品牌色之后就成了一块突兀的深色方块。按卡片颜色派生之后，深色主题上是稍亮
            // 一点、浅色主题上是稍暗一点的一小片，两种主题、选中与未选中都自然。
            manage.setColor(mix(gdCardColor(card.isHovering()), text, manage.isHovering() ? .24 : .11));
            manage.setTextColor(text);
        });
    }

    private void showGdPlatforms() {
        choosingGdPlatform = true;
        getChildren().clear();

        Panel dialog = createDialog(510, 430);
        addBackButton(dialog, this::showOverview);
        addDetailTitle(dialog, "GD音乐台 · 在线聚合源", "选择一个 API 平台作为当前内容源；再次点击当前平台即可关闭");

        LabelWidget status = new LabelWidget(() -> statusText, FontManager.pf12);
        dialog.addChild(status);
        status.setClickable(false);
        status.setBeforeRenderCallback(() -> {
            status.setColor(statusColor);
            status.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            status.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            status.setPosition(16, 68);
        });

        ScrollPanel list = new ScrollPanel();
        dialog.addChild(list);
        list.setSpacing(5);
        list.setScrollStrength(20);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, dialog.getWidth() - 32), Math.max(42, dialog.getHeight() - 122));
            list.setPosition(16, 92);
        });

        for (GdStudioMusicService.Platform platform : GdStudioMusicService.getPlatforms()) {
            addGdPlatformRow(list, platform);
        }
    }

    private void addGdPlatformRow(ScrollPanel list, final GdStudioMusicService.Platform platform) {
        Panel row = new Panel();
        list.addChild(row);
        row.setBeforeRenderCallback(() -> row.setBounds(Math.max(1, list.getWidth()), 54));

        RoundedRectWidget background = new RoundedRectWidget();
        row.addChild(background);
        background.setClickable(false);
        background.setRadius(8);
        background.setBeforeRenderCallback(() -> {
            background.setMargin(0);
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        LabelWidget name = new LabelWidget(platform.displayName, FontManager.pf12bold);
        row.addChild(name);
        name.setClickable(false);
        name.setBeforeRenderCallback(() -> {
            name.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            name.setPosition(10, 9);
        });

        // The static status describes what upstream currently exposes; the runtime status shows a
        // circuit-breaker cooldown so a selected source that just failed explains itself in place.
        LabelWidget description = new LabelWidget(() -> {
            String runtime = GdStudioMusicService.statusLabel(platform.key);
            return platform.key + " · " + (runtime.isEmpty() ? platform.status.description : runtime);
        }, FontManager.pf12);
        row.addChild(description);
        description.setClickable(false);
        description.setBeforeRenderCallback(() -> {
            description.setColor(GdStudioMusicService.statusLabel(platform.key).isEmpty()
                    ? platform.status.color : 0xE07B6B);
            description.setMaxWidth(Math.max(1, row.getWidth() - 96));
            description.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            description.setPosition(10, 30);
        });

        RoundedButtonWidget select = new RoundedButtonWidget(
                () -> platform.key.equals(GdStudioSourceSettings.getPlatform()) ? "关闭" : "选用", FontManager.pf12bold);
        row.addChild(select);
        select.setRadius(6);
        select.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            boolean enable = !platform.key.equals(GdStudioSourceSettings.getPlatform());
            if (!GdStudioSourceSettings.setPlatform(enable ? platform.key : "")) return true;

            if (enable) {
                CadenceMusicService.setCurrentPlatform(MusicPlatform.GD);
                statusText = "已切换到「" + platform.displayName + "」内容源";
                statusColor = 0x75D8A0;
            } else {
                if (CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD) {
                    CadenceMusicService.setCurrentPlatform(MusicPlatform.NETEASE);
                }
                statusText = "已关闭 GD音乐台 内容源";
                statusColor = 0xAEB5C4;
            }
            resetToHomeForCurrentSource();
            showGdPlatforms();
            return true;
        });
        select.setBeforeRenderCallback(() -> {
            select.setBounds(62, 26);
            select.setPosition(Math.max(1, row.getWidth() - 78), 13);
            select.setColor(select.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            select.setTextColor(platform.key.equals(GdStudioSourceSettings.getPlatform())
                    ? 0x75D8A0 : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
    }

    private boolean isGdActive() {
        return CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD && GdStudioSourceSettings.isEnabled();
    }

    private String buildGdSummary() {
        String key = GdStudioSourceSettings.getPlatform();
        if (key.isEmpty()) return "未选用 · 选择后作为独立内容源使用";
        String label = GdStudioMusicService.displayName(key) + "（" + key + "）";
        // The documented budget is 50 requests / 5 minutes; showing what is left makes the
        // protective throttle visible instead of looking like a random failure.
        String budget = " · 额度剩余 " + GdStudioMusicService.remainingRequestBudget() + "/50";
        return CadenceMusicService.getCurrentPlatform() == MusicPlatform.GD
                ? "当前内容源：" + label + budget : "已选用：" + label + " · 点击切换";
    }

    private void addContentSource(Panel dialog, final MusicPlatform platform, final double y) {
        RoundedButtonWidget card = new RoundedButtonWidget("", FontManager.pf14bold);
        dialog.addChild(card);
        card.setRadius(10);
        card.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            boolean changed = CadenceMusicService.getCurrentPlatform() != platform;
            // Always call the setter: it also clears a stale persisted GD selection when the
            // user clicks the already-selected official source.
            CadenceMusicService.setCurrentPlatform(platform);
            if (changed) {
                resetToHomeForCurrentSource();
            }
            dispose();
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(Math.max(1, dialog.getWidth() - CARD_INSET * 2.0), CARD_HEIGHT);
            card.setPosition(CARD_INSET, y);
            card.setColor(officialCardColor(platform, card.isHovering()));
            card.setTextColor(0x00FFFFFF);
        });

        // 品牌字形必须配对到自己那份字体：music-brand-icons 里没有 U+E888（QQ 音乐的标记），
        // 原来两个平台都用 musicBrand18 去取，QQ 那一枚落到兜底字体上、画出来是空白。
        // 酷狗两份图标字体里都没有，所以用正文字体画一枚汉字字标（见 MusicBrandIcons.KUGOU_MONOGRAM）。
        final boolean qq = platform == MusicPlatform.QQ;
        final boolean kugou = platform == MusicPlatform.KUGOU;
        final String brandGlyph = kugou ? MusicBrandIcons.KUGOU_MONOGRAM
                : qq ? MusicBrandIcons.QQ_MUSIC : MusicBrandIcons.NETEASE_CLOUD_MUSIC;
        final CFontRenderer brandFont = kugou ? FontManager.pf34bold
                : qq ? FontManager.qqMusicIcon68 : FontManager.musicBrand52;
        LabelWidget icon = new LabelWidget(brandGlyph, brandFont);
        dialog.addChild(icon);
        icon.setClickable(false);
        icon.setBeforeRenderCallback(() -> {
            icon.setColor(officialTextColor(platform));
            centerIcon(icon, brandFont, brandGlyph.charAt(0), y);
        });

        LabelWidget title = new LabelWidget(platform.getDisplayName(), FontManager.pf14bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(officialTextColor(platform));
            title.setPosition(TEXT_LEFT, titleTop(y));
        });

        LabelWidget subtitle = new LabelWidget(kugou
                ? "完整内容来源 · 搜索、歌单与酷狗账号"
                : qq ? "完整内容来源 · 搜索、歌单与 QQ 账号"
                : "完整内容来源 · 搜索、歌单与网易云账号", FontManager.pf12);
        dialog.addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(CadenceMusicService.getCurrentPlatform() == platform ? 0xE5FFFFFF
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - TEXT_LEFT - CARD_INSET - CHIP_INSET));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(TEXT_LEFT, subtitleTop(y));
        });
    }

    // ==================== 来源卡片的共用尺寸与配色 ====================

    /** 卡片高度。左侧图标、文字块与右侧胶囊都按它做垂直居中。 */
    private static final double CARD_HEIGHT = 58.0;
    /** 卡片到对话框左右边缘的留白。 */
    private static final double CARD_INSET = 16.0;
    /** 左侧品牌图标的水平中心（卡片左边缘 + 14 的内边距 + 13 的图标半宽）。 */
    private static final double ICON_CENTER_X = CARD_INSET + 27.0;
    /** 标题与副标题的左边缘。 */
    private static final double TEXT_LEFT = 68.0;
    /** 标题与副标题之间的行距。 */
    private static final double TEXT_GAP = 7.0;
    /** 右侧「选择平台」胶囊的宽高，以及它到卡片右边缘的内边距。 */
    private static final double CHIP_WIDTH = 84.0;
    private static final double CHIP_HEIGHT = 26.0;
    private static final double CHIP_INSET = 12.0;

    private static double lineHeight(CFontRenderer font, double fallback) {
        return font == null ? fallback : font.getHeight();
    }

    /**
     * 标题的顶边。
     *
     * <p>把「标题 + 行距 + 副标题」当成一整块在卡片里垂直居中，而不是写死两个偏移量：原来标题固定
     * 在 y+13、副标题在 y+34，在 58 高的卡片里上边距 13、下边距 19，整块明显偏上。按字体自身的行高
     * 算还有个好处：以后换字号不用重新调这两个数。</p>
     */
    private static double titleTop(double cardY) {
        double block = lineHeight(FontManager.pf14bold, 6.0) + TEXT_GAP + lineHeight(FontManager.pf12, 5.0);
        return cardY + (CARD_HEIGHT - block) * .5;
    }

    private static double subtitleTop(double cardY) {
        return titleTop(cardY) + lineHeight(FontManager.pf14bold, 6.0) + TEXT_GAP;
    }

    /**
     * 把一枚单字形图标摆到图标位的视觉正中心。
     *
     * <p>不能用 {@code icon.getWidth()/getHeight()}：其一，LabelWidget 的尺寸是在 {@code onRender}
     * 的末尾按文本回填的，而这里是 {@code beforeRender}，读到的是上一帧的值（首帧还是 0），会闪一下；
     * 其二，那个尺寸是字体的行盒，而图标字形占满 em 方格的比例各不相同（实测 U+E888 只有 0.53、
     * U+E800 有 0.91），行盒高度还受中文回退字体的行高牵连，按它居中三枚图标不会对齐。这里改用
     * {@link GlyphInkMetrics} 量出的墨迹盒。</p>
     */
    private static void centerIcon(LabelWidget icon, CFontRenderer font, char glyph, double cardY) {
        double centerY = cardY + CARD_HEIGHT * .5;
        GlyphInkMetrics ink = GlyphInkMetrics.of(font, glyph);
        if (ink == null) {
            // 量不出来（字体没加载好 / 字形是空的）就退回行盒居中，至少不会画到卡片外面。
            icon.setPosition(ICON_CENTER_X - icon.getWidth() * .5, centerY - icon.getHeight() * .5);
            return;
        }
        icon.setPosition(ink.alignCenterX(ICON_CENTER_X), ink.alignCenterY(centerY));
    }

    /**
     * 在两个 {@code 0xRRGGBB} 之间线性插值。
     *
     * <p>只混 RGB：{@code AbstractWidget.setColor(int)} 走的是 {@code new Color(int)}，高位的 alpha
     * 会被丢掉（透明度由组件自己的 alpha 决定），所以传半透明色是没用的。要让一个控件"淡一点"，
     * 只能把它的颜色往背景或前景色混。</p>
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

    private int officialCardColor(MusicPlatform platform, boolean hovering) {
        if (CadenceMusicService.getCurrentPlatform() == platform) return platform.getBrandColor();
        return hovering ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
    }

    /**
     * 卡片上的前景色。
     *
     * <p>未选中时用主题的主要文字色而不是写死的白色：浅色主题下卡片底色是浅的，白色图标和标题会
     * 直接看不见。</p>
     */
    private int officialTextColor(MusicPlatform platform) {
        return CadenceMusicService.getCurrentPlatform() == platform
                ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    private int gdCardColor(boolean hovering) {
        if (isGdActive()) return MusicPlatform.GD.getBrandColor();
        return hovering ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
    }

    private int gdTextColor() {
        return isGdActive() ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);
    }

    /**
     * 「灰色歌曲解灰」开关。
     *
     * <p>放在音乐来源这一层而不是 HUD 配置里：它决定的就是「官方源没有流的时候要不要换一家」，
     * 和这个面板讨论的是同一件事。</p>
     */
    private void addUnblockToggle(Panel dialog, final double y) {
        final double rowHeight = 30.0;

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(8);
        background.setBeforeRenderCallback(() -> {
            background.setBounds(Math.max(1, dialog.getWidth() - CARD_INSET * 2.0), rowHeight);
            background.setPosition(CARD_INSET, y);
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
        });

        LabelWidget title = new LabelWidget("灰色歌曲解灰", FontManager.pf12bold);
        dialog.addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(CARD_INSET + 12.0, y + (rowHeight - title.getHeight()) * .5);
        });

        LabelWidget hint = new LabelWidget("官方源无可播放流时，再到第三方源按曲名匹配", FontManager.pf12);
        dialog.addChild(hint);
        hint.setClickable(false);
        hint.setBeforeRenderCallback(() -> {
            hint.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            hint.setMaxWidth(Math.max(1, dialog.getWidth() - CARD_INSET * 2.0 - 12.0 - 96.0
                    - CHIP_INSET - CHIP_WIDTH - 8.0));
            hint.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            hint.setPosition(CARD_INSET + 12.0 + 96.0, y + (rowHeight - hint.getHeight()) * .5);
        });

        RoundedButtonWidget toggle = new RoundedButtonWidget(
                () -> NeteaseUnblockSettings.isEnabled() ? "已开启" : "已关闭", FontManager.pf12bold);
        dialog.addChild(toggle);
        toggle.setRadius(CHIP_HEIGHT * .5);
        toggle.setOnClickCallback((x, yy, button) -> {
            if (button != 0) return false;
            NeteaseUnblockSettings.setEnabled(!NeteaseUnblockSettings.isEnabled());
            return true;
        });
        toggle.setBeforeRenderCallback(() -> {
            toggle.setBounds(CHIP_WIDTH, CHIP_HEIGHT);
            toggle.setPosition(Math.max(CARD_INSET,
                            dialog.getWidth() - CARD_INSET - CHIP_INSET - CHIP_WIDTH),
                    y + (rowHeight - CHIP_HEIGHT) * .5);
            boolean enabled = NeteaseUnblockSettings.isEnabled();
            int text = enabled ? 0xFFFFFF : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT);
            int base = enabled
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
            toggle.setColor(mix(base, text, toggle.isHovering() ? .18 : .0));
            toggle.setTextColor(text);
        });
    }

    private void addSectionLabel(Panel dialog, String text, double y) {
        LabelWidget label = new LabelWidget(text, FontManager.pf12bold);
        dialog.addChild(label);
        label.setClickable(false);
        label.setBeforeRenderCallback(() -> {
            label.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            label.setPosition(16, y);
        });
    }

    /**
     * A provider switch changes the type of content that the current panel can render.
     * Always return to Home before rebuilding the navigation rail instead of leaving a
     * stale search/discovery panel from the previous provider on screen.
     */
    private void resetToHomeForCurrentSource() {
        NCMScreen screen = NCMScreen.getInstance();
        screen.setCurrentPanel(new HomePanel());
        screen.markDirty();
    }

    private Panel createDialog(double preferredWidth, double preferredHeight) {
        presentation = 0.0;
        RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setColor(0).setAlpha(.48f);
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
            presentation = Interpolations.interpolate(presentation, 1.0, .18f);
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double height = Math.max(1, Math.min(preferredHeight, getHeight() - 24));
            dialog.setBounds(width, height);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - height * .5 + (1.0 - presentation) * 10.0);
        });

        RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false);
        background.setRadius(13);
        background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
        background.setBeforeRenderCallback(() -> background.setMargin(0));
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
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - 32));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(16, 39);
        });
    }

    private void addBackButton(Panel dialog, Runnable action) {
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
}
