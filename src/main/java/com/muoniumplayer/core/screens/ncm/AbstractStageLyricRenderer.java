package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 各套「文字 PV」样式的公共骨架：帧计时、整层开合、裁剪、节拍平滑、可见行窗口。
 *
 * <h3>三条工程约束（从「律动词云」沿用，新样式必须一起遵守）</h3>
 * <ol>
 *   <li><b>布局只在换行/换视口时重算。</b>种子取自行的时间戳与文本，同一行每帧画出来一样，
 *       否则字会原地乱抖。</li>
 *   <li><b>动画与帧率无关。</b>进场取播放毫秒，整层开合与镜头取真实 dt，一帧最多推进
 *       {@link FullscreenLyricStage#MAX_FRAME_SECONDS}。</li>
 *   <li><b>画之前必须开裁剪。</b>歌词视口只是播放器的右半边，溢出会画到封面和控件上。</li>
 * </ol>
 */
abstract class AbstractStageLyricRenderer
        implements FullscreenLyricRenderer, SharedRenderingConstants, SharedConstants {

    /** 整层开合的时间常数，秒。与「律动词云」一致。 */
    private static final double OPEN_TAU = .11;

    private static final double BEAT_ATTACK_TAU = .028;
    private static final double BEAT_RELEASE_TAU = .14;

    private long lastFrameNanos;

    /** 本帧的时间步长，秒；已夹到 {@link FullscreenLyricStage#MAX_FRAME_SECONDS}。 */
    protected double frameSeconds;

    /** 这一层活了多少秒。用于与播放进度无关的环境动画（背景、齿轮、尘埃）。 */
    protected double elapsedSeconds;

    /** 整层开合量 0..1，{@link #reset()} 归零，于是每次重开歌词页都重新滑进来一次。 */
    private double openK;

    /** 平滑后的节拍强度 0..1，优先用实时音频包络，退回当前行的演唱起伏。 */
    protected double beat;

    /** 上一帧用的字号倍率，用来发现「歌词字号被调了」。 */
    private float laidOutFontScale = Float.NaN;

    /**
     * 本帧有影响排版的配置被改动。
     *
     * <p>子类的重排条件<b>必须</b>带上它。各样式只在「换行 / 换视口」时重排，而拖字号滑块这两件事
     * 都没发生 —— 不带上它就会出现「滑块拖了、要等到下一句歌词才变」，看起来像实时预览失效。</p>
     */
    protected boolean tuningDirty;

    public final void reset() {
        lastFrameNanos = 0L;
        frameSeconds = 0;
        elapsedSeconds = 0;
        openK = 0;
        beat = 0;
        laidOutFontScale = Float.NaN;
        tuningDirty = false;
        onReset();
    }

    protected abstract void onReset();

    public final void render(double viewLeft, double viewTop, double viewWidth, double viewHeight,
                             float alpha, double positionMs, LyricLine currentLine) {
        if (viewWidth <= 4 || viewHeight <= 4 || alpha <= 0.004f) return;

        long now = System.nanoTime();
        frameSeconds = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        if (frameSeconds < 0) frameSeconds = 0;
        if (frameSeconds > FullscreenLyricStage.MAX_FRAME_SECONDS) {
            frameSeconds = FullscreenLyricStage.MAX_FRAME_SECONDS;
        }
        elapsedSeconds += frameSeconds;

        openK += (1.0 - openK) * (1.0 - Math.exp(-frameSeconds / OPEN_TAU));
        if (openK > .998) openK = 1.0;
        double opened = FullscreenLyricStage.smoothstep(openK);

        updateBeat(positionMs, currentLine);

        float fontScaleNow = HudConfig.lyricFontScale;
        tuningDirty = Float.isNaN(laidOutFontScale)
                || Math.abs(fontScaleNow - laidOutFontScale) > 1.0e-4f;
        laidOutFontScale = fontScaleNow;

        FullscreenLyricStage.beginStageClip(viewLeft, viewTop, viewWidth, viewHeight);
        try {
            draw(viewLeft, viewTop, viewWidth, viewHeight,
                    (float) (alpha * opened), positionMs, currentLine, opened);
        } finally {
            FullscreenLyricStage.endStageClip();
        }
    }

    private void updateBeat(double positionMs, LyricLine currentLine) {
        double wordPulse = 0;
        if (currentLine != null) {
            double progress = FullscreenLyricStage.lineProgress(currentLine, positionMs);
            if (progress > 0 && progress < 1) wordPulse = Math.sin(Math.PI * progress) * .5;
        }
        double audio = PlaybackPulse.isAvailable() ? PlaybackPulse.beat() : 0.0;
        double target = Math.max(audio, wordPulse);
        double tau = target > beat ? BEAT_ATTACK_TAU : BEAT_RELEASE_TAU;
        beat += (target - beat) * (1.0 - Math.exp(-frameSeconds / tau));
        if (beat < .001) beat = 0;
    }

    /**
     * 画这一层的内容。调用时裁剪已建立，{@code alpha} 已乘过整层开合量。
     *
     * @param opened 整层开合量 0..1，需要「从下面滑上来」之类的入场时用它
     */
    protected abstract void draw(double left, double top, double width, double height,
                                 float alpha, double positionMs, LyricLine currentLine,
                                 double opened);

    // ==================== 可见行窗口 ====================

    /** 当前行在整份歌词里的下标；找不到时返回 {@code -1}。 */
    protected static int indexOf(LyricLine line) {
        if (line == null) return -1;
        List<LyricLine> lyrics = CloudMusic.lyrics;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i) == line) return i;
        }
        return -1;
    }

    /**
     * 窗口该以哪一行为中心。
     *
     * <p>不能只用 {@code indexOf(current)}：间奏、空行、还没唱到第一句时 {@code current} 可能是
     * {@code null} 或是一条被过滤掉的空行，那时 {@code indexOf} 返回 -1。此前的写法会退回 0，
     * 于是画面停在整首歌的开头不动 —— 看起来就是「间奏时歌词跳回开头」。所以退化时改成
     * <b>按播放时间找最后一条已经开始的行</b>。</p>
     */
    protected static int centerIndex(List<LyricLine> lyrics, LyricLine current, double positionMs) {
        int direct = -1;
        for (int i = 0; i < lyrics.size(); i++) {
            if (lyrics.get(i) == current) {
                direct = i;
                break;
            }
        }
        if (direct >= 0) return direct;
        int best = 0;
        for (int i = 0; i < lyrics.size(); i++) {
            LyricLine line = lyrics.get(i);
            if (line != null && line.timestamp <= positionMs) best = i;
            else break;
        }
        return best;
    }

    /**
     * 取当前行前后各若干行，跳过空行。
     *
     * <p>参考实现的多行样式都是「以当前行为中心的一个滑动窗口」，而不是整首歌全画出来 ——
     * 一首歌上百行，全画一遍纯属浪费。</p>
     */
    protected static List<LyricLine> window(LyricLine current, double positionMs,
                                            int before, int after) {
        List<LyricLine> out = new ArrayList<LyricLine>();
        List<LyricLine> lyrics = CloudMusic.lyrics;
        if (lyrics.isEmpty()) return out;
        int center = centerIndex(lyrics, current, positionMs);
        int from = Math.max(0, center - before);
        int to = Math.min(lyrics.size() - 1, center + after);
        for (int i = from; i <= to; i++) {
            LyricLine line = lyrics.get(i);
            if (line == null) continue;
            if (line.isBreakLine) continue;
            if (line.lyric == null || line.lyric.trim().isEmpty()) continue;
            out.add(line);
        }
        return out;
    }

    /**
     * 这一批可见行里哪一行是「当前」。
     *
     * <p>{@code current} 有可能因为是空行而被 {@link #window} 过滤掉，那时按播放时间挑最后一条
     * 已经开始的行，而不是一律退回第 0 行。</p>
     */
    protected static int activeIndexIn(List<LyricLine> lines, LyricLine current, double positionMs) {
        int direct = lines.indexOf(current);
        if (direct >= 0) return direct;
        int best = 0;
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (line != null && line.timestamp <= positionMs) best = i;
            else break;
        }
        return best;
    }
}
