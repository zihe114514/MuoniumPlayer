package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网易云搜索建议（联想词）。
 *
 * <p>端点见 {@link CloudMusicApi#searchSuggest(String, boolean)} —— 严格对应 api-enhanced 的
 * {@code module/search_suggest.js}，它有 {@code keyword} 与 {@code web} 两个变体。这里<b>先</b>用
 * {@code keyword}（{@code result.allMatch[].keyword}，纯联想词），空了才退到 {@code web}
 * （按 {@code result.order} 分组的歌曲 / 歌手 / 专辑 / 歌单）。理由见 {@link #fetch(String)} 上的说明 ——
 * 是按真实返回体挑的，不是照文档的默认值。<b>不</b>两个都请求：白发一倍请求换不到信息。</p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li>结果按关键词缓存（含空结果），10 分钟过期。用户逐字打「周杰伦」会命中四次同一批缓存中的前缀，
 *       不会因为退一个字符就重新发请求。</li>
 *   <li>同一个关键词在飞时不会重复发第二次。</li>
 *   <li>缓存条目超过 256 条整体清空 —— 联想词是纯装饰，清了下一次重新问就好，不值得为它维护 LRU。</li>
 *   <li>任何异常都落成「这个关键词没有建议」的空结果，不会向 UI 抛。</li>
 * </ul>
 */
public final class NeteaseSearchSuggestService {

    /** 联想面板最多展示这么多条，取数时就截断，免得白解析。 */
    public static final int MAX_SUGGESTIONS = 8;

    private static final long CACHE_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 256;

    /** {@code result.order} 缺失时的兜底顺序，与网易云网页端一致。 */
    private static final List<String> DEFAULT_ORDER =
            Collections.unmodifiableList(Arrays.asList("songs", "artists", "albums", "playlists"));

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();
    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private NeteaseSearchSuggestService() {
    }

    /** 一条联想项。{@code text} 是点下去真正拿去搜的词。 */
    public static final class Suggestion {
        public final String text;
        /** 右侧的类型标签：歌曲 / 歌手 / 专辑 / 歌单；{@code keyword} 变体没有类型，这里是空串。 */
        public final String kind;
        /** 副标题，通常是艺人名；没有就是空串。 */
        public final String detail;

        Suggestion(String text, String kind, String detail) {
            this.text = text;
            this.kind = kind;
            this.detail = detail;
        }
    }

    private static final class Entry {
        final List<Suggestion> suggestions;
        final long expiresAt;

        Entry(List<Suggestion> suggestions, long expiresAt) {
            this.suggestions = suggestions;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 只读缓存，<b>永远不发请求、永远不阻塞</b>，所以可以在渲染回调里每帧调用。
     *
     * @return 已缓存的建议；没缓存过或已过期时返回空列表
     */
    public static List<Suggestion> peek(String keyword) {
        String key = normalize(keyword);
        if (key.isEmpty()) return Collections.emptyList();
        Entry entry = CACHE.get(key);
        if (entry == null) return Collections.emptyList();
        if (entry.expiresAt <= System.currentTimeMillis()) {
            CACHE.remove(key, entry);
            return Collections.emptyList();
        }
        return entry.suggestions;
    }

    /** 这个关键词是否已经有结论（含"确认没有建议"），用来避免重复发请求。 */
    public static boolean isResolved(String keyword) {
        String key = normalize(keyword);
        if (key.isEmpty()) return true;
        Entry entry = CACHE.get(key);
        return entry != null && entry.expiresAt > System.currentTimeMillis();
    }

    /** 异步取一次并落缓存。已有结论或正在飞时直接返回，调用方可以放心每帧调。 */
    public static void prefetch(final String keyword) {
        final String key = normalize(keyword);
        if (key.isEmpty() || isResolved(key)) return;
        if (!IN_FLIGHT.add(key)) return;
        MultiThreadingUtil.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    store(key, fetch(key));
                } catch (Throwable ignored) {
                    // 联想词是纯装饰：取不到就记一次空结果，不要把异常抬到 UI。
                    store(key, Collections.<Suggestion>emptyList());
                } finally {
                    IN_FLIGHT.remove(key);
                }
            }
        });
    }
    /**
     * 清掉全部缓存。
     *
     * <p>没有挂在登录/登出流程上：联想结果的个性化程度很低，而缓存本身 10 分钟就过期，换账号后
     * 最多带着上一个账号的排序活十分钟。为了它去改官方账号链路不值得。</p>
     */
    public static void clear() {
        CACHE.clear();
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private static void store(String key, List<Suggestion> suggestions) {
        if (CACHE.size() >= MAX_CACHE_ENTRIES) CACHE.clear();
        CACHE.put(key, new Entry(suggestions == null ? Collections.<Suggestion>emptyList() : suggestions,
                System.currentTimeMillis() + CACHE_MILLIS));
    }

    /**
     * 先 {@code keyword} 变体，它空了才退到 {@code web}。
     *
     * <p>这个顺序是按真实返回体定的，不是照文档的默认值。匿名直连实测搜「周杰伦」：
     * {@code keyword} 给的是「周杰伦 / 周杰伦歌单 / 周杰伦晴天 / 周杰伦稻香…」——正是一个搜索框该有的
     * 联想词；而 {@code web} 给的前四条是「布拉格广场（蔡依林/周杰伦）」「屋顶」「默 (Live)」这类
     * <b>他只是参与演唱</b>的歌，歌手条目排在第五，专辑还给出了蔡依林的两张。对搜索框来说
     * {@code keyword} 明显更对，所以它是主路径。</p>
     */
    private static List<Suggestion> fetch(String keyword) {
        List<Suggestion> keywords = parseKeyword(read(keyword, true));
        if (!keywords.isEmpty()) return keywords;
        return parseWeb(read(keyword, false));
    }

    private static JsonObject read(String keyword, boolean mobile) {
        try {
            RequestUtil.RequestAnswer answer = CloudMusicApi.searchSuggest(keyword, mobile);
            if (answer == null || answer.getStatus() != 200) return null;
            JsonObject root = answer.toJsonObject();
            if (root == null || !root.has("result") || !root.get("result").isJsonObject()) return null;
            return root.getAsJsonObject("result");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * {@code web} 变体：按 {@code result.order} 的顺序摊平四个分组。
     *
     * <p>照 {@code order} 走而不是自己定死顺序 —— 那个数组就是上游给出的相关性排序，
     * 搜歌手名时它会把 {@code artists} 排在 {@code songs} 前面。</p>
     */
    private static List<Suggestion> parseWeb(JsonObject result) {
        if (result == null) return Collections.emptyList();
        List<String> order = readOrder(result);
        List<Suggestion> suggestions = new ArrayList<Suggestion>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String group : order) {
            if (suggestions.size() >= MAX_SUGGESTIONS) break;
            String kind = kindOf(group);
            // 只认这四个已知分组。上游哪天在 order 里加了新东西（视频、播客…），它的名字不该被当成
            // 一个音乐搜索词塞进联想列表里。
            if (kind.isEmpty()) continue;
            JsonArray items = array(result, group);
            if (items == null) continue;
            for (JsonElement element : items) {
                if (suggestions.size() >= MAX_SUGGESTIONS) break;
                if (element == null || !element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String name = text(item, "name");
                if (name.isEmpty()) continue;
                if (!seen.add(name.toLowerCase(Locale.ROOT) + '#' + group)) continue;
                suggestions.add(new Suggestion(name, kind, detailOf(item, group)));
            }
        }
        return suggestions.isEmpty() ? Collections.<Suggestion>emptyList()
                : Collections.unmodifiableList(suggestions);
    }

    /** {@code keyword} 变体：{@code result.allMatch[].keyword}，纯联想词、没有类型。 */
    private static List<Suggestion> parseKeyword(JsonObject result) {
        if (result == null) return Collections.emptyList();
        JsonArray items = array(result, "allMatch");
        if (items == null) return Collections.emptyList();
        List<Suggestion> suggestions = new ArrayList<Suggestion>();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonElement element : items) {
            if (suggestions.size() >= MAX_SUGGESTIONS) break;
            if (element == null || !element.isJsonObject()) continue;
            String keyword = text(element.getAsJsonObject(), "keyword");
            if (keyword.isEmpty()) continue;
            if (!seen.add(keyword.toLowerCase(Locale.ROOT))) continue;
            suggestions.add(new Suggestion(keyword, "", ""));
        }
        return suggestions.isEmpty() ? Collections.<Suggestion>emptyList()
                : Collections.unmodifiableList(suggestions);
    }

    private static List<String> readOrder(JsonObject result) {
        JsonArray order = array(result, "order");
        if (order == null || order.size() == 0) return DEFAULT_ORDER;
        List<String> groups = new ArrayList<String>();
        for (JsonElement element : order) {
            try {
                String group = element == null || element.isJsonNull() ? "" : element.getAsString();
                if (group != null && !group.trim().isEmpty()) groups.add(group.trim());
            } catch (Throwable ignored) {
                // 顺序数组里出现非字符串时忽略这一项即可。
            }
        }
        return groups.isEmpty() ? DEFAULT_ORDER : groups;
    }

    private static String kindOf(String group) {
        if ("songs".equals(group)) return "歌曲";
        if ("artists".equals(group)) return "歌手";
        if ("albums".equals(group)) return "专辑";
        if ("playlists".equals(group)) return "歌单";
        return "";
    }

    /** 歌曲取 {@code artists[].name}，专辑取 {@code artist.name}；歌手与歌单没有副标题。 */
    private static String detailOf(JsonObject item, String group) {
        if ("songs".equals(group)) {
            JsonArray artists = array(item, "artists");
            if (artists == null) return "";
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : artists) {
                if (element == null || !element.isJsonObject()) continue;
                String name = text(element.getAsJsonObject(), "name");
                if (name.isEmpty()) continue;
                if (builder.length() > 0) builder.append(" / ");
                builder.append(name);
            }
            return builder.toString();
        }
        if ("albums".equals(group)) {
            if (!item.has("artist") || !item.get("artist").isJsonObject()) return "";
            return text(item.getAsJsonObject("artist"), "name");
        }
        return "";
    }

    private static JsonArray array(JsonObject object, String property) {
        if (object == null || !object.has(property) || !object.get(property).isJsonArray()) return null;
        JsonArray array = object.getAsJsonArray(property);
        return array.size() == 0 ? null : array;
    }

    private static String text(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            String value = object.get(property).getAsString();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalize(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}