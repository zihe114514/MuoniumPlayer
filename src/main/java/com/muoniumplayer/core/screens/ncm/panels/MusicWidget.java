package com.muoniumplayer.core.screens.ncm.panels;

import today.opai.api.enums.EnumChatColor;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.ThemedTextureIconWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.awt.*;

/**
 * @author IzumiiKonata
 * Date: 2025/10/17 20:40
 */
public class MusicWidget extends RoundedRectWidget {

    private static final long ENTRANCE_DURATION_MILLIS = 280L;

    /** 时长文本与行右边缘之间的留白。 */
    private static final double DURATION_RIGHT_MARGIN = 8.0;
    /** 右侧每一枚操作图标的边长。 */
    private static final double ACTION_ICON_SIZE = 20.0;
    /** 右侧控件彼此之间的间距。 */
    private static final double ACTION_GAP = 4.0;
    /** 一小时的毫秒数：到这个长度 {@link #formatDuration(long)} 才会多出小时位。 */
    private static final long ONE_HOUR_MILLIS = 3600_000L;

    public PlayList playList;
    public Music music;
    boolean coverLoaded = false;
    private long entranceStartedAt = -1L;
    private long entranceDelayMillis;
    private boolean entranceAnimationFinished = true;

    public MusicWidget(Music music, PlayList playList, int index) {
        super(0, 0, 0, 30);
        this.music = music;
        this.playList = playList;

        RoundedRectWidget rrHoverIndicator = new RoundedRectWidget();
        this.addChild(rrHoverIndicator);
        rrHoverIndicator
                .setAlpha(0f)
                .setClickable(false);
        rrHoverIndicator.setBeforeRenderCallback(() -> rrHoverIndicator
                .setMargin(0)
                .setRadius(this.getRadius())
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)));

        RoundedRectWidget rrPlayingIndicator = new RoundedRectWidget();
        this.addChild(rrPlayingIndicator);
        rrPlayingIndicator
                .setAlpha(0f)
                .setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT))
                .setClickable(false);
        if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music)) {
            rrPlayingIndicator.setAlpha(1f);
        }
        rrPlayingIndicator.setBeforeRenderCallback(() -> rrPlayingIndicator
                .setMargin(0)
                .setRadius(this.getRadius()));

        this.setBeforeRenderCallback(() -> {

            // 只在这个 music 被渲染的时候才加载封面；GD 封面未就绪时下一帧重试
            if (!coverLoaded) {
                coverLoaded = this.loadCover();
            }

            this.setBounds(this.getParentWidth(), 30);
            this.setColor(NCMScreen.getColor(index % 2 == 0 ? NCMScreen.ColorType.ELEMENT_BACKGROUND : NCMScreen.ColorType.GENERIC_BACKGROUND));
            this.updateEntranceAnimation();

            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music)) {
//                this.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                rrPlayingIndicator.setAlpha(Interpolations.interpolate(rrPlayingIndicator.getWidgetAlpha(), .9f, .4f));
                rrPlayingIndicator.setHidden(false);
            } else if (this.isHovering()) {
//                this.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                rrHoverIndicator.setAlpha(Interpolations.interpolate(rrHoverIndicator.getWidgetAlpha(), 1, .3f));
                rrHoverIndicator.setHidden(false);
            } else {
                rrPlayingIndicator.setAlpha(Interpolations.interpolate(rrPlayingIndicator.getWidgetAlpha(), 0, .4f));
                rrHoverIndicator.setAlpha(Interpolations.interpolate(rrHoverIndicator.getWidgetAlpha(), 0, .3f));

                if (rrPlayingIndicator.getWidgetAlpha() <= .05f)
                    rrPlayingIndicator.setHidden(true);

                if (rrHoverIndicator.getWidgetAlpha() <= .05f)
                    rrHoverIndicator.setHidden(true);
            }

            this.setRadius(2);
        });

        this.setOnClickCallback((x, y, i) -> {

            if (i == 0) {
                CloudMusic.currentPlaylistContext = playList.isSearchMode() ? null : playList;
                if (playList.isPersonalFm()) {
                    CloudMusic.playFm(playList.getMusics(), index);
                } else if (playList.isSearchResultList()) {
                    // 搜索结果不顺着搜索排序往下播，改为接用户的"最近播放"。
                    CloudMusic.playFromSearchSelection(this.music, playList.getMusics(), index);
                } else {
                    CloudMusic.play(playList.getMusics(), index);
                }
            }

            return true;
        });

        RoundedImageWidget cover = new RoundedImageWidget(this.music.getSmallCoverLocation(), 0, 0, 0, 0);
        this.addChild(cover);
        cover.fadeIn();
        cover.setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            cover.setRadius(2);
            cover.setBounds(24, 24);
            cover.centerVertically();
            cover.setPosition(30, cover.getRelativeY());
        });
        cover.setClickable(false);

        LabelWidget lblMusicIndex = new LabelWidget(String.valueOf(index + 1), FontManager.pf14bold);
        this.addChild(lblMusicIndex);

        lblMusicIndex.setBeforeRenderCallback(() -> {
            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicIndex.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicIndex.centerVertically();
            lblMusicIndex.setPosition(cover.getRelativeX() - 4 - lblMusicIndex.getWidth(), lblMusicIndex.getRelativeY());
        });

        lblMusicIndex.setClickable(false);

        boolean musicDirty = music.isDirty();
        double dirtyIndicatorSize = 8;
        boolean digitalAlbumTrack = music.isDigitalAlbumTrack();
        // A digital-album purchase is a different entitlement from a VIP-only stream;
        // show one clear badge instead of a redundant "VIP + 专辑" pair.
        boolean vipRestricted = music.hasVipRestriction() && !digitalAlbumTrack;
        boolean cloudSong = music.isCloudSong();
        String highestQuality = music.getHighestQualityLabel();
        boolean showHighestQuality = highestQuality != null && !highestQuality.isEmpty();
        String gdSourceLabel = music.isGd() ? GdStudioMusicService.displayName(music.getGdPlatform()) : "";
        boolean showGdSource = music.isGd() && !gdSourceLabel.isEmpty();
        double gdBadgeWidth = showGdSource ? Math.max(26.0, gdSourceLabel.length() * 7.0 + 10.0) : 0.0;
        // 音效标签：网易云 mark 位里的杜比全景声与纯伴奏。两个位一直有定义却没有任何 UI 用过。
        // 刻意不展示 STEREO(8192)：那一位的确切含义在上游没有可靠说明，宁可不标也不要标错。
        boolean dolbyAtmos = music.isDolbyAtmos();
        boolean instrumental = music.isInstrumental();
        double dolbyBadgeWidth = dolbyAtmos ? 34.0 : 0.0;
        double instrumentalBadgeWidth = instrumental ? 27.0 : 0.0;
        double accessBadgeReserve = (vipRestricted ? 24.0 : 0.0) + (digitalAlbumTrack ? 28.0 : 0.0)
                + (cloudSong ? 28.0 : 0.0) + (showHighestQuality ? 38.0 : 0.0) + gdBadgeWidth
                + (dolbyAtmos ? dolbyBadgeWidth + 3.0 : 0.0)
                + (instrumental ? instrumentalBadgeWidth + 3.0 : 0.0);
        // 解灰标签是播放之后才知道结果的，所以它占的宽度必须逐帧算，否则标签出现时会压在长标题上。
        final double[] unblockBadgeReserve = {0.0};

        String translatedNames = music.getTranslatedNames();

        LabelWidget lblMusicName = new LabelWidget(music.getName() + (translatedNames.isEmpty() ? "" : EnumChatColor.GRAY + " (" + translatedNames + ")"), FontManager.pf14bold);
        this.addChild(lblMusicName);

        lblMusicName
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    lblMusicName.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    lblMusicName.centerVertically();
                    lblMusicName.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicName.getRelativeY() - lblMusicName.getHeight() * .5 - 2);
                    // Reserve space for duration/actions and licensing badges so no badge can overlap a trimmed title.
                    lblMusicName.setMaxWidth(Math.max(1.0, this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4
                            + rightActionsReserve() + (musicDirty ? (dirtyIndicatorSize + 4) : 0)
                            + accessBadgeReserve + unblockBadgeReserve[0])));
                });
        lblMusicName.setClickable(false);

        double nextAccessBadgeX = 0.0;
        if (vipRestricted) {
            RoundedRectWidget vipBadge = new RoundedRectWidget(0, 0, 21, 10);
            this.addChild(vipBadge);
            vipBadge.setClickable(false);
            vipBadge.setBeforeRenderCallback(() -> {
                vipBadge.setRadius(2.5);
                vipBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                vipBadge.setAlpha(.92f);
                vipBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - vipBadge.getHeight() * .5);
            });
            LabelWidget vipText = new LabelWidget("VIP", FontManager.pf12bold);
            vipBadge.addChild(vipText);
            vipText.setClickable(false);
            vipText.setBeforeRenderCallback(() -> {
                vipText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                vipText.center();
            });
            nextAccessBadgeX = 24.0;
        }

        if (digitalAlbumTrack) {
            final double albumBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget albumBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(albumBadge);
            albumBadge.setClickable(false);
            albumBadge.setBeforeRenderCallback(() -> {
                albumBadge.setRadius(2.5);
                albumBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                albumBadge.setAlpha(.95f);
                albumBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + albumBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - albumBadge.getHeight() * .5);
            });
            LabelWidget albumText = new LabelWidget("专辑", FontManager.pf12bold);
            albumBadge.addChild(albumText);
            albumText.setClickable(false);
            albumText.setBeforeRenderCallback(() -> {
                albumText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                albumText.center();
            });
        }

        // Keep the title badges in one advancing row: VIP / digital album / cloud drive / highest tier.
        nextAccessBadgeX += digitalAlbumTrack ? 28.0 : 0.0;
        if (cloudSong) {
            final double cloudBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget cloudBadge = new RoundedRectWidget(0, 0, 25, 10);
            this.addChild(cloudBadge);
            cloudBadge.setClickable(false);
            cloudBadge.setBeforeRenderCallback(() -> {
                cloudBadge.setRadius(2.5);
                cloudBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                cloudBadge.setAlpha(.95f);
                cloudBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + cloudBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - cloudBadge.getHeight() * .5);
            });
            LabelWidget cloudText = new LabelWidget("网盘", FontManager.pf12bold);
            cloudBadge.addChild(cloudText);
            cloudText.setClickable(false);
            cloudText.setBeforeRenderCallback(() -> {
                cloudText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                cloudText.center();
            });
            nextAccessBadgeX += 28.0;
        }

        if (showHighestQuality) {
            final double qualityBadgeOffset = nextAccessBadgeX;
            final double qualityBadgeWidth = "Hi-Res".equals(highestQuality) ? 34.0 : 27.0;
            RoundedRectWidget qualityBadge = new RoundedRectWidget(0, 0, qualityBadgeWidth, 10);
            this.addChild(qualityBadge);
            qualityBadge.setClickable(false);
            qualityBadge.setBeforeRenderCallback(() -> {
                qualityBadge.setRadius(2.5);
                qualityBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ACCENT));
                qualityBadge.setAlpha(.82f);
                qualityBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + qualityBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - qualityBadge.getHeight() * .5);
            });
            LabelWidget qualityText = new LabelWidget(highestQuality, FontManager.pf12bold);
            qualityBadge.addChild(qualityText);
            qualityText.setClickable(false);
            qualityText.setBeforeRenderCallback(() -> {
                qualityText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                qualityText.center();
            });
            nextAccessBadgeX += qualityBadgeWidth + 3.0;
        }

        if (showGdSource) {
            final double gdBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget gdBadge = new RoundedRectWidget(0, 0, gdBadgeWidth, 10);
            this.addChild(gdBadge);
            gdBadge.setClickable(false);
            gdBadge.setBeforeRenderCallback(() -> {
                gdBadge.setRadius(2.5);
                gdBadge.setColor(0x3D5AFE);
                gdBadge.setAlpha(.92f);
                gdBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + gdBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - gdBadge.getHeight() * .5);
            });
            LabelWidget gdText = new LabelWidget(gdSourceLabel, FontManager.pf12bold);
            gdBadge.addChild(gdText);
            gdText.setClickable(false);
            gdText.setBeforeRenderCallback(() -> {
                gdText.setColor(0xFFFFFF);
                gdText.center();
            });
            nextAccessBadgeX += gdBadgeWidth + 3.0;
        }

        if (dolbyAtmos) {
            final double dolbyBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget dolbyBadge = new RoundedRectWidget(0, 0, dolbyBadgeWidth, 10);
            this.addChild(dolbyBadge);
            dolbyBadge.setClickable(false);
            dolbyBadge.setBeforeRenderCallback(() -> {
                dolbyBadge.setRadius(2.5);
                dolbyBadge.setColor(0x7B4DFF);
                dolbyBadge.setAlpha(.92f);
                dolbyBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + dolbyBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - dolbyBadge.getHeight() * .5);
            });
            LabelWidget dolbyText = new LabelWidget("全景声", FontManager.pf12bold);
            dolbyBadge.addChild(dolbyText);
            dolbyText.setClickable(false);
            dolbyText.setBeforeRenderCallback(() -> {
                dolbyText.setColor(0xFFFFFF);
                dolbyText.center();
            });
            nextAccessBadgeX += dolbyBadgeWidth + 3.0;
        }

        if (instrumental) {
            final double instrumentalBadgeOffset = nextAccessBadgeX;
            RoundedRectWidget instrumentalBadge = new RoundedRectWidget(0, 0, instrumentalBadgeWidth, 10);
            this.addChild(instrumentalBadge);
            instrumentalBadge.setClickable(false);
            instrumentalBadge.setBeforeRenderCallback(() -> {
                instrumentalBadge.setRadius(2.5);
                instrumentalBadge.setColor(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
                instrumentalBadge.setAlpha(.95f);
                instrumentalBadge.setPosition(
                        lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + instrumentalBadgeOffset,
                        lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5
                                - instrumentalBadge.getHeight() * .5);
            });
            LabelWidget instrumentalText = new LabelWidget("伴奏", FontManager.pf12bold);
            instrumentalBadge.addChild(instrumentalText);
            instrumentalText.setClickable(false);
            instrumentalText.setBeforeRenderCallback(() -> {
                instrumentalText.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                instrumentalText.center();
            });
            nextAccessBadgeX += instrumentalBadgeWidth + 3.0;
        }

        final double dirtyBadgeOffset = nextAccessBadgeX;
        if (musicDirty) {
            RoundedRectWidget dirtyIndicator = new RoundedRectWidget(0, 0, dirtyIndicatorSize, dirtyIndicatorSize);
            this.addChild(dirtyIndicator);
            dirtyIndicator
                    .setRadius(1.5)
                    .setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));

            dirtyIndicator.setBeforeRenderCallback(() -> {
//                dirtyIndicator.centerVertically();
                dirtyIndicator.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 2 + dirtyBadgeOffset, lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - dirtyIndicatorSize * .5);
            });

            dirtyIndicator.setClickable(false);

            LabelWidget lblDirty = new LabelWidget("E", FontManager.pf12bold);
            dirtyIndicator.addChild(lblDirty);
            lblDirty.setBeforeRenderCallback(() -> {
                lblDirty.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                lblDirty.center();
            });
        }

        // 解灰 / 灰色标签。它的文案在点播之后才确定，所以文本、宽度、显隐全部逐帧算。
        final double unblockBadgeOffset = nextAccessBadgeX + (musicDirty ? dirtyIndicatorSize + 3.0 : 0.0);
        RoundedRectWidget unblockBadge = new RoundedRectWidget(0, 0, 0, 10);
        this.addChild(unblockBadge);
        unblockBadge.setClickable(false);
        LabelWidget unblockText = new LabelWidget(() -> unblockBadgeText(this.music), FontManager.pf12bold);
        unblockBadge.addChild(unblockText);
        unblockText.setClickable(false);
        unblockText.setBeforeRenderCallback(() -> {
            unblockText.setColor(this.music.getUnblockedSourceLabel().isEmpty()
                    ? NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT) : 0xFFFFFF);
            unblockText.center();
        });
        // 注意：这里不能用 unblockBadge.setHidden(true) 来隐藏自己 ——
        // AbstractWidget.renderWidget 在 isHidden() 时直接 return，连 beforeRenderCallback 都不跑，
        // 一旦自己把自己隐藏掉就再也没有机会恢复。改为收成 0 尺寸 + alpha 0，并只隐藏子文本。
        unblockBadge.setBeforeRenderCallback(() -> {
            String label = unblockBadgeText(this.music);
            if (label.isEmpty()) {
                unblockBadgeReserve[0] = 0.0;
                unblockBadge.setBounds(0, 0);
                unblockBadge.setAlpha(0f);
                unblockText.setHidden(true);
                return;
            }
            double width = Math.max(18.0, FontManager.pf12bold.getStringWidthD(label) + 8.0);
            unblockBadgeReserve[0] = width + 3.0;
            unblockText.setHidden(false);
            unblockBadge.setBounds(width, 10);
            unblockBadge.setRadius(2.5);
            boolean unblocked = !this.music.getUnblockedSourceLabel().isEmpty();
            unblockBadge.setColor(unblocked ? 0x2E9E5B : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER));
            unblockBadge.setAlpha(.95f);
            unblockBadge.setPosition(lblMusicName.getRelativeX() + lblMusicName.getWidth() + 3 + unblockBadgeOffset,
                    lblMusicName.getRelativeY() + lblMusicName.getHeight() * .5 - unblockBadge.getHeight() * .5);
        });

        String albumName = music.getAlbum() == null || music.getAlbum().getName() == null
                ? "未知专辑" : music.getAlbum().getName();
        LabelWidget lblMusicArtist = new LabelWidget(music.getArtistsName() + " - " + albumName, FontManager.pf14);
        this.addChild(lblMusicArtist);

        lblMusicArtist
                .setWidthLimitType(LabelWidget.WidthLimitType.SCROLL)
                .setBeforeRenderCallback(() -> {
                    if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
                    else
                        lblMusicArtist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
                    lblMusicArtist.centerVertically();
                    lblMusicArtist.setPosition(cover.getRelativeX() + cover.getWidth() + 4, lblMusicArtist.getRelativeY() + lblMusicArtist.getHeight() * .5 + 2);
                    lblMusicArtist.setMaxWidth(this.getWidth() - (cover.getRelativeX() + cover.getWidth() + 4 + rightActionsReserve()));
                });

        lblMusicArtist.setClickable(false);

        // Third-party sources ship no duration in their search response; it is measured while
        // decoding, so the label reads the value live instead of freezing 00:00 at build time.
        LabelWidget lblMusicDuration = new LabelWidget(
                () -> formatDuration(music.getDuration()), FontManager.pf14bold);
        this.addChild(lblMusicDuration);
        lblMusicDuration.setBeforeRenderCallback(() -> {
            if (CloudMusic.currentlyPlaying != null && CloudMusic.currentlyPlaying.equals(music))
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            else
                lblMusicDuration.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            lblMusicDuration.centerVertically();
            lblMusicDuration.setPosition(this.getWidth() - 8 - lblMusicDuration.getWidth(), lblMusicDuration.getRelativeY());
        });
        lblMusicDuration.setClickable(false);

        // ===== 收藏：歌曲喜欢/取消喜欢 + 加入歌单 =====
        ThemedTextureIconWidget btnLike = new ThemedTextureIconWidget(
                PlayerIconAssets.FAVORITE, "☆", FontManager.pf16bold, 0, 0, 20, 20);
        this.addChild(btnLike);
        btnLike.setShouldOverrideMouseCursor(true);
        btnLike.setHidden(!music.isNetease());
        btnLike.setClickable(music.isNetease());
        btnLike.setBeforeRenderCallback(() -> {
            boolean liked = CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
            btnLike.setColor(liked ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnLike.centerVertically();
            // 挂在固定的时长槽位左边缘上，而不是时长标签的左边缘：后者的宽度随数字变化，
            // 会让每一行的图标列各自漂移，上下两行对不齐。
            btnLike.setPosition(actionsAnchorX(lblMusicDuration) - ACTION_GAP - btnLike.getWidth(),
                    btnLike.getRelativeY());
        });
        btnLike.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !music.isNetease())
                return false;

            boolean liked = CloudMusic.likeList != null && CloudMusic.likeList.contains(music.getId());
            if (!liked) {
                if (CloudMusic.likeList != null && !CloudMusic.likeList.contains(music.getId())) {
                    CloudMusic.likeList.add(music.getId());
                }
                MultiThreadingUtil.runAsync(() -> music.setLike(true));
                return true;
            }

            NCMScreen.getInstance().openConfirmation(
                    "取消收藏歌曲？",
                    "取消后，这首歌曲将从“我喜欢的音乐”中移除。",
                    "取消收藏",
                    () -> {
                        if (CloudMusic.likeList != null) {
                            CloudMusic.likeList.remove(music.getId());
                        }
                        MultiThreadingUtil.runAsync(() -> music.setLike(false));
                    }
            );
            return true;
        });

        // 图标走字体（player-action-icons U+E113 方框加号）而不是 playlist.png：字形按实际字号
        // 运行时生成，不再受离线烘图的尺寸/裁切影响，也和相邻的「下一首播放」共用同一套墨迹对齐。
        IconWidget btnAddToPlaylist = PlayerQueueIcons.newAddToPlaylistButton(20);
        this.addChild(btnAddToPlaylist);
        btnAddToPlaylist.setShouldOverrideMouseCursor(true);
        btnAddToPlaylist.setHidden(!music.isNetease());
        btnAddToPlaylist.setClickable(music.isNetease());
        btnAddToPlaylist.setBeforeRenderCallback(() -> {
            btnAddToPlaylist.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnAddToPlaylist.centerVertically();
            btnAddToPlaylist.setPosition(btnLike.getRelativeX() - ACTION_GAP - btnAddToPlaylist.getWidth(),
                    btnAddToPlaylist.getRelativeY());
        });
        btnAddToPlaylist.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !music.isNetease())
                return false;
            NCMScreen.getInstance().openAddToPlaylist(music);
            return true;
        });

        // ===== 下一首播放：把这首歌插到当前歌曲之后，按点击顺序排队 =====
        // Available for every source, not just Netease: it only reorders the local play queue, so it
        // needs nothing from any account or API.
        // 图标走字体（player-queue-icons U+E309）而不是贴图：烘成 PNG 的那一版把字形放大并裁掉了
        // 一角，且每个播放器缩放下都要重新采样。
        IconWidget btnPlayNext = PlayerQueueIcons.newPlayNextButton(20);
        this.addChild(btnPlayNext);
        btnPlayNext.setShouldOverrideMouseCursor(true);
        btnPlayNext.setBeforeRenderCallback(() -> {
            boolean queued = CloudMusic.isQueuedNext(music);
            btnPlayNext.setColor(queued ? NCMScreen.getColor(NCMScreen.ColorType.ACCENT)
                    : NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            btnPlayNext.centerVertically();
            // The two Netease-only controls are hidden for third-party tracks, and a hidden widget's
            // position is not a reliable anchor, so fall back to the fixed duration slot in that case.
            double anchorX = music.isNetease()
                    ? btnAddToPlaylist.getRelativeX()
                    : actionsAnchorX(lblMusicDuration);
            btnPlayNext.setPosition(anchorX - ACTION_GAP - btnPlayNext.getWidth(), btnPlayNext.getRelativeY());
        });
        btnPlayNext.setOnClickCallback((x, y, button) -> {
            if (button != 0)
                return false;
            CloudMusic.playNext(music);
            return true;
        });
    }

    /**
     * Gives freshly fetched tracks a short, staggered opacity entrance without
     * moving their real layout bounds (so scrolling and hit-testing stay stable).
     */
    public MusicWidget setEntranceAnimation(long startedAt, long delayMillis) {
        this.entranceStartedAt = startedAt;
        this.entranceDelayMillis = Math.max(0L, delayMillis);
        this.entranceAnimationFinished = false;
        this.setAlpha(0f);
        return this;
    }

    private void updateEntranceAnimation() {
        if (entranceAnimationFinished) {
            return;
        }

        long elapsed = System.currentTimeMillis() - entranceStartedAt - entranceDelayMillis;
        if (elapsed <= 0L) {
            this.setAlpha(0f);
            return;
        }

        float progress = Math.min(1f, elapsed / (float) ENTRANCE_DURATION_MILLIS);
        // Cubic ease-out makes the fade settle gently instead of appearing linear.
        float easedProgress = 1f - (float) Math.pow(1f - progress, 3f);
        this.setAlpha(easedProgress);

        if (progress >= 1f) {
            this.setAlpha(1f);
            this.entranceAnimationFinished = true;
        }
    }
    /**
     * 时长文本占用的固定槽位宽度。
     *
     * <p>时长用的是比例字体：pf14bold 里 {@code '1'} 只有 3.5 逻辑像素，{@code '0'} / {@code '8'} 是 5.0，
     * 于是同样五个字符的 {@code "11:11"} 量出来 16.0、{@code "00:00"} 量出来 22.0，宽度能差 6 个逻辑像素。
     * 右侧那几枚图标原来是一路挂在时长标签左边缘上的，因此每一行的图标列都跟着自己那首歌的数字宽度
     * 左右漂移，上下相邻两行就对不齐——尤其是列表末尾几行凑在一起时最明显。</p>
     *
     * <p>这里按"用最宽的那个数字铺满模板"算出一个固定槽位：图标挂在槽位左边缘，列因此是死的；时长文本
     * 本身仍然右对齐贴着行的右边缘（数字按右边缘对齐，与主流播放器一致）。宽度是从字体现量的，以后换
     * 字体或改字号都会自己跟着变，不留经验常数。</p>
     *
     * <p>是否留出小时位按这一行自己的时长决定。一个歌单里"有小时"基本是全有或全无（普通歌单一首都没有，
     * 混音 / 播客歌单全都有），所以两种常见情况下都是完美对齐；极少数混排歌单里也只是退回到原来的行为，
     * 不会比现在更差，而给每一行都无条件留出小时位会白扔 12 个逻辑像素。</p>
     *
     * <p>刻意做成包级可见而不是私有：这条算式的正确性完全取决于字体的真实度量，需要能离线量。</p>
     */
    static double durationSlotWidth(boolean withHours) {
        CFontRenderer fr = FontManager.pf14bold;
        double digit = 0.0;
        for (char c = '0'; c <= '9'; c++) {
            digit = Math.max(digit, fr.getCharWidth(c, '\0'));
        }
        double colon = fr.getCharWidth(':', '\0');
        return digit * (withHours ? 6 : 4) + colon * (withHours ? 2 : 1);
    }

    private double durationSlotWidth(LabelWidget lblMusicDuration) {
        // 再拿标签自己的实测宽度兜一层底：字形是懒加载的，首帧 getCharWidth 会返回 0；万一以后
        // formatDuration 的格式变了，槽位也不会窄到把文本压进图标底下。
        return Math.max(durationSlotWidth(this.music.getDuration() >= ONE_HOUR_MILLIS),
                lblMusicDuration.getWidth());
    }

    /** 右侧图标列的锚点，也就是时长槽位的左边缘。 */
    private double actionsAnchorX(LabelWidget lblMusicDuration) {
        return this.getWidth() - DURATION_RIGHT_MARGIN - durationSlotWidth(lblMusicDuration);
    }

    /**
     * 右侧那一组控件（时长 + 图标）实际占掉的宽度，标题与艺术家按它留白。
     *
     * <p>原先这里写的是固定 {@code 80 / 40}。那是「加入歌单」与「下一首播放」两枚图标加进来之前的数字，
     * 现在实际占用是 102 / 54，于是长标题会一直滑到图标底下（标题用的是滚动式限宽，看起来就是文字从图标
     * 下面穿过去）。改成和图标锚点同一套算式之后，两边永远一致。</p>
     */
    private double rightActionsReserve() {
        double slot = durationSlotWidth(this.music.getDuration() >= ONE_HOUR_MILLIS);
        int iconCount = this.music.isNetease() ? 3 : 1;
        return DURATION_RIGHT_MARGIN + slot + iconCount * (ACTION_GAP + ACTION_ICON_SIZE);
    }

    /**
     * 解灰徽标的文案。
     *
     * <p>三态合成一枚徽标：已经用第三方源放出来 → 「解灰 · 源名」；官方链路取不到流且
     * 「音乐是否可用」判定为不可用 → 「灰」；其余情况不显示。合成一枚而不是两枚，是因为
     * 这两种状态互斥，分开占位会在长标题上白白吃掉一段宽度。</p>
     */
    private static String unblockBadgeText(Music music) {
        if (music == null) return "";
        String source = music.getUnblockedSourceLabel();
        if (!source.isEmpty()) return "解灰 · " + source;
        return music.isGreyDetected() ? "灰" : "";
    }

    private String formatDuration(long totalMillis) {
        long totalSeconds = totalMillis / 1000;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();

        if (hours > 0) {
            sb.append(String.format("%02d:", hours));
        }

        sb.append(String.format("%02d:", minutes));
        sb.append(String.format("%02d", seconds));

        return sb.toString();
    }

    private boolean loadCover() {
        TextureManager textureManager = TextureManager.getInstance();
        Location coverLoc = this.music.getSmallCoverLocation();
        if (textureManager.getTexture(coverLoc) != null)
            return true;

        String url = music.getCoverUrl(64);
        if (url == null || url.trim().isEmpty()) {
            // GD 封面仍在异步预取中：返回 false，下一帧重新尝试，避免对空串发起无效下载。
            return false;
        }
        Textures.downloadTextureAndLoadAsync(url, coverLoc);
        return true;
    }

}
