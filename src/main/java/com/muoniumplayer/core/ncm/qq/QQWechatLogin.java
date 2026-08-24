package com.muoniumplayer.core.ncm.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐的<b>微信</b>扫码登录。
 *
 * <p>这是微信开放平台的网页 OAuth：{@code open.weixin.qq.com} 发一张二维码，确认后长轮询会给出一个
 * OAuth {@code code}，再用它向 QQ 音乐的 musicu 换出 {@code musicid}/{@code musickey}。
 * 它与项目里已有的 QQ 扫码（{@code ssl.ptlogin2.qq.com}，由 Cadence 承载）<b>没有任何共用步骤</b>，
 * 唯一相同的是最后那一次 musicu 调用，而那一次必须带 {@code tmeLoginType = 1}（微信）而不是 QQ 的值 ——
 * 用错这个数字，换出来的凭证在后续每个接口上都会被拒。</p>
 *
 * <p>端点、{@code appid} 与状态码是第三方逆向得到的协议事实（对照
 * {@code yakult-green-tea/qq-music-api} 的 {@code services/auth/wechatLogin.ts} 与
 * {@code qrLogin.ts#exchangeWechatCredential}），不是腾讯官方定义。未识别的上游数字保持不透明，
 * 不映射成"官方"错误含义。</p>
 */
public final class QQWechatLogin {

    /** QQ 音乐在微信开放平台的应用 id。 */
    public static final String WECHAT_APP_ID = "wx48db31d50e334801";

    /** musicu 的 {@code comm.tmeLoginType}：1 = 微信。 */
    private static final int WECHAT_LOGIN_TYPE = 1;

    private static final String CONNECT_URL = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String IMAGE_URL_PREFIX = "https://open.weixin.qq.com/connect/qrcode/";
    private static final String POLL_URL = "https://lp.open.weixin.qq.com/connect/l/qrconnect";
    private static final String REDIRECT_URI =
            "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/";
    private static final String STYLE_HREF =
            "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect";
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";

    private static final Pattern UUID_PATTERN = Pattern.compile("uuid=(.+?)\"");
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("window\\.wx_errcode=(\\d+);window\\.wx_code='([^']*)'");

    /** {@code window.wx_errcode} 的取值。 */
    public static final int STATUS_WAITING = 408;
    public static final int STATUS_SCANNED = 404;
    public static final int STATUS_CONFIRMED = 405;
    public static final int STATUS_EXPIRED = 402;
    public static final int STATUS_REFUSED = 403;

    /**
     * 长轮询的读超时。上游会把连接一直挂着直到状态变化，所以这个值不是"轮询间隔"，
     * 而是"一次挂起最多等多久"；到点返回等待态，由调用方决定要不要接着挂。
     */
    private static final int POLL_READ_TIMEOUT_MILLIS = 30_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private QQWechatLogin() {
    }

    /** 一次微信扫码会话。 */
    public static final class Session {
        public final String uuid;
        /** {@code data:image/...;base64,...}，可直接交给账号面板现有的 QQ 二维码渲染路径。 */
        public final String imageDataUrl;

        Session(String uuid, String imageDataUrl) {
            this.uuid = uuid;
            this.imageDataUrl = imageDataUrl;
        }
    }

    /** 一次轮询结果。{@code code} 只在 {@link #STATUS_CONFIRMED} 时非空。 */
    public static final class Poll {
        public final int status;
        public final String code;

        Poll(int status, String code) {
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    /**
     * 申请一张微信登录二维码。
     *
     * @throws IllegalStateException 页面里取不到 {@code uuid}、或图片不是 PNG/JPEG
     */
    public static Session create() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("appid", WECHAT_APP_ID);
        params.put("redirect_uri", REDIRECT_URI);
        params.put("response_type", "code");
        params.put("scope", "snsapi_login");
        params.put("state", "STATE");
        params.put("href", STYLE_HREF);

        String page = getText(HttpUtils.mapToString(CONNECT_URL, params, "?"), null);
        Matcher matcher = UUID_PATTERN.matcher(page == null ? "" : page);
        if (!matcher.find()) {
            throw new IllegalStateException("微信二维码响应里没有 uuid");
        }
        String uuid = matcher.group(1);
        return new Session(uuid, fetchImage(uuid));
    }

    /** 重新取一次同一个 uuid 的二维码图片，不会换一张新的码。 */
    public static String fetchImage(String uuid) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Referer", CONNECT_URL);
        byte[] bytes = getBytes(IMAGE_URL_PREFIX + uuid, headers, POLL_READ_TIMEOUT_MILLIS);
        String mimetype = imageMimetype(bytes);
        if (mimetype == null) {
            throw new IllegalStateException("微信二维码响应不是 PNG/JPEG 图片");
        }
        return "data:" + mimetype + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 长轮询一次二维码状态。
     *
     * @return 永不为 {@code null}；请求失败时返回等待态，让调用方按"还没有变化"继续，
     *         而不是把一次网络抖动当成二维码失效
     */
    public static Poll poll(String uuid) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Referer", "https://open.weixin.qq.com/");
        String url = POLL_URL + "?uuid=" + uuid + "&_=" + System.currentTimeMillis();
        String body;
        try {
            body = getText(url, headers);
        } catch (Throwable throwable) {
            System.err.println("[Music/QQ-WeChat] 轮询失败: " + throwable.getMessage());
            return new Poll(STATUS_WAITING, "");
        }
        Matcher matcher = STATUS_PATTERN.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            System.err.println("[Music/QQ-WeChat] 轮询响应里没有 wx_errcode: "
                    + (body == null ? "null" : body.substring(0, Math.min(120, body.length()))));
            return new Poll(STATUS_WAITING, "");
        }
        int status;
        try {
            status = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException malformed) {
            return new Poll(STATUS_WAITING, "");
        }
        return new Poll(status, matcher.group(2));
    }

    /** 换凭证的结果。 */
    public static final class Credential {
        public final String musicId;
        public final String musicKey;
        public final String nickname;
        public final String avatarUrl;

        Credential(String musicId, String musicKey, String nickname, String avatarUrl) {
            this.musicId = musicId;
            this.musicKey = musicKey;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }
    }

    /**
     * 用微信 OAuth {@code code} 换 QQ 音乐凭证。
     *
     * <p>微信凭证把真实账号 id 放在 {@code str_musicid}，{@code musicid} 里是个占位值 ——
     * 所以取 id 必须先看 {@code str_musicid}。用错那个字段，后续请求会带着一个上游不认的 id。</p>
     *
     * @throws IllegalStateException 上游没有返回可用凭证
     */
    public static Credential exchange(String code) {
        JsonObject param = new JsonObject();
        param.addProperty("code", code == null ? "" : code);
        param.addProperty("strAppid", WECHAT_APP_ID);

        JsonObject request = new JsonObject();
        request.addProperty("module", "music.login.LoginServer");
        request.addProperty("method", "Login");
        request.add("param", param);

        JsonObject payload = new JsonObject();
        payload.add("comm", comm());
        payload.add("req_0", request);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "QQMusic 14090008(android 10)");

        String body;
        try {
            InputStream stream = HttpUtils.request(MUSICU_URL, payload.toString(), headers, "POST",
                    "application/json");
            body = stream == null ? "" : HttpUtils.readString(stream);
        } catch (Throwable throwable) {
            throw new IllegalStateException("微信凭证兑换请求失败：" + throwable.getMessage(), throwable);
        }

        JsonObject root = asObject(body);
        JsonObject item = root == null ? null : asObject(root.get("req_0"));
        JsonObject data = item == null ? null : asObject(item.get("data"));
        if (data == null) {
            throw new IllegalStateException("微信凭证兑换响应为空（上游码 "
                    + readInt(item, "code") + "/" + readInt(root, "code") + "）");
        }

        String musicId = firstNonEmpty(readString(data, "str_musicid"), readString(data, "musicid"));
        String musicKey = readString(data, "musickey");
        if (musicId.isEmpty() || "0".equals(musicId) || musicKey.isEmpty()) {
            throw new IllegalStateException("微信凭证兑换响应缺少 musicid/musickey（上游码 "
                    + readInt(item, "code") + "）");
        }
        return new Credential(musicId, musicKey,
                firstNonEmpty(readString(data, "nick"), readString(data, "nickname")),
                firstNonEmpty(readString(data, "logo"), readString(data, "avatarUrl")));
    }

    /**
     * musicu 的 comm 块。
     *
     * <p>{@code QIMEI}/{@code QIMEI36} 留空：完整的 QIMEI 引导要一整套 RSA + AES 的设备注册链路，
     * 而这个字段在协议里本来就是可空的。设备标识每次进程随机生成而不是持久化，是因为它只在这一次
     * 兑换里用到 —— 凭证本身由 Cadence 的凭证库保存。</p>
     */
    private static JsonObject comm() {
        String openUdid = randomHex(32);
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 11);
        comm.addProperty("cv", 14090008);
        comm.addProperty("v", 14090008);
        comm.addProperty("chid", "10003505");
        comm.addProperty("tmeAppID", "qqmusic");
        comm.addProperty("QIMEI", "");
        comm.addProperty("QIMEI36", "");
        comm.addProperty("OpenUDID", openUdid);
        comm.addProperty("udid", openUdid);
        comm.addProperty("OpenUDID2", openUdid);
        comm.addProperty("aid", randomHex(16));
        comm.addProperty("os_ver", "10");
        comm.addProperty("phonetype", "MI 6");
        comm.addProperty("devicelevel", "29");
        comm.addProperty("newdevicelevel", "29");
        comm.addProperty("rom", "xiaomi/iarim/sagit:10/eomam.200122.001/1234567:user/release-keys");
        comm.addProperty("tmeLoginType", WECHAT_LOGIN_TYPE);
        return comm;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static String getText(String url, Map<String, String> headers) {
        try {
            InputStream stream = HttpUtils.request(url, null, headers, "GET",
                    "application/x-www-form-urlencoded", 10_000, POLL_READ_TIMEOUT_MILLIS);
            return stream == null ? "" : HttpUtils.readString(stream);
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable.getMessage(), throwable);
        }
    }

    private static byte[] getBytes(String url, Map<String, String> headers, int readTimeoutMillis) {
        try {
            InputStream stream = HttpUtils.request(url, null, headers, "GET",
                    "application/x-www-form-urlencoded", 10_000, readTimeoutMillis);
            if (stream == null) return new byte[0];
            ByteArrayOutputStream output = new ByteArrayOutputStream(65536);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) output.write(buffer, 0, read);
            stream.close();
            return output.toByteArray();
        } catch (Throwable throwable) {
            throw new IllegalStateException("二维码图片下载失败：" + throwable.getMessage(), throwable);
        }
    }

    /** 按魔数判断图片类型；返回 {@code null} 表示这不是一张图（通常是被网关换成了 HTML 错误页）。 */
    static String imageMimetype(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47) {
            return "image/png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    private static String randomHex(int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append(Integer.toHexString(RANDOM.nextInt(16)));
        }
        return builder.substring(0, length);
    }

    private static JsonObject asObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String readString(JsonObject parent, String name) {
        if (parent == null) return "";
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private static int readInt(JsonObject parent, String name) {
        if (parent == null) return -1;
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return -1;
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }

    static String utf8(byte[] bytes) {
        return new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
    }
}