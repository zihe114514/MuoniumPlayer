package com.muoniumplayer.core.screens.ncm.panels;

import org.lwjgl.input.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.ncm.music.dto.User;
import com.muoniumplayer.core.rendering.FontelloIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.CoverflowOverlay;
import com.muoniumplayer.core.screens.ncm.NCMPanel;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * Date: 2025/10/17 18:42
 */
public class PlaylistPanel extends NCMPanel {

    public PlayList playList;
    private final boolean showBackButton;

    public PlaylistPanel(PlayList playlist) {
        this(playlist, true);
    }

    /** Used by the recent-play list, whose parent discovery page already owns navigation. */
    public PlaylistPanel(PlayList playlist, boolean showBackButton) {
        this.playList = playlist;
        this.showBackButton = showBackButton;
    }

    private static final int MUSIC_ENTRANCE_STAGGER_LIMIT = 28;
    private static final long MUSIC_ENTRANCE_STAGGER_MILLIS = 16L;

    private TextFieldWidget tfSearch;
    private double tfOpenAnimation = 20;
    private volatile boolean musicsLoading = true;

    @Override
    public void onInit() {
        // onInit is also used when account metadata (including cloud-drive IDs)
        // arrives asynchronously. Make it idempotent so a refresh replaces the
        // old rows instead of stacking a second copy over the first one.
        this.getChildren().clear();
        this.musicsLoading = true;

        // 搜索结果页不放返回按钮：搜索是从左侧导航栏的搜索框直接进入的，右上角这枚返回键在那里
        // 没有对应的"上一页"，只会把内容区右上角挤掉一块。歌单详情、发现页曲目列表照旧保留。
        boolean backButtonAllowed = showBackButton && !playList.isSearchResultList();
        RoundedButtonWidget btnBack = new RoundedButtonWidget(FontelloIcons.BACK, FontManager.fontello18);
        this.addChild(btnBack);
        btnBack.setShouldOverrideMouseCursor(true);
        btnBack.setBeforeRenderCallback(() -> {
            btnBack.setHidden(!backButtonAllowed);
            btnBack.setBounds(28, 22);
            btnBack.setPosition(Math.max(8, getWidth() - btnBack.getWidth() - 12), 8);
            btnBack.setRadius(6);
            btnBack.setColor(btnBack.isHovering()
                    ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            btnBack.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        });
        btnBack.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
            if (mouseButton != 0) return false;
            NCMScreen.getInstance().navigateBack();
            return true;
        });

        double musicsContainerOffsetY;

        if (!playList.isSearchMode()) {
            RoundedImageWidget cover = new RoundedImageWidget(this.playList.getCoverLocation(), 0, 0, 0, 0);

            cover.setPosition(24, 24);
            cover.setBounds(128, 128);
            cover.fadeIn();
            cover.setLinearFilter(true);

            this.addChild(cover);
            this.loadCover();

            cover.setBeforeRenderCallback(() -> cover.setRadius(4));

//        LabelWidget lblPlaylistName = new LabelWidget(playList.name, FontManager.pf);
            RoundedButtonWidget btnPlay = new RoundedButtonWidget("播放歌单", FontManager.pf16bold);
            this.addChild(btnPlay);

            btnPlay.setBeforeRenderCallback(() -> {
                btnPlay.setBounds(57, 17);
                btnPlay.setPosition(cover.getRelativeX() + cover.getWidth() + 12, cover.getRelativeY() + cover.getHeight() - btnPlay.getHeight());
                btnPlay.setRadius(3);
                btnPlay.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnPlay.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnPlay.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    playList.loadMusicsWithCallback(musics -> {
                        CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                        CloudMusic.play(musics, 0);
                    });
                }

                return true;
            });


            RoundedButtonWidget btnCoverflow = new RoundedButtonWidget("Coverflow", FontManager.pf16bold);
            this.addChild(btnCoverflow);

            btnCoverflow.setBeforeRenderCallback(() -> {
                btnCoverflow.setBounds(57, 17);
                btnCoverflow.setPosition(btnPlay.getRelativeX() + btnPlay.getWidth() + 8, cover.getRelativeY() + cover.getHeight() - btnCoverflow.getHeight());
                btnCoverflow.setRadius(3);
                btnCoverflow.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnCoverflow.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            btnCoverflow.setOnClickCallback((relativeX, relativeY, mouseButton) -> {

                if (mouseButton == 0) {
                    api.displayScreen(CoverflowOverlay.byPlaylist(playList));
                }

                return true;
            });

            RoundedButtonWidget btnSubscribe = new RoundedButtonWidget(
                    () -> playList.isSubscribed() ? "取消收藏" : "收藏歌单", FontManager.pf16bold);
            this.addChild(btnSubscribe);
            btnSubscribe.setBeforeRenderCallback(() -> {
                boolean canToggleSubscription = canToggleSubscription();
                btnSubscribe.setHidden(!canToggleSubscription);
                btnSubscribe.setBounds(57, 17);
                btnSubscribe.setPosition(btnCoverflow.getRelativeX() + btnCoverflow.getWidth() + 8,
                        cover.getRelativeY() + cover.getHeight() - btnSubscribe.getHeight());
                btnSubscribe.setRadius(3);
                btnSubscribe.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                btnSubscribe.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });
            btnSubscribe.setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                if (mouseButton != 0 || !canToggleSubscription()) {
                    return false;
                }
                if (!playList.isSubscribed()) {
                    playList.setSubscribed(true);
                    MultiThreadingUtil.runAsync(() -> playList.subscribe(true));
                    return true;
                }
                NCMScreen.getInstance().openConfirmation(
                        "取消收藏歌单？",
                        "取消后，该歌单将从“我的歌单”中移除。",
                        "取消收藏",
                        () -> {
                            playList.setSubscribed(false);
                            MultiThreadingUtil.runAsync(() -> playList.subscribe(false));
                        }
                );
                return true;
            });
            RoundedRectWidget searchBar = new RoundedRectWidget();
            this.addChild(searchBar);

            searchBar
                    .setShouldOverrideMouseCursor(true)
                    .setOnClickCallback((relativeX, relativeY, mouseButton) -> {
                        if (mouseButton == 0) {
                            if (!this.tfSearch.isFocused()) {
                                this.tfSearch.setFocused(true);
                                this.tfSearch.getTextField().lmbPressed = true;
                            }
                        }

                        return true;
                    })
                    .setBeforeRenderCallback(() -> {
                        tfOpenAnimation = Interpolations.interpolate(tfOpenAnimation, this.tfSearch.isFocused() ? 80 : 20, .3f);

                        this.tfSearch.setHidden(!this.tfSearch.isFocused() && tfOpenAnimation < 21);

                        boolean canToggleSubscription = canToggleSubscription();
                        double searchAnchorX = canToggleSubscription
                                ? btnSubscribe.getRelativeX() + btnSubscribe.getWidth()
                                : btnCoverflow.getRelativeX() + btnCoverflow.getWidth();
                        searchBar
                                .setAlpha(1f)
                                .setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER))
                                .setWidth(tfOpenAnimation)
                                .setHeight(btnCoverflow.getHeight())
                                .setRadius(7)
                                .setPosition(searchAnchorX + 8, btnCoverflow.getRelativeY());
                    });

            RoundedRectWidget searchBarBg = new RoundedRectWidget();
            searchBar.addChild(searchBarBg);
            searchBarBg
                    .setClickable(false)
                    .setBeforeRenderCallback(() -> {
                        searchBarBg
                                .setMargin(.5)
                                .setAlpha(.6f)
                                .setRadius(searchBar.getRadius() - .5);
                        searchBarBg.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
                    });

            LabelWidget lblSearchIcon = new LabelWidget("K", FontManager.music18);
            searchBar.addChild(lblSearchIcon);
            lblSearchIcon
                    .setClickable(false)
                    .setColor(hexColor(100, 100, 100))
                    .setBeforeRenderCallback(() -> lblSearchIcon
                            .centerVertically()
                            .setPosition(lblSearchIcon.getRelativeY(), lblSearchIcon.getRelativeY()));

            this.tfSearch = new TextFieldWidget(FontManager.pf14bold);
            searchBar.addChild(tfSearch);

            this.tfSearch.setOnKeyTypedCallback((character, keyCode) -> {
                if (this.tfSearch.isFocused()) {
                    if (keyCode == Keyboard.KEY_ESCAPE)
                        this.tfSearch.setFocused(false);


                    return true;
                }

                return false;
            });

            this.setOnKeyTypedCallback((character, keyCode) -> {

                if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_G)) {
                    this.tfSearch.setFocused(true);
                    this.tfSearch.getTextField().selectAll();
                    return true;
                }

                return false;
            });

            tfSearch.setBeforeRenderCallback(() -> {
                tfSearch.drawUnderline(false);
                tfSearch.setMargin(2);
                double xSpacing = lblSearchIcon.getRelativeX() + lblSearchIcon.getWidth() + 4;
                tfSearch.setBounds(xSpacing, tfSearch.getRelativeY(), tfSearch.getWidth() - xSpacing, tfSearch.getHeight());
                tfSearch.setColor(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                tfSearch.setDisabledTextColor(RenderSystem.reAlpha(this.getColor(NCMScreen.ColorType.PRIMARY_TEXT), .4f));
            });

            User creator = this.playList.getCreator();
            boolean hasCreatorAvatar = creator != null
                    && creator.getAvatarUrl() != null
                    && !creator.getAvatarUrl().trim().isEmpty();
            RoundedImageWidget creatorAvatar = hasCreatorAvatar
                    ? new RoundedImageWidget(creator.getAvatarLocation(), 0, 0, 0, 0)
                    : null;

            if (creatorAvatar != null) {
                this.addChild(creatorAvatar);
                creatorAvatar.fadeIn();
                creatorAvatar.setLinearFilter(true);
                this.loadAvatar();
                creatorAvatar.setBeforeRenderCallback(() -> {
                    creatorAvatar.setBounds(16, 16);
                    creatorAvatar.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            btnPlay.getRelativeY() - 6 - creatorAvatar.getHeight());
                    creatorAvatar.setRadius(7.25);
                });
            }

            String creatorName = creator == null || creator.getName() == null || creator.getName().trim().isEmpty()
                    ? playList.getPlatform().getDisplayName()
                    : creator.getName();
            LabelWidget lblCreator = new LabelWidget(creatorName, FontManager.pf16bold);
            this.addChild(lblCreator);

            lblCreator.setBeforeRenderCallback(() -> {
                if (creatorAvatar != null) {
                    lblCreator.setPosition(creatorAvatar.getRelativeX() + creatorAvatar.getWidth() + 4,
                            creatorAvatar.getRelativeY() + creatorAvatar.getHeight() * .5 - lblCreator.getHeight() * .5);
                } else {
                    lblCreator.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                            btnPlay.getRelativeY() - 6 - lblCreator.getHeight());
                }
                lblCreator.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            LabelWidget lblPlaylistInfo = new LabelWidget(this::getPlayListInfo, FontManager.pf12);
            this.addChild(lblPlaylistInfo);

            lblPlaylistInfo.setBeforeRenderCallback(() -> {
                double creatorTop = creatorAvatar == null
                        ? lblCreator.getRelativeY()
                        : creatorAvatar.getRelativeY();
                lblPlaylistInfo.setPosition(cover.getRelativeX() + cover.getWidth() + 12,
                        creatorTop - 8 - lblPlaylistInfo.getHeight());
                lblPlaylistInfo.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            });
            LabelWidget lblPlaylistName = new LabelWidget(playList.getName(), FontManager.pf32);
            this.addChild(lblPlaylistName);

            lblPlaylistName.setBeforeRenderCallback(() -> {
                lblPlaylistName.setPosition(cover.getRelativeX() + cover.getWidth() + 12, lblPlaylistInfo.getRelativeY() - 4 - lblPlaylistName.getHeight());
                lblPlaylistName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            });

            musicsContainerOffsetY = cover.getRelativeY() + cover.getHeight() + 24;
        } else {
            musicsContainerOffsetY = 18;
        }

        Panel rwMusicsContainer = new Panel();
        final double bottomSafeInset = 8.0;

        this.addChild(rwMusicsContainer);

        rwMusicsContainer.setBeforeRenderCallback(() -> {
            // Keep the last visible row clear of the controls bar. The nested ScrollPanel
            // clips song rows to this reduced viewport instead of letting text touch the edge.
            rwMusicsContainer.setBounds(
                    Math.max(0.0, this.getWidth() - 36),
                    Math.max(0.0, this.getHeight() - musicsContainerOffsetY - bottomSafeInset)
            );
            rwMusicsContainer.centerHorizontally();
            rwMusicsContainer.setPosition(rwMusicsContainer.getRelativeX(), musicsContainerOffsetY);
        });

        ScrollPanel musicsPanel = new ScrollPanel();

        rwMusicsContainer.addChild(musicsPanel);
        musicsPanel.setSpacing(0);

        musicsPanel.setBeforeRenderCallback(() -> musicsPanel.setMargin(0));

        // Keep the loading feedback above the clipped song list. The widget fades
        // itself out after the asynchronous request has finished.
        PlaylistLoadingWidget loadingWidget = new PlaylistLoadingWidget();
        this.addChild(loadingWidget);
        loadingWidget
                .setClickable(false)
                .setAlpha(0f)
                .setBeforeRenderCallback(() -> {
                    loadingWidget.setBounds(rwMusicsContainer.getRelativeX(), rwMusicsContainer.getRelativeY(),
                            rwMusicsContainer.getWidth(), rwMusicsContainer.getHeight());

                    float targetAlpha = musicsLoading ? 1f : 0f;
                    float nextAlpha = Interpolations.interpolate(loadingWidget.getWidgetAlpha(), targetAlpha, .24f);
                    loadingWidget.setAlpha(nextAlpha);
                    loadingWidget.setHidden(!musicsLoading && nextAlpha <= .015f);
                });

        playList.loadMusicsWithCallback(musics -> {
            // Do not call List#indexOf for every song: that is O(n²) and becomes
            // noticeably slow for large playlists. Build one child batch instead.
            List<AbstractWidget<?>> musicWidgets = new ArrayList<>(musics == null ? 0 : musics.size());
            if (musics != null) {
                for (int index = 0; index < musics.size(); index++) {
                    MusicWidget musicWidget = new MusicWidget(musics.get(index), playList, index);
                    musicWidget.setShouldOverrideMouseCursor(true);
                    musicWidgets.add(musicWidget);
                }
            }

            // Start the entrance animation after every widget has been built. This
            // keeps huge playlists from spending their fade-in time on construction.
            long entranceStartedAt = System.currentTimeMillis();
            for (int index = 0; index < musicWidgets.size(); index++) {
                ((MusicWidget) musicWidgets.get(index)).setEntranceAnimation(
                        entranceStartedAt,
                        Math.min(index, MUSIC_ENTRANCE_STAGGER_LIMIT) * MUSIC_ENTRANCE_STAGGER_MILLIS
                );
            }
            musicsPanel.addChild(musicWidgets);
            musicsLoading = false;
        });

        if (this.tfSearch != null) {
            this.tfSearch.setTextChangedCallback(text -> {
                if (text.isEmpty()) {
                    musicsPanel.getChildren().forEach(child -> child.setHidden(false));
                } else {
                    musicsPanel.getChildren()
                            .stream()
                            .filter(child -> child instanceof MusicWidget)
                            .map(child -> (MusicWidget) child)
                            .forEach(widget -> {
                                    if (
                                            widget.music.getName().toLowerCase().contains(text.toLowerCase()) ||
                                            widget.music.getTranslatedNames().toLowerCase().contains(text.toLowerCase()) ||
                                            widget.music.getArtists().stream().anyMatch(artist -> artist != null && artist.getName() != null && artist.getName().toLowerCase().contains(text.toLowerCase())) ||
                                            (widget.music.getAlbum() != null && widget.music.getAlbum().getName() != null && widget.music.getAlbum().getName().toLowerCase().contains(text.toLowerCase()))
                                    ) {
                                        widget.setHidden(false);
                                    } else {
                                        widget.setHidden(true);
                                    }
                            });
                }

            });
        }
    }

    /**
     * Only external NetEase playlists support the subscribe/unsubscribe toggle.
     * A playlist created by the current account is already part of “我的歌单”,
     * so displaying “取消收藏” there is both misleading and ineffective.
     */
    private boolean canToggleSubscription() {
        if (playList == null || playList.getPlatform() != MusicPlatform.NETEASE) {
            return false;
        }
        User creator = playList.getCreator();
        User currentUser = CloudMusic.profile;
        return creator == null || currentUser == null || creator.getId() != currentUser.getId();
    }

    /**
     * A compact 12-segment activity indicator modeled after the iOS/macOS
     * spinner. It deliberately contains no backdrop, so it feels integrated
     * with every player theme rather than like a blocking dialog.
     */
    private static final class PlaylistLoadingWidget extends AbstractWidget<PlaylistLoadingWidget> {
        private static final int SEGMENT_COUNT = 12;
        private float rotation;

        @Override
        public void onRender(double mouseX, double mouseY) {
            double centerX = this.getX() + this.getWidth() * .5;
            double centerY = this.getY() + this.getHeight() * .5 - 12;

            // RenderSystem frame delta is expressed in roughly 10 ms units.
            rotation = (float) ((rotation + RenderSystem.getFrameDeltaTime() * 5.0) % 360.0);

            for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
                float trail = 1f - segment / (float) SEGMENT_COUNT;
                float segmentAlpha = this.getAlpha() * (.14f + .86f * trail * trail);

                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(centerX, centerY, 0);
                api.getGLStateManager().rotate(rotation + segment * (360f / SEGMENT_COUNT), 0, 0, 1);
                this.roundedRect(-1.65, -12, 3.3, 6.2, 1.65,
                        RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), segmentAlpha));
                api.getGLStateManager().popMatrix();
            }

            String loadingText = "正在加载曲目…";
            double textWidth = FontManager.pf14.getStringWidthD(loadingText);
            FontManager.pf14.drawString(loadingText, centerX - textWidth * .5, centerY + 17,
                    RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), this.getAlpha()));
        }
    }
    private String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format("%02d时", hours));
        }

        if (minutes > 0) {
            sb.append(String.format("%02d分", minutes));
        }

        sb.append(String.format("%02d秒", seconds));

        return sb.toString();
    }

    String cached = "";
    int lastSize = -1;

    private String getPlayListInfo() {
        if (!playList.musicsLoaded)
            return "";

        List<Music> musics = playList.musics;

        if (lastSize != musics.size()) {
            lastSize = musics.size();
            if (musics.isEmpty()) {
                cached = playList.getCount() + "首歌曲";
            } else {
                cached = musics.size() + "首歌曲 · " + this.formatDuration(musics.stream().mapToLong(Music::getDuration).sum());
            }
        }

        return cached;
    }

    private void loadCover() {
        String coverUrl = this.playList.getCoverUrl();
        if (coverUrl == null || coverUrl.trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.playList.getCoverLocation();
        if (textureManager.getTexture(coverLoc) != null) {
            return;
        }

        String secureUrl = coverUrl.replace("http://", "https://");
        String requestUrl = this.playList.getPlatform() == MusicPlatform.QQ
                ? secureUrl
                : secureUrl + "?param=256y256";
        Textures.downloadTextureAndLoadAsync(requestUrl, coverLoc);
    }

    private void loadAvatar() {
        User creator = this.playList.getCreator();
        if (creator == null || creator.getAvatarUrl() == null || creator.getAvatarUrl().trim().isEmpty()) {
            return;
        }

        TextureManager textureManager = TextureManager.getInstance();
        Location avatarLoc = creator.getAvatarLocation();
        if (textureManager.getTexture(avatarLoc) != null) {
            return;
        }

        String secureUrl = creator.getAvatarUrl().replace("http://", "https://");
        Textures.downloadTextureAndLoadAsync(secureUrl + "?param=32y32", avatarLoc);
    }

}
