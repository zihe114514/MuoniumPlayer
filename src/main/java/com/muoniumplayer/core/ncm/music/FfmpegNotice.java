package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「这台电脑上似乎没有 ffmpeg」全屏提示的状态与持久化。
 *
 * <p>这里只管三件事：启动时在后台探一次 ffmpeg、记住用户有没有选过「永不展示」、判断本次启动该不该弹。
 * 界面本身在 {@code com.muoniumplayer.core.screens.GuiFfmpegNotice}，两者刻意分开：状态要在主菜单
 * 出现之前就准备好，而界面依赖 Minecraft 的 GuiScreen，不该被状态逻辑拖进类加载。</p>
 *
 * <p>探测放在守护线程上：{@link FfmpegSupport} 的校验是 {@code ffmpeg -version}，会真的创建子进程，
 * 最坏情况下要为好几个候选路径各等一次超时。放在主线程或渲染线程上就是几秒的卡顿。</p>
 */
public final class FfmpegNotice {

    /** ffmpeg 官方下载页。刻意用官网而不是某个镜像：镜像会失效，官网上各平台的构建入口都在。 */
    public static final String DOWNLOAD_URL = "https://ffmpeg.org/download.html";

    /** 尚未探测 / 探测还没结束。 */
    public static final int STATE_UNKNOWN = 0;
    /** 找到了可用的 ffmpeg。 */
    public static final int STATE_PRESENT = 1;
    /** 探测完成，确认没有。 */
    public static final int STATE_MISSING = -1;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static volatile int probeState = STATE_UNKNOWN;
    private static volatile boolean probeStarted;
    /** 本次启动是否已经弹过。弹过就不再弹，避免每次回到主菜单都糊一次全屏。 */
    private static volatile boolean shownThisSession;

    private static boolean loaded;
    private static boolean neverShow;

    private FfmpegNotice() {
    }

    /**
     * 启动时调用一次。即使用户选了「永不展示」也照样探测：HUD 编辑器的「动态封面 · 未检测到 ffmpeg」
     * 标签读的是 {@link FfmpegSupport#cachedState()}（它绝不主动探测），不在启动时热一次的话，
     * 用户在放歌之前打开 HUD 编辑器只会看到一个没有结论的标签。
     */
    public static void beginProbe() {
        synchronized (LOCK) {
            if (probeStarted) return;
            probeStarted = true;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                int result;
                try {
                    result = FfmpegSupport.isAvailable() ? STATE_PRESENT : STATE_MISSING;
                } catch (Throwable ignored) {
                    // 探测本身失败（例如安全策略禁止创建进程）只当作「没有」，提示照旧可以关掉。
                    result = STATE_MISSING;
                }
                probeState = result;
                System.out.println("[MuoniumPlayer] ffmpeg startup probe: "
                        + (result == STATE_PRESENT ? "found" : "not found"));
            }
        }, "muonium-ffmpeg-startup-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /** 启动探测的结论：{@link #STATE_PRESENT} / {@link #STATE_MISSING} / {@link #STATE_UNKNOWN}。 */
    public static int probeState() {
        return probeState;
    }

    /** 用户是否已经选过「永不展示」。 */
    public static boolean isNeverShow() {
        load();
        synchronized (LOCK) {
            return neverShow;
        }
    }

    /** 记下「永不展示」并立刻落盘。写盘失败只会导致下次启动再问一次，不抛给调用方。 */
    public static void setNeverShow() {
        load();
        synchronized (LOCK) {
            neverShow = true;
            save();
        }
        shownThisSession = true;
    }

    /**
     * 现在该不该弹这个全屏提示。
     *
     * <p>四个条件同时成立才弹：探测已完成、确认没有 ffmpeg、用户没选过「永不展示」、本次启动还没弹过。
     * 探测没完成时返回 {@code false} 而不是等待——调用方在客户端 tick 里，下一 tick 再问一次即可。</p>
     */
    public static boolean shouldShow() {
        if (shownThisSession) return false;
        if (probeState != STATE_MISSING) return false;
        return !isNeverShow();
    }

    /** 标记本次启动已经弹过。由界面在真正显示出来的那一刻调用。 */
    public static void markShownThisSession() {
        shownThisSession = true;
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            neverShow = readNeverShow();
        }
    }

    private static boolean readNeverShow() {
        try {
            File file = ConfigPaths.FFMPEG_NOTICE;
            if (!file.isFile()) return false;
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has("neverShow") || root.get("neverShow").isJsonNull()) return false;
            return root.get("neverShow").getAsBoolean();
        } catch (Throwable ignored) {
            // 读坏了就当作「还没选过」：再问一次比永久静默好，用户随时能再点一次「永不展示」。
            return false;
        }
    }

    private static void save() {
        try {
            File parent = ConfigPaths.FFMPEG_NOTICE.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("neverShow", neverShow);
            JsonConfigStorage.writeObject(ConfigPaths.FFMPEG_NOTICE, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
