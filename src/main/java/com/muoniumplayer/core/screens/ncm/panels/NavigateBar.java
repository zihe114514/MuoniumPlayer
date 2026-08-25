package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.input.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouMusicService;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseSearchSuggestService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.MusicBrandIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.AccountAvatar;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 22:00
 */
public class NavigateBar extends NCMPanel {

    TextFieldWidget searchField = new TextFieldWidget(FontManager.pf14bold);
    /**
     * NetEase cloud search supports both tracks and playlists; QQ Cadence currently exposes tracks only.
     */
    private boolean playlistSearchMode;
    ScrollPanel playlistPanel = new ScrollPanel();

    /**
     * 侧栏选中项的高亮条。它是共享的一条，而不是每个条目自己画一块：只有共享的一条才能在切换栏目时
     * 从旧位置滑到新位置。放在 {@link #playlistPanel} 之前加入，因此绘制在条目文字之下。
     */
    private final RoundedRectWidget selectionIndicator = new RoundedRectWidget();
    /** 高亮条当前的上下边沿（相对侧栏），两条边用不同速度跟随，移动时会被拉长。 */
    private double indicatorTop, indicatorBottom;
    private double indicatorLeft, indicatorWidth;
    private float indicatorAlpha;
    /** 第一帧直接落位，避免高亮条从侧栏顶部飞进来。 */
    private boolean indicatorPlaced;
    private PlaylistItem homeItem;
    private final List<PlaylistItem> playlistItems = new CopyOnWriteArrayList<>();

    // ── 搜索联想 ────────────────────────────────────────────────────────────
    /** 一条联想行的行高。 */
    private static final double SUGGEST_ROW_HEIGHT = 17.0;
    /** 面板上下的内边距。 */
    private static final double SUGGEST_PADDING = 3.0;
    /**
     * 面板左右距侧栏边缘的距离。
     *
     * <p>必须小于歌单条目的 4：条目占 x=4 到 width-4，面板窄了就盖不住它，鼠标停在面板上时
     * 下面那一行的强调色高亮会从两侧露出来。</p>
     */
    private static final double SUGGEST_INSET = 3.0;
    /**
     * 停止输入多久之后才发请求。
     *
     * <p>没有这段静默就会「打一个字发一次请求」：打完「周杰伦」等于三次请求，前两次的结果还没回来
     * 就已经过期了。180ms 是连续打字的自然间隔之上、又不至于让人觉得卡的一档。</p>
     */
    private static final long SUGGEST_DEBOUNCE_MILLIS = 180L;

    private final Panel suggestionPanel = new Panel();
    /**
     * 面板打开时铺在搜索栏下方的"点空白处收起"层。
     *
     * <p>它的点击回调<b>返回 true 把这一下吃掉</b>。原先返回 false 想做成"顺手收起、点击照常落到
     * 下面的条目上"，但那正是点击穿透：明明是想关掉联想面板，结果连带切了歌单、开了音乐来源弹窗。
     * 项目里 {@code ControlsBar#consumeQualityMenuClick} 与 {@code PlayQueuePanel#consumeClick}
     * 处理同类遮挡时也都是整块吃掉，这里保持一致。</p>
     */
    private final Panel suggestionDismissLayer = new Panel();
    private final List<SuggestionRow> suggestionRows = new ArrayList<>();
    /** 当前展示的联想结果。只在渲染线程里换引用，异步结果统一经由服务的缓存读回来。 */
    private List<NeteaseSearchSuggestService.Suggestion> suggestions = Collections.emptyList();
    private String suggestQuery = "";
    private long suggestQueryChangedAt;
    /** 用户主动收起（Esc / 已提交搜索）之后，同一个关键词不再自动弹回来。 */
    private String suggestDismissedFor;
    private int suggestHighlight = -1;
    /** 0 → 1 的展开进度，只用于淡入与轻微下滑。 */
    private float suggestReveal;
    private boolean suggestionsVisible;
    /** 搜索框底边（相对侧栏），联想面板挂在它下面。由搜索框自己的回调写入。 */
    private double searchBarBottom = 24.0;

    public NavigateBar() {
        this.layout();
    }

    private void layout() {
        RectWidget bg = new RectWidget();
        this.addChild(bg);

        this.setBeforeRenderCallback(() -> {
            double panelWidth = NCMScreen.getInstance().getPanelWidth();
            double responsiveMinimum = Math.min(92.0, panelWidth * .30);
            this.setBounds(Math.max(panelWidth * .15, responsiveMinimum), NCMScreen.getInstance().getPanelHeight());
            this.setPosition(0, 0);

            bg.setMargin(0);
            bg.setColor(this.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(0.9f - NCMTheme.getLiquidGlassAmount() * 0.22f);

            // 高亮条的布局必须由侧栏来跑，不能挂在高亮条自己的 beforeRenderCallback 上：
            // AbstractWidget#renderWidget 在调用 beforeRenderCallback 之前就会因为 isHidden()
            // 直接返回（父组件的渲染循环也会跳过隐藏的子组件），而唯一能把它重新显示出来的代码
            // 就在那个回调里。于是首帧一旦因为列表还没完成布局而被判定为不可见，就会永久卡在隐藏
            // 状态——选中项的强调色高亮框再也不会出现。
            this.layoutSelectionIndicator();
            // 联想面板同理必须由侧栏驱动：它在没有建议时要把自己隐藏起来，而隐藏之后自己的回调就不会
            // 再被调用，只有父组件的回调能把它显示回来。
            this.layoutSuggestions();
        });

        this.setOnKeyTypedCallback((character, keyCode) -> {

            if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F)) {
                this.searchField.setFocused(true);
                this.searchField.getTextField().selectAll();
                return true;
            }

            return false;
        });

        RoundedRectWidget searchBar = new RoundedRectWidget();
        RoundedRectWidget searchBarFocusAnimation = new RoundedRectWidget();

        this.addChild(searchBarFocusAnimation);
        this.addChild(searchBar);

        searchBarFocusAnimation.setBeforeRenderCallback(() -> {
            if (!searchField.isFocused()) {
                searchBarFocusAnimation.setAlpha(0);
            } else {
                searchBarFocusAnimation.setAlpha(Interpolations.interpolate(searchBarFocusAnimation.getAlpha(), 1f, .3f));
                searchBarFocusAnimation.setRadius(4);
                searchBarFocusAnimation.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                searchBarFocusAnimation.setBounds(searchBar.getRelativeX(), searchBar.getRelativeY(), searchBar.getWidth(), searchBar.getHeight());
                searchBarFocusAnimation.expand(1 + 5 * (1 - searchBarFocusAnimation.getAlpha()));
            }
        });

        searchBar
//            .setShouldSetMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    searchBar.setAlpha(1f);
                    searchBar.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
                    searchBar.setMargin(8);
                    searchBar.setHeight(16);
                    this.searchBarBottom = searchBar.getRelativeY() + searchBar.getHeight();
                    searchBar.setRadius(3.5);
                });

        RoundedRectWidget searchBarBg = new RoundedRectWidget();
        searchBar.addChild(searchBarBg);

        searchBarBg.setBeforeRenderCallback(() -> {
            searchBarBg.setMargin(.5);
            searchBarBg.setAlpha(.6f);
            searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            searchBarBg.setRadius(searchBar.getRadius() - .5);
        });

        LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music14);
        searchBar.addChild(lblSearchIcon);

        lblSearchIcon.setBeforeRenderCallback(() -> {
            lblSearchIcon.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSearchIcon.centerVertically();
            lblSearchIcon.setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY());
        });

        RoundedButtonWidget searchTypeToggle = new RoundedButtonWidget(
                () -> playlistSearchMode ? "歌单" : "歌曲", FontManager.pf12bold);
        searchBar.addChild(searchTypeToggle);
        searchTypeToggle.setShouldOverrideMouseCursor(true);
        searchTypeToggle.setBeforeRenderCallback(() -> {
            boolean netease = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
            searchTypeToggle.setHidden(!netease);
            searchTypeToggle.setClickable(netease);
            searchTypeToggle.setBounds(32, 13);
            searchTypeToggle.setPosition(Math.max(0, searchBar.getWidth() - searchTypeToggle.getWidth() - 2), 1.5);
            searchTypeToggle.setRadius(3);
            searchTypeToggle.setColor(playlistSearchMode
                    ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            searchTypeToggle.setTextColor(playlistSearchMode
                    ? NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        searchTypeToggle.setOnClickCallback((x, y, button) -> {
            if (button != 0 || CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
                return false;
            }
            playlistSearchMode = !playlistSearchMode;
            searchField.setPlaceholder(playlistSearchMode ? "搜索歌单..." : "搜索歌曲...");
            return true;
        });

        searchBar.addChild(searchField);

        this.searchField.setPlaceholder("搜索歌曲...");

        this.searchField.setOnKeyTypedCallback((character, keyCode) -> {
            if (this.searchField.isFocused()) {
                if (keyCode == Keyboard.KEY_ESCAPE) {
                    // 联想面板开着时，第一次 Esc 只收起面板，第二次才真正退出搜索框。
                    if (this.suggestionsVisible) {
                        this.dismissSuggestions();
                    } else {
                        this.searchField.setFocused(false);
                    }
                    return true;
                }

                if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_UP) {
                    if (this.moveSuggestionHighlight(keyCode == Keyboard.KEY_DOWN ? 1 : -1)) {
                        return true;
                    }
                }

                if (keyCode == Keyboard.KEY_TAB) {
                    // Tab 只把高亮的联想词填进输入框，不直接搜 —— 方便在联想词基础上继续改。
                    String completion = this.highlightedSuggestionText();
                    if (!completion.isEmpty()) {
                        this.searchField.setText(completion);
                        this.searchField.getTextField().setCursorPositionEnd();
                        this.suggestHighlight = -1;
                        return true;
                    }
                }

                if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                    // 有高亮的联想词就用它，否则用输入框里的原文。
                    String highlighted = this.highlightedSuggestionText();
                    if (!highlighted.isEmpty()) {
                        this.searchField.setText(highlighted);
                    }
                    final String keyword = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
                    this.submitSearch(keyword);
                }

                return true;
            }

            return false;
        });

        searchField.setBeforeRenderCallback(() -> {
            searchField.drawUnderline(false);
            searchField.setMargin(2);
            double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
            double typeToggleReserve = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE ? 36.0 : 0.0;
            searchField.setBounds(xSpacing, searchField.getRelativeY(),
                    Math.max(1.0, searchBar.getWidth() - xSpacing - typeToggleReserve), searchField.getHeight());
            searchField.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            searchField.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
//            Rect.draw(searchField.getX(), searchField.getY(), searchField.getWidth(), searchField.getHeight(), 0x800090ff);
        });
        // A single second-level entry replaces the old two-button NetEase/QQ switcher and also
        // exposes the GD Studio aggregated platforms without crowding this navigation rail.
        Panel sourceSwitcher = new Panel();
        this.addChild(sourceSwitcher);
        sourceSwitcher.setBeforeRenderCallback(() -> {
            sourceSwitcher.setBounds(Math.max(1, this.getWidth() - 16), 24);
            sourceSwitcher.setPosition(8, searchBar.getRelativeY() + searchBar.getHeight() + 5);
        });

        RoundedButtonWidget sourceMenu = new RoundedButtonWidget(() -> {
            String provider = CadenceMusicService.getCurrentPlatform().getDisplayName();
            return "音乐来源 · " + provider;
        }, FontManager.pf12bold);
        sourceSwitcher.addChild(sourceMenu);
        sourceMenu.setRadius(7);
        sourceMenu.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().openMusicSourceManager();
            return true;
        });
        sourceMenu.setBeforeRenderCallback(() -> {
            // The menu lives inside a plain Panel, so give it an explicit hit box.
            // Without this it can render with a zero-sized interactive area after the
            // navigation bar is rebuilt, making it impossible to switch back to an
            // official source from a GD source.
            sourceMenu.setBounds(Math.max(1, sourceSwitcher.getWidth()), sourceSwitcher.getHeight());
            sourceMenu.setPosition(0, 0);
            sourceMenu.setMargin(0);
            sourceMenu.setColor(sourceMenu.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            sourceMenu.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });

        // 先加入高亮条，再加入列表：渲染顺序即层次，高亮条要在条目文字下面。
        this.addChild(selectionIndicator);
        this.selectionIndicator.setClickable(false);

        this.addChild(playlistPanel);
        this.playlistPanel.setBeforeRenderCallback(() -> {
            this.playlistPanel.setMargin(0);
            double top = sourceSwitcher.getRelativeY() + sourceSwitcher.getHeight() + 7;
            this.playlistPanel.setPosition(this.playlistPanel.getRelativeX(), top);
            // 为紧凑快捷操作栏和账号入口预留底部空间。
            this.playlistPanel.setBounds(this.playlistPanel.getWidth(), Math.max(0, this.getHeight() - top - 70));
        });

        this.playlistPanel.setSpacing(4);


        {
            PlaylistItem item = new PlaylistItem("A", () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT), () -> "主页", () -> NCMScreen.getInstance().setCurrentPanel(new HomePanel()));
            item.setSelected(true);
            this.homeItem = item;

            item.setShouldOverrideMouseCursor(true);

            this.playlistPanel.addChild(item);
        }

        boolean qqMode = CadenceMusicService.getCurrentPlatform() == MusicPlatform.QQ;
        boolean kugouMode = CadenceMusicService.getCurrentPlatform() == MusicPlatform.KUGOU;
        // GD 聚合源沿用网易云那一套入口（原来 neteaseMode 就是这个含义），这里只把酷狗单独拆出来。
        boolean neteaseMode = !qqMode && !kugouMode;
        {
            LabelWidget lblDiscovery = new LabelWidget(
                    kugouMode ? "酷狗音乐发现" : qqMode ? "QQ 音乐发现" : "网易云发现", FontManager.pf14bold);
            lblDiscovery.setClickable(false);
            lblDiscovery.setBeforeRenderCallback(() -> {
                lblDiscovery.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                lblDiscovery.setPosition(6, lblDiscovery.getRelativeY());
            });
            this.playlistPanel.addChild(lblDiscovery);

            // 排行榜：网易云的榜单页最终展示的是 HomePanel 卡片墙，先取数据再一次切页（navigateAsync），
            // 否则会先闪一个空的中转页、历史栈里也会多出一条回不去的记录。
            // QQ 的榜单不一样 —— 它是真的留在 NeteaseDiscoveryPanel 里按歌曲行展示，所以照旧直接切页。
            this.playlistPanel.addChild(new PlaylistItem(FontelloIcons.LEADERBOARD, FontManager.fontello16,
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> "排行榜", () -> {
                if (qqMode) {
                    // QQ 的榜单目录也是卡片墙（25 个榜），同样先取数据再一次切页。
                    NCMScreen.getInstance().navigateAsync("排行榜", NeteaseDiscoveryPanel::loadQQTopListsPanel);
                } else if (kugouMode) {
                    // 酷狗榜单同样是卡片墙：先取数据再一次切页，避免闪一个空的中转页。
                    NCMScreen.getInstance().navigateAsync("排行榜", () -> {
                        List<PlayList> ranks = KuGouMusicService.rankPlaylists();
                        return new HomePanel(ranks, "酷狗榜单 · " + ranks.size());
                    });
                } else {
                    NCMScreen.getInstance().navigateAsync("排行榜", NeteaseDiscoveryPanel::loadTopListsPanel);
                }
            }));
            if (qqMode) {
                // QQ 也有自己的热搜端点，和网易云一样在发现页里展示列表，保持直接切页。
                this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH, MusicPlatform.QQ))));
            }
            if (neteaseMode) {
                // 热搜是真正在发现页里展示列表的，保持直接切页。
                this.playlistPanel.addChild(new PlaylistItem("K", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "热搜", () -> NCMScreen.getInstance().setCurrentPanel(
                        new NeteaseDiscoveryPanel(NeteaseDiscoveryPanel.Page.HOT_SEARCH))));
                this.playlistPanel.addChild(new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        () -> "最近播放", () -> NCMScreen.getInstance()
                        .navigateAsync("最近播放", NeteaseDiscoveryPanel::loadRecentSongsPanel)));
                this.playlistPanel.addChild(new PlaylistItem(MusicBrandIcons.PERSONAL_FM, FontManager.musicBrand16,
                        () -> NCMScreen.getColor(NCMScreen.ColorType.ACCENT),
                        () -> "私人 FM", () -> NCMScreen.getInstance().setCurrentPanel(new PersonalFmPanel())));
            }
        }
        LabelWidget lblPlaylists = new LabelWidget("我的歌单", FontManager.pf14bold);
        lblPlaylists.setBeforeRenderCallback(() -> {
            lblPlaylists.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblPlaylists.setPosition(6, lblPlaylists.getRelativeY());
        });

        this.playlistPanel.addChild(lblPlaylists);

        // QQ / 酷狗账号歌单都是异步加载的快照；网易云继续使用现有 CloudMusic 缓存。
        List<PlayList> pl = qqMode
                ? CadenceMusicService.getQQUserPlaylistsSnapshot()
                : kugouMode ? KuGouMusicService.getUserPlaylistsSnapshot() : CloudMusic.playLists;

        if (qqMode && CadenceMusicService.isLoggedIn(MusicPlatform.QQ)
                && !CadenceMusicService.areQQUserPlaylistsLoaded()) {
            CadenceMusicService.ensureQQUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }
        if (kugouMode && CadenceMusicService.isLoggedIn(MusicPlatform.KUGOU)
                && !KuGouMusicService.areUserPlaylistsLoaded()) {
            KuGouMusicService.ensureUserPlaylistsAsync(() ->
                    MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().markDirty()));
        }

        // QQ 的创建/收藏歌单统一显示在“我的歌单”下，不再因为平台切换而隐藏整个入口。
        lblPlaylists.setHidden(false);
        if (pl != null && !pl.isEmpty()) {
            if (qqMode || kugouMode) {
                for (PlayList playList : pl) {
                    PlaylistItem item = new PlaylistItem("D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            } else {
                List<PlayList> playLists = pl.stream()
                        .filter(playList -> !playList.isSubscribed())
                        .collect(java.util.stream.Collectors.toList());
                for (int i = 0; i < playLists.size(); i++) {
                    PlayList playList = playLists.get(i);
                    PlaylistItem item = new PlaylistItem(i == 0 ? "C" : "D",
                            () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                            playList::getName,
                            () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                    item.setPlaylist(playList);
                    this.playlistItems.add(item);
                    item.setShouldOverrideMouseCursor(true);
                    this.playlistPanel.addChild(item);
                }
            }
        } else if (qqMode || kugouMode) {
            String status = kugouMode
                    ? (!CadenceMusicService.isLoggedIn(MusicPlatform.KUGOU)
                    ? "登录酷狗音乐后加载歌单"
                    : (KuGouMusicService.areUserPlaylistsLoading()
                    ? "正在加载酷狗歌单…" : "暂无可用酷狗歌单"))
                    : (!CadenceMusicService.isLoggedIn(MusicPlatform.QQ)
                    ? "登录 QQ 音乐后加载歌单"
                    : (CadenceMusicService.areQQUserPlaylistsLoading()
                    ? "正在加载 QQ 歌单…" : "暂无可用 QQ 歌单"));
            PlaylistItem statusItem = new PlaylistItem("·",
                    () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                    () -> status,
                    () -> NCMScreen.getInstance().openAccountManager());
            statusItem.setShouldOverrideMouseCursor(true);
            this.playlistPanel.addChild(statusItem);
        }

        LabelWidget lblSubscribed = new LabelWidget("收藏歌单", FontManager.pf14bold);
        lblSubscribed.setBeforeRenderCallback(() -> {
            lblSubscribed.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblSubscribed.setPosition(6, lblSubscribed.getRelativeY());
        });

        // 收藏歌单是网易云独有的分组：QQ 与酷狗的收藏歌单都已经并入上面的"我的歌单"。
        lblSubscribed.setHidden(qqMode || kugouMode);
        this.playlistPanel.addChild(lblSubscribed);

        if (neteaseMode && pl != null) {
            List<PlayList> subscribedPlaylists = pl.stream()
                    .filter(PlayList::isSubscribed)
                    .collect(java.util.stream.Collectors.toList());
            subscribedPlaylists.forEach(playList -> {
                PlaylistItem item = new PlaylistItem("D", () -> NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                        playList::getName, () -> NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(playList)));
                item.setPlaylist(playList);
                this.playlistItems.add(item);
                item.setShouldOverrideMouseCursor(true);
                this.playlistPanel.addChild(item);
            });
        }
        RoundedRectWidget accountButton = new RoundedRectWidget();
        this.addChild(accountButton);
        accountButton.setRadius(6);
        accountButton.setShouldOverrideMouseCursor(true);
        accountButton.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            NCMScreen.getInstance().openAccountManager();
            return true;
        });
        accountButton.setBeforeRenderCallback(() -> {
            accountButton.setBounds(Math.max(1, this.getWidth() - 16), 22);
            accountButton.setPosition(8, this.getHeight() - 27);
            accountButton.setColor(accountButton.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            accountButton.setAlpha(accountButton.isHovering() ? .85f : .38f);
        });

        RoundedImageWidget creatorAvatar = new RoundedImageWidget(this::getUserAvatarLocation, 0, 0, 0, 0);
        this.addChild(creatorAvatar);
        creatorAvatar.fadeIn();
        creatorAvatar.setClickable(false);
        creatorAvatar.setLinearFilter(true);
        this.loadAvatar();

        RoundedRectWidget quickActionsSurface = new RoundedRectWidget();
        this.addChild(quickActionsSurface);
        quickActionsSurface.setClickable(false);
        quickActionsSurface.setBeforeRenderCallback(() -> {
            // Keep the original compact action rail dimensions; only the glyph rendering
            // is strengthened so the sidebar layout and button size stay unchanged.
            quickActionsSurface.setBounds(Math.max(1, this.getWidth() - 16), 32);
            quickActionsSurface.setPosition(8, this.getHeight() - 63);
            quickActionsSurface.setRadius(9);
            quickActionsSurface.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            quickActionsSurface.setAlpha(.90f);
        });

        SidebarActionButton btnTheme = new SidebarActionButton(SidebarActionIcon.THEME);
        SidebarActionButton btnPlayerSize = new SidebarActionButton(SidebarActionIcon.SCALE);
        SidebarActionButton btnRefresh = new SidebarActionButton(SidebarActionIcon.REFRESH);
        quickActionsSurface.addChild(btnTheme, btnPlayerSize, btnRefresh);

        btnTheme.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0 && mouseButton != 1) return false;
            if (mouseButton == 1) {
                // 右键进入调色板：改的是当前主题的颜色配置项，不轮换主题。
                NCMScreen.getInstance().openThemePalette();
                return true;
            }
            NCMScreen.getInstance().cycleThemeFrom(
                    btnTheme.getX() + btnTheme.getWidth() * .5,
                    btnTheme.getY() + btnTheme.getHeight() * .5);
            DownloadDynamicIsland.showTheme(NCMTheme.getCurrentName());
            return true;
        });

        btnPlayerSize.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton == 0) {
                NCMPlayerConfig.cycleScale();
            } else if (mouseButton == 1) {
                NCMPlayerConfig.resetScale();
            } else {
                return false;
            }
            DownloadDynamicIsland.showPlayerScale(NCMPlayerConfig.getPlayerScalePercent());
            return true;
        });

        btnRefresh.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return mouseButton == 1;
            if (!CloudMusic.beginNeteaseRefresh()) {
                btnRefresh.setSpinning(true);
                DownloadDynamicIsland.showPlaylistRefreshInProgress();
                return true;
            }

            btnRefresh.setSpinning(true);
            DownloadDynamicIsland.showPlaylistRefreshInProgress();
            MultiThreadingUtil.runAsync(() -> {
                CloudMusic.NeteaseRefreshResult result = CloudMusic.refreshNeteaseAccountData();
                MultiThreadingUtil.runOnMainThread(() -> {
                    try {
                        btnRefresh.setSpinning(false);
                        if (result.isSuccess()) {
                            NCMScreen.getInstance().markDirty();
                            // The cloud-song ID set is loaded asynchronously during
                            // the refresh. Rebuild the visible playlist rows so the
                            // newly available "网盘" badge is created immediately.
                            NCMScreen.getInstance().reloadCurrentPanel();
                            DownloadDynamicIsland.showPlaylistRefreshSuccess(
                                    result.getPlaylistCount(), result.getElapsedMillis());
                        } else {
                            DownloadDynamicIsland.showPlaylistRefreshFailure(result.getMessage());
                        }
                    } finally {
                        CloudMusic.endNeteaseRefresh();
                    }
                });
            });
            return true;
        });
        btnTheme.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnTheme.setBounds(actionWidth, 28);
            btnTheme.setPosition(2, 2);
            btnTheme.setRadius(7);
            btnTheme.setColor(btnTheme.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnPlayerSize.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnPlayerSize.setBounds(actionWidth, 28);
            btnPlayerSize.setPosition(4 + actionWidth, 2);
            btnPlayerSize.setRadius(7);
            btnPlayerSize.setColor(btnPlayerSize.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });

        btnRefresh.setBeforeRenderCallback(() -> {
            double actionWidth = Math.max(1, (quickActionsSurface.getWidth() - 8) / 3.0);
            btnRefresh.setBounds(actionWidth, 28);
            btnRefresh.setPosition(6 + actionWidth * 2, 2);
            btnRefresh.setRadius(7);
            // This refreshes the signed-in NetEase account and its playlists, which is
            // independent from the currently selected search/content provider.
            btnRefresh.setAlpha(1f);
            btnRefresh.setColor(btnRefresh.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
        });
        creatorAvatar.setBeforeRenderCallback(() -> {
            creatorAvatar.setBounds(16, 16);
            creatorAvatar.setPosition(12, this.getHeight() - 24);
            creatorAvatar.setRadius(7.25);
        });

        LabelWidget lblCreator = new LabelWidget(() -> CadenceMusicService.getCurrentPlatform().getDisplayName() + " · "
                + CadenceMusicService.getAccountName(CadenceMusicService.getCurrentPlatform()), FontManager.pf14bold);
        this.addChild(lblCreator);
        lblCreator.setClickable(false);
        lblCreator.setBeforeRenderCallback(() -> {
            lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                    creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
            lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            lblCreator.setMaxWidth(Math.max(1, this.getWidth() - lblCreator.getRelativeX() - 12));
        });

        // 联想面板最后加入：渲染顺序即层次，而 iterateChildrenMouseClick 是倒序遍历子组件的，
        // 所以最后加入的既画在最上面、也最先拿到点击。否则它下面的歌单条目会把点击抢走。
        this.buildSuggestionPanel();
    }

    /**
     * 搜索联想的下拉面板。
     *
     * <p>行是<b>预先建好的固定几个</b>，文案走 supplier 从 {@link #suggestions} 里按下标取 ——
     * 每帧按结果重建子组件会在渲染途中改动子列表，而且会让悬停/动画状态每帧丢一次。</p>
     */
    private void buildSuggestionPanel() {
        // 收起层先加入、面板后加入：倒序遍历时面板（连同它的行）先拿到点击，落在别处的点击才交给收起层。
        this.addChild(this.suggestionDismissLayer);
        this.suggestionDismissLayer.setHidden(true);
        this.suggestionDismissLayer.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            this.dismissSuggestions();
            // 吃掉这一下：这次点击的意图是"关掉联想面板"，不能同时触发它盖住的条目。
            return true;
        });

        this.addChild(this.suggestionPanel);
        this.suggestionPanel.setHidden(true);

        RoundedRectWidget surface = new RoundedRectWidget();
        this.suggestionPanel.addChild(surface);
        surface.setClickable(false);
        surface.setBeforeRenderCallback(() -> {
            surface.setMargin(0);
            surface.setRadius(4);
            surface.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            surface.setAlpha(this.suggestReveal);
        });

        RoundedRectWidget inner = new RoundedRectWidget();
        surface.addChild(inner);
        inner.setClickable(false);
        inner.setBeforeRenderCallback(() -> {
            inner.setMargin(.5);
            inner.setRadius(3.5);
            inner.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            // 完全不透明：面板底下就是歌单列表，留一点透明度会让下面的条目与它的悬停高亮透上来。
            inner.setAlpha(this.suggestReveal);
        });

        for (int index = 0; index < NeteaseSearchSuggestService.MAX_SUGGESTIONS; index++) {
            SuggestionRow row = new SuggestionRow(index);
            this.suggestionRows.add(row);
            this.suggestionPanel.addChild(row);
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int mouseButton) {
        return false;
    }

    /**
     * 账号头像按需加载。
     *
     * <p>地址、贴图位置、节流全部交给 {@link AccountAvatar} —— 群唱样式的右侧头像用的是同一份。
     * 两处必须请出同一个地址：贴图位置是按账号定的，若一处要 32 px、另一处要 128 px，
     * 后下完的那张会盖掉前一张。</p>
     */
    private void loadAvatar() {
        AccountAvatar.textureOrNull();
    }

    private Location getUserAvatarLocation() {
        return CadenceMusicService.getAccountAvatarLocation();
    }

    private SourceButton createSourceButton(MusicPlatform platform) {
        SourceButton button = new SourceButton(platform);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            boolean changed = CadenceMusicService.getCurrentPlatform() != platform;
            // The setter also clears any stale persisted GD selection when an official source
            // is chosen, even if the visible source already says NetEase/QQ.
            CadenceMusicService.setCurrentPlatform(platform);
            if (changed) {
                NCMScreen.getInstance().markDirty();
            }
            return true;
        });
        return button;
    }

    private void layoutSourceButton(SourceButton button, Panel parent, MusicPlatform platform, boolean right) {
        double gap = 3;
        double width = Math.max(1, (parent.getWidth() - gap) * .5);
        button.setBounds(width, parent.getHeight());
        button.setPosition(right ? width + gap : 0, 0);
    }

    private final class SourceButton extends Panel {
        private final MusicPlatform platform;
        private final RoundedRectWidget background = new RoundedRectWidget();
        private final LabelWidget platformIcon;
        private final LabelWidget label;

        private SourceButton(MusicPlatform platform) {
            this.platform = platform;
            this.setShouldOverrideMouseCursor(true);

            this.background.setClickable(false);
            this.addChild(this.background);
            this.background.setBeforeRenderCallback(() -> {
                boolean selected = CadenceMusicService.getCurrentPlatform() == this.platform;
                this.background.setBounds(1, 1, Math.max(1, this.getWidth() - 2), Math.max(1, this.getHeight() - 2));
                this.background.setRadius(4.5);
                this.background.setColor(selected
                        ? this.platform.getBrandColor()
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                this.background.setAlpha(selected ? .96f : (this.isHovering() ? .45f : 0f));
            });

            // QQ Music uses its own provider-brand glyph. Do not reuse the QQ login icon.
            this.platformIcon = new LabelWidget(this.platform == MusicPlatform.QQ ? MusicBrandIcons.QQ_MUSIC : MusicBrandIcons.NETEASE_CLOUD_MUSIC,
                    this.platform == MusicPlatform.QQ ? FontManager.qqMusicIcon20 : FontManager.musicBrand16);
            this.platformIcon.setClickable(false);
            this.addChild(this.platformIcon);
            this.label = new LabelWidget(
                    () -> NavigateBar.this.getWidth() < 112 ? ""
                            : (this.platform == MusicPlatform.QQ ? "QQ音乐" : "网易云音乐"),
                    FontManager.pf12bold);
            this.label.setClickable(false);
            this.addChild(this.label);
            this.platformIcon.setBeforeRenderCallback(() -> {
                boolean compact = NavigateBar.this.getWidth() < 112;
                double groupWidth = this.platformIcon.getWidth() + (compact ? 0 : 4 + this.label.getWidth());
                this.platformIcon.setColor(CadenceMusicService.getCurrentPlatform() == this.platform
                        ? 0xFFFFFF : this.platform.getBrandColor());
                this.platformIcon.setPosition(this.getWidth() * .5 - groupWidth * .5,
                        this.getHeight() * .5 - this.platformIcon.getHeight() * .5 + 1);
            });
            this.label.setBeforeRenderCallback(() -> {
                this.label.setColor(CadenceMusicService.getCurrentPlatform() == this.platform
                        ? 0xFFFFFF
                        : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                this.label.setPosition(this.platformIcon.getRelativeX() + this.platformIcon.getWidth() + 4,
                        this.getHeight() * .5 - this.label.getHeight() * .5 + 1);
            });
        }
    }

    private enum SidebarActionIcon {
        THEME,
        SCALE,
        REFRESH
    }

    /**
     * Compact vector icon button that stays legible at every player scale.
     */
    private static final class SidebarActionButton extends RoundedRectWidget {
        private final SidebarActionIcon icon;
        private float hoverAnimation;
        private float pressAnimation;
        private volatile boolean spinning;
        private long lastClickAt;

        private SidebarActionButton(SidebarActionIcon icon) {
            this.icon = icon;
            this.setShouldOverrideMouseCursor(true);
        }

        private void setSpinning(boolean spinning) {
            this.spinning = spinning;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            hoverAnimation = Interpolations.interpolate(hoverAnimation, isHovering() ? 1f : 0f, .22f);
            boolean recentlyPressed = System.currentTimeMillis() - lastClickAt < 170L;
            pressAnimation = Interpolations.interpolate(pressAnimation, recentlyPressed ? 1f : 0f, .30f);

            int base = NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND);
            int hover = NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER);
            setColor(mixColor(base, hover, .18f + hoverAnimation * .58f));
            super.onRender(mouseX, mouseY);

            double centerX = getX() + getWidth() * .5;
            double centerY = getY() + getHeight() * .5;
            double iconScale = 1.13 + hoverAnimation * .08 - pressAnimation * .055;
            float alpha = getAlpha() * (.92f + hoverAnimation * .08f);
            // Keep the source colors opaque here. The old implementation pre-applied alpha
            // and then applied it again inside each primitive, which made the glyphs visibly
            // fade into the dark action rail on several themes.
            int accent = getIconAccentColor();
            int foreground = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT);

            // Every action now has a persistent contrasting icon plate and a colored bottom
            // marker. This preserves the compact icon-only layout while making the three
            // controls immediately distinguishable before the pointer hovers them.
            roundedRect(centerX - 8.6, centerY - 8.6, 17.2, 17.2, 8.6,
                    RenderSystem.reAlpha(accent, alpha * (.16f + hoverAnimation * .16f)));
            roundedRect(centerX - 7.4, centerY - 7.4, 14.8, 14.8, 7.4,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                            alpha * (.38f - hoverAnimation * .12f)));
            roundedRect(getX() + 4.0, getY() + getHeight() - 2.4,
                    Math.max(1.0, getWidth() - 8.0), 1.35, .675,
                    RenderSystem.reAlpha(accent, alpha * (.58f + hoverAnimation * .38f)));

            api.getGLStateManager().pushMatrix();
            scaleAtPos(centerX, centerY, iconScale);
            if (icon == SidebarActionIcon.THEME) {
                renderThemeIcon(centerX, centerY, alpha, accent, foreground);
            } else if (icon == SidebarActionIcon.SCALE) {
                renderScaleIcon(centerX, centerY, alpha, accent, foreground);
            } else {
                renderRefreshIcon(centerX, centerY, alpha, accent, foreground);
            }
            api.getGLStateManager().popMatrix();
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            lastClickAt = System.currentTimeMillis();
            return super.onMouseClicked(relativeX, relativeY, mouseButton);
        }

        private void renderThemeIcon(double x, double y, float alpha, int accent, int foreground) {
            // A four-color palette mark: larger dots and a bright center remain readable
            // on both dark and liquid-glass themes without relying on a font glyph.
            roundedRect(x - 2.15, y - 6.25, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(accent, alpha));
            roundedRect(x - 6.25, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF73B9FF, alpha));
            roundedRect(x + 1.95, y - 2.15, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFFFF86B8, alpha));
            roundedRect(x - 2.15, y + 1.95, 4.3, 4.3, 2.15,
                    RenderSystem.reAlpha(0xFF75E0B1, alpha));
            roundedRect(x - 1.05, y - 1.05, 2.1, 2.1, 1.05,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        private void renderRefreshIcon(double x, double y, float alpha, int accent, int foreground) {
            long now = System.currentTimeMillis();
            double rotation = spinning ? (now / 6.0) % 360.0 : -28.0;
            final int segments = 8;
            for (int segment = 0; segment < segments; segment++) {
                // Leave two gaps in the ring to make the refresh silhouette readable.
                if (segment == 2 || segment == 6) continue;
                float segmentAlpha = alpha * (spinning
                        ? (.32f + .68f * (1f - segment / 8f))
                        : .86f);
                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(x, y, 0);
                api.getGLStateManager().rotate((float) (rotation + segment * 45.0), 0, 0, 1);
                roundedRect(-.85, -6.55, 1.7, 3.25, .85,
                        RenderSystem.reAlpha(spinning ? accent : foreground, segmentAlpha));
                api.getGLStateManager().popMatrix();
            }
            // Small arrow tips close the two gaps and turn the ring into a clear
            // refresh symbol rather than a generic loading spinner.
            drawRotatedPill(x - 4.25, y - 4.05, 4.45, 1.65, -42f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
            drawRotatedPill(x + 4.25, y + 4.05, 4.45, 1.65, 138f,
                    RenderSystem.reAlpha(spinning ? accent : foreground, alpha));
        }

        private void renderScaleIcon(double x, double y, float alpha, int accent, int foreground) {
            double left = x - 6.15;
            double top = y - 6.15;
            double right = x + 6.15;
            double bottom = y + 6.15;
            double thickness = 1.65;
            double arm = 4.7;
            int corner = RenderSystem.reAlpha(foreground, alpha);
            int highlight = RenderSystem.reAlpha(accent, alpha * .94f);

            roundedRect(left, top, arm, thickness, thickness * .5, highlight);
            roundedRect(left, top, thickness, arm, thickness * .5, highlight);
            roundedRect(right - arm, top, arm, thickness, thickness * .5, corner);
            roundedRect(right - thickness, top, thickness, arm, thickness * .5, corner);
            roundedRect(left, bottom - thickness, arm, thickness, thickness * .5, corner);
            roundedRect(left, bottom - arm, thickness, arm, thickness * .5, corner);
            roundedRect(right - arm, bottom - thickness, arm, thickness, thickness * .5, highlight);
            roundedRect(right - thickness, bottom - arm, thickness, arm, thickness * .5, highlight);
            roundedRect(x - 1.15, y - 1.15, 2.3, 2.3, 1.15,
                    RenderSystem.reAlpha(foreground, alpha * .92f));
        }

        private int getIconAccentColor() {
            if (icon == SidebarActionIcon.SCALE) return 0xFF67D8FF;
            if (icon == SidebarActionIcon.REFRESH) return 0xFF59E3A5;
            return NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        }

        private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                     float rotation, int color) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate(rotation, 0, 0, 1);
            roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
            api.getGLStateManager().popMatrix();
        }

        private int mixColor(int first, int second, float amount) {
            float t = Math.max(0f, Math.min(1f, amount));
            int a = (int) (((first >>> 24) & 255) * (1f - t) + ((second >>> 24) & 255) * t);
            int r = (int) (((first >>> 16) & 255) * (1f - t) + ((second >>> 16) & 255) * t);
            int g = (int) (((first >>> 8) & 255) * (1f - t) + ((second >>> 8) & 255) * t);
            int b = (int) ((first & 255) * (1f - t) + (second & 255) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * 把高亮条摆到当前选中的条目上。
     *
     * <p>上下两条边用不同的速度跟随目标：朝下切换时下边沿快、上边沿慢，朝上切换时相反，于是高亮条在
     * 移动途中被拉长、落位时收回原来的高度，而不是生硬地在两个位置之间闪现。</p>
     *
     * <p>高亮条不是列表的子组件（列表会把所有可见子组件重新排到垂直流里），所以它拿不到列表的裁剪，
     * 这里手动把它和列表视口求交：选中项滚出可见区域时高亮条跟着被裁掉，而不是画到列表外面去。</p>
     */
    private void layoutSelectionIndicator() {
        PlaylistItem selected = findSelectedItem();
        double panelTop = this.playlistPanel.getRelativeY();
        double panelHeight = this.playlistPanel.getHeight();
        double panelBottom = panelTop + panelHeight;

        // 列表与条目要等到各自的 beforeRenderCallback 跑过一次才有真实宽高。在那之前不要落位，
        // 否则高亮条会把一组全 0 的坐标当成起点，之后再从侧栏左上角滑进来。
        boolean geometryReady = panelHeight > .5 && selected != null && selected.getWidth() > .5;

        if (selected != null && geometryReady) {
            double targetTop = panelTop + selected.getRelativeY();
            double targetBottom = targetTop + selected.getHeight();
            double targetLeft = this.playlistPanel.getRelativeX() + selected.getRelativeX();
            double targetWidth = selected.getWidth();

            if (!indicatorPlaced) {
                indicatorTop = targetTop;
                indicatorBottom = targetBottom;
                indicatorLeft = targetLeft;
                indicatorWidth = targetWidth;
                indicatorPlaced = true;
            } else {
                boolean movingDown = targetTop > indicatorTop;
                double leading = .5, trailing = .26;
                indicatorTop = Interpolations.interpolate(indicatorTop, targetTop,
                        movingDown ? trailing : leading);
                indicatorBottom = Interpolations.interpolate(indicatorBottom, targetBottom,
                        movingDown ? leading : trailing);
                indicatorLeft = Interpolations.interpolate(indicatorLeft, targetLeft, .5);
                indicatorWidth = Interpolations.interpolate(indicatorWidth, targetWidth, .5);
            }
            indicatorAlpha = Interpolations.interpolate(indicatorAlpha, 1f, .4f);
        } else if (selected == null) {
            indicatorAlpha = Interpolations.interpolate(indicatorAlpha, 0f, .4f);
        }

        double top = Math.max(panelTop, indicatorTop);
        double bottom = Math.min(panelBottom, indicatorBottom);
        boolean visible = indicatorPlaced && indicatorAlpha > .01f
                && bottom - top > .5 && indicatorWidth > .5;
        // 隐藏是安全的：这个方法由侧栏的回调驱动，下一帧照样会跑到，可以再把它显示回来。
        this.selectionIndicator.setHidden(!visible);
        if (!visible) {
            return;
        }

        this.selectionIndicator
                .setBounds(indicatorWidth, bottom - top)
                .setPosition(indicatorLeft, top)
                .setRadius(4)
                // 选中项用主题强调色，而不是和悬停一样的中性灰，否则"选中"和"鼠标经过"看起来一样。
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setAlpha(this.getAlpha() * .22f * indicatorAlpha);
    }

    /**
     * 每帧驱动搜索联想：取词 → 防抖后异步请求 → 从缓存读回结果 → 摆放面板与行。
     *
     * <p>整个方法只做布局与状态推进，<b>不阻塞</b>：请求由
     * {@link NeteaseSearchSuggestService#prefetch(String)} 异步发出，读取走
     * {@link NeteaseSearchSuggestService#peek(String)} 的纯缓存查询。</p>
     *
     * <p>联想只对<b>官方网易云</b>生效：上游只有网易云有这个端点，QQ / 酷狗 / GD 聚合源都没有对应接口，
     * 所以在那些来源下这个面板永远不出现，它们的搜索行为一字未改。</p>
     */
    private void layoutSuggestions() {
        String text = this.searchField.getText() == null ? "" : this.searchField.getText().trim();
        if (!text.equals(this.suggestQuery)) {
            this.suggestQuery = text;
            this.suggestQueryChangedAt = System.currentTimeMillis();
            this.suggestHighlight = -1;
            // 换了词就不再沿用上一次的"已收起"，否则改完词面板弹不出来。
            if (this.suggestDismissedFor != null && !this.suggestDismissedFor.equals(text)) {
                this.suggestDismissedFor = null;
            }
        }

        boolean neteaseSource = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
        boolean eligible = neteaseSource && !text.isEmpty() && this.searchField.isFocused()
                && !text.equals(this.suggestDismissedFor);

        if (eligible && System.currentTimeMillis() - this.suggestQueryChangedAt >= SUGGEST_DEBOUNCE_MILLIS) {
            NeteaseSearchSuggestService.prefetch(text);
        }
        this.suggestions = eligible
                ? NeteaseSearchSuggestService.peek(text)
                : Collections.<NeteaseSearchSuggestService.Suggestion>emptyList();

        int count = Math.min(this.suggestions.size(), this.suggestionRows.size());
        if (this.suggestHighlight >= count) this.suggestHighlight = -1;

        boolean visible = eligible && count > 0;
        this.suggestionsVisible = visible;
        if (!visible) {
            // 立刻隐藏而不是淡出：淡出期间面板仍然会抢走点击，那比少一个退场动画糟糕得多。
            this.suggestReveal = 0f;
            this.suggestionPanel.setHidden(true);
            this.suggestionDismissLayer.setHidden(true);
            for (SuggestionRow row : this.suggestionRows) row.setHidden(true);
            return;
        }

        this.suggestReveal = Interpolations.interpolate(this.suggestReveal, 1f, .4f);
        // 收起层铺满搜索栏以下的整条侧栏。它没有任何绘制（Panel 是隐形容器），只负责接一次点击。
        this.suggestionDismissLayer.setHidden(false);
        this.suggestionDismissLayer.setBounds(Math.max(1.0, this.getWidth()),
                Math.max(1.0, this.getHeight() - this.searchBarBottom));
        this.suggestionDismissLayer.setPosition(0, this.searchBarBottom);

        // 面板要比它盖住的歌单条目宽：条目自己占 x=4 到 width-4（见 PlaylistItem 的布局），
        // 而 hovering 是各组件按自己的矩形独立算的、兄弟之间不互相压制。面板要是只有 x=8 到 width-8，
        // 鼠标停在面板上时下面那一行照样算自己被悬停，强调色高亮就会从面板两侧各露出 4px。
        // 取 x=3 / width-6 刚好把条目整个盖住，还多出 1px 余量。
        double width = Math.max(1.0, this.getWidth() - SUGGEST_INSET * 2.0);
        double height = count * SUGGEST_ROW_HEIGHT + SUGGEST_PADDING * 2.0;
        this.suggestionPanel.setHidden(false);
        this.suggestionPanel.setBounds(width, height);
        // 轻微下滑入场：只动位置不动高度，因为 Panel 不裁剪子组件，压高度会让行画到面板外面。
        this.suggestionPanel.setPosition(SUGGEST_INSET,
                this.searchBarBottom + 3.0 - 4.0 * (1f - this.suggestReveal));

        for (int index = 0; index < this.suggestionRows.size(); index++) {
            SuggestionRow row = this.suggestionRows.get(index);
            if (index >= count) {
                row.setHidden(true);
                continue;
            }
            row.setHidden(false);
            row.setBounds(Math.max(1.0, width - SUGGEST_PADDING * 2.0), SUGGEST_ROW_HEIGHT - 1.0);
            row.setPosition(SUGGEST_PADDING, SUGGEST_PADDING + index * SUGGEST_ROW_HEIGHT);
        }
    }

    /**
     * 联想面板范围内的点击由它自己处理，并且不再往下传。
     *
     * <p>为什么不能只靠组件树的短路（{@code iterateChildrenMouseClick} 一命中就 return）：
     * {@link NCMScreen#mouseClicked} 会把同一次点击「依次」交给 basePanel、currentPanel、
     * controlsBar，中间没有早退；而 {@code onMouseClickReceived} 返回的是 void，上层根本拿不到
     * 「这一下已经被吃掉了」这个信息。项目里音质弹窗（{@code ControlsBar#consumeQualityMenuClick}）
     * 与队列抽屉（{@code PlayQueuePanel#consumeClick}）就是为同一个问题加的前置消费口，这里照办。</p>
     *
     * <p>命中判定走「面板与行自己的矩形」，不看 {@code isHovering()}：悬停状态是渲染时算出来的，
     * 依赖它就等于依赖「这一帧确实渲染过、而且没有被别的组件抢走悬停」，而这个前置口是在渲染之外
     * 被调用的。</p>
     *
     * @return 是否吃掉了这次点击
     */
    public boolean consumeSuggestionClick(double mouseX, double mouseY, int mouseButton) {
        if (!this.suggestionsVisible || this.suggestionPanel.isHidden()) return false;

        if (this.isHovered(mouseX, mouseY, this.suggestionPanel.getX(), this.suggestionPanel.getY(),
                this.suggestionPanel.getWidth(), this.suggestionPanel.getHeight())) {
            if (mouseButton == 0) {
                int index = this.suggestionRowAt(mouseX, mouseY);
                if (index >= 0) this.selectSuggestion(index);
            }
            // 落在面板留白（上下各 3 px）上的点击也要吃掉：它盖住的仍然是歌单条目。
            return true;
        }

        // 收起层的范围是搜索栏以下的整条侧栏。这里的点击意图是「关掉面板」，
        // 不能连带触发被它盖住的歌单条目或音乐来源卡片。
        if (!this.suggestionDismissLayer.isHidden() && this.isHovered(mouseX, mouseY,
                this.suggestionDismissLayer.getX(), this.suggestionDismissLayer.getY(),
                this.suggestionDismissLayer.getWidth(), this.suggestionDismissLayer.getHeight())) {
            this.dismissSuggestions();
            return true;
        }

        // 侧栏之外（右侧列表、底部控制条）没有任何视觉遮挡，那里的点击照常生效：
        // 输入框失焦后面板下一帧自己就会收起，不在这里额外记「这个词不再提示」。
        return false;
    }

    /** 命中的联想行下标；没命中返回负一。 */
    private int suggestionRowAt(double mouseX, double mouseY) {
        for (int index = 0; index < this.suggestionRows.size(); index++) {
            SuggestionRow row = this.suggestionRows.get(index);
            if (row.isHidden()) continue;
            if (this.isHovered(mouseX, mouseY, row.getX(), row.getY(),
                    row.getWidth(), row.getHeight())) {
                return index;
            }
        }
        return -1;
    }

    /** 选中第 {@code index} 条联想词：填进搜索框，然后走与回车完全同一条搜索路径。 */
    private void selectSuggestion(int index) {
        List<NeteaseSearchSuggestService.Suggestion> current = this.suggestions;
        if (current == null || index < 0 || index >= current.size()) return;
        NeteaseSearchSuggestService.Suggestion suggestion = current.get(index);
        String keyword = suggestion == null || suggestion.text == null ? "" : suggestion.text.trim();
        if (keyword.isEmpty()) return;
        this.searchField.setText(keyword);
        this.searchField.getTextField().setCursorPositionEnd();
        this.submitSearch(keyword);
    }

    /** 上/下键移动高亮。返回是否消费了这次按键（面板没开时不消费，方向键仍归输入框）。 */
    private boolean moveSuggestionHighlight(int delta) {
        if (!this.suggestionsVisible) return false;
        int count = Math.min(this.suggestions.size(), this.suggestionRows.size());
        if (count <= 0) return false;
        if (this.suggestHighlight < 0) {
            this.suggestHighlight = delta > 0 ? 0 : count - 1;
        } else {
            // 循环而不是在两端停住：只有八条，绕一圈比试探边界快。
            this.suggestHighlight = ((this.suggestHighlight + delta) % count + count) % count;
        }
        return true;
    }

    /** 当前高亮的联想词；没有高亮或面板没开时返回空串。 */
    private String highlightedSuggestionText() {
        if (!this.suggestionsVisible || this.suggestHighlight < 0
                || this.suggestHighlight >= this.suggestions.size()) {
            return "";
        }
        String text = this.suggestions.get(this.suggestHighlight).text;
        return text == null ? "" : text.trim();
    }

    /** 收起联想面板，并记住"这个词已经不想看建议了"。 */
    private void dismissSuggestions() {
        this.suggestDismissedFor = this.suggestQuery;
        this.suggestHighlight = -1;
        this.suggestionsVisible = false;
        this.suggestReveal = 0f;
        this.suggestionPanel.setHidden(true);
        this.suggestionDismissLayer.setHidden(true);
        for (SuggestionRow row : this.suggestionRows) row.setHidden(true);
    }

    /**
     * 执行一次搜索。
     *
     * <p>从原先内联在回车处理里的那段代码原样提出来，好让联想行点击走<b>完全同一条</b>路径 ——
     * 官方源的搜索行为一字未改：网易云的歌单模式仍然走 {@code cloudSearch} 的 1000 类型，
     * 歌曲模式仍然建一个 {@code searchMode + searchResultList} 的临时列表。</p>
     */
    private void submitSearch(final String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        final String query = keyword.trim();
        this.dismissSuggestions();
        this.searchField.setFocused(false);

        this.playlistPanel.getChildren().forEach(child -> {
            if (child instanceof PlaylistItem) {
                ((PlaylistItem) child).setSelected(false);
            }
        });

        final boolean searchPlaylists = playlistSearchMode
                && CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
        if (searchPlaylists) {
            MultiThreadingUtil.runAsync(() -> {
                List<PlayList> results = searchNeteasePlaylists(query);
                MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().setCurrentPanel(
                        new HomePanel(results, "歌单搜索 · " + query + " · " + results.size())));
            });
        } else {
            // Keep the original track-search flow and its independent temporary playlist.
            PlayList playList = JsonUtils.parse("{}", PlayList.class);
            playList.setSearchMode(true);
            // 只有搜索框产生的列表才走"起播后接最近播放"，发现页复用 searchMode 的那些列表不受影响。
            playList.setSearchResultList(true);
            playList.musics = new CopyOnWriteArrayList<>();
            PlaylistPanel panel = new PlaylistPanel(playList);
            NCMScreen.getInstance().setCurrentPanel(panel);
            MultiThreadingUtil.runAsync(() -> {
                List<Music> search = CloudMusic.search(query);
                playList.musics.addAll(search);
                panel.onInit();
            });
        }
    }

    /** 列表里当前被选中的条目，没有选中项时返回 {@code null}。 */
    private PlaylistItem findSelectedItem() {
        for (AbstractWidget<?> child : this.playlistPanel.getChildren()) {
            if (child instanceof PlaylistItem && ((PlaylistItem) child).isSelected()) {
                return (PlaylistItem) child;
            }
        }
        return null;
    }

    /**
     * Re-selects the sidebar item that represents the currently visible content panel.
     */
    public void selectCurrentPanel(NCMPanel panel) {
        PlaylistItem selectedItem = null;
        if (panel instanceof HomePanel) {
            selectedItem = this.homeItem;
        } else if (panel instanceof PlaylistPanel) {
            PlayList activePlaylist = ((PlaylistPanel) panel).playList;
            if (activePlaylist != null && !activePlaylist.isSearchMode()) {
                for (PlaylistItem item : this.playlistItems) {
                    if (activePlaylist.equals(item.getPlaylist())) {
                        selectedItem = item;
                        break;
                    }
                }
            }
        }

        if (this.homeItem != null) {
            this.homeItem.setSelected(this.homeItem == selectedItem);
        }
        for (PlaylistItem item : this.playlistItems) {
            item.setSelected(item == selectedItem);
        }
    }

    @Override
    public void onInit() {

    }

    /**
     * 一条搜索联想行。
     *
     * <p>行是固定几个、复用的，所以文案必须走 supplier 按下标去读当前的
     * {@link NavigateBar#suggestions}，而不是构造时把字符串塞进去。下标越界要返回空串 ——
     * 行的显隐由 {@link NavigateBar#layoutSuggestions()} 控制，但结果列表可能在两次布局之间被换掉。</p>
     */
    private final class SuggestionRow extends Panel {

        private final int index;

        private SuggestionRow(int index) {
            this.index = index;
            this.setShouldOverrideMouseCursor(true);

            // 高亮必须是一个独立的子组件，不能靠给整行设 alpha 来开关：
            // AbstractWidget#getAlpha 会把 alpha 逐层乘下去，行的 alpha 归零会把行里的文字一起抹掉。
            RoundedRectWidget highlight = new RoundedRectWidget();
            this.addChild(highlight);
            highlight.setClickable(false);
            highlight.setBeforeRenderCallback(() -> {
                highlight.setMargin(0);
                highlight.setRadius(2.5);
                highlight.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                highlight.setAlpha(this.isActive() ? .30f : 0f);
            });

            LabelWidget kind = new LabelWidget(this::kindText, FontManager.pf12);
            this.addChild(kind);
            kind.setClickable(false);
            kind.setBeforeRenderCallback(() -> {
                kind.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                kind.centerVertically();
                kind.setPosition(Math.max(0, this.getWidth() - kind.getWidth() - 5), kind.getRelativeY());
            });

            LabelWidget name = new LabelWidget(this::nameText, FontManager.pf12bold);
            this.addChild(name);
            name.setClickable(false);
            name.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            name.setBeforeRenderCallback(() -> {
                name.setColor(NCMScreen.getColor(this.isActive()
                        ? NCMScreen.ColorType.PRIMARY_TEXT : NCMScreen.ColorType.SECONDARY_TEXT));
                name.centerVertically();
                name.setPosition(6, name.getRelativeY());
                name.setMaxWidth(Math.max(1, this.getWidth() - 6 - kind.getWidth() - 10));
            });

            // 整行的 alpha 就是展开进度：文字与高亮都跟着淡入。
            this.setBeforeRenderCallback(() -> this.setAlpha(suggestReveal));

            // 正常路径是 NavigateBar#consumeSuggestionClick（在 NCMScreen 里前置消费、早退），
            // 这里留着组件树这一条只是兜底：两条路走同一个 selectSuggestion，行为一致。
            this.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0) return false;
                selectSuggestion(this.index);
                return true;
            });
        }

        /** 键盘高亮与鼠标悬停用同一套视觉：两者都表示"回车/点击会选这一条"。 */
        private boolean isActive() {
            return this.isHovering() || suggestHighlight == this.index;
        }

        private NeteaseSearchSuggestService.Suggestion current() {
            List<NeteaseSearchSuggestService.Suggestion> current = suggestions;
            return current == null || this.index >= current.size() ? null : current.get(this.index);
        }

        private String nameText() {
            NeteaseSearchSuggestService.Suggestion suggestion = this.current();
            return suggestion == null || suggestion.text == null ? "" : suggestion.text;
        }

        private String kindText() {
            NeteaseSearchSuggestService.Suggestion suggestion = this.current();
            return suggestion == null || suggestion.kind == null ? "" : suggestion.kind;
        }
    }

    public static class PlaylistItem extends Panel {

        String icon;
        Location iconLocation;
        Supplier<Integer> iconColorSupplier;
        Supplier<String> label;
        Runnable onClick;

        @Getter
        @Setter
        boolean selected = false;

        @Getter
        @Setter
        private PlayList playlist;

        public PlaylistItem(String icon, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this(icon, FontManager.music18, null, iconColorSupplier, label, onClick);
        }

        public PlaylistItem(String icon, CFontRenderer iconFont, Supplier<Integer> iconColorSupplier,
                            Supplier<String> label, Runnable onClick) {
            this(icon, iconFont, null, iconColorSupplier, label, onClick);
        }

        public PlaylistItem(Location iconLocation, Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this(null, FontManager.music18, iconLocation, iconColorSupplier, label, onClick);
        }

        private PlaylistItem(String icon, CFontRenderer iconFont, Location iconLocation,
                             Supplier<Integer> iconColorSupplier, Supplier<String> label, Runnable onClick) {
            this.icon = icon;
            this.iconLocation = iconLocation;
            this.iconColorSupplier = iconColorSupplier;
            this.label = label;
            this.onClick = onClick;
            this.setBeforeRenderCallback(() -> {
                // The item is inset by four pixels on both sides. Subtract the insets from
                // its live width as the player scales so neither background nor text can
                // extend past the navigation viewport.
                this.setBounds(Math.max(1, this.getParentWidth() - 8), 16);
                this.setPosition(4, this.getRelativeY());
            });

            // 选中背景由侧栏共享的那一条高亮条绘制（见 NavigateBar#layoutSelectionIndicator）。
            // 每个条目各画一块的话，切换栏目只能是一块消失、另一块出现，没有位移可动画。

            AbstractWidget<?> iconWidget;
            if (iconLocation == null) {
                LabelWidget glyphIcon = new LabelWidget(icon, iconFont == null ? FontManager.music18 : iconFont);
                this.addChild(glyphIcon);
                glyphIcon.setBeforeRenderCallback(() -> {
                    glyphIcon.setColor(iconColorSupplier.get());
                    glyphIcon.centerVertically();
                    glyphIcon.setPosition(8, glyphIcon.getRelativeY()/* + .5*/);
                });
                glyphIcon.setClickable(false);
                iconWidget = glyphIcon;
            } else {
                ThemedTextureIconWidget textureIcon = new ThemedTextureIconWidget(iconLocation, FontManager.music18,
                        0, 0, 12, 12);
                this.addChild(textureIcon);
                textureIcon.setBeforeRenderCallback(() -> {
                    textureIcon.setColor(iconColorSupplier.get());
                    textureIcon.centerVertically();
                    textureIcon.setPosition(8, textureIcon.getRelativeY());
                });
                textureIcon.setClickable(false);
                iconWidget = textureIcon;
            }

            LabelWidget lbl = new LabelWidget(label, FontManager.pf14bold);
            this.addChild(lbl);

            lbl.setBeforeRenderCallback(() -> {
                lbl.centerVertically();
                lbl.setPosition(iconWidget.getRelativeX() + iconWidget.getWidth() + 4, lbl.getRelativeY());
                lbl.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lbl.setMaxWidth(this.getWidth() - 8 - iconWidget.getWidth() - 12);
            });

            lbl.setClickable(false);

            this.setOnClickCallback(((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    this.selected = true;

                    this.onClick.run();

                    NCMScreen.getInstance().getPlaylistsPanel().playlistPanel.getChildren().stream()
                            .filter(it -> it instanceof PlaylistItem && it != this)
                            .forEach(it -> ((PlaylistItem) it).setSelected(false));
                }

                return true;
            }));
        }

    }

    /**
     * Executes the documented NetEase cloud-search playlist endpoint (type 1000).
     */
    private List<PlayList> searchNeteasePlaylists(String keyword) {
        List<PlayList> results = new ArrayList<>();
        try {
            JsonObject response = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Playlist).toJsonObject();
            if (response == null || !response.has("result") || !response.get("result").isJsonObject()) {
                return results;
            }
            JsonObject result = response.getAsJsonObject("result");
            JsonArray playlists = result.getAsJsonArray("playlists");
            if (playlists == null) {
                return results;
            }

            for (JsonElement element : playlists) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                try {
                    JsonObject object = element.getAsJsonObject();
                    if ((!object.has("coverImgUrl") || object.get("coverImgUrl").isJsonNull())
                            && object.has("picUrl") && !object.get("picUrl").isJsonNull()) {
                        object.addProperty("coverImgUrl", object.get("picUrl").getAsString());
                    }
                    if ((!object.has("playCount") || object.get("playCount").isJsonNull())
                            && object.has("playcount") && !object.get("playcount").isJsonNull()) {
                        object.addProperty("playCount", object.get("playcount").getAsLong());
                    }
                    PlayList playlist = JsonUtils.parse(object, PlayList.class);
                    if (playlist == null || playlist.getId() == 0L || playlist.getName() == null
                            || playlist.getName().trim().isEmpty() || playlist.getCoverUrl() == null
                            || playlist.getCoverUrl().trim().isEmpty()) {
                        continue;
                    }
                    playlist.setPlatform(MusicPlatform.NETEASE);
                    results.add(playlist);
                } catch (Throwable ignored) {
                    // A malformed item cannot invalidate the remaining search results.
                }
            }
        } catch (Throwable throwable) {
            System.err.println("[Music] NetEase playlist search failed: " + throwable.getMessage());
        }
        return results;
    }

}
