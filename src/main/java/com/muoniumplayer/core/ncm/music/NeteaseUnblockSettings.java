package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 网易云「灰色歌曲」解灰的开关。
 *
 * <p>只有一项：官方链路彻底取不到流之后，是否再去第三方源按曲名 / 艺人重新匹配一条可播链接。
 * 默认开启 —— 关掉就完全回到原来的行为（解析失败 → 灵动岛报「没有可用音频源」→ 停下等用户操作）。</p>
 *
 * <p>刻意<b>不</b>提供「主动检测整张歌单是否可用」的开关：那需要给每一首都发一次网易云请求，
 * 会瞬间打爆上游与 GD 音乐台的额度。可用性只在真正点播失败的那一首上问一次。</p>
 */
public final class NeteaseUnblockSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_ENABLED = "unblockGreyTracks";

    private static volatile boolean loaded;
    private static boolean enabled = true;

    private NeteaseUnblockSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            enabled = loadFlagLocked();
        }
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    private static boolean loadFlagLocked() {
        try {
            File file = ConfigPaths.NETEASE_UNBLOCK;
            if (!file.isFile()) return true;
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has(KEY_ENABLED) || root.get(KEY_ENABLED).isJsonNull()) return true;
            return root.get(KEY_ENABLED).getAsBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.NETEASE_UNBLOCK.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty(KEY_ENABLED, enabled);
            JsonConfigStorage.writeObject(ConfigPaths.NETEASE_UNBLOCK, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}