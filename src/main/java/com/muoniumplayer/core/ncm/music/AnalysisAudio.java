package com.muoniumplayer.core.ncm.music;

import java.io.Closeable;

/**
 * 一段可以按毫秒窗口取出单声道 PCM 的音频源，供 automix 的离线分析使用。
 *
 * <p>抽出这层是因为分析原本只认 WAV：{@link PcmWindowReader} 直接解析 RIFF 头，遇到 mp3 / flac 一律返回
 * {@code null}，于是 {@link AutomixPlanner} 判定"不可分析"，小节对齐、响度匹配、节奏锁定、调性判定全部
 * 失效，日志只会打一句"无分析"。缓存里绝大多数第三方源文件正好是 mp3 / flac，所以那条快路径实际上覆盖
 * 不到多少歌。</p>
 *
 * <p>两个实现：WAV 走 {@link PcmWindowReader}（自带文件句柄，不起进程）；其余格式走
 * {@link FfmpegAnalysisAudio}（外部解码，按需起进程）。选择由 {@link AnalysisAudioSource} 负责。</p>
 *
 * <p>所有失败路径都返回空窗口而不是抛异常：分析永远是加分项，读不到就退回等功率淡化。</p>
 */
interface AnalysisAudio extends Closeable {

    /** 窗口的采样率；{@code <= 0} 表示这个源不可用。 */
    int sampleRate();

    /**
     * 取一段归一化到 [-1,1] 的单声道窗口。请求越界、解码失败都返回长度 0 的数组，调用方不必二次判界。
     */
    float[] readMono(long startMillis, long lengthMillis);

    @Override
    void close();
}
