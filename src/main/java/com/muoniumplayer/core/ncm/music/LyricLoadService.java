package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Network, embedded-tag and bundled-YRC lyric resolution.
 *
 * <p>The result is intentionally not published here. CloudMusic retains session validation,
 * cross-thread state publication and main-thread timeline application.</p>
 *
 * <p><b>Priority.</b> The AMLL TTML database is asked first: its entries are hand-checked
 * word-by-word timelines and beat every platform response, so once it answers nothing else is
 * requested at all. QQ Music's QRC comes second — still syllable-level, with much wider coverage
 * than any open database — and it short-circuits the rest of the chain the same way. Everything
 * below the two word-by-word sources (cloud-drive lyrics, embedded tags, the unified provider
 * model, the NetEase lyric API, the bundled YRC files) is line-level fallback, and each of those
 * steps is still skipped when the previous one already produced real word timing.</p>
 */
final class LyricLoadService {

    private LyricLoadService() {
    }

    static LyricLoadResult load(Music music, long songId, long profileId, File embeddedLyricFile) {
        final String trackKey = music.getStableKey();
        JsonObject rawJson = new JsonObject();
        List<LyricLine> parsed = Collections.emptyList();
        boolean cloudLyricsLoaded = false;

        // 逐字歌词永远优先于普通歌词:命中 AMLL 词库后直接返回,不再请求任何平台歌词。
        List<LyricLine> amllLyrics = AmllTtmlLyricService.resolve(music);
        if (LyricParser.hasWordByWordTiming(amllLyrics)) {
            System.out.println("[Music/AMLL] Loaded word-by-word TTML lyrics for " + trackKey);
            return new LyricLoadResult(rawJson, amllLyrics);
        }

        // 逐字第二顺位:QQ 音乐 QRC。AMLL 词库是人工投稿、覆盖率有限,QQ 自己的 QRC 覆盖面大得多,
        // 所以它排在所有普通歌词之前;同样只有"真的是逐字"才采用,行级结果留给下面带翻译的兜底链路。
        List<LyricLine> qqQrcLyrics = QQQrcLyricService.resolve(music);
        if (LyricParser.hasWordByWordTiming(qqQrcLyrics)) {
            System.out.println("[Music/QQ-QRC] Loaded word-by-word QRC lyrics for " + trackKey);
            return new LyricLoadResult(rawJson, qqQrcLyrics);
        }
        // 逐字第三顺位:酷狗 KRC。它只对酷狗曲目生效(方法内部自检),同样只在"真的是逐字"时短路。
        List<LyricLine> kugouLyrics = KuGouMusicService.getWordByWordLyrics(music);
        if (LyricParser.hasWordByWordTiming(kugouLyrics)) {
            System.out.println("[Music/KuGou] Loaded word-by-word KRC lyrics for " + trackKey);
            return new LyricLoadResult(rawJson, kugouLyrics);
        }

        if (music.isCloudSong() && profileId > 0L) {
            try {
                JsonObject cloudJson = normalizeCloudLyricResponse(
                        CloudMusicApi.cloudLyricGet(profileId, songId).toJsonObject());
                List<LyricLine> cloudLyrics = LyricParser.parse(cloudJson);
                if (!cloudLyrics.isEmpty()) {
                    rawJson = cloudJson;
                    parsed = cloudLyrics;
                    cloudLyricsLoaded = true;
                    System.out.println("[Music] Loaded cloud-drive lyrics for " + trackKey);
                }
            } catch (Throwable throwable) {
                System.err.println("[Music] Cloud-drive lyric API failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (!cloudLyricsLoaded && embeddedLyricFile != null) {
            try {
                String embeddedText = EmbeddedLyricsReader.read(embeddedLyricFile);
                if (!embeddedText.isEmpty()) {
                    JsonObject embeddedJson = createEmbeddedLyricJson(embeddedText);
                    List<LyricLine> embedded = LyricParser.parse(embeddedJson);
                    if (!embedded.isEmpty()) {
                        rawJson = embeddedJson;
                        parsed = embedded;
                        cloudLyricsLoaded = true;
                        System.out.println("[Music] Loaded embedded lyrics for cloud song " + trackKey
                                + " from " + embeddedLyricFile.getName());
                    }
                }
            } catch (Throwable throwable) {
                System.err.println("[Music] Embedded lyric read failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (parsed.isEmpty()) {
            try {
                top.fpsmaster.music.Lyric cadenceLyric = CadenceMusicService.getLyric(music);
                if (cadenceLyric != null) {
                    parsed = LyricParser.fromCadence(cadenceLyric, music.getDuration(), false);
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/Cadence] Unified lyric conversion failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        // 酷狗的普通歌词兜底:上面那条统一 provider 链路对酷狗返回 null,行级 LRC 只能从这里补。
        if (parsed.isEmpty() && music.isKuGou()) {
            try {
                String lrc = KuGouMusicService.getFallbackLrc(music);
                if (!lrc.isEmpty()) {
                    JsonObject lrcJson = createEmbeddedLyricJson(lrc);
                    List<LyricLine> lines = LyricParser.parse(lrcJson);
                    if (!lines.isEmpty()) {
                        rawJson = lrcJson;
                        parsed = lines;
                        System.out.println("[Music/KuGou] Loaded line-level LRC lyrics for " + trackKey);
                    }
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] LRC fallback failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
            try {
                String lyricResponse = CloudMusicApi.lyricNew(songId).toString();
                lyricResponse = lyricResponse.replaceAll("[ - ]", " ");
                rawJson = JsonUtils.toJsonObject(lyricResponse);
                List<LyricLine> fallback = LyricParser.parse(rawJson);
                if (LyricParser.hasRealWordTiming(fallback) || parsed.isEmpty()) {
                    parsed = fallback;
                }
            } catch (Throwable throwable) {
                System.err.println("[NCM] Legacy lyric fallback failed for " + trackKey + ": "
                        + throwable.getMessage());
            }
        }

        if (music.isNetease() && !cloudLyricsLoaded && !LyricParser.hasRealWordTiming(parsed)) {
            InputStream stream = LyricLoadService.class.getResourceAsStream("/muonium/yrc/" + songId + ".yrc");
            if (stream != null) {
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    MusicDownloadService.writeTo(stream, output);
                    String yrc = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    List<LyricLine> embedded = new ArrayList<>();
                    LyricParser.parseYrc(yrc, embedded);
                    if (LyricParser.hasRealWordTiming(embedded)) {
                        parsed = embedded;
                    }
                } catch (Throwable throwable) {
                    System.err.println("[NCM] Embedded YRC fallback failed for " + trackKey + ": "
                            + throwable.getMessage());
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        return new LyricLoadResult(rawJson, parsed);
    }

    static File resolveEmbeddedLyricFile(File playbackFile) {
        if (playbackFile == null || !playbackFile.isFile()) {
            return null;
        }
        String name = playbackFile.getName();
        final String decodedSuffix = ".decoded.wav";
        if (!name.endsWith(decodedSuffix)) {
            return playbackFile;
        }

        String baseName = name.substring(0, name.length() - decodedSuffix.length());
        String[] sourceExtensions = {"m4a", "aac", "mp3", "flac"};
        for (String extension : sourceExtensions) {
            File source = new File(playbackFile.getParentFile(), baseName + "." + extension);
            if (source.isFile()) {
                return source;
            }
        }
        return playbackFile;
    }

    private static JsonObject createEmbeddedLyricJson(String lyricText) {
        JsonObject root = new JsonObject();
        JsonObject lrc = new JsonObject();
        lrc.addProperty("lyric", lyricText);
        root.add("lrc", lrc);
        return root;
    }

    private static JsonObject normalizeCloudLyricResponse(JsonObject response) {
        if (response == null) return new JsonObject();
        if (hasLyricPayload(response)) return response;

        JsonElement data = response.get("data");
        if (data != null && data.isJsonObject() && hasLyricPayload(data.getAsJsonObject())) {
            return data.getAsJsonObject();
        }

        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && hasLyricPayload(result.getAsJsonObject())) {
            return result.getAsJsonObject();
        }
        return response;
    }

    private static boolean hasLyricPayload(JsonObject object) {
        return object.has("lrc") || object.has("yrc") || object.has("ytlrc")
                || object.has("tlyric") || object.has("romalrc");
    }

    static final class LyricLoadResult {
        final JsonObject rawJson;
        final List<LyricLine> lines;

        private LyricLoadResult(JsonObject rawJson, List<LyricLine> lines) {
            this.rawJson = rawJson;
            this.lines = lines;
        }

        /** 加载彻底失败时的空结果：调用方仍然要提交一次，好让歌词时间轴的归属推进到当前歌曲。 */
        static LyricLoadResult empty() {
            return new LyricLoadResult(new JsonObject(), Collections.<LyricLine>emptyList());
        }
    }
}