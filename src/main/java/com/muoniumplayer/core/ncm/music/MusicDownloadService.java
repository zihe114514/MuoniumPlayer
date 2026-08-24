package com.muoniumplayer.core.ncm.music;

import lombok.SneakyThrows;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.WrappedInputStream;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Download implementation and its existing progress publication contract.
 *
 * <p>Both the in-player download state and Dynamic Island notifications are
 * published at their original points in the transfer lifecycle.</p>
 */
final class MusicDownloadService {

    /** Audio payloads are large; a stalled CDN read must not be treated as a failure after 8s. */
    private static final int STREAM_CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int STREAM_READ_TIMEOUT_MILLIS = 45_000;

    private MusicDownloadService() {
    }

    @SneakyThrows
    static void downloadMusic(String playUrl, File music) {
        // Some CDNs reject anonymous requests (Bilibili answers 403 without a browser
        // User-Agent plus a bilibili Referer), so host-specific headers travel with the transfer.
        Map<String, String> streamHeaders = AudioStreamHeaders.forUrl(playUrl);
        MuoniumPlayerExtension.getInstance().musicInfo.downloading = true;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 0;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = "0 b/s";
        DownloadDynamicIsland.beginDownload();

        try {
            InputStream stream = new WrappedInputStream(HttpUtils.get(playUrl, null, streamHeaders,
                    STREAM_CONNECT_TIMEOUT_MILLIS, STREAM_READ_TIMEOUT_MILLIS),
                    new WrappedInputStream.ProgressListener() {
                        com.muoniumplayer.core.utils.timing.Timer timer = new com.muoniumplayer.core.utils.timing.Timer();

                        @Override
                        public void onProgress(double progress) {
                            MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = progress;
                            DownloadDynamicIsland.updateProgress(progress);
                            if (progress >= 1) {
                                MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
                            }
                        }

                        final long kilo = 1024;
                        final long mega = kilo * kilo;
                        final long giga = mega * kilo;
                        final long tera = giga * kilo;

                        String getSize(long size) {
                            String text;
                            double kb = (double) size / kilo;
                            double mb = kb / kilo;
                            double gb = mb / kilo;
                            double tb = gb / kilo;
                            if (size < kilo) {
                                text = size + " Bytes";
                            } else if (size < mega) {
                                text = String.format("%.2f", kb) + " KB";
                            } else if (size < giga) {
                                text = String.format("%.2f", mb) + " MB";
                            } else if (size < tera) {
                                text = String.format("%.2f", gb) + " GB";
                            } else {
                                text = String.format("%.2f", tb) + " TB";
                            }
                            return text;
                        }

                        int lastBytesRead = 0;

                        @Override
                        public void bytesRead(int bytesRead) {
                            int checkDelay = 500;
                            if (timer.isDelayed(checkDelay)) {
                                timer.reset();
                                int diff = (bytesRead - lastBytesRead) * (1000 / checkDelay);
                                String speed = getSize(diff) + "/s";
                                MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = speed;
                                DownloadDynamicIsland.updateSpeed(speed);
                                lastBytesRead = bytesRead;
                            }
                        }
                    });

            /*
             * 必须 try/finally 关流。原来是「writeTo 之后再 close」：一旦传输中途抛异常（CDN 断流、
             * 读超时），output 就一直开着，而 catch 分支紧接着要 music.delete() —— Windows 上删除
             * 一个仍被本进程打开的文件必然失败，于是磁盘上留下一个长度不对的半成品，下一次播放
             * 直接拿它去解码。stream 一并关掉，否则连接也回不到池里。
             */
            OutputStream output = Files.newOutputStream(music.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                writeTo(stream, output);
            } finally {
                closeQuietly(output);
                closeQuietly(stream);
            }
            MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 1.0;
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.finishDownload();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();
            music.delete();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 关流失败无法补救，也不该盖掉传输阶段真正的异常。
        }
    }

    /**
     * 音频体积在几 MB 到几十 MB，1 KB 的搬运缓冲区意味着一次下载要来回几万次；同时
     * {@link WrappedInputStream} 的进度回调是按块触发的，块太小回调本身就成了开销。
     * 64 KB 与磁盘写入的簇大小同量级，再往上收益就平了。
     */
    private static final int TRANSFER_BUFFER_BYTES = 64 * 1024;

    @SneakyThrows
    static void writeTo(InputStream source, OutputStream destination) {
        byte[] buffer = new byte[TRANSFER_BUFFER_BYTES];
        int length;
        while ((length = source.read(buffer)) != -1) {
            destination.write(buffer, 0, length);
        }
        destination.flush();
    }
}