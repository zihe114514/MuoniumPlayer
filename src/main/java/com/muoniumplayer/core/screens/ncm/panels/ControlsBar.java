package com.muoniumplayer.core.screens.ncm.panels;

import org.lwjgl.input.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.NeteaseListenTogetherService;
import com.muoniumplayer.core.ncm.music.PersonalFmManager;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.MusicLyricsPanel;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.screens.ncm.VolumeControl;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;


/**
 * @author IzumiiKonata
 * Date: 2025/10/17 21:24
 */
public class ControlsBar extends NCMPanel {

    private boolean qualityMenuOpen;
    /**
     * 展开/收起的动画进度，0 为完全收起、1 为完全展开。由音质按钮的每帧回调推进：按钮常驻可见，
     * 而弹出层收起后会被隐藏、回调不再执行，进度放在弹出层里就没有帧可以播完收起动画。
     */
    private double qualityMenuAnimation;
    private RoundedRectWidget qualityMenuBackground;
    private RoundedRectWidget[] qualityOptions;

    /** 弹出层在动画彻底结束前必须继续渲染，否则收起会变成瞬间消失。 */
    private boolean isQualityMenuVisible() {
        return qualityMenuOpen || qualityMenuAnimation > .004;
    }

    /** 平滑线性进度，让展开的起步与收尾都不生硬。 */
    private static double smoothProgress(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    public boolean consumeQualityMenuClick(double mouseX, double mouseY, int mouseButton) {
        if (!qualityMenuOpen || qualityMenuBackground == null) return false;
        boolean withinMenu = mouseX >= qualityMenuBackground.getX() && mouseX <= qualityMenuBackground.getX() + qualityMenuBackground.getWidth()
                && mouseY >= qualityMenuBackground.getY() && mouseY <= qualityMenuBackground.getY() + qualityMenuBackground.getHeight();
        if (!withinMenu) return false;
        this.onMouseClickReceived(mouseX, mouseY, mouseButton);
        return true;
    }

    public ControlsBar() {
    }

    @Override
    public void onInit() {
        RectWidget bg = new RectWidget();

        this.addChild(bg);

        bg.setBeforeRenderCallback(() -> {
            bg.setMargin(0);
            bg.setColor(NCMScreen.getColor(NCMScreen.ColorType.NAVIGATION_BACKGROUND));
            bg.setAlpha(.95f - NCMTheme.getLiquidGlassAmount() * .22f);
        });

        RoundedImageWidget playingCover = new RoundedImageWidget(() -> {
            if (CloudMusic.currentlyPlaying == null)
                return null;

            // 正在播放的曲目有动态封面时,播放条上的这张也跟着动;没有则仍是静态小封面。
            return CloudMusic.preferredCoverLocation(CloudMusic.currentlyPlaying,
                    CloudMusic.currentlyPlaying.getSmallCoverLocation());
        }, 0 , 0, 0, 0);

        this.addChild(playingCover);

        playingCover
                .fadeIn()
                .setLinearFilter(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> playingCover
                        .setMargin(5)
                        .setBounds(playingCover.getHeight(), playingCover.getHeight())
                        .setRadius(2))
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (CloudMusic.currentlyPlaying != null) {
                        NCMScreen.getInstance().musicLyricsPanel = new MusicLyricsPanel(CloudMusic.currentlyPlaying);
                    }

                    return true;
                });
        double buttonsYOffset = -4;

        IconWidget playPause = new IconWidget("B", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(playPause);

        playPause
                .setBeforeRenderCallback(() -> {
                    boolean showPausingIcon = CloudMusic.player == null || CloudMusic.player.isPausing();

                    playPause
                            .center()
                            .setIcon(showPausingIcon ? "B" : "A")
                            .setPosition(playPause.getRelativeX(), playPause.getRelativeY() + buttonsYOffset)
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                })
                .setOnClickCallback((x, y, i) -> {
                    boolean hasCurrentlyPlaying = CloudMusic.player != null && CloudMusic.currentlyPlaying != null;
                    if (hasCurrentlyPlaying) {
                        if (CloudMusic.player.isPausing())
                            CloudMusic.player.unpause();
                        else
                            CloudMusic.player.pause();
                    }
                    return true;
                });

        IconWidget prev = new IconWidget("H", FontManager.icon30, 0, 0, 20, 20);

        this.addChild(prev);

        prev
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null)
                        CloudMusic.prev();

                    return true;
                })
                .setBeforeRenderCallback(() -> prev
                        .center()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setPosition(prev.getRelativeX() - 20 - prev.getWidth() * .5, prev.getRelativeY() + buttonsYOffset));
        IconWidget next = new IconWidget("E", FontManager.icon30, 0, 0, 20, 20);
        this.addChild(next);

        next
                .setOnClickCallback((x, y, i) -> {
                    if (CloudMusic.player != null && CloudMusic.currentlyPlaying != null)
                        CloudMusic.next();

                    return true;
                })
                .setBeforeRenderCallback(() -> next
                        .center()
                        .setPosition(next.getRelativeX() + next.getWidth() * .5 + 20, next.getRelativeY() + buttonsYOffset)
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT)));
        // 播放模式与核心控件并列：顺序播放 → 随机播放 → 单曲循环。
        ThemedTextureIconWidget playMode = new ThemedTextureIconWidget(
                () -> CloudMusic.isPersonalFmActive() ? null : PlayerIconAssets.forPlayMode(CloudMusic.playMode),
                () -> CloudMusic.isPersonalFmActive() ? "F" : CloudMusic.playMode.getIcon(),
                FontManager.icon30, 0, 0, 20, 20);
        this.addChild(playMode);

        playMode
                .setBeforeRenderCallback(() -> {
                    CloudMusic.PlayMode mode = CloudMusic.playMode;
                    boolean personalFm = CloudMusic.isPersonalFmActive();
                    playMode
                            .center()
                            .setPosition(next.getRelativeX() + next.getWidth() + 10, next.getRelativeY())
                            .setClickable(!personalFm)
                            .setAlpha(ControlsBar.this.getAlpha() * (personalFm ? .35f : 1.0f))
                            // The supplied mode icons share one visual language; use one
                            // consistent theme tint for sequential, random and single-loop.
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                })
                .setOnClickCallback((x, y, mouseButton) -> {
                    if (mouseButton == 0) {
                        if (!CloudMusic.isPersonalFmActive()) CloudMusic.cyclePlayMode();
                    } else if (mouseButton == 1) {
                        // Right click toggles the seamless handover, next to the mode it applies to.
                        boolean enabled = !AutomixSettings.isEnabled();
                        AutomixSettings.setEnabled(enabled);
                        DownloadDynamicIsland.showAutomixToggle(enabled, enabled
                                ? String.format(java.util.Locale.ROOT, "重叠 %.1fs · 下一首将提前解码",
                                        AutomixSettings.getOverlapSeconds())
                                : "切歌恢复为原有的直接切换");
                    }
                    return true;
                });

        // Explicit personal-FM action. It is hidden entirely outside the FM session,
        // so normal playlists never expose nor invoke the radio-trash endpoint.
        RoundedButtonWidget fmSkip = new RoundedButtonWidget("换一首", FontManager.pf12bold);
        this.addChild(fmSkip);
        fmSkip.setShouldOverrideMouseCursor(true);
        // 显示与否必须由播放条来判定，不能写在按钮自己的回调里：AbstractWidget#renderWidget
        // 在跑 beforeRenderCallback 之前就会因为 isHidden() 直接返回（父组件的渲染循环也会跳过
        // 隐藏的子组件），而唯一能把它重新显示出来的代码就在那个回调里。播放条是常驻的，首帧
        // 通常还没进入私人 FM，于是这个按钮会永久卡在隐藏状态，进了私人 FM 也不会出现。
        this.setBeforeRenderCallback(() -> fmSkip.setHidden(!CloudMusic.isPersonalFmActive()));
        fmSkip.setBeforeRenderCallback(() -> {
            boolean active = CloudMusic.isPersonalFmActive();
            fmSkip.setClickable(active && !PersonalFmManager.isLoading());
            fmSkip.setBounds(44, 17);
            fmSkip.setPosition(playMode.getRelativeX() + playMode.getWidth() + 10, playMode.getRelativeY() + 1);
            fmSkip.setRadius(4);
            fmSkip.setAlpha(ControlsBar.this.getAlpha() * (PersonalFmManager.isLoading() ? .50f : 1.0f));
            fmSkip.setColor(fmSkip.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
            fmSkip.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        fmSkip.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0 || !CloudMusic.isPersonalFmActive()) return mouseButton == 0;
            PersonalFmManager.skipCurrentAndRequestNext();
            return true;
        });



        RoundedRectWidget progressBarBg = new RoundedRectWidget() {

            boolean prevMouse = false;

            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);

                if (prevMouse && !Mouse.isButtonDown(0))
                    prevMouse = false;

                if (this.testHovered(mouseX, mouseY, 1) && Mouse.isButtonDown(0) && !prevMouse) {
                    prevMouse = true;
                    double xDelta = Math.max(0, Math.min(this.getWidth(), (mouseX - this.getX())));
                    double percent = xDelta / this.getWidth();

                    if (CloudMusic.player != null) {
                        float total = CloudMusic.player.getTotalTimeMillis();
                        if (total > 0.0f) {
                            float progress = (float) (percent * total);
                            CloudMusic.player.setPlaybackTime(progress);
                            // Seek 后立即读取真实 positionMs，用实际音频时钟同步歌词/进度
                            float actual = CloudMusic.player.getCurrentTimeMillis();
                            MusicLyricsWidget.resetProgress(actual);
                            MusicLyricsPanel.resetProgress(actual);
                        }
                    }
                }
            }
        };

        this.addChild(progressBarBg);

        progressBarBg
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                .setRadius(1)
                .setBounds(135, 3)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> progressBarBg
                        .center()
                        .setPosition(progressBarBg.getRelativeX(), progressBarBg.getRelativeY() + 8));

        RoundedRectWidget progressBar = new RoundedRectWidget();

        progressBarBg.addChild(progressBar);
        progressBar
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setWidth(0)
                .setClickable(false)
                .setBeforeRenderCallback(() -> {
                    progressBar.setMargin(0);

                    AudioPlayer player = CloudMusic.player;
                    if (player == null)
                        return;

                    float total = player.getTotalTimeMillis();
                    if (total <= 0.0f) {
                        progressBar.setWidth(0).setRadius(0);
                        return;
                    }

                    float perc = Math.max(0.0f,
                            Math.min(1.0f, player.getCurrentTimeMillis() / total));
                    progressBar
                            .setWidth(perc * progressBarBg.getWidth())
                            .setRadius(perc);
                });

        LabelWidget lblCurTime = new LabelWidget(
                () -> {
                    if (CloudMusic.player == null)
                        return "00:00";
                    return formatDuration(CloudMusic.player.getCurrentTimeMillis());
                },
                FontManager.pf12
        );
        this.addChild(lblCurTime);

        lblCurTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblCurTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(
                                progressBarBg.getRelativeX() - lblCurTime.getWidth() - 4,
                                progressBarBg.getRelativeY() + progressBarBg.getHeight() * .5 - lblCurTime.getHeight() * .5
                        ));

        LabelWidget lblRemainingTime = new LabelWidget(
                () -> {
                    if (CloudMusic.player == null)
                        return "00:00";
                    float total = CloudMusic.player.getTotalTimeMillis();
                    if (total <= 0.0f)
                        return "00:00";
                    return "-" + formatDuration(Math.max(0.0f,
                            total - CloudMusic.player.getCurrentTimeMillis()));
                },
                FontManager.pf12
        );
        this.addChild(lblRemainingTime);

        lblRemainingTime
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblRemainingTime
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setPosition(progressBarBg.getRelativeX() + progressBarBg.getWidth() + 4, lblCurTime.getRelativeY()));

        LabelWidget lblMusicName = new LabelWidget(() -> CloudMusic.currentlyPlaying == null ? "未在播放" : CloudMusic.currentlyPlaying.getName(), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicName
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                        .setMaxWidth(lblCurTime.getRelativeX() - lblMusicName.getRelativeX() - 4)
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2
                        ));

        LabelWidget lblMusicArtist = new LabelWidget(
                () -> {
                    if (CloudMusic.currentlyPlaying == null)
                        return "无";
                    return CloudMusic.currentlyPlaying.getArtistsName() + " - " + CloudMusic.currentlyPlaying.getAlbum().getName();
                },
                FontManager.pf14bold
        );
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setClickable(false)
                .setBeforeRenderCallback(() -> lblMusicArtist
                        .centerVertically()
                        .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT))
                        .setMaxWidth(lblCurTime.getRelativeX() - lblMusicArtist.getRelativeX() - 4)
                        .setPosition(
                                playingCover.getRelativeX() + playingCover.getWidth() + 4,
                                lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2
                        ));

        // Right-aligned compact volume strip. It delegates all visual and input behavior to the
        // full-screen control implementation, so both surfaces remain visually synchronized.
        final VolumeControl compactVolumeControl = new VolumeControl();
        RoundedRectWidget compactVolumeWidget = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                compactVolumeControl.render(mouseX, mouseY, this.getX(),
                        this.getY() + this.getHeight() * .5, this.getWidth(), this.getAlpha());
            }
        };
        this.addChild(compactVolumeWidget);
        compactVolumeWidget
                .setClickable(false)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    double controlWidth = Math.max(74.0,
                            Math.min(122.0, compactVolumeWidget.getParentWidth() * .18));
                    compactVolumeWidget
                            .setBounds(controlWidth, 20.0)
                            .setPosition(compactVolumeWidget.getParentWidth() - controlWidth - 8.0,
                                    Math.max(1.0, (compactVolumeWidget.getParentHeight() - 20.0) * .5))
                            .setAlpha(ControlsBar.this.getAlpha());
                });

        // Compact quality selector.  The available provider tiers are exposed in an upward
        // pop-up so a user can select a known target instead of repeatedly cycling the button.
        // Keeping all controls as direct children of ControlsBar also makes the pop-up render
        // above the compact volume control and prevents it from being clipped by the selector.
        final Quality[] selectableQualities = Quality.values();
        final double qualityButtonHeight = 20.0;
        final double qualityOptionHeight = 18.0;
        final double qualityMenuPadding = 3.0;


        qualityOptions = new RoundedRectWidget[selectableQualities.length];
        RoundedRectWidget qualitySelector = new RoundedRectWidget();
        this.addChild(qualitySelector);
        qualitySelector
                .setClickable(true)
                .setShouldOverrideMouseCursor(true)
                .setBeforeRenderCallback(() -> {
                    // Dedicated footer slot: immediately left of the volume control, with a
                    // fixed visual gap matching the player layout.  Do not let a transient
                    // layout value hide this primary action.
                    // 常驻回调：在这里推进展开动画，收起过程才有帧可用。
                    qualityMenuAnimation = Interpolations.interpolate(qualityMenuAnimation,
                            qualityMenuOpen ? 1.0 : 0.0, .3);
                    if (qualityMenuOpen && qualityMenuAnimation > .996) qualityMenuAnimation = 1.0;
                    if (!qualityMenuOpen && qualityMenuAnimation < .004) qualityMenuAnimation = 0.0;

                    // 隐藏状态只在这里统一维护：被隐藏的组件不再执行自己的回调，没法自己复活。
                    boolean menuVisible = isQualityMenuVisible();
                    if (qualityMenuBackground != null) qualityMenuBackground.setHidden(!menuVisible);
                    if (qualityOptions != null) {
                        for (RoundedRectWidget menuOption : qualityOptions) {
                            if (menuOption != null) menuOption.setHidden(!menuVisible);
                        }
                    }

                    double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                    double buttonHeight = qualityButtonHeight * scale;
                    double parentWidth = qualitySelector.getParentWidth();
                    double volumeWidth = Math.max(74.0, Math.min(122.0, parentWidth * .18));
                    double volumeX = compactVolumeWidget.getRelativeX();
                    if (volumeX <= 0.0 || volumeX >= parentWidth) {
                        volumeX = parentWidth - volumeWidth - 8.0;
                    }
                    double selectorGap = Math.min(38.0, Math.max(14.0, parentWidth * .045));
                    String selectorLabel = "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen ? " ︿" : " ﹀");
                    double desiredWidth = (scale < .9 ? FontManager.pf10bold : FontManager.pf12bold).getStringWidthD(selectorLabel) + 14.0 * scale;
                    double selectorWidth = Math.max(56.0 * scale, Math.min(112.0 * scale, desiredWidth));
                    double selectorX = volumeX - selectorGap - selectorWidth;

                    // On a very narrow player, shrink only enough to remain inside the footer;
                    // the label itself trims safely instead of the whole quality action vanishing.
                    if (selectorX < 6.0) {
                        selectorWidth = Math.max(34.0 * scale, volumeX - selectorGap - 6.0);
                        selectorX = Math.max(6.0, volumeX - selectorGap - selectorWidth);
                    }

                    qualitySelector
                            .setHidden(false)
                            .setClickable(true)
                            .setBounds(selectorWidth, buttonHeight)
                            .setRadius(5.0 * scale)
                            .setColor(qualitySelector.isHovering()
                                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha(ControlsBar.this.getAlpha() * .94f)
                            .setPosition(selectorX,
                                    Math.max(1.0, (qualitySelector.getParentHeight() - buttonHeight) * .5));
                })
                .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                    if (mouseButton == 0) {
                        // 只翻转状态：是否渲染交给动画进度判断，收起时弹出层要留到动画播完。
                        qualityMenuOpen = !qualityMenuOpen;
                    }
                    return true;
                });

        LabelWidget qualityText = new LabelWidget(() -> "音质 · " + formatQuality(CloudMusic.quality) + (qualityMenuOpen ? " ︿" : " ﹀"), FontManager.pf12bold);
        qualitySelector.addChild(qualityText);
        qualityText.setClickable(false);
        qualityText.setBeforeRenderCallback(() -> qualityText
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                .setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH)
                .setMaxWidth(Math.max(0.0, qualitySelector.getWidth() - 14.0 * Math.max(.82, NCMPlayerConfig.getPlayerScale())))
                .setPosition(7.0, Math.max(2.0, (qualitySelector.getHeight() - qualityText.getHeight()) * .5)));

        // 下一首播放列表：队列抽屉的开关排在音质按钮左边，沿用右下角次级控件的同一条锚点链。
        // 用「播放三角 + 列表」（player-action-icons U+E144）而不是歌曲行那枚「下一首播放」
        // （U+E309）：这里打开的是整张待播列表，和单曲的"插到下一首"不是同一个动作。
        IconWidget queueToggle = PlayerQueueIcons.newPlayQueueButton(18);
        this.addChild(queueToggle);
        queueToggle.setShouldOverrideMouseCursor(true);
        queueToggle.setBeforeRenderCallback(() -> {
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            boolean opened = queuePanel != null && queuePanel.isOpen();
            double toggleX = qualitySelector.getRelativeX() - 8.0 - queueToggle.getWidth();
            // 面板很窄时右下角排不下了：用 alpha 让它隐形并停止响应，而不是 setHidden——
            // 被隐藏的组件不会再执行自己的回调，也就再没有机会把自己显示回来。
            boolean fits = toggleX > lblRemainingTime.getRelativeX() + lblRemainingTime.getWidth() + 6.0;
            queueToggle
                    .setClickable(fits)
                    .setAlpha(fits ? ControlsBar.this.getAlpha() : 0f)
                    .centerVertically();
            queueToggle
                    .setPosition(toggleX, queueToggle.getRelativeY())
                    .setColor(opened || CloudMusic.getQueuedNextCount() > 0
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        queueToggle.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            PlayQueuePanel queuePanel = NCMScreen.getInstance().getPlayQueuePanel();
            if (queuePanel != null) queuePanel.toggle();
            return true;
        });

        // 待播数量贴在图标右上角。作为图标的子组件，它自动继承那一份 alpha，
        // 因此窄面板下不需要第二处可见性判断。
        LabelWidget queueCount = new LabelWidget(() -> {
            int queued = CloudMusic.getQueuedNextCount();
            return queued > 0 ? String.valueOf(Math.min(99, queued)) : "";
        }, FontManager.pf10bold);
        queueToggle.addChild(queueCount);
        queueCount.setClickable(false);
        queueCount.setBeforeRenderCallback(() -> queueCount
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setPosition(queueToggle.getWidth() - 3.0, -1.0));

        // 一起听入口排在待播列表按钮左边，沿用同一条锚点链。
        // 只在网易云官方源下可用（房间与邀请都依赖网易云登录态），其余来源下用 alpha 隐形并停止
        // 响应，而不是 setHidden —— 被隐藏的组件不再执行自己的回调，也就没机会把自己显示回来。
        IconWidget listenTogether = new IconWidget(FontelloIcons.ACCOUNT_ADD, FontManager.fontello16, 0, 0, 18, 18);
        this.addChild(listenTogether);
        listenTogether.setShouldOverrideMouseCursor(true);
        listenTogether.setBeforeRenderCallback(() -> {
            boolean netease = CadenceMusicService.getCurrentPlatform() == MusicPlatform.NETEASE;
            double toggleX = queueToggle.getRelativeX() - 8.0 - listenTogether.getWidth();
            boolean fits = toggleX > lblRemainingTime.getRelativeX() + lblRemainingTime.getWidth() + 6.0;
            boolean usable = netease && fits;
            listenTogether
                    .setClickable(usable)
                    .setAlpha(usable ? ControlsBar.this.getAlpha() : 0f)
                    .centerVertically();
            listenTogether
                    .setPosition(toggleX, listenTogether.getRelativeY())
                    .setColor(NeteaseListenTogetherService.isActive()
                            ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                            : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        });
        listenTogether.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().openListenTogether();
            return true;
        });

        // The menu is declared after the selector, therefore it is rendered and hit-tested on
        // top of the bottom bar.  It grows upward so it never covers the compact controls.
        qualityMenuBackground = new RoundedRectWidget();
        this.addChild(qualityMenuBackground);
        qualityMenuBackground
                .setClickable(false)
                .setHidden(true)
                .setBeforeRenderCallback(() -> {
                    double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                    double menuHeight = selectableQualities.length * qualityOptionHeight * scale + qualityMenuPadding * 2.0 * scale;
                    // 底边固定贴在按钮上方，只有上边沿随进度向上展开，看起来是从按钮里长出来的。
                    double progress = smoothProgress(qualityMenuAnimation);
                    double bottom = qualitySelector.getRelativeY() - 4.0;
                    double visibleHeight = Math.max(.01, menuHeight * progress);
                    qualityMenuBackground
                            .setBounds(qualitySelector.getWidth(), visibleHeight)
                            .setPosition(qualitySelector.getRelativeX(), bottom - visibleHeight)
                            .setRadius(6.0 * Math.max(.82, NCMPlayerConfig.getPlayerScale()))
                            .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND))
                            .setAlpha((float) (ControlsBar.this.getAlpha() * .98f * progress));
                });

        for (int index = 0; index < selectableQualities.length; index++) {
            final Quality option = selectableQualities[index];
            final int optionIndex = index;
            qualityOptions[index] = new RoundedRectWidget();
            RoundedRectWidget qualityOption = qualityOptions[index];
            this.addChild(qualityOption);
            qualityOption
                    .setShouldOverrideMouseCursor(true)
                    .setHidden(true)
                    .setBeforeRenderCallback(() -> {
                        boolean selected = option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality);
                        double scale = Math.max(.82, Math.min(1.0, NCMPlayerConfig.getPlayerScale()));
                        double optionHeight = qualityOptionHeight * scale;
                        // 与背景共用同一条固定底边，所以展开过程里选项不会整体滑动，只是逐个露出来。
                        double bottom = qualitySelector.getRelativeY() - 4.0;
                        double slotY = bottom - qualityMenuPadding * scale
                                - (selectableQualities.length - optionIndex) * optionHeight;
                        // 背景的上边沿扫过这一项时它才显形：任何时候都不会有选项浮在面板之外。
                        double reveal = smoothProgress((slotY + optionHeight - qualityMenuBackground.getRelativeY())
                                / Math.max(1.0, optionHeight));
                        qualityOption
                                .setBounds(Math.max(0.0, qualitySelector.getWidth() - qualityMenuPadding * 2.0), optionHeight)
                                .setPosition(qualitySelector.getRelativeX() + qualityMenuPadding,
                                        slotY + (1.0 - reveal) * 3.0)
                                .setRadius(4.0 * scale)
                                .setColor(qualityOption.isHovering()
                                        ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                                        : (selected ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT) : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND)))
                                .setAlpha((float) (ControlsBar.this.getAlpha() * (selected ? .72f : .98f) * reveal));
                        // 收起动画期间不再接受点击，避免"已经看不清"的选项还能被误中。
                        qualityOption.setClickable(qualityMenuOpen && reveal > .35);
                    })
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton != 0) return true;
                        CloudMusic.quality = option;
                        NCMPlayerConfig.setAudioQuality(option);
                        // 收起同样走动画，弹出层由 isQualityMenuVisible() 保留到播完。
                        qualityMenuOpen = false;
                        DownloadDynamicIsland.showPlaybackQuality("已切换 " + formatQuality(option), "下次播放生效");
                        return true;
                    });

            LabelWidget optionText = new LabelWidget(() -> option == (CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality)
                    ? "✓  " + formatQuality(option)
                    : "   " + formatQuality(option), FontManager.pf12bold);
            qualityOption.addChild(optionText);
            optionText.setClickable(false);
            optionText.setBeforeRenderCallback(() -> optionText
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT))
                    .setPosition(5.0, Math.max(1.0, (qualityOption.getHeight() - optionText.getHeight()) * .5)));
        }
    }

    private static String formatQuality(Quality quality) {
        if (quality == null) return "无损";
        switch (quality) {
            case STANDARD: return "标准";
            case HIGHER: return "高品质";
            case EXHIGH: return "极高";
            case LOSSLESS: return "无损";
            case HIRES: return "Hi-Res";
            case JYEFFECT: return "高清环绕";
            case SKY: return "沉浸音质";
            case JYMASTER: return "母带";
            default: return quality.getQuality();
        }
    }
    private String formatDuration(float totalMillis) {
        int totalSeconds = (int) (totalMillis / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String result = "";

        if (hours > 0) {
            result += (hours < 10 ? "0" : "") + hours + ":";
        }

        result += (minutes < 10 ? "0" : "") + minutes + ":";
        result += (seconds < 10 ? "0" : "") + seconds;

        return result;
    }
}
