package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酷狗接口的请求封装：注入设备参数、算签名、发请求、解 JSON。
 *
 * <p>拆成独立一层而不是塞进 API 类里，是因为酷狗有<b>三套</b>签名与两个网关（老 {@code mobilecdn}
 * 免签、新 {@code gateway} 必签），每个端点选哪一套是端点自己的属性。把这段逻辑集中在这里，
 * {@link KuGouMusicApi} 里每个方法就只剩"填参数 + 读字段"。</p>
 *
 * <h3>设备身份</h3>
 * <p>{@code mid} 由一个持久化的 GUID 推出（见 {@link KuGouSign#mid}）。它必须在一次安装里保持稳定：
 * 每次请求换一个新 mid 会被上游当成大量新设备，风控会开始要求二次验证（{@code ssa-code}），
 * 表现是接口突然全部返回 {@code error_code} 非 0。</p>
 */
final class KuGouHttp {

    /** 新网关，需要签名。 */
    static final String GATEWAY = "https://gateway.kugou.com";

    /** 老移动端网关，多数只读接口在这里免签可用，是本实现的主路径。 */
    static final String MOBILE_CDN = "http://mobilecdn.kugou.com";

    static final String LOGIN_USER = "https://login-user.kugou.com";

    static final String LYRICS = "https://lyrics.kugou.com";

    static final String APP_ID = "1005";
    static final String CLIENT_VER = "20489";
    static final String SRC_APP_ID = "2919";

    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    private KuGouHttp() {
    }

    enum Sign {
        /** 不加签名（老 mobilecdn 端点）。 */
        NONE,
        ANDROID,
        WEB
    }

    /** 一次请求的参数集合。用 LinkedHashMap 保持可读的书写顺序；签名自己会排序。 */
    static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    /** 注入每个签名接口都要带的设备与版本参数。 */
    static Map<String, String> deviceParams() {
        Map<String, String> params = params();
        params.put("dfid", "-");
        params.put("mid", KuGouDevice.mid());
        params.put("uuid", "-");
        params.put("appid", APP_ID);
        params.put("clientver", CLIENT_VER);
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000L));
        return params;
    }

    static JsonObject get(String baseUrl, String path, Map<String, String> params, Sign sign) {
        return get(baseUrl, path, params, sign, null);
    }

    /**
     * @param extraHeaders 额外请求头，例如新网关的 {@code x-router}
     * @return 解析后的 JSON 对象；请求或解析失败时返回 {@code null}（调用方按"没数据"处理，不要吞成空对象）
     */
    static JsonObject get(String baseUrl, String path, Map<String, String> params, Sign sign,
                          Map<String, String> extraHeaders) {
        Map<String, String> signed = signIfNeeded(params, sign, null);
        String url = baseUrl + path + "?" + query(signed);
        try {
            InputStream stream = HttpUtils.request(url, null, headers(signed, extraHeaders), "GET");
            return parse(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] GET " + path + " failed: " + throwable.getMessage());
            return null;
        }
    }

    /** POST JSON。签名要把请求体也算进去，所以 body 必须在算签名前就定下来。 */
    static JsonObject postJson(String baseUrl, String path, Map<String, String> params, String body,
                               Sign sign, Map<String, String> extraHeaders) {
        Map<String, String> signed = signIfNeeded(params, sign, body);
        String url = baseUrl + path + "?" + query(signed);
        Map<String, String> headers = headers(signed, extraHeaders);
        headers.put("Content-Type", "application/json");
        try {
            InputStream stream = HttpUtils.request(url, body, headers, "POST", "application/json");
            return parse(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] POST " + path + " failed: " + throwable.getMessage());
            return null;
        }
    }

    private static Map<String, String> signIfNeeded(Map<String, String> params, Sign sign, String body) {
        Map<String, String> copy = params == null ? params() : new LinkedHashMap<>(params);
        if (sign == Sign.ANDROID) {
            copy.put("signature", KuGouSign.android(copy, body));
        } else if (sign == Sign.WEB) {
            copy.put("signature", KuGouSign.web(copy));
        }
        return copy;
    }

    private static Map<String, String> headers(Map<String, String> params, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("dfid", "-");
        headers.put("mid", KuGouDevice.mid());
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        String clientTime = params == null ? null : params.get("clienttime");
        headers.put("clienttime", clientTime == null
                ? String.valueOf(System.currentTimeMillis() / 1000L) : clientTime);
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return headers;
    }

    private static String query(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        if (params == null) return "";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(KuGouSign.urlEncode(entry.getKey()))
                    .append('=')
                    .append(KuGouSign.urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static JsonObject parse(InputStream stream) {
        if (stream == null) return null;
        String text = HttpUtils.readString(stream);
        if (text == null || text.trim().isEmpty()) return null;
        JsonElement element = new JsonParser().parse(text);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
}