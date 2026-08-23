package com.muoniumplayer.core.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Central registry for MuoniumPlayer persisted paths.
 *
 * <p>All newly written files use the MuoniumPlayer names. On first startup after
 * the rename, known legacy Deuterium/Tritium files are moved to their new names
 * when the new target does not already exist, preserving existing user settings.</p>
 */
public final class ConfigPaths {

    public static final File HUD = new File("muoniumplayer_hud.json");
    public static final File PLAYER = new File("muoniumplayer_config.json");
    public static final File THEME = new File("muoniumplayer_theme.json");
    public static final Path MUSIC_AUTH = Paths.get("config", "muonium", "music_auth.json");
    public static final File NETEASE_ACCOUNTS = new File("config", "muonium/netease_accounts.json");

    /** User-selected GD Studio aggregated platform (netease/joox/...), persisted separately. */
    public static final File GD_SOURCE = new File("config", "muonium/gd_source.json");

    /** Automix (seamless track handover) preferences. */
    public static final File AUTOMIX = new File("config", "muonium/automix.json");

    /**
     * 「没装 ffmpeg」全屏提示的「永不展示」选择。
     *
     * <p>刻意单独一个文件而不是塞进 {@link #HUD}：这个提示要在<b>主菜单</b>就决定要不要弹，
     * 那时 HUD 配置的读写路径还牵着一整套 HUD 组件；单独一个只有一个布尔值的小文件，读失败也
     * 只是「再提示一次」，不会影响任何别的设置。</p>
     */
    public static final File FFMPEG_NOTICE = new File("config", "muonium/ffmpeg_notice.json");

    static {
        migrateLegacyFiles();
    }

    private ConfigPaths() {
    }

    /**
     * Migrates only when the new file is absent. Existing new files always win,
     * so a user can safely keep backups of the old configuration beside them.
     */
    private static void migrateLegacyFiles() {
        migrate(new File("deuteriummusic_hud.json"), HUD);
        migrate(new File("tritium_player_config.json"), PLAYER);
        migrate(new File("tritium_player_theme.json"), THEME);
        // Also migrate the intermediate Muonium names created by development builds.
        migrate(new File("muonium_hud.json"), HUD);
        migrate(new File("muonium_player_config.json"), PLAYER);
        migrate(new File("muonium_player_theme.json"), THEME);

        migrate(Paths.get("config", "tritium", "music_auth.json").toFile(), MUSIC_AUTH.toFile());
        migrate(Paths.get("config", "tritium", "netease_accounts.json").toFile(), NETEASE_ACCOUNTS);
    }

    private static void migrate(File legacy, File target) {
        if (legacy == null || target == null || !legacy.isFile() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A read-only/locked legacy file should not prevent the mod from loading.
            }
        }
    }
}
