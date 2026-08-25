package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.texture.AccountAvatar;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;

/**
 * 「群唱」：每一行歌词是一条聊天气泡，左右交替，正在唱的那一条逐字浮现。
 *
 * <p>移植自 folia-major 的 {@code cappella} 视觉，含内置头像与表情气泡。参考实现的素材是
 * 3.6 MB 的散图（15 张 256² 头像 + 9 张 ~440² 表情，均为 AI 生成、其 README 声明不受该仓库
 * AGPL 约束），这里挑了 8 + 6 张重新压成两张精灵图共 236 KB：
 * {@code cappella/avatars.png}（4×2，每格 96²，调色板 PNG）与
 * {@code cappella/emoji.png}（3×2，每格 128²，画面只占中间 112²、四周留 8 px 透明边，保留软边
 * alpha）。表情素材原图是<b>贴边裁切</b>的（alpha 包围盒等于整张图），直接铺进精灵图会让相邻格子
 * 在线性过滤下互相渗色，也会被 UV 内缩切掉笔画 —— 那道透明边就是为此留的。</p>
 */
final class CappellaLyricRenderer extends AbstractStageLyricRenderer {

    private static final int WINDOW_BEFORE = 7;
    private static final int WINDOW_AFTER = 2;

    /** 参考实现的间奏占位。前后两行间隔够长就插一条。 */
    private static final String INTERLUDE_TEXT = "......";
    private static final long INTERLUDE_GAP_MS = 5000L;

    /** 参考实现的逐字淡入窗口，毫秒。 */
    private static final double CHAR_FADE_MS = 220;

    /**
     * 当前气泡的<b>底边</b>停在视口高度的这个位置，下面留一点空当预告下一条。
     *
     * <p>之前这里对齐的是气泡<b>顶边</b>（且值是 .70），于是一条折了两三行的长歌词会从 70% 高度
     * 往下画，底部直接被裁到视口外 —— 表现就是「正在播放的那条右侧歌词显示不全」。改成对齐底边后，
     * 只要气泡高度不超过 {@code height * BOTTOM_ANCHOR} 就必然完整可见；万一超过，
     * {@link #draw} 里还会把锚点往下让，保证至少从第一行开始可见。</p>
     */
    private static final double BOTTOM_ANCHOR = .84;

    /** 单条气泡最多占视口多高。超过就整体降一档字号重排，绝不让它被裁。 */
    private static final double MAX_BUBBLE_HEIGHT_RATIO = .78;

    // ==================== 内置素材（folia-chan 精灵图） ====================

    private static final Location AVATAR_SHEET =
            Location.of("/muonium/textures/player/cappella/avatars.png");
    private static final int AVATAR_COLS = 4;
    private static final int AVATAR_ROWS = 2;
    private static final int AVATAR_CELL = 96;
    private static final int AVATAR_COUNT = AVATAR_COLS * AVATAR_ROWS;

    private static final Location EMOJI_SHEET =
            Location.of("/muonium/textures/player/cappella/emoji.png");
    private static final int EMOJI_COLS = 3;
    private static final int EMOJI_ROWS = 2;
    private static final int EMOJI_CELL = 128;
    private static final int EMOJI_COUNT = EMOJI_COLS * EMOJI_ROWS;

    /** 相邻两张表情之间至少隔这么多条歌词。 */
    private static final int EMOJI_MIN_GAP_LINES = 2;
    /** 一屏窗口里表情最多占歌词条数的这个比例。 */
    private static final double EMOJI_MAX_RATIO = 1.0 / 6.0;
    /**
     * 表情盒子边长 = 字号 × 这个系数（参考实现 emoActiveSize / 字号 ≈ 4.4）。
     *
     * <p>比参考实现略大：表情格子里画面只占 112/128，四周留了 8 px 透明边（见类注释），
     * 按 {@code 128/112} 放大盒子之后，屏幕上表情本身的大小才和不留边时一致。</p>
     */
    private static final double EMOJI_BOX_RATIO = 4.9;
    /** 不是当前行时表情只画到盒子的这个比例（参考实现 96/132 ≈ .73）。 */
    private static final double EMOJI_IDLE_RATIO = .73;
    /** 进场初始缩放（参考实现 emoEnterScale）。 */
    private static final double EMOJI_ENTER_SCALE = .62;

    private static final class Message {
        LyricLine line;
        boolean right;
        boolean interlude;
        boolean showAvatar;
        /** 表情精灵图的格子下标；{@code < 0} 表示这是一条普通歌词/间奏气泡。 */
        int emoji = -1;
        /** 左侧说话人的编号，决定用内置头像里的哪一张。右侧固定用保留的那一张。 */
        int avatarSlot;
        double x;
        double y;
        double width;
        double height;
        final List<Row> rows = new ArrayList<Row>();
    }

    private static final class Row {
        List<String> chars;
        int graphemeStart;
        double width;
        double y;
    }

    private final List<Message> messages = new ArrayList<Message>();

    private LyricLine laidOutCenter;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    /**
     * 这一次排版里有文字的字形还没上传，量出来的宽度不可信。
     *
     * <p>见 {@link FullscreenLyricStage#glyphWidth}：字形是按需异步生成的，「刚换到这一行」的那一帧
     * 常常量不到宽度。排版只在换行/换视口时重算，所以这一帧的偏差会被整行沿用下去 —— 表现就是
     * 「字冲出气泡」和「右侧那句整条铺到视口外看不见」。标记上之后由 {@link #draw} 逐帧重排，
     * 字形补齐了自然停下。</p>
     */
    private boolean metricsPending;

    /** 因为字形没齐而重排了多少次。封顶是防御：万一某个字符永远生成不出来，别逐帧重排到底。 */
    private int metricsRetries;

    /** 字形没齐时最多重排这么多帧（60 fps 下约 .7 秒），之后接受当前排版。 */
    private static final int METRICS_MAX_RETRIES = 40;

    private double cameraY;
    private boolean cameraReady;
    private LyricLine anchorLine;
    private double anchorY;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 14;
    private double avatarSize = 20;

    /** 右侧（主唱）保留的内置头像下标，按歌曲稳定。 */
    private int avatarRight;
    /** 左侧头像的起始偏移，按歌曲稳定。 */
    private int avatarOffset;

    protected void onReset() {
        messages.clear();
        laidOutCenter = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        cameraY = 0;
        cameraReady = false;
        anchorLine = null;
        anchorY = 0;
        metricsPending = false;
        metricsRetries = 0;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        boolean viewportChanged = tuningDirty
                || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0;
        boolean relayout = currentLine != laidOutCenter || viewportChanged;
        // 字形没上传完就重排下一帧：这一帧照常绘制会把字形逐步上传（drawString 会触发生成），
        // 下一帧量出来的宽度才是真的。项目里 LyricLine.computeHeight 与 ConfirmationOverlay
        // 的换行也是同一个套路。
        if (!relayout && metricsPending && metricsRetries < METRICS_MAX_RETRIES) {
            relayout = true;
            metricsRetries++;
        } else if (relayout) {
            metricsRetries = 0;
        }
        if (relayout) {
            double previousAnchorY = anchorY;
            LyricLine previousAnchor = anchorLine;
            rebuild(currentLine, positionMs, width, height);
            laidOutCenter = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
            if (previousAnchor != null) {
                double newY = docBottomOf(previousAnchor);
                if (!Double.isNaN(newY)) cameraY += newY - previousAnchorY;
            }
            anchorLine = currentLine;
            anchorY = focusBottom(currentLine, positionMs);
        }
        if (messages.isEmpty() || font == null) return;

        // cameraY 是「当前气泡底边」在文档坐标里的位置，屏幕上它落在 anchorScreenY。
        double targetY = focusBottom(currentLine, positionMs);
        double approach = 1.0 - Math.exp(-frameSeconds * 7.0);
        if (!cameraReady) {
            cameraY = targetY;
            cameraReady = true;
        } else {
            cameraY += (targetY - cameraY) * approach;
        }

        double anchorScreenY = top + height * BOTTOM_ANCHOR + (1.0 - opened) * height * .2;
        // 气泡比锚点上方的空间还高时（极窄视口 + 极长歌词）把锚点往下让，
        // 优先保证第一行可见 —— 宁可尾巴出去，也不能开头就看不到。
        double focusHeight = focusHeight(currentLine, positionMs);
        double minAnchor = top + focusHeight + 4.0;
        if (anchorScreenY < minAnchor) {
            anchorScreenY = Math.min(minAnchor, top + height - 2.0);
        }
        int litColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        int dimColor = FullscreenLyricStage.mixRgb(litColor, 0x7E8794, .5) & 0xFFFFFF;
        boolean effects = HudConfig.currentLyricEffectsEnabled;

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            double screenY = anchorScreenY + (message.y - cameraY);
            if (screenY + message.height < top - 8 || screenY > top + height + 8) continue;

            int state = message.interlude
                    ? FullscreenLyricStage.STATE_PASSED
                    : FullscreenLyricStage.lineState(message.line, positionMs);
            boolean active = state == FullscreenLyricStage.STATE_ACTIVE;
            double enter = message.interlude ? 1.0 : FullscreenLyricStage.clamp01(
                    (positionMs - (message.line.timestamp - 320.0)) / 420.0);
            double eased = FullscreenLyricStage.easeOutCubic(enter);

            double bubbleAlpha = (active ? 1.0
                    : (state == FullscreenLyricStage.STATE_PASSED ? .5 : .3)) * alpha * eased;
            if (bubbleAlpha <= .014) continue;

            // 进场滑动一律<b>朝视口内侧</b>。原先是朝自己那一侧滑（右侧气泡往右滑），
            // 静止位置本就贴着视口边，进场那一段就会把气泡和头像推出边界被裁掉。
            double slide = (1.0 - eased) * fontPx * 1.1 * (message.right ? -1 : 1);
            double x = left + message.x + slide;

            if (message.emoji >= 0) {
                drawEmojiBubble(x, screenY, message, bubbleAlpha, eased, active, accent, left, width);
                continue;
            }

            // 气泡
            double radius = Math.min(message.height * .38, fontPx * .9);
            FullscreenLyricStage.bar(x, screenY, message.width, message.height, radius,
                    active ? accent : 0xFFFFFF, bubbleAlpha * (active ? .17 : .085));

            if (message.showAvatar) {
                drawAvatar(x, screenY, message, bubbleAlpha, accent, left, width);
            }

            double padX = fontPx * .55;
            double padY = fontPx * .38;
            for (int r = 0; r < message.rows.size(); r++) {
                Row row = message.rows.get(r);
                // 右对齐取 max 是防御：row.width 万一比气泡内容区还宽（字形补齐前的估算偏小、
                // 或者单个字形本身就比内容区宽），起点会算到气泡左边缘外面去，整句往视口外冲。
                double rowX = message.right
                        ? Math.max(x + padX, x + message.width - padX - row.width)
                        : x + padX;
                double rowY = screenY + padY + row.y;
                FullscreenLyricStage.pushMatrix();
                try {
                    FullscreenLyricStage.scaleAt(rowX, rowY, fontScale);
                    if (message.interlude) {
                        font.drawString(INTERLUDE_TEXT, rowX, rowY,
                                FullscreenLyricStage.withAlpha(dimColor, bubbleAlpha * .7));
                    } else {
                        int total = FullscreenLyricStage.graphemeCount(
                                message.line.lyric == null ? "" : message.line.lyric);
                        FullscreenLyricStage.drawLitRun(font, row.chars, row.graphemeStart,
                                message.line, Math.max(1, total), positionMs, rowX, rowY,
                                dimColor, litColor, bubbleAlpha,
                                state != FullscreenLyricStage.STATE_WAITING, CHAR_FADE_MS,
                                effects && active);
                    }
                } finally {
                    FullscreenLyricStage.popMatrix();
                }
            }
        }
    }

    /**
     * 头像：内置头像 / 「封面 + 我的头像」/ 主题色圆。取不到贴图时一律退回主题色圆。
     *
     * <p>「封面 + 我的头像」这一档（{@code cappellaAvatarSource == 0}）是聊天界面该有的样子：
     * <b>左侧是歌曲封面</b>（唱歌的那一方），<b>右侧是当前登录账号的头像</b>（自己这一方）。
     * 账号头像取不到时右侧退回封面 —— 未登录、账号没设头像、贴图还在下载中，三种情况都算取不到，
     * 所以这一档在未登录时的表现与改动前完全一致（两侧都是封面）。</p>
     */
    private void drawAvatar(double bubbleX, double bubbleY, Message message,
                            double alpha, int accent, double viewLeft, double viewWidth) {
        double size = avatarSize;
        double gap = fontPx * .35;
        double x = message.right ? bubbleX + message.width + gap : bubbleX - size - gap;
        double y = bubbleY;
        // 气泡被夹到占满整行时（极窄视口）头像已经没地方放了。画半个出去比不画更难看，索性不画。
        if (x < viewLeft - .5 || x + size > viewLeft + viewWidth + .5) return;

        if (HudConfig.cappellaAvatarSource == 2) {
            int index = message.right ? avatarRight : leftAvatar(message.avatarSlot);
            if (drawSheetCell(AVATAR_SHEET, AVATAR_COLS, AVATAR_ROWS, AVATAR_CELL, index,
                    x, y, size, size * .5, alpha)) {
                return;
            }
        } else if (HudConfig.cappellaAvatarSource == 0) {
            // 右侧＝自己，用登录账号的头像；左侧＝对方，用歌曲封面。账号头像没就绪就退回封面。
            ITextureObject texture = message.right ? AccountAvatar.textureOrNull() : null;
            if (texture == null) texture = coverTexture();
            if (texture != null) {
                bindTextureForDraw(texture);
                this.roundedRectTextured(x, y, size, size, size * .5, (float) alpha);
                releaseTextureAfterDraw();
                return;
            }
        }
        FullscreenLyricStage.bar(x, y, size, size, size * .5, accent, alpha * .5);
    }

    /**
     * 表情气泡：只画一张图，没有底板。
     *
     * <p>盒子尺寸在排版时就按「放大到最大」定死，随进度变化的只有盒内那张图的缩放。让盒子本身
     * 变大会把后面所有气泡往下推，镜头就得每帧去追一个正在动的目标，看起来像抽搐。</p>
     */
    private void drawEmojiBubble(double bubbleX, double bubbleY, Message message, double alpha,
                                 double eased, boolean active, int accent,
                                 double viewLeft, double viewWidth) {
        double box = Math.min(message.width, message.height);
        double target = active ? 1.0 : EMOJI_IDLE_RATIO;
        double scale = target * (EMOJI_ENTER_SCALE + (1.0 - EMOJI_ENTER_SCALE) * eased);
        double size = box * FullscreenLyricStage.clamp(scale, .1, 1.0);
        double cx = bubbleX + message.width * .5;
        double cy = bubbleY + message.height * .5;
        if (!drawSheetCell(EMOJI_SHEET, EMOJI_COLS, EMOJI_ROWS, EMOJI_CELL, message.emoji,
                cx - size * .5, cy - size * .5, size, size * .18, alpha)) {
            // 贴图还没上传完（第一次用到的那一两帧）：画个淡圆占位，别让这一条看起来是空的。
            FullscreenLyricStage.bar(cx - size * .5, cy - size * .5, size, size, size * .5,
                    accent, alpha * .12);
        }
        if (message.showAvatar) {
            drawAvatar(bubbleX, bubbleY, message, alpha, accent, viewLeft, viewWidth);
        }
    }

    /**
     * 画精灵图里的一格。
     *
     * @return 是否真的画出来了；贴图还没加载好时返回 {@code false}，由调用方兜底
     */
    private boolean drawSheetCell(Location sheet, int cols, int rows, int cell, int index,
                                  double x, double y, double size, double radius, double alpha) {
        if (size <= 1.0 || alpha <= .01) return false;
        ITextureObject texture = TextureManager.getInstance().getTexture(sheet);
        if (texture == null) {
            // 首次使用时按需加载，这一帧先兜底，下一帧就能用上。
            TextureManager.getInstance().loadTexture(sheet, new DynamicTexture(sheet));
            return false;
        }
        int span = Math.max(1, cols * rows);
        int safe = ((index % span) + span) % span;
        double sheetWidth = cols * (double) cell;
        double sheetHeight = rows * (double) cell;
        // 四周各内缩一整个纹素。半个纹素只够挡住"正好在边界上"的采样，放大显示时线性过滤的采样点
        // 会越过边界更多，相邻格子的画面就会沿着边缘渗进来 —— 表现就是表情/头像边上多出一条别人
        // 的碎片。表情图另外还留了 8 px 透明边，两道保险叠在一起才彻底看不到渗色。
        double texX = ((safe % cols) * (double) cell + 1.0) / sheetWidth;
        double texY = ((safe / cols) * (double) cell + 1.0) / sheetHeight;
        double u = (cell - 2.0) / sheetWidth;
        double v = (cell - 2.0) / sheetHeight;
        bindTextureForDraw(texture);
        this.roundedRectTextured(x, y, size, size, texX, texY, u, v, radius, (float) alpha);
        releaseTextureAfterDraw();
        return true;
    }

    /** 当前歌曲封面的贴图；没有歌 / 贴图还没就绪时返回 {@code null}。 */
    private static ITextureObject coverTexture() {
        Music music = CloudMusic.getSnapshot().music;
        if (music == null) return null;
        return TextureManager.getInstance().getTexture(
                CloudMusic.preferredCoverLocation(music, music.getCoverLocation()));
    }

    private void bindTextureForDraw(ITextureObject texture) {
        // 必须先把颜色复位成白：RQT 会乘上当前的 GL 颜色，上一次绘制留下的主题色会把整张图染一遍。
        GL11.glColor4f(1f, 1f, 1f, 1f);
        api.getGLStateManager().enableTexture2D();
        api.getGLStateManager().bindTexture(texture.getGlTextureId());
        texture.linearFilter();
    }

    private void releaseTextureAfterDraw() {
        api.getGLStateManager().bindTexture(0);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * 按歌曲重算头像分配：右侧（主唱）保留一张，左侧从余下的里取，保证两侧不撞脸。
     *
     * <p>和参考实现 {@code pickStableBuiltinAvatarImage} 同一套思路：种子取歌曲，所以同一首歌
     * 每次进来都是同一组头像，换歌才换人。</p>
     */
    private void refreshAvatarPick() {
        Music music = CloudMusic.getSnapshot().music;
        String key = music == null ? "cappella" : (music.getName() + "|" + music.getId());
        int hash = hash32(key);
        avatarRight = (hash >>> 1) % AVATAR_COUNT;
        avatarOffset = (hash >>> 9) % Math.max(1, AVATAR_COUNT - 1);
    }

    private int leftAvatar(int slot) {
        int pool = Math.max(1, AVATAR_COUNT - 1);
        int index = (avatarOffset + Math.max(0, slot)) % pool;
        return index >= avatarRight ? index + 1 : index;
    }

    private static int hash32(String text) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 16777619;
        }
        return hash & 0x7FFFFFFF;
    }

    /** 由行的文本与时间戳派生的稳定随机数 0..1。同一行每次重排都得到同一个值，窗口滑动也不会变。 */
    private static double hash01(LyricLine line, int salt) {
        String text = line.lyric == null ? "" : line.lyric;
        int hash = hash32(text + "|" + line.timestamp + "|" + salt);
        return ((hash >>> 6) & 0xFFFFFF) / (double) 0x1000000;
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine center, double positionMs,
                         double viewWidth, double viewHeight) {
        messages.clear();
        List<LyricLine> lines = window(center, positionMs, WINDOW_BEFORE, WINDOW_AFTER);
        if (lines.isEmpty()) return;

        // 和其它样式一致：最高的一条气泡装不进视口就降一档字号重排，而不是让它被裁。
        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * .82;
        for (int attempt = 0; attempt < 7; attempt++) {
            if (layout(lines, targetPx, viewWidth, viewHeight)) return;
            targetPx *= .85;
            if (targetPx < 9) break;
        }
        layout(lines, targetPx, viewWidth, viewHeight);
    }

    /** 一个字形的宽度（已乘倍率）；字形还没上传时用估算值兜底。 */
    private double glyphWidth(String grapheme) {
        return FullscreenLyricStage.glyphWidth(font, grapheme, fontScale, fontPx);
    }

    /** @return true 表示这一档字号下每条气泡都装得进视口 */
    private boolean layout(List<LyricLine> lines, double targetPx,
                           double viewWidth, double viewHeight) {
        messages.clear();
        font = FullscreenLyricStage.fontAtMost(targetPx);
        fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
        fontPx = targetPx;
        avatarSize = Math.max(12.0, fontPx * 1.55);
        refreshAvatarPick();
        // 这一档字号还没生成过任何字形时 fontHeight 是负一，行高只能先用兜底值，得等下一帧重排。
        metricsPending = font.getFontHeight() < font.sizePx * .4;

        double emojiChance = HudConfig.cappellaEmojiEnabled
                ? FullscreenLyricStage.clamp(HudConfig.cappellaEmojiChance, 0.0, .45) : 0.0;
        // 概率 + 间隔 + 配额三件套，缺一个就会出现「连着刷五张」。
        int emojiBudget = Math.max(1, (int) (lines.size() * EMOJI_MAX_RATIO));
        int emojiCount = 0;
        int linesSinceEmoji = EMOJI_MIN_GAP_LINES;

        double bubbleRatio = FullscreenLyricStage.clamp(HudConfig.cappellaBubbleWidth, .45, .95);
        // 上限不只看比例，还要夹到「扣掉头像与留白之后真正还剩多少宽」：
        // 极窄视口下光按比例算会得到比可用宽度还大的气泡，右侧那条的起点就变成负数、直接越界。
        double usable = Math.max(fontPx * 3, viewWidth - avatarSize - fontPx * 1.2);
        double maxBubble = Math.min(usable,
                Math.max(fontPx * 4, viewWidth * bubbleRatio - avatarSize - fontPx));
        double padX = fontPx * .55;
        double padY = fontPx * .38;
        double lineHeight = FullscreenLyricStage.lineHeightOf(font, fontScale) * 1.14;
        double gapBetween = fontPx * .55;

        double y = 0;
        double tallest = 0;
        long previousEnd = Long.MIN_VALUE;
        LyricLine previous = null;
        boolean previousRight = false;

        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            String text = line.lyric == null ? "" : line.lyric.trim();
            if (text.isEmpty()) continue;
            if (!FullscreenLyricStage.glyphsReady(font, text)) metricsPending = true;

            if (previousEnd != Long.MIN_VALUE && line.timestamp - previousEnd >= INTERLUDE_GAP_MS) {
                Message interlude = new Message();
                interlude.line = line;
                interlude.interlude = true;
                interlude.right = false;
                interlude.showAvatar = false;
                Row row = new Row();
                row.chars = FullscreenLyricStage.graphemes(INTERLUDE_TEXT);
                row.graphemeStart = 0;
                // 间奏那条是整串一次画出来的（不走逐字），所以必须整串量：整串量才带上字距调整。
                // 只有量不到（字形还没上传）时才退回逐字估算。
                row.width = font.getStringWidthD(INTERLUDE_TEXT) * fontScale;
                if (row.width <= .01) {
                    for (int c = 0; c < row.chars.size(); c++) {
                        row.width += glyphWidth(row.chars.get(c));
                    }
                    metricsPending = true;
                }
                row.y = 0;
                interlude.rows.add(row);
                interlude.width = row.width + padX * 2;
                interlude.height = lineHeight + padY * 2;
                interlude.x = (viewWidth - interlude.width) * .5;
                interlude.y = y;
                messages.add(interlude);
                y += interlude.height + gapBetween;
            }

            // 有对唱标记时按对唱槽位分左右，没有才退回逐条交替：同一个演唱者始终待在同一侧，
            // 头像也就跟着稳定下来（参考实现按 TTML 的 agentId 分，duetSlot 是我们这边的等价物）。
            boolean right = line.duetSlot >= 0 ? line.duetSlot == 1 : (indexOf(line) & 1) == 1;
            int avatarSlot = Math.max(0, line.duetSlot);

            if (emojiChance > 0.0 && linesSinceEmoji >= EMOJI_MIN_GAP_LINES
                    && emojiCount < emojiBudget && hash01(line, 0x5EED) < emojiChance) {
                Message emoji = new Message();
                emoji.line = line;
                emoji.right = right;
                emoji.avatarSlot = avatarSlot;
                emoji.emoji = (int) (hash01(line, 0x1CDE) * EMOJI_COUNT);
                emoji.showAvatar = previous == null || right != previousRight;
                double box = fontPx * EMOJI_BOX_RATIO;
                emoji.width = box;
                emoji.height = box;
                emoji.x = right
                        ? viewWidth - box - avatarSize - fontPx * .5
                        : avatarSize + fontPx * .5;
                emoji.x = FullscreenLyricStage.clamp(emoji.x, 0.0, Math.max(0.0, viewWidth - box));
                emoji.y = y;
                messages.add(emoji);
                tallest = Math.max(tallest, box);
                y += box + gapBetween;
                emojiCount++;
                linesSinceEmoji = 0;
                // 表情已经顶掉了「这一侧刚说过话」，紧跟着的那条歌词就不再重复画一次头像。
                previous = line;
                previousRight = right;
            }
            linesSinceEmoji++;

            Message message = new Message();
            message.line = line;
            message.right = right;
            message.avatarSlot = avatarSlot;
            message.showAvatar = previous == null || right != previousRight
                    || FullscreenLyricStage.graphemeCount(text) > 12;

            List<String> chars = FullscreenLyricStage.graphemes(text);
            double contentWidth = maxBubble - padX * 2;
            int cursor = 0;
            double widest = 0;
            double rowY = 0;
            while (cursor < chars.size()) {
                int take = 0;
                double rowWidth = 0;
                while (cursor + take < chars.size()) {
                    double advance = glyphWidth(chars.get(cursor + take));
                    if (take > 0 && rowWidth + advance > contentWidth) break;
                    rowWidth += advance;
                    take++;
                }
                if (take <= 0) take = 1;
                Row row = new Row();
                row.chars = new ArrayList<String>(chars.subList(cursor, cursor + take));
                row.graphemeStart = cursor;
                row.width = rowWidth;
                row.y = rowY;
                message.rows.add(row);
                widest = Math.max(widest, rowWidth);
                rowY += lineHeight;
                cursor += take;
            }
            message.width = widest + padX * 2;
            message.height = rowY + padY * 2;
            message.x = right
                    ? viewWidth - message.width - avatarSize - fontPx * .5
                    : avatarSize + fontPx * .5;
            // 兜底：无论上面怎么算，气泡都必须整条落在视口里。
            message.x = FullscreenLyricStage.clamp(message.x, 0.0,
                    Math.max(0.0, viewWidth - message.width));
            message.y = y;
            messages.add(message);
            tallest = Math.max(tallest, message.height);

            y += message.height + gapBetween;
            previousEnd = FullscreenLyricStage.lineEndMs(line);
            previous = line;
            previousRight = right;
        }
        return tallest <= viewHeight * MAX_BUBBLE_HEIGHT_RATIO;
    }

    /** 这一行对应气泡的<b>底边</b>在文档坐标里的位置；不在这批气泡里时返回 NaN。 */
    private double docBottomOf(LyricLine line) {
        Message message = messageOf(line);
        return message == null ? Double.NaN : message.y + message.height;
    }

    /**
     * 镜头该把哪条气泡的底边贴到锚点上。
     *
     * <p>当前行可能因为是空行/间奏而不在这批气泡里，那时退到最后一条已经开始的气泡，
     * 而不是一律退回第一条 —— 后者会让画面在间奏时突然甩到窗口开头。</p>
     */
    private double focusBottom(LyricLine currentLine, double positionMs) {
        Message focus = focusMessage(currentLine, positionMs);
        if (focus != null) return focus.y + focus.height;
        return messages.isEmpty() ? 0 : messages.get(0).y + messages.get(0).height;
    }

    private double focusHeight(LyricLine currentLine, double positionMs) {
        Message focus = focusMessage(currentLine, positionMs);
        return focus == null ? 0 : focus.height;
    }

    private Message focusMessage(LyricLine currentLine, double positionMs) {
        Message direct = messageOf(currentLine);
        if (direct != null) return direct;
        Message nearest = null;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!message.interlude && message.emoji < 0 && message.line != null
                    && message.line.timestamp <= positionMs) {
                nearest = message;
            }
        }
        return nearest;
    }

    private Message messageOf(LyricLine line) {
        if (line == null) return null;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            // 表情气泡和它后面那条歌词共用同一个 LyricLine，这里必须跳过它，
            // 否则镜头会去对齐表情的底边，当前那句歌词反而被顶到锚点下面。
            if (message.line == line && !message.interlude && message.emoji < 0) return message;
        }
        return null;
    }
}
