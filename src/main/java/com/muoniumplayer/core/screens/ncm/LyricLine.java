package com.muoniumplayer.core.screens.ncm;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.utils.timing.Timer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author IzumiiKonata
 * Date: 2025/10/18 10:55
 */
public class LyricLine {
    @Getter
    public long timestamp;  // 时间戳(ms)

    @Getter
    @Setter
    @NonNull
    public String lyric;  // 歌词文本

    public LyricLine(long timestamp, @NonNull String lyric) {
        this.timestamp = timestamp;
        this.lyric = lyric;
    }

    @Getter
    public String translationText;  // 翻译文本
    @Getter
    public String romanizationText; // 罗马音文本

    public long duration;
    public double posY = 0;
    public double height = 0;
    public float alpha = .4f;
    public float hoveringAlpha = 0f;
    public float blurAlpha = 0f;
    public boolean shouldUpdatePosition = false;
    public double reboundAnimation = 0;
    public Timer delayTimer = new Timer();
    public boolean renderEmphasizes = true;
    public boolean isBreakLine = false;

    /**
     * 对唱组编号，{@code -1} 表示独唱行。由 {@link LyricDuetGroups#mark(java.util.List)} 在歌词
     * 提交时写入一次：同组的行时间轴真正重叠，渲染层要把它们当作同一个当前行整块点亮。
     */
    public int duetGroup = -1;

    /**
     * 行在对唱组内的序号，{@code -1} 表示独唱行。渲染层据此决定这一行排在左列还是右列
     * （偶数序号靠左、奇数序号靠右），从而让同时演唱的两位歌手左右分开显示。
     */
    public int duetSlot = -1;

    public final SpringAnimation spring = new SpringAnimation(new SpringParams(.9, 15, 90, false));

    // MusicLyricsWidgets fields
    public double scrollWidth = 0;
    public double offsetX = 0;
    public double targetOffsetX = 0;
    public float lineAlpha = .4f;

    public double offsetY = Double.MIN_VALUE;

    public final List<Word> words = new CopyOnWriteArrayList<>();

    public boolean hasTimedWords() {
        for (Word word : words) {
            if (word != null && word.duration > 0L) return true;
        }
        return false;
    }

    public static class Word {
        public final String word;
        public final long timestamp, duration;
        public final double[] emphasizes;

        // fields for MusicLyricsWidget
        public float alpha = 0.0f;
        public double progress = 0.0;

        public Word(String word, long timestamp, long duration) {
            this.word = word;
            this.timestamp = timestamp;
            this.duration = duration;
            this.emphasizes = new double[word.length()];
        }

        /** 与播放器毫秒时钟对齐的线性逐字进度。 */
        public double getProgress(double positionMs) {
            if (duration <= 0L) return positionMs >= timestamp ? 1.0 : 0.0;
            double value = (positionMs - timestamp) / (double) duration;
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    private boolean heightComputed = false;

    public void markDirty() {
        heightComputed = false;
    }

    public void computeHeight(double width) {

        if (heightComputed) return;

        CFontRenderer fr = FontManager.pf65bold;

        boolean canSetComputed = true;

        if (!this.words.isEmpty()) {
            double height = fr.getHeight();

            double w = 0;
            for (Word word : words) {

                if (!fr.areGlyphsLoaded(word.word)) {
                    canSetComputed = false;
                }

                double wordWidth = fr.getStringWidthD(word.word);

                if (w + wordWidth > width) {
                    w = wordWidth;
                    height += fr.getHeight() * .85 + 4;
                } else {
                    w += wordWidth;
                }

            }

            this.height = height;
        } else {

            if (!fr.areGlyphsLoaded(lyric)) {
                canSetComputed = false;
            }

            int length = fr.fitWidth(lyric, width).length;
            this.height = length * fr.getHeight() * .85 + length * 4;
        }

        if (translationText != null) {
            if (!fr.areGlyphsLoaded(translationText)) {
                canSetComputed = false;
            }

            CFontRenderer frTranslation = FontManager.pf34bold;
            String[] strings = frTranslation.fitWidth(translationText, width);
            height += frTranslation.getHeight() * strings.length + 4 * (strings.length - 1) + 8;
        }

        heightComputed = canSetComputed;
    }

}
