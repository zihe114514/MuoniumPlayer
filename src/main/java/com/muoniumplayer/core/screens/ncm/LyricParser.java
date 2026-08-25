package com.muoniumplayer.core.screens.ncm;

import com.google.gson.JsonObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import top.fpsmaster.music.Lyric;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 歌词解析与跨平台时间轴适配。 */
public class LyricParser {

    private static final Pattern LRC_LINE = Pattern.compile("((?:\\[\\d{1,3}:\\d{1,2}[.:]\\d{1,3}])+)(.*)");
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})[.:](\\d{1,3})]");
    private static final Pattern ENHANCED_LRC_WORD = Pattern.compile("<(\\d{1,3}):(\\d{1,2})[.:](\\d{1,3})>([^<]*)");
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+)(?:,(\\d+))?\\)([^()]*)");
    /** QRC 行头:{@code [行起始毫秒,行时长毫秒]}。歌词元数据行([ti:]/[ar:]/...)匹配不到,天然被跳过。 */
    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    /** QRC 音节:文本在前,{@code (绝对起始毫秒,时长毫秒)} 在后。 */
    private static final Pattern QRC_WORD = Pattern.compile("([^()]*?)\\((\\d+),(\\d+)\\)");

    public static List<LyricLine> parse(JsonObject input) {
        if (input == null || input.has("uncollected")) return new ArrayList<>();

        String lrc = readLyric(input, "lrc");
        String translated = firstNonEmpty(readLyric(input, "ytlrc"), readLyric(input, "tlyric"));
        String yrc = readLyric(input, "yrc");
        if (lrc.trim().isEmpty() && yrc.trim().isEmpty()) return new ArrayList<>();

        // 真实逐字时间轴必须先于普通 LRC 解析，否则普通整句结果会吞掉 YRC/增强 LRC。
        List<LyricLine> timedLines = new ArrayList<>();
        if (!yrc.trim().isEmpty()) parseYrc(replace(yrc), timedLines);
        if (timedLines.isEmpty() && looksLikeYrc(lrc)) parseYrc(replace(lrc), timedLines);
        if (timedLines.isEmpty() && containsEnhancedTiming(lrc)) timedLines = parseEnhancedLrc(replace(lrc));
        if (!timedLines.isEmpty()) {
            applySecondary(parseSingleLine(translated), timedLines, true);
            applyRomanization(input, timedLines);
            return timedLines;
        }

        // 统一 API 模型仍负责普通 LRC 与平台翻译；这里只接受真实逐字数据，不再伪造字符时间轴。
        try {
            List<top.fpsmaster.music.LyricLine> remote = top.fpsmaster.music.lyric.LyricParser.INSTANCE
                    .parse(replace(lrc), replace(translated), "");
            Lyric cadenceLyric = new Lyric(lrc, translated, "", remote);
            List<LyricLine> converted = fromCadence(cadenceLyric, 0L, false);
            if (!converted.isEmpty()) {
                applyRomanization(input, converted);
                return converted;
            }
        } catch (Throwable ignored) {
        }

        List<LyricLine> lyricLines = new ArrayList<>(parseSingleLine(replace(lrc)));
        processTranslationLyrics(input, lyricLines);
        processRomanizationLyrics(input, lyricLines);
        inferLineDurations(lyricLines, 0L);
        return lyricLines;
    }

    /** 把 Cadence 的统一歌词模型转换为项目现有渲染模型。 */
    public static List<LyricLine> fromCadence(Lyric lyric, long trackDurationMs, boolean estimateMissingWords) {
        List<LyricLine> result = new ArrayList<>();
        if (lyric == null || lyric.getLines() == null) return result;

        for (top.fpsmaster.music.LyricLine source : lyric.getLines()) {
            if (source == null || source.isMetadata()) continue;
            String text = replace(source.getText()).trim();
            if (text.isEmpty()) continue;

            LyricLine line = new LyricLine(Math.max(0L, source.getStartMs()), text);
            line.duration = Math.max(0L, source.getDurationMs());
            if (source.getTranslation() != null && !source.getTranslation().trim().isEmpty()) {
                line.translationText = replace(source.getTranslation()).trim();
            }

            List<top.fpsmaster.music.LyricWord> sourceWords = source.getWords();
            if (sourceWords != null && !sourceWords.isEmpty()) {
                List<TimedText> words = new ArrayList<>();
                String pendingPrefix = "";
                for (top.fpsmaster.music.LyricWord sourceWord : sourceWords) {
                    if (sourceWord == null || sourceWord.getText() == null) continue;
                    String wordText = replace(sourceWord.getText());
                    if (wordText.isEmpty()) continue;
                    long duration = sourceWord.getDurationMs();
                    if (duration <= 0L) {
                        if (!words.isEmpty()) words.get(words.size() - 1).text += wordText;
                        else pendingPrefix += wordText;
                        continue;
                    }
                    long start = normalizeWordStart(line.timestamp, line.duration, sourceWord.getStartMs());
                    words.add(new TimedText(pendingPrefix + wordText, start, duration));
                    pendingPrefix = "";
                }
                if (!pendingPrefix.isEmpty() && !words.isEmpty()) words.get(words.size() - 1).text += pendingPrefix;
                addWords(line, words);
            }
            result.add(line);
        }

        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(result, trackDurationMs);
        normalizeWordDurations(result);

        // 参数仅为二进制兼容保留。普通 LRC 不再按字数平均切分，避免“假逐字歌词”。
        return result;
    }

    public static boolean hasRealWordTiming(List<LyricLine> lines) {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line != null && line.hasTimedWords()) return true;
        }
        return false;
    }

    private static String readLyric(JsonObject input, String key) {
        try {
            if (input.has(key) && input.get(key).isJsonObject()) {
                JsonObject obj = input.getAsJsonObject(key);
                if (obj.has("lyric") && !obj.get("lyric").isJsonNull()) return obj.get("lyric").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : (second == null ? "" : second);
    }

    private static String replace(String input) {
        if (input == null) return "";
        return input.replace('\u00a0', ' ').replaceAll("[\\u2000-\\u200A]", " ").replaceAll(" {2,}", " ");
    }

    private static void processTranslationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        applySecondary(parseSingleLine(firstNonEmpty(readLyric(input, "ytlrc"), readLyric(input, "tlyric"))), lyricLines, true);
    }

    private static void processRomanizationLyrics(JsonObject input, List<LyricLine> lyricLines) {
        applyRomanization(input, lyricLines);
    }

    private static void applyRomanization(JsonObject input, List<LyricLine> lyricLines) {
        String roman = firstNonEmpty(readLyric(input, "yromalrc"), readLyric(input, "romalrc"));
        applySecondary(parseSingleLine(roman), lyricLines, false);
    }

    private static void applySecondary(List<LyricLine> secondary, List<LyricLine> lyricLines, boolean translation) {
        Map<Long, String> map = new HashMap<>();
        for (LyricLine line : secondary) map.put(line.timestamp, line.lyric);
        for (LyricLine line : lyricLines) {
            String value = map.get(line.timestamp);
            if (value == null) value = findNearbySecondary(map, line.timestamp, 80L);
            if (value == null || value.isEmpty()) continue;
            if (translation && line.translationText == null) line.translationText = value;
            if (!translation && line.romanizationText == null) line.romanizationText = value;
        }
    }

    private static String findNearbySecondary(Map<Long, String> values, long timestamp, long toleranceMs) {
        String nearest = null;
        long distance = toleranceMs + 1L;
        for (Map.Entry<Long, String> entry : values.entrySet()) {
            long candidateDistance = Math.abs(entry.getKey() - timestamp);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                nearest = entry.getValue();
            }
        }
        return nearest;
    }

    private static List<LyricLine> parseSingleLine(String input) {
        List<LyricLine> result = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) return result;
        String[] lines = input.split("\\r?\\n");
        if (lines.length == 1) lines = input.split("\\\\n");
        for (String line : lines) {
            List<LyricLine> parsed = parseLine(line);
            if (parsed != null) result.addAll(parsed);
        }
        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        return result;
    }

    private static List<LyricLine> parseLine(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        Matcher lineMatcher = LRC_LINE.matcher(input.trim());
        if (!lineMatcher.matches()) return null;

        String times = lineMatcher.group(1);
        String text = replace(lineMatcher.group(2)).trim();
        if (text.isEmpty()) return null;

        List<LyricLine> entryList = new ArrayList<>();
        Matcher timeMatcher = LRC_TIME.matcher(times);
        while (timeMatcher.find()) entryList.add(new LyricLine(parseTimestamp(timeMatcher), text.replace("　", " ")));
        return entryList;
    }

    private static boolean containsEnhancedTiming(String lrc) {
        return lrc != null && ENHANCED_LRC_WORD.matcher(lrc).find();
    }

    private static boolean looksLikeYrc(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String[] lines = text.split("\\r?\\n", 2);
        return YRC_LINE.matcher(lines[0].trim()).matches() && YRC_WORD.matcher(lines[0]).find();
    }

    private static List<LyricLine> parseEnhancedLrc(String lrc) {
        List<LyricLine> result = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) return result;
        String[] rawLines = lrc.split("\\r?\\n");
        if (rawLines.length == 1) rawLines = lrc.split("\\\\n");

        for (String rawLine : rawLines) {
            Matcher lineMatcher = LRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            Matcher lineTime = LRC_TIME.matcher(lineMatcher.group(1));
            if (!lineTime.find()) continue;

            long lineStart = parseTimestamp(lineTime);
            Matcher wordMatcher = ENHANCED_LRC_WORD.matcher(lineMatcher.group(2));
            List<TimedText> tags = new ArrayList<>();
            while (wordMatcher.find()) {
                long wordStart = parseTimestamp(wordMatcher.group(1), wordMatcher.group(2), wordMatcher.group(3));
                tags.add(new TimedText(replace(wordMatcher.group(4)), Math.max(lineStart, wordStart), 0L));
            }
            if (tags.isEmpty()) continue;

            List<TimedText> words = new ArrayList<>();
            StringBuilder lyricText = new StringBuilder();
            for (int i = 0; i < tags.size(); i++) {
                TimedText tag = tags.get(i);
                if (tag.text.isEmpty()) continue;
                long nextStart = i + 1 < tags.size() ? tags.get(i + 1).start : tag.start;
                long wordDuration = Math.max(0L, nextStart - tag.start);
                words.add(new TimedText(tag.text, tag.start, wordDuration));
                lyricText.append(tag.text);
            }
            if (words.isEmpty()) continue;

            LyricLine line = new LyricLine(lineStart, lyricText.toString());
            TimedText lastTag = tags.get(tags.size() - 1);
            if (lastTag.text.isEmpty() && lastTag.start > lineStart) line.duration = lastTag.start - lineStart;
            addWords(line, words);
            result.add(line);
        }

        result.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(result, 0L);
        normalizeWordDurations(result);
        return result;
    }

    public static void parseYrc(String yrc, List<LyricLine> lyricLines) {
        lyricLines.clear();
        if (yrc == null || yrc.trim().isEmpty()) return;
        String[] lines = yrc.split("\\r?\\n");
        if (lines.length == 1) lines = yrc.split("\\\\n");

        for (String rawLine : lines) {
            Matcher lineMatcher = YRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;
            long start = Long.parseLong(lineMatcher.group(1));
            long duration = Long.parseLong(lineMatcher.group(2));
            String content = lineMatcher.group(3);

            List<TimedText> words = new ArrayList<>();
            String pendingPrefix = "";
            Matcher wordMatcher = YRC_WORD.matcher(content);
            while (wordMatcher.find()) {
                String wordText = replace(wordMatcher.group(4));
                if (wordText.isEmpty()) continue;
                long wordStart = Long.parseLong(wordMatcher.group(1));
                long wordDuration = Long.parseLong(wordMatcher.group(2));

                // 网易云 YRC 中 (0,0,0) 常用于把空格/标点续接到前一个词，不能生成时间戳为 0 的假词。
                if (wordDuration <= 0L) {
                    if (!words.isEmpty()) words.get(words.size() - 1).text += wordText;
                    else pendingPrefix += wordText;
                    continue;
                }

                long absoluteStart = normalizeWordStart(start, duration, wordStart);
                words.add(new TimedText(pendingPrefix + wordText, absoluteStart, wordDuration));
                pendingPrefix = "";
            }
            if (!pendingPrefix.isEmpty() && !words.isEmpty()) words.get(words.size() - 1).text += pendingPrefix;
            if (words.isEmpty()) continue;

            StringBuilder text = new StringBuilder();
            for (TimedText word : words) text.append(word.text);
            LyricLine line = new LyricLine(start, text.toString());
            line.duration = Math.max(0L, duration);
            addWords(line, words);
            lyricLines.add(line);
        }
        lyricLines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(lyricLines, 0L);
        normalizeWordDurations(lyricLines);
    }

    /**
     * QQ 音乐 QRC 逐字歌词解析,输出与 YRC/TTML 完全相同的渲染模型。
     *
     * <p>格式为 {@code [行起始,行时长]文本(绝对起始,时长)文本(绝对起始,时长)...}。<b>音节时间是绝对
     * 毫秒</b>(不是相对行首),但个别歌曲混用相对值,所以仍然走 {@link #normalizeWordStart} 兼容两者。
     * 文件头部的 {@code [ti:]/[ar:]/[al:]/[by:]/[offset:]} 元数据行匹配不到行头正则,会被跳过。</p>
     *
     * <p>{@code (0,0)} 这类零时长音节是标点/空格的续接标记,折进前一个音节而不是生成时间戳为 0 的
     * 假词——否则逐字扫词会在行首闪回。</p>
     */
    public static void parseQrc(String qrc, List<LyricLine> lyricLines) {
        lyricLines.clear();
        if (qrc == null || qrc.trim().isEmpty()) return;
        String[] lines = qrc.split("\\r?\\n");
        if (lines.length == 1) lines = qrc.split("\\\\n");

        for (String rawLine : lines) {
            Matcher lineMatcher = QRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;

            long start;
            long duration;
            try {
                start = Long.parseLong(lineMatcher.group(1));
                duration = Long.parseLong(lineMatcher.group(2));
            } catch (NumberFormatException overflow) {
                continue;
            }
            String content = lineMatcher.group(3);

            List<TimedText> words = new ArrayList<>();
            String pendingPrefix = "";
            Matcher wordMatcher = QRC_WORD.matcher(content);
            while (wordMatcher.find()) {
                String wordText = replace(wordMatcher.group(1));
                if (wordText.isEmpty()) continue;

                long wordStart;
                long wordDuration;
                try {
                    wordStart = Long.parseLong(wordMatcher.group(2));
                    wordDuration = Long.parseLong(wordMatcher.group(3));
                } catch (NumberFormatException overflow) {
                    continue;
                }

                if (wordDuration <= 0L) {
                    if (!words.isEmpty()) words.get(words.size() - 1).text += wordText;
                    else pendingPrefix += wordText;
                    continue;
                }

                words.add(new TimedText(pendingPrefix + wordText,
                        normalizeWordStart(start, duration, wordStart), wordDuration));
                pendingPrefix = "";
            }
            if (!pendingPrefix.isEmpty() && !words.isEmpty()) words.get(words.size() - 1).text += pendingPrefix;
            if (words.isEmpty()) continue;

            StringBuilder text = new StringBuilder();
            for (TimedText word : words) text.append(word.text);
            if (text.toString().trim().isEmpty()) continue;

            LyricLine line = new LyricLine(start, text.toString());
            line.duration = Math.max(0L, duration);
            addWords(line, words);
            lyricLines.add(line);
        }
        lyricLines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(lyricLines, 0L);
        normalizeWordDurations(lyricLines);
    }

    /**
     * 把 QQ 的翻译/罗马音贴到已解析的逐字行上。QQ 这两个字段有时是普通 LRC,有时也是 QRC(带音节
     * 时间),所以两种都试;行首时间与主歌词不总是完全相等,按最近的一行匹配,容差 1 秒。
     */
    public static void applyQrcSidecar(String sidecar, List<LyricLine> lyricLines, boolean translation) {
        if (sidecar == null || sidecar.trim().isEmpty() || lyricLines == null || lyricLines.isEmpty()) return;

        List<LyricLine> side = new ArrayList<>();
        try {
            parseQrc(sidecar, side);
        } catch (Throwable ignored) {
            side.clear();
        }
        if (side.isEmpty()) side = parseSingleLine(sidecar);
        if (side.isEmpty()) return;

        Map<Long, String> values = new HashMap<>();
        for (LyricLine line : side) {
            String text = line.lyric == null ? "" : line.lyric.trim();
            // QQ 用 "//" 之类的占位符标记"这一行没有翻译"。
            if (text.isEmpty() || text.equals("//")) continue;
            values.put(line.timestamp, text);
        }
        if (values.isEmpty()) return;

        for (LyricLine line : lyricLines) {
            String value = values.get(line.timestamp);
            if (value == null) value = findNearbySecondary(values, line.timestamp, 1000L);
            if (value == null || value.isEmpty()) continue;
            if (translation && line.translationText == null) line.translationText = value;
            if (!translation && line.romanizationText == null) line.romanizationText = value;
        }
    }

    /**
     * AMLL 风味 TTML(Apple Music 逐字歌词格式)解析,输出与 YRC 完全相同的渲染模型。
     *
     * <p>只读取每个 {@code <p>} 的直接子 {@code <span>},避免把翻译、罗马音、和声嵌套 span
     * 当成主歌词的音节。{@code ttm:role} 的处理:{@code x-translation} 进 translationText,
     * {@code x-roman} 进 romanizationText,{@code x-bg}(和声)追加到主行词表末尾——本项目的
     * LyricLine 没有独立的背景人声通道,新开一行会产生与主行时间重叠的行,滚动与高亮都会跳。
     * 追加时会给这些音节打上 {@link LyricLine.Word#background} 标记,想按 Apple Music 的排版把
     * 和声单独排成一条小字行的样式据此还原两路数据,不读标记的样式行为完全不变。</p>
     *
     * <p>span 之间的空白文本节点是英文歌词的词间距,折进前一个词而不是生成时间戳为 0 的假词;
     * 缺少 begin 的文本同样只做拼接。解析失败(网络截断、非 XML、实体攻击)时返回空列表,由调用方
     * 回退到普通歌词。</p>
     */
    public static void parseTtml(String ttml, List<LyricLine> lyricLines) {
        lyricLines.clear();
        if (ttml == null || ttml.trim().isEmpty()) return;

        Document document = readTtmlDocument(ttml);
        if (document == null) return;

        NodeList paragraphs = document.getElementsByTagName("p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Node node = paragraphs.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element paragraph = (Element) node;

            long lineStart = Math.max(0L, parseTtmlClock(paragraph.getAttribute("begin")));
            long lineEnd = parseTtmlClock(paragraph.getAttribute("end"));

            TtmlCollector collector = new TtmlCollector();
            collectTtmlSpans(paragraph, collector, true);

            if (collector.words.isEmpty()) {
                // 行级 TTML:整段只有文本没有音节 span,按整行一个词处理。
                String text = collector.pendingPrefix.trim();
                if (text.isEmpty()) continue;
                long duration = lineEnd > lineStart ? lineEnd - lineStart : 0L;
                collector.words.add(new TimedText(text, lineStart, duration));
                collector.pendingPrefix = "";
            }

            StringBuilder text = new StringBuilder();
            for (TimedText word : collector.words) text.append(word.text);
            String lineText = text.toString().trim();
            if (lineText.isEmpty()) continue;

            LyricLine line = new LyricLine(lineStart, lineText);
            // 和声 span 常常越过 <p> 自己的 end(它一直唱到下一行开始),行窗口必须覆盖到
            // 最后一个词,否则 normalizeWordDurations 之后仍有词落在行外,逐字扫词会提前停住。
            long wordsEnd = lineEnd;
            for (TimedText word : collector.words) {
                wordsEnd = Math.max(wordsEnd, word.start + word.duration);
            }
            if (wordsEnd > lineStart) line.duration = wordsEnd - lineStart;
            if (collector.translation != null) line.translationText = collector.translation;
            if (collector.romanization != null) line.romanizationText = collector.romanization;
            addWords(line, collector.words);
            lyricLines.add(line);
        }

        lyricLines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        inferLineDurations(lyricLines, 0L);
        normalizeWordDurations(lyricLines);
    }

    /** 至少有一行拆出两个以上计时音节,才算真正的逐字歌词(行级 TTML 不算)。 */
    public static boolean hasWordByWordTiming(List<LyricLine> lines) {
        if (lines == null) return false;
        for (LyricLine line : lines) {
            if (line == null) continue;
            int timed = 0;
            for (LyricLine.Word word : line.words) {
                if (word != null && word.duration > 0L) timed++;
            }
            if (timed >= 2) return true;
        }
        return false;
    }

    private static void collectTtmlSpans(Element parent, TtmlCollector collector, boolean allowBackground) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            short type = child.getNodeType();

            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                String raw = child.getNodeValue();
                if (raw == null || raw.isEmpty()) continue;
                if (raw.trim().isEmpty()) collector.appendSpace();
                else collector.appendText(replace(raw));
                continue;
            }
            if (type != Node.ELEMENT_NODE) continue;

            String name = child.getNodeName().toLowerCase(Locale.ROOT);
            if (!name.equals("span") && !name.endsWith(":span")) continue;
            Element span = (Element) child;

            String role = ttmlRole(span);
            if (role.startsWith("x-translation")) {
                if (collector.translation == null) {
                    String value = replace(span.getTextContent()).trim();
                    if (!value.isEmpty()) collector.translation = value;
                }
                continue;
            }
            if (role.startsWith("x-roman")) {
                if (collector.romanization == null) {
                    String value = replace(span.getTextContent()).trim();
                    if (!value.isEmpty()) collector.romanization = value;
                }
                continue;
            }
            if (role.equals("x-bg")) {
                if (allowBackground) appendTtmlBackground(span, collector);
                continue;
            }

            long start = parseTtmlClock(span.getAttribute("begin"));
            long end = parseTtmlClock(span.getAttribute("end"));
            String wordText = replace(span.getTextContent());
            if (start < 0L) {
                collector.appendText(wordText);
                continue;
            }
            collector.addWord(wordText, start, end < 0L ? 0L : Math.max(0L, end - start));
        }
    }

    /**
     * 把 {@code x-bg} 和声 span 追加到主行末尾。只有当和声排在主行最后一个词之后时才追加:
     * 时间戳倒退会让逐字扫词回跳,那比丢掉一句和声更糟。和声自带的翻译不覆盖主行翻译。
     */
    private static void appendTtmlBackground(Element backgroundSpan, TtmlCollector main) {
        TtmlCollector background = new TtmlCollector();
        collectTtmlSpans(backgroundSpan, background, false);
        if (background.words.isEmpty()) return;
        if (!main.words.isEmpty()) {
            TimedText last = main.words.get(main.words.size() - 1);
            if (background.words.get(0).start < last.start) return;
            if (!last.text.endsWith(" ")) last.text += " ";
        }
        // 追加进主行的同时留下和声标记：渲染模型上它们和主歌词的音节混在一条词表里，
        // 想按 Apple 的排版把和声单独排一行，就只能靠这个标记把两路分开。
        for (TimedText word : background.words) word.background = true;
        main.words.addAll(background.words);
        main.pendingPrefix = "";
    }

    private static String ttmlRole(Element span) {
        String role = span.getAttribute("ttm:role");
        if (role == null || role.isEmpty()) role = span.getAttribute("role");
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * TTML 时钟值:{@code 12.345}、{@code 02:43.590}、{@code 1:02:03.4},以及带单位的偏移
     * {@code 300ms}/{@code 12.5s}/{@code 2m}/{@code 1h}。无法识别时返回 -1,调用方据此判断
     * 这个 span 没有时间轴,而不是把它当成 0 毫秒。
     */
    static long parseTtmlClock(String value) {
        if (value == null) return -1L;
        String text = value.trim();
        if (text.isEmpty()) return -1L;
        try {
            if (text.endsWith("ms")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 2)));
            if (text.endsWith("s")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 1)) * 1000.0);
            if (text.endsWith("m")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 1)) * 60_000.0);
            if (text.endsWith("h")) return Math.round(Double.parseDouble(text.substring(0, text.length() - 1)) * 3_600_000.0);
            String[] parts = text.split(":");
            long millis = Math.round(Double.parseDouble(parts[parts.length - 1].trim()) * 1000.0);
            if (parts.length >= 2) millis += Long.parseLong(parts[parts.length - 2].trim()) * 60_000L;
            if (parts.length >= 3) millis += Long.parseLong(parts[parts.length - 3].trim()) * 3_600_000L;
            return Math.max(0L, millis);
        } catch (RuntimeException failure) {
            return -1L;
        }
    }

    /** 命名空间无关的安全解析:歌词是远端内容,禁掉 DTD 与外部实体,失败一律返回 null。 */
    private static Document readTtmlDocument(String ttml) {
        try {
            String text = ttml;
            int bom = text.indexOf('\uFEFF');
            if (bom >= 0) text = text.replace("\uFEFF", "");
            text = text.trim();
            if (!text.startsWith("<")) {
                int begin = text.indexOf('<');
                if (begin < 0) return null;
                text = text.substring(begin);
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setExpandEntityReferences(false);
            setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(new org.xml.sax.EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new ByteArrayInputStream(new byte[0]));
                }
            });
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                }

                @Override
                public void error(SAXParseException exception) {
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            return builder.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Throwable ignored) {
        }
    }

    /** TTML 解析中转:词表、待拼接前缀,以及行级的翻译/罗马音。 */
    private static final class TtmlCollector {
        private final List<TimedText> words = new ArrayList<>();
        private String pendingPrefix = "";
        private String translation;
        private String romanization;

        private void appendText(String text) {
            if (text == null || text.isEmpty()) return;
            if (words.isEmpty()) pendingPrefix += text;
            else words.get(words.size() - 1).text += text;
        }

        private void appendSpace() {
            if (words.isEmpty()) {
                if (!pendingPrefix.isEmpty() && !pendingPrefix.endsWith(" ")) pendingPrefix += " ";
                return;
            }
            TimedText last = words.get(words.size() - 1);
            if (!last.text.endsWith(" ")) last.text += " ";
        }

        private void addWord(String text, long start, long duration) {
            if (text == null || text.isEmpty()) return;
            if (duration <= 0L) {
                appendText(text);
                return;
            }
            words.add(new TimedText(pendingPrefix + text, start, duration));
            pendingPrefix = "";
        }
    }

    private static long normalizeWordStart(long lineStart, long lineDuration, long wordStart) {
        if (wordStart >= lineStart) return wordStart;
        // 一些增强歌词使用相对行首毫秒，网易云 YRC 通常使用绝对毫秒；兼容两者。
        if (wordStart >= 0L && (lineDuration <= 0L || wordStart <= lineDuration + 1000L)) return lineStart + wordStart;
        return lineStart;
    }

    private static void addWords(LyricLine line, List<TimedText> words) {
        for (TimedText word : words) {
            if (word.text != null && !word.text.isEmpty()) {
                LyricLine.Word added = new LyricLine.Word(word.text, Math.max(0L, word.start), Math.max(0L, word.duration));
                added.background = word.background;
                line.words.add(added);
            }
        }
    }

    private static void inferLineDurations(List<LyricLine> lines, long trackDurationMs) {
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            long next = i + 1 < lines.size() ? lines.get(i + 1).timestamp : trackDurationMs;
            if (line.duration <= 0L && next > line.timestamp) line.duration = next - line.timestamp;
            if (line.duration <= 0L) line.duration = Math.max(1200L, line.lyric.codePointCount(0, line.lyric.length()) * 180L);
        }
    }

    private static void normalizeWordDurations(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line.words.isEmpty()) continue;
            List<LyricLine.Word> normalized = new ArrayList<>();
            long lineEnd = line.timestamp + Math.max(1L, line.duration);
            for (int i = 0; i < line.words.size(); i++) {
                LyricLine.Word word = line.words.get(i);
                long start = Math.max(line.timestamp, word.timestamp);
                long nextStart = i + 1 < line.words.size() ? Math.max(start + 1L, line.words.get(i + 1).timestamp) : lineEnd;
                long duration = word.duration > 0L ? word.duration : nextStart - start;
                if (lineEnd > start) duration = Math.min(duration, lineEnd - start);
                LyricLine.Word copy = new LyricLine.Word(word.word, start, Math.max(1L, duration));
                copy.background = word.background;
                normalized.add(copy);
            }
            line.words.clear();
            line.words.addAll(normalized);
        }
    }

    private static long parseTimestamp(Matcher matcher) {
        return parseTimestamp(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static long parseTimestamp(String minutes, String seconds, String fraction) {
        long millis = Long.parseLong(fraction);
        if (fraction.length() == 1) millis *= 100L;
        else if (fraction.length() == 2) millis *= 10L;
        return Long.parseLong(minutes) * 60000L + Long.parseLong(seconds) * 1000L + millis;
    }

    private static final class TimedText {
        private String text;
        private final long start;
        private final long duration;
        /** 这一段来自 {@code ttm:role="x-bg"} 的和声 span。 */
        private boolean background;

        private TimedText(String text, long start, long duration) {
            this.text = text;
            this.start = start;
            this.duration = duration;
        }
    }
}
