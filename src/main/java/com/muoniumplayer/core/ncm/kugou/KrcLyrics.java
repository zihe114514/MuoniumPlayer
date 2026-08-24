package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * 酷狗 KRC 歌词的解密与转换。
 *
 * <p>KRC 是<b>逐字</b>歌词：每个音节都有自己的起始与时长，因此它在本项目的歌词优先级里属于
 * "逐字"一档，而不是普通 LRC 的兜底档。</p>
 *
 * <h3>解密</h3>
 * <p>下载得到的是 base64，解开后前 4 字节是魔数 {@code krc1}，其后与一个 16 字节固定密钥循环异或，
 * 再 zlib 解压得到明文。密钥是第三方逆向得到的协议事实（对照 {@code MakcRe/KuGouMusicApi} 的
 * {@code util/util.js#decodeLyrics}），不是酷狗官方定义。</p>
 *
 * <h3>为什么转成 YRC 文本而不是直接构造 LyricLine</h3>
 * <p>本项目已有 {@code LyricParser.parseYrc}，它负责音节时间归一化、零时长音节折进前一个音节、
 * 行时长推断这些容易写错的细节。KRC 的行内格式与 YRC 只差括号形状和"偏移是相对行首"，
 * 做一次文本改写就能复用那一整套，比再写一份解析器可靠。</p>
 */
public final class KrcLyrics {

    /** KRC 的固定异或密钥。 */
    private static final int[] XOR_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    private static final String MAGIC = "krc1";

    /** {@code [行起始,行时长]} 行头。 */
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");

    /** {@code <相对起始,时长,0>音节}。 */
    private static final Pattern WORD = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");

    /** {@code [language:BASE64]}：里面是翻译/音译的 JSON。 */
    private static final Pattern LANGUAGE = Pattern.compile("\\[language:([^\\]]*)]");

    private KrcLyrics() {
    }

    /** 一次解码的结果。{@code yrc} 为空表示这份 KRC 没有可用的逐字内容。 */
    public static final class Decoded {
        /** 已改写为 YRC 文本形式的逐字歌词，直接喂给 {@code LyricParser.parseYrc}。 */
        public final String yrc;
        /** LRC 形式的翻译，可直接喂给 {@code LyricParser.applyQrcSidecar}；没有时为空串。 */
        public final String translation;

        Decoded(String yrc, String translation) {
            this.yrc = yrc == null ? "" : yrc;
            this.translation = translation == null ? "" : translation;
        }
    }

    /**
     * 解密并转换一份 base64 的 KRC。
     *
     * @return 永不为 {@code null}；任何一步失败都返回空结果，由调用方回退到普通歌词
     */
    public static Decoded decode(String base64Content) {
        String plain = decrypt(base64Content);
        if (plain.isEmpty()) return new Decoded("", "");
        return new Decoded(toYrc(plain), toTranslationLrc(plain));
    }

    /** 解密 + 解压，返回 KRC 明文；失败返回空串。 */
    public static String decrypt(String base64Content) {
        if (base64Content == null || base64Content.trim().isEmpty()) return "";
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Content.trim());
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] KRC base64 解析失败: " + throwable.getMessage());
            return "";
        }
        if (raw.length <= MAGIC.length()) return "";
        String magic = new String(raw, 0, MAGIC.length(), StandardCharsets.US_ASCII);
        if (!MAGIC.equals(magic)) {
            System.err.println("[Music/KuGou] KRC 魔数不是 krc1，实际为 " + magic);
            return "";
        }

        byte[] body = new byte[raw.length - MAGIC.length()];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) ((raw[index + MAGIC.length()] & 0xFF) ^ XOR_KEY[index % XOR_KEY.length]);
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(body);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, body.length * 3));
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read <= 0) {
                    // needsInput/needsDictionary 都说明这份数据不完整，继续循环会空转。
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                    continue;
                }
                output.write(buffer, 0, read);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            // 上游会带 UTF-8 BOM，留着会让第一行的元数据匹配失败。
            return text.isEmpty() || text.charAt(0) != '\uFEFF' ? text : text.substring(1);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] KRC 解压失败: " + throwable.getMessage());
            return "";
        } finally {
            inflater.end();
        }
    }

    /**
     * KRC 行内格式改写成 YRC 文本。
     *
     * <p>两处差异：括号形状（{@code <>} → {@code ()}）与音节起始的基准（KRC 是相对行首，
     * 这里加上行起始改成绝对毫秒）。改成绝对值而不是依赖解析器的相对值兼容分支，是因为
     * 那条分支要靠"看起来像相对值"来猜，行首为 0 的第一行会猜不准。</p>
     */
    private static String toYrc(String plain) {
        StringBuilder result = new StringBuilder();
        int wordCount = 0;
        for (String rawLine : plain.split("\\r?\\n")) {
            Matcher lineMatcher = LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            long lineStart;
            long lineDuration;
            try {
                lineStart = Long.parseLong(lineMatcher.group(1));
                lineDuration = Long.parseLong(lineMatcher.group(2));
            } catch (NumberFormatException malformed) {
                continue;
            }

            StringBuilder body = new StringBuilder();
            Matcher wordMatcher = WORD.matcher(lineMatcher.group(3));
            while (wordMatcher.find()) {
                long offset;
                long duration;
                try {
                    offset = Long.parseLong(wordMatcher.group(1));
                    duration = Long.parseLong(wordMatcher.group(2));
                } catch (NumberFormatException malformed) {
                    continue;
                }
                body.append('(').append(lineStart + offset).append(',').append(duration).append(",0)")
                        .append(wordMatcher.group(4));
                wordCount++;
            }
            if (body.length() == 0) continue;
            result.append('[').append(lineStart).append(',').append(lineDuration).append(']')
                    .append(body).append('\n');
        }
        // 一个音节都没有的"逐字歌词"不是逐字歌词，交给普通歌词兜底比伪装成逐字更好。
        return wordCount == 0 ? "" : result.toString();
    }

    /**
     * 从 {@code [language:...]} 里取出翻译，按行拼成 LRC 文本。
     *
     * <p>{@code lyricContent} 的第 i 项对应主歌词的第 i 行，所以必须按同一顺序遍历行头才能对上；
     * 数量不一致时以较短的一边为准，不做插值猜测。</p>
     */
    private static String toTranslationLrc(String plain) {
        Matcher languageMatcher = LANGUAGE.matcher(plain);
        if (!languageMatcher.find()) return "";
        List<String> translations = parseTranslations(languageMatcher.group(1));
        if (translations.isEmpty()) return "";

        List<Long> starts = new ArrayList<Long>();
        for (String rawLine : plain.split("\\r?\\n")) {
            Matcher lineMatcher = LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            try {
                starts.add(Long.parseLong(lineMatcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }

        StringBuilder result = new StringBuilder();
        int total = Math.min(starts.size(), translations.size());
        for (int index = 0; index < total; index++) {
            String text = translations.get(index);
            if (text == null || text.trim().isEmpty()) continue;
            result.append(timestamp(starts.get(index))).append(text.trim()).append('\n');
        }
        return result.toString();
    }

    /** 取 {@code type == 1}（翻译）那一组；同一行的多个片段直接拼接。 */
    private static List<String> parseTranslations(String base64) {
        List<String> result = new ArrayList<String>();
        if (base64 == null || base64.trim().isEmpty()) return result;
        try {
            String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(json);
            if (parsed == null || !parsed.isJsonObject()) return result;
            JsonElement contentElement = parsed.getAsJsonObject().get("content");
            if (contentElement == null || !contentElement.isJsonArray()) return result;
            for (JsonElement groupElement : contentElement.getAsJsonArray()) {
                if (!groupElement.isJsonObject()) continue;
                JsonObject group = groupElement.getAsJsonObject();
                if (!group.has("type") || group.get("type").isJsonNull()) continue;
                if (group.get("type").getAsInt() != 1) continue;
                JsonElement linesElement = group.get("lyricContent");
                if (linesElement == null || !linesElement.isJsonArray()) continue;
                for (JsonElement lineElement : linesElement.getAsJsonArray()) {
                    result.add(joinSegments(lineElement));
                }
                break;
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] KRC 翻译解析失败: " + throwable.getMessage());
        }
        return result;
    }

    private static String joinSegments(JsonElement lineElement) {
        if (lineElement == null || lineElement.isJsonNull()) return "";
        if (lineElement.isJsonPrimitive()) return lineElement.getAsString();
        if (!lineElement.isJsonArray()) return "";
        StringBuilder builder = new StringBuilder();
        JsonArray segments = lineElement.getAsJsonArray();
        for (JsonElement segment : segments) {
            if (segment != null && segment.isJsonPrimitive()) builder.append(segment.getAsString());
        }
        return builder.toString();
    }

    /** 毫秒转 {@code [mm:ss.SSS]}，与 LyricParser 的 LRC 时间正则一致。 */
    static String timestamp(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60000L;
        long seconds = (safe % 60000L) / 1000L;
        long fraction = safe % 1000L;
        StringBuilder builder = new StringBuilder("[");
        if (minutes < 10) builder.append('0');
        builder.append(minutes).append(':');
        if (seconds < 10) builder.append('0');
        builder.append(seconds).append('.');
        if (fraction < 100) builder.append('0');
        if (fraction < 10) builder.append('0');
        builder.append(fraction).append(']');
        return builder.toString();
    }
}