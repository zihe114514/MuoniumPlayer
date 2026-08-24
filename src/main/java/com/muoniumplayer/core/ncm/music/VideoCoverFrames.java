package com.muoniumplayer.core.ncm.music;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 ffmpeg 把动态封面视频解成一串定尺寸帧。
 *
 * <p>管线是 {@code ffmpeg -i <video> -vf fps=N,scale,pad -f image2pipe -c:v png -}:PNG 在字节流里是
 * 自定界的(签名 + 一串"长度 + 类型 + 数据 + CRC"分块,直到 IEND),所以顺着分块头走就能把一帧帧切出来,
 * 不需要任何容器解析,也不额外引入依赖。</p>
 *
 * <p>与参考实现的区别:这里<b>只解一次、解成有限帧</b>,交给项目已有的 {@link
 * com.muoniumplayer.core.rendering.texture.AnimatedCoverTexture} 循环播放,而不是让 ffmpeg 常驻、每帧
 * 上传一次纹理。原因是封面循环通常只有几秒,常驻进程会在整局游戏里持续占用 CPU,而每帧上传必须发生在
 * 主线程上——那是渲染线程,不该为装饰性动画加固定开销。</p>
 *
 * <p>所有资源上限都写死在这里:帧尺寸、帧率、帧数上限、总像素预算与整体超时。任何失败(ffmpeg 缺失、
 * 视频损坏、超时)都返回空列表,调用方继续用静态封面。</p>
 */
final class VideoCoverFrames {

    /** 单帧边长。封面在全屏歌词页最大也就 300 逻辑像素上下,256 足够,再大只是白占内存。 */
    static final int FRAME_SIZE = 256;
    /** 抽帧帧率。动态封面基本都是缓慢的循环动画,10 fps 已经看不出跳帧。 */
    static final int FRAMES_PER_SECOND = 10;
    /** 帧数上限,同时也是时长上限(50 帧 = 5 秒)。 */
    static final int MAX_FRAMES = 50;
    /** 帧数据总预算:50 × 256 × 256 × 4 ≈ 13 MB,再多就该考虑显存与堆了。 */
    private static final long MAX_FRAME_BYTES = 14L * 1024L * 1024L;
    private static final long DECODE_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_SINGLE_FRAME_BYTES = 4 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private VideoCoverFrames() {
    }

    /** 每帧的显示时长,与抽帧帧率对应。 */
    static long frameDurationMillis() {
        return Math.max(40L, 1000L / FRAMES_PER_SECOND);
    }

    /**
     * 解码 {@code video},返回边长为 {@link #FRAME_SIZE} 的帧序列;失败或没有可用帧时返回空列表,
     * 永不抛出。
     */
    static List<BufferedImage> decode(File video) {
        String ffmpeg = FfmpegSupport.executable();
        if (ffmpeg == null || video == null || !video.isFile() || video.length() <= 0L) {
            return Collections.emptyList();
        }

        Process process = null;
        Thread stderrDrain = null;
        Thread watchdog = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    ffmpeg, "-nostdin", "-hide_banner", "-v", "error",
                    // 单线程解码 + 单线程 PNG 编码。默认按核心数开满，而这里只有几十张 256×256 要出，
                    // 多开除了在游戏运行时抢光所有核心之外没有收益。
                    "-threads", "1",
                    "-i", video.getAbsolutePath(),
                    "-an", "-sn", "-dn",
                    // 等比缩放后补边到正方形。补边色是全透明而不是黑色:封面槽位是圆角方形,非 1:1 的
                    // 素材补黑边会在圆角里露出两条黑条,透明则直接透出下层背景。
                    "-vf", "fps=" + FRAMES_PER_SECOND
                            + ",scale=" + FRAME_SIZE + ":" + FRAME_SIZE + ":force_original_aspect_ratio=decrease"
                            + ",format=rgba"
                            + ",pad=" + FRAME_SIZE + ":" + FRAME_SIZE + ":(ow-iw)/2:(oh-ih)/2:color=0x00000000",
                    "-frames:v", String.valueOf(MAX_FRAMES),
                    "-f", "image2pipe", "-c:v", "png", "-");
            process = builder.start();
            stderrDrain = drainAsync(process.getErrorStream());
            long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MILLIS;
            // 超时判断只在两帧之间生效是不够的:ffmpeg 卡住却不关 stdout 时,读操作会永久阻塞并
            // 一直占着线程池里的一条线程。到点直接杀进程,读端随即拿到 EOF,正常收尾。
            watchdog = startWatchdog(process, deadline);
            List<BufferedImage> frames = readFrames(process.getInputStream(), deadline);
            return frames;
        } catch (Throwable failure) {
            System.err.println("[Music/Cover] Animated cover decode failed: " + failure.getMessage());
            return Collections.emptyList();
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if (watchdog != null) watchdog.interrupt();
            if (stderrDrain != null) stderrDrain.interrupt();
        }
    }

    /**
     * 到期后销毁 ffmpeg 进程。销毁会关掉管道,阻塞在 {@code read} 上的解码线程立刻以 EOF 收尾,
     * 已解出的帧照样返回。解码正常结束时调用方会中断它,睡眠随即抛出并安全退出。
     */
    private static Thread startWatchdog(final Process process, final long deadlineMillis) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long remaining = deadlineMillis - System.currentTimeMillis();
                    while (remaining > 0L) {
                        Thread.sleep(Math.min(250L, remaining));
                        remaining = deadlineMillis - System.currentTimeMillis();
                    }
                } catch (InterruptedException finished) {
                    return;   // 解码已经结束,不用管了
                }
                try {
                    if (process.isAlive()) {
                        System.err.println("[Music/Cover] Animated cover decode timed out; stopping ffmpeg");
                        process.destroy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "muonium-ffmpeg-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 顺着 PNG 分块头把连成一片的帧切开。读到流尾、越过预算或超时都算正常结束——已经解出来的帧仍然
     * 可用,少一帧不影响循环观感。
     */
    private static List<BufferedImage> readFrames(InputStream stream, long deadlineMillis) throws IOException {
        List<BufferedImage> frames = new ArrayList<BufferedImage>();
        long frameBytes = 0L;
        byte[] signature = new byte[8];

        while (frames.size() < MAX_FRAMES) {
            if (System.currentTimeMillis() > deadlineMillis) break;
            if (!readFully(stream, signature, 8)) break;
            if (!isPngSignature(signature)) break;

            ByteArrayOutputStream frame = new ByteArrayOutputStream(64 * 1024);
            frame.write(signature, 0, 8);
            if (!readChunks(stream, frame)) break;

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.toByteArray()));
            if (image == null) break;

            frameBytes += (long) image.getWidth() * image.getHeight() * 4L;
            if (frameBytes > MAX_FRAME_BYTES) break;
            frames.add(image);
        }
        return frames;
    }

    /** 读到 IEND 为止。返回 false 表示流在一帧中间就结束了,这一帧不完整必须丢掉。 */
    private static boolean readChunks(InputStream stream, ByteArrayOutputStream frame) throws IOException {
        byte[] header = new byte[8];
        byte[] crc = new byte[4];
        while (true) {
            if (!readFully(stream, header, 8)) return false;
            int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                    | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            if (length < 0 || length > MAX_SINGLE_FRAME_BYTES) return false;
            frame.write(header, 0, 8);
            if (length > 0) {
                byte[] data = new byte[length];
                if (!readFully(stream, data, length)) return false;
                frame.write(data, 0, length);
            }
            if (!readFully(stream, crc, 4)) return false;
            frame.write(crc, 0, 4);
            if (header[4] == 'I' && header[5] == 'E' && header[6] == 'N' && header[7] == 'D') return true;
            if (frame.size() > MAX_SINGLE_FRAME_BYTES) return false;
        }
    }

    private static boolean isPngSignature(byte[] candidate) {
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (candidate[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }

    private static boolean readFully(InputStream stream, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = stream.read(buffer, read, length - read);
            if (count < 0) return false;
            read += count;
        }
        return true;
    }

    /** ffmpeg 的诊断输出必须被读走,否则管道填满后它会阻塞在写 stderr 上而永远不结束。 */
    private static Thread drainAsync(final InputStream stream) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (stream == null) return;
                StringBuilder message = new StringBuilder();
                try {
                    byte[] buffer = new byte[2048];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        if (message.length() < 512) {
                            message.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                } catch (Throwable ignored) {
                }
                String text = message.toString().trim();
                if (!text.isEmpty()) {
                    System.err.println("[Music/Cover] ffmpeg: " + text.replace('\n', ' '));
                }
            }
        }, "muonium-ffmpeg-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
