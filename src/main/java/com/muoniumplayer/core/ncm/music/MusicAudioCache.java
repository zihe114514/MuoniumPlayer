package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘音频缓存的容量兜底。
 *
 * <p>为什么需要：{@code MusicCache/} 原先只会因为「同一首歌换了音质」而删旧文件，
 * 除此之外只增不减。一首无损 FLAC 二三十兆，长期使用下这个目录能涨到几个 GB 甚至几十 GB，
 * 用户看不到也管不了。歌词缓存不存在这个问题（只在内存里，且有条数上限与 TTL），
 * 真正会无限增长的只有这里。</p>
 *
 * <h3>淘汰单位是「组」而不是「文件」</h3>
 * 一首歌在磁盘上可能有两个文件：下载到的原始容器（{@code <key>.m4a}）与解码后的
 * WAV 边车（{@code <key>.decoded.wav}）。边车能否复用由
 * {@link AudioCacheFiles#isReusableDecodedWav} 按「边车比源文件新」判定，
 * 所以只删其中一个会留下永远无法复用的孤儿文件。这里按文件名第一个点之前的部分
 * 归组，一组要么整组删掉，要么整组保留。
 *
 * <h3>绝不删正在播放的文件</h3>
 * 两层保护：① {@link #touch(File)} 记下每次被解析出来的组，最近使用的若干组永不参与淘汰；
 * ② 真正动手前先把整组文件改名，Windows 上被打开的文件改名会失败，此时回滚改名并跳过这一组。
 * 播放中的文件因此不可能被删掉，最坏情况只是这一轮少回收一点空间。
 */
public final class MusicAudioCache {

    /** 播放流程里硬编码的缓存目录，保持一致。 */
    private static final File DIRECTORY = new File("MusicCache");

    /** 最近使用过的组数量，这些组永不淘汰。够覆盖「当前播放 + automix 预备 + 前后翻几首」。 */
    static final int PROTECTED_RECENT_GROUPS = 6;

    /** 未完成的下载/转码残片超过这个时长就是被中断留下的垃圾。 */
    static final long PARTIAL_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;

    /** 改名淘汰用的中间后缀。上一轮被打断留下的这类文件下一轮无条件清掉。 */
    private static final String EVICT_SUFFIX = ".evicting";

    private static final String[] PARTIAL_SUFFIXES = {".download", ".part", ".jaad-input"};

    private static final Object LOCK = new Object();

    /** 组名 -> 最近一次被播放流程解析出来的时刻。lastModified 只记写入，读命中不会更新它。 */
    private static final LinkedHashMap<String, Long> RECENT = new LinkedHashMap<String, Long>();

    private static final int RECENT_CAPACITY = 64;

    /** 同一时刻只允许一次清理，避免下载并发时反复扫目录。 */
    private static volatile boolean sweeping;

    private static final long USAGE_REFRESH_MILLIS = 3000L;

    private static volatile long measuredBytes;
    private static volatile long measuredAt;
    private static volatile boolean measuring;

    private MusicAudioCache() {
    }

    /** 一次清理的结果。 */
    public static final class Sweep {
        public final long freedBytes;
        public final long remainingBytes;
        public final int removedGroups;
        public final int skippedGroups;

        Sweep(long freedBytes, long remainingBytes, int removedGroups, int skippedGroups) {
            this.freedBytes = freedBytes;
            this.remainingBytes = remainingBytes;
            this.removedGroups = removedGroups;
            this.skippedGroups = skippedGroups;
        }
    }

    public static File directory() {
        return DIRECTORY;
    }

    /** 当前缓存目录占用。目录不存在时返回 0。 */
    public static long totalBytes() {
        return totalBytes(DIRECTORY);
    }

    static long totalBytes(File directory) {
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isFile()) total += file.length();
        }
        return total;
    }

    /**
     * 设置界面用的占用值。
     *
     * <p>界面是每帧重绘的，直接 {@code listFiles()} 会把几百次目录扫描摊到渲染线程上。
     * 这里只返回上一次的结果，过期时在后台线程刷新，所以显示最多滞后几秒，但永不掉帧。</p>
     */
    public static long cachedTotalBytes() {
        long now = System.currentTimeMillis();
        if (now - measuredAt > USAGE_REFRESH_MILLIS && !measuring) {
            measuring = true;
            measuredAt = now;
            // 同上：目录遍历放后台，设置界面显示的占用值晚一点刷新无所谓。
            BackgroundWork.submit(new Runnable() {
                public void run() {
                    try {
                        measuredBytes = totalBytes();
                        measuredAt = System.currentTimeMillis();
                    } catch (Throwable ignored) {
                    } finally {
                        measuring = false;
                    }
                }
            });
        }
        return measuredBytes;
    }

    /** 清理之后立刻把显示值改成已知的准确值，用户不用等下一次刷新。 */
    static void publishMeasuredBytes(long bytes) {
        measuredBytes = Math.max(0L, bytes);
        measuredAt = System.currentTimeMillis();
    }

    /**
     * 记下这个缓存文件所属的组刚刚被使用。命中已有缓存时也要调用：
     * 只看 lastModified 的话，一首反复听但很久以前下载的歌会被当成最冷的组删掉。
     */
    public static void touch(File cacheFile) {
        if (cacheFile == null) return;
        String group = groupOf(cacheFile.getName());
        if (group.isEmpty()) return;
        synchronized (LOCK) {
            RECENT.remove(group);
            RECENT.put(group, Long.valueOf(System.currentTimeMillis()));
            while (RECENT.size() > RECENT_CAPACITY) {
                RECENT.remove(RECENT.keySet().iterator().next());
            }
        }
    }

    /** 后台执行一次清理。播放流程调用它，绝不能占用下载或渲染线程。 */
    public static void enforceLimitAsync() {
        if (sweeping) return;
        // 低优先级池：清理要遍历整个缓存目录取文件大小与时间戳，是一串同步 IO。
        BackgroundWork.submit(new Runnable() {
            public void run() {
                enforceLimit();
            }
        });
    }

    /**
     * 按当前配置清理一次。
     *
     * <p>上限为 0（不限制）时仍然会清掉过期残片：那些文件任何情况下都没有价值。</p>
     */
    public static Sweep enforceLimit() {
        if (sweeping) return new Sweep(0L, totalBytes(), 0, 0);
        sweeping = true;
        try {
            Sweep result = sweep(DIRECTORY, configuredLimitBytes(), System.currentTimeMillis(),
                    snapshotRecent(), PROTECTED_RECENT_GROUPS, PARTIAL_MAX_AGE_MILLIS);
            publishMeasuredBytes(result.remainingBytes);
            if (result.freedBytes > 0L) {
                // 自动清理刻意不弹灵动岛：它总是跟在下载/转码通知后面，再插一条只会挤掉更重要的信息。
                // 留一行日志，事后能从 latest.log 里查到到底删了什么规模的缓存。
                System.out.println("[Cache] freed " + formatBytes(result.freedBytes)
                        + " from MusicCache, " + result.removedGroups + " track(s) removed, "
                        + formatBytes(result.remainingBytes) + " left"
                        + (result.skippedGroups > 0 ? " (" + result.skippedGroups + " in use)" : ""));
            }
            return result;
        } catch (Throwable ignored) {
            // 清理失败不该影响播放，下一次下载还会再试。
            return new Sweep(0L, totalBytes(), 0, 0);
        } finally {
            sweeping = false;
        }
    }

    private static long configuredLimitBytes() {
        try {
            return NCMPlayerConfig.getMusicCacheLimitBytes();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Map<String, Long> snapshotRecent() {
        synchronized (LOCK) {
            return new HashMap<String, Long>(RECENT);
        }
    }

    /**
     * 真正的清理。参数全部显式传入，方便脱离游戏环境验证。
     *
     * @param limitBytes 上限字节数，{@code <= 0} 表示不限制
     * @param recent     组名 -> 最近使用时刻
     */
    static Sweep sweep(File directory, long limitBytes, long now,
                       Map<String, Long> recent, int protectedGroups, long partialMaxAge) {
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return new Sweep(0L, 0L, 0, 0);

        long freed = 0L;
        Map<String, GroupState> groups = new LinkedHashMap<String, GroupState>();
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();

            // 上一轮改名到一半被打断留下的文件，任何情况下都是垃圾。
            if (name.endsWith(EVICT_SUFFIX)) {
                long size = file.length();
                if (file.delete()) freed += size;
                continue;
            }

            if (isPartial(name)) {
                long age = now - file.lastModified();
                if (age > partialMaxAge) {
                    long size = file.length();
                    if (file.delete()) {
                        freed += size;
                        continue;
                    }
                }
                // 还在下载/转码中的残片：算进占用，但它所在的组不参与淘汰。
                GroupState busy = group(groups, name);
                busy.bytes += file.length();
                busy.busy = true;
                busy.touch(file.lastModified());
                continue;
            }

            GroupState state = group(groups, name);
            state.files.add(file);
            state.bytes += file.length();
            state.touch(file.lastModified());
        }

        long total = 0L;
        for (GroupState state : groups.values()) total += state.bytes;
        if (limitBytes <= 0L || total <= limitBytes) {
            return new Sweep(freed, total, 0, 0);
        }

        List<GroupState> candidates = new ArrayList<GroupState>();
        for (Map.Entry<String, GroupState> entry : groups.entrySet()) {
            GroupState state = entry.getValue();
            Long touched = recent == null ? null : recent.get(entry.getKey());
            if (touched != null) state.touch(touched.longValue());
            if (state.busy || state.files.isEmpty()) continue;
            candidates.add(state);
        }

        // 最近使用的在后面。末尾的 protectedGroups 组直接不考虑。
        Collections.sort(candidates, new Comparator<GroupState>() {
            public int compare(GroupState left, GroupState right) {
                return left.newest < right.newest ? -1 : (left.newest > right.newest ? 1 : 0);
            }
        });
        int evictable = Math.max(0, candidates.size() - Math.max(0, protectedGroups));

        int removed = 0;
        int skipped = 0;
        for (int index = 0; index < evictable && total > limitBytes; index++) {
            GroupState state = candidates.get(index);
            long released = evictGroup(state);
            if (released <= 0L) {
                skipped++;
                continue;
            }
            freed += released;
            total -= released;
            removed++;
        }
        return new Sweep(freed, total, removed, skipped);
    }

    /**
     * 整组删除，全有或全无。
     *
     * <p>先把每个文件改名：Windows 上正在被解码器读的文件改名会失败，这就是「不要删正在播放的歌」
     * 最可靠的判据。任何一个失败就把已改名的改回去并放弃这一组，因此不会出现「源文件删了、
     * 解码边车还在」这种永远无法复用的孤儿。</p>
     *
     * @return 实际回收的字节数，放弃时为 0
     */
    private static long evictGroup(GroupState state) {
        List<File> renamed = new ArrayList<File>();
        List<File> originals = new ArrayList<File>();
        for (File file : state.files) {
            File staged = new File(file.getParentFile(), file.getName() + EVICT_SUFFIX);
            if (staged.exists() || !file.renameTo(staged)) {
                for (int index = 0; index < renamed.size(); index++) {
                    renamed.get(index).renameTo(originals.get(index));
                }
                return 0L;
            }
            renamed.add(staged);
            originals.add(file);
        }

        long released = 0L;
        for (File staged : renamed) {
            long size = staged.length();
            if (staged.delete()) released += size;
        }
        return released;
    }

    private static GroupState group(Map<String, GroupState> groups, String fileName) {
        String key = groupOf(fileName);
        GroupState state = groups.get(key);
        if (state == null) {
            state = new GroupState();
            groups.put(key, state);
        }
        return state;
    }

    /**
     * 缓存文件名归组。缓存名的格式是 {@code <source>_<id>_<quality>.<扩展名>}，
     * 而 stableKey 与音质标识都不含点，所以第一个点之前的部分就是组名，
     * {@code .decoded.wav} 这类边车会自然落到同一组。
     */
    static String groupOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.indexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    static boolean isPartial(String fileName) {
        for (String suffix : PARTIAL_SUFFIXES) {
            if (fileName.endsWith(suffix)) return true;
        }
        return false;
    }

    /** 供界面显示。以 1024 为进制，和资源管理器一致。 */
    public static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double megabytes = bytes / 1048576.0;
        if (megabytes < 1024.0) return String.format("%.0f MB", Double.valueOf(megabytes));
        return String.format("%.2f GB", Double.valueOf(megabytes / 1024.0));
    }

    private static final class GroupState {
        private final List<File> files = new ArrayList<File>();
        private long bytes;
        private long newest = Long.MIN_VALUE;
        private boolean busy;

        void touch(long millis) {
            if (millis > newest) newest = millis;
        }
    }
}
