package com.muoniumplayer.core.utils.other;

import lombok.SneakyThrows;

import java.io.IOException;
import java.io.InputStream;

/**
 * 带进度回调的输入流。
 *
 * <p>原实现只覆写了 {@link #read()}，而 {@link InputStream#read(byte[], int, int)} 的默认实现是
 * 「逐字节循环调用 {@code read()}」。于是下载一首几 MB 的歌，每一个字节都要：一次 {@code in.read()}、
 * 一次 {@code in.available()}、一次 {@code onProgress}（写静态字段 + 更新灵动岛进度）、一次
 * {@code bytesRead}。批量读被转发之后这些回调按块触发，下载吞吐不再被回调开销压住。</p>
 *
 * <p>进度语义不变：分母仍然是 {@code in.available()}（{@code HttpUtils} 的流把它实现为响应声明的
 * 总长度），分子仍然是累计已读字节数，因此进度条的数值走势与改动前一致，只是刷新频率从「每字节」
 * 变成「每块」。</p>
 */
public class WrappedInputStream extends InputStream {

    private final InputStream in;
    private final ProgressListener listener;

    @SneakyThrows
    public WrappedInputStream(InputStream in, ProgressListener l) {
        this.in = in;
        this.listener = l;
    }

    public int read = 0;

    @Override
    public int read() throws IOException {
        int read = this.in.read();
        if (read >= 0) {
            this.read++;
            publish();
        }
        return read;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = this.in.read(buffer, offset, length);
        if (read > 0) {
            this.read += read;
            publish();
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        long skipped = this.in.skip(count);
        if (skipped > 0L) {
            this.read += (int) skipped;
            publish();
        }
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return this.in.available();
    }

    @Override
    public void close() throws IOException {
        this.in.close();
    }

    private void publish() throws IOException {
        int total = this.in.available();
        // total <= 0 表示服务端没有声明长度（分块传输）：算不出百分比就只报字节数，
        // 而不是把一个负数或者 Infinity 送进进度条。
        if (total > 0) {
            this.listener.onProgress(this.read / (double) total);
        }
        this.listener.bytesRead(this.read);
    }

    public interface ProgressListener {

        void onProgress(double progress);

        void bytesRead(int bytesRead);

    }

}