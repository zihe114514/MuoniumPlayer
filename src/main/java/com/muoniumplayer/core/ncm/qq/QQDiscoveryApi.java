package com.muoniumplayer.core.ncm.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 音乐里 Cadence（{@code music-api} 库）没有覆盖的发现页接口。
 *
 * <p>Cadence 只提供"取某一个榜单的曲目"（{@code getToplist(topId, limit)}），没有"榜单有哪些"，
 * 所以 QQ 的排行榜页原来只能硬编码两个榜 id。这里补上榜单列表与热搜，让 QQ 的这两个入口与网易云同形。</p>
 *
 * <p>两个端点各用一种上游风格：榜单列表走老 CGI（{@code fcg_myqq_toplist.fcg}，免签，返回 JSON），
 * 热搜走 musicu RPC。这不是随意选的 —— {@code splcloud/fcgi-bin/gethotkey.fcg} 实测已经
 * 稳定返回 500，musicu 上的 {@code HotkeyService} 才是还活着的那一条。</p>
 */
public final class QQDiscoveryApi {

    private static final String TOPLIST_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg";
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";

    private QQDiscoveryApi() {
    }

    /** 一个 QQ 榜单。{@code id} 就是 Cadence {@code getToplist} 要的 topId。 */
    public static final class TopList {
        public final int id;
        public final String title;
        public final String coverUrl;
        public final String description;

        TopList(int id, String title, String coverUrl, String description) {
            this.id = id;
            this.title = title;
            this.coverUrl = coverUrl;
            this.description = description;
        }
    }

    /** 榜单列表；失败返回空列表。 */
    public static List<TopList> topLists() {
        List<TopList> result = new ArrayList<TopList>();
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("format", "json");
        params.put("outCharset", "utf-8");
        params.put("platform", "h5");
        params.put("needNewCode", "1");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Referer", "https://c.y.qq.com/");

        JsonObject root = getJson(HttpUtils.mapToString(TOPLIST_URL, params, "?"), headers);
        JsonObject data = child(root, "data");
        JsonArray list = array(data, "topList");
        if (list == null) return result;
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            int id = integer(item, "id");
            String title = text(item, "topTitle", "title");
            if (id <= 0 || title.isEmpty()) continue;
            result.add(new TopList(id, title, text(item, "picUrl", "headPicUrl"),
                    text(item, "listenCount", "updateTime")));
        }
        return result;
    }

    /** 热搜关键词；失败返回空列表。 */
    public static List<String> hotSearches(int limit) {
        List<String> result = new ArrayList<String>();
        JsonObject param = new JsonObject();
        param.addProperty("search_type", 0);
        param.addProperty("remoteplace", "txt.miniapp.wxada7aab80ba27074");

        JsonObject request = new JsonObject();
        request.addProperty("module", "music.musicsearch.HotkeyService");
        request.addProperty("method", "GetHotkeyForQQMusicMobile");
        request.add("param", param);

        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 0);

        JsonObject payload = new JsonObject();
        payload.add("comm", comm);
        payload.add("req", request);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Content-Type", "application/json");

        JsonObject root = postJson(MUSICU_URL, payload.toString(), headers);
        JsonObject data = child(child(root, "req"), "data");
        JsonArray keys = array(data, "vec_hotkey");
        if (keys == null) return result;
        int max = limit <= 0 ? 30 : limit;
        for (JsonElement element : keys) {
            if (result.size() >= max) break;
            if (!element.isJsonObject()) continue;
            String query = text(element.getAsJsonObject(), "query", "hotkey", "k");
            if (query.isEmpty() || result.contains(query)) continue;
            result.add(query);
        }
        return result;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static JsonObject getJson(String url, Map<String, String> headers) {
        try {
            InputStream stream = HttpUtils.request(url, null, headers, "GET");
            return parse(stream == null ? null : HttpUtils.readString(stream));
        } catch (Throwable throwable) {
            System.err.println("[Music/QQ] GET " + url + " 失败: " + throwable.getMessage());
            return null;
        }
    }

    private static JsonObject postJson(String url, String body, Map<String, String> headers) {
        try {
            InputStream stream = HttpUtils.request(url, body, headers, "POST", "application/json");
            return parse(stream == null ? null : HttpUtils.readString(stream));
        } catch (Throwable throwable) {
            System.err.println("[Music/QQ] POST " + url + " 失败: " + throwable.getMessage());
            return null;
        }
    }

    private static JsonObject parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject child(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement element = parent.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String text(JsonObject parent, String... names) {
        if (parent == null) return "";
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            String value = element.getAsString();
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static int integer(JsonObject parent, String name) {
        if (parent == null) return 0;
        JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return 0;
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}