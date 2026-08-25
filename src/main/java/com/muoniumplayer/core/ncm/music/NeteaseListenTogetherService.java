package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网易云「一起听」会话。
 *
 * <p>端点全部来自 {@link CloudMusicApi}（对应 api-enhanced 的 {@code listentogether_*} 九个模块）。
 *
 * <h3>为什么主机完整、客机只能手动</h3>
 * <p>上游<b>没有</b>「拉取主机指令」的 HTTP 端点 —— 官方客户端靠一条长连接接收推送。所以：</p>
 * <ul>
 *   <li><b>主机</b>：建房、成员轮询、心跳、把本地播放状态上报成 PLAY/PAUSE/SEEK/GOTO、
 *       同步播放列表、结束房间，都是完整的；用官方客户端加入你房间的人会真的跟着你听。</li>
 *   <li><b>客机</b>：能接受邀请、能看成员、能拉房间播放列表并在本地播放，但<b>不能</b>自动跟随
 *       主机的每一次暂停/切歌 —— 只能点「同步一次」按当前房间快照对齐。这是接口能力的边界，
 *       不是实现偷懒，所以界面上写明了。</li>
 * </ul>
 *
 * <h3>与播放链路的耦合</h3>
 * <p>刻意<b>不往播放路径里插回调</b>：后台线程每秒采样一次 {@link CloudMusic#currentlyPlaying}、
 * {@code player.isPausing()} 与播放进度，靠<b>差分</b>推断出该发哪条指令。这样一起听坏掉、
 * 上游超时、网络断开都不可能拖慢或打断播放本身 —— 播放路径一行都没动。</p>
 */
public final class NeteaseListenTogetherService {

    /** 房间里的一个成员。 */
    public static final class Member {
        public final long userId;
        public final String nickname;
        public final String avatarUrl;

        Member(long userId, String nickname, String avatarUrl) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }
    }

    public enum Role {
        /** 没有会话。 */
        NONE,
        /** 自己建的房间：会把本地播放状态上报出去。 */
        HOST,
        /** 加入别人的房间：只保活与拉取，不上报指令。 */
        GUEST
    }

    public enum Phase {
        IDLE,
        /** 正在建房/加入，界面显示转子。 */
        CONNECTING,
        ACTIVE,
        /** 终态失败，界面显示原因并允许重试。 */
        FAILED
    }

    /** 官方分享链接前缀，客机可以直接粘整条进来。 */
    private static final String SHARE_PREFIX = "https://st.music.163.com/listen-together/share/";

    private static final long TICK_MILLIS = 1000L;
    private static final long STATUS_INTERVAL_MILLIS = 5000L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 20000L;
    /** 进度与推算值差这么多才算一次拖动，低于它的是正常播放漂移。 */
    private static final long SEEK_TOLERANCE_MILLIS = 2500L;

    private static final Object LOCK = new Object();
    private static final AtomicLong CLIENT_SEQ = new AtomicLong(1L);

    private static volatile Role role = Role.NONE;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String roomId = "";
    private static volatile long inviterId;
    private static volatile String statusText = "";
    private static volatile boolean statusIsError;
    private static volatile List<Member> members = Collections.emptyList();

    /**
     * userId → vipType 缓存。会员状态不会在一局游戏里变来变去，所以查到就一直用，
     * 不设过期；查失败的不入缓存，下一轮成员刷新会再试一次。
     */
    private static final Map<Long, Integer> VIP_TYPES = new ConcurrentHashMap<Long, Integer>();

    /** 正在查的 userId，避免 5 秒一轮的成员轮询把同一个人反复查。 */
    private static final Set<Long> VIP_LOOKUPS = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    /**
     * 已确认是非会员的成员昵称（不含自己）。
     *
     * <p>只有<b>确认</b>才会进这个列表：查不到会员状态时它保持为空，于是跳过逻辑不生效 ——
     * 宁可退回"各放各的"，也不要因为一次网络失败就把房主的歌跳掉。</p>
     */
    private static volatile List<String> nonVipNames = Collections.emptyList();
    private static volatile Thread worker;
    private static volatile long generation;

    // 差分状态：只有后台线程读写。
    private static long lastSongId;
    private static boolean lastPaused = true;
    private static long lastProgressMillis;
    private static long lastProgressSampleNanos;
    private static String lastSyncedIds = "";
    private static long lastStatusMillis;
    private static long lastHeartbeatMillis;

    private NeteaseListenTogetherService() {
    }

    // ── 对外状态 ────────────────────────────────────────────────────────────

    /**
     * 一起听是否可用。
     *
     * <p>严格限定在<b>网易云官方源且已登录</b>：QQ / 酷狗 / GD 聚合源没有对等能力，
     * 房间里的曲目也只能是网易云 songId。</p>
     */
    public static boolean isAvailable() {
        return CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE
                && CadenceMusicService.isLoggedIn(MusicPlatform.NETEASE)
                && CloudMusic.profile != null;
    }

    public static Role getRole() {
        return role;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public static boolean isBusy() {
        return phase == Phase.CONNECTING;
    }

    public static String getRoomId() {
        return roomId;
    }

    public static long getInviterId() {
        return inviterId;
    }

    public static String getStatusText() {
        return statusText;
    }

    public static boolean isStatusError() {
        return statusIsError;
    }

    public static List<Member> getMembers() {
        return members;
    }

    /** 分享链接。{@code songId} 取当前播放的那一首，纯粹是让对方点开时有个封面。 */
    public static String getShareLink() {
        String room = roomId;
        if (room.isEmpty()) return "";
        Music current = CloudMusic.currentlyPlaying;
        long songId = current != null && current.isNetease() ? current.getId() : 0L;
        return SHARE_PREFIX + "?songId=" + songId + "&roomId=" + room + "&inviterId=" + inviterId;
    }

    /** 手工输入用的短码：{@code roomId#inviterId}。 */
    public static String getInviteCode() {
        return roomId.isEmpty() ? "" : roomId + "#" + inviterId;
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /** 建房。UI 只管调用，结果通过 {@link #getPhase()} / {@link #getStatusText()} 读。 */
    public static void createRoom() {
        if (!beginTransition("正在创建房间…")) return;
        final long token = generation;
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject created = CloudMusicApi.listenTogetherRoomCreate().toJsonObject();
                String newRoomId = text(deepFind(created, "roomId"));
                if (newRoomId.isEmpty()) {
                    throw new IllegalStateException(message(created, "创建房间失败"));
                }
                // 参考实现在 create 之后紧跟一次 check，房间才真正进入可邀请状态。
                try {
                    CloudMusicApi.listenTogetherRoomCheck(newRoomId);
                } catch (Throwable ignored) {
                }

                User profile = CloudMusic.profile;
                synchronized (LOCK) {
                    if (token != generation) return;
                    roomId = newRoomId;
                    inviterId = profile == null ? 0L : profile.getId();
                    role = Role.HOST;
                }
                refreshMembersQuietly();
                finishTransition(token, "房间已就绪 · 把链接发给朋友即可");
                DownloadDynamicIsland.showNetworkConnectionSuccess("一起听房间 " + newRoomId);
            } catch (Throwable throwable) {
                failTransition(token, "创建房间失败：" + reason(throwable));
            }
        });
    }

    /**
     * 加入房间。
     *
     * @param invite 分享链接、{@code roomId#inviterId} 短码，或只有 roomId（此时需要另给 inviterId）
     */
    public static void joinRoom(String invite, long fallbackInviterId) {
        final String targetRoom = parseRoomId(invite);
        final long targetInviter = parseInviterId(invite, fallbackInviterId);
        if (targetRoom.isEmpty()) {
            statusText = "认不出房间号：请粘贴分享链接，或填 房间号#邀请人ID";
            statusIsError = true;
            phase = Phase.FAILED;
            return;
        }
        if (targetInviter <= 0L) {
            statusText = "缺少邀请人 ID：分享链接里的 inviterId，或用 房间号#邀请人ID";
            statusIsError = true;
            phase = Phase.FAILED;
            return;
        }
        if (!beginTransition("正在加入房间…")) return;
        final long token = generation;
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject accepted = CloudMusicApi.listenTogetherAccept(targetRoom, targetInviter).toJsonObject();
                if (code(accepted) != 200) {
                    throw new IllegalStateException(message(accepted, "上游拒绝了这次加入"));
                }
                synchronized (LOCK) {
                    if (token != generation) return;
                    roomId = targetRoom;
                    inviterId = targetInviter;
                    role = Role.GUEST;
                }
                refreshMembersQuietly();
                finishTransition(token, "已加入房间 · 点「同步一次」对齐播放列表");
                DownloadDynamicIsland.showNetworkConnectionSuccess("一起听房间 " + targetRoom);
            } catch (Throwable throwable) {
                failTransition(token, "加入房间失败：" + reason(throwable));
            }
        });
    }

    /** 退出。主机会顺带关掉房间。 */
    public static void leave() {
        final Role leavingRole;
        final String leavingRoom;
        synchronized (LOCK) {
            leavingRole = role;
            leavingRoom = roomId;
            generation++;
            role = Role.NONE;
            phase = Phase.IDLE;
            roomId = "";
            inviterId = 0L;
            members = Collections.emptyList();
            nonVipNames = Collections.emptyList();
            statusText = "";
            statusIsError = false;
        }
        // vipType 缓存留着：同一个人下次进房间不必再查一次，会员状态不会在一局里变。
        stopWorker();
        if (leavingRole == Role.NONE || leavingRoom.isEmpty()) return;
        MultiThreadingUtil.runAsync(() -> {
            try {
                if (leavingRole == Role.HOST) {
                    CloudMusicApi.listenTogetherEnd(leavingRoom);
                }
            } catch (Throwable ignored) {
                // 关房失败没有可做的补救：本地已经退出，上游房间会自己因为断心跳而过期。
            }
        });
    }

    // ── 客机：手动同步 ──────────────────────────────────────────────────────

    /**
     * 按房间当前快照对齐一次：拉房间播放列表放到本地，并尽量对上当前曲目与进度。
     *
     * <p>「尽量」是因为房间快照的字段名上游没有公开文档，这里用 BFS 找 {@code songId} /
     * {@code progress} / {@code playStatus}；找不到就只同步列表，并在状态行里说清楚。</p>
     */
    public static void syncOnce() {
        if (!isActive() || roomId.isEmpty()) return;
        final long token = generation;
        final String room = roomId;
        statusText = "正在同步房间播放列表…";
        statusIsError = false;
        MultiThreadingUtil.runAsync(() -> {
            try {
                List<Long> ids = roomTrackIds(room);
                if (ids.isEmpty()) {
                    publish(token, "房间还没有同步过播放列表", false);
                    return;
                }
                final List<Music> songs = songsByIds(ids);
                if (songs.isEmpty()) {
                    publish(token, "房间列表里的曲目取不到详情", true);
                    return;
                }

                JsonObject snapshot = CloudMusicApi.listenTogetherRoomCheck(room).toJsonObject();
                long currentSongId = number(deepFind(snapshot, "songId"));
                long progressMillis = number(deepFind(snapshot, "progress"));
                int startIndex = 0;
                for (int index = 0; index < songs.size(); index++) {
                    if (songs.get(index).getId() == currentSongId) {
                        startIndex = index;
                        break;
                    }
                }

                final int resolvedIndex = startIndex;
                final boolean aligned = currentSongId > 0L;
                final long seekMillis = progressMillis;
                MultiThreadingUtil.runOnMainThread(() -> {
                    if (token != generation) return;
                    CloudMusic.play(songs, resolvedIndex);
                    if (aligned && seekMillis > 1500L) {
                        // 起播是异步的，进度对齐交给下一次采样；这里只记录目标，避免和起播抢播放器。
                        pendingSeekMillis = seekMillis;
                    }
                });
                publish(token, aligned
                        ? "已对齐房间当前曲目 · 共 " + songs.size() + " 首"
                        : "已同步房间列表（" + songs.size() + " 首）· 房间未提供当前进度", false);
            } catch (Throwable throwable) {
                publish(token, "同步失败：" + reason(throwable), true);
            }
        });
    }

    /** {@link #syncOnce()} 解析到的进度目标，由后台线程在播放器可用后消费一次。 */
    private static volatile long pendingSeekMillis;

    // ── 后台线程 ────────────────────────────────────────────────────────────

    private static void startWorker() {
        synchronized (LOCK) {
            if (worker != null && worker.isAlive()) return;
            final long token = generation;
            Thread thread = new Thread(() -> workerLoop(token), "ListenTogether");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    private static void stopWorker() {
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
    }

    private static void workerLoop(long token) {
        resetDiffState();
        while (token == generation && phase == Phase.ACTIVE) {
            try {
                // 音源被切走就立刻结束会话：一起听的曲目只能是网易云 songId，
                // 留着一个上报别家 id 的会话没有意义，也会让房间里的人听到错的东西。
                if (CadenceMusicService.getCurrentPlatform() != MusicPlatform.NETEASE) {
                    DownloadDynamicIsland.showNetworkConnectionFailure("一起听", "已切换音源，房间已退出");
                    leave();
                    return;
                }

                long now = System.currentTimeMillis();
                if (role == Role.HOST) reportPlaybackDiff();
                if (now - lastHeartbeatMillis >= HEARTBEAT_INTERVAL_MILLIS) {
                    lastHeartbeatMillis = now;
                    sendHeartbeat();
                }
                if (now - lastStatusMillis >= STATUS_INTERVAL_MILLIS) {
                    lastStatusMillis = now;
                    pollStatus(token);
                }
                consumePendingSeek();
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable throwable) {
                // 单次失败不结束会话：上游偶发超时很常见，界面只更新状态行。
                publish(token, "上游暂时无响应：" + reason(throwable), true);
                try {
                    Thread.sleep(TICK_MILLIS * 3L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void resetDiffState() {
        lastSongId = 0L;
        lastPaused = true;
        lastProgressMillis = 0L;
        lastProgressSampleNanos = System.nanoTime();
        lastSyncedIds = "";
        lastStatusMillis = 0L;
        lastHeartbeatMillis = 0L;
    }

    private static void consumePendingSeek() {
        long target = pendingSeekMillis;
        if (target <= 0L) return;
        AudioPlayer player = CloudMusic.player;
        if (player == null || !player.isUsable()) return;
        pendingSeekMillis = 0L;
        MultiThreadingUtil.runOnMainThread(() -> {
            AudioPlayer active = CloudMusic.player;
            if (active != null && active.isUsable()) active.setPlaybackTime(target);
        });
    }

    /**
     * 采样本地播放状态并把变化上报成一起听指令。
     *
     * <p>顺序有意为之：GOTO 必须先于 PLAY/PAUSE —— 换歌那一帧暂停状态往往也跟着变，
     * 先发状态再发换歌会让对方在旧歌上暂停一下。</p>
     */
    private static void reportPlaybackDiff() {
        Music current = CloudMusic.currentlyPlaying;
        AudioPlayer player = CloudMusic.player;
        long songId = current != null && current.isNetease() ? current.getId() : 0L;
        boolean paused = player == null || player.isPausing();
        long progress = player == null ? 0L : (long) player.getCurrentTimeMillis();

        long nowNanos = System.nanoTime();
        long elapsed = Math.max(0L, (nowNanos - lastProgressSampleNanos) / 1_000_000L);
        long expected = lastPaused ? lastProgressMillis : lastProgressMillis + elapsed;
        lastProgressSampleNanos = nowNanos;

        if (songId <= 0L) {
            lastProgressMillis = progress;
            return;
        }

        if (songId != lastSongId) {
            sendCommand("GOTO", progress, paused, lastSongId, songId);
            lastSongId = songId;
            lastPaused = paused;
            lastProgressMillis = progress;
            syncQueueIfChanged();
            return;
        }
        if (paused != lastPaused) {
            sendCommand(paused ? "PAUSE" : "PLAY", progress, paused, songId, songId);
            lastPaused = paused;
            lastProgressMillis = progress;
            return;
        }
        if (Math.abs(progress - expected) > SEEK_TOLERANCE_MILLIS) {
            sendCommand("SEEK", progress, paused, songId, songId);
        }
        lastProgressMillis = progress;
        syncQueueIfChanged();
    }

    /** 本地待播列表变了就整表 REPLACE 一次。只比较 id 串，避免每秒都发请求。 */
    private static void syncQueueIfChanged() {
        List<Music> queue = new ArrayList<>(CloudMusic.playList);
        StringBuilder builder = new StringBuilder();
        for (Music music : queue) {
            if (music == null || !music.isNetease() || music.getId() <= 0L) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(music.getId());
        }
        String ids = builder.toString();
        if (ids.isEmpty() || ids.equals(lastSyncedIds)) return;
        lastSyncedIds = ids;
        User profile = CloudMusic.profile;
        try {
            CloudMusicApi.listenTogetherSyncList(roomId, "REPLACE",
                    profile == null ? 0L : profile.getId(), CLIENT_SEQ.getAndIncrement(), ids);
        } catch (Throwable throwable) {
            // 列表同步失败不影响播放指令，下一次变化会再试一遍。
            lastSyncedIds = "";
        }
    }

    private static void sendCommand(String commandType, long progressMillis, boolean paused,
                                    long formerSongId, long targetSongId) {
        try {
            CloudMusicApi.listenTogetherPlayCommand(roomId, commandType, Math.max(0L, progressMillis),
                    paused ? "PAUSE" : "PLAY", formerSongId, targetSongId, CLIENT_SEQ.getAndIncrement());
        } catch (Throwable ignored) {
            // 单条指令丢了不要紧：心跳里也带着 songId / playStatus / progress。
        }
    }

    private static void sendHeartbeat() {
        Music current = CloudMusic.currentlyPlaying;
        AudioPlayer player = CloudMusic.player;
        long songId = current != null && current.isNetease() ? current.getId() : 0L;
        boolean paused = player == null || player.isPausing();
        long progress = player == null ? 0L : (long) player.getCurrentTimeMillis();
        try {
            CloudMusicApi.listenTogetherHeartbeat(roomId, songId, paused ? "PAUSE" : "PLAY",
                    Math.max(0L, progress));
        } catch (Throwable ignored) {
        }
    }

    private static void pollStatus(long token) {
        JsonObject root = CloudMusicApi.listenTogetherStatus().toJsonObject();
        JsonElement inRoom = deepFind(root, "inRoom");
        if (inRoom != null && inRoom.isJsonPrimitive() && !truthy(inRoom)) {
            DownloadDynamicIsland.showNetworkConnectionFailure("一起听",
                    role == Role.HOST ? "房间已结束" : "已离开房间");
            leave();
            return;
        }
        applyMembers(parseMembers(root));
        if (statusIsError) publish(token, defaultActiveStatus(), false);
    }

    private static void refreshMembersQuietly() {
        try {
            applyMembers(parseMembers(CloudMusicApi.listenTogetherStatus().toJsonObject()));
        } catch (Throwable ignored) {
        }
    }

    private static void applyMembers(List<Member> parsed) {
        if (parsed == null || parsed.isEmpty()) return;
        members = Collections.unmodifiableList(parsed);
        recomputeNonVipMembers();
        resolveMemberVipTypesAsync(parsed);
    }

    private static String defaultActiveStatus() {
        return role == Role.HOST
                ? "房间进行中 · 你的播放状态正在同步给房间"
                : "已在房间中 · 点「同步一次」对齐播放列表";
    }

    // ── 状态迁移 ────────────────────────────────────────────────────────────

    private static boolean beginTransition(String text) {
        synchronized (LOCK) {
            if (phase == Phase.CONNECTING) return false;
            generation++;
            phase = Phase.CONNECTING;
            statusText = text;
            statusIsError = false;
        }
        return true;
    }

    private static void finishTransition(long token, String text) {
        synchronized (LOCK) {
            if (token != generation) return;
            phase = Phase.ACTIVE;
            statusText = text;
            statusIsError = false;
        }
        startWorker();
    }

    private static void failTransition(long token, String text) {
        synchronized (LOCK) {
            if (token != generation) return;
            phase = Phase.FAILED;
            role = Role.NONE;
            roomId = "";
            inviterId = 0L;
            statusText = text;
            statusIsError = true;
        }
        DownloadDynamicIsland.showNetworkConnectionFailure("一起听", text);
    }

    private static void publish(long token, String text, boolean error) {
        synchronized (LOCK) {
            if (token != generation) return;
            statusText = text;
            statusIsError = error;
        }
    }

    // ── 解析helpers ─────────────────────────────────────────────────────────

    /** 从分享链接 / {@code roomId#inviterId} / 裸 roomId 里取房间号。 */
    static String parseRoomId(String invite) {
        String value = invite == null ? "" : invite.trim();
        if (value.isEmpty()) return "";
        int roomIndex = value.toLowerCase(Locale.ROOT).indexOf("roomid=");
        if (roomIndex >= 0) {
            String tail = value.substring(roomIndex + "roomid=".length());
            int end = indexOfAny(tail, "&#? ");
            return end < 0 ? tail.trim() : tail.substring(0, end).trim();
        }
        int hash = value.indexOf('#');
        if (hash > 0) return value.substring(0, hash).trim();
        return value.indexOf('/') >= 0 ? "" : value;
    }

    /** 同上，取邀请人 ID；链接与短码里都没有时回落到调用方给的值。 */
    static long parseInviterId(String invite, long fallback) {
        String value = invite == null ? "" : invite.trim();
        int inviterIndex = value.toLowerCase(Locale.ROOT).indexOf("inviterid=");
        if (inviterIndex >= 0) {
            String tail = value.substring(inviterIndex + "inviterid=".length());
            int end = indexOfAny(tail, "&#? ");
            long parsed = parseLong(end < 0 ? tail : tail.substring(0, end));
            if (parsed > 0L) return parsed;
        }
        int hash = value.indexOf('#');
        if (hash >= 0 && hash + 1 < value.length()) {
            long parsed = parseLong(value.substring(hash + 1));
            if (parsed > 0L) return parsed;
        }
        return fallback;
    }

    private static int indexOfAny(String value, String terminators) {
        int best = -1;
        for (int index = 0; index < terminators.length(); index++) {
            int found = value.indexOf(terminators.charAt(index));
            if (found >= 0 && (best < 0 || found < best)) best = found;
        }
        return best;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    // ── 房间成员的会员状态 ──────────────────────────────────────────────────

    /**
     * 房间里是否有<b>已确认</b>的非会员成员（不含自己）。
     *
     * <p>查不到会员状态时返回 {@code false}，跳过逻辑随之失效 —— 这是有意的保守方向。</p>
     */
    public static boolean hasNonVipMember() {
        return !nonVipNames.isEmpty();
    }

    /** 已确认的非会员昵称，给界面显示用。 */
    public static List<String> getNonVipMemberNames() {
        return nonVipNames;
    }

    /** 某个成员是否已确认为非会员。界面上给成员行挂标记用。 */
    public static boolean isMemberNonVip(long userId) {
        Integer vipType = VIP_TYPES.get(userId);
        return vipType != null && vipType <= 0 && userId != selfUserId();
    }

    /**
     * 房主是否应当在起播前跳过这首歌。
     *
     * <p>只针对 {@code fee == 1}（VIP 专享）：非会员拿到的是 30 秒试听，官方客户端在这种情况下
     * 会让整房一起切歌，不跳过就会出现"房主接着放、房员早换歌了"的脱队。</p>
     *
     * <p>其余计费类型<b>一律不跳</b>：{@code fee == 8 / 16} 非会员能完整听（只是音质受限），
     * {@code fee == 4} 是数字专辑 —— 买没买是按人算的、查不到，跳掉会误伤。</p>
     */
    public static boolean shouldHostSkip(Music song) {
        if (song == null) return false;
        if (phase != Phase.ACTIVE || role != Role.HOST) return false;
        if (!ListenTogetherSettings.isSkipVipWhenRoomHasNonVip()) return false;
        if (!song.isNetease() || song.getFee() != 1) return false;
        return hasNonVipMember();
    }

    /** 跳过时给灵动岛用的一句原因。 */
    public static String skipReason() {
        List<String> names = nonVipNames;
        if (names.isEmpty()) return "房内有成员无法完整播放";
        String who = names.size() == 1 ? names.get(0) : names.get(0) + " 等 " + names.size() + " 人";
        return who + " 不是会员，本曲只有试听";
    }

    private static long selfUserId() {
        User profile = CloudMusic.profile;
        return profile == null ? 0L : profile.getId();
    }

    private static void recomputeNonVipMembers() {
        long self = selfUserId();
        List<String> names = new ArrayList<String>();
        for (Member member : members) {
            if (member.userId <= 0L || member.userId == self) continue;
            Integer vipType = VIP_TYPES.get(member.userId);
            if (vipType == null || vipType > 0) continue;
            names.add(member.nickname.isEmpty() ? "用户 " + member.userId : member.nickname);
        }
        nonVipNames = names.isEmpty() ? Collections.<String>emptyList() : Collections.unmodifiableList(names);
    }

    /**
     * 补齐成员的 vipType。
     *
     * <p>房间快照只给 {@code userId / nickname / avatarUrl}，不带会员信息，所以按 userId 逐个查
     * 公开的用户详情。每人只查一次（查成功就进缓存），查失败不入缓存、下一轮再试。</p>
     */
    private static void resolveMemberVipTypesAsync(List<Member> parsed) {
        final long self = selfUserId();
        for (Member member : parsed) {
            final long userId = member.userId;
            if (userId <= 0L || userId == self) continue;
            if (VIP_TYPES.containsKey(userId)) continue;
            if (!VIP_LOOKUPS.add(userId)) continue;
            MultiThreadingUtil.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        JsonObject detail = CloudMusicApi.userDetail(userId).toJsonObject();
                        JsonElement vipType = deepFind(detail, "vipType");
                        if (vipType != null && vipType.isJsonPrimitive()) {
                            VIP_TYPES.put(userId, (int) number(vipType));
                            recomputeNonVipMembers();
                        }
                    } catch (Throwable ignored) {
                        // 查不到就当"不知道"：跳过逻辑不生效，回到各放各的。
                    } finally {
                        VIP_LOOKUPS.remove(userId);
                    }
                }
            });
        }
    }

    private static List<Member> parseMembers(JsonObject root) {
        List<Member> result = new ArrayList<Member>();
        JsonElement users = deepFind(root, "roomUsers");
        if (users == null || !users.isJsonArray()) return result;
        for (JsonElement element : users.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject userObject = item.has("userInfo") && item.get("userInfo").isJsonObject()
                    ? item.getAsJsonObject("userInfo") : item;
            long userId = number(firstOf(userObject, "userId", "id"));
            String nickname = text(firstOf(userObject, "nickname", "nickName", "name"));
            String avatar = text(firstOf(userObject, "avatarUrl", "avatar", "picUrl"));
            if (userId <= 0L && nickname.isEmpty()) continue;
            // 快照里顺手带了会员信息就直接用，省掉一次 user/detail。上游没文档，所以不能指望它在。
            JsonElement vipType = firstOf(userObject, "vipType", "vipRights");
            if (userId > 0L && vipType != null && vipType.isJsonPrimitive()) {
                VIP_TYPES.put(userId, (int) number(vipType));
            }
            result.add(new Member(userId, nickname, avatar));
        }
        return result;
    }

    /** 房间播放列表里的 songId。上游字段名没有公开文档，所以两种形状都认。 */
    private static List<Long> roomTrackIds(String room) {
        JsonObject root = CloudMusicApi.listenTogetherPlaylistGet(room).toJsonObject();
        Set<Long> ordered = new LinkedHashSet<Long>();
        collectIds(deepFind(root, "displayList"), ordered);
        if (ordered.isEmpty()) collectIds(deepFind(root, "randomList"), ordered);
        if (ordered.isEmpty()) collectIds(deepFind(root, "songIds"), ordered);
        return new ArrayList<Long>(ordered);
    }

    private static void collectIds(JsonElement element, Set<Long> out) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) continue;
            long id = item.isJsonObject()
                    ? number(firstOf(item.getAsJsonObject(), "songId", "id"))
                    : number(item);
            if (id > 0L) out.add(id);
        }
    }

    private static List<Music> songsByIds(List<Long> ids) {
        JsonObject root = CloudMusicApi.songDetail(ids).toJsonObject();
        JsonArray songs = root != null && root.has("songs") && root.get("songs").isJsonArray()
                ? root.getAsJsonArray("songs") : null;
        if (songs == null) return Collections.emptyList();
        JsonArray privileges = root.has("privileges") && root.get("privileges").isJsonArray()
                ? root.getAsJsonArray("privileges") : null;

        List<Music> result = new ArrayList<Music>();
        for (int index = 0; index < songs.size(); index++) {
            if (!songs.get(index).isJsonObject()) continue;
            JsonObject songObject = songs.get(index).getAsJsonObject();
            try {
                Music music = JsonUtils.parse(songObject, Music.class);
                if (music == null || music.getId() <= 0L) continue;
                JsonObject privilege = privileges != null && index < privileges.size()
                        && privileges.get(index).isJsonObject()
                        ? privileges.get(index).getAsJsonObject() : null;
                music.applyNeteaseMetadata(songObject, privilege);
                result.add(music);
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /**
     * 广度优先找第一个叫这个名字的字段。
     *
     * <p>一起听的响应上游没有公开结构（{@code data.roomInfo.roomId} 这类嵌套在不同端点里
     * 深度不一样），按路径硬取很容易在某个端点上取空。BFS 而不是 DFS：同名字段总是浅的那个
     * 更可能是想要的。</p>
     */
    private static JsonElement deepFind(JsonObject root, String name) {
        if (root == null) return null;
        Deque<JsonObject> queue = new ArrayDeque<JsonObject>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 512) {
            JsonObject current = queue.poll();
            if (current.has(name) && !current.get(name).isJsonNull()) return current.get(name);
            for (java.util.Map.Entry<String, JsonElement> entry : current.entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull()) continue;
                if (value.isJsonObject()) {
                    queue.add(value.getAsJsonObject());
                } else if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (item != null && item.isJsonObject()) queue.add(item.getAsJsonObject());
                    }
                }
            }
        }
        return null;
    }

    private static JsonElement firstOf(JsonObject object, String... names) {
        if (object == null) return null;
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) return object.get(name);
        }
        return null;
    }

    private static int code(JsonObject root) {
        long value = number(firstOf(root, "code", "status"));
        return value == 0L ? 200 : (int) value;
    }

    private static String message(JsonObject root, String fallback) {
        String value = text(firstOf(root, "message", "msg"));
        return value.isEmpty() ? fallback : value;
    }

    private static String text(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return "";
        return element.getAsString();
    }

    private static long number(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return 0L;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            return primitive.isNumber() ? primitive.getAsLong() : parseLong(primitive.getAsString());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean truthy(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return false;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsLong() != 0L;
        return "true".equalsIgnoreCase(primitive.getAsString());
    }

    private static String reason(Throwable throwable) {
        String value = throwable == null ? null : throwable.getMessage();
        return value == null || value.trim().isEmpty() ? "未知错误" : value.trim();
    }
}