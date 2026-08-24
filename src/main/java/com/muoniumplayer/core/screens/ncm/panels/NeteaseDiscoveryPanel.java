package com.muoniumplayer.core.screens.ncm.panels;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseRecentPlaysService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small reusable NetEase discovery surface.  It deliberately keeps provider payloads at the
 * boundary and opens the existing PlaylistPanel for playable song collections, so discovery
 * features do not fork the player, lyric, or download pipelines.
 */
public final class NeteaseDiscoveryPanel extends NCMPanel {

    public enum Page {
        HOT_SEARCH("热搜", "网易云实时热搜"),
        DIGITAL_ALBUMS("我的数字专辑", "已购买的数字专辑"),
        TOP_LISTS("排行榜", "网易云音乐榜单"),
        RECENT_SONGS("最近播放", "最近播放的歌曲");

        final String title;
        final String subtitle;

        Page(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private final Page page;
    private final MusicPlatform platform;
    private volatile List<Entry> entries = Collections.emptyList();
    private volatile boolean loading;
    private volatile String status = "";

    public NeteaseDiscoveryPanel(Page page) {
        this(page, MusicPlatform.NETEASE);
    }

    /**
     * Shared discovery surface: QQ routes use the same list, typography and
     * playback pipeline as the existing NetEase page instead of duplicating UI.
     */
    public NeteaseDiscoveryPanel(Page page, MusicPlatform platform) {
        this.page = page == null ? Page.HOT_SEARCH : page;
        this.platform = platform == null ? MusicPlatform.NETEASE : platform;
    }

    @Override
    public void onInit() {
        // 顺序很关键：先把 loading 立起来再建布局。原来是先 renderLayout()（此时 loading 还是 false）
        // 再 loadPage()（在那里才置 true，但不会重建布局），于是列表区第一帧就是空的 ——
        // 表现就是"最近播放没有加载动画"，只有副标题那一行小字在动。
        loading = true;
        status = "正在获取…";
        renderLayout();
        loadPage();
    }

    private void renderLayout() {
        getChildren().clear();
        final double margin = 12.0;

        LabelWidget title = new LabelWidget(() -> pageTitle(), FontManager.pf25bold);
        addChild(title);
        title.setClickable(false);
        title.setBeforeRenderCallback(() -> title
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, margin));

        LabelWidget subtitle = new LabelWidget(() -> status.isEmpty() ? pageSubtitle() : status, FontManager.pf12);
        addChild(subtitle);
        subtitle.setClickable(false);
        subtitle.setBeforeRenderCallback(() -> subtitle
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                .setMaxWidth(Math.max(1.0, getWidth() - margin * 2.0))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setPosition(margin, 39));

        ScrollPanel list = new ScrollPanel();
        addChild(list);
        list.setSpacing(6);
        list.setScrollStrength(40);
        list.setAlignment(ScrollPanel.Alignment.VERTICAL);
        list.setBeforeRenderCallback(() -> list.setBounds(margin, 60,
                Math.max(1.0, getWidth() - margin * 2.0), Math.max(1.0, getHeight() - 74)));

        if (loading && entries.isEmpty()) {
            // 和歌单页、主页用同一枚转子。原来这里只有一行不会动的"正在获取…"，
            // 看起来像页面卡住了而不是在等网络。
            PanelLoadingWidget spinner = new PanelLoadingWidget("正在获取" + pageTitle() + "…");
            addChild(spinner);
            spinner.setClickable(false);
            spinner.setBeforeRenderCallback(() -> spinner.setBounds(list.getRelativeX(), list.getRelativeY(),
                    Math.max(1.0, list.getWidth()), Math.max(1.0, list.getHeight())));
            return;
        }

        if (entries.isEmpty()) {
            LabelWidget empty = new LabelWidget(status.isEmpty() ? "暂无可显示内容" : status, FontManager.pf14bold);
            list.addChild(empty);
            empty.setClickable(false);
            empty.setBeforeRenderCallback(() -> empty
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setBounds(Math.max(1.0, list.getWidth()), 22));
            return;
        }

        for (Entry entry : entries) {
            RoundedButtonWidget row = new RoundedButtonWidget(entry::getTitle, FontManager.pf14bold);
            list.addChild(row);
            row.setRadius(5);
            row.setOnClickCallback((x, y, button) -> {
                if (button != 0 || entry.action == null) return button == 0;
                entry.action.run();
                return true;
            });
            row.setBeforeRenderCallback(() -> {
                row.setBounds(Math.max(1.0, list.getWidth()), 28);
                row.setColor(row.isHovering() && entry.action != null
                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                        : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
                row.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget detail = new LabelWidget(entry::getSubtitle, FontManager.pf12);
            row.addChild(detail);
            detail.setClickable(false);
            detail.setBeforeRenderCallback(() -> detail
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                    .setMaxWidth(Math.max(1.0, row.getWidth() - 14))
                    .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                    .setPosition(7, Math.max(14, row.getHeight() - detail.getHeight() - 3)));
        }
    }

    private void loadPage() {
        loading = true;
        status = "正在获取…";
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (platform == MusicPlatform.QQ) {
                    loadQQPage();
                    return;
                }
                // 这三页是"中转"：取完数据就把用户送到别处，自己不显示内容。导航栏现在走
                // NCMScreen#navigateAsync 直达，正常不会再进到这里；仍然保留是因为它们还能被
                // 历史记录、外部调用重新实例化。切页用 replaceCurrentPanel —— 用 setCurrentPanel
                // 会把这个中转页留在历史里，按返回退回来它又会重新加载再把人送走。
                if (page == Page.TOP_LISTS) {
                    NCMPanel target = loadTopListsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "排行榜暂无内容";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }
                if (page == Page.DIGITAL_ALBUMS) {
                    NCMPanel target = loadDigitalAlbumsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "暂无已购买数字专辑或登录状态已失效";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }
                if (page == Page.RECENT_SONGS) {
                    NCMPanel target = loadRecentSongsPanel();
                    MultiThreadingUtil.runOnMainThread(() -> {
                        loading = false;
                        if (target == null) {
                            status = "暂无最近播放记录或登录状态已失效";
                            renderLayout();
                        } else {
                            NCMScreen.getInstance().replaceCurrentPanel(target);
                        }
                    });
                    return;
                }

                List<Entry> loaded = requestEntries();
                entries = loaded;
                status = loaded.isEmpty() ? "未获取到内容或当前账号无权限" : "共 " + loaded.size() + " 项";
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            } catch (Throwable throwable) {
                entries = Collections.emptyList();
                status = "获取失败：" + safeMessage(throwable);
                loading = false;
                MultiThreadingUtil.runOnMainThread(this::renderLayout);
            }
        });
    }

    private void loadQQPage() {
        if (page == Page.HOT_SEARCH) {
            List<String> keywords = com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.hotSearches(50);
            List<Entry> hot = new ArrayList<>();
            int rank = 1;
            for (String keyword : keywords) {
                final String selected = keyword;
                hot.add(new Entry(rank++ + ". " + keyword, "点击搜索",
                        () -> openLegacySongSearch(selected)));
            }
            entries = hot;
            status = hot.isEmpty() ? "QQ 音乐热搜暂无内容" : "共 " + hot.size() + " 项";
            loading = false;
            MultiThreadingUtil.runOnMainThread(this::renderLayout);
            return;
        }
        if (page != Page.TOP_LISTS) {
            entries = Collections.emptyList();
            status = "该 QQ 功能暂未由当前数据源提供";
            loading = false;
            MultiThreadingUtil.runOnMainThread(this::renderLayout);
            return;
        }

        // 走到这里说明是被历史记录/外部调用重新实例化的旧入口：导航栏现在直达卡片墙
        // （loadQQTopListsPanel），这条按歌曲行展示的兜底保留着，取的是默认热歌榜。
        final List<Music> tracks = CadenceMusicService.getQQTopTracks(100);
        List<Entry> loaded = new ArrayList<>();
        for (int index = 0; index < tracks.size(); index++) {
            final int playIndex = index;
            final Music track = tracks.get(index);
            if (track == null) continue;
            String artist = track.getArtistsName() == null ? "" : track.getArtistsName();
            String album = track.getAlbum() == null || track.getAlbum().getName() == null ? "" : track.getAlbum().getName();
            String subtitle = artist + (artist.isEmpty() || album.isEmpty() ? "" : " · ") + album;
            loaded.add(new Entry((index + 1) + ". " + track.getName(), subtitle,
                    () -> CloudMusic.play(tracks, playIndex)));
        }
        entries = loaded;
        status = loaded.isEmpty() ? "QQ 音乐排行榜暂无内容" : "QQ 音乐巅峰榜 · " + loaded.size() + " 首";
        loading = false;
        MultiThreadingUtil.runOnMainThread(this::renderLayout);
    }

    private String pageTitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐排行榜";
        if (platform == MusicPlatform.QQ && page == Page.HOT_SEARCH) return "QQ 音乐热搜";
        return page.title;
    }

    private String pageSubtitle() {
        if (platform == MusicPlatform.QQ && page == Page.TOP_LISTS) return "QQ 音乐巅峰榜 · 复用播放器现有列表与播放样式";
        if (platform == MusicPlatform.QQ && page == Page.HOT_SEARCH) return "QQ 音乐实时热搜 · 点击直接搜索";
        return page.subtitle;
    }

    private List<Entry> requestEntries() {
        switch (page) {
            case HOT_SEARCH:
                return hotEntries();
            default:
                return Collections.emptyList();
        }
    }

    private List<Entry> hotEntries() {
        JsonObject root = CloudMusicApi.searchHotDetail().toJsonObject();
        JsonArray data = array(root, "data");
        List<Entry> result = new ArrayList<>();
        if (data == null) return result;
        int index = 1;
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String keyword = text(item, "searchWord", "first", "keyword");
            if (keyword.isEmpty()) continue;
            String content = text(item, "content", "iconType");
            final String selected = keyword;
            result.add(new Entry(index++ + ". " + keyword, content.isEmpty() ? "点击搜索" : content,
                    () -> openLegacySongSearch(selected)));
        }
        return result;
    }

    /**
     * Hot-search shortcuts deliberately reuse the same single-result-list search flow as the sidebar.
     */
    private static void openLegacySongSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        MultiThreadingUtil.runAsync(() -> {
            List<Music> songs = CloudMusic.search(keyword.trim());
            MultiThreadingUtil.runOnMainThread(() -> openSongs("搜索 · " + keyword.trim(), songs));
        });
    }

    private static CardPage digitalAlbumCards() {
        JsonObject root = CloudMusicApi.digitalAlbumPurchased(100, 0).toJsonObject();
        JsonArray data = nestedArray(root, "paidAlbums", "albums", "data");
        List<PlayList> cards = new ArrayList<>();
        Map<Long, Runnable> actions = new HashMap<>();
        if (data == null) return new CardPage(cards, actions);
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject product = element.getAsJsonObject();
            JsonObject album = object(product, "album");
            if (album == null) album = product;
            long id = number(album, "id");
            String name = text(album, "name", "albumName");
            String cover = text(album, "picUrl", "coverImgUrl", "coverUrl");
            if (id <= 0 || name.isEmpty()) continue;
            PlayList card = new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(album, "size", "trackCount")),
                    0L, null, "数字专辑", 0L);
            card.setPlatform(com.muoniumplayer.core.ncm.music.MusicPlatform.NETEASE);
            cards.add(card);
            final long albumId = id;
            actions.put(id, () -> openAlbum(albumId, name));
        }
        return new CardPage(cards, actions);
    }

    private static CardPage topListCards() {
        JsonObject root = CloudMusicApi.topListDetail().toJsonObject();
        JsonArray data = nestedArray(root, "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topListDetailV2().toJsonObject(), "list", "data");
        if (data == null) data = nestedArray(CloudMusicApi.topLists().toJsonObject(), "list", "data");
        List<PlayList> cards = new ArrayList<>();
        if (data == null) return new CardPage(cards, Collections.emptyMap());
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject list = element.getAsJsonObject();
            long id = number(list, "id");
            String name = text(list, "name");
            String cover = text(list, "coverImgUrl", "coverUrl", "picUrl");
            if (id <= 0 || name.isEmpty()) continue;
            cards.add(new PlayList(id, name, cover, (int) Math.min(Integer.MAX_VALUE, number(list, "trackCount")), 0L,
                    null, text(list, "description", "updateFrequency"), 0L));
        }
        return new CardPage(cards, Collections.emptyMap());
    }

    private static void openAlbum(long albumId, String albumName) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject root = CloudMusicApi.album(albumId).toJsonObject();
                JsonObject privilegeRoot = CloudMusicApi.albumPrivilege(albumId).toJsonObject();
                JsonArray songs = firstSongArray(root, "songs");
                JsonArray privileges = array(privilegeRoot, "privileges", "data");
                if (privileges == null) {
                    JsonObject data = object(privilegeRoot, "data");
                    privileges = array(data, "privileges", "songs", "data");
                }
                List<Music> parsed = parseSongs(songs, privileges);
                MultiThreadingUtil.runOnMainThread(() -> openSongs("专辑 · " + albumName, parsed));
            } catch (Throwable ignored) {
            }
        });
    }

    private static void openPlaylist(long playlistId, String name) {
        try {
            JsonObject item = new JsonObject();
            item.addProperty("id", playlistId);
            item.addProperty("name", name);
            item.addProperty("coverImgUrl", "");
            item.addProperty("trackCount", 0);
            NCMScreen.getInstance().setCurrentPanel(new PlaylistPanel(JsonUtils.parse(item, PlayList.class)));
        } catch (Throwable ignored) {
        }
    }

    public static void openSongs(String title, List<Music> songs) {
        openSongs(title, songs, true);
    }

    private static void openSongs(String title, List<Music> songs, boolean showBackButton) {
        // setCurrentPanel() invokes onInit() exactly once. Calling it again here
        // duplicated every row and made the recent-play page render overlapping text.
        NCMScreen.getInstance().setCurrentPanel(buildSongsPanel(songs, showBackButton));
    }

    /**
     * 只<b>构造</b>歌曲列表页而不切页。可以在工作线程调用（不碰 GL，{@code onInit} 由
     * {@code setCurrentPanel} 在主线程触发），所以导航栏那条"先取数据再一次切页"的路径能复用它。
     */
    private static PlaylistPanel buildSongsPanel(List<Music> songs, boolean showBackButton) {
        CloudMusic.currentPlaylistContext = null;
        PlayList playlist = JsonUtils.parse("{}", PlayList.class);
        playlist.setSearchMode(true);
        playlist.setMusics(new CopyOnWriteArrayList<>(songs == null ? Collections.<Music>emptyList() : songs));
        playlist.setMusicsQueried(true);
        playlist.setMusicsLoaded(true);
        return new PlaylistPanel(playlist, showBackButton);
    }

    /**
     * 以下三个工厂给 {@code NCMScreen#navigateAsync} 用：<b>在工作线程</b>取数据并直接返回目标页面，
     * 返回 {@code null} 表示没有内容可展示（调用方留在当前页面并给一条提示）。
     *
     * <p>它们存在的理由是这三个入口本来就不是"页面"，只是"取完数据去哪"的决策 ——
     * 原来靠一个空的中转面板承载这段等待，代价是两次页面切换加一条回不去的历史。</p>
     */
    public static NCMPanel loadTopListsPanel() {
        CardPage cards = topListCards();
        if (cards.items.isEmpty()) return null;
        return new HomePanel(cards.items, "排行榜 · " + cards.items.size());
    }

    /**
     * QQ 音乐榜单卡片墙。
     *
     * <p>原来 QQ 的"排行榜"只写死两个榜（26 / 4）并按歌曲行展示；这里改成把 QQ 自己那份榜单目录
     * （{@code fcg_myqq_toplist}，实测 25 个榜）铺成卡片，和网易云榜单页保持同一种形态。</p>
     *
     * <p>卡片不预取曲目：{@code platformPlaylistId} 写成 {@code toplist:<id>}，点进去由
     * {@code PlayList#queryMusics} 走 QQ 那条既有的懒加载路径，所以这一页只发一个请求。</p>
     */
    public static NCMPanel loadQQTopListsPanel() {
        List<com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.TopList> lists =
                com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.topLists();
        if (lists.isEmpty()) return null;
        List<PlayList> cards = new ArrayList<>();
        for (com.muoniumplayer.core.ncm.qq.QQDiscoveryApi.TopList list : lists) {
            PlayList card = new PlayList(-3_000_000L - list.id, list.title, list.coverUrl, 0, 0L,
                    null, "QQ 音乐榜单", 0L);
            card.setPlatform(MusicPlatform.QQ);
            card.setPlatformPlaylistId("toplist:" + list.id);
            cards.add(card);
        }
        return new HomePanel(cards, "QQ 音乐榜单 · " + cards.size());
    }

    public static NCMPanel loadDigitalAlbumsPanel() {
        CardPage cards = digitalAlbumCards();
        if (cards.items.isEmpty()) return null;
        return new HomePanel(cards.items, "我的数字专辑 · " + cards.items.size(), cards.actions);
    }

    public static NCMPanel loadRecentSongsPanel() {
        JsonObject root = CloudMusicApi.recentSongs(100).toJsonObject();
        List<Music> songs = parseSongsFromRecentResponse(root);
        if (songs.isEmpty()) return null;
        return buildSongsPanel(songs, false);
    }


    /**
     * 最近播放的解析已经搬到 {@link NeteaseRecentPlaysService}：搜索结果起播也要用同一份数据，
     * 两处各写一份解析迟早会分叉。这里保留一层薄封装，本页的调用点不必关心它在哪。
     */
    private static List<Music> parseSongsFromRecentResponse(JsonObject root) {
        return NeteaseRecentPlaysService.parseRecentSongs(root, NeteaseRecentPlaysService.MAX_RECENT_SONGS);
    }

    private static List<Music> parseSongs(JsonArray songs) {
        return parseSongs(songs, null);
    }

    private static List<Music> parseSongs(JsonArray songs, JsonArray privileges) {
        if (songs == null) return Collections.emptyList();
        Map<Long, JsonObject> privilegesBySongId = new HashMap<>();
        if (privileges != null) {
            for (JsonElement privilegeElement : privileges) {
                if (!privilegeElement.isJsonObject()) continue;
                JsonObject privilege = privilegeElement.getAsJsonObject();
                long id = number(privilege, "id");
                if (id <= 0) id = number(privilege, "songId");
                if (id > 0) privilegesBySongId.put(id, privilege);
            }
        }

        List<Music> result = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            JsonElement element = songs.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            JsonObject actual = object(song, "song");
            if (actual == null) actual = song;
            try {
                Music music = JsonUtils.parse(actual, Music.class);
                JsonObject privilege = privilegesBySongId.get(music.getId());
                if (privilege == null && privileges != null && index < privileges.size() && privileges.get(index).isJsonObject()) {
                    privilege = privileges.get(index).getAsJsonObject();
                }
                // Reuse the existing highest-quality / VIP / album badge metadata path.
                music.applyNeteaseMetadata(actual, privilege);
                result.add(music);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static JsonArray nestedArray(JsonObject root, String... names) {
        JsonArray direct = array(root, names);
        if (direct != null) return direct;
        JsonObject data = object(root, "data");
        direct = array(data, names);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        return array(result, names);
    }

    private static JsonArray firstSongArray(JsonObject root, String firstKey) {
        JsonArray direct = array(root, firstKey);
        if (direct != null) return direct;
        JsonObject result = object(root, "result");
        if (result != null) {
            direct = array(result, firstKey, "songs");
            if (direct != null) return direct;
        }
        return null;
    }

    private static JsonArray array(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            try {
                if (object.has(name) && object.get(name).isJsonArray()) return object.getAsJsonArray(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static long number(JsonObject object, String... names) {
        if (object == null || names == null) return 0L;
        for (String name : names) {
            try {
                if (name != null && object.has(name) && !object.get(name).isJsonNull()) {
                    return object.get(name).getAsLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String text(JsonObject object, String... names) {
        if (object == null) return "";
        for (String name : names) {
            try {
                if (object.has(name) && !object.get(name).isJsonNull()) {
                    String value = object.get(name).getAsString();
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String artistText(JsonObject album) {
        String artist = text(object(album, "artist"), "name");
        if (!artist.isEmpty()) return artist;
        JsonArray artists = array(album, "artists");
        if (artists == null || artists.size() == 0 || !artists.get(0).isJsonObject()) return "";
        return text(artists.get(0).getAsJsonObject(), "name");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "网络请求失败" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "网络请求失败" : message.trim();
    }

    private static final class CardPage {
        private final List<PlayList> items;
        private final Map<Long, Runnable> actions;

        private CardPage(List<PlayList> items, Map<Long, Runnable> actions) {
            this.items = items == null ? Collections.emptyList() : items;
            this.actions = actions == null ? Collections.emptyMap() : actions;
        }
    }

    private static final class Entry {
        private final String title;
        private final String subtitle;
        private final Runnable action;

        private Entry(String title, String subtitle, Runnable action) {
            this.title = title == null || title.trim().isEmpty() ? "未命名" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action;
        }

        private String getTitle() {
            return title;
        }

        private String getSubtitle() {
            return subtitle;
        }
    }
}
