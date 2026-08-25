package com.muoniumplayer.core.ncm.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import com.muoniumplayer.core.ncm.DeviceIdGenerator;
import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * Date: 2025/7/2 19:56
 */
@UtilityClass
public class CloudMusicApi {

    /**
     * 用户主页详情。这里只用它读别人的 {@code profile.vipType}。
     *
     * <p>一起听要判断"房里有没有人是非会员"，而房间快照只给 {@code userId / nickname / avatarUrl}，
     * 不带会员信息。与其去赌某个未公开字段，不如按 userId 逐个查公开的用户详情 —— 这条是
     * 确定可用的，第三方客户端显示 VIP 标记走的就是它。</p>
     */
    public RequestUtil.RequestAnswer userDetail(long uid) {
        Map<String, Object> data = new HashMap<>();
        return RequestUtil.createRequest("/api/v1/user/detail/" + uid, data,
                OptionsUtil.createOptions("weapi"));
    }

    // ── 一起听（listen together）─────────────────────────────────────────────
    //
    // 端点与参数按 api-enhanced 的 module/listentogether_*.js 一一对应，走默认 eapi
    // （`status/get` 上游用的是 weapi，这里保持一致）。
    //
    // 上游<b>没有</b>「拉取主机指令」的端点：真实客户端靠一条长连接接收推送。因此本项目里
    // 主机侧是完整的（建房 → 上报 → 心跳 → 结束），客机侧只能主动拉（接受邀请、房间快照、
    // 房间播放列表），不能自动跟随主机的每一次暂停/切歌。

    /** 一起听：创建房间。返回 {@code data.roomInfo.roomId}。 */
    public RequestUtil.RequestAnswer listenTogetherRoomCreate() {
        Map<String, Object> data = new HashMap<>();
        data.put("refer", "songplay_more");
        return RequestUtil.createRequest("/api/listen/together/room/create", data, OptionsUtil.createOptions());
    }

    /**
     * 一起听：房间快照。
     *
     * <p>建房之后上游要求紧跟一次 check，房间才真正进入可邀请状态（参考实现里 create 之后
     * 立刻调它，且忽略返回值）。</p>
     */
    public RequestUtil.RequestAnswer listenTogetherRoomCheck(String roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        return RequestUtil.createRequest("/api/listen/together/room/check", data, OptionsUtil.createOptions());
    }

    /** 一起听：当前账号的房间状态，含 {@code data.inRoom} 与 {@code data.roomInfo.roomUsers}。 */
    public RequestUtil.RequestAnswer listenTogetherStatus() {
        return RequestUtil.createRequest("/api/listen/together/status/get", new HashMap<>(),
                OptionsUtil.createOptions("weapi"));
    }

    /** 一起听：接受邀请加入房间。 */
    public RequestUtil.RequestAnswer listenTogetherAccept(String roomId, long inviterId) {
        Map<String, Object> data = new HashMap<>();
        data.put("refer", "inbox_invite");
        data.put("roomId", roomId);
        data.put("inviterId", inviterId);
        return RequestUtil.createRequest("/api/listen/together/play/invitation/accept", data,
                OptionsUtil.createOptions());
    }

    /** 一起听：结束房间。 */
    public RequestUtil.RequestAnswer listenTogetherEnd(String roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        return RequestUtil.createRequest("/api/listen/together/end/v2", data, OptionsUtil.createOptions());
    }

    /** 一起听：心跳保活。{@code playStatus} 取 {@code PLAY} / {@code PAUSE}，{@code progress} 单位毫秒。 */
    public RequestUtil.RequestAnswer listenTogetherHeartbeat(String roomId, long songId,
                                                            String playStatus, long progressMillis) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("songId", songId);
        data.put("playStatus", playStatus);
        data.put("progress", progressMillis);
        return RequestUtil.createRequest("/api/listen/together/heartbeat", data, OptionsUtil.createOptions());
    }

    /**
     * 一起听：上报一条播放指令。
     *
     * <p>{@code commandInfo} 是<b>一个 JSON 字符串</b>而不是嵌套对象（上游就这么要求），
     * 所以这里手工序列化后再作为普通字段传进去。</p>
     *
     * @param commandType {@code PLAY} / {@code PAUSE} / {@code SEEK} / {@code GOTO}
     * @param clientSeq   客户端自增序号，用于让服务端丢弃过期指令
     */
    public RequestUtil.RequestAnswer listenTogetherPlayCommand(String roomId, String commandType,
                                                              long progressMillis, String playStatus,
                                                              long formerSongId, long targetSongId,
                                                              long clientSeq) {
        JsonObject commandInfo = new JsonObject();
        commandInfo.addProperty("commandType", commandType);
        commandInfo.addProperty("progress", progressMillis);
        commandInfo.addProperty("playStatus", playStatus);
        commandInfo.addProperty("formerSongId", String.valueOf(formerSongId));
        commandInfo.addProperty("targetSongId", String.valueOf(targetSongId));
        commandInfo.addProperty("clientSeq", clientSeq);

        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("commandInfo", commandInfo.toString());
        return RequestUtil.createRequest("/api/listen/together/play/command/report", data,
                OptionsUtil.createOptions());
    }

    /**
     * 一起听：同步房间播放列表。
     *
     * @param commandType 参考实现只用 {@code REPLACE}
     * @param songIds     逗号分隔的网易云 songId，顺序即播放顺序
     */
    public RequestUtil.RequestAnswer listenTogetherSyncList(String roomId, String commandType,
                                                            long userId, long version, String songIds) {
        JsonArray versions = new JsonArray();
        JsonObject versionEntry = new JsonObject();
        versionEntry.addProperty("userId", userId);
        versionEntry.addProperty("version", version);
        versions.add(versionEntry);

        JsonArray ids = new JsonArray();
        for (String id : (songIds == null ? "" : songIds).split(",")) {
            if (!id.trim().isEmpty()) ids.add(id.trim());
        }

        JsonObject param = new JsonObject();
        param.addProperty("commandType", commandType);
        param.add("version", versions);
        param.addProperty("anchorSongId", "");
        param.addProperty("anchorPosition", -1);
        param.add("randomList", ids);
        param.add("displayList", ids);

        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("playlistParam", param.toString());
        return RequestUtil.createRequest("/api/listen/together/sync/list/command/report", data,
                OptionsUtil.createOptions());
    }

    /** 一起听：读取房间当前播放列表。 */
    public RequestUtil.RequestAnswer listenTogetherPlaylistGet(String roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        return RequestUtil.createRequest("/api/listen/together/sync/playlist/get", data,
                OptionsUtil.createOptions());
    }

    /** Requests NetEase dynamic-cover metadata for a signed-in song. */
    public RequestUtil.RequestAnswer songDynamicCover(long id) {
        Map<String, Object> data = new HashMap<>();
        data.put("songId", id);
        return RequestUtil.createRequest("/api/songplay/dynamic-cover", data, OptionsUtil.createOptions());
    }
    public RequestUtil.RequestAnswer lyricNew(long id) {

        Map<String, Object> data = new HashMap<>();

        data.put("id", id);
        data.put("cp", false);
        data.put("tv", 0);
        data.put("lv", 0);
        data.put("rv", 0);
        data.put("kv", 0);
        data.put("yv", 0);
        data.put("ytv", 0);
        data.put("yrv", 0);

        return RequestUtil.createRequest("/api/song/lyric/v1", data, OptionsUtil.createOptions());
    }

    public RequestUtil.RequestAnswer loginStatus() {

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/w/nuser/account/get", new HashMap<>(), OptionsUtil.createOptions("weapi"));

        JsonObject result = request.toJsonObject();

        if (request.getStatus() == 200) {

            JsonObject objResult = new JsonObject();

            objResult.addProperty("status", 200);
            objResult.add("data", request.toJsonObject());
            if (request.getCookies() != null) {
                objResult.addProperty("cookie", String.join(";", request.getCookies()));
            }

            result = objResult;
        }

        return RequestUtil.RequestAnswer.of(result, 200, request.getCookies());
    }

    @SneakyThrows
    public RequestUtil.RequestAnswer cloudSearch(String keyWord, @NonNull SearchType type) {

        Map<String, Object> data = new HashMap<>();

        data.put("s", keyWord);
        data.put("type", type.getId());
        data.put("limit", 100);
        data.put("offset", 0);
        data.put("total", true);

        return RequestUtil.createRequest("/api/cloudsearch/pc", data, OptionsUtil.createOptions());

    }

    /**
     * Fetches lyrics embedded in a NetEase cloud-drive entry.
     * The enhanced API exposes this as /cloud/lyric/get and expects eapi
     * parameters named userId/songId (uid/sid are the public query names).
     */
    public static RequestUtil.RequestAnswer cloudLyricGet(long userId, long songId) {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("songId", songId);
        data.put("lv", -1);
        data.put("kv", -1);
        return RequestUtil.createRequest("/api/cloud/lyric/get", data, OptionsUtil.createOptions("eapi"));
    }

    /** Returns the logged-in user's NetEase cloud-drive entries. */
    public RequestUtil.RequestAnswer userCloudSongs(int limit, int offset) {
        Map<String, Object> data = new HashMap<>();
        data.put("limit", Math.max(1, Math.min(200, limit)));
        data.put("offset", Math.max(0, offset));
        return RequestUtil.createRequest("/api/v1/cloud/get", data, OptionsUtil.createOptions("weapi"));
    }

    public enum SearchType {

        Single(1),
        Album(10),
        Singer(100),
        Playlist(1000),
        User(1002),
        MV(1004),
        Lyric(1006),
        Radio(1009),
        Video(1014),
        All(1018),
        Sound(2000);

        @Getter
        private final int id;

        SearchType(int id) {
            this.id = id;
        }

    }

    /** Fetches a small batch of recommendations for the logged-in personal FM. */
    public RequestUtil.RequestAnswer personalFm() {
        return RequestUtil.createRequest("/api/v1/radio/get", new HashMap<>(), OptionsUtil.createOptions("weapi"));
    }

    /** Fetches a small batch for a selected personal FM mode. */
    public RequestUtil.RequestAnswer personalFmMode(String mode, String submode, int limit) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", mode == null || mode.trim().isEmpty() ? "DEFAULT" : mode.trim());
        if (submode != null && !submode.trim().isEmpty()) data.put("submode", submode.trim());
        data.put("limit", Math.max(1, Math.min(3, limit)));
        return RequestUtil.createRequest("/api/v1/radio/get", data, OptionsUtil.createOptions());
    }

    /** Marks a personal FM track as skipped after an explicit user action. */
    public RequestUtil.RequestAnswer personalFmTrash(long songId) {
        Map<String, Object> data = new HashMap<>();
        data.put("songId", songId);
        data.put("alg", "RT");
        data.put("time", 25);
        return RequestUtil.createRequest("/api/radio/trash/add", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 搜索建议（联想词）。严格对应 api-enhanced 的 {@code module/search_suggest.js}：
     * 端点 {@code /api/search/suggest/web}（或 {@code .../keyword}），weapi，唯一参数是 {@code s}。
     *
     * @param mobile {@code true} 走 {@code keyword} 变体（返回 {@code result.allMatch[].keyword} 纯联想词），
     *               {@code false} 走默认的 {@code web} 变体（返回按 {@code result.order} 分组的
     *               歌曲 / 歌手 / 专辑 / 歌单）
     */
    public RequestUtil.RequestAnswer searchSuggest(String keywords, boolean mobile) {
        Map<String, Object> data = new HashMap<>();
        data.put("s", keywords == null ? "" : keywords);
        return RequestUtil.createRequest("/api/search/suggest/" + (mobile ? "keyword" : "web"), data,
                OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer searchHotDetail() {
        return RequestUtil.createRequest("/api/hotsearchlist/get", new HashMap<>(), OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer topLists() {
        return RequestUtil.createRequest("/api/toplist", new HashMap<>(), OptionsUtil.createOptions());
    }

    /**
     * Stable top-list summary route documented by both supplied API sources.
     */
    public RequestUtil.RequestAnswer topListDetail() {
        return RequestUtil.createRequest("/api/toplist/detail", new HashMap<>(), OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer topListDetailV2() {
        return RequestUtil.createRequest("/api/toplist/detail/v2", new HashMap<>(), OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer topArtists(int areaType) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", Math.max(1, Math.min(4, areaType)));
        data.put("limit", 100);
        data.put("offset", 0);
        data.put("total", true);
        return RequestUtil.createRequest("/api/toplist/artist", data, OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer digitalAlbumPurchased(int limit, int offset) {
        Map<String, Object> data = new HashMap<>();
        data.put("limit", Math.max(1, Math.min(100, limit)));
        data.put("offset", Math.max(0, offset));
        data.put("total", true);
        return RequestUtil.createRequest("/api/digitalAlbum/purchased", data, OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer album(long albumId) {
        return RequestUtil.createRequest("/api/v1/album/" + albumId, new HashMap<>(), OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer albumPrivilege(long albumId) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", albumId);
        return RequestUtil.createRequest("/api/album/privilege", data, OptionsUtil.createOptions());
    }


    public RequestUtil.RequestAnswer eventList(int pageSize, long lastTime) {
        Map<String, Object> data = new HashMap<>();
        data.put("pagesize", Math.max(1, Math.min(50, pageSize)));
        data.put("lasttime", lastTime <= 0 ? -1 : lastTime);
        return RequestUtil.createRequest("/api/v1/event/get", data, OptionsUtil.createOptions("weapi"));
    }

    public RequestUtil.RequestAnswer recentSongs(int limit) {
        Map<String, Object> data = new HashMap<>();
        data.put("limit", Math.max(1, Math.min(100, limit)));
        return RequestUtil.createRequest("/api/play-record/song/list", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * Submits a listening record for a song that has actually been played by the local player.
     * The endpoint accepts a NetEase song id, an optional originating playlist id and real played
     * seconds. Callers are responsible for consent, local timing and duplicate prevention.
     */
    public RequestUtil.RequestAnswer reportListeningHistory(long songId, long sourcePlaylistId, int playedSeconds) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", songId);
        data.put("sourceid", Math.max(0L, sourcePlaylistId));
        data.put("time", Math.max(1, playedSeconds));
        return RequestUtil.createRequest("/api/feedback/weblog", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * Validates an imported Cookie without mutating the active account state.  The caller only
     * persists it after the response proves that a NetEase profile is available.
     */
    public RequestUtil.RequestAnswer loginStatusWithCookie(String cookie) {
        RequestUtil.RequestOptions options = RequestUtil.RequestOptions.builder()
                .crypto("weapi")
                .cookie(cookie == null ? "" : cookie.trim())
                .ua("")
                .proxy("")
                .encryptedResponse(null)
                .build();
        return RequestUtil.createRequest("/api/w/nuser/account/get", new HashMap<>(), options);
    }

    public RequestUtil.RequestAnswer likeList(long uid) {

        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);

        return RequestUtil.createRequest("/api/song/like/get", data, OptionsUtil.createOptions());

    }

    public RequestUtil.RequestAnswer loginQrKey() {

        Map<String, Object> data = new HashMap<>();
        data.put("type", 3);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/login/qrcode/unikey", data, OptionsUtil.createOptions());

        JsonObject obj = new JsonObject();
        obj.addProperty("status", 200);
        obj.add("data", request.toJsonObject());
        if (request.getCookies() != null) {
            obj.addProperty("cookie", String.join(";", request.getCookies()));
        }

        return RequestUtil.RequestAnswer.of(obj, 200, request.getCookies());
    }

    public RequestUtil.RequestAnswer loginQrCheck(String key) {

        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("type", 3);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/login/qrcode/client/login", data, OptionsUtil.createOptions());

//        JsonObject obj = new JsonObject();
//        obj.addProperty("status", 200);
//
//        JsonObject objBody = new JsonObject();
//
//        if (request.getStatus() == 200) {
//            JsonObject jsonObject = gson.fromJson(request.toJsonString(), JsonObject.class);
//            jsonObject.addProperty("cookie", String.join(";", request.getCookies()));
//            objBody.add("body", jsonObject);
//        }
//
//        obj.add("body", objBody);
//        obj.addProperty("cookie", String.join(";", request.getCookies()));
//
//        return RequestUtil.RequestAnswer.of(obj, 200, request.getCookies());

        if (request.getCookies() != null) {
            ((Map<String, Object>) request.getBody()).put("cookie", String.join(";", request.getCookies()));

        }

        return request;
    }

    /**
     * Resolves a NetEase Cloud Music playback URL using the currently selected
     * quality. Existing callers keep their FLAC preference through this
     * compatibility overload.
     */
    public RequestUtil.RequestAnswer songUrlV1(long id, String level) {
        return songUrlV1(id, level, "flac");
    }

    /**
     * Resolves a NetEase Cloud Music playback URL for a requested quality and
     * container. Some album tracks do not expose the requested lossless stream
     * even though their standard MP3 stream is playable, so callers can make a
     * controlled MP3 fallback request without changing the selected quality.
     */
    public RequestUtil.RequestAnswer songUrlV1(long id, String level, String encodeType) {
        String requestedLevel = level == null || level.trim().isEmpty() ? "standard" : level.trim();
        String requestedEncodeType = encodeType == null || encodeType.trim().isEmpty()
                ? "mp3" : encodeType.trim();

        Map<String, Object> data = new HashMap<>();
        data.put("ids", "[" + id + "]");
        data.put("level", requestedLevel);
        data.put("encodeType", requestedEncodeType);

        if ("sky".equalsIgnoreCase(requestedLevel)) {
            data.put("immerseType", "c51");
        }

        return RequestUtil.createRequest("/api/song/enhance/player/url/v1", data, OptionsUtil.createOptions("eapi"));
    }

    /**
     * Stable playback fallback used when the selected quality has no playable
     * URL. This mirrors the reference API's standard-MP3 request and is kept
     * separate from the user's normal quality preference.
     */
    public RequestUtil.RequestAnswer songUrlStandardMp3(long id) {
        return songUrlV1(id, "standard", "mp3");
    }

    /**
     * 「音乐是否可用」。严格对应 api-enhanced 的 {@code module/check_music.js}。
     *
     * <p>上游<b>没有</b>专用端点：它就是把取流端点 {@code /api/song/enhance/player/url}（weapi，
     * 参数只有 {@code ids} 与 {@code br}）请一遍，再看返回体里<b>这一首</b>自己的 {@code code}。
     * 所以判定条件是 {@code body.code == 200 && body.data[0].code == 200}，
     * 见 {@link #isMusicPlayable(long)}。</p>
     *
     * @param br 码率，默认 999000（与参考实现一致）；传 &lt;= 0 时按默认值
     */
    public RequestUtil.RequestAnswer checkMusic(long id, int br) {
        Map<String, Object> data = new HashMap<>();
        data.put("ids", "[" + id + "]");
        data.put("br", br <= 0 ? 999000 : br);
        return RequestUtil.createRequest("/api/song/enhance/player/url", data,
                OptionsUtil.createOptions("weapi"));
    }

    /**
     * 这一首在当前账号下能不能放。
     *
     * <p>请求失败、返回体不合规一律当作「不可用」——调用方只在官方链路已经取不到流之后才问它，
     * 此时把网络故障和真正的灰色歌都归为「拿不到官方流」不会带来误判损失。</p>
     */
    public boolean isMusicPlayable(long id) {
        try {
            RequestUtil.RequestAnswer answer = checkMusic(id, 999000);
            if (answer == null || answer.getStatus() != 200) return false;
            JsonObject root = answer.toJsonObject();
            if (root == null || !root.has("code") || root.get("code").isJsonNull()) return false;
            if (root.get("code").getAsInt() != 200) return false;
            if (!root.has("data") || !root.get("data").isJsonArray()) return false;
            JsonArray data = root.getAsJsonArray("data");
            if (data.size() == 0 || !data.get(0).isJsonObject()) return false;
            JsonObject first = data.get(0).getAsJsonObject();
            return first.has("code") && !first.get("code").isJsonNull() && first.get("code").getAsInt() == 200;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public RequestUtil.RequestAnswer like(long id, boolean like) {

        Map<String, Object> data = new HashMap<>();
        data.put("alg", "itembased");
        data.put("trackId", id);
        data.put("like", like);
        data.put("time", 3);

        return RequestUtil.createRequest("/api/radio/like", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * Loads all songs in a playlist without putting every track id into one request.
     *
     * <p>The v6 playlist response provides the ordered {@code trackIds} list, but
     * {@code /api/v3/song/detail} rejects or times out on a very large {@code c}
     * payload. Fetching details in bounded batches keeps large playlists reliable
     * while the final response restores the playlist's original song order.</p>
     */
    public RequestUtil.RequestAnswer playlistTrackAll(long id, int s) {
        final int detailBatchSize = 200;

        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("n", 100000);
        data.put("s", s);

        RequestUtil.RequestAnswer v6Detail = RequestUtil.createRequest(
                "/api/v6/playlist/detail", data, OptionsUtil.createOptions());
        JsonObject v6Obj = v6Detail.toJsonObject();
        JsonObject playlist = v6Obj.getAsJsonObject("playlist");
        if (playlist == null) {
            throw new IllegalStateException("playlist detail missing 'playlist' field (status="
                    + v6Detail.getStatus() + ")");
        }

        JsonArray trackIds = playlist.getAsJsonArray("trackIds");
        if (trackIds == null) {
            throw new IllegalStateException("playlist detail missing 'trackIds' field (status="
                    + v6Detail.getStatus() + ")");
        }

        List<Long> orderedIds = new ArrayList<>(trackIds.size());
        for (JsonElement trackId : trackIds) {
            if (trackId != null && trackId.isJsonObject()
                    && trackId.getAsJsonObject().has("id")) {
                orderedIds.add(trackId.getAsJsonObject().get("id").getAsLong());
            }
        }

        Map<Long, JsonObject> songsById = new HashMap<>();
        // Retain the full privilege payload so playlist consumers can show the provider's
        // advertised maximum quality and cloud-drive state, not only fee/payed flags.
        Map<Long, JsonObject> privilegesById = new HashMap<>();
        for (int start = 0; start < orderedIds.size(); start += detailBatchSize) {
            int end = Math.min(start + detailBatchSize, orderedIds.size());
            List<String> batch = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                batch.add("{\"id\":" + orderedIds.get(index) + "}");
            }

            Map<String, Object> detailData = new HashMap<>();
            detailData.put("c", "[" + String.join(",", batch) + "]");
            RequestUtil.RequestAnswer detailAnswer = RequestUtil.createRequest(
                    "/api/v3/song/detail", detailData, OptionsUtil.createOptions());
            JsonObject detailBody = detailAnswer.toJsonObject();
            JsonArray songs = detailBody.getAsJsonArray("songs");
            if (songs == null) {
                throw new IllegalStateException("song detail batch missing 'songs' field (status="
                        + detailAnswer.getStatus() + ")");
            }

            // The endpoint returns licensing details separately from song metadata. Preserve fee/payed
            // on each song before DTO parsing so the GUI can distinguish ordinary, VIP, and digital-album tracks.
            JsonArray privileges = detailBody.getAsJsonArray("privileges");
            if (privileges != null) {
                for (JsonElement privilegeElement : privileges) {
                    if (privilegeElement == null || !privilegeElement.isJsonObject()) continue;
                    JsonObject privilege = privilegeElement.getAsJsonObject();
                    if (privilege.has("id") && !privilege.get("id").isJsonNull()) {
                        privilegesById.put(privilege.get("id").getAsLong(), privilege);
                    }
                }
            }

            for (JsonElement songElement : songs) {
                if (songElement != null && songElement.isJsonObject()) {
                    JsonObject song = songElement.getAsJsonObject();
                    if (song.has("id") && !song.get("id").isJsonNull()) {
                        long songId = song.get("id").getAsLong();
                        JsonObject privilege = privilegesById.get(songId);
                        if (privilege != null) {
                            copyPrivilegeField(privilege, song, "fee");
                            copyPrivilegeField(privilege, song, "payed");
                        }
                        songsById.put(songId, song);
                    }
                }
            }
        }

        JsonArray orderedSongs = new JsonArray();
        JsonArray orderedPrivileges = new JsonArray();
        for (Long songId : orderedIds) {
            JsonObject song = songsById.get(songId);
            if (song != null) {
                orderedSongs.add(song);
                JsonObject privilege = privilegesById.get(songId);
                // Preserve song/privilege index alignment expected by PlayList.queryMusics().
                orderedPrivileges.add(privilege == null ? new JsonObject() : privilege);
            }
        }

        JsonObject result = new JsonObject();
        result.add("songs", orderedSongs);
        result.add("privileges", orderedPrivileges);
        return RequestUtil.RequestAnswer.of(result, v6Detail.getStatus(), v6Detail.getCookies());
    }

    private static void copyPrivilegeField(JsonObject privilege, JsonObject song, String field) {
        if (privilege.has(field) && !privilege.get(field).isJsonNull()) {
            song.add(field, privilege.get(field));
        }
    }

    public RequestUtil.RequestAnswer playlistUpdatePlaycount(long id) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);

        return RequestUtil.createRequest("/api/playlist/update/playcount", data, OptionsUtil.createOptions());
    }

    /**
     * 对歌单中的歌曲执行添加或删除操作。
     *
     * <p>返回真实的 HTTP 响应和服务端响应体，调用方必须同时校验 HTTP 状态与响应中的 {@code code}。
     * 这里不能再把失败包装成 200，否则 UI 会把权限、登录或网络错误错误地提示为成功。</p>
     *
     * @param operation {@code add} 或 {@code del}
     * @param trackId   歌单 Id
     * @param musics    用英文逗号分割的音乐 Id
     */
    public RequestUtil.RequestAnswer playlistTracks(String operation, long trackId, String musics) {
        String[] split = musics.split(",");
        Map<String, Object> data = new HashMap<>();
        data.put("op", operation);
        data.put("pid", trackId);
        data.put("trackIds", JsonUtils.toJsonString(split));
        data.put("imme", "true");

        // 歌单编辑接口使用 weapi 请求链路，与桌面客户端接口的会话校验保持一致。
        return RequestUtil.createRequest(
                "/api/playlist/manipulate/tracks",
                data,
                OptionsUtil.createOptions("weapi")
        );
    }

    private static final int PLAYLIST_TRACK_VERIFICATION_ATTEMPTS = 3;
    private static final long PLAYLIST_TRACK_VERIFICATION_RETRY_DELAY_MILLIS = 250L;

    /**
     * 将单曲加入指定歌单，并在请求完成后重新读取歌单 trackIds。
     *
     * <p>网易云的 manipulate/tracks 接口只代表服务端已接收请求；它不能作为
     * 收藏成功的唯一依据。尤其是用户歌单、权限异常或服务端异步落库时，HTTP 200
     * 并不表示歌曲已经出现在目标歌单中。因此只有在二次读取确认 trackIds 包含
     * 对应歌曲后，才向界面报告成功。</p>
     */
    public PlaylistTrackOperationResult addTrackToPlaylist(long playlistId, long musicId) {
        if (playlistId <= 0L) {
            return new PlaylistTrackOperationResult(false, false, false, 400, -1, "目标歌单 ID 无效");
        }
        if (musicId <= 0L) {
            return new PlaylistTrackOperationResult(false, false, false, 400, -1, "歌曲 ID 无效，无法加入歌单");
        }

        // 先确认歌曲是否已经存在：对“我的歌单”中再次收藏同一首歌的场景给出
        // 明确反馈，同时避免不必要的 manipulate 请求。
        PlaylistTrackVerificationResult before = verifyPlaylistContainsTrack(playlistId, musicId);
        if (before.isVerified() && before.isPresent()) {
            return new PlaylistTrackOperationResult(true, true, true, before.getHttpStatus(), 200,
                    "已确认歌曲已在目标歌单中");
        }

        RequestUtil.RequestAnswer request = playlistTracks("add", playlistId, String.valueOf(musicId));
        JsonObject body = null;
        int apiCode = -1;
        String message = "";

        try {
            body = request.toJsonObject();
            apiCode = getInt(body, "code", -1);
            message = getMessage(body);
        } catch (Exception ignored) {
            // 请求工具在遇到非 JSON 响应时也会保留 HTTP 状态；下面会给出可读的失败提示。
        }

        boolean httpSuccess = request.getStatus() >= 200 && request.getStatus() < 300;
        boolean apiSuccess = apiCode == 200;
        boolean alreadyExists = isAlreadyInPlaylist(message);
        boolean requestAccepted = httpSuccess && apiSuccess;

        // 除了“已存在”外，服务端已明确拒绝请求时无需把它伪装成成功。
        if (!requestAccepted && !alreadyExists) {
            return new PlaylistTrackOperationResult(false, false, false, request.getStatus(), apiCode,
                    resolvePlaylistOperationFailureMessage(request.getStatus(), apiCode, message));
        }

        // 新增后服务端可能有短暂的落库延迟；在后台线程中有限重试，仍然只以
        // trackIds 的实际内容作为结果判断依据。
        PlaylistTrackVerificationResult after = verifyPlaylistContainsTrackWithRetry(playlistId, musicId);
        if (after.isVerified() && after.isPresent()) {
            String confirmedMessage = alreadyExists
                    ? "已确认歌曲已在目标歌单中"
                    : "网易云已确认歌曲已加入目标歌单";
            return new PlaylistTrackOperationResult(true, alreadyExists, true, after.getHttpStatus(), apiCode,
                    confirmedMessage);
        }

        if (after.isVerified()) {
            return new PlaylistTrackOperationResult(false, false, true, after.getHttpStatus(), apiCode,
                    "服务端未确认歌曲已加入目标歌单，请重试");
        }

        String verificationMessage = after.getMessage();
        if (verificationMessage == null || verificationMessage.trim().isEmpty()) {
            verificationMessage = "无法读取目标歌单内容";
        }
        return new PlaylistTrackOperationResult(false, false, false, after.getHttpStatus(), apiCode,
                "请求已发送，但" + verificationMessage + "，未确认收藏成功");
    }

    /**
     * 轻量读取目标歌单的 trackIds。与 playlistTrackAll 不同，此方法不再批量请求
     * 每首歌曲详情，避免大歌单在结果校验阶段额外产生大量网络请求。
     */
    private PlaylistTrackVerificationResult verifyPlaylistContainsTrack(long playlistId, long musicId) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", playlistId);
        data.put("n", 100000);
        data.put("s", 8);

        RequestUtil.RequestAnswer answer = RequestUtil.createRequest(
                "/api/v6/playlist/detail", data, OptionsUtil.createOptions());
        int httpStatus = answer.getStatus();
        boolean httpSuccess = httpStatus >= 200 && httpStatus < 300;
        if (!httpSuccess) {
            return new PlaylistTrackVerificationResult(false, false, httpStatus,
                    "读取目标歌单失败（HTTP " + httpStatus + "）");
        }

        try {
            JsonObject body = answer.toJsonObject();
            int apiCode = getInt(body, "code", 200);
            if (apiCode != 200) {
                return new PlaylistTrackVerificationResult(false, false, httpStatus,
                        "网易云返回错误（代码 " + apiCode + "）");
            }

            JsonObject playlist = body == null ? null : body.getAsJsonObject("playlist");
            JsonArray trackIds = playlist == null ? null : playlist.getAsJsonArray("trackIds");
            if (trackIds == null) {
                return new PlaylistTrackVerificationResult(false, false, httpStatus,
                        "目标歌单响应缺少歌曲列表");
            }

            for (JsonElement element : trackIds) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject track = element.getAsJsonObject();
                if (track.has("id") && !track.get("id").isJsonNull()
                        && track.get("id").getAsLong() == musicId) {
                    return new PlaylistTrackVerificationResult(true, true, httpStatus, "");
                }
            }
            return new PlaylistTrackVerificationResult(true, false, httpStatus, "");
        } catch (Exception ignored) {
            return new PlaylistTrackVerificationResult(false, false, httpStatus,
                    "无法解析目标歌单响应");
        }
    }

    private PlaylistTrackVerificationResult verifyPlaylistContainsTrackWithRetry(long playlistId, long musicId) {
        PlaylistTrackVerificationResult result = null;
        for (int attempt = 0; attempt < PLAYLIST_TRACK_VERIFICATION_ATTEMPTS; attempt++) {
            result = verifyPlaylistContainsTrack(playlistId, musicId);
            if (result.isVerified() && result.isPresent()) {
                return result;
            }
            if (attempt + 1 < PLAYLIST_TRACK_VERIFICATION_ATTEMPTS) {
                try {
                    Thread.sleep(PLAYLIST_TRACK_VERIFICATION_RETRY_DELAY_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result == null
                ? new PlaylistTrackVerificationResult(false, false, 502, "无法读取目标歌单内容")
                : result;
    }

    private String resolvePlaylistOperationFailureMessage(int httpStatus, int apiCode, String serverMessage) {
        if (serverMessage != null && !serverMessage.trim().isEmpty()) {
            return serverMessage.trim();
        }
        if (httpStatus == 401) {
            return "登录状态已失效，请重新登录";
        }
        if (httpStatus == 403) {
            return "没有操作此歌单的权限";
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            return "网络请求失败（HTTP " + httpStatus + "）";
        }
        if (apiCode != -1) {
            return "网易云音乐返回错误（代码 " + apiCode + "）";
        }
        return "服务端返回异常，请稍后重试";
    }

    private int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String getMessage(JsonObject object) {
        if (object == null) {
            return "";
        }
        for (String key : Arrays.asList("message", "msg")) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            try {
                String value = object.get(key).getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private boolean isAlreadyInPlaylist(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("已存在")
                || text.contains("已经存在")
                || text.contains("已收藏")
                || text.contains("already exists")
                || text.contains("already in");
    }

    @Getter
    public static class PlaylistTrackVerificationResult {
        private final boolean verified;
        private final boolean present;
        private final int httpStatus;
        private final String message;

        public PlaylistTrackVerificationResult(boolean verified, boolean present, int httpStatus, String message) {
            this.verified = verified;
            this.present = present;
            this.httpStatus = httpStatus;
            this.message = message;
        }
    }

    @Getter
    public static class PlaylistTrackOperationResult {
        private final boolean success;
        private final boolean alreadyExists;
        /**
         * True only when /api/v6/playlist/detail confirmed the final trackIds state.
         */
        private final boolean verified;
        private final int httpStatus;
        private final int apiCode;
        private final String message;

        public PlaylistTrackOperationResult(boolean success, boolean alreadyExists, int httpStatus, int apiCode, String message) {
            this(success, alreadyExists, false, httpStatus, apiCode, message);
        }

        public PlaylistTrackOperationResult(boolean success, boolean alreadyExists, boolean verified,
                                            int httpStatus, int apiCode, String message) {
            this.success = success;
            this.alreadyExists = alreadyExists;
            this.verified = verified;
            this.httpStatus = httpStatus;
            this.apiCode = apiCode;
            this.message = message;
        }
    }

    public RequestUtil.RequestAnswer userPlaylist(long uid, int limit, int offset) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid", uid);
        data.put("limit", limit);
        data.put("offset", offset);
        data.put("includeVideo", true);

        return RequestUtil.createRequest("/api/user/playlist", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 收藏/取消收藏歌单（原项目无此接口，需新增；复用 weapi 加密与 OptionsUtil 链路）。
     * 网易云歌单收藏接口：POST /weapi/playlist/subscribe 或 /weapi/playlist/unsubscribe，data={id}。
     *
     * @param id  歌单 Id
     * @param sub true=收藏，false=取消收藏
     */
    public RequestUtil.RequestAnswer subscribe(long id, boolean sub) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);

        return RequestUtil.createRequest("/api/playlist/" + (sub ? "subscribe" : "unsubscribe"), data, OptionsUtil.createOptions("weapi"));
    }

    private final String ID_XOR_KEY_1 = "3go8&$8*3*3h0k(2)2";

    private String ncmDllEncodeId(String someId) {
        StringBuilder xoredString = new StringBuilder();

        for (int i = 0; i < someId.length(); i++) {
            char charCode = (char) (someId.charAt(i) ^
                    ID_XOR_KEY_1.charAt(i % ID_XOR_KEY_1.length()));
            xoredString.append(charCode);
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md5.digest(xoredString.toString().getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @SneakyThrows
    public RequestUtil.RequestAnswer registerAnonimous() {
        String deviceId = DeviceIdGenerator.generate();
        RequestUtil.globalDeviceId = deviceId;

        System.out.println("Device ID: " + deviceId);

        String encodedId = Base64.getEncoder().encodeToString(
                (deviceId + " " + ncmDllEncodeId(deviceId)).getBytes(StandardCharsets.UTF_8)
        );

        Map<String, Object> data = new HashMap<>();
        data.put("username", encodedId);

        RequestUtil.RequestAnswer request = RequestUtil.createRequest("/api/register/anonimous", data, OptionsUtil.createOptions("weapi"));
//        System.out.println(request);
        return request;
    }

    public RequestUtil.RequestAnswer songDetail(long id) {
        return songDetail(Collections.singletonList(id));
    }

    public RequestUtil.RequestAnswer songDetail(List<Long> ids) {

        Map<String, Object> data = new HashMap<>();

        StringBuilder sb = new StringBuilder();

        for (Long id : ids) {
            if (sb.length() != 0)
                sb.append(",");
            sb.append("{\"id\":").append(id).append("}");
        }

        data.put("c", "[" + sb + "]");

        return RequestUtil.createRequest("/api/v3/song/detail", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 每日推荐歌单接口
     */
    public RequestUtil.RequestAnswer recommendResource() {
        return RequestUtil.createRequest("/api/v1/discovery/recommend/resource", null, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 发现页推荐歌单。每日推荐接口只会返回少量与账号相关的歌单，不能单独
     * 作为播放器主页的完整数据源，因此用此接口补足主页内容。
     */
    public RequestUtil.RequestAnswer personalizedPlaylists(int limit) {
        Map<String, Object> data = new HashMap<>();
        data.put("limit", Math.max(1, Math.min(limit, 100)));
        data.put("total", true);
        data.put("n", 1000);

        return RequestUtil.createRequest("/api/personalized/playlist", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 热门歌单分页接口。用于在个性化推荐数量不足时继续补页，避免主页永远
     * 只显示每日推荐接口返回的少量内容。
     */
    public RequestUtil.RequestAnswer topPlaylists(int limit, int offset) {
        Map<String, Object> data = new HashMap<>();
        data.put("cat", "全部");
        data.put("order", "hot");
        data.put("limit", Math.max(1, Math.min(limit, 100)));
        data.put("offset", Math.max(0, offset));
        data.put("total", true);

        return RequestUtil.createRequest("/api/playlist/list", data, OptionsUtil.createOptions("weapi"));
    }

    /**
     * 每日推荐歌曲
     */
    public RequestUtil.RequestAnswer recommendSongs() {
        return RequestUtil.createRequest("/api/v3/discovery/recommend/songs", null, OptionsUtil.createOptions("weapi"));
    }

}
