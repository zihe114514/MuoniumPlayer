package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds responsive physical KTV rows while preserving each word's original timing.
 * Rendering, colour, glow and playback state remain in {@link MusicLyricsWidget}.
 */
final class KaraokeLayoutBuilder {

    private KaraokeLayoutBuilder() {
    }

    static Layout build(CFontRenderer font, LyricLine line, double availableWidth) {
        List<String> physicalLines = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        double currentWidth = 0.0;

        for (LyricLine.Word word : line.words) {
            String text = word.word;
            if (text == null || text.isEmpty()) {
                continue;
            }

            double wordWidth = font.getStringWidthD(text);
            if (wordWidth <= availableWidth) {
                if (currentLine.length() > 0 && currentWidth + wordWidth > availableWidth) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
                segments.add(new Segment(text, word, physicalLines.size(), currentWidth, wordWidth,
                        0, text.codePointCount(0, text.length())));
                currentLine.append(text);
                currentWidth += wordWidth;
                continue;
            }

            int start = 0;
            while (start < text.length()) {
                if (currentLine.length() > 0 && currentWidth >= availableWidth - .01) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }

                int end = findFragmentEnd(font, text, start, availableWidth - currentWidth);
                if (end <= start) {
                    if (currentLine.length() > 0) {
                        physicalLines.add(currentLine.toString());
                        currentLine.setLength(0);
                        currentWidth = 0.0;
                        continue;
                    }
                    end = text.offsetByCodePoints(start, 1);
                }

                String fragment = text.substring(start, end);
                double fragmentWidth = font.getStringWidthD(fragment);
                segments.add(new Segment(fragment, word, physicalLines.size(), currentWidth, fragmentWidth,
                        text.codePointCount(0, start), fragment.codePointCount(0, fragment.length()),
                        text.codePointCount(0, text.length())));
                currentLine.append(fragment);
                currentWidth += fragmentWidth;
                start = end;

                if (start < text.length()) {
                    physicalLines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0.0;
                }
            }
        }

        if (currentLine.length() > 0 || physicalLines.isEmpty()) {
            physicalLines.add(currentLine.toString());
        }
        return new Layout(physicalLines.toArray(new String[0]), segments);
    }

    static String[] fitText(CFontRenderer font, String text, double availableWidth) {
        if (text == null || text.isEmpty()) {
            return new String[]{""};
        }
        String[] fitted = font.fitWidth(text, availableWidth);
        return fitted == null || fitted.length == 0 ? new String[]{text} : fitted;
    }

    private static int findFragmentEnd(CFontRenderer font, String text, int start, double availableWidth) {
        int end = start;
        int candidate = start;
        while (candidate < text.length()) {
            int next = text.offsetByCodePoints(candidate, 1);
            if (font.getStringWidthD(text.substring(start, next)) > availableWidth) {
                break;
            }
            end = next;
            candidate = next;
        }
        return end;
    }

    static final class Layout {
        final String[] primaryLines;
        final List<Segment> segments;
        /**
         * 每个物理行的真实步进宽度 = 该行所有片段宽度之和。
         *
         * <p>不能用 {@code font.getStringWidthD(primaryLines[i])} 代替：那个值含字间 kerning，
         * 而片段的 {@code offsetX} 是逐词宽度累加出来的、不含 kerning。两者相差通常一到几像素。
         * 左对齐时看不出来（都从内容区左边缘起算），但<b>右对齐</b>时对齐基准用整行宽度、字词落点
         * 用累加宽度，行尾就会正好溢出这个差值 —— 对唱行被强制右对齐，所以只在对唱段落露出来。</p>
         */
        final double[] lineAdvances;

        private Layout(String[] primaryLines, List<Segment> segments) {
            this.primaryLines = primaryLines;
            this.segments = segments;
            this.lineAdvances = new double[primaryLines.length];
            for (Segment segment : segments) {
                if (segment.lineIndex >= 0 && segment.lineIndex < this.lineAdvances.length) {
                    this.lineAdvances[segment.lineIndex] += segment.width;
                }
            }
        }

        /** 第 {@code lineIndex} 个物理行的步进宽度；越界时回退到 0（空行）。 */
        double lineAdvance(int lineIndex) {
            return lineIndex >= 0 && lineIndex < lineAdvances.length ? lineAdvances[lineIndex] : 0.0;
        }
    }

    static final class Segment {
        final String text;
        final LyricLine.Word word;
        final int lineIndex;
        final double offsetX;
        final double width;
        final int characterOffset;
        final int characterCount;
        final int totalCharacterCount;

        private Segment(String text, LyricLine.Word word, int lineIndex, double offsetX, double width,
                        int characterOffset, int characterCount) {
            this(text, word, lineIndex, offsetX, width, characterOffset, characterCount, characterCount);
        }

        private Segment(String text, LyricLine.Word word, int lineIndex, double offsetX, double width,
                        int characterOffset, int characterCount, int totalCharacterCount) {
            this.text = text;
            this.word = word;
            this.lineIndex = lineIndex;
            this.offsetX = offsetX;
            this.width = width;
            this.characterOffset = characterOffset;
            this.characterCount = Math.max(1, characterCount);
            this.totalCharacterCount = Math.max(this.characterCount, totalCharacterCount);
        }
    }
}