package com.muoniumplayer.core.ncm.music;

import java.io.File;

/**
 * 为 automix 分析挑一个能读的音频源。
 *
 * <p>顺序是有意的：先试 {@link PcmWindowReader}，它直接 seek 解码后的 WAV 缓存，不起进程、不依赖外部
 * 工具；只有它认不出格式（mp3 / flac / m4a 原始文件）时才退到 {@link FfmpegAnalysisAudio}。</p>
 *
 * <p>两条路都失败就返回 {@code null}，交接照旧退化成一次纯等功率淡化——分析从来不是播放的前置条件。</p>
 */
final class AnalysisAudioSource {

    private AnalysisAudioSource() {
    }

    static AnalysisAudio open(File source) {
        PcmWindowReader wav = PcmWindowReader.open(source);
        if (wav != null) return wav;
        return FfmpegAnalysisAudio.open(source);
    }
}
