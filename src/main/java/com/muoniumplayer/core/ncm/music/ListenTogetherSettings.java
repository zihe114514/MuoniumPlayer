package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 一起听的行为开关。
 *
 * <p>目前只有一项：房间里有非会员时，是否在起播前跳过「VIP 专享」曲目。默认开启 ——
 * 官方客户端在这种情况下会让整房一起切歌，不跳过就会出现"房主接着放、房员 30 秒后已经换歌"
 * 的脱队。关掉它就回到"各放各的"。</p>
 */
public final class ListenTogetherSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_SKIP_VIP = "skipVipWhenRoomHasNonVip";

    private static volatile boolean loaded;
    private static boolean skipVipWhenRoomHasNonVip = true;

    private ListenTogetherSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            skipVipWhenRoomHasNonVip = loadFlagLocked();
        }
    }

    public static boolean isSkipVipWhenRoomHasNonVip() {
        load();
        synchronized (LOCK) {
            return skipVipWhenRoomHasNonVip;
        }
    }

    public static void setSkipVipWhenRoomHasNonVip(boolean value) {
        load();
        synchronized (LOCK) {
            if (skipVipWhenRoomHasNonVip == value) return;
            skipVipWhenRoomHasNonVip = value;
            saveLocked();
        }
    }

    private static boolean loadFlagLocked() {
        try {
            File file = ConfigPaths.LISTEN_TOGETHER;
            if (!file.isFile()) return true;
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has(KEY_SKIP_VIP) || root.get(KEY_SKIP_VIP).isJsonNull()) return true;
            return root.get(KEY_SKIP_VIP).getAsBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.LISTEN_TOGETHER.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty(KEY_SKIP_VIP, skipVipWhenRoomHasNonVip);
            JsonConfigStorage.writeObject(ConfigPaths.LISTEN_TOGETHER, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}