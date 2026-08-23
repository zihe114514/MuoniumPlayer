package com.muoniumplayer.core;

import lombok.Getter;
import today.opai.api.OpenAPI;
import com.muoniumplayer.core.management.AbstractManager;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.module.impl.OpenNCMScreen;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.reflection.Reflection;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.OpenGlHelper;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.widget.impl.MusicInfoWidget;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.util.Arrays;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2026/3/31 22:28
 */
public class MuoniumPlayerExtension {

    public static final String NAME = "MuoniumPlayer";
    public static final String AUTHOR = "IzumiiKonata";
    public static final String VERSION = "1.3.0";

    @Getter
    private static final MuoniumPlayerExtension instance = new MuoniumPlayerExtension();

    @Getter
    private FontManager fontManager;

    /** The music-player feature registered with the legacy OpenAPI bridge. */
    public OpenNCMScreen musicPlayerModule = new OpenNCMScreen();
    public MusicInfoWidget musicInfo = new MusicInfoWidget();
    public MusicLyricsWidget musicLyrics = new MusicLyricsWidget();

    public MuoniumPlayerExtension() {

    }

    public void init(OpenAPI api) {
        api.registerEvent(MuoniumPlayerEventHandler.getInstance());

        // Restore all player preferences before the asynchronous account bootstrap
        // can create an AudioPlayer. This includes the mod-owned persisted volume.
        HudConfig.load();
        this.musicInfo.volume.setValue((double) HudConfig.playerVolume);
        this.musicInfo.animatedCover.setValue(HudConfig.animatedCoverEnabled);
        NCMPlayerConfig.load();
        GdStudioSourceSettings.load();
        CloudMusic.quality = NCMPlayerConfig.getAudioQuality();
        MultiThreadingUtil.runAsync(CloudMusic::initNCM);
        Reflection.init(api);
//        Framebuffer.updateMcFramebuffer();

        this.fontManager = new FontManager();

        List<AbstractManager> managers = Arrays.asList(this.fontManager);

        for (AbstractManager manager : managers) {
//            logger.debug("calling init() on {}...", manager.getName());
            manager.init();
        }

        api.registerFeature(this.musicPlayerModule);
        api.registerFeature(this.musicInfo);
        api.registerFeature(this.musicInfo.widget);
        api.registerFeature(this.musicLyrics);
        api.registerFeature(this.musicLyrics.widget);

        // 歌曲信息 HUD / 歌词 HUD：默认开启，位置与缩放由 HudConfig 归一化字段决定。
        // 原项目这两个模块默认关闭、靠 Opai ClickGUI 手动开启并拖动定位；独立 Mod 无 ClickGUI，
        // 故默认开启 + 加载 HudConfig 持久化位置，并提供快捷键开关（TOGGLE_MUSIC_INFO / TOGGLE_LYRICS）
        // 与 HUD 编辑器（EDIT_HUD，参考 cloudmusic/mod/gui/GuiOverlayEditor）。
        this.musicInfo.setEnabled(true);
        this.musicLyrics.setEnabled(true);
        NCMTheme.load();
        this.musicLyrics.loadHudEditorSettings();
    }

    public void unload() {

    }

    public static boolean isCallingFromMainThread() {
        return Thread.currentThread().getName().equals("Client thread");
    }

}
