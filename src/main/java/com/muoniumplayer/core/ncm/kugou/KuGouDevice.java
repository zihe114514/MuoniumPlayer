package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * 酷狗的设备标识与登录凭证的持久化。
 *
 * <p>设备 GUID 与登录 token 放在同一个文件里，因为它们必须一起保持稳定：token 是绑在注册设备上的，
 * 换了 GUID 相当于换了一台机器，已登录的 token 会被上游按风控拒掉。</p>
 */
public final class KuGouDevice {

    private static final Object LOCK = new Object();
    private static volatile boolean loaded;
    private static volatile String guid = "";
    private static volatile String mid = "";
    private static volatile String token = "";
    private static volatile String userId = "";
    private static volatile String nickname = "";
    private static volatile String avatarUrl = "";

    private KuGouDevice() {
    }

    public static String mid() {
        ensureLoaded();
        return mid;
    }

    public static String token() {
        ensureLoaded();
        return token;
    }

    public static String userId() {
        ensureLoaded();
        return userId;
    }

    public static String nickname() {
        ensureLoaded();
        return nickname;
    }

    public static String avatarUrl() {
        ensureLoaded();
        return avatarUrl;
    }

    public static boolean isLoggedIn() {
        ensureLoaded();
        return !KuGouSign.isBlank(token) && !KuGouSign.isBlank(userId) && !"0".equals(userId.trim());
    }

    /** 扫码成功后写入。{@code nickname}/{@code avatarUrl} 可以为空，随后由用户资料接口补齐。 */
    public static void saveLogin(String newToken, String newUserId, String newNickname, String newAvatarUrl) {
        ensureLoaded();
        synchronized (LOCK) {
            token = KuGouSign.nonNull(newToken).trim();
            userId = KuGouSign.nonNull(newUserId).trim();
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            persist();
        }
    }

    public static void saveProfile(String newNickname, String newAvatarUrl) {
        ensureLoaded();
        synchronized (LOCK) {
            if (!KuGouSign.isBlank(newNickname)) nickname = newNickname.trim();
            if (!KuGouSign.isBlank(newAvatarUrl)) avatarUrl = newAvatarUrl.trim();
            persist();
        }
    }

    /** 退出登录只清凭证，<b>保留</b> GUID：设备身份继续沿用，下次登录不会被当成新设备。 */
    public static void logout() {
        ensureLoaded();
        synchronized (LOCK) {
            token = "";
            userId = "";
            nickname = "";
            avatarUrl = "";
            persist();
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                File file = ConfigPaths.KUGOU_AUTH;
                if (file.isFile()) {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    JsonObject root = new JsonParser().parse(text).getAsJsonObject();
                    guid = readString(root, "guid");
                    token = readString(root, "token");
                    userId = readString(root, "userid");
                    nickname = readString(root, "nickname");
                    avatarUrl = readString(root, "avatar");
                }
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] 凭证读取失败，按未登录处理: " + throwable.getMessage());
            }
            if (KuGouSign.isBlank(guid)) {
                guid = UUID.randomUUID().toString();
            }
            mid = KuGouSign.mid(guid);
            loaded = true;
            persist();
        }
    }

    private static void persist() {
        try {
            File file = ConfigPaths.KUGOU_AUTH;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("guid", guid);
            root.addProperty("token", token);
            root.addProperty("userid", userId);
            root.addProperty("nickname", nickname);
            root.addProperty("avatar", avatarUrl);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 凭证写入失败: " + throwable.getMessage());
        }
    }

    private static String readString(JsonObject root, String name) {
        try {
            return root != null && root.has(name) && !root.get(name).isJsonNull()
                    ? root.get(name).getAsString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}