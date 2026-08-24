package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用外部 ffmpeg 把任意格式解成分析窗口，是 {@link PcmWindowReader} 的兜底。
 *
 * <h3>为什么要有它</h3>
 * <p>automix 的所有结构判断（小节网格、前奏结束点、尾奏落点、K 加权响度、调性）都只要单声道包络，
 * 不参与播放。但原来的读取器只解析 WAV，缓存里的 mp3 / flac 一律读不出来，于是这些判断整体失效。
 * 把解码外包给 ffmpeg 之后，格式不再是分析能力的门槛。</p>
 *
 * <h3>三个刻意的取舍</h3>
 * <ul>
 *   <li><b>22.05 kHz 单声道</b>：onset 通量、能量包络、BS.1770 K 加权系数都是按传入采样率推导的，降采样
 *       只是少掉 11 kHz 以上的内容——那里既没有节拍也没有基频。代价换来一半的解码时间和一半的内存。</li>
 *   <li><b>单窗口缓存</b>：一次 plan 要读五个窗口，但它们高度重叠（尾奏扫描的 45 s 窗口本身就包含出点
 *       网格窗口和重叠区窗口）。缓存最近一次解码的结果并切片，把三到五次进程压到两三次。</li>
 *   <li><b>stderr 必须异步排空</b>：ffmpeg 的管道被写满时子进程会直接卡死，这个坑项目里已经踩过一次。</li>
 * </ul>
 *
 * <p>ffmpeg 不存在、解码失败、超时，全部返回空窗口——分析退回"不可分析"，播放不受影响。</p>
 */
final class FfmpegAnalysisAudio implements AnalysisAudio {

    /** 分析用采样率。节拍与基频都远在 11 kHz 以下，再高只是浪费解码时间。 */
    private static final int RATE = 22_050;
    /** 与 {@link PcmWindowReader} 对齐的单窗口上限，防止一次畸形请求申请任意大的数组。 */
    private static final long MAX_WINDOW_MILLIS = 60_000L;
    /** 缓存上限；超过这个长度的窗口只用一次就丢，免得两路 deck 各留一大块常驻内存。 */
    private static final int MAX_CACHE_FRAMES = 55 * RATE;
    /** 单次解码的挂钟上限。超时按解码失败处理，宁可少一次分析也不要吊住预备线程。 */
    private static final long DECODE_TIMEOUT_MILLIS = 25_000L;

    private static final float[] EMPTY = new float[0];

    private final String executable;
    private final String path;

    /** 最近一次解码结果，以及它在文件里的起点。{@code -1} 表示还没有缓存。 */
    private float[] cache;
    private long cacheStartMillis = -1L;

    private FfmpegAnalysisAudio(String executable, String path) {
        this.executable = executable;
        this.path = path;
    }

    /** ffmpeg 不可用或文件不存在时返回 {@code null}。 */
    static FfmpegAnalysisAudio open(File source) {
        if (source == null || !source.isFile() || source.length() < 1_024L) return null;
        String executable = FfmpegSupport.executable();
        if (executable == null) {
            FfmpegSupport.warnMissingOnce();
            return null;
        }
        return new FfmpegAnalysisAudio(executable, source.getAbsolutePath());
    }

    @Override
    public int sampleRate() {
        return RATE;
    }

    @Override
    public float[] readMono(long startMillis, long lengthMillis) {
        if (lengthMillis <= 0L) return EMPTY;
        long start = Math.max(0L, startMillis);
        long capped = Math.min(lengthMillis, MAX_WINDOW_MILLIS);

        float[] hit = fromCache(start, capped);
        if (hit != null) return hit;

        float[] decoded = decode(start, capped);
        if (decoded.length > 0 && decoded.length <= MAX_CACHE_FRAMES) {
            this.cache = decoded;
            this.cacheStartMillis = start;
        }
        return decoded;
    }

    /**
     * 从缓存切一段。只有请求被完整覆盖时才算命中——短于请求的答案可能是"文件到头了"，也可能是"缓存不够
     * 长"，把两者混在一起会让调用方对着半截窗口做结构判断。
     */
    private float[] fromCache(long startMillis, long lengthMillis) {
        float[] cached = this.cache;
        if (cached == null || this.cacheStartMillis < 0L) return null;
        long offset = startMillis - this.cacheStartMillis;
        if (offset < 0L) return null;
        long from = offset * RATE / 1000L;
        long want = lengthMillis * RATE / 1000L;
        if (want <= 0L || from >= cached.length) return null;
        if (from + want > cached.length) return null;
        float[] slice = new float[(int) want];
        System.arraycopy(cached, (int) from, slice, 0, (int) want);
        return slice;
    }

    private float[] decode(long startMillis, long lengthMillis) {
        int maxFrames = (int) (lengthMillis * RATE / 1000L);
        if (maxFrames <= 0) return EMPTY;

        Process process = null;
        try {
            process = new ProcessBuilder(command(startMillis, lengthMillis)).start();
            drainQuietly(process.getErrorStream());

            byte[] raw = new byte[maxFrames * 4];
            InputStream stdout = process.getInputStream();
            long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MILLIS;
            int total = 0;
            while (total < raw.length) {
                int read = stdout.read(raw, total, raw.length - total);
                if (read < 0) break;
                total += read;
                if (System.currentTimeMillis() > deadline) break;
            }

            int frames = total / 4;
            if (frames <= 0) return EMPTY;
            float[] mono = new float[frames];
            for (int frame = 0; frame < frames; frame++) {
                int base = frame * 4;
                int bits = (raw[base] & 0xFF)
                        | ((raw[base + 1] & 0xFF) << 8)
                        | ((raw[base + 2] & 0xFF) << 16)
                        | (raw[base + 3] << 24);
                float value = Float.intBitsToFloat(bits);
                mono[frame] = Float.isNaN(value) || Float.isInfinite(value) ? 0f : value;
            }
            return mono;
        } catch (Throwable ignored) {
            return EMPTY;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private List<String> command(long startMillis, long lengthMillis) {
        List<String> command = new ArrayList<String>();
        command.add(this.executable);
        command.add("-hide_banner");
        command.add("-v");
        command.add("error");
        command.add("-nostdin");
        // 单线程解码。默认 ffmpeg 会按核心数开满线程，而这里要的只是一小段 22050 Hz 单声道 PCM——
        // 一条线程绰绰有余，多开只是在游戏运行时把所有核心一起占掉。
        command.add("-threads");
        command.add("1");
        // -ss 放在 -i 之前是输入级 seek（快，走索引），-accurate_seek 把它纠正到样本级，否则一个粗略的
        // 关键帧 seek 会让"出点前 12 秒"实际落在别的地方，网格相位就全错了。
        command.add("-accurate_seek");
        command.add("-ss");
        command.add(seconds(startMillis));
        command.add("-t");
        command.add(seconds(lengthMillis));
        command.add("-i");
        command.add(this.path);
        command.add("-vn");
        command.add("-map");
        command.add("a:0?");
        command.add("-ac");
        command.add("1");
        command.add("-ar");
        command.add(Integer.toString(RATE));
        command.add("-f");
        command.add("f32le");
        command.add("-");
        return command;
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    /** ffmpeg 的 stderr 必须一直被读走：管道满了子进程会停在写调用上，看起来像解码挂死。 */
    private static void drainQuietly(final InputStream stream) {
        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] sink = new byte[4_096];
                try {
                    while (stream.read(sink) >= 0) {
                        // 丢掉即可：解码失败会由空窗口体现，日志里再多一份 ffmpeg 噪音没有价值。
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        stream.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "muonium-automix-ffmpeg");
        drain.setDaemon(true);
        drain.start();
    }

    @Override
    public void close() {
        this.cache = null;
        this.cacheStartMillis = -1L;
    }
}
