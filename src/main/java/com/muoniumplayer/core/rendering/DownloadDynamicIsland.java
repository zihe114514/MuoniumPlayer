package com.muoniumplayer.core.rendering;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Global, screen-independent download activity island.
 *
 * <p>This class owns both the small thread-safe download snapshot and its presentation. Forge
 * renders it as a global overlay above gameplay and every GuiScreen, so it does not depend on
 * the full-screen player or inherit the player's layout/scale animation.</p>
 */
public final class DownloadDynamicIsland implements SharedConstants, SharedRenderingConstants {

    private static final DownloadDynamicIsland INSTANCE = new DownloadDynamicIsland();
    private static final double COMPACT_WIDTH = 40.0;
    private static final double COMPACT_HEIGHT = 16.0;
    private static final double TOP_MARGIN = 6.0;
    private static final double MIN_AUTO_TEXT_SCALE = .62;
    private static final double MAX_TEXT_SCALE = 1.18;
    private static final double CONTENT_CHANGE_SLIDE = 4.5;

    /** Modern island silhouettes inspired by pill, glass, compact status and card surfaces. */
    public enum DynamicIslandStyle {
        PILL("经典胶囊"),
        GLASS("通透玻璃"),
        COMPACT("紧凑状态"),
        CARD("浮层卡片"),
        SYSTEM_CARD("系统通知"),
        MUSIC_FOCUS("音乐聚焦"),
        LIQUID_GLASS("液态玻璃");

        private final String displayName;

        DynamicIslandStyle(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static DynamicIslandStyle getStyle() {
        DynamicIslandStyle[] styles = DynamicIslandStyle.values();
        return styles[HudConfig.clampInt(HudConfig.dynamicIslandStyle, 0, styles.length - 1)];
    }

    public static String getStyleName() {
        return getStyle().getDisplayName();
    }

    /** Cycles only the island presentation; download state and notices remain untouched. */
    public static void cycleStyle() {
        DynamicIslandStyle[] styles = DynamicIslandStyle.values();
        HudConfig.dynamicIslandStyle = (HudConfig.clampInt(HudConfig.dynamicIslandStyle, 0, styles.length - 1) + 1) % styles.length;
        HudConfig.save();
        publishNotice(IslandNoticeType.STYLE, "灵动岛样式", getStyleName());
    }

    private static volatile boolean downloading;
    private static volatile double downloadProgress;
    private static volatile String downloadSpeed = "0 b/s";
    private static volatile long downloadStartedAt;
    private static volatile long downloadCompletedAt;
    private static volatile boolean transcoding;
    private static volatile double transcodeProgress;
    /** Last moment {@link #transcodeProgress} actually moved; drives the transcode stall watchdog. */
    private static volatile long transcodeProgressAdvancedAt;

    private static volatile IslandNoticeType noticeType = IslandNoticeType.NONE;
    private static volatile String noticeTitle = "";
    private static volatile String noticeValue = "";
    private static volatile long noticeShownAt;
    private static volatile long noticeRevision;
    private static volatile boolean noticePersistent;
    /** Serializes notices arriving from async network/playback workers. */
    private static final Object NOTICE_QUEUE_LOCK = new Object();
    private static final Deque<QueuedNotice> NOTICE_QUEUE = new ArrayDeque<QueuedNotice>();
    /** A missing refresh callback must not leave the island in a permanent loading state. */
    private static final long PLAYLIST_REFRESH_NOTICE_TIMEOUT_MILLIS = 30_000L;
    /** GD URL resolution has a longer network budget, but its loading card must still terminate. */
    private static final long GD_SOURCE_NOTICE_TIMEOUT_MILLIS = 45_000L;
    /** Playlist membership submits are short requests; a lost callback must not pin the card. */
    private static final long PLAYLIST_TRACK_ADD_NOTICE_TIMEOUT_MILLIS = 20_000L;
    /** Transcoding may legitimately run for minutes, so it is watched for stalls, not total time. */
    private static final long TRANSCODE_STALL_TIMEOUT_MILLIS = 30_000L;
    /** Safety net: a persistent card without a dedicated channel still terminates on its own. */
    private static final long DEFAULT_PERSISTENT_NOTICE_TIMEOUT_MILLIS = 30_000L;
    /**
     * Absolute moment the active persistent card gives up. It is captured once per status channel, so
     * repeated "still working" updates (clicking refresh again, a retried resolve) can no longer slide
     * the deadline forward and leave the island stuck in a loading state forever.
     */
    private static volatile long noticeDeadlineAt;
    /** True only for ordinary notices that are part of a multi-message queue. */
    private static volatile boolean noticeUsesQueueInterval;

    private float visibility;
    private double expansion;
    private float contentAlpha;
    private double animatedProgress;
    private float successMorph;
    private double spinnerRotation;
    private double lastObservedProgress;
    private boolean previousDownloading;
    private boolean completing;
    private boolean initialized;
    private long shownAt;
    private long completeAt;
    private long observedNoticeRevision = -1L;
    /** Animated expanded bounds; compact entry/exit remains driven by {@link #expansion}. */
    private double animatedExpandedWidth = -1.0;
    private double animatedExpandedHeight = -1.0;
    /** Fades and gently offsets replacement content without interrupting the island surface. */
    private float contentTransition = 1f;
    /** Last notification snapshot used to distinguish an actual replacement from a hold refresh. */
    private IslandNoticeType observedNoticeType = IslandNoticeType.NONE;
    private String observedNoticeTitle = "";
    private String observedNoticeValue = "";
    /** Outgoing copy is kept briefly so updates flow into the latest notice rather than re-entering. */
    private String outgoingNoticeTitle = "";
    private String outgoingNoticeValue = "";
    private float noticeCopyTransition = 1f;
    /** Volume has a separate target animation; its track never jumps or restarts the island. */
    private double animatedNoticeProgress;

    /**
     * 常驻内容与通知内容之间的交叉淡入进度：1 为常驻状态栏，0 为通知/下载卡片。
     * 常驻不是一种通知，因此它只参与这一层内容混合，完全不进 {@link #NOTICE_QUEUE}。
     */
    private float ambientBlend;
    private boolean previousAlwaysOn;
    private IslandAmbientStatus.Snapshot ambientSnapshot = IslandAmbientStatus.EMPTY;
    private long ambientSampledAt;

    private DownloadDynamicIsland() {
    }

    /** Render above the normal game HUD. */
    public static void render() {
        INSTANCE.renderInternal();
    }

    /** A live-size preview used by the HUD editor even when no download is active. */
    public static void renderEditorPreview() {
        INSTANCE.renderPreviewInternal();
    }

    /** Shows the active theme in the global island after the compact icon is used. */
    public static void showTheme(String themeName) {
        publishNotice(IslandNoticeType.THEME, "当前主题", safeNoticeValue(themeName, "默认主题"));
    }

    /** Shows the effective full-screen player scale in the global island. */
    public static void showPlayerScale(int scalePercent) {
        int safePercent = Math.max(50, Math.min(150, scalePercent));
        publishNotice(IslandNoticeType.SCALE, "播放器大小", safePercent + "%");
    }

    /** Keeps the island visible until the asynchronous playlist request finishes. */

    /** Shows the persisted music volume together with a matching progress indicator. */
    public static void showVolume(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        publishNotice(IslandNoticeType.VOLUME, "音乐音量", safePercent + "%");
    }
    /** Shows the stable transcode card; progress updates only move the bar and do not restart entry animation. */
    public static void beginTranscode(String sourceName, String targetName) {
        transcoding = true;
        transcodeProgress = 0.0;
        transcodeProgressAdvancedAt = System.currentTimeMillis();
        publishNotice(IslandNoticeType.TRANSCODING, "音频转码",
                formatTranscodeValue(sourceName, targetName), true);
    }

    /** Updates the transcode progress without publishing a new notice revision. */
    public static void updateTranscodeProgress(double progress) {
        double clamped = DynamicIslandMath.clamp01(progress);
        // A stalled decoder stops moving the bar; that is what the watchdog below reacts to.
        if (clamped > transcodeProgress) transcodeProgressAdvancedAt = System.currentTimeMillis();
        transcodeProgress = clamped;
    }

    /** Shows the verified conversion result for the configured completion hold duration. */
    public static void finishTranscode(String sourceName, String targetName, long elapsedMillis) {
        transcoding = false;
        transcodeProgress = 1.0;
        publishNotice(IslandNoticeType.TRANSCODE_SUCCESS, "转码完成",
                formatTranscodeValue(sourceName, targetName) + " · " + Math.max(0L, elapsedMillis) + " ms");
    }

    /** Removes an interrupted transcode card before the normal playback-failure notice is shown. */
    public static void cancelTranscode() {
        transcoding = false;
        transcodeProgress = 0.0;
        if (noticeType == IslandNoticeType.TRANSCODING) {
            publishNotice(IslandNoticeType.NONE, "", "");
        }
    }

    private static String formatTranscodeValue(String sourceName, String targetName) {
        return safeNoticeValue(sourceName, "输入音频") + " → " + safeNoticeValue(targetName, "WAV");
    }
    public static void showPlaylistRefreshInProgress() {
        publishNotice(IslandNoticeType.REFRESHING, "歌单同步", "正在刷新…", true);
    }

    public static void showPlaylistRefreshSuccess(int playlistCount, long elapsedMillis) {
        int safeCount = Math.max(0, playlistCount);
        long safeElapsed = Math.max(0L, elapsedMillis);
        completePersistentNotice(IslandNoticeType.REFRESHING, IslandNoticeType.REFRESH_SUCCESS,
                "歌单同步", "已同步 " + safeCount + " 个歌单 · " + safeElapsed + " ms");
    }

    public static void showPlaylistRefreshFailure(String message) {
        completePersistentNotice(IslandNoticeType.REFRESHING, IslandNoticeType.REFRESH_ERROR,
                "歌单同步失败", safeNoticeValue(message, "网络请求失败"));
    }
    /** Keeps the island visible while a selected track is submitted to a playlist. */
    public static void showPlaylistTrackAddInProgress(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADDING, "加入歌单",
                "正在加入「" + safeNoticeValue(playlistName, "目标歌单") + "」", true);
    }

    /** Announces a verified new playlist membership. */
    public static void showPlaylistTrackAddSuccess(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS, "已加入歌单",
                "网易云已确认「" + safeNoticeValue(playlistName, "目标歌单") + "」");
    }

    /** Announces the separately verified no-op state without claiming a new add succeeded. */
    public static void showPlaylistTrackAlreadyExists(String playlistName) {
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS, "歌曲已在歌单中",
                "已确认「" + safeNoticeValue(playlistName, "目标歌单") + "」");
    }

    /** Announces that the request was not verified by the target playlist's trackIds. */
    public static void showPlaylistTrackAddFailure(String playlistName, String message) {
        String value = safeNoticeValue(message, "服务端未确认加入成功");
        publishNotice(IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR, "加入歌单失败",
                safeNoticeValue(playlistName, "目标歌单") + " · " + value);
    }

    /** Shows the stream tier and container resolved for the track that just started. */
    public static void showPlaybackQuality(String quality, String format) {
        String safeQuality = safeNoticeValue(quality, "标准");
        String safeFormat = safeNoticeValue(format, "未知").toUpperCase(java.util.Locale.ROOT);
        publishNotice(IslandNoticeType.QUALITY, "获取音质：" + safeQuality, "格式: " + safeFormat);
    }

    /**
     * Reports a generic MP4 payload detected from the downloaded bytes. The playback pipeline keeps
     * the header check but intentionally does not transcode this container.
     */
    public static void showUnsupportedMp4Container(String sourceName) {
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "检测到不兼容 MP4 容器",
                safeNoticeValue(sourceName, "当前音频") + " · 已跳过并尝试备用音源");
    }

    /** Reports a real playback failure without treating a user-initiated track switch as an error. */
    public static void showPlaybackFailure(String songName, String reason) {
        String safeSong = safeNoticeValue(songName, "当前歌曲");
        String safeReason = safeNoticeValue(reason, "无法获取可播放音频");
        publishNotice(IslandNoticeType.PLAYBACK_ERROR, "播放失败", safeSong + " · " + safeReason);
    }

    /**
     * 网易云上报进行中的提示。刻意和上报结果同属最低优先级并走排队通道：它是后台遥测，
     * 不能抢占任何用户主动触发的反馈，也不做 persistent（否则会插队并需要超时兜底）。
     */
    public static void showNeteaseUploadStarted(String label) {
        publishNotice(IslandNoticeType.NETEASE_UPLOADING, safeNoticeValue(label, "网易云上报"),
                "正在上报到网易云客户端");
    }

    /** Shows the immediate recent-play upload result and request latency. */
    public static void showRecentPlayUploadSuccess(long elapsedMillis) {
        publishNotice(IslandNoticeType.RECENT_PLAY_SUCCESS, "听歌历史上报",
                "最近播放上报成功 " + Math.max(0L, elapsedMillis) + "ms");
    }

    public static void showRecentPlayUploadFailure(long elapsedMillis, String reason) {
        publishNotice(IslandNoticeType.RECENT_PLAY_ERROR, "听歌历史上报",
                "最近播放上报失败 " + Math.max(0L, elapsedMillis) + "ms · "
                        + safeNoticeValue(reason, "请求失败"));
    }

    /** Shows a cumulative listening-duration checkpoint result and request latency. */
    public static void showListeningDurationUploadSuccess(int playedSeconds, long elapsedMillis) {
        publishNotice(IslandNoticeType.LISTENING_DURATION_SUCCESS, "听歌时长上报",
                "听歌时长上报成功 时长" + Math.max(0, playedSeconds) + "秒 "
                        + Math.max(0L, elapsedMillis) + "ms");
    }

    public static void showListeningDurationUploadFailure(int playedSeconds, long elapsedMillis, String reason) {
        publishNotice(IslandNoticeType.LISTENING_DURATION_ERROR, "听歌时长上报",
                "听歌时长上报失败 时长" + Math.max(0, playedSeconds) + "秒 "
                        + Math.max(0L, elapsedMillis) + "ms · "
                        + safeNoticeValue(reason, "请求失败"));
    }
    /**
     * Announces a seamless automix handover. An ordinary notice on purpose: the blend is already
     * audible, so this card must queue politely behind anything the user actually triggered instead of
     * interrupting it.
     */
    public static void showAutomixHandover(String trackName, String detail) {
        publishNotice(IslandNoticeType.AUTOMIX, "无缝切换",
                safeNoticeValue(trackName, "下一首") + " · " + safeNoticeValue(detail, "已交接"));
    }

    /** Reports that the next track is decoded and waiting on the idle deck. */
    public static void showAutomixArmed(String trackName) {
        publishNotice(IslandNoticeType.AUTOMIX, "下一首已就绪",
                safeNoticeValue(trackName, "下一首") + " · 将无缝接入");
    }

    /**
     * Confirms that a track was queued to play next. An ordinary notice: the user triggered it, but it
     * changes nothing audible right now, so it queues politely like every other confirmation.
     *
     * @param pending how many user-queued tracks are now waiting, including this one
     */
    public static void showQueuedNext(String trackName, int pending) {
        String detail = pending > 1
                ? "已排在第 " + pending + " 位 · 播完后回到歌单"
                : "下一首就播 · 播完后回到歌单";
        publishNotice(IslandNoticeType.PLAY_NEXT, "已加入下一首播放",
                safeNoticeValue(trackName, "所选歌曲") + " · " + detail);
    }

    /**
     * 搜索结果起播后，队列已经接上"最近播放"的提示。普通通道：歌已经在放了，这只是解释后面会播什么。
     */
    public static void showSearchQueueFromRecentPlays(int count) {
        publishNotice(IslandNoticeType.PLAY_NEXT, "已接入最近播放",
                Math.max(0, count) + " 首 · 播完当前歌曲后继续");
    }

    /**
     * 私人 FM 已经把下一首推荐预备进队列的提示。普通通道：这只是解释接下来会播什么，必须老实排在用户
     * 主动触发的反馈后面，不能抢占正在显示的下载 / 转码卡片。
     */
    public static void showPersonalFmPrefetched(String trackName) {
        publishNotice(IslandNoticeType.PLAY_NEXT, "已预备下一首",
                safeNoticeValue(trackName, "下一首推荐") + " · 私人 FM 将无缝接入");
    }

    /** Reports that "play next" had no queue to insert into and simply started the track. */
    public static void showQueuedNextStarted(String trackName) {
        publishNotice(IslandNoticeType.PLAY_NEXT, "开始播放",
                safeNoticeValue(trackName, "所选歌曲") + " · 当前没有播放队列，已直接播放");
    }

    /**
     * 全局快捷键切换暂停/继续后的提示。刻意用普通通道（非 persistent）：这只是一次状态确认，必须
     * 老实排在用户触发的其它反馈后面，不能抢占正在显示的下载/转码卡片。
     */
    public static void showPlaybackPaused(String trackName) {
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "已暂停",
                safeNoticeValue(trackName, "当前播放") + " · 再按一次继续");
    }

    /** 与 {@link #showPlaybackPaused(String)} 成对，报告播放已恢复。 */
    public static void showPlaybackResumed(String trackName) {
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "继续播放",
                safeNoticeValue(trackName, "当前播放"));
    }

    /** 快捷键按下但当前没有可暂停的播放时的提示，避免用户以为按键没生效。 */
    public static void showPlaybackToggleUnavailable() {
        publishNotice(IslandNoticeType.PLAYBACK_TOGGLE, "暂停快捷键", "当前没有正在播放的歌曲");
    }

    /** Reports the automix switch itself being turned on or off. */
    public static void showAutomixToggle(boolean enabled, String detail) {
        publishNotice(IslandNoticeType.AUTOMIX, enabled ? "无缝切换已开启" : "无缝切换已关闭",
                safeNoticeValue(detail, enabled ? "切歌时自动交叉淡化" : "切歌恢复为直接切换"));
    }

    /** Feedback for GD Studio API URL resolution. */
    public static void showGdSourceResolving(String sourceName) {
        publishNotice(IslandNoticeType.GD_SOURCE_LOADING, "GD音乐台获取链接",
                "正在连接 " + safeNoticeValue(sourceName, "GD 音乐源"), true);
    }

    public static void showGdSourceResolved(String sourceName, String format) {
        String source = safeNoticeValue(sourceName, "GD 音乐源");
        String streamFormat = safeNoticeValue(format, "未知").toUpperCase(java.util.Locale.ROOT);
        completePersistentNotice(IslandNoticeType.GD_SOURCE_LOADING, IslandNoticeType.GD_SOURCE_SUCCESS,
                "音频直链已获取", source + " · " + streamFormat + " · 可开始播放");
        // This ordinary follow-up intentionally enters the existing notice queue. It provides a
        // clear health confirmation without interrupting any notice preserved by the live loader.
        publishNotice(IslandNoticeType.GD_SOURCE_HEALTHY, "当前音源正常",
                source + " · 当前 API 已返回可播放链接");
    }

    /**
     * Search-time notice: another source served the query. {@code requestedSourceFailed} separates a
     * real outage from a source that simply had no match, so the card never misreports the cause.
     */
    public static void showGdSearchFallback(String requestedSource, String servedSource,
                                            boolean requestedSourceFailed) {
        publishNotice(IslandNoticeType.GD_SOURCE_HEALTHY, "音源已自动回退",
                safeNoticeValue(requestedSource, "所选音源")
                        + (requestedSourceFailed ? " 暂不可用 · 已用 " : " 无匹配结果 · 已用 ")
                        + safeNoticeValue(servedSource, "备用音源") + " 返回结果");
    }

    /** Search-time notice: neither the selected source nor the fallback returned anything. */
    public static void showGdSearchFailure(String requestedSource, String reason) {
        publishNotice(IslandNoticeType.GD_SOURCE_ERROR, "音源搜索失败",
                safeNoticeValue(requestedSource, "所选音源") + " · "
                        + safeNoticeValue(reason, "请求失败"));
    }

    public static void showGdSourceFailure(String sourceName, String reason) {
        completePersistentNotice(IslandNoticeType.GD_SOURCE_LOADING, IslandNoticeType.GD_SOURCE_ERROR,
                "GD音乐台链接获取失败", safeNoticeValue(sourceName, "GD 音乐源") + " · "
                        + safeNoticeValue(reason, "解析失败"));
    }


    /** Gives login and account validation a distinct, non-download success feedback. */
    public static void showNetworkConnectionSuccess(String target) {
        publishNotice(IslandNoticeType.NETWORK_SUCCESS, "网络连接成功",
                safeNoticeValue(target, "音乐服务") + " · 已建立连接");
    }

    /** Gives login and account validation a distinct, readable connection failure feedback. */
    public static void showNetworkConnectionFailure(String target, String reason) {
        publishNotice(IslandNoticeType.NETWORK_ERROR, "网络连接失败",
                safeNoticeValue(target, "音乐服务") + " · " + safeNoticeValue(reason, "请检查网络后重试"));
    }
    private static long noticeHoldMillis(boolean queueMode) {
        return DynamicIslandMath.completionHoldMillis(queueMode
                ? HudConfig.dynamicIslandQueueIntervalSeconds
                : HudConfig.dynamicIslandCompletionHoldSeconds);
    }

    private static void publishNotice(IslandNoticeType type, String title, String value) {
        publishNotice(type, title, value, false);
    }

    private static void publishNotice(IslandNoticeType type, String title, String value, boolean persistent) {
        IslandNoticeType safeType = type == null ? IslandNoticeType.NONE : type;
        String safeTitle = safeNoticeValue(title, "状态");
        String safeValue = safeNoticeValue(value, "—");
        long now = System.currentTimeMillis();
        synchronized (NOTICE_QUEUE_LOCK) {
            if (safeType == IslandNoticeType.NONE) {
                NOTICE_QUEUE.clear();
                publishActiveNoticeLocked(safeType, safeTitle, safeValue, persistent, false, now);
                return;
            }

            if (noticeType != IslandNoticeType.NONE && isLowestPriorityNotice(safeType)) {
                // NetEase listening-duration reports are background telemetry. Keep them behind
                // every visible/user-facing notification rather than allowing them to interrupt it.
                noticeUsesQueueInterval = noticeUsesQueueInterval || !noticePersistent;
                markQueuedNoticesUseQueueIntervalLocked();
                NOTICE_QUEUE.addLast(new QueuedNotice(safeType, safeTitle, safeValue, persistent, true));
                return;
            }

            if (noticeType != IslandNoticeType.NONE && isLowestPriorityNotice(noticeType)) {
                // A later user-facing notification takes the active slot; defer the telemetry card
                // to the tail so it remains strictly lowest priority even when it arrived first.
                NOTICE_QUEUE.addLast(new QueuedNotice(noticeType, noticeTitle, noticeValue,
                        noticePersistent, true));
                markQueuedNoticesUseQueueIntervalLocked();
                publishActiveNoticeLocked(safeType, safeTitle, safeValue, persistent,
                        !bypassesNoticeQueue(safeType, persistent), now);
                return;
            }

            // Real-time notices such as volume adjustments must keep the original immediate,
            // coalescing behaviour. They never enter the ordinary notification queue.
            if (bypassesNoticeQueue(safeType, persistent)) {
                preserveActiveQueuedNoticeLocked();
                publishActiveNoticeLocked(safeType, safeTitle, safeValue, persistent, false, now);
                return;
            }

            if (noticeType == IslandNoticeType.NONE) {
                publishActiveNoticeLocked(safeType, safeTitle, safeValue, persistent, false, now);
                return;
            }

            if (bypassesNoticeQueue(noticeType, noticePersistent)) {
                boolean alreadyQueued = !NOTICE_QUEUE.isEmpty();
                if (alreadyQueued) markQueuedNoticesUseQueueIntervalLocked();
                NOTICE_QUEUE.addLast(new QueuedNotice(safeType, safeTitle, safeValue, persistent, alreadyQueued));
                return;
            }

            // A second ordinary notice means the current notice and all queued notices use the
            // user-configured queue interval instead of the single-notice completion hold.
            noticeUsesQueueInterval = true;
            markQueuedNoticesUseQueueIntervalLocked();
            NOTICE_QUEUE.addLast(new QueuedNotice(safeType, safeTitle, safeValue, persistent, true));
        }
    }

    /**
     * Replaces a matching live status card with its terminal result without clearing the queued
     * notices that the live card may have temporarily interrupted.
     */
    private static void completePersistentNotice(IslandNoticeType loadingType, IslandNoticeType resultType,
                                                 String title, String value) {
        long now = System.currentTimeMillis();
        synchronized (NOTICE_QUEUE_LOCK) {
            if (noticeType == loadingType && noticePersistent) {
                boolean queueMode = !NOTICE_QUEUE.isEmpty();
                if (queueMode) markQueuedNoticesUseQueueIntervalLocked();
                publishActiveNoticeLocked(resultType, safeNoticeValue(title, "状态"),
                        safeNoticeValue(value, "—"), false, queueMode, now);
                return;
            }
        }
        publishNotice(resultType, title, value);
    }

    /** NetEase cumulative listening-duration uploads are non-interactive telemetry. */
    private static boolean isLowestPriorityNotice(IslandNoticeType type) {
        return type == IslandNoticeType.LISTENING_DURATION_SUCCESS
                || type == IslandNoticeType.LISTENING_DURATION_ERROR
                // 上报进行中与上报结果是同一条遥测链路，优先级必须一致，否则"上报中"会插到
                // 用户触发的反馈前面，而它的结果卡片还老实排在后面。
                || type == IslandNoticeType.NETEASE_UPLOADING;
    }

    /** Volume and persistent progress/status cards stay immediate rather than being queued. */
    private static boolean bypassesNoticeQueue(IslandNoticeType type, boolean persistent) {
        return persistent || type == IslandNoticeType.VOLUME || type == IslandNoticeType.TRANSCODING;
    }

    /** Preserves an interrupted ordinary notice so a live volume/status update cannot discard it. */
    private static void preserveActiveQueuedNoticeLocked() {
        if (noticeType == IslandNoticeType.NONE || bypassesNoticeQueue(noticeType, noticePersistent)) return;
        NOTICE_QUEUE.addFirst(new QueuedNotice(noticeType, noticeTitle, noticeValue,
                noticePersistent, noticeUsesQueueInterval));
    }

    private static void markQueuedNoticesUseQueueIntervalLocked() {
        for (QueuedNotice notice : NOTICE_QUEUE) {
            notice.useQueueInterval = true;
        }
    }

    private static void publishActiveNoticeLocked(IslandNoticeType type, String title, String value,
                                                   boolean persistent, boolean useQueueInterval, long shownAt) {
        // Keep the deadline of a status channel that is merely re-announcing itself, so a repeated
        // "still working" update cannot postpone the timeout indefinitely.
        boolean sameChannel = persistent && noticePersistent && noticeType == type && noticeDeadlineAt > 0L;
        noticeDeadlineAt = !persistent ? 0L
                : sameChannel ? noticeDeadlineAt : shownAt + persistentNoticeTimeoutMillis(type);
        noticeType = type;
        noticeTitle = title;
        noticeValue = value;
        noticePersistent = persistent;
        noticeUsesQueueInterval = useQueueInterval;
        noticeShownAt = shownAt;
        noticeRevision++;
    }

    /** Advances queued ordinary notices after their configured display interval. */
    private static void advanceQueuedNotice(long now) {
        synchronized (NOTICE_QUEUE_LOCK) {
            if (noticeType == IslandNoticeType.NONE) return;

            if (noticePersistent) {
                if (isPersistentNoticeTimedOut(now)) {
                    boolean queueMode = !NOTICE_QUEUE.isEmpty();
                    if (queueMode) markQueuedNoticesUseQueueIntervalLocked();
                    if (noticeType == IslandNoticeType.REFRESHING) {
                        publishActiveNoticeLocked(IslandNoticeType.REFRESH_ERROR, "歌单同步超时",
                                "30 秒内未收到结果 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.GD_SOURCE_LOADING) {
                        publishActiveNoticeLocked(IslandNoticeType.GD_SOURCE_ERROR, "GD音乐台链接超时",
                                "45 秒内未获取到播放链接 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.PLAYLIST_TRACK_ADDING) {
                        publishActiveNoticeLocked(IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR, "加入歌单超时",
                                "20 秒内未收到确认 · 请稍后重试", false, queueMode, now);
                    } else if (noticeType == IslandNoticeType.TRANSCODING) {
                        transcoding = false;
                        transcodeProgress = 0.0;
                        publishActiveNoticeLocked(IslandNoticeType.PLAYBACK_ERROR, "转码无响应",
                                "30 秒内解码进度没有变化 · 已结束提示", false, queueMode, now);
                    } else if (!NOTICE_QUEUE.isEmpty()) {
                        // Unknown channel: hand the island back to the waiting notices instead of
                        // pinning a card that has no terminal message of its own.
                        QueuedNotice next = NOTICE_QUEUE.removeFirst();
                        publishActiveNoticeLocked(next.type, next.title, next.value, next.persistent,
                                next.useQueueInterval, now);
                    } else {
                        publishActiveNoticeLocked(IslandNoticeType.NONE, "", "", false, false, now);
                    }
                }
                return;
            }
            if (noticeShownAt <= 0L || now - noticeShownAt < noticeHoldMillis(noticeUsesQueueInterval)) return;

            if (!NOTICE_QUEUE.isEmpty()) {
                QueuedNotice next = NOTICE_QUEUE.removeFirst();
                publishActiveNoticeLocked(next.type, next.title, next.value, next.persistent,
                        next.useQueueInterval, now);
                return;
            }

            publishActiveNoticeLocked(IslandNoticeType.NONE, "", "", false, false, now);
        }
    }

    /** Persistent refresh and GD-link cards have independent timeout/completion channels. */
    private static boolean isPersistentNoticeTimedOut(long now) {
        if (noticeShownAt <= 0L) return false;
        if (noticeType == IslandNoticeType.TRANSCODING) {
            // A long track may decode for minutes, so only a stalled decoder ends the card.
            long advancedAt = transcodeProgressAdvancedAt;
            return advancedAt > 0L && now - advancedAt >= TRANSCODE_STALL_TIMEOUT_MILLIS;
        }
        return noticeDeadlineAt > 0L && now >= noticeDeadlineAt;
    }

    /** Every live status card needs a finite budget; an unlisted one falls back to the default. */
    private static long persistentNoticeTimeoutMillis(IslandNoticeType type) {
        if (type == IslandNoticeType.REFRESHING) return PLAYLIST_REFRESH_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.GD_SOURCE_LOADING) return GD_SOURCE_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.PLAYLIST_TRACK_ADDING) return PLAYLIST_TRACK_ADD_NOTICE_TIMEOUT_MILLIS;
        if (type == IslandNoticeType.TRANSCODING) return TRANSCODE_STALL_TIMEOUT_MILLIS;
        return DEFAULT_PERSISTENT_NOTICE_TIMEOUT_MILLIS;
    }
    private static final class QueuedNotice {
        private final IslandNoticeType type;
        private final String title;
        private final String value;
        private final boolean persistent;
        private boolean useQueueInterval;

        private QueuedNotice(IslandNoticeType type, String title, String value,
                             boolean persistent, boolean useQueueInterval) {
            this.type = type;
            this.title = title;
            this.value = value;
            this.persistent = persistent;
            this.useQueueInterval = useQueueInterval;
        }
    }

    private static String safeNoticeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Starts a fresh download snapshot. Publish the flag last for cross-thread visibility. */
    public static void beginDownload() {
        downloadProgress = 0.0;
        downloadSpeed = "0 b/s";
        downloadCompletedAt = 0L;
        downloadStartedAt = System.currentTimeMillis();
        downloading = true;
    }

    /** Updates progress and closes the active state after publishing the final 100% value. */
    public static void updateProgress(double progress) {
        downloadProgress = DynamicIslandMath.clamp01(progress);
        if (progress >= 1.0) {
            downloadCompletedAt = System.currentTimeMillis();
            downloading = false;
        }
    }

    public static void updateSpeed(String speed) {
        downloadSpeed = speed == null ? "" : speed;
    }

    /** Completes the current download even if the HTTP progress callback did not report 100%. */
    public static void finishDownload() {
        downloadProgress = 1.0;
        downloadCompletedAt = System.currentTimeMillis();
        downloading = false;
    }

    /** Hides an aborted/stale download without playing the success animation. */
    public static void cancelDownload() {
        downloadProgress = 0.0;
        downloadStartedAt = 0L;
        downloadCompletedAt = 0L;
        downloading = false;
    }

    private void renderInternal() {
        long now = System.currentTimeMillis();
        advanceQueuedNotice(now);
        double rawProgress = DynamicIslandMath.clamp01(downloadProgress);
        boolean activeDownload = downloading;
        double sourceTranscodeProgress = DynamicIslandMath.clamp01(transcodeProgress);
        long sourceStartedAt = downloadStartedAt;
        long sourceCompletedAt = downloadCompletedAt;
        long sourceNoticeShownAt = noticeShownAt;
        long sourceNoticeRevision = noticeRevision;
        IslandNoticeType sourceNoticeType = noticeType;
        String sourceNoticeTitle = noticeTitle;
        String sourceNoticeValue = noticeValue;
        boolean sourceNoticePersistent = noticePersistent;
        boolean sourceNoticeQueueMode = noticeUsesQueueInterval;

        // 场合门控：游戏外（主菜单/未进世界）与玩家列表打开时整块不画；播放器界面里只放通知，
        // 常驻状态栏让位。判定见 IslandVisibilityGate。
        IslandVisibilityGate.Scene scene = IslandVisibilityGate.capture();
        if (!IslandVisibilityGate.allowSurface(scene)) {
            // 直接把动画状态归零而不是原地冻结：下次允许显示时照常播一次入场，
            // 不会从上次被打断的半展开姿态突然跳出来。
            visibility = 0f;
            expansion = 0.0;
            contentAlpha = 0f;
            ambientBlend = 0f;
            animatedExpandedWidth = -1.0;
            animatedExpandedHeight = -1.0;
            previousAlwaysOn = false;
            ambientSnapshot = IslandAmbientStatus.EMPTY;
            ambientSampledAt = 0L;
            return;
        }

        boolean enabled = HudConfig.dynamicIslandEnabled;
        boolean alwaysOn = enabled && HudConfig.dynamicIslandAlwaysOn
                && IslandVisibilityGate.allowAmbient(scene);
        // 常驻内容按固定节拍采样：帧率和延迟每帧都在抖，直接跟帧读会让数字闪烁得没法看。
        if (alwaysOn) {
            if (ambientSampledAt <= 0L || now - ambientSampledAt >= IslandAmbientStatus.SAMPLE_INTERVAL_MILLIS) {
                ambientSnapshot = IslandAmbientStatus.capture(now);
                ambientSampledAt = now;
            }
        } else {
            ambientSnapshot = IslandAmbientStatus.EMPTY;
            ambientSampledAt = 0L;
        }
        // 已经稳定停在屏幕上的常驻岛必须锁住入场动画，否则每来一条通知都要重播一次缩放入场。
        boolean suppressEntry = IslandAmbientStatus.suppressEntry(alwaysOn, visibility);
        boolean alwaysOnJustEnabled = alwaysOn && !previousAlwaysOn;
        previousAlwaysOn = alwaysOn;
        if (alwaysOnJustEnabled && !suppressEntry) {
            // 只有"刚打开常驻"这一次照常播入场，之后交给 suppressEntry 拦住全部重播。
            shownAt = now;
            contentTransition = 0f;
        }

        if (!initialized) {
            initialized = true;
            previousDownloading = activeDownload;
            lastObservedProgress = rawProgress;
            animatedProgress = activeDownload ? rawProgress : 0.0;
            observedNoticeRevision = sourceNoticeRevision;
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
            animatedNoticeProgress = sourceNoticeType == IslandNoticeType.VOLUME
                    ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                    : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
            if (activeDownload) {
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            } else if (sourceCompletedAt > 0L && now - sourceCompletedAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds)) {
                completing = true;
                completeAt = sourceCompletedAt;
                animatedProgress = 1.0;
            } else if (sourceNoticeType != IslandNoticeType.NONE
                    && (sourceNoticePersistent || now - sourceNoticeShownAt < noticeHoldMillis(sourceNoticeQueueMode))) {
                shownAt = sourceNoticeShownAt;
            }
        }

        boolean noticeChanged = sourceNoticeRevision != observedNoticeRevision;
        if (noticeChanged) {
            boolean noticeContentChanged = sourceNoticeType != observedNoticeType
                    || !sourceNoticeTitle.equals(observedNoticeTitle)
                    || !sourceNoticeValue.equals(observedNoticeValue);
            // noticeShownAt is refreshed by each publisher to extend the hold window. shownAt
            // instead owns the surface-entry animation and stays stable while the island is visible.
            boolean islandAlreadyVisible = visibility > .035f || expansion > .05 || contentAlpha > .05f;
            observedNoticeRevision = sourceNoticeRevision;
            if (!activeDownload) {
                completing = false;
                successMorph = 0f;
                if (!islandAlreadyVisible && !suppressEntry) {
                    contentTransition = 0f;
                    noticeCopyTransition = 1f;
                    shownAt = sourceNoticeShownAt > 0L ? sourceNoticeShownAt : now;
                } else if (ambientBlend > .5f) {
                    // 从常驻状态栏切到通知：交叉淡入交给 ambientBlend 处理，这里不能再放出上一条
                    // 通知的残留文案，否则会看到一条早就过期的旧消息闪一下再被顶掉。
                    noticeCopyTransition = 1f;
                } else if (noticeContentChanged && noticeCopyTransition >= .84f) {
                    // The surface does not re-enter. Copy transitions are coalesced so a rapid
                    // key-repeat sequence resolves cleanly to its latest notification value.
                    outgoingNoticeTitle = observedNoticeTitle;
                    outgoingNoticeValue = observedNoticeValue;
                    noticeCopyTransition = 0f;
                }
            }
            observedNoticeType = sourceNoticeType;
            observedNoticeTitle = sourceNoticeTitle;
            observedNoticeValue = sourceNoticeValue;
        }

        boolean progressRestarted = activeDownload && rawProgress + .12 < lastObservedProgress;
        boolean justStarted = activeDownload && !previousDownloading;
        if (justStarted || progressRestarted) {
            completing = false;
            completeAt = 0L;
            successMorph = 0f;
            animatedProgress = rawProgress;
            if (!suppressEntry) {
                // 常驻态下一次新下载同样不重播入场：表面本来就在屏幕上，只需要换内容。
                contentTransition = 0f;
                shownAt = sourceStartedAt > 0L ? sourceStartedAt : now;
            }
        }

        // Fast downloads may begin and finish between two rendered frames. The final progress
        // edge keeps a short success state visible instead of silently disappearing.
        boolean completedBetweenFrames = !activeDownload
                && rawProgress >= .995
                && lastObservedProgress < .995;
        boolean newlyObservedCompletion = !activeDownload
                && sourceCompletedAt > completeAt
                && now - sourceCompletedAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds);
        if (!activeDownload && (previousDownloading || completedBetweenFrames || newlyObservedCompletion)) {
            if (rawProgress >= .965) {
                completing = true;
                completeAt = newlyObservedCompletion ? sourceCompletedAt : now;
                animatedProgress = Math.max(animatedProgress, rawProgress);
            } else {
                completing = false;
            }
        }

        if (!activeDownload && sourceCompletedAt == 0L && rawProgress < .965) {
            completing = false;
        }

        if (activeDownload) {
            completing = false;
            animatedProgress = Interpolations.interpolate(animatedProgress, rawProgress, .20f);
        } else if (completing) {
            animatedProgress = Interpolations.interpolate(animatedProgress, 1.0, .30f);
        }

        double noticeProgressTarget = sourceNoticeType == IslandNoticeType.VOLUME
                ? DynamicIslandMath.parseNoticePercent(sourceNoticeValue) / 100.0
                : sourceNoticeType == IslandNoticeType.TRANSCODING ? sourceTranscodeProgress : 0.0;
        if (sourceNoticeType == IslandNoticeType.VOLUME
                || sourceNoticeType == IslandNoticeType.TRANSCODING) {
            // Repeated transcode/volume updates converge to one continuous bar animation.
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, noticeProgressTarget, .26f);
        } else {
            animatedNoticeProgress = Interpolations.interpolate(animatedNoticeProgress, 0.0, .24f);
        }

        boolean holdingCompletion = completing && now - completeAt < DynamicIslandMath.completionHoldMillis(HudConfig.dynamicIslandCompletionHoldSeconds);
        boolean activeNotice = sourceNoticeType != IslandNoticeType.NONE
                && sourceNoticeShownAt > 0L
                && now >= sourceNoticeShownAt
                && (sourceNoticePersistent || now - sourceNoticeShownAt < noticeHoldMillis(sourceNoticeQueueMode));
        boolean noticeMode = !activeDownload && !holdingCompletion && activeNotice;
        // 常驻只是多了一条"没有任何事件也留在屏幕上"的显示理由；下载、完成停留、通知这三条
        // 原有理由与判定顺序都没有改动，所以通知排队的时序与关闭常驻时完全一致。
        boolean ambientMode = IslandAmbientStatus.isAmbient(enabled, alwaysOn, activeDownload,
                holdingCompletion, activeNotice);
        IslandAmbientStatus.Snapshot ambient = ambientSnapshot;
        boolean shouldShow = IslandAmbientStatus.shouldShow(enabled, alwaysOn, activeDownload,
                holdingCompletion, activeNotice);
        // 常驻但三项内容全关时收成紧凑胶囊，而不是展开一张空白卡片。
        double expansionGoal = IslandAmbientStatus.expansionTarget(shouldShow, ambientMode, !ambient.isEmpty());

        // 动画节奏来自配置：每个逼近系数乘以对应的速度倍率，再钳到 (0,.95] 以免越过目标值抖动。
        float expandRate = animationRate(shouldShow ? HudConfig.dynamicIslandExpandSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shouldShow ? .28f : .22f);
        float expansionRate = animationRate(shouldShow ? HudConfig.dynamicIslandExpandSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shouldShow ? .24f : .25f);
        float contentRate = animationRate(shouldShow ? HudConfig.dynamicIslandContentSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shouldShow ? .32f : .24f);
        float transitionRate = animationRate(shouldShow ? HudConfig.dynamicIslandContentSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shouldShow ? .38f : .26f);
        float copyRate = animationRate(shouldShow ? HudConfig.dynamicIslandContentSpeed
                : HudConfig.dynamicIslandCollapseSpeed, shouldShow ? .32f : .26f);
        float successRate = animationRate(HudConfig.dynamicIslandContentSpeed, .28f);

        visibility = Interpolations.interpolate(visibility, shouldShow ? 1f : 0f, expandRate);
        expansion = Interpolations.interpolate(expansion, expansionGoal, expansionRate);
        float contentTarget = shouldShow && expansion > .30 ? 1f : 0f;
        contentAlpha = Interpolations.interpolate(contentAlpha, contentTarget, contentRate);
        contentTransition = Interpolations.interpolate(contentTransition, shouldShow ? 1f : 0f, transitionRate);
        noticeCopyTransition = Interpolations.interpolate(noticeCopyTransition, 1f, copyRate);
        ambientBlend = Interpolations.interpolate(ambientBlend, ambientMode ? 1f : 0f, contentRate);
        successMorph = Interpolations.interpolate(successMorph,
                enabled && holdingCompletion ? 1f : 0f, successRate);

        double frameDelta = Math.min(4.0, Math.max(0.0, RenderSystem.getFrameDeltaTime()));
        spinnerRotation = (spinnerRotation + frameDelta * 7.2
                * DynamicIslandMath.clamp(HudConfig.dynamicIslandSpinnerSpeed, .30, 3.0)) % 360.0;

        previousDownloading = activeDownload;
        lastObservedProgress = rawProgress;

        if (visibility <= .012f && !shouldShow) {
            completing = false;
            successMorph = 0f;
            return;
        }

        final double easedExpansion = DynamicIslandMath.smoothStep(expansion);
        final float alpha = DynamicIslandMath.clamp01f(visibility);
        final float renderedContentTransition = DynamicIslandMath.clamp01f(contentTransition);
        final String speed = downloadSpeed;
        final boolean renderNotice = noticeMode;
        final IslandNoticeType renderedNoticeType = sourceNoticeType;
        final String renderedNoticeTitle = sourceNoticeTitle;
        final String renderedNoticeValue = sourceNoticeValue;
        final String renderedOutgoingNoticeTitle = outgoingNoticeTitle;
        final String renderedOutgoingNoticeValue = outgoingNoticeValue;
        final float renderedNoticeCopyTransition = DynamicIslandMath.clamp01f(noticeCopyTransition);
        final double renderedNoticeProgress = DynamicIslandMath.clamp01(animatedNoticeProgress);
        final IslandAmbientStatus.Snapshot renderedAmbient = ambient;
        final float renderedAmbientBlend = DynamicIslandMath.clamp01f(ambientBlend);
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                drawIsland(easedExpansion, alpha, renderedContentTransition, animatedProgress, successMorph, speed,
                        System.currentTimeMillis(), shownAt, false, renderNotice,
                        renderedNoticeType, renderedNoticeTitle, renderedNoticeValue,
                        renderedOutgoingNoticeTitle, renderedOutgoingNoticeValue,
                        renderedNoticeCopyTransition, renderedNoticeProgress, sourceNoticePersistent,
                        renderedAmbient, renderedAmbientBlend);
            }
        });
    }

    private void renderPreviewInternal() {
        if (RenderSystem.getWidth() <= 0 || RenderSystem.getHeight() <= 0) return;
        renderIsolated(new Runnable() {
            @Override
            public void run() {
                float alpha = HudConfig.dynamicIslandEnabled ? .96f : .46f;
                // 打开常驻时预览直接展示常驻状态栏，让这个开关在 HUD 编辑器里可见即所得。
                IslandAmbientStatus.Snapshot ambient = HudConfig.dynamicIslandEnabled
                        && HudConfig.dynamicIslandAlwaysOn
                        ? IslandAmbientStatus.capture(System.currentTimeMillis())
                        : IslandAmbientStatus.EMPTY;
                drawIsland(1.0, alpha, 1f, .64, 0f,
                        HudConfig.dynamicIslandEnabled ? "1.8 MB/s" : "灵动岛已关闭",
                        System.currentTimeMillis(), 0L, true, false,
                        IslandNoticeType.NONE, "", "", "", "", 1f, 0.0, false,
                        ambient, ambient.isEmpty() ? 0f : 1f);
            }
        });
    }

    private void renderIsolated(Runnable renderer) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        api.getGLStateManager().pushMatrix();
        try {
            // The island is a global overlay. It must not inherit a scissor/stencil region left by
            // the player UI or another mod, otherwise the left-side status icon can be cut in half.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            renderer.run();
        } finally {
            api.getGLStateManager().popMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawIsland(double expansionValue, float alpha, float contentFade,
                            double progress, float success, String speedValue, long now,
                            long animationStart, boolean preview, boolean noticeMode,
                            IslandNoticeType activeNoticeType, String activeNoticeTitle,
                            String activeNoticeValue, String outgoingNoticeTitle,
                            String outgoingNoticeValue, float noticeCopyBlend,
                            double noticeProgress, boolean activeNoticePersistent,
                            IslandAmbientStatus.Snapshot ambient, float ambientBlendValue) {
        double configuredScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandScale, .60, 1.35);
        double screenWidth = RenderSystem.getWidth();
        DynamicIslandStyle style = getStyle();
        IslandLayout layout = createIslandLayout(style, noticeMode, activeNoticeType, activeNoticeTitle,
                activeNoticeValue, speedValue, preview, configuredScale, screenWidth);
        float ambientMix = DynamicIslandMath.clamp01f(ambientBlendValue);
        boolean hasAmbient = ambient != null && !ambient.isEmpty();
        if (!hasAmbient) ambientMix = 0f;

        // 常驻文字的排版比例：内容超出可用宽度时先缩字号，永远不截断实时数值。
        double ambientScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandTextScale,
                MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        double ambientTargetWidth = layout.targetWidth;
        double ambientTargetHeight = layout.targetHeight;
        if (ambientMix > .002f) {
            double budget = DynamicIslandLayoutCalculator.ambientContentBudget(style, configuredScale, screenWidth);
            double rawRowWidth = measureAmbientRow(ambient);
            if (rawRowWidth * ambientScale > budget) {
                ambientScale = Math.max(MIN_AUTO_TEXT_SCALE, budget / Math.max(1.0, rawRowWidth));
            }
            DynamicIslandLayoutCalculator.LayoutData ambientLayout =
                    DynamicIslandLayoutCalculator.calculateAmbient(style, rawRowWidth * ambientScale,
                            configuredScale, screenWidth, COMPACT_WIDTH);
            ambientTargetWidth = ambientLayout.targetWidth;
            ambientTargetHeight = ambientLayout.targetHeight;
        }
        // 常驻与通知的目标尺寸按混合度插值，切换时表面是一次连续变形，不会先塌再弹。
        double blendedTargetWidth = DynamicIslandMath.lerp(layout.targetWidth, ambientTargetWidth, ambientMix);
        double blendedTargetHeight = DynamicIslandMath.lerp(layout.targetHeight, ambientTargetHeight, ambientMix);

        double compactWidth = style == DynamicIslandStyle.COMPACT ? 46.0
                : (style == DynamicIslandStyle.MUSIC_FOCUS ? 48.0 : COMPACT_WIDTH);
        double compactHeight = style == DynamicIslandStyle.COMPACT ? 14.0 : COMPACT_HEIGHT;
        double expandedWidth = preview ? blendedTargetWidth
                : animateExpandedDimension(animatedExpandedWidth, blendedTargetWidth, true);
        double expandedHeight = preview ? blendedTargetHeight
                : animateExpandedDimension(animatedExpandedHeight, blendedTargetHeight, false);
        if (!preview) {
            animatedExpandedWidth = expandedWidth;
            animatedExpandedHeight = expandedHeight;
        }

        double width = DynamicIslandMath.lerp(compactWidth, expandedWidth, expansionValue);
        double height = DynamicIslandMath.lerp(compactHeight, expandedHeight, expansionValue);
        double centerX = screenWidth * .5;
        double visibilityEase = DynamicIslandMath.smoothStep(alpha);
        double y = TOP_MARGIN - (height + 11.0) * (1.0 - visibilityEase);

        double entryScale = 1.0;
        if (!preview && animationStart > 0L) {
            double entranceDuration = DynamicIslandMath.clamp(HudConfig.dynamicIslandEntranceDuration,
                    160.0, 1200.0);
            double entryProgress = DynamicIslandMath.clamp01((now - animationStart) / entranceDuration);
            double cubicOut = 1.0 - Math.pow(1.0 - entryProgress, 3.0);
            double restrainedOvershoot = Math.sin(entryProgress * Math.PI)
                    * (1.0 - entryProgress) * .025
                    * DynamicIslandMath.clamp(HudConfig.dynamicIslandOvershoot, 0.0, 2.0);
            entryScale = .965 + .035 * cubicOut + restrainedOvershoot;
        }

        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, TOP_MARGIN, configuredScale);
        scaleAtPos(centerX, y + height * .5, entryScale);

        double x = centerX - width * .5;
        boolean systemCard = style == DynamicIslandStyle.SYSTEM_CARD;
        boolean musicFocus = style == DynamicIslandStyle.MUSIC_FOCUS;
        boolean liquidGlass = style == DynamicIslandStyle.LIQUID_GLASS;
        double radius = systemCard ? Math.min(11.0, height * .25)
                : (musicFocus ? Math.min(14.0, height * .31)
                : (liquidGlass ? Math.min(16.0, height * .40)
                : (style == DynamicIslandStyle.CARD ? Math.min(12.0, height * .30) : height * .5)));
        int accentColor = NCMTheme.getAccentColor();
        int iconAccentColor = DynamicIslandMath.brightenAccent(accentColor, .30f);

        // Keep the shadow on the island's own rounded silhouette. The prior expanded,
        // left-shifted rectangle looked like a separate dark background canvas.
        float shadowAlpha = alpha * (style == DynamicIslandStyle.GLASS ? .13f
                : (liquidGlass ? .15f : (musicFocus ? .23f : .19f)));
        roundedRect(x + 1.0, y + 1.8, width, height, radius,
                hexColor(0f, 0f, 0f, shadowAlpha));
        roundedRect(x + 2.0, y + 3.0, width, height, radius,
                hexColor(0f, 0f, 0f, shadowAlpha * .42f));
        if (liquidGlass) {
            renderLiquidGlassSurface(x, y, width, height, radius, alpha, accentColor, now);
        } else if (style == DynamicIslandStyle.GLASS) {
            roundedRect(x, y, width, height, radius, RenderSystem.reAlpha(accentColor, alpha * .20f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height * .46),
                    Math.max(1.0, radius - 1.0), hexColor(1f, 1f, 1f, alpha * .075f));
            roundedOutline(x, y, width, height, radius, .75,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 62f)));
        } else if (style == DynamicIslandStyle.CARD) {
            roundedRect(x, y, width, height, radius, hexColor(.028f, .034f, .046f, alpha * .985f));
            roundedRect(x, y + radius, 2.2, Math.max(1.0, height - radius * 2.0), 1.1,
                    RenderSystem.reAlpha(accentColor, alpha * .90f));
            roundedOutline(x, y, width, height, radius, .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 30f)));
        } else if (musicFocus) {
            // Music-focused card: the surface expands first, then the animated artwork and copy settle in.
            roundedRect(x, y, width, height, radius, hexColor(.010f, .014f, .024f, alpha * .99f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                    Math.max(1.0, radius - 1.0), hexColor(.050f, .064f, .098f, alpha * .46f));
            roundedRect(x + 1.2, y + 1.2, Math.max(1.0, width - 2.4), Math.max(1.0, height * .36),
                    Math.max(1.0, radius - 1.2), RenderSystem.reAlpha(accentColor, alpha * .10f));
            roundedOutline(x, y, width, height, radius, .80,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 54f)));
            roundedRect(x + 8.0, y + height - 5.0, Math.max(1.0, width - 16.0), 1.0, .5,
                    RenderSystem.reAlpha(accentColor, alpha * .22f));
        } else if (systemCard) {
            // Dedicated content-driven system notification card inspired by the supplied reference project.
            roundedRect(x, y, width, height, radius, hexColor(.025f, .031f, .043f, alpha * .99f));
            roundedRect(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                    Math.max(1.0, radius - 1.0), hexColor(.075f, .088f, .118f, alpha * .32f));
            roundedOutline(x, y, width, height, radius, .80,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 46f)));
            double tileSize = Math.min(24.0, Math.max(16.0, height - 16.0));
            roundedRect(x + 8.0, y + (height - tileSize) * .5, tileSize, tileSize,
                    Math.min(7.0, tileSize * .30), RenderSystem.reAlpha(accentColor, alpha * .26f));
            roundedOutline(x + 8.0, y + (height - tileSize) * .5, tileSize, tileSize,
                    Math.min(7.0, tileSize * .30), .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 36f)));
        } else {
            roundedRect(x, y, width, height, radius, hexColor(.012f, .014f, .020f, alpha * .985f));
            roundedOutline(x, y, width, height, radius, .65,
                    new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 20f)));
        }
        if (!systemCard && !musicFocus && !liquidGlass) {
            roundedRect(x + radius * .70, y + .8, Math.max(2.0, width - radius * 1.40), .75, .38,
                    hexColor(1f, 1f, 1f, alpha * (style == DynamicIslandStyle.GLASS ? .13f : .055f)));
        }

        // Keep the icon safely inside the rounded silhouette and reserve a real gap before text.
        // Previously the 1.16x icon scale could push its outer edge into a stale clip or the pill's
        // curved shoulder, making the left edge look incomplete.
        // SYSTEM_CARD reserves a 24px tile at x + 8, whose exact center is x + 20.
        // Keep the glyph center tied to that tile rather than its wider text gutter.
        double iconInset = systemCard ? 20.0 : (musicFocus ? 26.0
                : (liquidGlass ? 24.0 : (style == DynamicIslandStyle.CARD ? 25.0 : 23.0)));
        double iconExpandedX = centerX - expandedWidth * .5 + iconInset;
        double iconX = DynamicIslandMath.lerp(centerX, iconExpandedX, expansionValue);
        double iconY = y + height * .5;
        double pulse = .5 + .5 * Math.sin(now / 260.0);
        float iconAlpha = alpha * (.87f + (float) pulse * .10f);

        api.getGLStateManager().pushMatrix();
        if (musicFocus) {
            renderMusicFocusArtwork(iconX, iconY, height, iconAlpha, accentColor, iconAccentColor, now,
                    preview, noticeMode, activeNoticeType, success, ambientMix, ambient);
        } else {
            double iconPlateBase = systemCard ? 17.0 : (liquidGlass ? 19.6 : 18.4);
            // 折叠态的岛只有 16px 高，而托盘固定 18.4px：它会顶出胶囊的圆肩，看起来正像"图标加载不全"。
            // 让托盘随岛高收缩，图标就始终整枚落在轮廓内，展开/收起全过程都不会被切边。
            double iconFit = DynamicIslandMath.clamp((height - 3.6) / iconPlateBase, .40, 1.0);
            // 再按展开度淡入：紧凑态只留一枚淡淡的圆点，不会在极小的胶囊里塞满高对比图形。
            double iconReveal = DynamicIslandMath.smoothStep(
                    DynamicIslandMath.clamp01((expansionValue - .10) / .68));
            double iconPlate = iconPlateBase * iconFit;
            float plateAlpha = alpha * (.82f + (float) pulse * .12f) * (.34f + .66f * (float) iconReveal);
            // 字形不再跟着呼吸忽明忽暗——那正是"图标动效看不清"的主因，常亮才读得出形状。
            float glyphAlpha = alpha * .98f * (.40f + .60f * (float) iconReveal);
            scaleAtPos(iconX, iconY, (systemCard ? 1.0 : 1.06) * iconFit);
            // 深色底衬：液态玻璃这类亮表面上，只靠一层薄强调色会让字形糊进背景里。
            roundedRect(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                    iconPlate * .5, hexColor(.020f, .026f, .038f, plateAlpha * .60f));
            roundedRect(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                    iconPlate * .5, RenderSystem.reAlpha(accentColor, plateAlpha * (liquidGlass ? .20f : .26f)));
            if (liquidGlass) {
                // 磨砂托盘：上亮下暗的一层反光，让图标像压在玻璃里而不是贴在表面。
                roundedRectGradientVertical(iconX - iconPlate * .5, iconY - iconPlate * .5,
                        iconPlate, iconPlate, iconPlate * .5,
                        new Color(255, 255, 255, DynamicIslandMath.clamp255(plateAlpha * 34f)),
                        new Color(255, 255, 255, 0));
            }
            roundedOutline(iconX - iconPlate * .5, iconY - iconPlate * .5, iconPlate, iconPlate,
                    iconPlate * .5, .95, new Color(255, 255, 255, DynamicIslandMath.clamp255(plateAlpha * 92f)));
            if (ambientMix < .995f) {
                float eventGlyphAlpha = glyphAlpha * (1f - ambientMix);
                if (noticeMode) {
                    renderNoticeIcon(activeNoticeType, iconX, iconY, eventGlyphAlpha, iconAccentColor, now);
                } else {
                    renderSpinner(iconX, iconY, eventGlyphAlpha * (1f - success), iconAccentColor, now, preview);
                    renderSuccess(iconX, iconY, alpha * success * (1f - ambientMix));
                }
            }
            if (ambientMix > .005f) {
                renderAmbientIcon(ambient, iconX, iconY, iconPlate, glyphAlpha * ambientMix,
                        iconAccentColor, now);
            }
        }
        api.getGLStateManager().popMatrix();

        // 常驻内容出现时通知/下载文案整体让位：两者共用同一块排版区域，靠交叉淡入切换而不是叠字。
        float eventFade = 1f - ambientMix;
        float textAlpha = alpha * contentAlpha * (float) expansionValue
                * DynamicIslandMath.clamp01f(contentFade) * eventFade;
        if (preview) textAlpha = alpha * (float) expansionValue * eventFade;
        boolean staticVolumeCopy = noticeMode && activeNoticeType == IslandNoticeType.VOLUME;
        boolean transcodeCopy = noticeMode && activeNoticeType == IslandNoticeType.TRANSCODING;
        // Volume is frequently updated from key-repeat. Its labels deliberately skip both the
        // island-content entry and copy-transition animations, while the bar still interpolates.
        float renderedTextAlpha = staticVolumeCopy
                ? (expansionValue >= .74 ? alpha * eventFade : 0f) : textAlpha;
        if (renderedTextAlpha > .01f) {
            double textLeft = x + (systemCard ? 44.0 : (musicFocus ? 55.0
                    : (liquidGlass ? 39.0 : (style == DynamicIslandStyle.CARD ? 39.0 : 37.0))));
            double textRight = x + width - (systemCard ? 12.0 : (musicFocus ? 12.0 : 9.0));
            double textCenter = (textLeft + textRight) * .5;
            double copySlide = (1.0 - DynamicIslandMath.clamp01(contentFade)) * CONTENT_CHANGE_SLIDE;
            double baseTitleY = y + (systemCard ? 9.0 : (musicFocus ? 10.0 : 5.4));
            double baseValueY = y + (systemCard ? 23.0 : (musicFocus ? 25.0 : 16.6));
            double titleY = baseTitleY + copySlide;
            double valueY = baseValueY + copySlide;

            if (noticeMode) {
                // 可用宽度必须严格跟随本帧动画出来的 width：这里原来有一个 28 像素的下限，
                // 于是灵动岛在展开/收起过程中比 28 像素还窄时，文案照 28 像素排版就会溢出胶囊。
                double availableWidth = Math.max(0.0, textRight - textLeft);
                if (staticVolumeCopy) {
                    double staticTitleY = baseTitleY;
                    double staticValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, staticTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter, staticValueY,
                            availableWidth, layout.textScale, hexColor(1f, 1f, 1f, renderedTextAlpha));
                } else if (transcodeCopy) {
                    double transcodeTitleY = baseTitleY;
                    double transcodeValueY = baseValueY;
                    drawCenteredIslandText(FontManager.pf12, layout.title, textCenter, transcodeTitleY,
                            availableWidth, layout.textScale,
                            hexColor(.62f, .67f, .76f, renderedTextAlpha * .96f));
                    drawCenteredIslandText(FontManager.pf12, layout.value, textCenter, transcodeValueY,
                            availableWidth, layout.textScale,
                            hexColor(1f, 1f, 1f, renderedTextAlpha));
                    String percent = Math.max(0, Math.min(100,
                            (int) Math.round(DynamicIslandMath.clamp01(noticeProgress) * 100.0))) + "%";
                    drawCenteredIslandText(FontManager.pf12bold, percent, textRight - 10.0, transcodeValueY,
                            20.0, layout.textScale,
                            hexColor(.58f, .90f, 1f, renderedTextAlpha));                } else {
                    double copyBlend = DynamicIslandMath.clamp01(noticeCopyBlend);
                    double noticeSlide = CONTENT_CHANGE_SLIDE * .62;

                    // A notification refresh keeps the island itself stable. The previous copy exits
                    // upward while the latest value enters from below; rapid updates are coalesced by
                    // renderInternal rather than constantly restarting this transition.
                    if (copyBlend < .995) {
                        float outgoingAlpha = textAlpha * (float) (1.0 - copyBlend);
                        if (outgoingAlpha > .01f) {
                            drawCenteredIslandText(FontManager.pf12,
                                    safeNoticeValue(outgoingNoticeTitle, layout.title), textCenter,
                                    titleY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(.62f, .67f, .76f, outgoingAlpha * .96f));
                            drawCenteredIslandText(FontManager.pf14bold,
                                    safeNoticeValue(outgoingNoticeValue, layout.value), textCenter,
                                    valueY - noticeSlide * copyBlend, availableWidth, layout.textScale,
                                    hexColor(1f, 1f, 1f, outgoingAlpha));
                        }
                    }

                    float incomingAlpha = textAlpha * (float) copyBlend;
                    if (incomingAlpha > .01f) {
                        drawCenteredIslandText(FontManager.pf12, layout.title, textCenter,
                                titleY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                hexColor(.62f, .67f, .76f, incomingAlpha * .96f));
                        drawCenteredIslandText(FontManager.pf14bold, layout.value, textCenter,
                                valueY + noticeSlide * (1.0 - copyBlend), availableWidth, layout.textScale,
                                hexColor(1f, 1f, 1f, incomingAlpha));
                    }
                }

                if (staticVolumeCopy || transcodeCopy) {
                    double progressX = textLeft;
                    double progressWidth = Math.max(28.0, textRight - textLeft);
                    double progressHeight = systemCard ? 3.2 : (musicFocus ? 2.6 : 2.8);
                    double progressY = y + height - (systemCard ? 8.2 : (musicFocus ? 7.0 : 6.0));
                    double progressRadius = progressHeight * .5;
                    roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                            hexColor(1f, 1f, 1f, renderedTextAlpha * .12f));
                    double fillWidth = progressWidth * DynamicIslandMath.clamp01(noticeProgress);
                    if (fillWidth > .10) {
                        roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                                Math.min(progressRadius, fillWidth * .5),
                                DynamicIslandMath.colorWithAlpha(accentColor, renderedTextAlpha * .98f),
                                new Color(103, 216, 255, DynamicIslandMath.clamp255(renderedTextAlpha * 255f)));
                    }
                }
            } else {
                float loadingAlpha = textAlpha * (1f - success);
                float completeAlpha = textAlpha * success;
                String status = success > .52f ? "完成" : Math.max(0, Math.min(100,
                        (int) Math.round(DynamicIslandMath.clamp01(progress) * 100.0))) + "%";
                double totalTextWidth = Math.max(0.0, textRight - textLeft);
                // 百分比槽与正文同样不能有固定下限：动画中途的窄胶囊里，写死的 25/28 像素
                // 会让"加载中 / 100%"整块横着穿出灵动岛边缘。
                double statusWidth = Math.min(totalTextWidth, Math.max(25.0,
                        FontManager.pf12bold.getStringWidthD(status) * layout.textScale + 10.0));
                double statusX = textRight - statusWidth;
                double bodyRight = statusX - 5.0;
                double bodyCenter = (textLeft + bodyRight) * .5;
                double bodyWidth = Math.max(0.0, bodyRight - textLeft);

                drawCenteredIslandText(FontManager.pf14bold, layout.title, bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, loadingAlpha));
                drawCenteredIslandText(FontManager.pf14bold, "加载完成", bodyCenter, titleY,
                        bodyWidth, layout.textScale, hexColor(1f, 1f, 1f, completeAlpha));
                drawCenteredIslandText(FontManager.pf12, layout.value, bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.65f, .69f, .78f, loadingAlpha * .94f));
                drawCenteredIslandText(FontManager.pf12, "可以开始播放", bodyCenter, valueY,
                        bodyWidth, layout.textScale, hexColor(.59f, .90f, .67f, completeAlpha));

                int statusColor = success > .52f
                        ? hexColor(.58f, 1f, .68f, textAlpha)
                        : hexColor(.82f, .85f, .91f, textAlpha);
                roundedRect(statusX, y + (systemCard ? 8.5 : (musicFocus ? 11.0 : 4.2)), statusWidth, 12.5, 6.25,
                        success > .52f ? hexColor(.18f, .55f, .32f, textAlpha * .34f)
                                : hexColor(1f, 1f, 1f, textAlpha * .10f));
                drawCenteredIslandText(FontManager.pf12bold, status, statusX + statusWidth * .5,
                        y + (systemCard ? 10.4 : (musicFocus ? 12.9 : 6.1)), statusWidth - 4.0, layout.textScale, statusColor);

                double progressX = x + (systemCard ? 8.0 : (musicFocus ? 10.0 : 9.0));
                double progressY = y + height - (systemCard ? 7.0 : (musicFocus ? 6.8 : 3.7));
                double progressWidth = width - (systemCard ? 16.0 : (musicFocus ? 20.0 : 18.0));
                double progressHeight = Math.min(systemCard ? 3.8 : (musicFocus ? 3.0 : height * .22),
                        DynamicIslandMath.clamp(HudConfig.dynamicIslandProgressHeight, .75, 4.0));
                double progressRadius = progressHeight * .5;
                roundedRect(progressX, progressY, progressWidth, progressHeight, progressRadius,
                        hexColor(1f, 1f, 1f, textAlpha * .10f));
                double fillWidth = progressWidth * DynamicIslandMath.clamp01(progress);
                if (fillWidth > .10) {
                    Color startColor = DynamicIslandMath.colorWithAlpha(accentColor, textAlpha * .94f);
                    Color endColor = new Color(103, 216, 255, DynamicIslandMath.clamp255(textAlpha * 255f));
                    roundedRectGradientHorizontal(progressX, progressY, fillWidth, progressHeight,
                            Math.min(progressRadius, fillWidth * .5), startColor, endColor);
                }
            }
        }

        if (ambientMix > .005f) {
            float ambientAlpha = preview
                    ? alpha * (float) expansionValue * ambientMix
                    : alpha * contentAlpha * (float) expansionValue * ambientMix;
            if (ambientAlpha > .01f) {
                drawAmbientRow(ambient, x, y, width, height, style, ambientAlpha, ambientScale, accentColor);
            }
        }
        api.getGLStateManager().popMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private IslandLayout createIslandLayout(DynamicIslandStyle style, boolean noticeMode,
                                            IslandNoticeType noticeType, String noticeTitleValue,
                                            String noticeBodyValue, String speedValue, boolean preview,
                                            double configuredScale, double screenWidth) {
        DynamicIslandLayoutCalculator.LayoutData layout = DynamicIslandLayoutCalculator.calculate(
                style, noticeMode, noticeMode && noticeType == IslandNoticeType.VOLUME,
                noticeTitleValue, noticeBodyValue, speedValue, preview, configuredScale, screenWidth,
                COMPACT_WIDTH, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        return new IslandLayout(layout.title, layout.value, layout.textScale,
                layout.targetWidth, layout.targetHeight);
    }
    private double animateExpandedDimension(double current, double target, boolean width) {
        if (current <= .0) return target;
        // Expansion reacts a little faster than contraction; this reads as deliberate rather than
        // as a layout jump when a notification changes from short to long or back again.
        float speed = target > current ? (width ? .42f : .38f) : (width ? .24f : .22f);
        return Interpolations.interpolate(current, target, speed);
    }

    private void drawCenteredIslandText(CFontRenderer font, String text, double centerX, double y,
                                         double maxWidth, double textScale, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        // 宽度不足就整段不画。原来这里对可用宽度取 max(18, ...)：收起动画的最后几帧里胶囊只有
        // 十几像素宽，按 18 像素裁剪出来的文字必然越过圆角边缘，看起来就是"文字溢出"。
        if (maxWidth <= 1.0) return;
        double safeScale = DynamicIslandMath.clamp(textScale, MIN_AUTO_TEXT_SCALE, MAX_TEXT_SCALE);
        String trimmed = trimIslandText(font, text, maxWidth / safeScale);
        if (trimmed.isEmpty()) return;
        double textWidth = font.getStringWidthD(trimmed) * safeScale;
        font.drawString(trimmed, centerX - textWidth * .5, y, safeScale, color);
    }

    private String trimIslandText(CFontRenderer font, String text, double maxRawWidth) {
        if (maxRawWidth <= 0.0) return "";
        if (font.getStringWidthD(text) <= maxRawWidth) return text;
        final String ellipsis = "...";
        if (font.getStringWidthD(ellipsis) > maxRawWidth) return "";
        int end = text.length();
        while (end > 0) {
            String candidate = text.substring(0, end) + ellipsis;
            if (font.getStringWidthD(candidate) <= maxRawWidth) return candidate;
            end--;
        }
        return ellipsis;
    }

    private static final class IslandLayout {
        final String title;
        final String value;
        final double textScale;
        final double targetWidth;
        final double targetHeight;

        IslandLayout(String title, String value, double textScale, double targetWidth, double targetHeight) {
            this.title = title;
            this.value = value;
            this.textScale = textScale;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }
    }

    /**
     * 常驻状态栏的排版常量。数值槽按等位数模板预留宽度（见 {@link IslandAmbientStatus.Chip#widthTemplate}），
     * 所以帧率从 99 跳到 100、延迟从 8ms 跳到 120ms 时，灵动岛的宽度都不会跟着抖动。
     */
    private static final double AMBIENT_ICON_WIDTH = 9.0;
    private static final double AMBIENT_ICON_GAP = 3.2;
    private static final double AMBIENT_UNIT_GAP = 2.2;
    private static final double AMBIENT_CHIP_SPACING = 11.0;
    /** 常驻行右侧保留的安全边距，和文本区右边距同源，保证行尾不会压到圆角上。 */
    private static final double AMBIENT_RIGHT_PADDING = 9.0;

    /** 未缩放的常驻内容总宽。缩放由调用方乘上去，便于先量宽再决定字号。 */
    private double measureAmbientRow(IslandAmbientStatus.Snapshot ambient) {
        if (ambient == null || ambient.isEmpty()) return 0.0;
        double total = 0.0;
        for (int index = 0; index < ambient.chips.length; index++) {
            if (index > 0) total += AMBIENT_CHIP_SPACING;
            total += measureAmbientChip(ambient.chips[index]);
        }
        return total;
    }

    private double measureAmbientChip(IslandAmbientStatus.Chip chip) {
        // 正在播放页的条目不画小图标（左侧封面已经是它的图标），因此也不预留图标位，
        // 否则歌名会整体右移一段，看起来像没对齐。
        double width = (chip.showIcon ? AMBIENT_ICON_WIDTH + AMBIENT_ICON_GAP : 0.0)
                + FontManager.pf14bold.getStringWidthD(chip.widthTemplate);
        if (!chip.unit.isEmpty()) {
            width += AMBIENT_UNIT_GAP + FontManager.pf12.getStringWidthD(chip.unit);
        }
        return width;
    }

    /** 单行常驻状态栏：整体在文本区里居中，但绝不越过左侧图标托盘。 */
    private void drawAmbientRow(IslandAmbientStatus.Snapshot ambient, double x, double y,
                                double width, double height, DynamicIslandStyle style,
                                float alpha, double scale, int accentColor) {
        if (ambient == null || ambient.isEmpty()) return;
        double centerY = y + height * .5;
        double gutter = style == DynamicIslandStyle.SYSTEM_CARD ? 40.0
                : (style == DynamicIslandStyle.MUSIC_FOCUS ? 50.0 : 34.0);
        // 常驻行同样必须跟着本帧的 width 收缩。展开/收起动画中途 width 远小于目标宽度，
        // 而行宽是按目标宽度算好的，不做这一步就会整行横着穿出胶囊右侧。
        double available = width - gutter - AMBIENT_RIGHT_PADDING;
        double rawRowWidth = measureAmbientRow(ambient);
        if (rawRowWidth <= 0.0 || available <= 1.0) return;
        scale = Math.min(scale, available / rawRowWidth);
        if (scale <= .04) return;
        double rowWidth = rawRowWidth * scale;
        double left = Math.max(x + gutter, x + width * .5 - rowWidth * .5);
        double cursor = left;
        for (int index = 0; index < ambient.chips.length; index++) {
            if (index > 0) {
                roundedRect(cursor + (AMBIENT_CHIP_SPACING * .5 - .5) * scale, centerY - 4.4 * scale,
                        1.0 * scale, 8.8 * scale, .5 * scale, hexColor(1f, 1f, 1f, alpha * .13f));
                cursor += AMBIENT_CHIP_SPACING * scale;
            }
            cursor += drawAmbientChip(ambient.chips[index], cursor, centerY, alpha, scale, accentColor);
        }
    }

    /** 画一个条目并返回它实际占掉的宽度，让调用方不必重复一遍宽度公式。 */
    private double drawAmbientChip(IslandAmbientStatus.Chip chip, double left, double centerY,
                                   float alpha, double scale, int accentColor) {
        if (chip.showIcon) {
            drawAmbientChipIcon(chip.kind, left + AMBIENT_ICON_WIDTH * .5 * scale, centerY,
                    alpha, accentColor, scale);
        }
        double cursor = left + (chip.showIcon ? (AMBIENT_ICON_WIDTH + AMBIENT_ICON_GAP) * scale : 0.0);
        double slot = FontManager.pf14bold.getStringWidthD(chip.widthTemplate) * scale;
        double valueWidth = FontManager.pf14bold.getStringWidthD(chip.value) * scale;
        // 数值在预留槽内居中：位数变化只影响槽内位置，不影响后面条目的起点。
        FontManager.pf14bold.drawString(chip.value, cursor + (slot - valueWidth) * .5,
                centerY - FontManager.pf14bold.getFontHeight() * scale * .5, scale,
                ambientHealthColor(chip.health, alpha));
        cursor += slot;
        if (!chip.unit.isEmpty()) {
            cursor += AMBIENT_UNIT_GAP * scale;
            FontManager.pf12.drawString(chip.unit, cursor,
                    centerY - FontManager.pf12.getFontHeight() * scale * .5, scale,
                    hexColor(.60f, .66f, .76f, alpha * .92f));
            cursor += FontManager.pf12.getStringWidthD(chip.unit) * scale;
        }
        return cursor - left;
    }

    /** 健康度配色。中性一律接近纯白，避免让"正常"看起来像出错。 */
    private int ambientHealthColor(IslandAmbientStatus.Health health, float alpha) {
        if (health == IslandAmbientStatus.Health.GOOD) return hexColor(.60f, 1f, .74f, alpha);
        if (health == IslandAmbientStatus.Health.WARN) return hexColor(1f, .83f, .47f, alpha);
        if (health == IslandAmbientStatus.Health.BAD) return hexColor(1f, .53f, .55f, alpha);
        return hexColor(.96f, .97f, 1f, alpha);
    }

    /**
     * 条目小图标改用 island-status-icons 字体：帧率=心跳(U+E904)、延迟=链条(U+E903)、时间=时钟(U+E901)。
     *
     * <p>原来这三枚是用圆角矩形手工拼的。在 9 像素见方、还要再乘常驻缩放的尺寸下，手绘笔画的
     * 粗细和间距都落不到整数像素上，看起来发糊也认不出形状；字形是按字号栅格化过的，同样尺寸下
     * 边缘干净得多。</p>
     */
    private void drawAmbientChipIcon(IslandAmbientStatus.Kind kind, double centerX, double centerY,
                                     float alpha, int accentColor, double scale) {
        if (alpha <= .01f) return;
        String glyph = kind == IslandAmbientStatus.Kind.FPS ? IslandStatusIcons.PULSE
                : (kind == IslandAmbientStatus.Kind.PING ? IslandStatusIcons.LINK
                : IslandStatusIcons.CLOCK);
        int color = kind == IslandAmbientStatus.Kind.CLOCK
                ? hexColor(.86f, .90f, .98f, alpha * .94f)
                : RenderSystem.reAlpha(accentColor, alpha * .98f);
        drawAmbientGlyph(glyph, centerX, centerY, AMBIENT_ICON_WIDTH * scale, color);
    }

    /** 把一枚图标字形等比缩放到目标边长并居中绘制。 */
    private void drawAmbientGlyph(String glyph, double centerX, double centerY, double targetSize,
                                  int color) {
        CFontRenderer font = FontManager.islandIcon12;
        if (font == null || glyph == null || glyph.isEmpty() || targetSize <= .5) return;
        double rawWidth = font.getStringWidthD(glyph);
        double rawHeight = Math.max(1.0, font.getFontHeight());
        double glyphScale = targetSize / Math.max(rawWidth, rawHeight);
        if (glyphScale <= .02) return;
        font.drawString(glyph, centerX - rawWidth * glyphScale * .5,
                centerY - rawHeight * glyphScale * .5, glyphScale, color);
    }

    /** 跑步精灵条资源：14 帧竖排，每格 24×24，白色字形 + alpha 遮罩，可整体染色。 */
    private static final Location RUNNING_SPRITE =
            Location.of("/muonium/textures/player/icons/running-strip.png");
    private static final int RUNNING_SPRITE_FRAMES = 14;
    private static final int RUNNING_SPRITE_CELL = 24;
    /** 每帧停留时长：源 GIF 是 28 帧 ≈ 43ms/帧，抽掉一半后每帧约 87ms，一个跑步循环 1.2 秒。 */
    private static final long RUNNING_SPRITE_FRAME_MILLIS = 87L;

    /**
     * 常驻态的左侧标记。三种形态：
     * <ul>
     *   <li>正在播放页 → 当前歌曲封面（有动态封面时优先用它）；</li>
     *   <li>状态页 + 玩家在移动 → 跑步精灵动画；</li>
     *   <li>状态页 + 玩家静止 → 心跳字形（U+E904）。</li>
     * </ul>
     * 刻意不复用加载转子——常驻并不代表有任务在跑，用转子会误导成"正在加载"。
     */
    private void renderAmbientIcon(IslandAmbientStatus.Snapshot ambient, double centerX, double centerY,
                                   double plateSize, float alpha, int accentColor, long now) {
        if (alpha <= .01f) return;
        double glyphSize = Math.max(6.0, plateSize * .68);
        if (ambient != null && ambient.isNowPlaying() && drawAmbientArtwork(centerX, centerY,
                Math.max(8.0, plateSize * .82), alpha)) {
            return;
        }
        if (ambient != null && ambient.moving && drawRunningSprite(centerX, centerY, glyphSize * 1.12,
                alpha, hexColor(.96f, .97f, 1f, alpha), now)) {
            return;
        }
        drawAmbientGlyph(IslandStatusIcons.PULSE, centerX, centerY, glyphSize,
                RenderSystem.reAlpha(DynamicIslandMath.brightenAccent(accentColor, .18f), alpha));
    }

    /**
     * 画当前歌曲封面。动态封面就绪时 {@code preferredCoverLocation} 会返回抽帧纹理，
     * 于是常驻岛上的封面和桌面歌曲信息、全屏歌词页用的是同一张。
     *
     * @return 是否真的画出来了；没有歌曲或纹理还没加载好时返回 false，调用方退回字形图标
     */
    private boolean drawAmbientArtwork(double centerX, double centerY, double size, float alpha) {
        try {
            Music music = CloudMusic.currentlyPlaying;
            if (music == null || size <= 2.0) return false;
            Location cover = CloudMusic.preferredCoverLocation(music, music.getSmallCoverLocation());
            ITextureObject texture = TextureManager.getInstance().getTexture(cover);
            if (texture == null) {
                // 小封面还没下载好时退回大封面；两张都没有就交给调用方画字形。
                texture = TextureManager.getInstance().getTexture(music.getCoverLocation());
            }
            if (texture == null) return false;
            api.getGLStateManager().bindTexture(texture.getGlTextureId());
            texture.linearFilter();
            roundedRectTextured(centerX - size * .5, centerY - size * .5, size, size,
                    Math.min(5.0, size * .28), alpha);
            api.getGLStateManager().bindTexture(0);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 画跑步精灵动画的当前帧。
     *
     * <p>自己写四边形而不是用 {@code Image.draw}：需要"四边形尺寸"和"采样区域"解耦
     * （从 24×336 的竖排里只取一格），而现有助手把这两者绑在一起。线性过滤 + 半像素内缩
     * 保证缩到 10 像素左右时不会串到相邻帧。</p>
     *
     * @return 纹理可用时返回 true
     */
    private boolean drawRunningSprite(double centerX, double centerY, double size, float alpha,
                                      int color, long now) {
        if (size <= 2.0 || alpha <= .01f) return false;
        ITextureObject texture = TextureManager.getInstance().getTexture(RUNNING_SPRITE);
        if (texture == null) {
            // 首次使用时按需加载；这一帧先交给调用方兜底画字形，下一帧就能用上。
            TextureManager.getInstance().loadTexture(RUNNING_SPRITE, new DynamicTexture(RUNNING_SPRITE));
            return false;
        }
        int frame = (int) ((now / RUNNING_SPRITE_FRAME_MILLIS) % RUNNING_SPRITE_FRAMES);
        double sheetHeight = (double) RUNNING_SPRITE_CELL * RUNNING_SPRITE_FRAMES;
        // 上下各内缩半个纹素：相邻帧在线性过滤下会互相渗色，缩小显示时尤其明显。
        double v0 = (frame * (double) RUNNING_SPRITE_CELL + .5) / sheetHeight;
        double v1 = ((frame + 1) * (double) RUNNING_SPRITE_CELL - .5) / sheetHeight;
        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().bindTexture(texture.getGlTextureId());
        RenderSystem.linearFilter();
        RenderSystem.color(color);
        double left = centerX - size * .5;
        double top = centerY - size * .5;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2d(0.0, v1);
        GL11.glVertex2d(left, top + size);
        GL11.glTexCoord2d(1.0, v1);
        GL11.glVertex2d(left + size, top + size);
        GL11.glTexCoord2d(1.0, v0);
        GL11.glVertex2d(left + size, top);
        GL11.glTexCoord2d(0.0, v0);
        GL11.glVertex2d(left, top);
        GL11.glEnd();
        api.getGLStateManager().bindTexture(0);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return true;
    }

    private void renderNoticeIcon(IslandNoticeType type, double centerX, double centerY,
                                  float alpha, int accentColor, long now) {
        if (alpha <= .01f) return;
        int foreground = hexColor(.94f, .95f, .98f, alpha);
        if (type == IslandNoticeType.REFRESHING || type == IslandNoticeType.PLAYLIST_TRACK_ADDING
                || type == IslandNoticeType.TRANSCODING || type == IslandNoticeType.GD_SOURCE_LOADING) {
            renderSpinner(centerX, centerY, alpha, accentColor, now, false);
            return;
        }
        // 一切"成功"共用同一枚对勾（U+E902）：原来的两笔手绘勾在小尺寸下笔画粗细不均，
        // 而且和"连接成功"的链条图形太像，用户分不出是哪一类完成了。
        if (type == IslandNoticeType.REFRESH_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ADD_SUCCESS
                || type == IslandNoticeType.PLAYLIST_TRACK_ALREADY_EXISTS
                || type == IslandNoticeType.TRANSCODE_SUCCESS || type == IslandNoticeType.GD_SOURCE_HEALTHY
                || type == IslandNoticeType.RECENT_PLAY_SUCCESS
                || type == IslandNoticeType.LISTENING_DURATION_SUCCESS) {
            drawIslandStatusIcon(IslandStatusIcons.SUCCESS, centerX, centerY, alpha,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        // 网易云上报进行中：上传托盘（U+E807），和"成功"的对勾区分开。
        if (type == IslandNoticeType.NETEASE_UPLOADING) {
            drawIslandStatusIcon(IslandStatusIcons.UPLOAD, centerX, centerY, alpha,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        // 连接成功统一用链条（U+E903）。
        if (type == IslandNoticeType.GD_SOURCE_SUCCESS || type == IslandNoticeType.NETWORK_SUCCESS) {
            drawIslandStatusIcon(IslandStatusIcons.LINK, centerX, centerY, alpha,
                    hexColor(.70f, 1f, .77f, alpha));
            return;
        }
        if (type == IslandNoticeType.NETWORK_ERROR) {
            drawNoticeFontelloIcon(FontelloIcons.UNLINK, centerX, centerY, alpha,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.REFRESH_ERROR || type == IslandNoticeType.PLAYLIST_TRACK_ADD_ERROR
                || type == IslandNoticeType.PLAYBACK_ERROR || type == IslandNoticeType.GD_SOURCE_ERROR) {
            drawRotatedPill(centerX, centerY, 10.0, 1.25, 45f,
                    hexColor(1f, .48f, .50f, alpha));
            drawRotatedPill(centerX, centerY, 10.0, 1.25, -45f,
                    hexColor(1f, .48f, .50f, alpha));
            return;
        }
        if (type == IslandNoticeType.THEME || type == IslandNoticeType.STYLE) {
            roundedRect(centerX - 2.5, centerY - 2.5, 5.0, 5.0, 2.5,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - .5, centerY - 6.0, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - .5, centerY + 4.2, 1.0, 1.8, .5, foreground);
            roundedRect(centerX - 6.0, centerY - .5, 1.8, 1.0, .5, foreground);
            roundedRect(centerX + 4.2, centerY - .5, 1.8, 1.0, .5, foreground);
            return;
        }
        if (type == IslandNoticeType.VOLUME) {
            // Speaker body, cone and two sound pulses remain crisp at every island scale.
            roundedRect(centerX - 5.6, centerY - 2.0, 2.3, 4.0, .7, foreground);
            roundedRect(centerX - 3.7, centerY - 3.8, 3.6, 7.6, .8,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 2.0, centerY - 3.5, 1.1, 2.4, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .82f));
            roundedRect(centerX + 3.8, centerY - 5.1, 1.1, 3.9, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .52f));
            roundedRect(centerX + 2.0, centerY + 1.1, 1.1, 2.4, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .82f));
            roundedRect(centerX + 3.8, centerY + 1.2, 1.1, 3.9, .55,
                    RenderSystem.reAlpha(accentColor, alpha * .52f));
            return;
        }
        if (type == IslandNoticeType.PLAY_NEXT) {
            // Three queue lines with a play wedge: the same idea as the row control that produced it.
            roundedRect(centerX - 5.6, centerY - 4.6, 8.4, 1.4, .7, foreground);
            roundedRect(centerX - 5.6, centerY - 1.0, 8.4, 1.4, .7, foreground);
            roundedRect(centerX - 5.6, centerY + 2.6, 4.6, 1.4, .7, foreground);
            roundedRect(centerX + 1.6, centerY + 1.4, 1.2, 3.8, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 2.8, centerY + 2.1, 1.2, 2.4, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX + 4.0, centerY + 2.7, 1.2, 1.2, .6,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        if (type == IslandNoticeType.PLAYBACK_TOGGLE) {
            // 两条竖条 = 暂停键，是这一类通知最直观的符号。
            roundedRect(centerX - 4.2, centerY - 4.8, 2.6, 9.6, 1.1, foreground);
            roundedRect(centerX + 1.6, centerY - 4.8, 2.6, 9.6, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }
        if (type == IslandNoticeType.QUALITY || type == IslandNoticeType.AUTOMIX) {
            // A compact equalizer makes a playback-quality notice distinct from other status states.
            roundedRect(centerX - 5.1, centerY + 1.5, 2.2, 3.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            roundedRect(centerX - 1.1, centerY - 1.3, 2.2, 6.3, 1.1, foreground);
            roundedRect(centerX + 2.9, centerY - 4.5, 2.2, 9.5, 1.1,
                    RenderSystem.reAlpha(accentColor, alpha));
            return;
        }

        double left = centerX - 4.8;
        double top = centerY - 4.8;
        double right = centerX + 4.8;
        double bottom = centerY + 4.8;
        double thickness = 1.0;
        double arm = 3.2;
        roundedRect(left, top, arm, thickness, .5, foreground);
        roundedRect(left, top, thickness, arm, .5, foreground);
        roundedRect(right - arm, top, arm, thickness, .5, foreground);
        roundedRect(right - thickness, top, thickness, arm, .5, foreground);
        roundedRect(left, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(left, bottom - arm, thickness, arm, .5, foreground);
        roundedRect(right - arm, bottom - thickness, arm, thickness, .5, foreground);
        roundedRect(right - thickness, bottom - arm, thickness, arm, .5, foreground);
    }

    private void drawNoticeFontelloIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (glyph == null || glyph.isEmpty() || alpha <= .01f || FontManager.fontello16 == null) return;
        double width = FontManager.fontello16.getStringWidthD(glyph);
        double height = Math.max(1.0, FontManager.fontello16.getFontHeight());
        // Do not use a fixed baseline: Fontello glyphs have different ascender and
        // descender bounds. Metric centering keeps every left-side notice icon in
        // the middle of its circular plate.
        FontManager.fontello16.drawString(glyph, centerX - width * .5, centerY - height * .5, color);
    }

    /**
     * 用 island-status-icons 字体画一枚居中的状态图标。
     *
     * <p>和 {@link #drawNoticeFontelloIcon} 分开是因为字体不同（两批 fontello 的私用区码位冲突），
     * 居中同样按度量算而不是固定基线：这批字形的 ascender/descender 各不相同。</p>
     */
    private void drawIslandStatusIcon(String glyph, double centerX, double centerY, float alpha, int color) {
        if (glyph == null || glyph.isEmpty() || alpha <= .01f || FontManager.islandIcon16 == null) return;
        double width = FontManager.islandIcon16.getStringWidthD(glyph);
        double height = Math.max(1.0, FontManager.islandIcon16.getFontHeight());
        FontManager.islandIcon16.drawString(glyph, centerX - width * .5, centerY - height * .5, color);
    }

    private void renderSpinner(double centerX, double centerY, float alpha, int accentColor,
                               long now, boolean preview) {
        if (alpha <= .01f) return;
        double rotation = preview ? (now / 7.0) % 360.0 : spinnerRotation;
        if (getStyle() == DynamicIslandStyle.LIQUID_GLASS) {
            // 液态玻璃有自己的加载动画；这里集中转发，通知图标那一路也能一起用上。
            renderLiquidGlassSpinner(centerX, centerY, alpha, accentColor, rotation, now);
            return;
        }
        // 先铺一圈极淡的轨道：慢转速下原来只看到一小截笔画在飘，很难认出是"正在加载"。
        roundedOutline(centerX - 6.7, centerY - 6.7, 13.4, 13.4, 6.7, .85,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 26f)));
        final int segments = 8;
        for (int segment = 0; segment < segments; segment++) {
            float trail = 1f - segment / (float) segments;
            // 尾部不透明度抬到 .22 起步、笔画加粗到 1.9px：在 0.88 缩放 + 高 GUI Scale 下仍然连成一条。
            float segmentAlpha = alpha * (.22f + .78f * trail * trail);
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (rotation + segment * (360f / segments)), 0, 0, 1);
            roundedRect(-.95, -6.7, 1.90, 3.0 + 1.0 * trail, .95,
                    RenderSystem.reAlpha(accentColor, segmentAlpha));
            api.getGLStateManager().popMatrix();
        }
        // 领头的高亮点，给旋转方向一个明确的读点。
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate((float) rotation, 0, 0, 1);
        roundedRect(-1.15, -7.0, 2.30, 2.30, 1.15, hexColor(1f, 1f, 1f, alpha * .92f));
        api.getGLStateManager().popMatrix();
    }

    /** 把配置里的速度倍率折算成插值系数，永远留在 (0,.95] 内，速度再快也不会越过目标值。 */
    private static float animationRate(float configuredSpeed, float baseRate) {
        float speed = (float) DynamicIslandMath.clamp(configuredSpeed, .40, 2.50);
        return Math.max(.02f, Math.min(.95f, baseRate * speed));
    }

    /**
     * 液态玻璃表面：半透明冷色基底 + 上半部折射亮带 + 一条缓慢横扫的镜面反光，
     * 外沿是一圈按时间旋转色相的动态取色光。
     *
     * <p>所有笔触都用圆角矩形与其渐变版本绘制，因此天然贴合灵动岛轮廓，不会像径向光斑那样
     * 溢出边界；也没有额外的 GL 状态切换，与既有样式共用同一层状态隔离。</p>
     */
    private void renderLiquidGlassSurface(double x, double y, double width, double height,
                                         double radius, float alpha, int accentColor, long now) {
        double seconds = now / 1000.0;
        int accentBright = DynamicIslandMath.brightenAccent(accentColor, .34f) & 0xFFFFFF;
        double innerRadius = Math.max(1.0, radius - 1.0);

        // 玻璃本体：偏冷的深色基底压住背景，再叠一层强调色的竖向渐变当作体积感。
        roundedRect(x, y, width, height, radius, hexColor(.043f, .055f, .078f, alpha * .82f));
        roundedRectGradientVertical(x, y, width, height, radius,
                DynamicIslandMath.colorWithAlpha(accentBright, alpha * .17f),
                DynamicIslandMath.colorWithAlpha(accentColor, alpha * .07f));
        // 冷白到紫蓝的横向色散。这里不用 drawGradientCornerLR：它的混合角会被算成完全不透明，
        // 放在半透明的玻璃上会糊成两块实色。
        roundedRectGradientHorizontal(x, y, width, height, radius,
                new Color(222, 241, 255, DynamicIslandMath.clamp255(alpha * 34f)),
                new Color(122, 152, 240, DynamicIslandMath.clamp255(alpha * 26f)));

        // 折射：上半部亮带 + 底部回弹的强调色反射。
        roundedRectGradientVertical(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .52), innerRadius,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 46f)),
                new Color(255, 255, 255, 0));
        roundedRectGradientVertical(x + 1.0, y + height * .60, Math.max(1.0, width - 2.0),
                Math.max(1.0, height * .38), innerRadius,
                new Color(255, 255, 255, 0),
                DynamicIslandMath.colorWithAlpha(accentBright, alpha * .13f));

        // 镜面反光：一条两侧渐隐的窄光带来回扫过，速度很慢，不会干扰读字。
        double streakWidth = Math.max(12.0, width * .18);
        double travel = Math.max(0.0, width - streakWidth - radius);
        double streakX = x + radius * .5 + (.5 + .5 * Math.sin(seconds * .82)) * travel;
        double streakHalf = streakWidth * .5;
        float streakAlpha = alpha * 34f;
        roundedRectGradientHorizontal(streakX, y + 1.4, streakHalf, Math.max(1.0, height - 2.8),
                streakHalf * .5, new Color(255, 255, 255, 0),
                new Color(255, 255, 255, DynamicIslandMath.clamp255(streakAlpha)));
        roundedRectGradientHorizontal(streakX + streakHalf, y + 1.4, streakHalf,
                Math.max(1.0, height - 2.8), streakHalf * .5,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(streakAlpha)),
                new Color(255, 255, 255, 0));

        // 边缘动态取色：四角取自主题强调色的四个色相相位，整圈随时间缓慢转动。
        float phase = (float) ((seconds * 26.0) % 360.0);
        roundedOutlineGradient(x, y, width, height, radius, 1.0,
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase), alpha * .80f),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 70f), alpha * .92f),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 140f), alpha * .74f),
                DynamicIslandMath.colorWithAlpha(DynamicIslandMath.shiftHue(accentBright, phase + 210f), alpha * .90f));
        roundedOutline(x + 1.0, y + 1.0, Math.max(1.0, width - 2.0), Math.max(1.0, height - 2.0),
                innerRadius, .55, new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 30f)));
    }

    /**
     * 液态玻璃的加载动画：外圈静止的玻璃刻度、一条按色相渐变的彗尾主环、反向旋转的内圈三点，
     * 以及中心随呼吸缩放的光点。全部由旋转后的圆角矩形组成，1.8.9 的固定管线可以直接吃下。
     */
    private void renderLiquidGlassSpinner(double centerX, double centerY, float alpha,
                                          int accentColor, double rotation, long now) {
        int accentBright = DynamicIslandMath.brightenAccent(accentColor, .30f) & 0xFFFFFF;
        double seconds = now / 1000.0;

        // 外圈刻度：固定不动，给旋转体一个可参照的"玻璃环"。
        final int ticks = 18;
        for (int tick = 0; tick < ticks; tick++) {
            drawRotatedPill(centerX, centerY - 7.1, 1.05, .95,
                    tick * (360f / ticks), hexColor(1f, 1f, 1f, alpha * .10f));
        }

        // 主环彗尾：越靠尾部越短越淡，颜色沿色相环推移。
        final int segments = 12;
        for (int segment = 0; segment < segments; segment++) {
            float trail = 1f - segment / (float) segments;
            float segmentAlpha = alpha * (.08f + .92f * trail * trail);
            double length = 2.1 + 2.4 * trail;
            int color = DynamicIslandMath.shiftHue(accentBright, segment * 9f
                    + (float) ((seconds * 34.0) % 360.0));
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (rotation - segment * (250f / segments)), 0, 0, 1);
            roundedRect(-.80, -6.5, 1.60, length, .80,
                    DynamicIslandMath.colorWithAlpha(color, segmentAlpha).getRGB());
            api.getGLStateManager().popMatrix();
        }

        // 内圈：三点反向慢转，制造两层不同速度的视差。
        for (int dot = 0; dot < 3; dot++) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(centerX, centerY, 0);
            api.getGLStateManager().rotate((float) (-rotation * .58 + dot * 120f), 0, 0, 1);
            roundedRect(-.75, -3.7, 1.50, 1.50, .75,
                    RenderSystem.reAlpha(accentBright, alpha * (.34f + .18f * dot)));
            api.getGLStateManager().popMatrix();
        }

        // 中心光点：呼吸缩放，和边缘光同一套节奏。
        double breathe = .78 + .22 * (.5 + .5 * Math.sin(seconds * 2.4));
        double core = 1.9 * breathe;
        roundedRect(centerX - core, centerY - core, core * 2.0, core * 2.0, core,
                hexColor(1f, 1f, 1f, alpha * .58f));
    }

    /**
     * Original music-card artwork for the MUSIC_FOCUS style. It deliberately uses primitive OpenGL
     * shapes instead of Android assets: the rotating accent bands make the compact and expanded
     * states feel connected while remaining safe for Minecraft 1.8.9's fixed-function pipeline.
     */
    private void renderMusicFocusArtwork(double centerX, double centerY, double islandHeight, float alpha,
                                         int accentColor, int iconAccentColor, long now, boolean preview,
                                         boolean noticeMode, IslandNoticeType noticeType, float success,
                                         float ambientMix, IslandAmbientStatus.Snapshot ambient) {
        double tileSize = Math.min(31.0, Math.max(21.0, islandHeight - 16.0));
        double tileRadius = Math.min(8.0, tileSize * .28);
        double beat = .985 + .025 * (.5 + .5 * Math.sin((preview ? now / 8.0 : now / 290.0)));
        scaleAtPos(centerX, centerY, beat);
        roundedRect(centerX - tileSize * .5, centerY - tileSize * .5, tileSize, tileSize, tileRadius,
                RenderSystem.reAlpha(accentColor, alpha * .66f));
        roundedRect(centerX - tileSize * .5 + 1.0, centerY - tileSize * .5 + 1.0,
                Math.max(1.0, tileSize - 2.0), Math.max(1.0, tileSize - 2.0), Math.max(1.0, tileRadius - 1.0),
                hexColor(.018f, .026f, .045f, alpha * .76f));
        roundedOutline(centerX - tileSize * .5, centerY - tileSize * .5, tileSize, tileSize, tileRadius, .80,
                new Color(255, 255, 255, DynamicIslandMath.clamp255(alpha * 92f)));

        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate((float) ((preview ? now / 7.0 : spinnerRotation * .75) % 360.0), 0, 0, 1);
        roundedRect(-tileSize * .34, -1.25, tileSize * .68, 2.5, 1.25,
                RenderSystem.reAlpha(iconAccentColor, alpha * .58f));
        roundedRect(-1.25, -tileSize * .34, 2.5, tileSize * .68, 1.25,
                RenderSystem.reAlpha(accentColor, alpha * .46f));
        api.getGLStateManager().popMatrix();

        double coreSize = Math.max(8.0, tileSize * .37);
        roundedRect(centerX - coreSize * .5, centerY - coreSize * .5, coreSize, coreSize, coreSize * .5,
                hexColor(.012f, .016f, .029f, alpha * .96f));
        float eventMix = 1f - DynamicIslandMath.clamp01f(ambientMix);
        if (eventMix > .005f) {
            if (noticeMode) {
                api.getGLStateManager().pushMatrix();
                scaleAtPos(centerX, centerY, .64);
                renderNoticeIcon(noticeType, centerX, centerY, alpha * eventMix, iconAccentColor, now);
                api.getGLStateManager().popMatrix();
            } else {
                renderSpinner(centerX, centerY, alpha * (1f - success) * .72f * eventMix,
                        iconAccentColor, now, preview);
                renderSuccess(centerX, centerY, alpha * success * eventMix);
            }
        }
        if (ambientMix > .005f) {
            api.getGLStateManager().pushMatrix();
            scaleAtPos(centerX, centerY, .64);
            renderAmbientIcon(ambient, centerX, centerY, tileSize, alpha * ambientMix,
                    iconAccentColor, now);
            api.getGLStateManager().popMatrix();
        }
    }
    private void renderSuccess(double centerX, double centerY, float alpha) {
        if (alpha <= .01f) return;
        double scale = .78 + .22 * DynamicIslandMath.smoothStep(successMorph);
        api.getGLStateManager().pushMatrix();
        scaleAtPos(centerX, centerY, scale);
        roundedOutline(centerX - 6.2, centerY - 6.2, 12.4, 12.4, 6.2, .75,
                new Color(91, 240, 129, DynamicIslandMath.clamp255(alpha * 220f)));
        // 下载/播放完成也用同一枚 U+E902 对勾，和通知里的"成功"完全一致。
        drawIslandStatusIcon(IslandStatusIcons.SUCCESS, centerX, centerY, alpha,
                hexColor(.70f, 1f, .77f, alpha));
        api.getGLStateManager().popMatrix();
    }

    private void drawRotatedPill(double centerX, double centerY, double width, double height,
                                 float rotation, int color) {
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().translate(centerX, centerY, 0);
        api.getGLStateManager().rotate(rotation, 0, 0, 1);
        roundedRect(-width * .5, -height * .5, width, height, height * .5, color);
        api.getGLStateManager().popMatrix();
    }

    private enum IslandNoticeType {
        NONE,
        THEME,
        STYLE,
        SCALE,
        VOLUME,
        QUALITY,
        REFRESHING,
        REFRESH_SUCCESS,
        REFRESH_ERROR,
        PLAYLIST_TRACK_ADDING,
        PLAYLIST_TRACK_ADD_SUCCESS,
        PLAYLIST_TRACK_ALREADY_EXISTS,
        PLAYLIST_TRACK_ADD_ERROR,
        TRANSCODING,
        TRANSCODE_SUCCESS,
        PLAYBACK_ERROR,
        NETWORK_SUCCESS,
        NETWORK_ERROR,
        NETEASE_UPLOADING,
        RECENT_PLAY_SUCCESS,
        RECENT_PLAY_ERROR,
        LISTENING_DURATION_SUCCESS,
        LISTENING_DURATION_ERROR,
        GD_SOURCE_LOADING,
        GD_SOURCE_SUCCESS,
        GD_SOURCE_HEALTHY,
        GD_SOURCE_ERROR,
        AUTOMIX,
        PLAY_NEXT,
        PLAYBACK_TOGGLE
    }


}
