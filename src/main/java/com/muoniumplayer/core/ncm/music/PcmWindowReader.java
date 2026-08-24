package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads mono analysis windows straight out of a decoded WAV cache file, with its own file handle.
 *
 * <h3>Why this exists instead of reading the JSyn sample</h3>
 * <p>A {@code SoundFile} does <em>not</em> hold the decoded track in RAM: {@code SampleLoader
 * .loadStreamedFloatSample} builds a {@code BufferedFloatSample}, which streams from a shared
 * {@code RandomAccessFile} through one sliding window ({@code lastReadIndexOffset} plus a single
 * decode buffer). Reading an arbitrary position out of that sample <b>moves the window</b>. Doing that
 * to a deck that is currently playing repositions the buffer under the JSyn engine thread, which then
 * indexes the window with a stale offset and throws
 * {@code ArrayIndexOutOfBoundsException: -12666884} out of {@code SynthesisEngine.generateNextBuffer}
 * - audio stops being produced for as long as the analysis runs. That is exactly what automix analysis
 * used to do to the outgoing track.</p>
 *
 * <p>So analysis never touches a live sample. It opens the same cache file again, seeks, decodes and
 * closes, which is both race-free and much faster than a few hundred thousand single-value reads
 * through the streaming sample.</p>
 *
 * <p>Every failure path returns {@code null} / an empty window: analysis is optional, and a file this
 * class cannot parse simply means the handover degrades to a plain equal-power crossfade.</p>
 */
final class PcmWindowReader implements AnalysisAudio {

    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;
    /** Upper bound on one window, so a malformed request cannot allocate an arbitrary array. */
    private static final long MAX_WINDOW_MILLIS = 60_000L;
    private static final int COPY_CHUNK_BYTES = 64 * 1024;

    private final RandomAccessFile handle;
    private final long dataOffset;
    private final long dataBytes;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final int format;
    private final int bytesPerFrame;
    private final int frames;

    private PcmWindowReader(RandomAccessFile handle, long dataOffset, long dataBytes, int sampleRate,
                            int channels, int bitsPerSample, int format, int bytesPerFrame) {
        this.handle = handle;
        this.dataOffset = dataOffset;
        this.dataBytes = dataBytes;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.format = format;
        this.bytesPerFrame = bytesPerFrame;
        long frameCount = dataBytes / bytesPerFrame;
        this.frames = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, frameCount));
    }

    /** Opens the file for analysis, or returns {@code null} when it is not a WAV this can decode. */
    static PcmWindowReader open(File source) {
        if (source == null || !source.isFile() || source.length() < 44L) return null;
        RandomAccessFile handle = null;
        try {
            handle = new RandomAccessFile(source, "r");
            if (readTag(handle) != 0x52494646) return null;          // "RIFF"
            handle.readInt();                                         // riff size, unused
            if (readTag(handle) != 0x57415645) return null;          // "WAVE"

            int format = 0;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            int bytesPerFrame = 0;
            long dataOffset = -1L;
            long dataBytes = 0L;
            long length = handle.length();

            while (handle.getFilePointer() + 8L <= length) {
                int id = readTag(handle);
                long size = readIntLittle(handle) & 0xFFFFFFFFL;
                long body = handle.getFilePointer();
                if (id == 0x666D7420) {                               // "fmt "
                    format = readShortLittle(handle);
                    channels = readShortLittle(handle);
                    sampleRate = readIntLittle(handle);
                    readIntLittle(handle);                            // byte rate
                    bytesPerFrame = readShortLittle(handle);
                    bitsPerSample = readShortLittle(handle);
                    if (format == WAVE_FORMAT_EXTENSIBLE && size >= 40L) {
                        readShortLittle(handle);                      // extension size
                        readShortLittle(handle);                      // valid bits
                        readIntLittle(handle);                        // channel mask
                        format = readShortLittle(handle);             // first field of the sub-format GUID
                    }
                } else if (id == 0x64617461) {                        // "data"
                    dataOffset = body;
                    dataBytes = Math.min(size, length - body);
                    if (dataBytes > 0L) break;
                }
                long next = body + size + (size & 1L);                // chunks are word aligned
                if (next <= body || next > length) break;
                handle.seek(next);
            }

            if (dataOffset < 0L || dataBytes <= 0L) return null;
            if (channels < 1 || channels > 8 || sampleRate <= 0) return null;
            if (bytesPerFrame <= 0) bytesPerFrame = channels * (bitsPerSample / 8);
            if (bytesPerFrame <= 0) return null;
            boolean supported = (format == WAVE_FORMAT_PCM
                    && (bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32))
                    || (format == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32);
            if (!supported) return null;

            PcmWindowReader reader = new PcmWindowReader(handle, dataOffset, dataBytes, sampleRate,
                    channels, bitsPerSample, format, bytesPerFrame);
            if (reader.frames <= 0) return null;
            handle = null;   // ownership handed to the reader
            return reader;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    int frames() {
        return frames;
    }

    long durationMillis() {
        return frames * 1000L / sampleRate;
    }

    /**
     * Copies a window of the file down to normalised mono.
     *
     * <p>Returns an empty array when the request falls outside the data chunk, so callers never have to
     * bounds-check twice. Channels are averaged, which is what every measurement here wants: a beat or
     * an envelope is a property of the mix, not of one side of the stereo image.</p>
     */
    @Override
    public float[] readMono(long startMillis, long lengthMillis) {
        if (lengthMillis <= 0L) return new float[0];
        try {
            long capped = Math.min(lengthMillis, MAX_WINDOW_MILLIS);
            long startFrame = Math.max(0L, startMillis) * sampleRate / 1000L;
            if (startFrame >= frames) return new float[0];
            int wantFrames = (int) Math.min(frames - startFrame, capped * sampleRate / 1000L);
            if (wantFrames <= 0) return new float[0];

            int sampleBytes = bitsPerSample / 8;
            float[] mono = new float[wantFrames];
            byte[] buffer = new byte[Math.max(bytesPerFrame, (COPY_CHUNK_BYTES / bytesPerFrame) * bytesPerFrame)];

            handle.seek(dataOffset + startFrame * bytesPerFrame);
            int produced = 0;
            while (produced < wantFrames) {
                int framesLeft = wantFrames - produced;
                int wantBytes = (int) Math.min(buffer.length, (long) framesLeft * bytesPerFrame);
                int read = readFully(buffer, wantBytes);
                if (read < bytesPerFrame) break;
                int framesRead = read / bytesPerFrame;
                for (int frame = 0; frame < framesRead; frame++) {
                    int base = frame * bytesPerFrame;
                    float sum = 0f;
                    for (int channel = 0; channel < channels; channel++) {
                        sum += decode(buffer, base + channel * sampleBytes);
                    }
                    mono[produced + frame] = sum / channels;
                }
                produced += framesRead;
            }
            if (produced == wantFrames) return mono;
            if (produced <= 0) return new float[0];
            float[] trimmed = new float[produced];
            System.arraycopy(mono, 0, trimmed, 0, produced);
            return trimmed;
        } catch (Throwable ignored) {
            // A cache file can be deleted or truncated by a concurrent switch.
            return new float[0];
        }
    }

    private int readFully(byte[] buffer, int wantBytes) throws IOException {
        int total = 0;
        while (total < wantBytes) {
            int read = handle.read(buffer, total, wantBytes - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private float decode(byte[] buffer, int offset) {
        switch (bitsPerSample) {
            case 16: {
                int value = (buffer[offset] & 0xFF) | (buffer[offset + 1] << 8);
                return value / 32768f;
            }
            case 24: {
                int value = (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
                        | (buffer[offset + 2] << 16);
                return value / 8388608f;
            }
            case 32: {
                int bits = (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
                        | ((buffer[offset + 2] & 0xFF) << 16) | (buffer[offset + 3] << 24);
                if (format == WAVE_FORMAT_IEEE_FLOAT) {
                    float value = Float.intBitsToFloat(bits);
                    if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
                    return value;
                }
                return (float) (bits / 2147483648.0);
            }
            default:
                return 0f;
        }
    }

    @Override
    public void close() {
        try {
            handle.close();
        } catch (IOException ignored) {
        }
    }

    private static int readTag(RandomAccessFile handle) throws IOException {
        return handle.readInt();
    }

    private static int readIntLittle(RandomAccessFile handle) throws IOException {
        int value = handle.readInt();
        return Integer.reverseBytes(value);
    }

    private static int readShortLittle(RandomAccessFile handle) throws IOException {
        int low = handle.read();
        int high = handle.read();
        if (low < 0 || high < 0) throw new IOException("truncated WAV header");
        return (high << 8) | low;
    }
}
