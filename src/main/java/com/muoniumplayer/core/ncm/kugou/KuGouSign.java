package com.muoniumplayer.core.ncm.kugou;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 酷狗接口的请求签名。
 *
 * <p>这些盐值与拼接顺序是第三方逆向得到的协议事实（对照 {@code MakcRe/KuGouMusicApi} 的
 * {@code util/helper.js}），不是酷狗官方定义。改动前请先用真实请求验证，签名错的表现是
 * {@code error_code} 非 0 而 HTTP 仍然 200 —— 不会抛异常，只会一直取不到数据。</p>
 *
 * <p>三种签名的区别只在盐值和参与拼接的内容：
 * <ul>
 *   <li>{@link #android(Map, String)}：{@code MD5(salt + 排序后的 key=value + body + salt)}</li>
 *   <li>{@link #web(Map)}：与 android 同形，但盐值不同，且排序的是<b>拼好的 {@code key=value} 串</b>
 *       而不是 key —— 两者在含数字 key 时结果不同，不能合并</li>
 *   <li>{@link #playKey(String)}：老版 {@code trackercdn} 取流地址用的 key</li>
 * </ul>
 * </p>
 */
public final class KuGouSign {

    /** Android 客户端签名盐值。 */
    private static final String ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp";

    /** Web 端签名盐值。 */
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";

    /** {@code /v5/url} 的 key 参数盐值。 */
    private static final String URL_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d";

    /** 老版 {@code trackercdn.kugou.com/i/v2/} 的 key 盐值。 */
    private static final String PLAY_KEY_SALT = "kgcloudv2";

    private KuGouSign() {
    }

    public static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xFF);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须提供 MD5，这里走不到。
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    /** Android 版签名：按 key 排序后拼 {@code key=value}，前后夹盐值，请求体（若有）夹在末尾盐值之前。 */
    public static String android(Map<String, String> params, String body) {
        return md5(ANDROID_SALT + joinSortedByKey(params) + (body == null ? "" : body) + ANDROID_SALT);
    }

    /** Web 版签名：排序的是拼好的 {@code key=value} 串本身，这是上游的行为，不要"顺手改成"按 key 排。 */
    public static String web(Map<String, String> params) {
        List<String> pairs = new ArrayList<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                pairs.add(entry.getKey() + "=" + nonNull(entry.getValue()));
            }
        }
        Collections.sort(pairs);
        StringBuilder builder = new StringBuilder();
        for (String pair : pairs) builder.append(pair);
        return md5(WEB_SALT + builder + WEB_SALT);
    }

    /** {@code /v5/url} 需要的 key 参数。 */
    public static String urlKey(String hash, String mid, String userId, String appId) {
        return md5(nonNull(hash) + URL_KEY_SALT + nonNull(appId) + nonNull(mid)
                + (isBlank(userId) ? "0" : userId));
    }

    /** 老版取流接口的 key：{@code MD5(hash + "kgcloudv2")}。匿名也能用，是本实现的主路径。 */
    public static String playKey(String hash) {
        return md5(lower(hash) + PLAY_KEY_SALT);
    }

    /**
     * 由设备 GUID 推出 mid。
     *
     * <p>上游把 MD5 的 32 位十六进制串<b>当成一个 16 进制大整数</b>再转十进制，而不是取它的数值哈希。
     * 直接 {@code Long.parseLong} 会溢出，必须用 {@link BigInteger}。</p>
     */
    public static String mid(String guid) {
        return new BigInteger(md5(guid), 16).toString();
    }

    /** 参数按 key 升序拼成 {@code key=value} 连续串（无分隔符），这是 android 签名的输入。 */
    private static String joinSortedByKey(Map<String, String> params) {
        List<String> keys = new ArrayList<>();
        if (params != null) keys.addAll(params.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            builder.append(key).append('=').append(nonNull(params.get(key)));
        }
        return builder.toString();
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(nonNull(value), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            return nonNull(value);
        }
    }

    static String lower(String value) {
        return nonNull(value).toLowerCase(java.util.Locale.ROOT);
    }

    static String nonNull(String value) {
        return value == null ? "" : value;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}