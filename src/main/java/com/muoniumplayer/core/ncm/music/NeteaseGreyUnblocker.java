package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.Tuple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 网易云「灰色歌曲」解灰。
 *
 * <h3>它按谁的流程做的</h3>
 * <p>api-enhanced 的「直接获取灰色歌曲链接」（{@code module/song_url_match.js} →
 * {@code /song/url/match}）<b>完全不请求网易云</b>：它把活整个转给
 * {@code @neteasecloudmusicapienhanced/unblockmusic-utils} 的 {@code matchID}，
 * 而那个包的文件头注释写得很直接 —— 「支持qq音乐、酷狗音乐、酷我音乐、咪咕音乐、
 * 第三方网易云API等等(来自GD音乐台)」。也就是说协议层<b>没有</b>可以「适配」的端点，
 * 真正要做的是把 UnblockNeteaseMusic/server 的匹配流水线搬过来。</p>
 *
 * <p>所以这里复刻的是 UNM {@code src/provider/} 那一套的<b>流程</b>，而不是它的九个
 * Node provider：</p>
 * <ol>
 *   <li>{@code find.js}：清掉曲名里的 {@code （cover:xxx）}/{@code （翻自xxx）}，
 *       艺人按累计长度 15 截断，拼成检索关键词；</li>
 *   <li>{@code select.js}：只看前 5 条结果，取第一条时长差 &lt; 5 秒的；没有就取匹配度最高的；</li>
 *   <li>{@code match.js}：按源顺序逐个试，第一个拿到链接的即用（{@code FOLLOW_SOURCE_ORDER}）。</li>
 * </ol>
 *
 * <h3>为什么不移植那九个 provider</h3>
 * <p>{@code kuwo} 要 DES、{@code migu} 要私有签名、{@code ytdlp}/{@code pyncmd} 要外部
 * 二进制或 Python —— 在只有 Java 8、没有 Node 的环境里等于重写九套私有加密。而本项目已经
 * 有<b>官方酷狗 / 官方 QQ</b> 两条完整链路，外加 GD 音乐台聚合源（也正是 api-enhanced 自己
 * 用的那一家）。所以候选源直接用项目里已经跑通的这几条。</p>
 *
 * <h3>护栏</h3>
 * <ul>
 *   <li><b>只在官方链路彻底失败之后</b>才启动，绝不主动对整张歌单做可用性探测 —— 那会瞬间
 *       打爆网易云与 GD 音乐台的额度。</li>
 *   <li>正、负结果都缓存：一首解不开的歌不会每次点击都再烧一遍第三方额度。</li>
 *   <li>GD 尝试前检查剩余额度与该源的熔断状态，并有整体墙钟上限。</li>
 *   <li>匹配门槛沿用项目里已有的严格程度（曲名相似度 + 艺人相似度双闸），比 UNM 只看时长更严，
 *       宁可解不开也不要放成另一首歌。</li>
 * </ul>
 */
public final class NeteaseGreyUnblocker {

    /** UNM {@code select.js} 的时长容差。 */
    private static final long DURATION_TOLERANCE_MILLIS = 5_000L;
    /** UNM {@code select.js} 只看前几条。 */
    private static final int SELECT_WINDOW = 5;
    /** UNM {@code find.js} 的艺人累计长度上限。 */
    private static final int ARTIST_KEYWORD_BUDGET = 15;

    /** 每个候选源最多取几条结果回来挑。 */
    private static final int SEARCH_LIMIT = 10;
    /** 整条流水线的墙钟上限；外层 {@code Music} 给的超时是 35 秒，这里必须先收手。 */
    private static final long PIPELINE_DEADLINE_MILLIS = 26_000L;
    /** 一次 GD 尝试 = 一次搜索 + 一到多次取流，额度不够就跳过 GD。 */
    private static final int GD_MIN_BUDGET = 10;

    /** 直链会过期，所以正结果只短期复用。 */
    private static final long POSITIVE_CACHE_MILLIS = 8L * 60L * 1000L;
    /** 解不开的歌记一段时间，避免每次点击都重跑整条流水线。 */
    private static final long NEGATIVE_CACHE_MILLIS = 10L * 60L * 1000L;

    private static final Pattern COVER_NOISE = Pattern.compile(
            "[（(]\\s*(?:cover|翻自)\\s*[:：]?[^）)]*[）)]", Pattern.CASE_INSENSITIVE);

    private static final Map<Long, CacheEntry> CACHE = new ConcurrentHashMap<Long, CacheEntry>();

    private NeteaseGreyUnblocker() {
    }

    /** 一次成功的解灰。{@code sourceLabel} 会直接出现在歌曲行的徽标上。 */
    public static final class Result {
        public final String url;
        public final String format;
        public final String sourceKey;
        public final String sourceLabel;
        public final String matchedName;
        public final String matchedArtist;

        Result(String url, String format, String sourceKey, String sourceLabel,
               String matchedName, String matchedArtist) {
            this.url = url;
            this.format = format;
            this.sourceKey = sourceKey;
            this.sourceLabel = sourceLabel;
            this.matchedName = matchedName;
            this.matchedArtist = matchedArtist;
        }
    }

    private static final class CacheEntry {
        final Result result;
        final long expiresAt;

        CacheEntry(Result result, long expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 候选源，按尝试顺序。
     *
     * <p>官方酷狗排第一：它是免费流覆盖最好的一家，且不消耗 GD 音乐台的额度。官方 QQ 第二，
     * 同样不吃 GD 额度。之后才是 GD 的三个源 —— 其中 {@code netease} 走的是上游自己的
     * 第三方网易云接口，对本机账号放不了的歌反而常常有流（等价于 UNM 的 {@code pyncmd}）。</p>
     */
    private enum Candidate {
        OFFICIAL_KUGOU("kugou", "酷狗", Kind.OFFICIAL),
        OFFICIAL_QQ("qq", "QQ音乐", Kind.OFFICIAL),
        /**
         * GD 的 {@code netease} 源用的<b>就是网易云自己的 songId</b>（已实测：它搜索返回的 id
         * 全是形如 {@code 2725685941} 的网易云曲目 id）。所以这一条不检索，直接拿本曲的 id 去
         * 上游的第三方网易云接口取流 —— 零错配风险，还省掉一次搜索请求。它等价于 UNM 的
         * {@code pyncmd} provider。
         */
        GD_NETEASE_BY_ID("gd:netease", "网易云API", Kind.GD_BY_ID),
        GD_JOOX("gd:joox", "JOOX", Kind.GD_SEARCH),
        GD_KUWO("gd:kuwo", "酷我", Kind.GD_SEARCH);

        enum Kind { OFFICIAL, GD_SEARCH, GD_BY_ID }

        final String key;
        final String label;
        final Kind kind;

        Candidate(String key, String label, Kind kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }

        String gdPlatform() {
            return key.substring("gd:".length());
        }
    }
    // ── 对外入口 ────────────────────────────────────────────────────────────

    /**
     * 给一首取不到官方流的网易云歌找一条可播链接。
     *
     * <p><b>调用契约</b>：只能在网易云自己的两条官方链路（内置 API + Cadence）都返回
     * {@code null} 之后调用。它自己不会去判断「该不该解灰」。</p>
     *
     * @return 解出来的链接；开关关闭、不是网易云曲目、或所有候选源都没有匹配时返回 {@code null}
     */
    public static Result unblock(Music music, Quality quality) {
        if (music == null || !music.isNetease()) return null;
        if (!NeteaseUnblockSettings.isEnabled()) return null;

        long id = music.getId();
        CacheEntry cached = CACHE.get(id);
        long now = System.currentTimeMillis();
        if (cached != null) {
            if (cached.expiresAt > now) return cached.result;
            CACHE.remove(id, cached);
        }

        String title = cleanTitle(music.getName());
        if (title.isEmpty()) return null;
        String artist = keywordArtists(music);

        DownloadDynamicIsland.showNeteaseUnblockSearching(music.getName());

        Result result = null;
        try {
            result = runPipeline(music, title, artist, quality);
        } catch (Throwable failure) {
            System.err.println("[Music/Unblock] 解灰流水线异常 " + music.getStableKey() + ": " + failure.getMessage());
        }

        long expiry = System.currentTimeMillis()
                + (result == null ? NEGATIVE_CACHE_MILLIS : POSITIVE_CACHE_MILLIS);
        CACHE.put(id, new CacheEntry(result, expiry));

        if (result == null) {
            DownloadDynamicIsland.showNeteaseUnblockFailure(music.getName());
        } else {
            DownloadDynamicIsland.showNeteaseUnblocked(music.getName(), result.sourceLabel, result.format);
        }
        return result;
    }

    /** 忘掉一首歌的缓存结论；直链过期后重新点播会走到这里。 */
    public static void forget(long neteaseId) {
        CACHE.remove(neteaseId);
    }

    /**
     * 这一首在当前账号下到底能不能放（api-enhanced 的「音乐是否可用」）。
     *
     * <p>只在官方链路已经失败之后调用一次，用来把「真的是灰色歌」和「刚好网络抖了一下」区分开，
     * 决定歌曲行上要不要挂「灰」标。</p>
     */
    public static boolean isPlayableOnOfficial(Music music) {
        if (music == null || !music.isNetease()) return true;
        try {
            return CloudMusicApi.isMusicPlayable(music.getId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 流水线 ──────────────────────────────────────────────────────────────

    private static Result runPipeline(Music music, String title, String artist, Quality quality) {
        long started = System.currentTimeMillis();
        for (Candidate candidate : Candidate.values()) {
            if (Thread.currentThread().isInterrupted()) return null;
            if (System.currentTimeMillis() - started > PIPELINE_DEADLINE_MILLIS) return null;
            try {
                Result result;
                switch (candidate.kind) {
                    case GD_BY_ID:
                        result = tryGdById(candidate, music, quality);
                        break;
                    case GD_SEARCH:
                        result = tryGd(candidate, music, title, artist, quality);
                        break;
                    case OFFICIAL:
                    default:
                        result = tryOfficial(candidate, music, title, artist, quality);
                        break;
                }
                if (result != null) return result;
            } catch (Throwable ignored) {
                // 一个源失败绝不能挡住后面的源。
            }
        }
        return null;
    }

    /** 官方酷狗 / 官方 QQ：搜索结果自带时长，所以能完整跑 UNM 的 ±5 秒规则。 */
    private static Result tryOfficial(Candidate candidate, Music music, String title, String artist,
                                      Quality quality) {
        MusicPlatform platform = candidate == Candidate.OFFICIAL_KUGOU
                ? MusicPlatform.KUGOU : MusicPlatform.QQ;
        List<Music> results = searchOfficial(platform, title, artist);
        Music match = selectOfficial(results, title, artist, music.getDuration());
        if (match == null) return null;

        Tuple<String, String> stream = platform == MusicPlatform.KUGOU
                ? KuGouMusicService.getSongUrl(match, quality)
                : CadenceMusicService.getSongUrl(match, quality);
        if (stream == null || stream.getA() == null || stream.getA().trim().isEmpty()) return null;
        return new Result(stream.getA().trim(), safe(stream.getB()), candidate.key, candidate.label,
                match.getName(), match.getArtistsName());
    }

    private static List<Music> searchOfficial(MusicPlatform platform, String title, String artist) {
        List<Music> primary = safeOfficialSearch(platform, searchQuery(title, artist));
        if (!primary.isEmpty() || artist.isEmpty()) return primary;
        // 平台之间的艺人写法差异（罗马音 / 团名 / 合唱顺序）比曲名差异大得多，
        // 带艺人搜不到时再退一次纯曲名，比直接放弃这一整个源划算。
        return safeOfficialSearch(platform, title);
    }

    private static List<Music> safeOfficialSearch(MusicPlatform platform, String keyword) {
        try {
            List<Music> found = CadenceMusicService.search(platform, keyword, SEARCH_LIMIT);
            return found == null ? new ArrayList<Music>() : found;
        } catch (Throwable ignored) {
            return new ArrayList<Music>();
        }
    }

    /**
     * GD 的第三方网易云接口，按本曲的 songId 直取。
     *
     * <p>不检索，所以既没有错配风险，也不花搜索额度。实测 GD 的 {@code netease} 源用的就是
     * 网易云自己的 songId；而它<b>按关键词搜</b>出来的前几条是一堆翻唱（「夜曲 / Xai小爱」、
     * 「夜曲(钢琴曲)」…），排序质量差到不能用 —— 这也是这一源只走 id 直取的原因。</p>
     */
    private static Result tryGdById(Candidate candidate, Music music, Quality quality) throws Exception {
        String platform = candidate.gdPlatform();
        GdStudioMusicService.Health health = GdStudioMusicService.getHealth(platform);
        if (health.unsupported || health.cooling) return null;
        if (GdStudioMusicService.remainingRequestBudget() < GD_MIN_BUDGET) return null;

        GdStudioMusicService.ResolveResult resolved =
                GdStudioMusicService.resolveTrack(platform, String.valueOf(music.getId()), quality);
        if (resolved == null || resolved.url == null || resolved.url.trim().isEmpty()) return null;
        return new Result(resolved.url.trim(), safe(resolved.format), candidate.key, candidate.label,
                music.getName(), music.getArtistsName());
    }

    /**
     * GD 音乐台：搜索响应<b>不带时长</b>（见 {@code Music#decodedDurationMillis} 的注释），
     * 所以这里只能靠曲名 / 艺人相似度定位，UNM 的 ±5 秒规则在这条链路上无法复现。
     */
    private static Result tryGd(Candidate candidate, Music music, String title, String artist,
                                Quality quality) throws Exception {
        String platform = candidate.gdPlatform();
        GdStudioMusicService.Health health = GdStudioMusicService.getHealth(platform);
        if (health.unsupported || health.cooling) return null;
        if (GdStudioMusicService.remainingRequestBudget() < GD_MIN_BUDGET) return null;

        List<GdStudioMusicService.GdTrack> tracks =
                GdStudioMusicService.search(platform, searchQuery(title, artist), SEARCH_LIMIT, 1);
        GdStudioMusicService.GdTrack match = selectGd(tracks, title, artist);
        if (match == null && !artist.isEmpty()
                && GdStudioMusicService.remainingRequestBudget() >= GD_MIN_BUDGET) {
            match = selectGd(GdStudioMusicService.search(platform, title, SEARCH_LIMIT, 1), title, artist);
        }
        if (match == null) return null;

        GdStudioMusicService.ResolveResult resolved =
                GdStudioMusicService.resolveTrack(platform, match.id, quality);
        if (resolved == null || resolved.url == null || resolved.url.trim().isEmpty()) return null;
        return new Result(resolved.url.trim(), safe(resolved.format), candidate.key, candidate.label,
                match.name, match.artist);
    }
    // ── UNM find.js：关键词构造 ─────────────────────────────────────────────

    /** 清掉 {@code （cover:xxx）}/{@code （翻自xxx）} 这类会把检索带偏的后缀。 */
    static String cleanTitle(String name) {
        return COVER_NOISE.matcher(safe(name)).replaceAll("").trim();
    }

    /**
     * 艺人列表按 UNM 的 {@code limit()} 截断：累计长度超过 15 就停，但至少留一个。
     *
     * <p>为什么要截：合唱曲的艺人可能有七八个，全拼进关键词之后没有任何平台搜得到。</p>
     */
    static String keywordArtists(Music music) {
        if (music == null) return "";
        List<String> names = new ArrayList<String>();
        List<Artist> artists = music.getArtists();
        if (artists != null) {
            for (Artist artist : artists) {
                if (artist == null) continue;
                String name = safe(artist.getName()).trim();
                if (name.isEmpty() || "Unknown".equalsIgnoreCase(name)) continue;
                names.add(name);
            }
        }
        if (names.isEmpty()) return "";

        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (String name : names) {
            if (used + name.length() > ARTIST_KEYWORD_BUDGET && builder.length() > 0) break;
            if (builder.length() > 0) builder.append(' ');
            builder.append(name);
            used += name.length();
        }
        return builder.toString();
    }

    /**
     * 检索关键词。
     *
     * <p>UNM 拼的是 {@code "曲名 - 艺人A / 艺人B"}；这里去掉了 {@code -} 与 {@code /} 两个分隔符，
     * 只保留同样的词。原因是本项目的三条搜索后端（酷狗 / QQ / GD）都是关键词匹配，
     * 分隔符只会成为额外的噪声 token，而词本身完全一致。</p>
     */
    static String searchQuery(String title, String artist) {
        String name = safe(title).trim();
        String performer = safe(artist).trim();
        return performer.isEmpty() ? name : name + ' ' + performer;
    }

    // ── UNM select.js：候选挑选 ─────────────────────────────────────────────

    /**
     * 官方源的挑选：先用曲名 / 艺人双闸筛出合格候选，再按 UNM 的规则取<b>第一条时长差
     * 小于 5 秒</b>的；都没有时长信息时退回匹配度最高的那条。
     *
     * <p>比 UNM 严的地方：UNM 在没有时长命中时直接用第一条结果，本项目不允许 —— 那会在
     * 冷门曲上安静地放成另一首同名歌。</p>
     */
    static Music selectOfficial(List<Music> results, String title, String artist, long durationMillis) {
        if (results == null || results.isEmpty()) return null;
        boolean artistKnown = !safe(artist).isEmpty();
        Music best = null;
        double bestScore = 0.0;
        int window = Math.min(SELECT_WINDOW, results.size());
        for (int index = 0; index < window; index++) {
            Music candidate = results.get(index);
            if (candidate == null) continue;
            double score = matchScore(candidate.getName(), candidate.getArtistsName(), title, artist, artistKnown);
            if (score <= 0.0) continue;
            long candidateDuration = candidate.getDuration();
            if (durationMillis > 0L && candidateDuration > 0L
                    && Math.abs(candidateDuration - durationMillis) < DURATION_TOLERANCE_MILLIS) {
                return candidate;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** GD 源的挑选：搜索响应没有时长，只能走相似度。 */
    static GdStudioMusicService.GdTrack selectGd(List<GdStudioMusicService.GdTrack> tracks,
                                                String title, String artist) {
        if (tracks == null || tracks.isEmpty()) return null;
        boolean artistKnown = !safe(artist).isEmpty();
        GdStudioMusicService.GdTrack best = null;
        double bestScore = 0.0;
        int window = Math.min(SELECT_WINDOW, tracks.size());
        for (int index = 0; index < window; index++) {
            GdStudioMusicService.GdTrack track = tracks.get(index);
            if (track == null || safe(track.id).isEmpty()) continue;
            double score = matchScore(track.name, track.artist, title, artist, artistKnown);
            if (score > bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    /**
     * 双闸打分，阈值与 {@code GdStudioMusicService.bestMatch} 保持一致（曲名 .5 / 艺人 .34），
     * 这样解灰救援和 GD 的跨源救援不会出现「一边认另一边不认」的分裂行为。
     *
     * @return 0 表示不合格
     */
    private static double matchScore(String candidateName, String candidateArtist,
                                     String title, String artist, boolean artistKnown) {
        double nameScore = titleSimilarity(candidateName, title);
        if (nameScore < .5) return 0.0;
        double artistScore = similarity(candidateArtist, artist);
        if (artistKnown && !safe(candidateArtist).isEmpty() && artistScore < .34) return 0.0;
        return nameScore * 2.0 + artistScore;
    }

    // ── 相似度（与 GdStudioMusicService 同一套算法）─────────────────────────

    private static double titleSimilarity(String left, String right) {
        String a = normalizeForMatch(left);
        String b = normalizeForMatch(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return .9;
        Set<String> first = bigrams(a);
        Set<String> second = bigrams(b);
        if (first.isEmpty() || second.isEmpty()) return 0.0;
        int shared = 0;
        for (String gram : first) if (second.contains(gram)) shared++;
        return (2.0 * shared) / (first.size() + second.size());
    }

    private static Set<String> bigrams(String value) {
        Set<String> grams = new HashSet<String>();
        for (int index = 0; index + 1 < value.length(); index++) {
            grams.add(value.substring(index, index + 2));
        }
        if (grams.isEmpty() && !value.isEmpty()) grams.add(value);
        return grams;
    }

    private static String normalizeForMatch(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    private static double similarity(String left, String right) {
        Set<Character> first = characters(left);
        Set<Character> second = characters(right);
        if (first.isEmpty() || second.isEmpty()) return 0.0;
        int shared = 0;
        for (Character character : first) if (second.contains(character)) shared++;
        return (double) shared / Math.max(first.size(), second.size());
    }

    private static Set<Character> characters(String value) {
        Set<Character> characters = new HashSet<Character>();
        String normalized = normalizeForMatch(value);
        for (int index = 0; index < normalized.length(); index++) {
            characters.add(normalized.charAt(index));
        }
        return characters;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}