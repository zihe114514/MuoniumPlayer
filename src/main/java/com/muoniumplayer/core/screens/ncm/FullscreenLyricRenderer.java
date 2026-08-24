package com.muoniumplayer.core.screens.ncm;

/**
 * 全屏歌词页一套展示样式的绘制入口。
 *
 * <p>{@link MusicLyricsPanel} 按 {@link FullscreenLyricStyle} 选一个实现整段接管歌词区的绘制：
 * 经典样式那套滚动、模糊、译文与 FBO 合成一律不执行。</p>
 */
interface FullscreenLyricRenderer {

    /** 换歌/重开歌词页时把状态清干净，否则会看到上一首的内容退场一次。 */
    void reset();

    /**
     * 画一帧。
     *
     * @param currentLine 当前行，{@code null} 表示这一刻没有歌词
     * @param positionMs  本帧的播放毫秒，与其它样式共用同一份播放快照
     */
    void render(double viewLeft, double viewTop, double viewWidth, double viewHeight,
                float alpha, double positionMs, LyricLine currentLine);
}
