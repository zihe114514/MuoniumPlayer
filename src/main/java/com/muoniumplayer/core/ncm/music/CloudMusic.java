package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import today.opai.api.enums.EnumChatColor;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.StringUtils;
import com.muoniumplayer.core.utils.other.WrappedInputStream;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 9:34 AM
 * <p>
 * S3 适配：仅保留音频状态机 + 播放线程 + 下载/解码链（play/pause/seek/next/prev/stop/loop/shuffle）。
 * 歌词（LyricLine/LyricParser/MusicLyricsPanel）、封面（TextureManager/Textures/GaussianKernel）、
 * 登录二维码（QRCodeGenerator/NCMScreen）等 UI/渲染/歌词依赖分别归入 S4/S5/S6，
 * 相关方法在 S3 阶段以 no-op 占位并标注，届时从原项目原样回填。
 */
public class CloudMusic implements SharedConstants {

    @Getter
    private static final Map<String, String> headers = new HashMap<>();

    // 播放会话 generation：每次开始新歌递增，用于逻辑取消（logical cancellation）旧歌词任务。
    // 不要依赖 Thread.interrupt 判断歌词任务是否过期；结果提交前校验 generation + songId。
    public static final AtomicLong generation = new AtomicLong(0);

    /**
     * 当前播放会话（volatile：播放线程写、主线程读，保证跨线程可见性）。
     */
    public static volatile PlaybackSession currentSession = null;

    public static volatile AudioPlayer player;
    /**
     * The deck that is fading out during an automix handover. It is not the active player any more, but
     * it is still audible, so volume changes have to reach it too.
     */
    static volatile AudioPlayer fadingOutPlayer;
    private static final Object PLAYER_STATE_LOCK = new Object();
    private static final long PLAY_THREAD_JOIN_TIMEOUT_MS = 1000L;
    // 当前播放列表
    public static List<Music> playList = new ArrayList<>();
    public static int curIdx = 0;
    public static volatile Music currentlyPlaying;
    /**
     * The real NetEase playlist that started the current queue; null for searches/temporary lists.
     */
    public static volatile PlayList currentPlaylistContext;
    public static Thread playThread;
    /** True after URL resolution, download, decode or player initialization fails for the current track. */
    private static volatile boolean awaitingPlaybackAction;

    public static volatile User profile;
    public static volatile List<PlayList> playLists;
    public static volatile List<Long> likeList;
    /**
     * IDs from /api/v1/cloud/get, used to mark the same songs wherever they appear in lists.
     */
    private static volatile Set<Long> userCloudSongIds = Collections.emptySet();

    /**
     * Prevents duplicate account refresh requests while the previous one is still running.
     */
    private static final AtomicBoolean NETEASE_REFRESHING = new AtomicBoolean(false);

    public static volatile PlayMode playMode = PlayMode.Sequential;
    /**
     * Whether the current queue belongs to the isolated personal FM session.
     */
    private static volatile boolean personalFmActive;
    private static volatile PlayMode playModeBeforePersonalFm = PlayMode.Sequential;

    public static Quality quality = Quality.LOSSLESS;

    public static final List<LyricLine> lyrics = new CopyOnWriteArrayList<>();
    public static volatile LyricLine currentLyric = null;
    /**
     * Stable key of the track {@link #lyrics} belongs to, or {@code null} when nothing is committed.
     *
     * <p>The lyric list is global and is only replaced when a load succeeds, so after a failed load it
     * still holds the <em>previous</em> track's timeline. Anything that draws conclusions from those
     * timestamps - automix picks its mix-out point from the last sung line - has to know whose lyrics
     * they are, otherwise it silently applies one song's structure to another.</p>
     */
    public static volatile String lyricsTrackKey = null;
    public static boolean hasTransLyrics = false;
    public static boolean hasRomanization = false;
    public static boolean haveNoWords = false;

    public static final File COOKIE_FILE = new File("NCMCookie.txt");

    @SneakyThrows
    public static void initNCM() {
        String cookie = getCookieFromFileOrOptions();
        CadenceMusicService.initialize(cookie);
        // Cadence 的凭证存储可能持有从新版账号管理中保存的网易云 Cookie。
        // 初始化后重新读取 Options，保证旧资料/歌单加载链路仍可复用。
        if (cookie.isEmpty()) {
            cookie = OptionsUtil.getCookie();
        }

        if (cookie.isEmpty()) {
            System.out.println("[NCM] Not logged in.");
        } else {
            loadNCM(cookie);
        }
    }

    @SneakyThrows
    private static String loadCookie() {
        if (!COOKIE_FILE.exists()) {
            return "";
        }

        List<String> cookieLines = Files.readAllLines(COOKIE_FILE.toPath());
        return cookieLines.isEmpty() ? "" : cookieLines.get(0);
    }

    private static String getCookieFromFileOrOptions() {
        String optionCookie = OptionsUtil.getCookie();
        return optionCookie == null || optionCookie.isEmpty() ? loadCookie() : optionCookie;
    }

    public static void loadNCM(String cookie) {
        OptionsUtil.setCookie(cookie);
        // 获取用户信息
        profile = NeteaseAccountRepository.getUserProfile();

        if (profile == null) {
            return;
        }

        System.out.printf("[NCM] Logged in as %s(%s)\n", profile.getName(), profile.getId());

        if (!OptionsUtil.getCookie().isEmpty()) {
            onStop();
        }

        CloudMusic.playLists = NeteaseAccountRepository.loadUserPlaylists(profile);
        System.out.printf("[NCM] Loaded %s playlists\n", playLists.size());

        likeList = NeteaseAccountRepository.loadLikeList(profile);
        Set<Long> loadedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();
        if (loadedCloudSongIds != null) {
            userCloudSongIds = loadedCloudSongIds;
            System.out.printf("[NCM] Loaded %s cloud-drive song markers%n", userCloudSongIds.size());
        }
        NCMScreen.getInstance().markDirty();
        MultiThreadingUtil.runOnMainThread(() -> NCMScreen.getInstance().reloadCurrentPanel());
    }

    /**
     * Attempts to claim the single网易云刷新 slot.
     */
    public static boolean beginNeteaseRefresh() {
        return NETEASE_REFRESHING.compareAndSet(false, true);
    }

    public static void endNeteaseRefresh() {
        NETEASE_REFRESHING.set(false);
    }

    /**
     * Reloads the logged-in网易云 account data without invoking initial-login side effects.
     * Network work is performed on the caller's thread. Published snapshots are replaced only
     * after all requests have completed successfully, so transient failures preserve old data.
     */
    public static NeteaseRefreshResult refreshNeteaseAccountData() {
        long startedAt = System.currentTimeMillis();
        String cookie = getCookieFromFileOrOptions();
        if (cookie == null || cookie.trim().isEmpty()) {
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "未登录网易云账号");
        }

        try {
            OptionsUtil.setCookie(cookie);
            User refreshedProfile = NeteaseAccountRepository.getUserProfile();
            if (refreshedProfile == null) {
                return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, "登录状态已失效");
            }

            List<PlayList> refreshedPlaylists = NeteaseAccountRepository.loadUserPlaylistsStrict(refreshedProfile);
            List<Long> refreshedLikeList = NeteaseAccountRepository.loadLikeList(refreshedProfile);
            Set<Long> refreshedCloudSongIds = NeteaseAccountRepository.loadCloudSongIds();

            profile = refreshedProfile;
            playLists = refreshedPlaylists;
            likeList = refreshedLikeList;
            if (refreshedCloudSongIds != null) {
                userCloudSongIds = refreshedCloudSongIds;
            }
            return NeteaseRefreshResult.success(System.currentTimeMillis() - startedAt,
                    refreshedPlaylists.size(), refreshedLikeList.size());
        } catch (Throwable throwable) {
            String detail = throwable.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = "网络请求失败";
            }
            System.err.println("[NCM] Playlist refresh failed: " + detail);
            return NeteaseRefreshResult.failure(System.currentTimeMillis() - startedAt, detail);
        }
    }

    /**
     * Returns whether a NetEase song is present in the authenticated user's cloud drive.
     */
    public static boolean isUserCloudSong(long songId) {
        return songId > 0L && userCloudSongIds.contains(songId);
    }

    @lombok.Getter
    public static final class NeteaseRefreshResult {
        private final boolean success;
        private final int playlistCount;
        private final int likeCount;
        private final long elapsedMillis;
        private final String message;

        private NeteaseRefreshResult(boolean success, long elapsedMillis, int playlistCount,
                                     int likeCount, String message) {
            this.success = success;
            this.elapsedMillis = elapsedMillis;
            this.playlistCount = playlistCount;
            this.likeCount = likeCount;
            this.message = message;
        }

        private static NeteaseRefreshResult success(long elapsedMillis, int playlistCount, int likeCount) {
            return new NeteaseRefreshResult(true, elapsedMillis, playlistCount, likeCount, "刷新成功");
        }

        private static NeteaseRefreshResult failure(long elapsedMillis, String message) {
            return new NeteaseRefreshResult(false, elapsedMillis, 0, 0,
                    message == null || message.trim().isEmpty() ? "刷新失败" : message.trim());
        }
    }

    @SneakyThrows
    public static void onStop() {
        Files.write(COOKIE_FILE.toPath(), OptionsUtil.getCookie().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Getter
    public enum PlayMode {
        Random("F", "随机播放"),
        LoopInList("I", "列表循环"),
        LoopSingle("L", "单曲循环"),
        Sequential("G", "顺序播放");

        private final String icon;
        private final String displayName;

        PlayMode(String icon, String displayName) {
            this.icon = icon;
            this.displayName = displayName;
        }
    }

    /**
     * 供播放器控制栏使用的播放模式循环：顺序播放 → 随机播放 → 单曲循环。
     * 列表循环保留给旧数据/兼容逻辑，不再作为主播放器的可见模式。
     */
    public static synchronized PlayMode cyclePlayMode() {
        // Personal FM owns its queue order; ordinary modes do not mutate it.
        if (personalFmActive) return playMode;
        switch (playMode) {
            case Sequential:
            case LoopInList:
                playMode = PlayMode.Random;
                prepareRandomQueue();
                break;
            case Random:
                playMode = PlayMode.LoopSingle;
                break;
            case LoopSingle:
            default:
                playMode = PlayMode.Sequential;
                break;
        }
        return playMode;
    }

    /**
     * 切入随机模式时保留正在播放的歌曲，并把它放在随机队列的开头。
     * 这样当前歌曲不会被中断，下一首立即按随机顺序播放。
     */
    private static void prepareRandomQueue() {
        if (playList == null || playList.size() < 2) {
            return;
        }

        Music currentSong = currentlyPlaying;
        int currentIndex = currentSong == null ? curIdx : playList.indexOf(currentSong);
        if (currentIndex < 0 || currentIndex >= playList.size()) {
            currentIndex = 0;
        }

        Music anchorSong = playList.get(currentIndex);
        List<Music> shuffledSongs = new ArrayList<>(playList);
        shuffledSongs.remove(anchorSong);
        Collections.shuffle(shuffledSongs);
        shuffledSongs.add(0, anchorSong);

        // 保持 List 实例不变，播放线程据此识别“是否切换了播放列表”。
        // 使用逐项覆盖而非 clear/addAll，避免播放线程在极短窗口读取到空队列。
        Collections.copy(playList, shuffledSongs);
        curIdx = 0;
    }

    private static void reshuffleRandomQueueForNextCycle(List<Music> songs) {
        if (songs.size() < 2) {
            return;
        }

        Music justFinished = currentlyPlaying;
        Collections.shuffle(songs);

        // 避免一个随机轮次结束后立刻又播放刚刚结束的那首。
        if (justFinished != null && songs.get(0).equals(justFinished)) {
            for (int i = 1; i < songs.size(); i++) {
                if (!songs.get(i).equals(justFinished)) {
                    Collections.swap(songs, 0, i);
                    break;
                }
            }
        }
    }

    // 以下这些 上一首/下一首 以及 播放/暂停 的逻辑我写完我自己都看不懂
    // BUT IT WORKS
    public static volatile boolean dontAdd = false;

    public static void prev() {
        // 私人 FM 没有"上一首"。原先的 curIdx <= 0 在实践中等于永远拦住：FM 队列以前每首歌都是一条
        // 独立的单曲队列。现在预备下一首会让队列真的长出历史，如果不改成无条件拦住，FM 就凭空多出
        // 一个官方客户端里并不存在的回退按钮。
        if (personalFmActive) return;
        updatePlayCountIfNeeded();

        if (!canPlayPrevious() || playList.isEmpty()) {
            return;
        }

        if (awaitingPlaybackAction) {
            curIdx--;
            restartFromUserTrackSelection();
            return;
        }

        if (player != null) {
            prepareForTrackChange();
            curIdx--;
            stopCurrentPlayback();
        }
    }

    private static boolean canPlayPrevious() {
        if (curIdx - 1 >= 0) {
            return true;
        }

        if (playMode == PlayMode.LoopInList || playMode == PlayMode.Random || playMode == PlayMode.LoopSingle) {
            curIdx = playList.size();
            return true;
        }

        return false;
    }

    public static void next() {
        if (personalFmActive && curIdx + 1 >= playList.size()) {
            PersonalFmManager.requestNextBatchAsync();
            return;
        }
        if (playList.isEmpty()) {
            return;
        }

        // 手动切歌优先交给已经预解码好的 automix 备用轨，避免"关闭播放器 → 重新解析下载解码"的静音断点。
        // 必须放在 canPlayNext() 之前：后者在循环模式走到队尾时会把 curIdx 改成 -1，而交接是在播放线程
        // 的下一个 10ms tick 上完成的，中途让 curIdx 停在 -1 会让读 playList.get(curIdx) 的界面越界。
        if (!awaitingPlaybackAction && player != null && requestManualAutomixSkip()) {
            return;
        }

        if (!canPlayNext()) {
            return;
        }

        if (awaitingPlaybackAction) {
            curIdx++;
            restartFromUserTrackSelection();
            return;
        }

        if (player != null) {
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }
    }


    // ─────────────────────────── 下一首播放（用户指定队列） ───────────────────────────
    //
    // Mainstream players let the user pin a few tracks in front of whatever the playlist would do
    // next, then fall back to the playlist once those are done. That is exactly an insertion into the
    // live queue: the chosen tracks sit immediately after the current one, in the order they were
    // chosen, and nothing else about the queue or the play mode changes. {@link #playList} is already
    // a private copy of the source list (see startPlaybackList), so the playlist the user is browsing
    // is never touched, and a user who never uses the control gets byte-for-byte the old behaviour.

    /** Guards the insertion so two quick clicks cannot interleave into the wrong order. */
    private static final Object PLAY_NEXT_LOCK = new Object();
    /**
     * Absolute index of the last track queued through {@link #playNext(Music)} that has not been
     * reached yet. The block heals itself: once playback passes the tail the value is simply below
     * {@link #curIdx} again, so the next insertion starts a fresh block after the current track.
     */
    private static volatile int playNextTail = -1;
    /**
     * Bumped on every queue change. Automix pre-decodes whatever it predicts will play next, so a deck
     * armed before the user changed their mind has to be thrown away.
     */
    static volatile long queueRevision;

    /** Whether user-queued tracks are still waiting to be played. */
    static boolean hasQueuedNext() {
        int tail = playNextTail;
        return tail > curIdx && tail < playList.size();
    }

    /** How many user-queued tracks are still waiting; 0 when the user queue is empty. */
    public static int getQueuedNextCount() {
        int tail = playNextTail;
        int current = curIdx;
        if (tail <= current || tail >= playList.size()) return 0;
        return tail - current;
    }

    /** Whether this track is one of the tracks currently waiting in the user queue. */
    public static boolean isQueuedNext(Music song) {
        if (song == null) return false;
        List<Music> queue = playList;
        int tail = playNextTail;
        int current = curIdx;
        if (queue == null || tail <= current || tail >= queue.size()) return false;
        for (int index = Math.max(0, current + 1); index <= tail; index++) {
            Music queued = queue.get(index);
            if (queued != null && queued.equals(song)) return true;
        }
        return false;
    }

    /**
     * Queues {@code song} to play after the current track, behind anything already queued this way.
     *
     * @return whether the track was queued, or started when there was no queue to insert into
     */
    public static boolean playNext(Music song) {
        if (song == null) return false;

        List<Music> queue = playList;
        if (queue == null || queue.isEmpty() || currentlyPlaying == null || curIdx < 0) {
            // Nothing is playing, so there is no "next" to insert into. Starting the track is what a
            // mainstream player does here, and it is more useful than silently doing nothing.
            play(Collections.singletonList(song), 0);
            DownloadDynamicIsland.showQueuedNextStarted(song.getName());
            return true;
        }

        int pending;
        synchronized (PLAY_NEXT_LOCK) {
            int current = Math.max(0, curIdx);
            int tail = playNextTail;
            int insertAt = (tail > current && tail < queue.size()) ? tail + 1 : current + 1;
            if (insertAt > queue.size()) insertAt = queue.size();
            queue.add(insertAt, song);
            playNextTail = insertAt;
            queueRevision++;
            pending = Math.max(1, insertAt - current);
        }
        DownloadDynamicIsland.showQueuedNext(song.getName(), pending);
        return true;
    }

    /**
     * 当前用户队列里还没播到的曲目，按播放顺序返回快照。给"下一首播放"列表用：界面每帧读一次，
     * 不能拿到会被后台修改的活列表。
     */
    public static List<Music> getQueuedNextSongs() {
        List<Music> queue = playList;
        int tail = playNextTail;
        int current = curIdx;
        if (queue == null || tail <= current || tail >= queue.size()) return Collections.emptyList();
        List<Music> result = new ArrayList<>(Math.max(1, tail - current));
        for (int index = current + 1; index <= tail && index < queue.size(); index++) {
            Music song = queue.get(index);
            if (song != null) result.add(song);
        }
        return result;
    }

    /**
     * 在用户队列内部重新排序：把第 {@code fromOffset} 个待播曲目移动到第 {@code toOffset} 个位置
     * （下标都相对于队列自身，0 就是下一首要播的那个）。
     *
     * <p>只搬动 {@code curIdx} 之后、{@code playNextTail} 之内的元素，所以当前播放位置和队列长度都
     * 不变，正在播的曲目不受影响。整个搬动持有 {@link #PLAY_NEXT_LOCK}，与 {@link #playNext} 的插入
     * 互斥；{@code queueRevision} 递增会让 automix 丢弃按旧顺序预备好的那一路。</p>
     *
     * @return 是否真的发生了移动
     */
    public static boolean moveQueuedNext(int fromOffset, int toOffset) {
        if (fromOffset == toOffset) return false;
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return false;

            int size = tail - current;
            if (fromOffset < 0 || fromOffset >= size) return false;
            if (toOffset < 0 || toOffset >= size) return false;

            Music song = queue.remove(current + 1 + fromOffset);
            if (song == null) return false;
            queue.add(current + 1 + toOffset, song);
            queueRevision++;
            return true;
        }
    }

    /**
     * 从用户队列里移除第 {@code offset} 个待播曲目（0 就是下一首要播的那个）。
     *
     * <p>被移除的曲目是当初 {@link #playNext(Music)} 插进来的那一条，所以直接从 {@code playList} 里
     * 删掉就回到了插入前的顺序；队列块因此短一格，{@code playNextTail} 同步下移。正在播放的曲目和
     * {@code curIdx} 都不受影响，队列清空后 {@code playNextTail} 复位为 -1。</p>
     *
     * @return 是否真的移除了一条
     */
    public static boolean removeQueuedNext(int offset) {
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return false;

            int size = tail - current;
            if (offset < 0 || offset >= size) return false;

            queue.remove(current + 1 + offset);
            playNextTail = tail - 1 > current ? tail - 1 : -1;
            queueRevision++;
            return true;
        }
    }

    /**
     * 清空用户队列：把所有待播曲目从 {@code playList} 里删掉，恢复到没用过"下一首播放"的顺序。
     *
     * @return 被移除的曲目数量
     */
    public static int clearQueuedNextSongs() {
        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int tail = playNextTail;
            if (queue == null || tail <= current || tail >= queue.size()) return 0;

            int removed = 0;
            for (int index = tail; index > current; index--) {
                queue.remove(index);
                removed++;
            }
            playNextTail = -1;
            queueRevision++;
            return removed;
        }
    }

    /** Forgets the user queue; the tracks themselves stay wherever they were inserted. */
    private static void clearQueuedNext() {
        playNextTail = -1;
        queueRevision++;
    }

    /**
     * 把私人 FM 预备好的下一首接到活队列尾部。必须在主线程调用。
     *
     * <p>FM 会话原本每首歌都是一条独立的单曲队列，{@code peekNextIndex()} 因此永远看不到下一首，无缝
     * 切换在私人 FM 下从来没有生效过。这里让队列真的长出下一格：automix 看到 {@code queueRevision}
     * 变化后会重新预测并提前预解码，普通切歌路径也不再需要"播完再请求"。</p>
     *
     * <p>校验都是为了让一次迟到的预备安全作废：用户可能已经退出 FM、换成了别的歌单（{@code playList}
     * 换成另一条实例）、或者这一格已经被填上了。任一条不成立就返回 false，调用方当作没预备过，旧的
     * "播完再请求下一批"兜底路径照旧生效。</p>
     *
     * @param expectedQueue 发起预备时的活队列实例
     * @param song          预备好的曲目
     * @return 是否真的接上了
     */
    public static boolean appendPrefetchedPersonalFmTrack(List<Music> expectedQueue, Music song) {
        if (expectedQueue == null || song == null) return false;
        synchronized (PLAY_NEXT_LOCK) {
            if (!personalFmActive || playList != expectedQueue) return false;
            int size = expectedQueue.size();
            // 只在"队尾就是正在播的这一首"时接：已经有下一首，或者下标已经越界，都不该再动队列。
            if (curIdx < 0 || curIdx + 1 != size) return false;

            Music tail = expectedQueue.get(size - 1);
            // 接口偶尔会把刚推过的那一首再推一遍，连着播同一首不是无缝切换该有的效果。
            if (tail != null && tail.getId() == song.getId()) return false;

            expectedQueue.add(song);
            queueRevision++;
        }
        return true;
    }

    /**
     * Returns the persisted player volume normalized to {@code 0.0..1.0}.
     * The mod-owned HUD configuration is the source of truth, so the value
     * survives a complete Minecraft restart as well as a track switch.
     */
    public static float getVolume() {
        return clampVolume(HudConfig.playerVolume);
    }

    /**
     * Applies volume through one path for the two player UIs and global hotkeys.
     * Values are quantized to the existing one-percent configuration precision.
     *
     * @return whether the persisted value changed
     */
    public static boolean setVolume(float volume, boolean showNotice) {
        float safeVolume = Math.round(clampVolume(volume) * 100.0f) / 100.0f;
        float previousVolume = getVolume();
        if (Math.abs(previousVolume - safeVolume) < .0001f) {
            return false;
        }

        // Persist independently of the host value manager. Its value store is not
        // guaranteed to be written when Minecraft closes, which previously reset
        // the slider to 10% at the next launch.
        HudConfig.playerVolume = safeVolume;
        HudConfig.save();
        try {
            // Keep the existing hidden OpenAPI setting synchronized for compatibility
            // with the HUD module and any legacy integration that reads it directly.
            MuoniumPlayerExtension.getInstance().musicInfo.volume.setValue((double) safeVolume);
        } catch (RuntimeException ignored) {
            // The active player still receives the new value below if the module is not ready.
        }

        AudioPlayer activePlayer = player;
        if (activePlayer != null) {
            activePlayer.setVolume(safeVolume);
        }
        // Keep a deck that is still fading out in step, otherwise the blend jumps in level.
        AudioPlayer fadingPlayer = fadingOutPlayer;
        if (fadingPlayer != null && fadingPlayer != activePlayer) {
            fadingPlayer.setVolume(safeVolume);
        }

        if (showNotice) {
            DownloadDynamicIsland.showVolume(Math.round(safeVolume * 100.0f));
        }
        return true;
    }

    /**
     * Adjusts volume by a normalized delta and reports the resulting percentage.
     */
    public static boolean adjustVolume(float delta) {
        return setVolume(getVolume() + delta, true);
    }

    private static float clampVolume(float volume) {
        if (Float.isNaN(volume) || Float.isInfinite(volume)) {
            return .10f;
        }
        return Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * Moves the current track by a relative amount and immediately aligns both
     * lyric renderers with the audio player's real clock. Keeping this in the
     * playback layer prevents each UI surface from implementing a subtly
     * different seek path.
     */
    public static boolean seekByMillis(float deltaMillis) {
        AudioPlayer activePlayer = player;
        if (activePlayer == null || currentlyPlaying == null) {
            return false;
        }

        float total = activePlayer.getTotalTimeMillis();
        if (total <= 0.0f) {
            return false;
        }

        float target = Math.max(0.0f, Math.min(total,
                activePlayer.getCurrentTimeMillis() + deltaMillis));
        activePlayer.setPlaybackTime(target);

        float actual = activePlayer.getCurrentTimeMillis();
        MusicLyricsWidget.resetProgress(actual);
        MusicLyricsPanel.resetProgress(actual);
        return true;
    }

    private static boolean canPlayNext() {
        if (curIdx + 1 <= playList.size() - 1) {
            return true;
        }

        if (playMode == PlayMode.Sequential) {
            return false;
        }

        // 非顺序模式下，手动下一首可从队列首位继续。
        curIdx = -1;
        return true;
    }

    /**
     * 给网易云发送当前歌曲的播放时长
     */
    private static void updatePlayCountIfNeeded() {
        if (playedFrom != null && player != null) {
            playList.get(curIdx).updPlayCount(playedFrom, player.getCurrentTimeSeconds());
        }
    }

    /**
     * 请求正在播放的 {@code PlayThread} 用已经预解码好的下一首完成一次无缝交接。
     *
     * <p>这里只是"请求"：真正的交接仍然发生在播放线程的 10ms 监督 tick 上，和自然结束时的自动切歌
     * 走同一段 {@code performAutomixHandover}，因此不会出现两个线程同时替换 player/session 的竞态。
     * 任何一个前置条件不满足（无缝切换被关掉、下一首还没备好、备好的不是队列此刻预测的那一首、正在
     * 暂停、上一次淡化还没跑完）都返回 false，调用方原样走旧的切歌路径。</p>
     */
    private static boolean requestManualAutomixSkip() {
        try {
            if (!AutomixSettings.isEnabled()) return false;
            Thread active = playThread;
            if (!(active instanceof PlayThread)) return false;
            return ((PlayThread) active).requestManualSkip();
        } catch (Throwable ignored) {
            // 手动切歌是用户操作，绝不能因为无缝路径上的意外而卡住：失败就当没有这条捷径。
            return false;
        }
    }

    /**
     * 切换当前播放的暂停/继续。播放器界面的空格键与全局快捷键共用这一条路径，避免两处各写一份判空
     * 逻辑之后行为慢慢分叉；正在进行的无缝淡化由 {@code driveAutomixFade} 一并挂起，这里不必特殊处理。
     *
     * @return 本次调用的实际结果；没有可操作的播放时返回 {@link PlayPauseResult#UNAVAILABLE}
     */
    public static PlayPauseResult togglePlayPause() {
        AudioPlayer activePlayer = player;
        if (currentlyPlaying == null || activePlayer == null || activePlayer.isFinished()) {
            return PlayPauseResult.UNAVAILABLE;
        }
        if (activePlayer.isPausing()) {
            activePlayer.unpause();
            return PlayPauseResult.RESUMED;
        }
        activePlayer.pause();
        return PlayPauseResult.PAUSED;
    }

    /** {@link #togglePlayPause()} 的结果，供界面与快捷键决定要不要给反馈。 */
    public enum PlayPauseResult {
        /** 当前没有可暂停/继续的播放。 */
        UNAVAILABLE,
        PAUSED,
        RESUMED
    }

    private static void prepareForTrackChange() {
        dontAdd = true;
    }

    private static void stopCurrentPlayback() {
        player.close();
        playing.set(false);
    }

    /** Restarts the queue only after an explicit next/previous action following a failed track. */
    private static void restartFromUserTrackSelection() {
        awaitingPlaybackAction = false;
        startPlaybackList(playList, curIdx);
    }

    /**
     * 播放来源, 用于记录播放时长
     */
    public static PlayList playedFrom = null;

    /**
     * 从搜索结果起播：只把用户点的那一首装进队列，随后用刷新过的"最近播放"续上后面的曲目。
     *
     * <p>搜索结果不是歌单，顺着它一路往下播是搜索关键词的排序，不是用户在听的东西。主流播放器在这
     * 里接的都是用户自己的播放上下文，本项目最接近的就是网易云的"最近播放"。</p>
     *
     * <p>刻意先起播、再异步补队列：拉一次最近播放要走一次网络，让点击等它回来会有几百毫秒的"点了
     * 没反应"。补队列时会校验队列还是刚装进去的那一条，用户在这段时间里点了别的歌或者用了"下一首
     * 播放"就直接放弃追加。最近播放为空（未登录 / Cookie 失效 / 接口变更）时退回用搜索结果本身续，
     * 也就是旧行为。</p>
     *
     * @param selected      用户点的那一首
     * @param searchResults 当前搜索结果，作为最近播放不可用时的兜底队列
     * @param selectedIndex {@code selected} 在搜索结果里的下标，仅在完全无法起播时使用
     */
    public static void playFromSearchSelection(Music selected, List<Music> searchResults, int selectedIndex) {
        if (selected == null) {
            play(searchResults, selectedIndex);
            return;
        }

        // 搜索结果不是歌单，播放时长上报没有来源歌单可归属，与旧行为一致。
        currentPlaylistContext = null;
        play(Collections.singletonList(selected), 0);

        final List<Music> installedQueue = playList;
        final List<Music> fallback = searchResults == null
                ? Collections.<Music>emptyList() : new ArrayList<>(searchResults);
        MultiThreadingUtil.runAsync(() -> {
            List<Music> recent = NeteaseRecentPlaysService.fetchRecentSongs(
                    NeteaseRecentPlaysService.MAX_RECENT_SONGS);
            final boolean usedRecent = !recent.isEmpty();
            final List<Music> queue = NeteaseRecentPlaysService.buildQueue(selected,
                    usedRecent ? recent : fallback);
            MultiThreadingUtil.runOnMainThread(() ->
                    appendSearchSelectionTail(installedQueue, selected, queue, usedRecent));
        });
    }

    /**
     * 把 {@link #playFromSearchSelection} 拉到的队列尾部接到活队列上。必须在主线程调用。
     */
    private static void appendSearchSelectionTail(List<Music> installedQueue, Music selected,
                                                  List<Music> queue, boolean usedRecent) {
        if (installedQueue == null || selected == null || queue == null || queue.size() <= 1) return;

        int appended;
        synchronized (PLAY_NEXT_LOCK) {
            // 队列必须还是当初装进去的那一条实例，并且仍然只有那一首：用户在这几百毫秒里换了歌、
            // 换了歌单、或者用"下一首播放"插了曲目，都不能再往里追加。
            if (playList != installedQueue || installedQueue.size() != 1) return;
            Music head = installedQueue.get(0);
            if (head == null || !head.equals(selected)) return;

            List<Music> tail = queue.subList(1, queue.size());
            installedQueue.addAll(tail);
            appended = tail.size();
            // automix 之前按"没有下一首"预测过一次，递增 revision 让它重新预测并重新预解码。
            queueRevision++;
        }
        System.out.println("[NCM] 搜索起播：已接入" + (usedRecent ? "最近播放" : "搜索结果")
                + " " + appended + " 首");
        if (usedRecent) {
            DownloadDynamicIsland.showSearchQueueFromRecentPlays(appended);
        }
    }

    /**
     * 播放给定的列表中的所有歌曲
     *
     * @param songs    歌曲列表
     * @param startIdx 第一首播放的索引
     */
    @SneakyThrows
    public static void play(List<Music> songs, int startIdx) {
        exitPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    /**
     * Starts a small personal FM batch without applying ordinary playlist modes.
     */
    @SneakyThrows
    public static void playFm(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty personal FM batch.");
            return;
        }
        enterPersonalFmSession();
        startPlaybackList(songs, startIdx);
    }

    @SneakyThrows
    private static void startPlaybackList(List<Music> songs, int startIdx) {
        if (songs == null || songs.isEmpty()) {
            System.err.println("[NCM] Ignoring empty playlist.");
            return;
        }

        // 旧队列即将被整条换掉，任何在途的 FM 预备结果都不再属于它。
        PersonalFmManager.clearPrefetchState();

        // 深拷贝一份以避免打乱时影响来源列表；FM 会话始终保持接口返回顺序。
        List<Music> safeSongList = new ArrayList<>(songs);
        stopExistingPlayThread();

        if (!personalFmActive && playMode == PlayMode.Random) {
            startIdx = handleRandomPlayMode(safeSongList, startIdx);
        }

        startIdx = normalizeStartIndex(startIdx);
        loadMusicCover(safeSongList.get(Math.min(startIdx, safeSongList.size() - 1)));
        playList = safeSongList;
        clearQueuedNext();
        startNewPlayThread(safeSongList, startIdx);
    }

    public static boolean isPersonalFmActive() {
        return personalFmActive;
    }

    public static synchronized void enterPersonalFmSession() {
        if (!personalFmActive) {
            playModeBeforePersonalFm = playMode;
            personalFmActive = true;
            playMode = PlayMode.Sequential;
        }
    }

    public static synchronized void exitPersonalFmSession() {
        if (!personalFmActive) return;
        personalFmActive = false;
        playMode = playModeBeforePersonalFm;
        PersonalFmManager.clearPrefetchState();
    }

    /**
     * 使当前播放会话失效（逻辑取消），用于切歌/切列表前阻止旧歌词任务污染新状态。
     * 不在此 increment generation：新歌 beginNewSession() 时再递增。
     */
    private static void invalidateCurrentSession() {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
    }

    /**
     * 开始播放一首新歌时生成唯一 Session：先失效旧 Session，再递增 generation，最后绑定新 Session。
     */
    public static PlaybackSession beginNewSession(Music song) {
        PlaybackSession old = currentSession;
        if (old != null) {
            old.invalidate();
        }
        long id = generation.incrementAndGet();
        PlaybackSession session = new PlaybackSession(id, song.getStableKey(), song.getId());
        currentSession = session;
        return session;
    }

    private static void stopExistingPlayThread() throws InterruptedException {
        invalidateCurrentSession();
        Thread previousThread = playThread;
        if (previousThread == null) return;

        doBreak = true;
        playing.set(false);
        if (previousThread instanceof PlayThread) {
            ((PlayThread) previousThread).cancelPlayback();
        } else {
            previousThread.interrupt();
        }

        // 网络实现可能忽略 interrupt。禁止无限 join 卡住 GUI/重新点歌入口。
        previousThread.join(PLAY_THREAD_JOIN_TIMEOUT_MS);
        if (previousThread.isAlive()) {
            System.err.println("[NCM] Previous play thread did not stop in time; continuing with a new session.");
        }
        if (playThread == previousThread) {
            playThread = null;
        }
    }

    private static int handleRandomPlayMode(List<Music> songs, int startIdx) {
        if (startIdx == -1) {
            Collections.shuffle(songs);
        } else {
            Music selectedMusic = songs.get(startIdx);
            Collections.shuffle(songs);
            startIdx = songs.indexOf(selectedMusic);
        }
        return startIdx;
    }

    private static int normalizeStartIndex(int startIdx) {
        return startIdx == -1 ? 0 : startIdx;
    }

    private static void startNewPlayThread(List<Music> songs, int startIdx) {
        playThread = new PlayThread(songs, startIdx);
        awaitingPlaybackAction = false;
        doBreak = false;
        playing.set(false);
        playThread.start();
    }

    static volatile boolean doBreak = false;

    static AtomicBoolean playing = new AtomicBoolean(true);

    private static class PlayThread extends Thread {
        private final List<Music> songs;
        private final int startIdx;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private PlaybackSession session;
        private volatile AudioPlayer ownedPlayer;
        /** Next track already decoded and waiting silently on the idle deck. */
        private volatile ArmedTrack armed;
        /** True while the background arm task is running, so only one arm is ever in flight. */
        private volatile boolean arming;
        /** Arm attempts made for the current track, so a transient failure retries but a broken one stops. */
        private volatile int armAttempts;
        /** Earliest wall-clock time the next arm attempt may start, i.e. the retry cooldown. */
        private volatile long nextArmAt;
        /** Last queue revision this thread reacted to, so a user queue change resets the arm. */
        private volatile long seenQueueRevision = -1L;
        /** Set by the wait loop when the armed deck took over, so {@code run()} skips a fresh start. */
        private volatile boolean automixHandover;
        /**
         * Set by {@link #requestManualSkip()} when a user-pressed "next" should be served by the armed
         * deck. Consumed by the wait loop on its next tick, so the handover always runs on the playback
         * thread even though the request comes from the interface thread.
         */
        private volatile boolean manualSkipRequested;
        /** Prefetch attempts made for the current track, so a flaky FM response retries but stops. */
        private volatile int fmPrefetchAttempts;
        /** Earliest wall-clock time the next personal-FM prefetch attempt may start. */
        private volatile long nextFmPrefetchAt;
        private volatile AutomixFade activeFade;

        public PlayThread(List<Music> songs, int startIdx) {
            this.songs = songs;
            this.setName("Play Thread");
            this.startIdx = startIdx;
        }

        @Override
        public void run() {
            curIdx = startIdx;

            try {
                while (shouldContinuePlayback()) {
                    if (playListChanged()) {
                        break;
                    }

                    if (automixHandover) {
                        // The armed deck is already audible and owns curIdx/session/lyrics. Nothing has
                        // to be started; just resume supervising the track that is now playing.
                        automixHandover = false;
                        resetArmState();
                    } else {
                        Music currentSong = playList.get(curIdx);
                        prepareForPlayback();

                        if (!playSong(currentSong)) {
                            // Never skip a failed track automatically. Keep the failed song selected and
                            // wait for the user to retry it, choose next/previous, or select another song.
                            awaitingPlaybackAction = !isPlaybackCancelled() && !playListChanged() && !doBreak;
                            playing.set(false);
                            break;
                        }

                        awaitingPlaybackAction = false;
                        resetArmState();
                        preloadNextCover();
                    }

                    waitForPlaybackCompletion();
                    if (automixHandover) {
                        // curIdx, session and the active player were advanced by the handover itself.
                        continue;
                    }

                    cancelArmedTrack();
                    finishAutomixFade();
                    if (isPlaybackCancelled() || playListChanged() || session == null || !session.isActive()) {
                        break;
                    }
                    handlePlaybackCompletion();
                    updateCurrentIndex();
                    if (personalFmActive && curIdx >= playList.size()) {
                        PersonalFmManager.requestNextBatchAsync();
                        break;
                    }
                }
            } catch (Throwable failure) {
                // 最后一道防线。播放链路里任何漏网的 Throwable（解码器 LinkageError、内存不足、
                // 第三方源解析里意外的 Error）如果直接逃出 run()，Play Thread 会静默死亡：
                // playing 留在 true、灵动岛不收尾、也不会有任何提示，表现为"下载完成却不播放"。
                // 这里统一提示一次并复位成"等待用户操作"，与解析失败不自动跳下一首保持一致。
                if (!isPlaybackCancelled() && !playListChanged()) {
                    handlePlayerInitializationError(currentlyPlaying, failure);
                    awaitingPlaybackAction = true;
                } else {
                    failure.printStackTrace();
                }
                playing.set(false);
            } finally {
                // A deck that is mid-blend is not the active player, so nothing else would release it.
                cancelArmedTrack();
                finishAutomixFade();
            }
        }

        private boolean shouldContinuePlayback() {
            return curIdx >= 0 && curIdx < playList.size() && !doBreak && !isPlaybackCancelled();
        }

        private boolean isPlaybackCancelled() {
            return cancelled.get() || this.isInterrupted();
        }

        private boolean isSessionUsable(PlaybackSession targetSession) {
            return !isPlaybackCancelled() && targetSession != null && targetSession.isActive()
                    && !playListChanged();
        }

        private void cancelPlayback() {
            cancelled.set(true);
            PlaybackSession activeSession = session;
            if (activeSession != null) activeSession.invalidate();
            interrupt();

            // Release the automix decks first: neither of them is CloudMusic.player, so the block below
            // would leave them running and audible.
            cancelArmedTrack();
            finishAutomixFade();

            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer activePlayer = ownedPlayer;
                NeteasePlaybackHistoryReporter.finish(activePlayer,
                        NeteasePlaybackHistoryReporter.EndReason.REPLACED);
                if (activePlayer != null && CloudMusic.player == activePlayer) {
                    try {
                        activePlayer.close();
                    } catch (Throwable ignored) {
                        // 播放器可能尚未完全初始化；取消流程不能再阻塞新的点歌请求。
                    }
                }
                playing.set(false);
            }
        }

        private boolean playListChanged() {
            return playList != songs;
        }

        private void prepareForPlayback() {
            stopPreviousPlayer();
            loadMusicCover(playList.get(curIdx));
        }

        private boolean playSong(Music song) {
            if (isPlaybackCancelled()) return false;

            // 先使旧 session 失效并生成新 session/generation，绑定本次歌曲。
            session = CloudMusic.beginNewSession(song);
            if (!isSessionUsable(session)) {
                session.invalidate();
                return false;
            }
            currentlyPlaying = song;
            // 有了 FM 预备之后，推进不再每首都经过 PersonalFmManager.requestBatch，界面同步必须在这里补。
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(song);
            // Dynamic cover lookup is optional and never blocks audio startup or static-cover rendering.
            loadDynamicMusicCover(song);

            // 每次点击/切歌都会重新创建解析任务；失败结果不会被缓存。
            Tuple<String, String> playUrl = song.getPlayUrl();
            if (!isSessionUsable(session)) return false;

            if (playUrl == null) {
                handleUnplayableSong(song);
                return false;
            }

            boolean started = initializeAndPlaySong(song, playUrl, session);
            if (started && isSessionUsable(session)) {
                DownloadDynamicIsland.showPlaybackQuality(
                        song.getPlaybackQuality().getDisplayName(), playUrl.getB());
            }
            return started;
        }

        private boolean initializeAndPlaySong(Music song, Tuple<String, String> playUrl, PlaybackSession targetSession) {
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();

            File musicFile;
            try {
                musicFile = getMusicFile(playUrl, song);
            } catch (Throwable e) {
                if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                    handlePlayerInitializationError(song, e);
                }
                return false;
            }

            // URL 获取、下载或解码期间可能已经切歌。旧线程绝不能覆盖新会话的播放器。
            if (!isSessionUsable(targetSession)) return false;

            synchronized (PLAYER_STATE_LOCK) {
                if (!isSessionUsable(targetSession)) return false;
                try {
                    player = initializePlayer(musicFile);
                    ownedPlayer = player;
                    targetSession.player = ownedPlayer;
                    // 必须捕 Throwable 而不是 Exception：下载与解码链路会抛 Error。随 mod 打包的
                    // jflac 在读 FLAC 的 VORBIS_COMMENT 时才第一次用到 VorbisString，PCM 分配失败
                    // 抛的是 OutOfMemoryError。这些 Error 从 catch (Exception) 漏出去会一直逃到
                    // run() 之外杀死 Play Thread：没有任何失败提示、playing 停在 true、灵动岛不收尾，
                    // 用户看到的就是"下载完成却不播放"。
                } catch (Throwable e) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, e);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;

            // Third-party search responses (GD Studio) carry no duration. The decoder knows the real
            // length now, so record it before the lyric/progress consumers read it.
            AudioPlayer startedPlayer = ownedPlayer;
            if (startedPlayer != null) {
                song.applyDecodedDuration((long) startedPlayer.getTotalTimeMillis());
            }

            // 歌词异步加载绑定本 Session（两阶段提交），歌词可先到或后到。
            // 网盘歌曲额外携带本地缓存文件，用于优先读取音频标签中的内嵌 LRC/YRC。
            loadLyric(song, targetSession, musicFile);
            if (!startPlayback(song, playUrl, musicFile, targetSession)) return false;
            targetSession.audioActive = true;
            return true;
        }

        private void waitForPlaybackCompletion() {
            while (true) {
                if (doBreak || !isSessionUsable(session)) break;

                AudioPlayer activePlayer = ownedPlayer;
                if (activePlayer == null) break;
                CloudMusic.updateCurrentLyric(activePlayer.getCurrentTimeMillis());
                NeteasePlaybackHistoryReporter.observe(activePlayer);

                // The 10 ms supervision tick doubles as the crossfade clock: fine-grained enough for a
                // smooth ramp and already bound to the session/cancellation checks above.
                driveAutomixFade();
                // Served before the arm bookkeeping: a queue-revision change inside maybeArmNextTrack
                // drops the armed deck, and a request that arrived a tick earlier must not be thrown
                // away with it without the ordinary switch running instead.
                if (tryManualNextHandover(activePlayer)) {
                    automixHandover = true;
                    return;
                }
                // 私人 FM 的下一首得先请求回来才存在，所以排在 arm 之前：预备成功会递增 queueRevision，
                // 紧接着的那次 arm 正好看到长出来的队列并立刻开始预解码。
                maybePrefetchPersonalFm(activePlayer);
                maybeArmNextTrack(activePlayer);
                maybePreRollArmedDeck(activePlayer);
                if (tryAutomixHandover(activePlayer)) {
                    automixHandover = true;
                    return;
                }

                // Checked after the handover attempt so a track that just ended can still hand over to an
                // armed deck instead of falling back to the close/download/decode gap.
                if (!playing.get()) break;

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void handlePlaybackCompletion() {
            AudioPlayer completedPlayer = ownedPlayer;
            if (completedPlayer == null || isPlaybackCancelled()) return;

            NeteasePlaybackHistoryReporter.finish(completedPlayer,
                    NeteasePlaybackHistoryReporter.EndReason.COMPLETED);

            if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                playList.get(curIdx).updPlayCount(playedFrom, completedPlayer.getCurrentTimeSeconds());
            }

            synchronized (PLAYER_STATE_LOCK) {
                if (!isPlaybackCancelled() && ownedPlayer == completedPlayer) {
                    completedPlayer.close();
                }
            }
        }

        private void stopPreviousPlayer() {
            synchronized (PLAYER_STATE_LOCK) {
                AudioPlayer previousPlayer = CloudMusic.player;
                if (previousPlayer != null && !previousPlayer.isFinished()) {
                    previousPlayer.close();
                }
            }
            if (!isPlaybackCancelled()) sleep(250);
        }

        private void handleUnplayableSong(Music song) {
            api.printMessage(EnumChatColor.RED + "无法播放: " + song.getName() + " - " + song.getArtistsName());
            DownloadDynamicIsland.showPlaybackFailure(song.getName(), "没有可用音频源");

            System.err.printf("%s无法播放: %s - %s, 可能因为该歌曲没有版权\n", EnumChatColor.RED, song.getName(), song.getArtistsName());
        }

        private void handlePlayerInitializationError(Music song, Throwable e) {
            e.printStackTrace();
            String message = rootCauseMessage(e);
            DownloadDynamicIsland.showPlaybackFailure(song == null ? "当前歌曲" : song.getName(),
                    message.isEmpty() ? "音频加载或解码失败" : message);
            System.err.printf(EnumChatColor.RED + "[NCM] Failed to initiate audio player! Error: %s\n", message);
        }

        /** Surfaces the deepest cause so a decode/memory limit is explained instead of hidden. */
        private String rootCauseMessage(Throwable throwable) {
            Throwable cause = throwable;
            String message = "";
            int depth = 0;
            while (cause != null && depth++ < 6) {
                String candidate = cause.getMessage();
                if (candidate != null && !candidate.trim().isEmpty()) {
                    message = candidate.trim();
                }
                cause = cause.getCause();
            }
            return message.length() > 90 ? message.substring(0, 90) : message;
        }

        private boolean startPlayback(Music song, Tuple<String, String> playUrl, File musicFile,
                                      PlaybackSession targetSession) {
            AudioPlayer activePlayer = ownedPlayer;
            if (activePlayer == null || !isSessionUsable(targetSession)) return false;

            try {
                synchronized (PLAYER_STATE_LOCK) {
                    if (!isSessionUsable(targetSession) || ownedPlayer != activePlayer) return false;
                    activePlayer.play();
                }
            } catch (ChannelMismatchException mismatch) {
                try {
                    activePlayer.player.cleanUp();
                    musicFile.delete();
                    if (!isSessionUsable(targetSession)) return false;

                    File retryFile = getMusicFile(playUrl, song);
                    synchronized (PLAYER_STATE_LOCK) {
                        if (!isSessionUsable(targetSession)) return false;
                        player = initializePlayer(retryFile);
                        ownedPlayer = player;
                        targetSession.player = ownedPlayer;
                        activePlayer = ownedPlayer;
                        activePlayer.play();
                    }
                } catch (Throwable retryFailure) {
                    if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {
                        handlePlayerInitializationError(song, retryFailure);
                    }
                    return false;
                }
            }

            if (!isSessionUsable(targetSession)) return false;
            playing.set(true);
            NeteasePlaybackHistoryReporter.start(song, currentPlaylistContext, activePlayer);
            final AudioPlayer callbackPlayer = activePlayer;
            callbackPlayer.setAfterPlayed(() -> {
                // 已被下一首复用/替换的播放器回调不能结束新会话。
                if (ownedPlayer == callbackPlayer && isSessionUsable(targetSession)) {
                    this.notifyWaitLock();
                }
            });
            return true;
        }

        private void preloadNextCover() {
            if (curIdx + 1 < playList.size()) {
                loadMusicCover(playList.get(curIdx + 1));
            }
        }

        private void updateCurrentIndex() {
            updateCurIdx();
        }

        private File getMusicFile(Tuple<String, String> playUrl, Music song) {
            String url = playUrl.getA();
            String reportedType = AudioContainerSupport.normalizeReportedContainer(playUrl.getB());
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Music URL is empty");
            }

            // CDN extensions and API-reported types are advisory only. The cache is named from
            // the downloaded bytes below, which also lets M4A/AAC fall back safely when the API
            // reports a generic or incorrect MIME type.
            try {
                return getCachedOrTempFile(url, reportedType, song);
            } catch (RuntimeException primaryFailure) {
                return getValidationFallbackFile(song, url, primaryFailure);
            }
        }

        /**
         * A URL can be syntactically valid while serving a DASH video or another non-audio MP4
         * payload. Once the byte-level validation rejects such a primary response, retry a
         * source-appropriate stream before treating the song as unplayable: NetEase falls back to
         * its standard-MP3 endpoint, a GD Studio track falls back to another healthy GD source.
         */
        private File getValidationFallbackFile(Music song, String failedUrl, RuntimeException primaryFailure) {
            if (song == null || !(song.isNetease() || song.isGd())) {
                throw primaryFailure;
            }

            Tuple<String, String> standardPlayUrl = song.isGd()
                    ? song.getGdCrossSourceFallbackPlayUrl()
                    : song.getStandardMp3PlayUrl();
            if (standardPlayUrl == null || standardPlayUrl.getA() == null
                    || standardPlayUrl.getA().trim().isEmpty()
                    || standardPlayUrl.getA().equals(failedUrl)) {
                throw primaryFailure;
            }

            try {
                System.err.println("[NCM] Primary response failed byte-level audio validation for "
                        + song.getStableKey() + "; retrying "
                        + (song.isGd() ? "another GD source." : "standard MP3."));
                return getCachedOrTempFile(standardPlayUrl.getA(),
                        AudioContainerSupport.normalizeReportedContainer(standardPlayUrl.getB()), song);
            } catch (RuntimeException standardFailure) {
                primaryFailure.addSuppressed(standardFailure);
                throw primaryFailure;
            }
        }

        /**
         * The format advertised by a CDN URL is not always the actual container returned by the
         * server. In particular, some lossless Netease URLs have been observed to return FLAC
         * bytes while reporting an MP3 type. SoundFile selects its decoder by extension, so the
         * cache extension must follow the downloaded bytes rather than the reported URL type.
         */
        private File getCachedOrTempFile(String playUrl, String type, Music song) {
            File musicCacheDir = new File("MusicCache");
            if (!musicCacheDir.exists() && !musicCacheDir.mkdirs()) {
                throw new IllegalStateException("Unable to create music cache directory: " + musicCacheDir.getAbsolutePath());
            }

            String cacheKey = song.getStableKey() + "_" + quality.getQuality();
            File cachedMusic = AudioCacheFiles.findCachedAudioFile(musicCacheDir, cacheKey, type);
            if (cachedMusic != null) {
                // 命中也要记一笔：容量淘汰只看 lastModified 的话，一首常听但下载得早的歌
                // 会被当成最冷的缓存删掉。
                MusicAudioCache.touch(cachedMusic);
                return resolvePlayableAudioFile(cachedMusic);
            }

            // Never expose a partially downloaded file to the decoder or cache lookup.
            File temporaryMusic = new File(musicCacheDir, cacheKey + ".download");
            if (temporaryMusic.exists() && !temporaryMusic.delete()) {
                throw new IllegalStateException("Unable to replace incomplete music download: " + temporaryMusic.getName());
            }

            downloadMusic(playUrl, temporaryMusic);
            if (!temporaryMusic.isFile() || temporaryMusic.length() < 4L) {
                throw new IllegalStateException("music download failed or was interrupted: " + temporaryMusic.getName());
            }

            String actualType = AudioContainerSupport.detectContainer(temporaryMusic);
            if (!AudioContainerSupport.isSupportedContainer(actualType)) {
                temporaryMusic.delete();
                throw new IllegalStateException("Downloaded audio has an unsupported or invalid container: " + cacheKey);
            }

            File music = new File(musicCacheDir, cacheKey + "." + actualType);
            if (music.exists()) {
                String existingType = AudioContainerSupport.detectContainer(music);
                if (actualType.equals(existingType)) {
                    temporaryMusic.delete();
                } else {
                    music.delete();
                    AudioCacheFiles.moveCacheFile(temporaryMusic, music);
                }
            } else {
                AudioCacheFiles.moveCacheFile(temporaryMusic, music);
            }

            removeOtherQualityCaches(musicCacheDir, song.getStableKey(), quality.getQuality());
            MusicAudioCache.touch(music);
            // 目录只会因为下载而变大，所以容量兜底只需要挂在这一处；解码边车在下一句产生，
            // 淘汰按组进行，所以这里先执行也不会把边车漏在外面。
            MusicAudioCache.enforceLimitAsync();
            return resolvePlayableAudioFile(music);
        }

        /**
         * JSyn can directly stream MP3, FLAC and WAV. AAC/ADTS and ISO-BMFF payloads are decoded
         * once into a validated WAV sidecar. The decoder accepts only AAC audio tracks, so video-only,
         * DRM-protected or unsupported MP4 streams still fail safely.
         */
        private File resolvePlayableAudioFile(File sourceFile) {
            String container = AudioContainerSupport.detectContainer(sourceFile);
            if (!AudioContainerSupport.isSupportedContainer(container)) {
                throw new IllegalStateException("Cached audio has an unsupported or invalid container: "
                        + sourceFile.getName());
            }
            if (!AudioContainerSupport.requiresAacDecode(container)) {
                return sourceFile;
            }

            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
                return decodedFile;
            }
            if (decodedFile.exists() && !decodedFile.delete()) {
                throw new IllegalStateException("Unable to replace invalid decoded audio cache: "
                        + decodedFile.getName());
            }

            long transcodeStartedAt = System.currentTimeMillis();
            DownloadDynamicIsland.beginTranscode(sourceFile.getName(), decodedFile.getName());
            try {
                AacAudioDecoder.decodeToWav(sourceFile, container, decodedFile,
                        new AacAudioDecoder.ProgressListener() {
                            @Override
                            public void onProgress(double progress) {
                                DownloadDynamicIsland.updateTranscodeProgress(progress);
                            }
                        });
                if (!AudioCacheFiles.isReusableDecodedWav(sourceFile, decodedFile)) {
                    throw new IOException("Decoded AAC cache is invalid: " + sourceFile.getName());
                }
            } catch (Exception exception) {
                DownloadDynamicIsland.cancelTranscode();
                if (decodedFile.exists()) {
                    decodedFile.delete();
                }
                if (sourceFile.exists() && !sourceFile.delete()) {
                    System.err.println("[NCM] Unable to remove undecodable AAC cache: " + sourceFile.getName());
                }
                throw new IllegalStateException("Unable to decode AAC audio into a playable WAV cache", exception);
            }
            DownloadDynamicIsland.finishTranscode(sourceFile.getName(), decodedFile.getName(),
                    System.currentTimeMillis() - transcodeStartedAt);
            return decodedFile;
        }

        private void discardLegacyDecodedWav(File sourceFile) {
            File decodedFile = AudioCacheFiles.getDecodedWavFile(sourceFile);
            if (decodedFile.exists() && !decodedFile.delete()) {
                System.err.println("[NCM] Unable to remove legacy MP4 decoded cache: " + decodedFile.getName());
            }
        }

        private void removeOtherQualityCaches(File musicCacheDir, String stableKey, String currentQuality) {
            MultiThreadingUtil.runAsync(() -> {
                File[] cacheFiles = musicCacheDir.listFiles();
                if (cacheFiles == null) {
                    return;
                }

                String allQualitiesPrefix = stableKey + "_";
                String currentQualityPrefix = allQualitiesPrefix + currentQuality;
                for (File file : cacheFiles) {
                    if (file.getName().startsWith(allQualitiesPrefix) && !file.getName().startsWith(currentQualityPrefix)) {
                        file.delete();
                    }
                }
            });
        }

        private AudioPlayer initializePlayer(File musicFile) {
            AudioPlayer player = CloudMusic.player;
            if (player == null) {
                player = new AudioPlayer(musicFile);
                player.setVolume(CloudMusic.getVolume());
                CloudMusic.player = player;
            } else {
                player.setAudio(musicFile);
            }
            return player;
        }

        private void notifyWaitLock() {
            playing.set(false);
        }

        // ─────────────────────────── automix (seamless handover) ───────────────────────────
        //
        // The original sequence was: track ends → close the player → sleep(250) → resolve URL →
        // download → decode → start. That is seconds of silence between two tracks. Automix moves all
        // of that work forward: while the current track still plays, the next one is resolved, decoded
        // and held silent on a second deck, and at the planned moment the two decks cross-fade. With
        // AutomixSettings disabled none of this runs and the original sequence is used unchanged.

        /** Playback position after which the next track may start being armed. */
        private static final long EARLY_ARM_MILLIS = 10_000L;
        /**
         * 私人 FM 开始预备下一首的播放位置。比 {@link #EARLY_ARM_MILLIS} 更早：预备本身要走一次网络
         * 请求，拿回来之后 automix 还要解析、下载、可能转码、再解码，越早接上队列越有机会真的无缝。
         * 又不是 0：用户连点跳过时每首都立刻打一次接口纯属浪费，还会把推荐白白塞进去重表。
         */
        private static final long FM_PREFETCH_AFTER_MILLIS = 5_000L;
        /** 每首歌允许的预备次数；连续失败就让位给"播完再请求下一批"的旧兜底路径。 */
        private static final int MAX_FM_PREFETCH_ATTEMPTS = 3;
        /** 预备失败后的冷却，避免接口异常时被 10ms 的监督 tick 反复敲。 */
        private static final long FM_PREFETCH_RETRY_MILLIS = 15_000L;
        /** Attempts allowed per track before automix gives up and lets the ordinary switch run. */
        private static final int MAX_ARM_ATTEMPTS = 3;
        /** Cooldown between arm attempts, so a failing source is not hammered every tick. */
        private static final long ARM_RETRY_MILLIS = 20_000L;
        /** Ramp used when the handover fires with (almost) none of the outgoing track left. */
        private static final long LATE_FIRE_FADE_MILLIS = 700L;
        /**
         * Ramp used when the user pressed "next" themselves.
         *
         * <p>The planned overlap is measured in bars and can be several seconds: correct for a blend the
         * listener never asked for, far too slow for a button press, which has to feel immediate. Just
         * under a second is short enough to read as "switched now" and still long enough to stay a
         * gain ramp rather than a click at the splice point.</p>
         */
        private static final long MANUAL_SKIP_FADE_MILLIS = 900L;
        /**
         * How long before the planned handover the armed deck is started, silently.
         *
         * <p>A deck streams its PCM from disk through a small sliding window that is refilled from
         * the JSyn engine thread, and starting one also adds units to the running synth and rewires
         * its circuit. Doing that at the instant of the crossfade puts a file seek, a read and a
         * graph change inside the audio callback that is simultaneously serving the outgoing deck -
         * which is heard as a hitch exactly where the blend is supposed to be seamless. So the deck
         * is started early at full silence, and the seam itself only moves gain.</p>
         */
        private static final long PRE_ROLL_MILLIS = 400L;

        /**
         * How far the outgoing track may fall behind the pre-roll point before the silent deck counts
         * as stale. Only a deliberate seek moves it that far; ordinary tick jitter is 10 ms.
         */
        private static final long PRE_ROLL_REWIND_SLACK_MILLIS = 500L;

        /** The next track, fully decoded and waiting silently on the idle deck. */
        private static final class ArmedTrack {
            private final Music song;
            private final File file;
            private final AudioPlayer deck;
            private final int index;
            /** Playback generation the arm was made for; a newer one means the arm is stale. */
            private final long generation;
            /** Queue revision the prediction was made under; a newer one means it predicted wrong. */
            private final long queueRevision;
            private volatile AutomixPlan plan;
            /** Silent lead-in actually available, i.e. min(PRE_ROLL_MILLIS, plan.incomingCueMillis). */
            private volatile long preRollMillis;
            /** The deck is already sounding (silently) and must not be cued or started again. */
            private volatile boolean preRolled;
            /** True while the pre-rolled deck is held paused because playback itself is paused. */
            private volatile boolean preRollPaused;

            private ArmedTrack(Music song, File file, AudioPlayer deck, int index, long generation,
                               long queueRevision) {
                this.song = song;
                this.file = file;
                this.deck = deck;
                this.index = index;
                this.generation = generation;
                this.queueRevision = queueRevision;
            }
        }

        /**
         * A crossfade in progress. Driven off the wall clock so a lag spike cannot stretch the ramp,
         * but the clock is held still while playback is paused - otherwise a pause during the blend
         * would let the ramp run to completion with only the outgoing deck audible.
         */
        private static final class AutomixFade {
            private final AudioPlayer outgoing;
            private final AudioPlayer incoming;
            private final AutomixPlan plan;
            /**
             * Effective ramp length. Usually {@code plan.overlapMillis}, but shortened when the
             * handover fires late: ramping in over four seconds while the outgoing track has already
             * ended would open the new song from near silence.
             */
            private final long overlapMillis;
            private long startedAt;
            private long tickedAt;
            /** True only when this fade paused the outgoing deck, so it is never resumed by accident. */
            private boolean outgoingPaused;

            private AutomixFade(AudioPlayer outgoing, AudioPlayer incoming, AutomixPlan plan,
                                long overlapMillis, long startedAt) {
                this.outgoing = outgoing;
                this.incoming = incoming;
                this.plan = plan;
                this.overlapMillis = Math.max(1L, overlapMillis);
                this.startedAt = startedAt;
                this.tickedAt = startedAt;
            }
        }

        /**
         * Predicts the index {@link #updateCurIdx()} will land on, without touching any queue state.
         * Returns -1 whenever the next track cannot be known ahead of time (a pending manual switch, the
         * random-mode reshuffle at the end of a cycle, an FM batch that still has to be fetched), in
         * which case automix simply does not arm and the ordinary switch runs.
         */
        private int peekNextIndex() {
            List<Music> queue = playList;
            if (queue == null || queue.isEmpty()) return -1;
            if (dontAdd) return -1;
            if (personalFmActive) {
                int next = curIdx + 1;
                return next < queue.size() ? next : -1;
            }
            if (playMode == PlayMode.LoopSingle) {
                if (hasQueuedNext()) {
                    int queuedNext = curIdx + 1;
                    return queuedNext < queue.size() ? queuedNext : -1;
                }
                // Single loop replays this exact track. Blending it into itself would trim its tail on
                // every repeat, which is the opposite of what "repeat one" asks for, so nothing is armed
                // and the track plays to its natural end before restarting.
                return -1;
            }
            int next = curIdx + 1;
            if (next < queue.size()) return next;
            // Random reshuffles the queue when it wraps, so the next track is genuinely unknown here.
            if (playMode == PlayMode.LoopInList) return 0;
            return -1;
        }

        /**
         * An arm is only useful while this exact playback generation is still the current one, and
         * while the queue still predicts the same next track. Queueing something with "play next"
         * changes that prediction, so the revision is part of the validity check.
         */
        private boolean armStillValid(long armGeneration, long armRevision) {
            return !isPlaybackCancelled() && !playListChanged() && !doBreak
                    && generation.get() == armGeneration
                    && CloudMusic.queueRevision == armRevision
                    && AutomixSettings.isEnabled();
        }

        /** Clears the arm bookkeeping so the newly started track gets its own attempts. */
        private void resetArmState() {
            armAttempts = 0;
            nextArmAt = 0L;
            // A request that never got served (its session died first) must not fire against the track
            // that starts next: the user asked to leave a different song.
            manualSkipRequested = false;
            // 预备下一首同样按"每首歌一份预算"计：交接到新的一首之后要能重新预备。
            fmPrefetchAttempts = 0;
            nextFmPrefetchAt = 0L;
        }

        /**
         * 私人 FM 专属：在当前这首还在放的时候就把下一首推荐拉回来接到队列尾部。
         *
         * <p>普通歌单的下一首本来就躺在队列里，automix 直接预解码即可；私人 FM 的下一首却必须先发一次
         * 请求才存在，所以旧流程只能"播完 → 请求 → 重开播放线程"，中间的静音躲不掉，automix 也因为
         * {@code peekNextIndex()} 恒为 -1 而完全没生效。</p>
         *
         * <p>刻意不看 {@link AutomixSettings#isEnabled()}：即使用户关掉了无缝切换，提前把下一首接进队列
         * 也能省掉那一段"播完才开始下载解码"的空白，效果上就是普通的顺序播放。解析失败等待用户操作
         * （{@code awaitingPlaybackAction}）或用户正在手动切歌（{@code dontAdd}）时不预备，避免替一条
         * 马上要被换掉的队列白跑一次请求。</p>
         */
        private void maybePrefetchPersonalFm(AudioPlayer activePlayer) {
            if (!personalFmActive || dontAdd || awaitingPlaybackAction) return;
            if (fmPrefetchAttempts >= MAX_FM_PREFETCH_ATTEMPTS) return;
            if (activePlayer == null || !isSessionUsable(session)) return;

            List<Music> queue = playList;
            // 只有队尾就是正在播的这一首时才需要预备；已经有下一首就什么都不做。
            if (queue == null || curIdx < 0 || curIdx + 1 != queue.size()) return;

            long now = System.currentTimeMillis();
            if (now < nextFmPrefetchAt) return;

            long total = (long) activePlayer.getTotalTimeMillis();
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (total <= 0L) return;

            // 播够一段时间，或者已经逼近尾声、再不预备就赶不上了。以先到者为准，和 arm 的判定同构。
            long lead = AutomixSettings.getOverlapMillis() + AutomixSettings.ARM_LEAD_MILLIS;
            if (position < FM_PREFETCH_AFTER_MILLIS && total - position > lead) return;

            fmPrefetchAttempts++;
            nextFmPrefetchAt = now + FM_PREFETCH_RETRY_MILLIS;
            PersonalFmManager.prefetchNextAsync(queue);
        }

        /**
         * Starts resolving and decoding the next track. This deliberately begins very early in the
         * current track rather than a few seconds before its end: a third-party source may have to be
         * resolved, downloaded, transcoded and only then decoded, which regularly takes longer than the
         * overlap itself. An arm that is not ready in time is not a blend, so being early is the whole
         * feature. A failed attempt is retried a couple of times behind a cooldown, because one
         * flaky URL should not silently disable the blend for the rest of the track.
         */
        private void maybeArmNextTrack(AudioPlayer activePlayer) {
            long revision = CloudMusic.queueRevision;
            if (revision != seenQueueRevision) {
                // The user changed what plays next. Whatever was armed decoded the wrong track, and
                // the attempt budget belongs to the old prediction, so both are reset.
                seenQueueRevision = revision;
                armAttempts = 0;
                nextArmAt = 0L;
                cancelArmedTrack();
            }
            if (armed != null || arming) return;
            if (armAttempts >= MAX_ARM_ATTEMPTS) return;
            if (!AutomixSettings.isEnabled() || awaitingPlaybackAction) return;
            if (activePlayer == null || !isSessionUsable(session)) return;

            long now = System.currentTimeMillis();
            if (now < nextArmAt) return;

            long total = (long) activePlayer.getTotalTimeMillis();
            long position = (long) activePlayer.getCurrentTimeMillis();
            if (total <= 0L) return;

            long lead = AutomixSettings.getOverlapMillis() + AutomixSettings.ARM_LEAD_MILLIS;
            // Either the track has settled into playback, or it is close enough to the end that the
            // arm can no longer wait. Whichever comes first.
            if (position < EARLY_ARM_MILLIS && total - position > lead) return;

            int nextIndex = peekNextIndex();
            if (nextIndex < 0 || nextIndex >= playList.size()) {
                armAttempts = MAX_ARM_ATTEMPTS;
                return;
            }
            Music nextSong = playList.get(nextIndex);
            if (nextSong == null) {
                armAttempts = MAX_ARM_ATTEMPTS;
                return;
            }

            armAttempts++;
            nextArmAt = now + ARM_RETRY_MILLIS;
            arming = true;
            final long armGeneration = generation.get();
            final Music song = nextSong;
            final int index = nextIndex;
            final long armRevision = revision;
            // 低优先级池：这条链是整首歌解码 + 若干次 ffmpeg 分析窗口，是本项目最重的后台活。
            // 用户并没有在等它（等不到就退回普通切歌），所以绝不该和渲染线程争 CPU。
            BackgroundWork.submit(() -> armTrack(song, index, armGeneration, armRevision));
        }

        /** Resolve, download, decode and hold the next track. Runs off the playback thread. */
        private void armTrack(Music song, int index, long armGeneration, long armRevision) {
            AudioPlayer deck = null;
            try {
                if (!armStillValid(armGeneration, armRevision)) return;

                Tuple<String, String> playUrl = song.getPlayUrl();
                if (playUrl == null || !armStillValid(armGeneration, armRevision)) return;

                File file = getMusicFile(playUrl, song);
                if (file == null || !file.isFile() || !armStillValid(armGeneration, armRevision)) return;

                // A second live deck doubles the resident PCM, so an oversized track is never armed.
                long limit = AutomixSettings.maxDeckBytes();
                if (file.length() > limit) {
                    System.out.println("[Automix] skipping arm for " + song.getName()
                            + ": decoded size " + PlaybackMemoryLimits.megabytes(file.length())
                            + "MB exceeds the " + PlaybackMemoryLimits.megabytes(limit) + "MB deck budget");
                    return;
                }

                deck = new AudioPlayer(file);
                deck.setVolume(CloudMusic.getVolume());
                deck.setFadeGain(0.0f);
                // Third-party search results carry no duration; the decoder knows the real one now.
                song.applyDecodedDuration((long) deck.getTotalTimeMillis());
                if (!armStillValid(armGeneration, armRevision)) return;

                ArmedTrack track = new ArmedTrack(song, file, deck, index, armGeneration, armRevision);
                track.plan = AutomixPlanner.plan(ownedPlayer, deck,
                        estimateLastVocalEndMillis(ownedPlayer));
                if (track.plan == null || !armStillValid(armGeneration, armRevision)) return;

                // Everything a start needs except making a sound, done here on the arming thread:
                // cue the reader, wire the low shelf, pull the first streaming window off the disk.
                track.preRollMillis = Math.min(PRE_ROLL_MILLIS, Math.max(0L, track.plan.incomingCueMillis));
                deck.setBassGainDb(track.plan.incomingBassDb(0.0));
                // Before prepareStart, so the chain picks both values up without slewing: a pre-rolled
                // deck is already rendering blocks (silently) well before the seam, and a duck that only
                // starts sliding in at the handover would let the first syllable through.
                deck.setVocalDuckDepth(track.plan.incomingVocalDepth(0.0));
                deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);
                if (!armStillValid(armGeneration, armRevision)) return;

                armed = track;
                deck = null;   // ownership handed to the armed track
                System.out.println("[Automix] armed " + song.getName() + " · " + track.plan.summary);
                DownloadDynamicIsland.showAutomixArmed(song.getName());
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to arm " + (song == null ? "next track" : song.getName())
                        + ": " + failure.getMessage());
            } finally {
                if (deck != null) closeQuietly(deck);
                arming = false;
            }
        }

        /**
         * End of the currently playing track's last lyric line, or {@code -1} when it has no timed
         * lyrics. Mixing out after the last word is what keeps two sets of vocals from colliding.
         */
        private long estimateLastVocalEndMillis(AudioPlayer outgoing) {
            try {
                Music current = currentlyPlaying;
                if (current == null) return -1L;
                // The lyric list survives a failed load, so it may still describe the previous track.
                // Using those timestamps would place the mix-out by the wrong song's structure.
                String owner = CloudMusic.lyricsTrackKey;
                if (owner == null || !owner.equals(current.getStableKey())) return -1L;

                List<LyricLine> lines = CloudMusic.lyrics;
                if (lines == null || lines.isEmpty()) return -1L;
                for (int index = lines.size() - 1; index >= 0; index--) {
                    LyricLine line = lines.get(index);
                    if (line == null || line.lyric == null || line.lyric.trim().isEmpty()) continue;
                    // A plain LRC line carries no duration; assume a short sung phrase.
                    long span = line.duration > 0L ? line.duration : 2_500L;
                    long end = line.timestamp + span;
                    if (end <= 0L) return -1L;
                    // Lyrics that run past the end of the audio belong to a different edit of the song.
                    long total = outgoing == null ? 0L : (long) outgoing.getTotalTimeMillis();
                    if (total > 0L && end > total) return -1L;
                    return end;
                }
                return -1L;
            } catch (Throwable ignored) {
                // Lyrics are replaced concurrently by the loader; missing them is not an error.
                return -1L;
            }
        }

        /**
         * Starts the armed deck at full silence shortly before the planned handover, so the seam
         * itself only moves gain and nothing has to be allocated, cued, rewired or read from disk
         * while both decks are being rendered. This mirrors what the reference implementation does
         * with its arm/release split, adapted to a sample-reader engine.
         *
         * <p>The silent deck is kept in step with the pause state: left running it would sail past
         * the cue point the plan aligned to a bar line while the listener has playback stopped.</p>
         */
        private void maybePreRollArmedDeck(AudioPlayer activePlayer) {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null) return;
            if (activePlayer == null || !activePlayer.isUsable()) return;

            AudioPlayer deck = track.deck;
            boolean playbackPaused = activePlayer.isPausing() || !playing.get();
            if (track.preRolled) {
                if (playbackPaused && !track.preRollPaused) {
                    if (!deck.isPausing()) deck.pause();
                    track.preRollPaused = true;
                } else if (!playbackPaused && track.preRollPaused) {
                    deck.startPrepared();
                    track.preRollPaused = false;
                }
                // A seek backwards moves the handover far away again while this deck keeps running:
                // by the time it finally fires it would enter the song at the wrong place, or have
                // played itself out silently. Put it back on its cue and pre-roll again later.
                long current = (long) activePlayer.getCurrentTimeMillis();
                if (deck.isFinished() || current + PRE_ROLL_REWIND_SLACK_MILLIS
                        < track.plan.fireMillis - track.preRollMillis) {
                    rearmPreRoll(track, deck);
                }
                return;
            }
            if (playbackPaused || !deck.isPrepared()) return;
            // Nothing to pre-roll into: the incoming track enters at (or almost at) its own start, so
            // running it early would swallow the first moments of the song. It still starts warm.
            if (track.preRollMillis <= 0L) return;
            if (!armStillValid(track.generation, track.queueRevision)) return;

            long position = (long) activePlayer.getCurrentTimeMillis();
            if (position < track.plan.fireMillis - track.preRollMillis) return;

            try {
                deck.setVolume(CloudMusic.getVolume());
                deck.setFadeGain(0.0f);
                deck.setBassGainDb(track.plan.incomingBassDb(0.0));
                deck.startPrepared();
                track.preRolled = true;
            } catch (Throwable failure) {
                System.err.println("[Automix] pre-roll failed for " + track.song.getName() + ": "
                        + failure.getMessage());
            }
        }

        /** Puts an already pre-rolled deck back to silence at its cue so it can be pre-rolled again. */
        private void rearmPreRoll(ArmedTrack track, AudioPlayer deck) {
            try {
                if (!deck.isPausing()) deck.pause();
                deck.setFadeGain(0.0f);
                deck.prepareStart(track.plan.incomingCueMillis - track.preRollMillis);
            } catch (Throwable failure) {
                System.err.println("[Automix] unable to re-cue the pre-rolled deck for "
                        + track.song.getName() + ": " + failure.getMessage());
            } finally {
                track.preRolled = false;
                track.preRollPaused = false;
            }
        }
        /**
         * Whether a user-pressed "next" can be served by the armed deck instead of the ordinary
         * close/resolve/download/decode switch. Called from the interface thread, so it only inspects
         * state and raises a flag - nothing here touches {@code player}, {@code session} or the queue.
         *
         * @return whether the request was accepted; {@code false} means the caller must switch normally
         */
        boolean requestManualSkip() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session)) return false;
            // Already asked; the pending request will be served on the next tick.
            if (manualSkipRequested) return false;
            // A blend is still running. Promoting a third deck now would leave two tracks audible
            // against the new one, so this switch has to be the ordinary abrupt one.
            if (activeFade != null) return false;

            AudioPlayer active = ownedPlayer;
            if (active == null || !active.isUsable() || active.isFinished() || active.isPausing()) return false;

            ArmedTrack track = armed;
            if (track == null || track.plan == null || track.deck == null || !track.deck.isUsable()) return false;
            if (!armStillValid(track.generation, track.queueRevision)) return false;
            // The decoded deck has to be exactly the track the queue predicts right now, or a manual
            // "next" would land on a different song than the one the ordinary switch would have played.
            if (track.index != peekNextIndex()) return false;

            manualSkipRequested = true;
            return true;
        }

        /**
         * Serves a pending manual skip. Unlike the automatic path this ignores {@code plan.fireMillis}:
         * the user asked to leave the current track now, so the seam happens on this tick with a short
         * ramp instead of at the planned bar line.
         */
        private boolean tryManualNextHandover(AudioPlayer activePlayer) {
            if (!manualSkipRequested) return false;

            ArmedTrack track = armed;
            boolean usable = activePlayer != null && activePlayer.isUsable() && !activePlayer.isFinished()
                    && !activePlayer.isPausing()
                    && track != null && track.plan != null && track.deck != null
                    && armStillValid(track.generation, track.queueRevision)
                    && track.index >= 0 && track.index < playList.size();
            manualSkipRequested = false;
            if (!usable) {
                // The deck went away in the few milliseconds since the request. The user still pressed
                // "next", so fall back to the ordinary switch rather than doing nothing at all.
                cancelArmedTrack();
                performOrdinaryNextAfterFailedHandover();
                return false;
            }

            if (!performAutomixHandover(activePlayer, track, MANUAL_SKIP_FADE_MILLIS)) {
                performOrdinaryNextAfterFailedHandover();
                return false;
            }
            return true;
        }

        /**
         * The original manual-switch path, run from the playback thread when the seamless one could not
         * be used. Identical to what {@link CloudMusic#next()} does when nothing is armed: attribute the
         * outgoing track, mark the index as changed by a player control, and close the player so the
         * supervision loop starts the next track.
         */
        private void performOrdinaryNextAfterFailedHandover() {
            if (doBreak || isPlaybackCancelled() || !isSessionUsable(session)) return;
            if (playList.isEmpty() || player == null || !canPlayNext()) return;
            updatePlayCountIfNeeded();
            prepareForTrackChange();
            curIdx++;
            stopCurrentPlayback();
        }

        /**
         * Fires the handover once the outgoing track reaches the planned point, or immediately if it
         * already ended (a late arm still beats a silent gap).
         */
        private boolean tryAutomixHandover(AudioPlayer activePlayer) {
            ArmedTrack track = armed;
            if (track == null || track.plan == null || activePlayer == null) return false;
            if (!armStillValid(track.generation, track.queueRevision)) {
                cancelArmedTrack();
                return false;
            }
            if (track.index < 0 || track.index >= playList.size()) {
                cancelArmedTrack();
                return false;
            }
            long position = (long) activePlayer.getCurrentTimeMillis();
            boolean ended = activePlayer.isFinished() || !playing.get();
            // A paused track must not hand over: the incoming deck would start sounding on its own
            // while the interface still shows playback as paused.
            if (!ended && activePlayer.isPausing()) return false;
            if (!ended && position < track.plan.fireMillis) return false;
            return performAutomixHandover(activePlayer, track);
        }

        /**
         * Promotes the armed deck to the active player: the outgoing track keeps sounding while it fades,
         * and the incoming one takes over the queue index, session, lyrics and history reporting at once.
         */
        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track) {
            return performAutomixHandover(outgoing, track, 0L);
        }

        /**
         * @param overlapOverrideMillis ramp to use instead of the planned overlap, or {@code 0} to keep
         *                              the plan's own length. A user-pressed "next" overrides it: the
         *                              planned blend is measured in bars and reads as sluggish when it
         *                              answers a button press.
         */
        private boolean performAutomixHandover(AudioPlayer outgoing, ArmedTrack track,
                                               long overlapOverrideMillis) {
            armed = null;
            AutomixPlan plan = track.plan;
            AudioPlayer incoming = track.deck;
            if (incoming == null || !incoming.isUsable()) {
                closeQuietly(incoming);
                return false;
            }

            // How much of the outgoing track is actually left decides how long the ramp may be. An
            // arm that only became ready after the planned point - a slow source, a long transcode -
            // would otherwise fade the new track in over four seconds with nothing playing against it,
            // which sounds like a gap followed by a track sneaking in rather than like a blend.
            long remaining = Math.max(0L,
                    (long) outgoing.getTotalTimeMillis() - (long) outgoing.getCurrentTimeMillis());
            long requestedOverlap = overlapOverrideMillis > 0L ? overlapOverrideMillis : plan.overlapMillis;
            long effectiveOverlap = requestedOverlap;
            if (!outgoing.isUsable() || outgoing.isFinished() || remaining < 400L) {
                effectiveOverlap = LATE_FIRE_FADE_MILLIS;
            } else if (remaining < effectiveOverlap) {
                effectiveOverlap = Math.max(LATE_FIRE_FADE_MILLIS, remaining);
            }
            System.out.println("[Automix] handover -> " + track.song.getName() + " · " + plan.summary
                    + (overlapOverrideMillis > 0L ? " (manual skip)" : "")
                    + (plan.outgoingRate(0.5, effectiveOverlap) == 1f && plan.tempoRatio != 1f
                            ? " (rate correction dropped: " + effectiveOverlap + "ms ramp)" : "")
                    + (effectiveOverlap == requestedOverlap
                            ? "" : " (ramp shortened to " + effectiveOverlap + "ms, " + remaining + "ms left)"));

            PlaybackSession nextSession;
            synchronized (PLAYER_STATE_LOCK) {
                if (!isSessionUsable(session) || ownedPlayer != outgoing) {
                    closeQuietly(incoming);
                    return false;
                }
                incoming.setVolume(CloudMusic.getVolume());
                // Set before the deck sounds: it picks the value up without slewing, so the incoming
                // track never gets one loud bass hit in before the swap takes hold.
                incoming.setBassGainDb(plan.incomingBassDb(0.0));
                // Same reason: the canceller slews, so the depth has to be in place before the first
                // block is rendered or the arriving vocal gets a syllable in before the duck engages.
                incoming.setVocalDuckDepth(plan.incomingVocalDepth(0.0));
                incoming.setFadeGain(plan.incomingGain(0.0));
                try {
                    if (track.preRolled) {
                        // Already sounding at full silence since the pre-roll: the gain line above is
                        // the entire handover, which is what makes the seam inaudible.
                        if (track.preRollPaused) {
                            incoming.startPrepared();
                            track.preRollPaused = false;
                        }
                    } else if (incoming.isPrepared()) {
                        incoming.startPrepared();
                    } else {
                        incoming.playFrom(plan.incomingCueMillis);
                    }
                } catch (Throwable startFailure) {
                    System.err.println("[Automix] incoming deck failed to start: " + startFailure.getMessage());
                    closeQuietly(incoming);
                    return false;
                }

                // Attributed here rather than before the deck is started: every early return above
                // leaves the outgoing track playing, and the ordinary completion path would then report
                // and count it a second time.
                try {
                    NeteasePlaybackHistoryReporter.finish(outgoing,
                            NeteasePlaybackHistoryReporter.EndReason.COMPLETED);
                } catch (Throwable ignored) {
                }
                if (!dontAdd && playedFrom != null && curIdx >= 0 && curIdx < playList.size()) {
                    try {
                        playList.get(curIdx).updPlayCount(playedFrom, outgoing.getCurrentTimeSeconds());
                    } catch (Throwable ignored) {
                    }
                }

                curIdx = track.index;
                currentlyPlaying = track.song;
                nextSession = CloudMusic.beginNewSession(track.song);
                session = nextSession;
                nextSession.player = incoming;
                nextSession.audioActive = true;
                player = incoming;
                ownedPlayer = incoming;
                fadingOutPlayer = outgoing;
                activeFade = new AutomixFade(outgoing, incoming, plan, effectiveOverlap,
                        System.currentTimeMillis());
                playing.set(true);
            }

            final AudioPlayer callbackPlayer = incoming;
            final PlaybackSession callbackSession = nextSession;
            incoming.setAfterPlayed(() -> {
                if (ownedPlayer == callbackPlayer && isSessionUsable(callbackSession)) {
                    this.notifyWaitLock();
                }
            });

            loadMusicCover(track.song);
            loadDynamicMusicCover(track.song);
            loadLyric(track.song, nextSession, track.file);
            NeteasePlaybackHistoryReporter.start(track.song, currentPlaylistContext, incoming);
            DownloadDynamicIsland.showAutomixHandover(track.song.getName());
            // 无缝交接同样是"一首 FM 曲目开始出声"，面板与预备标记都要跟着走。
            if (personalFmActive) PersonalFmManager.noteFmTrackStarted(track.song);
            return true;
        }

        /** Advances the equal-power ramp. Called from the same 10 ms tick that supervises playback. */
        private void driveAutomixFade() {
            AutomixFade fade = activeFade;
            if (fade == null) return;

            long now = System.currentTimeMillis();
            AudioPlayer active = ownedPlayer;
            boolean paused = active != null && active.isUsable() && !active.isFinished() && active.isPausing();
            if (paused) {
                // Hold the ramp instead of letting the wall clock run through the pause, and take the
                // outgoing deck down with the active one - nothing else in the player knows about it.
                fade.startedAt += Math.max(0L, now - fade.tickedAt);
                fade.tickedAt = now;
                if (!fade.outgoingPaused && fade.outgoing != null && fade.outgoing.isUsable()
                        && !fade.outgoing.isPausing()) {
                    fade.outgoing.pause();
                    fade.outgoingPaused = true;
                }
                return;
            }
            if (fade.outgoingPaused && fade.outgoing != null) {
                // Only ever resumed when this fade was the one that paused it: unpause() restarts a
                // deck that stopped on its own from wherever it was cued, which would replay the
                // outgoing track from the beginning.
                fade.outgoing.unpause();
                fade.outgoingPaused = false;
            }
            fade.tickedAt = now;

            double progress = (now - fade.startedAt) / (double) fade.overlapMillis;
            if (fade.incoming != null) {
                fade.incoming.setFadeGain(fade.plan.incomingGain(progress));
                fade.incoming.setBassGainDb(fade.plan.incomingBassDb(progress));
                fade.incoming.setVocalDuckDepth(fade.plan.incomingVocalDepth(progress));
            }
            if (fade.outgoing != null) {
                fade.outgoing.setFadeGain(fade.plan.outgoingGain(progress));
                fade.outgoing.setBassGainDb(fade.plan.outgoingBassDb(progress));
                // Evaluated against the ramp actually running, not the planned one: a shortened ramp
                // must not compress a semitone of tempo lock into a couple of hundred milliseconds.
                // 速度与音高分开给：节奏锁定只动速度（WSOLA，音高不变），整半音吸附只动音高
                // （重采样 + WSOLA 把时长还原）。合成一个数会让 WSOLA 把「移调」当成「变速」。
                fade.outgoing.setSpeedAndPitch(
                        fade.plan.outgoingTempo(progress, fade.overlapMillis),
                        fade.plan.outgoingPitch(progress, fade.overlapMillis));
            }
            if (progress < 1.0) return;

            activeFade = null;
            if (fade.incoming != null) {
                fade.incoming.setFadeGain(1.0f);
                // Flat response and natural tempo for the rest of the track: nothing from the blend
                // may survive into ordinary playback.
                fade.incoming.resetAutomixProcessing();
            }
            releaseFadedDeck(fade.outgoing);
        }

        /** Ends a blend early (cancellation, manual switch) without leaving a deck audible. */
        private void finishAutomixFade() {
            AutomixFade fade = activeFade;
            activeFade = null;
            if (fade == null) return;
            if (fade.incoming != null && fade.incoming == ownedPlayer) {
                fade.incoming.setFadeGain(1.0f);
                fade.incoming.resetAutomixProcessing();
            }
            releaseFadedDeck(fade.outgoing);
        }

        private void releaseFadedDeck(AudioPlayer deck) {
            if (deck == null) return;
            synchronized (PLAYER_STATE_LOCK) {
                if (fadingOutPlayer == deck) fadingOutPlayer = null;
                // Promoted to the active player by a switch that raced this fade: not ours to close.
                if (deck == ownedPlayer || deck == CloudMusic.player) {
                    // It has to be flattened though, or it would keep playing with the blend's bass cut
                    // and bent tempo for the rest of the track.
                    deck.resetAutomixProcessing();
                    return;
                }
            }
            closeQuietly(deck);
        }

        /** Drops an arm that was never fired, releasing its decoded sample. */
        private void cancelArmedTrack() {
            ArmedTrack track = armed;
            armed = null;
            // Nothing left to hand over to, so a pending manual request has to fall back to the
            // ordinary switch instead of waiting for a deck that no longer exists.
            manualSkipRequested = false;
            if (track == null || track.deck == null) return;
            if (track.deck == ownedPlayer || track.deck == CloudMusic.player) return;
            closeQuietly(track.deck);
        }

        private void closeQuietly(AudioPlayer deck) {
            if (deck == null) return;
            try {
                deck.close();
            } catch (Throwable ignored) {
                // A deck that never fully initialised must not block the switch.
            }
        }

        private void loadMusicCover(Music song) {
            CloudMusic.loadMusicCover(song);
        }

        private void updateCurIdx() {
            if (personalFmActive) {
                // FM batches are sequential and never loop, reshuffle, or repeat.
                if (dontAdd) dontAdd = false;
                else curIdx++;
                return;
            }
            if (playMode == PlayMode.LoopSingle) {
                // 单曲循环只影响自然播放结束；用户手动上一首/下一首仍可正常切歌。
                if (dontAdd) {
                    dontAdd = false;
                } else if (hasQueuedNext()) {
                    // A track the user explicitly queued outranks single-track repeat, exactly once:
                    // the queue is consumed, and repeat resumes on whatever lands here next.
                    curIdx++;
                }

                if (curIdx < 0 || curIdx >= playList.size()) {
                    curIdx = 0;
                }
                return;
            }

            boolean changedByPlayerControl = dontAdd;
            dontAdd = false;
            if (!changedByPlayerControl) {
                curIdx++;
            }

            if (playMode == PlayMode.Random) {
                if (curIdx >= playList.size()) {
                    reshuffleRandomQueueForNextCycle(songs);
                    curIdx = 0;
                }
                return;
            }

            if (playMode == PlayMode.LoopInList && curIdx >= playList.size()) {
                curIdx = 0;
            }
        }

        private void sleep(int millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void loadMusicCover(Music music) {
        loadMusicCover(music, false);
    }

    public static void loadMusicCover(Music music, boolean forceReload) {
        MusicCoverService.loadMusicCover(music, forceReload);
    }

    public static void loadDynamicMusicCover(Music music) {
        MusicCoverService.loadDynamicMusicCover(music);
    }

    /**
     * 渲染层用这个决定绑哪张封面:动态封面(网易云 MP4 抽帧)就绪时用它,否则用传入的静态封面。
     * 只有正在播放的曲目会有动态封面,歌单缩略图始终走静态图。
     */
    public static Location preferredCoverLocation(Music music, Location fallback) {
        return MusicCoverService.preferredCoverLocation(music, fallback);
    }

    /**
     * 动态封面所需的 ffmpeg 是否就绪。返回 1 已找到、-1 探测过但没找到、0 尚未探测。
     * 只读缓存结论,可以安全地在渲染线程里调用(HUD 编辑器用它给开关加一行提示)。
     */
    public static int animatedCoverToolingState() {
        return MusicCoverService.ffmpegState();
    }
    public static BufferedImage gaussianBlur(BufferedImage imgIn, int blur) {
        return MusicCoverService.gaussianBlur(imgIn, blur);
    }


    private static void downloadMusic(String playUrl, File music) {
        MusicDownloadService.downloadMusic(playUrl, music);
    }

    public static void writeTo(InputStream src, OutputStream dest) {
        MusicDownloadService.writeTo(src, dest);
    }

    public static void initLyrics(JsonObject rawLyricData, Music music, List<LyricLine> parsedLyrics) {
        resetLyricFlags();
        if (rawLyricData != null) {
            detectTranslations(rawLyricData);
        }
        if (parsedLyrics != null) {
            for (LyricLine line : parsedLyrics) {
                if (line == null) continue;
                if (line.translationText != null && !line.translationText.trim().isEmpty()) hasTransLyrics = true;
                if (line.romanizationText != null && !line.romanizationText.trim().isEmpty()) hasRomanization = true;
            }
        }

        synchronized (lyrics) {
            LyricTimelineSupport.PreparedTimeline timeline = LyricTimelineSupport.prepare(
                    parsedLyrics == null ? Collections.<LyricLine>emptyList() : parsedLyrics);
            lyrics.clear();
            lyrics.addAll(timeline.lines);
            currentLyric = lyrics.get(0);
            haveNoWords = timeline.haveNoWords;
            lyricsTrackKey = music == null ? null : music.getStableKey();
        }

        MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
    }

    /**
     * 两阶段提交的第二阶段：只在 Minecraft 主线程调用，把已验证的歌词结果提交为当前 Timeline。
     * 提交前再做一次 sessionId + songId 双重校验，并用当前 AudioPlayer 的真实 positionMs 对齐
     * currentLyric（歌词可先到或后到，均以声音时钟为准）。
     */
    public static void applyLyricTimeline(PlaybackSession session, JsonObject rawLyricData, List<LyricLine> parsedLyrics) {
        Music current = currentlyPlaying;
        if (!session.isActive() || current == null || !current.getStableKey().equals(session.trackKey)) {
            return;  // 提交瞬间已切歌：丢弃旧歌词，不污染新状态
        }

        initLyrics(rawLyricData, current, parsedLyrics);

        AudioPlayer p = session.player != null ? session.player : player;
        if (p != null) {
            updateCurrentLyric(p.getCurrentTimeMillis());
            // 歌词迟到时按真实 position 立即重新居中，避免先按首句定位再跳变
            MusicLyricsPanel.updateLyricPositionsImmediate(NCMScreen.getInstance().getPanelWidth() * MusicLyricsPanel.getLyricWidthFactor());
        }
    }

    /**
     * 不可变播放快照：渲染一帧只获取一次，本帧歌词、进度条、逐字高亮全部使用同一快照，
     * 避免一帧内跨线程读取到不同歌曲 / 不同 position。
     */
    public static class PlaybackSnapshot {
        public final long sessionId;
        public final long songId;
        public final long positionMs;
        public final long durationMs;
        public final boolean playing;
        public final AudioPlayer player;
        public final Music music;

        public PlaybackSnapshot(long sessionId, long songId, long positionMs, long durationMs, boolean playing, AudioPlayer player, Music music) {
            this.sessionId = sessionId;
            this.songId = songId;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.player = player;
            this.music = music;
        }
    }

    public static PlaybackSnapshot getSnapshot() {
        AudioPlayer p = player;
        Music m = currentlyPlaying;
        PlaybackSession s = currentSession;
        long sid = s == null ? -1L : s.sessionId;
        long songId = m == null ? -1L : m.getId();
        long positionMs = (p == null || m == null) ? 0L : Math.round(p.getCurrentTimeMillis());
        long durationMs = (p == null || m == null) ? 0L : Math.round(p.getTotalTimeMillis());
        boolean playing = (p != null && m != null) && !p.isFinished();
        return new PlaybackSnapshot(sid, songId, positionMs, durationMs, playing, p, m);
    }

    private static void resetLyricFlags() {
        hasTransLyrics = false;
        hasRomanization = false;
    }

    private static void detectTranslations(JsonObject lyric) {
        if (hasLyricsType(lyric, "tlyric") || hasLyricsType(lyric, "ytlrc")) hasTransLyrics = true;
        if (hasLyricsType(lyric, "romalrc") || hasLyricsType(lyric, "yromalrc")) hasRomanization = true;
    }

    private static boolean hasLyricsType(JsonObject lyric, String type) {
        if (lyric.has(type) && lyric.get(type).isJsonObject()) {
            JsonObject lyricTypeObj = lyric.get(type).getAsJsonObject();
            return lyricTypeObj.has("lyric") && !lyricTypeObj.get("lyric").getAsString().isEmpty();
        }
        return false;
    }

    /**
     * 更新当前歌词行
     *
     * @param songProgress 歌曲进度 (ms)
     */
    public static void updateCurrentLyric(float songProgress) {
        LyricLine previousLyric = currentLyric;
        currentLyric = findCurrentLyric(songProgress);

        if (previousLyric != currentLyric) {
            resetLyricPositionUpdate();
        }
    }

    public static LyricLine findCurrentLyric(double songProgress) {
        return LyricTimelineSupport.findCurrentLyric(lyrics, haveNoWords, songProgress);
    }

    public static void resetLyricPositionUpdate() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();
        });
    }

    public static void resetLyricStatus() {
        lyrics.forEach(l -> {
            l.shouldUpdatePosition = false;
            l.delayTimer.reset();

            for (LyricLine.Word word : l.words) {
                Arrays.fill(word.emphasizes, 0);
            }

            l.markDirty();
        });
    }

    public static void setLyricsProgress(float progress) {
        if (lyrics.isEmpty()) return;

        try {
            resetLyricDisplayStates();
            updateCurrentLyric(progress);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void resetLyricDisplayStates() {
        resetAllLyricsState();
        resetWordStates();
    }

    private static void resetAllLyricsState() {
        for (LyricLine lyric : lyrics) {
            lyric.scrollWidth = 0;
            lyric.offsetX = 0;
            lyric.offsetY = Double.MIN_VALUE;
            lyric.targetOffsetX = 0;
        }
    }

    private static void resetWordStates() {
        for (LyricLine lyric : lyrics) {
            for (LyricLine.Word word : lyric.words) {
                word.alpha = 0.0f;
                word.progress = 0.0;
            }
        }
    }

    public static String getSecondaryLyrics(LyricLine lyricLine) {
        if (hasTransLyrics) {
            return getTranslationOrRomanizationText(lyricLine);
        }

        if (hasRomanization) {
            return getRomanizationTextIfEnabled(lyricLine);
        }

        return "";
    }

    private static String getTranslationOrRomanizationText(LyricLine lyricLine) {
        boolean showRoman = MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue();

        if (!showRoman) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
        }

        if (hasRomanization) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }

        return StringUtils.returnEmptyStringIfNull(lyricLine.getTranslationText());
    }

    private static String getRomanizationTextIfEnabled(LyricLine lyricLine) {
        if (MuoniumPlayerExtension.getInstance().musicLyrics.showRoman.getValue()) {
            return StringUtils.returnEmptyStringIfNull(lyricLine.getRomanizationText());
        }
        return "";
    }

    public static boolean hasSecondaryLyrics() {
        boolean hasAvailableLyrics = hasTransLyrics || hasRomanization;
        boolean showTranslationEnabled = MuoniumPlayerExtension.getInstance().musicLyrics.showTranslation.getValue();
        return hasAvailableLyrics && showTranslationEnabled;
    }

    public static void loadLyric(Music music, PlaybackSession session) {
        loadLyric(music, session, null);
    }

    private static void loadLyric(Music music, PlaybackSession session, File playbackFile) {
        final long songId = music.getId();
        final String trackKey = music.getStableKey();
        final File embeddedLyricFile = music.isCloudSong()
                ? LyricLoadService.resolveEmbeddedLyricFile(playbackFile) : null;

        MultiThreadingUtil.runAsync(() -> {
            User currentProfile = profile;
            long profileId = currentProfile == null ? 0L : currentProfile.getId();
            LyricLoadService.LyricLoadResult result = LyricLoadService.load(
                    music, songId, profileId, embeddedLyricFile);

            Music current = currentlyPlaying;
            if (!session.isActive() || current == null || !trackKey.equals(current.getStableKey())
                    || !trackKey.equals(session.trackKey)) {
                return;
            }

            final JsonObject committedJson = result.rawJson;
            final List<LyricLine> committedLyrics = result.lines == null
                    ? Collections.<LyricLine>emptyList() : result.lines;
            session.pendingLyrics = committedLyrics;
            MultiThreadingUtil.runOnMainThread(() -> applyLyricTimeline(session, committedJson, committedLyrics));
        });
    }

    public static String qrCodeLogin() {
        String key = CloudMusic.qrKey();

        QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);

        while (true) {

            if (Thread.currentThread().isInterrupted()) {
                return "";
            }

            JsonObject json = CloudMusicApi.loginQrCheck(key).toJsonObject();

            int code = json.get("code").getAsInt();
            if (code == 800) {
                key = CloudMusic.qrKey();

                QRCodeGenerator.generateAndLoadTexture("https://music.163.com/login?codekey=" + key);
            }

            if (code == 802) {
                if (json.has("nickname")) {
                    NCMScreen.getInstance().loginRenderer.tempUsername = json.get("nickname").getAsString();
                }

                if (json.has("avatarUrl")) {
                    String url = json.get("avatarUrl").getAsString();

                    if (!NCMScreen.getInstance().loginRenderer.avatarLoaded) {
                        NCMScreen.getInstance().loginRenderer.avatarLoaded = true;
                        MultiThreadingUtil.runAsync(() -> {
                            try (InputStream is = HttpUtils.get(url, null)) {
                                BufferedImage img = DynamicTexture.readImage(is);

                                Textures.loadTextureAsyncly(NCMScreen.getInstance().loginRenderer.tempAvatar, img);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }

            if (code == 803) {

                String cookie = json.get("cookie").getAsString();

                String[] split = cookie.split(";");
                StringBuilder sb = new StringBuilder();
                for (String s : split) {
                    if (s.contains("MUSIC_U") || s.contains("__csrf")) {
                        sb.append(s).append("; ");
                    }
                }

                return sb.substring(0, sb.length() - 2);
            }

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static User getUserProfile() {
        return NeteaseAccountRepository.getUserProfile();
    }

    public static List<Music> search(String keyWord) {
        List<Music> cadenceResults = CadenceMusicService.search(keyWord, 50);
        MusicPlatform platform = CadenceMusicService.getCurrentPlatform();
        if (!cadenceResults.isEmpty() || platform == MusicPlatform.QQ || platform == MusicPlatform.GD) {
            return cadenceResults;
        }

        // Cadence 网络失败时仅对网易云保留原 API 兜底，QQ 不可误发到网易云搜索。
        return NeteaseAccountRepository.searchSongs(keyWord);
    }

    public static List<Long> likeList() {
        return NeteaseAccountRepository.loadLikeList(profile);
    }

    public static String qrKey() {
        return NeteaseAccountRepository.qrKey();
    }

}
