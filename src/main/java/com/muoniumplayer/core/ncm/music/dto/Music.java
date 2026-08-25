package com.muoniumplayer.core.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import top.fpsmaster.music.Track;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Data
public class Music {

    private static final long PLAY_URL_RESOLVE_TIMEOUT_SECONDS = 10L;
    // GD Studio proxies can resolve a valid third-party CDN link more slowly than official APIs.
    private static final long GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS = 35L;
    private static final AtomicInteger URL_RESOLVER_THREAD_ID = new AtomicInteger();
    private static final ExecutorService URL_RESOLVER = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "Music URL Resolver-" + URL_RESOLVER_THREAD_ID.incrementAndGet());
            // 底层网络库即使忽略 interrupt，也不能阻止游戏/IDE 退出。
            thread.setDaemon(true);
            return thread;
        }
    });

    static final int STEREO = 8192;
    static final int INSTRUMENTAL = 131072;
    static final int DOLBY_ATMOS = 262144;
    static final int DIRTY = 1048576;
    static final long HIRES = 17179869184L;

    @SerializedName("name")
    private final String name;

    @SerializedName("mainTitle")
    private final String mainTitle;

    @SerializedName("additionalTitle")
    private final String additionalTitle;

    @SerializedName("id")
    private final long id;

    @SerializedName("ar")
    private final List<Artist> artists;

    @SerializedName("alia")
    private final List<String> aliasName;

    @SerializedName("al")
    private final Album album;

    @SerializedName("dt")
    private final long duration;

    /**
     * Length measured from the decoded stream. GD Studio search responses carry no duration, so
     * without this the list, Coverflow and progress readouts would stay at 00:00 for those tracks.
     * {@code transient} keeps it out of Lombok equality and out of serialized playlist data.
     */
    private transient volatile long decodedDurationMillis;

    @SerializedName("mark")
    private final long featureFlag;

    @SerializedName("publishTime")
    private final long publishTime;

    @SerializedName("tns")
    private final List<String> translatedName;

    /**
     * NetEase song-detail privilege fields. They remain distinct from the resolved
     * audio quality: membership/album restrictions are licensing metadata.
     */
    @SerializedName("fee")
    private int fee;

    @SerializedName("payed")
    private int payed;

    private transient String artistsName, translatedNames;

    /** 跨平台字段。旧网易云 JSON 不包含这些字段时会自然回退到 NETEASE + 数字 id。 */
    private transient MusicPlatform source = MusicPlatform.NETEASE;
    private transient String sourceId;
    private transient String sourceMid;
    private transient String externalCoverUrl;
    /** GD Studio aggregated source key (netease/joox/...) for freely selected GD tracks. */
    private transient String gdPlatform;
    private transient String gdPicId;
    private transient String gdLyricId;
    /**
     * 酷狗的曲目主键。
     *
     * <p>酷狗不像网易云/QQ 那样有一个贯穿全站的歌曲 id：取流、取歌词都按<b>文件 hash</b> 定位，
     * 而同一首歌的三个音质是三个不同的 hash。所以这三个都要留着，取流时按音质挑一个再逐档回退。</p>
     */
    private transient String kugouHash = "";
    private transient String kugouHash320 = "";
    private transient String kugouHashLossless = "";
    private transient String kugouAlbumId = "";
    private transient String kugouAlbumAudioId = "";
    /** 带 {@code {size}} 占位符的酷狗封面模板。 */
    private transient String kugouCoverTemplate = "";
    /**
     * 解灰状态。两个字段都是 {@code transient}：它们描述的是「这一次点播发生了什么」，
     * 不该进歌单落盘，也不该参与 Lombok 的相等性判断。
     *
     * <p>{@code greyDetected} 由 api-enhanced 的「音乐是否可用」判定（见
     * {@code CloudMusicApi#isMusicPlayable}），只在官方链路已经取不到流之后问一次；
     * {@code unblockedSourceLabel} 非空表示这一首现在放的是第三方源的流。</p>
     */
    private transient volatile boolean greyDetected;
    private transient volatile String unblockedSourceLabel = "";
    private transient boolean vip;
    /** True only when the NetEase payload explicitly marks this track as a cloud-drive track. */
    private transient boolean cloudSong;
    /** Highest provider tier advertised by song/privilege metadata; empty means not supplied. */
    private transient String highestQualityLabel = "";
    private transient Track cadenceTrack;

    /** Actual stream quality resolved for the current playback request. */
    private transient volatile PlaybackQuality playbackQuality = PlaybackQuality.UNKNOWN;

    /**
     * Gson may allocate this DTO without running field initializers when it decodes
     * the immutable NetEase response model. Keep the render-facing state non-null
     * even for such deserialized instances.
     */
    public PlaybackQuality getPlaybackQuality() {
        PlaybackQuality current = playbackQuality;
        if (current == null) {
            playbackQuality = PlaybackQuality.UNKNOWN;
            return PlaybackQuality.UNKNOWN;
        }
        return current;
    }

    public enum PlaybackQuality {
        UNKNOWN(""),
        STANDARD(""),
        HQ("HQ"),
        LOSSLESS("无损");

        private final String badge;

        PlaybackQuality(String badge) {
            this.badge = badge;
        }

        public String getBadge() {
            return badge;
        }

        public boolean hasBadge() {
            return !badge.isEmpty();
        }

        /** Human-readable resolved stream tier for runtime status notifications. */
        public String getDisplayName() {
            switch (this) {
                case LOSSLESS:
                    return "无损";
                case HQ:
                    return "HQ";
                case STANDARD:
                    return "标准";
                case UNKNOWN:
                default:
                    return "未知";
            }
        }
    }

    /** Applies optional NetEase song/privilege fields without assuming they exist for QQ/Cadence tracks. */
    public void applyNeteaseMetadata(JsonObject song, JsonObject privilege) {
        if (!isNetease()) return;
        cloudSong = cloudSong || isCloudMarked(song) || isCloudMarked(privilege);
        if (privilege != null) {
            if (privilege.has("fee") && !privilege.get("fee").isJsonNull()) {
                fee = safeInt(privilege, "fee", fee);
            }
            if (privilege.has("payed") && !privilege.get("payed").isJsonNull()) {
                payed = safeInt(privilege, "payed", payed);
            }
            considerHighestQuality(readString(privilege, "maxBrLevel"), readLong(privilege, "maxbr"));
            considerHighestQuality(readString(privilege, "playMaxBrLevel"), readLong(privilege, "playMaxBr"));
            considerHighestQuality(readString(privilege, "downloadMaxBrLevel"), readLong(privilege, "downloadMaxBr"));
            considerHighestQuality(readString(privilege, "plLevel"), readLong(privilege, "br"));
            considerHighestQuality(readString(privilege, "dlLevel"), 0L);
        }
        if (song != null) {
            considerHighestQuality(readString(song, "maxBrLevel"), readLong(song, "maxbr"));
        }
        if ((highestQualityLabel == null || highestQualityLabel.isEmpty()) && isHiRes()) {
            highestQualityLabel = "Hi-Res";
        }
    }

    public boolean isCloudSong() {
        return cloudSong || (isNetease() && CloudMusic.isUserCloudSong(id));
    }

    public String getHighestQualityLabel() {
        if (highestQualityLabel == null || highestQualityLabel.trim().isEmpty()) {
            return isHiRes() ? "Hi-Res" : "";
        }
        return highestQualityLabel;
    }

    private void considerHighestQuality(String level, long bitrate) {
        String candidate = toQualityLabel(level, bitrate);
        if (qualityRank(candidate) > qualityRank(highestQualityLabel)) {
            highestQualityLabel = candidate;
        }
    }

    private static String toQualityLabel(String level, long bitrate) {
        String normalized = nonNull(level).trim().toLowerCase(Locale.ROOT);
        if ("jymaster".equals(normalized) || "hires".equals(normalized)) return "Hi-Res";
        if ("lossless".equals(normalized) || "jyeffect".equals(normalized) || bitrate >= 900000L) return "无损";
        if ("higher".equals(normalized) || "exhigh".equals(normalized) || "sky".equals(normalized)
                || bitrate >= 256000L) return "HQ";
        if ("standard".equals(normalized) || bitrate > 0L) return "标准";
        return "";
    }

    private static int qualityRank(String label) {
        if ("Hi-Res".equals(label)) return 4;
        if ("无损".equals(label)) return 3;
        if ("HQ".equals(label)) return 2;
        if ("标准".equals(label)) return 1;
        return 0;
    }

    private static boolean isCloudMarked(JsonObject object) {
        if (object == null) return false;
        return isTruthy(object, "cloudSong") || isTruthy(object, "cloudsong")
                || isTruthy(object, "isCloudSong") || isTruthy(object, "cloudStatus")
                || isTruthy(object, "cloudSongId");
    }

    private static boolean isTruthy(JsonObject object, String property) {
        if (!object.has(property) || object.get(property).isJsonNull()) return false;
        try {
            if (object.get(property).isJsonObject()) return true;
            if (object.get(property).getAsJsonPrimitive().isBoolean()) return object.get(property).getAsBoolean();
            if (object.get(property).getAsJsonPrimitive().isNumber()) return object.get(property).getAsLong() != 0L;
            String value = object.get(property).getAsString();
            return "true".equalsIgnoreCase(value) || (!"0".equals(value) && !value.trim().isEmpty());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int safeInt(JsonObject object, String property, int fallback) {
        try {
            return object.get(property).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
    public static Music fromCadenceTrack(Track track) {
        MusicPlatform platform = MusicPlatform.fromCadence(track.getSource());
        String id = nonNull(track.getId());
        long stableId = platform == MusicPlatform.NETEASE
                ? parseNeteaseId(id, track.getMid())
                : stableLong(platform.name() + ':' + id + ':' + nonNull(track.getMid()));

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.getArtists());
        if (!artists.trim().isEmpty()) {
            String[] names = artists.split("\\s*/\\s*|\\s*,\\s*");
            for (String artistName : names) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong(platform.name() + ":artist:" + artistName),
                            artistName.trim(), Collections.emptyList(), Collections.emptyList()));
                }
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.getAlbum());
        Album album = new Album(stableLong(platform.name() + ":album:" + albumName), albumName,
                nonNull(track.getCoverUrl()), Collections.emptyList());

        Music music = new Music(nonNull(track.getName()), nonNull(track.getName()), "", stableId,
                artistList, Collections.emptyList(), album, track.getDurationMs(), 0L, 0L,
                Collections.emptyList());
        music.source = platform;
        music.sourceId = id;
        music.sourceMid = track.getMid();
        music.externalCoverUrl = track.getCoverUrl();
        music.vip = track.getVip();
        music.cadenceTrack = track;
        return music;
    }

    /**
     * 由酷狗曲目构造一首 {@link Music}。
     *
     * <p>酷狗没有全站歌曲 id，所以 {@code id} 用 hash 派生的稳定哈希 —— 它要稳定，因为封面缓存、
     * 下载缓存、播放历史全都按 {@link #getStableKey()} 落盘，每次搜索换一个 id 会让缓存永远打不中。</p>
     */
    public static Music fromKuGouTrack(com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track track) {
        if (track == null) return null;
        long stableId = stableLong("kugou:" + track.hash);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            for (String artistName : artists.split("\\s*[、,/]\\s*")) {
                if (artistName.trim().isEmpty()) continue;
                artistList.add(new Artist(stableLong("kugou:artist:" + artistName), artistName.trim(),
                        Collections.emptyList(), Collections.emptyList()));
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        String cover = com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.cover(track.coverTemplate, 300);
        Album album = new Album(stableLong("kugou:album:" + albumName), albumName, cover,
                Collections.emptyList());

        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.KUGOU;
        music.sourceId = nonNull(track.hash);
        music.sourceMid = nonNull(track.albumAudioId);
        music.kugouHash = nonNull(track.hash);
        music.kugouHash320 = nonNull(track.hash320);
        music.kugouHashLossless = nonNull(track.hashLossless);
        music.kugouAlbumId = nonNull(track.albumId);
        music.kugouAlbumAudioId = nonNull(track.albumAudioId);
        music.kugouCoverTemplate = nonNull(track.coverTemplate);
        music.externalCoverUrl = cover;
        music.vip = track.vip;
        return music;
    }

    /** 还原成酷狗 API 的曲目模型，供取流与取歌词使用。 */
    public com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track toKuGouTrack() {
        com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track track =
                new com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track();
        track.hash = nonNull(kugouHash).isEmpty() ? getSourceId() : kugouHash;
        track.hash320 = nonNull(kugouHash320);
        track.hashLossless = nonNull(kugouHashLossless);
        track.albumId = nonNull(kugouAlbumId);
        track.albumAudioId = nonNull(kugouAlbumAudioId);
        track.name = nonNull(name);
        track.artist = getArtistsName();
        track.album = album == null ? "" : nonNull(album.getName());
        track.durationMillis = getDuration();
        track.coverTemplate = nonNull(kugouCoverTemplate);
        track.vip = vip;
        return track;
    }

    /** Builds a freely selectable GD Studio track from a GD API search result. */
    public static Music fromGdTrack(GdStudioMusicService.GdTrack track, String platformKey) {
        if (track == null) return null;
        String id = nonNull(track.id);
        String returnedPlatform = nonNull(track.source).trim().toLowerCase(Locale.ROOT);
        String configuredPlatform = platformKey == null ? "" : platformKey.trim().toLowerCase(Locale.ROOT);
        String platform = GdStudioMusicService.isKnownPlatform(returnedPlatform) ? returnedPlatform : configuredPlatform;
        long stableId = stableLong("gd:" + platform + ':' + id);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            String[] names = artists.split("\\s*[、,/]\\s*");
            for (String artistName : names) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong("gd:artist:" + artistName), artistName.trim(),
                            Collections.emptyList(), Collections.emptyList()));
                }
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        Album album = new Album(stableLong("gd:" + platform + ":album:" + albumName), albumName, "",
                Collections.emptyList());

        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, 0L, 0L, 0L, Collections.emptyList());
        music.source = MusicPlatform.GD;
        music.sourceId = id;
        music.gdPlatform = platform;
        music.gdPicId = nonNull(track.picId);
        music.gdLyricId = nonNull(track.lyricId);
        music.externalCoverUrl = "";
        return music;
    }

    /** Source metadata duration, falling back to the length measured while decoding. */
    public long getDuration() {
        return duration > 0L ? duration : decodedDurationMillis;
    }

    /** Records the decoder-reported length for a source that shipped no duration metadata. */
    public void applyDecodedDuration(long millis) {
        if (millis > 0L && duration <= 0L) {
            decodedDurationMillis = millis;
        }
    }

    public Track toCadenceTrack() {
        if (cadenceTrack != null) return cadenceTrack;
        cadenceTrack = new Track(source.toCadenceSource(), getSourceId(), sourceMid, nonNull(name),
                getArtistsName(), album == null ? "" : nonNull(album.getName()), duration,
                getBaseCoverUrl(), vip);
        return cadenceTrack;
    }

    public String getSourceId() {
        return sourceId == null || sourceId.trim().isEmpty() ? String.valueOf(id) : sourceId;
    }

    public MusicPlatform getSource() {
        return source == null ? MusicPlatform.NETEASE : source;
    }

    public boolean isQQ() {
        return getSource() == MusicPlatform.QQ;
    }

    public boolean isNetease() {
        return getSource() == MusicPlatform.NETEASE;
    }

    public boolean isGd() {
        return getSource() == MusicPlatform.GD;
    }

    public boolean isKuGou() {
        return getSource() == MusicPlatform.KUGOU;
    }

    /**
     * Returns whether the provider marks this track as membership or paid-content restricted.
     * Cadence supplies the flag for QQ tracks; NetEase fee is copied from its privilege payload.
     */
    public boolean hasVipRestriction() {
        return vip || (isNetease() && fee != 0);
    }

    /** True only for NetEase digital-album purchase tracks, not ordinary album metadata. */
    public boolean isDigitalAlbumTrack() {
        return isNetease() && fee == 4;
    }

    public String getStableKey() {
        return getSource().name().toLowerCase() + '_' + Long.toUnsignedString(id);
    }

    public final Location getCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover.png");
    }

    public final Location getBlurredCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_blurred.png");
    }

    /** Animated cover texture when NetEase provides a supported dynamic image; otherwise absent. */
    public final Location getDynamicCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_dynamic.png");
    }
    public final Location getSmallCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_small.png");
    }

    public String getArtistsName() {
        if (this.artistsName == null) {
            this.artistsName = this.buildArtistsNames();
            if (this.artistsName.isEmpty()) this.artistsName = "Unknown";
        }
        return this.artistsName;
    }

    public String getTranslatedNames() {
        if (this.translatedNames == null) this.translatedNames = this.buildTranslatedNames();
        return this.translatedNames;
    }

    private String buildTranslatedNames() {
        if (this.translatedName == null || this.translatedName.isEmpty()) return "";
        return String.join(", ", this.translatedName);
    }

    private String buildArtistsNames() {
        if (this.artists == null || this.artists.isEmpty()) return "";
        return this.artists.stream().filter(Objects::nonNull).map(Artist::getName)
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private String getBaseCoverUrl() {
        if (externalCoverUrl != null && !externalCoverUrl.trim().isEmpty()) return externalCoverUrl;
        return album == null ? "" : nonNull(album.getPicUrl());
    }

    public String getCoverUrl(int size) {
        String base = getBaseCoverUrl();
        if (base.isEmpty()) return "";
        if (isKuGou()) {
            // 酷狗封面是带 {size} 占位符的模板，尺寸要替换进去而不是拼查询参数。
            String template = kugouCoverTemplate == null || kugouCoverTemplate.trim().isEmpty()
                    ? base : kugouCoverTemplate;
            String resolved = com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.cover(template, size);
            return (resolved.isEmpty() ? base : resolved).replace("http://", "https://");
        }
        if (isQQ() || isGd()) {
            // QQ/GD 封面 URL 已带平台尺寸，避免追加网易云专用 param 导致 404。
            return base.replace("http://", "https://");
        }
        return base + "?param=" + size + "y" + size;
    }

    /**
     * Resolves the GD Studio album-art URL (network call). Safe to call from background
     * cover-loading threads only; the render path uses the cached {@link #getCoverUrl(int)}.
     */
    public String resolveGdCoverUrl(int size) {
        if (!isGd()) return getCoverUrl(size);
        String cached = externalCoverUrl;
        if (cached != null && !cached.trim().isEmpty()) {
            return cached.replace("http://", "https://");
        }
        String resolved = "";
        try {
            resolved = GdStudioMusicService.getPicUrl(getGdPlatform(), gdPicId, size);
        } catch (Throwable ignored) {
        }
        if (!resolved.isEmpty()) externalCoverUrl = resolved;
        return resolved.replace("http://", "https://");
    }

    /** 更新歌曲播放次数；跨平台曲目不调用网易云 scrobble。 */
    @Deprecated
    public void updPlayCount(PlayList pl, float sec) {
        if (!isNetease()) return;
    }

    /**
     * 获得歌曲播放 URL。每一次点歌都会重新解析，不缓存失败结果。
     *
     * 默认配置是无损优先：先请求无损；曲目没有无损、账号没有 VIP、接口返回空链接、
     * 解析异常或超时时，再明确降级到标准音质。网易云在主解析器失败后继续切换
     * 备用解析器；QQ 仅能使用 Cadence，但同样执行无损到标准的降级。
     */
    public Tuple<String, String> getPlayUrl() {
        // Never show a quality badge carried over from a previous failed resolve.
        this.playbackQuality = PlaybackQuality.UNKNOWN;
        if (isGd()) {
            return resolveGdPlayUrl();
        }
        if (isKuGou()) {
            return resolveKuGouPlayUrl();
        }
        if (isQQ()) {
            return resolveCadenceWithStandardFallback();
        }

        // Cadence 搜索得到的网易云歌曲仍优先保持原来源，且该来源内部先尝试无损。
        if (cadenceTrack != null) {
            Tuple<String, String> result = resolveCadenceWithStandardFallback();
            if (result != null) return result;
            result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
                @Override
                public Tuple<String, String> call() {
                    return resolveWithBuiltInNeteaseApi();
                }
            }, "built-in NetEase API");
            if (result != null) return result;
            return resolveNeteaseUnblock();
        }

        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithBuiltInNeteaseApi();
            }
        }, "built-in NetEase API");
        if (result != null) return result;
        result = resolveCadenceWithStandardFallback();
        if (result != null) return result;
        return resolveNeteaseUnblock();
    }

    /**
     * 最后一道网易云兜底：官方两条链路都没有流之后，按 UnblockNeteaseMusic 的流程去第三方源
     * 重新匹配一条可播链接。
     *
     * <p>刻意放在整条链路的<b>最后</b>，而且只对当前这一首生效 —— 主动对整张歌单探测可用性会
     * 瞬间打爆网易云与 GD 音乐台的额度。解灰失败时仍然返回 {@code null}，交回既有的
     * 「没有可用音频源 → 停下等用户操作」流程，不会自动跳下一曲。</p>
     */
    private Tuple<String, String> resolveNeteaseUnblock() {
        if (!isNetease()) return null;
        this.unblockedSourceLabel = "";
        if (!com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings.isEnabled()) return null;

        // 先问一次「音乐是否可用」，把真正的灰色歌和一次网络抖动区分开。判定只影响歌曲行上的
        // 「灰」标，不影响是否尝试解灰 —— 官方流已经拿不到了，无论原因都该去找替代源。
        this.greyDetected = !com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.isPlayableOnOfficial(this);

        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.Result unblocked =
                        com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.unblock(Music.this, requestedQuality);
                if (unblocked == null) return null;
                Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(unblocked.url, unblocked.format));
                if (normalized == null) return null;
                unblockedSourceLabel = unblocked.sourceLabel;
                playbackQuality = detectCadencePlaybackQuality(normalized.getB(), requestedQuality);
                considerHighestQuality(normalized.getB(), 0L);
                return normalized;
            }
        }, "NetEase grey unblock", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /** 非空表示这一首正在播第三方源解出来的流；值就是徽标上显示的源名。 */
    public String getUnblockedSourceLabel() {
        String label = unblockedSourceLabel;
        return label == null ? "" : label;
    }

    /** 官方链路取不到流，且「音乐是否可用」也判定为不可用。 */
    public boolean isGreyDetected() {
        return greyDetected;
    }

    /**
     * Resolves a freely selected GD Studio track. When the preferred platform fails to parse or
     * play, it sends the documented URL request to the exact platform/id returned by search.
     */
    private Tuple<String, String> resolveGdPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        final String platform = getGdPlatform().isEmpty() ? GdStudioSourceSettings.getPlatform() : getGdPlatform();
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() throws Exception {
                DownloadDynamicIsland.showGdSourceResolving("GD音乐台 · " + platform.toUpperCase(Locale.ROOT));
                // Protected resolve: the documented bitrate ladder on the selected source first, then
                // one healthy source re-matched by title/artist. Only a fully failed chain reports failure.
                GdStudioMusicService.ResolveResult result = GdStudioMusicService.resolveTrackWithFallback(
                        platform, getSourceId(), getName(), getArtistsName(), requestedQuality);
                if (result == null) {
                    String reason = GdStudioMusicService.getHealth(platform).lastError;
                    DownloadDynamicIsland.showGdSourceFailure("GD音乐台",
                            reason.isEmpty() ? "当前平台未返回可播放链接" : reason);
                    return null;
                }
                DownloadDynamicIsland.showGdSourceResolved(result.isFallback()
                        ? "已回退 " + GdStudioMusicService.displayName(result.platform)
                        : "GD音乐台 · " + result.platform.toUpperCase(Locale.ROOT), result.format);
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "GD Studio", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * Re-resolves a GD Studio track on another healthy source after byte-level inspection rejects
     * the stream the selected source handed out (DASH video, HTML error page, truncated payload).
     */
    public Tuple<String, String> getGdCrossSourceFallbackPlayUrl() {
        if (!isGd()) return null;
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        final String platform = getGdPlatform().isEmpty() ? GdStudioSourceSettings.getPlatform() : getGdPlatform();
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() throws Exception {
                GdStudioMusicService.ResolveResult result = GdStudioMusicService.resolveCrossSource(
                        platform, getName(), getArtistsName(), requestedQuality);
                if (result == null) return null;
                DownloadDynamicIsland.showGdSourceResolved("已回退 "
                        + GdStudioMusicService.displayName(result.platform), result.format);
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "GD cross-source fallback", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * Re-resolves a NetEase song as a known-playable standard MP3 stream after
     * byte-level inspection rejects the initially returned payload.
     */
    public Tuple<String, String> getStandardMp3PlayUrl() {
        if (!isNetease()) {
            return null;
        }
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveNeteasePlayUrl(CloudMusicApi.songUrlStandardMp3(id),
                        "post-download standard MP3 fallback");
            }
        }, "post-download standard MP3 fallback");
    }
    /**
     * 酷狗取流。
     *
     * <p>音质降级由 {@code KuGouMusicApi.songUrl} 自己在三个 hash 之间做，所以这里只需要一次调用：
     * 上游对每个音质是独立授权的，把降级放在能同时看到三个 hash 的那一层，比在这里重复三次请求更省。</p>
     */
    private Tuple<String, String> resolveKuGouPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                Tuple<String, String> resolved = normalizePlayUrl(
                        com.muoniumplayer.core.ncm.music.KuGouMusicService.getSongUrl(Music.this, requestedQuality));
                if (resolved != null) {
                    playbackQuality = detectCadencePlaybackQuality(resolved.getB(), requestedQuality);
                    considerHighestQuality(resolved.getB(), 0L);
                }
                return resolved;
            }
        }, "KuGou " + requestedQuality.getQuality());
    }

    /** Tries the configured tier once, then a known-playable standard Cadence stream. */
    private Tuple<String, String> resolveCadenceWithStandardFallback() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(requestedQuality);
            }
        }, "Cadence " + requestedQuality.getQuality());
        if (result != null || requestedQuality == Quality.STANDARD) return result;

        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(Quality.STANDARD);
            }
        }, "Cadence standard fallback");
    }

    private Tuple<String, String> resolveWithCadence(Quality requestedQuality) {
        Tuple<String, String> resolved = normalizePlayUrl(CadenceMusicService.getSongUrl(this, requestedQuality));
        if (resolved != null) {
            this.playbackQuality = detectCadencePlaybackQuality(resolved.getB(), requestedQuality);
            considerHighestQuality(resolved.getB(), 0L);
        }
        return resolved;
    }

    private Tuple<String, String> resolveWithBuiltInNeteaseApi() {
        // The default configuration requests lossless first. If the user deliberately
        // picks another tier in the module setting, honor that explicit preference; any
        // unavailable/VIP-gated/failed primary request still falls back to standard MP3.
        Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> preferred = resolveNeteasePlayUrl(
                CloudMusicApi.songUrlV1(this.id, requestedQuality.getQuality().toLowerCase(Locale.ROOT)),
                "requested quality");
        if (preferred != null) return preferred;

        return resolveNeteasePlayUrl(CloudMusicApi.songUrlStandardMp3(this.id), "standard MP3 fallback");
    }

    private Tuple<String, String> resolveNeteasePlayUrl(RequestUtil.RequestAnswer answer, String requestDescription) {
        try {
            if (answer == null || answer.getStatus() != 200) return null;

            JsonObject result = answer.toJsonObject();
            if (result == null || !result.has("data") || !result.get("data").isJsonArray()
                    || result.getAsJsonArray("data").size() == 0) return null;

            JsonObject music = result.getAsJsonArray("data").get(0).getAsJsonObject();
            if ((music.has("code") && !music.get("code").isJsonNull() && music.get("code").getAsInt() != 200)
                    || !music.has("url") || music.get("url").isJsonNull()) return null;

            String url = music.get("url").getAsString();
            if (url == null || url.trim().isEmpty()) return null;

            String type = "mp3";
            if (music.has("type") && !music.get("type").isJsonNull()) {
                String responseType = music.get("type").getAsString();
                if (responseType != null && !responseType.trim().isEmpty()) type = responseType;
            }
            Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(url, type));
            if (normalized != null) {
                this.playbackQuality = detectNeteasePlaybackQuality(music, normalized.getB());
                considerHighestQuality(readString(music, "level"), readLong(music, "br"));
            }
            return normalized;
        } catch (Throwable throwable) {
            System.err.println("[Music/NetEase] " + requestDescription + " URL resolver failed for "
                    + getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }
    private Tuple<String, String> resolveWithTimeout(Callable<Tuple<String, String>> resolver, String resolverName) {
        return resolveWithTimeout(resolver, resolverName, PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    private Tuple<String, String> resolveWithTimeout(Callable<Tuple<String, String>> resolver, String resolverName,
                                                      long timeoutSeconds) {
        Future<Tuple<String, String>> future = URL_RESOLVER.submit(resolver);
        try {
            return normalizePlayUrl(future.get(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台", "获取播放链接超时");
            }
            System.err.println("[Music] " + resolverName + " URL resolver timed out for " + getStableKey());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台", "获取播放链接被中断");
            }
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            future.cancel(true);
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台",
                        cause.getMessage() == null || cause.getMessage().trim().isEmpty()
                                ? "获取播放链接失败" : cause.getMessage());
            }
            System.err.println("[Music] " + resolverName + " URL resolver failed for " + getStableKey()
                    + ": " + cause.getMessage());
        }
        return null;
    }

    private PlaybackQuality detectNeteasePlaybackQuality(JsonObject response, String format) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }

        String level = readString(response, "level").toLowerCase(Locale.ROOT);
        if ("lossless".equals(level) || "hires".equals(level)
                || "jyeffect".equals(level) || "jymaster".equals(level)) {
            return PlaybackQuality.LOSSLESS;
        }

        long bitrate = readLong(response, "br");
        if (bitrate >= 256000L || "higher".equals(level) || "exhigh".equals(level)
                || "sky".equals(level)) {
            return PlaybackQuality.HQ;
        }
        return PlaybackQuality.STANDARD;
    }

    private PlaybackQuality detectCadencePlaybackQuality(String format, Quality requestedQuality) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }
        // Cadence does not provide a bitrate for every provider. A lossless request
        // that returns a lossy container is treated as a real standard fallback.
        return (requestedQuality == Quality.HIGHER
                || requestedQuality == Quality.EXHIGH || requestedQuality == Quality.SKY)
                ? PlaybackQuality.HQ : PlaybackQuality.STANDARD;
    }

    private static boolean isLosslessFormat(String format) {
        String normalized = nonNull(format).trim().toLowerCase(Locale.ROOT);
        return "flac".equals(normalized) || "wav".equals(normalized);
    }

    private static String readString(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            return nonNull(object.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLong(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Tuple<String, String> normalizePlayUrl(Tuple<String, String> result) {
        if (result == null || result.getA() == null || result.getA().trim().isEmpty()) return null;

        String url = result.getA().trim();
        String type = result.getB() == null ? "" : result.getB().trim().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(';');
        if (separator >= 0) type = type.substring(0, separator).trim();
        if (type.startsWith("audio/")) type = type.substring("audio/".length());
        if ("mpeg".equals(type) || "mpeg3".equals(type) || "x-mp3".equals(type)) type = "mp3";
        if ("x-flac".equals(type)) type = "flac";
        if ("x-wav".equals(type) || "wave".equals(type)) type = "wav";
        if ("adts".equals(type) || "x-aac".equals(type)) type = "aac";
        if ("mp4a".equals(type) || "m4a".equals(type)) type = "m4a";

        if (!isSupportedFormat(type)) {
            type = inferFormat(url);
        }
        return isSupportedFormat(type) ? new Tuple<>(url, type) : null;
    }

    private static boolean isSupportedFormat(String type) {
        // Downloaded bytes are still verified by CloudMusic before playback. AAC/M4A reaches
        // the existing decoder; generic MP4 reaches the file-header rejection/notification path.
        return "mp3".equals(type) || "flac".equals(type) || "wav".equals(type)
                || "aac".equals(type) || "m4a".equals(type) || "mp4".equals(type);
    }

    private static String inferFormat(String url) {
        String clean = nonNull(url).toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "";
    }

    public void setLike(boolean like) {
        if (isNetease()) CloudMusicApi.like(this.id, like);
    }

    public boolean isInstrumental() { return (this.featureFlag & INSTRUMENTAL) != 0; }
    public boolean isDolbyAtmos() { return (this.featureFlag & DOLBY_ATMOS) != 0; }
    public boolean isDirty() { return (this.featureFlag & DIRTY) != 0; }
    public boolean isHiRes() { return (this.featureFlag & HIRES) != 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Music)) return false;
        Music music = (Music) o;
        return getSource() == music.getSource() && getSourceId().equals(music.getSourceId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSource(), getSourceId());
    }

    private static long parseNeteaseId(String id, String mid) {
        try {
            return Long.parseLong(nonNull(id));
        } catch (NumberFormatException ignored) {
            return stableLong(MusicPlatform.NETEASE.name() + ':' + nonNull(id) + ':' + nonNull(mid));
        }
    }

    private static long stableLong(String value) {
        byte[] bytes = nonNull(value).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
