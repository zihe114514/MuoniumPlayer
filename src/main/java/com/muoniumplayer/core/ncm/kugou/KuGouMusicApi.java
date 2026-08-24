package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 酷狗音乐的只读接口客户端。
 *
 * <h3>为什么大部分端点走老网关</h3>
 * <p>酷狗有两套网关：{@code gateway.kugou.com}（新，每个请求都要 Android 签名，并且把设备指纹
 * {@code dfid} 当成风控输入）和 {@code mobilecdn.kugou.com}（老移动端，只读接口免签）。本实现的搜索、
 * 歌单、榜单都走老网关：它不需要先做设备注册（那一步要 AES + RSA 才能拿到真实 {@code dfid}），
 * 少一整条会失效的链路。<b>实测</b>新网关在未注册设备下对 {@code /v3/search/song} 返回
 * {@code error_code=152}，而老网关同一个查询正常。</p>
 *
 * <p>只有两处必须用新网关：扫码登录（{@code login-user.kugou.com}，Web 签名）与登录后的
 * VIP 取流（{@code /v5/url}）。</p>
 *
 * <h3>失败约定</h3>
 * <p>所有方法失败都返回空列表 / {@code null}，不抛异常：一次网络抖动不该把播放器整页打空。
 * 上游的失败是"HTTP 200 + {@code status != 1}"，所以每个方法都要显式检查 {@code status}。</p>
 */
public final class KuGouMusicApi {

    /** 单次列表请求的上限，防止把一个坏参数放大成几千条的请求。 */
    private static final int MAX_PAGE_SIZE = 300;

    private KuGouMusicApi() {
    }

    // ── 数据模型 ────────────────────────────────────────────────────────────

    /** 一首酷狗曲目。三个 hash 分别对应标准 / 320 / 无损，取流时按音质挑一个。 */
    public static final class Track {
        public String hash = "";
        public String hash320 = "";
        public String hashLossless = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String albumId = "";
        public String albumAudioId = "";
        public long durationMillis;
        /** 带 {@code {size}} 占位符的封面模板，取用时替换成具体尺寸。 */
        public String coverTemplate = "";
        public boolean vip;
    }

    /** 一个酷狗歌单/榜单。{@code id} 是 specialid（歌单）或 rankid（榜单）。 */
    public static final class Playlist {
        public String id = "";
        public String name = "";
        public String coverTemplate = "";
        public String description = "";
        public int trackCount;
        /** {@code true} 表示这一项是榜单，曲目要用 {@link #rankSongs} 而不是 {@link #playlistTracks} 取。 */
        public boolean rank;
    }

    /** 取流结果。 */
    public static final class Stream {
        public final String url;
        public final String format;

        Stream(String url, String format) {
            this.url = url;
            this.format = format;
        }
    }

    /** 扫码登录的一次轮询结果。 */
    public static final class QrStatus {
        /** 上游原始码：0 过期、1 等待扫码、2 待确认、4 登录成功。保持不透明，不改名成语义值。 */
        public final int code;
        public final String token;
        public final String userId;
        public final String nickname;
        public final String avatarUrl;

        QrStatus(int code, String token, String userId, String nickname, String avatarUrl) {
            this.code = code;
            this.token = token;
            this.userId = userId;
            this.nickname = nickname;
            this.avatarUrl = avatarUrl;
        }
    }

    // ── 搜索 ────────────────────────────────────────────────────────────────

    public static List<Track> search(String keyword, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(keyword)) return result;
        Map<String, String> params = KuGouHttp.params();
        params.put("format", "json");
        params.put("keyword", keyword.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("showtype", "1");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/search/song", params, KuGouHttp.Sign.NONE);
        for (JsonObject item : items(root)) {
            Track track = parseTrack(item);
            if (track != null) result.add(track);
        }
        return result;
    }

    // ── 歌单 / 榜单 ─────────────────────────────────────────────────────────

    /** 首页推荐歌单（酷狗的「精选歌单」）。匿名可用。 */
    public static List<Playlist> recommendPlaylists(int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        Map<String, String> params = KuGouHttp.params();
        params.put("json", "true");
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        JsonObject root = KuGouHttp.get("http://m.kugou.com", "/plist/index", params, KuGouHttp.Sign.NONE);
        JsonObject list = object(object(object(root, "plist"), "list"), null);
        JsonArray info = list == null ? null : array(list, "info");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.id = text(item, "specialid");
            playlist.name = text(item, "specialname");
            playlist.coverTemplate = text(item, "imgurl");
            playlist.description = text(item, "intro");
            playlist.trackCount = integer(item, "songcount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /** 榜单列表。返回的每一项都是 {@code rank = true}，曲目要走 {@link #rankSongs}。 */
    public static List<Playlist> rankList() {
        List<Playlist> result = new ArrayList<Playlist>();
        Map<String, String> params = KuGouHttp.params();
        params.put("version", "9108");
        params.put("plat", "0");
        params.put("showtype", "2");
        params.put("parentid", "0");
        params.put("apiver", "6");
        params.put("area_code", "1");
        params.put("withsong", "0");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/rank/list", params, KuGouHttp.Sign.NONE);
        for (JsonObject item : items(root)) {
            Playlist playlist = new Playlist();
            playlist.id = text(item, "rankid");
            playlist.name = text(item, "rankname");
            playlist.coverTemplate = firstText(item, "imgurl", "img_cover", "album_img_9", "banner_9");
            playlist.description = text(item, "update_frequency");
            playlist.rank = true;
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /** 歌单曲目（specialid）。 */
    public static List<Track> playlistTracks(String specialId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(specialId)) return result;
        Map<String, String> params = KuGouHttp.params();
        params.put("specialid", specialId.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("version", "9108");
        params.put("with_res_tag", "0");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/special/song", params, KuGouHttp.Sign.NONE);
        for (JsonObject item : items(root)) {
            Track track = parseTrack(item);
            if (track != null) result.add(track);
        }
        return result;
    }

    /** 榜单曲目（rankid）。 */
    public static List<Track> rankSongs(String rankId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(rankId)) return result;
        Map<String, String> params = KuGouHttp.params();
        params.put("version", "9108");
        params.put("ranktype", "1");
        params.put("plat", "2");
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("area_code", "1");
        params.put("rankid", rankId.trim());
        params.put("with_res_tag", "0");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/rank/song", params, KuGouHttp.Sign.NONE);
        for (JsonObject item : items(root)) {
            Track track = parseTrack(item);
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 登录用户自己的歌单（创建 + 收藏）。需要 token；未登录返回空列表。
     *
     * <p>只有这一个列表接口必须走新网关：老网关没有对应端点。请求体参与签名，所以 body 要在
     * 算签名前定下来。</p>
     */
    public static List<Playlist> userPlaylists(int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        if (!KuGouDevice.isLoggedIn()) return result;
        String userId = KuGouDevice.userId();
        String token = KuGouDevice.token();

        JsonObject body = new JsonObject();
        body.addProperty("userid", userId);
        body.addProperty("token", token);
        body.addProperty("total_ver", 979);
        body.addProperty("type", 2);
        body.addProperty("page", 1);
        body.addProperty("pagesize", clampPageSize(limit));

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("plat", "1");
        params.put("userid", userId);
        params.put("token", token);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-router", "cloudlist.service.kugou.com");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v7/get_all_list", params,
                body.toString(), KuGouHttp.Sign.ANDROID, headers);
        if (!isOk(root)) return result;

        JsonObject data = object(root, "data");
        JsonArray info = data == null ? null : array(data, "info");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            // global_collection_id 是新接口的歌单主键；老接口的 specialid 只在部分歌单上有。
            playlist.id = firstText(item, "global_collection_id", "listid", "specialid");
            playlist.name = firstText(item, "name", "listname", "specialname");
            playlist.coverTemplate = firstText(item, "pic", "imgurl", "cover");
            playlist.description = text(item, "intro");
            playlist.trackCount = integer(item, "count", "songcount", "musiccount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 云端歌单（{@code global_collection_id}）的曲目。
     *
     * <p>与 {@link #playlistTracks} 是两条不同的链路：用户自己的歌单主键是
     * {@code global_collection_id}，老网关的 {@code specialid} 端点认不出它。</p>
     */
    public static List<Track> collectionTracks(String globalCollectionId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(globalCollectionId)) return result;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("area_code", "1");
        params.put("begin_idx", "0");
        params.put("plat", "1");
        params.put("type", "1");
        params.put("mode", "1");
        params.put("personal_switch", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("global_collection_id", globalCollectionId.trim());
        if (KuGouDevice.isLoggedIn()) {
            params.put("userid", KuGouDevice.userId());
            params.put("token", KuGouDevice.token());
        }
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY,
                "/pubsongs/v2/get_other_list_file_nofilt", params, KuGouHttp.Sign.ANDROID);
        if (!isOk(root)) return result;
        JsonObject data = object(root, "data");
        JsonArray info = data == null ? null : array(data, "songs", "info", "list");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (!element.isJsonObject()) continue;
            Track track = parseTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    // ── 取流 ────────────────────────────────────────────────────────────────

    /**
     * 解析播放地址。
     *
     * <p>按音质从高到低依次尝试对应的 hash：上游对每个音质是<b>独立授权</b>的，无损拿不到不代表
     * 320 也拿不到，所以逐档回退而不是一次失败就放弃。</p>
     *
     * @param tier 0 标准、1 高品（320）、2 无损
     */
    public static Stream songUrl(Track track, int tier) {
        if (track == null) return null;
        for (String hash : hashesFor(track, tier)) {
            Stream stream = resolveHash(track, hash);
            if (stream != null) return stream;
        }
        return null;
    }

    private static List<String> hashesFor(Track track, int tier) {
        List<String> hashes = new ArrayList<String>();
        if (tier >= 2) addHash(hashes, track.hashLossless);
        if (tier >= 1) addHash(hashes, track.hash320);
        addHash(hashes, track.hash);
        // 高音质请求也把 320 补在最后：tier=2 时上面只加了无损与标准，中间那一档同样值得试。
        addHash(hashes, track.hash320);
        return hashes;
    }

    private static void addHash(List<String> hashes, String hash) {
        if (KuGouSign.isBlank(hash)) return;
        String lower = KuGouSign.lower(hash);
        if (!hashes.contains(lower)) hashes.add(lower);
    }

    /** 老 trackercdn 端点：匿名可用，是主路径。已登录时先试新网关，拿不到再退回这里。 */
    private static Stream resolveHash(Track track, String hash) {
        if (KuGouDevice.isLoggedIn()) {
            Stream authorized = resolveViaGateway(track, hash);
            if (authorized != null) return authorized;
        }
        Map<String, String> params = KuGouHttp.params();
        params.put("key", KuGouSign.playKey(hash));
        params.put("hash", hash);
        params.put("br", "hq");
        params.put("appid", KuGouHttp.APP_ID);
        params.put("pid", "2");
        params.put("cmd", "25");
        params.put("behavior", "play");
        params.put("album_id", KuGouSign.nonNull(track.albumId));
        params.put("album_audio_id", KuGouSign.nonNull(track.albumAudioId));
        JsonObject root = KuGouHttp.get("https://trackercdn.kugou.com", "/i/v2/", params, KuGouHttp.Sign.NONE);
        if (root == null) return null;
        // status=2 表示这一档需要会员/登录，不是网络错误，继续试下一档。
        if (integer(root, "status") != 1) return null;
        String url = firstUrl(array(root, "url", "backupdownurl"));
        if (KuGouSign.isBlank(url)) return null;
        String format = firstText(root, "extName");
        return new Stream(url, KuGouSign.isBlank(format) ? inferFormat(url) : format);
    }

    /** 新网关取流：能拿到会员音质，但必须有 token。 */
    private static Stream resolveViaGateway(Track track, String hash) {
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("userid", KuGouDevice.userId());
        params.put("token", KuGouDevice.token());
        params.put("album_id", KuGouSign.nonNull(track.albumId));
        params.put("area_code", "1");
        params.put("hash", hash);
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", "11430");
        params.put("page_id", "151369488");
        params.put("quality", "128");
        params.put("album_audio_id", KuGouSign.nonNull(track.albumAudioId));
        params.put("behavior", "play");
        params.put("pid", "2");
        params.put("cmd", "26");
        params.put("pidversion", "3001");
        params.put("IsFreePart", "0");
        params.put("ppage_id", "463467626,350369493,788954147");
        params.put("cdnBackup", "1");
        params.put("clientver", KuGouHttp.CLIENT_VER);
        params.put("key", KuGouSign.urlKey(hash, KuGouDevice.mid(), KuGouDevice.userId(), KuGouHttp.APP_ID));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-router", "trackercdn.kugou.com");
        // notSign：这个端点用 key 而不是 signature 校验，多带一个签名反而会被拒。
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v5/url", params, KuGouHttp.Sign.NONE, headers);
        if (root == null || integer(root, "status") != 1) return null;
        JsonArray urls = array(root, "url", "backupdownurl");
        JsonObject data = object(root, "data");
        if (urls == null && data != null) urls = array(data, "url", "backupdownurl");
        String url = firstUrl(urls);
        if (KuGouSign.isBlank(url)) return null;
        String format = firstText(root, "extName");
        return new Stream(url, KuGouSign.isBlank(format) ? inferFormat(url) : format);
    }

    // ── 歌词 ────────────────────────────────────────────────────────────────

    /**
     * 取 KRC 逐字歌词。
     *
     * @param hash          曲目 hash，上游按它精确匹配，命中率最高
     * @param durationMs    时长，用于在多个候选里挑最接近的一份；{@code <= 0} 时不参与打分
     * @param keyword       "歌手 - 歌名"，hash 匹配不到时的兜底检索词
     * @return 永不为 {@code null}；没有逐字内容时 {@code yrc} 为空串
     */
    public static KrcLyrics.Decoded lyric(String hash, long durationMs, String keyword) {
        String content = lyricContent(hash, durationMs, keyword, "krc");
        if (KuGouSign.isBlank(content)) return new KrcLyrics.Decoded("", "");
        return KrcLyrics.decode(content);
    }

    /**
     * 取行级 LRC 歌词，作为 KRC 解不出逐字时的兜底。
     *
     * <p>单独一条而不是从 KRC 降级得来：行级 KRC 会丢掉翻译，而 {@code fmt=lrc} 的那一份自带
     * 上游合并好的译文，作为普通歌词更完整。</p>
     *
     * @return 明文 LRC；拿不到时为空串
     */
    public static String lyricLrc(String hash, long durationMs, String keyword) {
        String content = lyricContent(hash, durationMs, keyword, "lrc");
        if (KuGouSign.isBlank(content)) return "";
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(content.trim());
            if (decoded == null || decoded.length == 0) return "";
            String text = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (Throwable throwable) {
            return "";
        }
    }

    /** 检索候选 → 下载，返回上游 {@code content} 原文（两种 fmt 都是 base64）。 */
    private static String lyricContent(String hash, long durationMs, String keyword, String format) {
        JsonObject candidate = bestLyricCandidate(hash, durationMs, keyword);
        if (candidate == null) return "";
        String id = text(candidate, "id");
        String accessKey = firstText(candidate, "accesskey", "accessKey");
        if (KuGouSign.isBlank(id) || KuGouSign.isBlank(accessKey)) return "";

        Map<String, String> params = KuGouHttp.params();
        params.put("ver", "1");
        params.put("client", "pc");
        params.put("id", id);
        params.put("accesskey", accessKey);
        params.put("fmt", format);
        params.put("charset", "utf8");
        JsonObject root = KuGouHttp.get(KuGouHttp.LYRICS, "/download", params, KuGouHttp.Sign.NONE);
        if (root == null) return "";
        return text(root, "content");
    }

    /** 按 hash 检索候选，失败再按关键词；时长最接近的胜出。 */
    private static JsonObject bestLyricCandidate(String hash, long durationMs, String keyword) {
        JsonArray candidates = searchLyric(hash, null);
        if (candidates == null || candidates.size() == 0) candidates = searchLyric(null, keyword);
        if (candidates == null || candidates.size() == 0) return null;

        JsonObject best = null;
        long bestDelta = Long.MAX_VALUE;
        for (JsonElement element : candidates) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (durationMs <= 0L) return item;
            long candidateDuration = longValue(item, "duration");
            long delta = Math.abs(candidateDuration - durationMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = item;
            }
        }
        return best;
    }

    private static JsonArray searchLyric(String hash, String keyword) {
        if (KuGouSign.isBlank(hash) && KuGouSign.isBlank(keyword)) return null;
        Map<String, String> params = KuGouHttp.params();
        params.put("ver", "1");
        params.put("man", "yes");
        params.put("client", "pc");
        if (!KuGouSign.isBlank(hash)) params.put("hash", KuGouSign.lower(hash));
        if (!KuGouSign.isBlank(keyword)) params.put("keyword", keyword.trim());
        JsonObject root = KuGouHttp.get(KuGouHttp.LYRICS, "/search", params, KuGouHttp.Sign.NONE);
        return root == null ? null : array(root, "candidates");
    }

    // ── 扫码登录 ────────────────────────────────────────────────────────────

    /** 申请一个扫码 key；失败返回空串。二维码内容是 {@link #qrContent(String)}。 */
    public static String createQrKey() {
        Map<String, String> params = KuGouHttp.deviceParams();
        // appid 在这个端点上必须是客户端 appid（1001），不是内容接口用的 1005。
        params.put("appid", "1001");
        params.put("type", "1");
        params.put("plat", "4");
        params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid="
                + KuGouHttp.APP_ID + "&");
        params.put("srcappid", KuGouHttp.SRC_APP_ID);
        JsonObject root = KuGouHttp.get(KuGouHttp.LOGIN_USER, "/v2/qrcode", params, KuGouHttp.Sign.WEB);
        if (!isOk(root)) return "";
        JsonObject data = object(root, "data");
        return data == null ? "" : text(data, "qrcode");
    }

    /** 手机酷狗扫这个 URL 即可确认登录。 */
    public static String qrContent(String key) {
        return "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + KuGouSign.nonNull(key);
    }

    /** 轮询扫码状态。返回 {@code null} 表示这次请求本身失败（网络/协议），调用方应重试而不是判失效。 */
    public static QrStatus checkQr(String key) {
        if (KuGouSign.isBlank(key)) return null;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("plat", "4");
        params.put("srcappid", KuGouHttp.SRC_APP_ID);
        params.put("qrcode", key.trim());
        params.put("dev", "");
        JsonObject root = KuGouHttp.get(KuGouHttp.LOGIN_USER, "/v2/get_userinfo_qrcode", params, KuGouHttp.Sign.WEB);
        if (root == null) return null;
        JsonObject data = object(root, "data");
        if (data == null) return null;
        return new QrStatus(integer(data, "status"), text(data, "token"),
                firstText(data, "userid", "user_id"),
                firstText(data, "nickname", "username", "nickName"),
                firstText(data, "pic", "avatar", "img"));
    }

    // ── 解析辅助 ────────────────────────────────────────────────────────────

    /** {@code data.info} 数组，兼容顶层直接给 {@code info} 的端点。 */
    private static List<JsonObject> items(JsonObject root) {
        List<JsonObject> result = new ArrayList<JsonObject>();
        if (root == null) return result;
        JsonObject data = object(root, "data");
        JsonArray info = data == null ? array(root, "info") : array(data, "info", "lists", "songs");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (element != null && element.isJsonObject()) result.add(element.getAsJsonObject());
        }
        return result;
    }

    /**
     * 把一条曲目 JSON 解析成 {@link Track}。
     *
     * <p>不同端点的字段名不统一（搜索有 {@code songname}/{@code singername}，歌单只有
     * {@code filename}），所以歌名与歌手先读专用字段、读不到再从 {@code "歌手 - 歌名"} 里拆。</p>
     */
    private static Track parseTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(firstText(item, "hash", "FileHash"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(firstText(item, "320hash", "hash_high"));
        track.hashLossless = KuGouSign.lower(firstText(item, "sqhash", "hash_super"));
        track.albumId = firstText(item, "album_id", "AlbumID");
        track.albumAudioId = firstText(item, "album_audio_id", "Audioid", "audio_id");
        track.durationMillis = Math.max(0L, longValue(item, "duration", "timelength", "Duration")) ;
        // 搜索给的是秒，个别端点给毫秒；超过 1 小时的"秒"几乎不存在，按此判定单位。
        if (track.durationMillis > 0L && track.durationMillis < 36000L) track.durationMillis *= 1000L;

        String name = firstText(item, "songname", "SongName", "filename", "FileName", "audio_name");
        String artist = firstText(item, "singername", "SingerName", "author_name");
        if (KuGouSign.isBlank(artist)) artist = authorNames(item);
        String fileName = firstText(item, "filename", "FileName");
        if (KuGouSign.isBlank(artist) && fileName.contains(" - ")) {
            int split = fileName.indexOf(" - ");
            artist = fileName.substring(0, split).trim();
            name = fileName.substring(split + 3).trim();
        } else if (name.contains(" - ") && name.equals(fileName)) {
            name = fileName.substring(fileName.indexOf(" - ") + 3).trim();
        }
        track.name = name;
        track.artist = artist;
        track.album = firstText(item, "album_name", "AlbumName", "remark");
        if (KuGouSign.isBlank(track.name)) return null;

        track.coverTemplate = firstText(item, "album_sizable_cover", "sizable_cover", "img", "imgurl");
        if (KuGouSign.isBlank(track.coverTemplate)) {
            JsonObject transParam = object(item, "trans_param");
            if (transParam != null) track.coverTemplate = firstText(transParam, "union_cover");
        }
        // privilege 10 是最常见的「仅会员」标记；0/8 在实测里都能匿名取到标准音质。
        track.vip = integer(item, "privilege") == 10;
        return track;
    }

    private static String authorNames(JsonObject item) {
        JsonArray authors = array(item, "authors");
        if (authors == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : authors) {
            if (!element.isJsonObject()) continue;
            String name = firstText(element.getAsJsonObject(), "author_name", "name");
            if (KuGouSign.isBlank(name)) continue;
            if (builder.length() > 0) builder.append('、');
            builder.append(name);
        }
        return builder.toString();
    }

    private static boolean isOk(JsonObject root) {
        if (root == null) return false;
        if (root.has("status") && integer(root, "status") != 1) return false;
        return !root.has("error_code") || integer(root, "error_code") == 0;
    }

    private static int clampPageSize(int limit) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, limit <= 0 ? 30 : limit));
    }

    private static String firstUrl(JsonArray urls) {
        if (urls == null) return "";
        for (JsonElement element : urls) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String url = element.getAsString();
            if (!KuGouSign.isBlank(url)) return url.trim();
        }
        return "";
    }

    private static String inferFormat(String url) {
        String clean = KuGouSign.lower(url);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "mp3";
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        if (name == null) return parent;
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String... names) {
        if (parent == null) return null;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        }
        return null;
    }

    private static String text(JsonObject parent, String name) {
        return firstText(parent, name);
    }

    private static String firstText(JsonObject parent, String... names) {
        if (parent == null) return "";
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            String value = element.getAsString();
            if (!KuGouSign.isBlank(value)) return value.trim();
        }
        return "";
    }

    private static int integer(JsonObject parent, String... names) {
        if (parent == null) return 0;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsInt();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static long longValue(JsonObject parent, String... names) {
        if (parent == null) return 0L;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsLong();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    /** 把带 {@code {size}} 的封面模板替换成具体尺寸。 */
    public static String cover(String template, int size) {
        if (KuGouSign.isBlank(template)) return "";
        return template.replace("{size}", String.valueOf(Math.max(64, size)))
                .replace("%7Bsize%7D", String.valueOf(Math.max(64, size)));
    }

    static String lowerLocale(String value) {
        return KuGouSign.nonNull(value).toLowerCase(Locale.ROOT);
    }
}