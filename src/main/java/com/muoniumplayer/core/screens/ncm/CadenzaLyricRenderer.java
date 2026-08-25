package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.spring.SpringAnimation;
import com.muoniumplayer.core.rendering.animation.spring.SpringParams;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 全屏歌词页的「律动词云」样式（{@link FullscreenLyricStyle#CADENZA}）。
 *
 * <p>与经典样式的根本区别：屏幕上只有当前这一行，并且它被拆成若干字词散布在画面里，
 * 镜头跟着正在唱的那个词走。经典样式是「一整首歌在滚动」，这个样式是「一行歌词在表演」。</p>
 *
 * <h3>三条工程约束（改这个类前必读）</h3>
 * <ol>
 *   <li><b>布局只在换行时重算。</b>字词落点、进出场动画种类、配色全部由行本身决定（种子取自
 *       行时间戳与文本），因此同一行每帧画出来都一样。要是每帧重掷随机数，字词会原地乱抖。</li>
 *   <li><b>动画一律与帧率无关。</b>进场进度来自播放毫秒，退场进度来自墙上时钟，
 *       镜头用 {@link SpringAnimation} 按真实 dt 推进。绝不用「每帧乘一个系数」那种写法。</li>
 *   <li><b>画之前必须开裁剪。</b>歌词视口只是播放器的右半边，左半边是封面与控件。
 *       字词带着位移和放大在飞，没有裁剪一定会糊到封面上。</li>
 * </ol>
 *
 * <p>「律动」的驱动源是<b>真实的实时音频包络</b>：{@link com.muoniumplayer.core.ncm.music.PlaybackPulse}
 * 在正在响的那一路信号上挂了一个峰值跟随器，做 onset 检测后给出 0..1 的节拍脉冲。参考实现的
 * {@code AudioPlayer.BEAT} 就是同一个东西，只是它自己解码所以能直接在解码回调里算。</p>
 *
 * <p>它<b>不是唯一来源</b>：实时包络这条路一旦不可用（音频图异常后永久降级），或者歌暂停了，
 * 脉冲会退回「当前字词自己的演唱进度」。两者取较大值，因此画面永远不会彻底不动。
 * 配色仍然用播放器主题强调色——项目没有封面调色板。</p>
 */
final class CadenzaLyricRenderer implements FullscreenLyricRenderer,
        SharedRenderingConstants, SharedConstants {

    /** 字词在唱到之前多久开始进场。 */
    private static final long ENTER_LEAD_MS = 260L;

    /** 进场动画时长。 */
    private static final long ENTER_DURATION_MS = 520L;

    /** 换行时旧字词的退场时长（秒）。 */
    private static final double EXIT_SECONDS = 0.5;

    /** 一帧最多推进这么多秒。卡顿一下不该让镜头瞬移。 */
    private static final double MAX_FRAME_SECONDS = 0.05;

    /**
     * 镜头跟随强度。1.0 = 焦点字词被<b>精确拉到视图中心</b>，与参考实现一致
     * （参考里镜头目标就是 {@code -focus.homeX} / {@code -focus.homeY}，没有任何衰减）。
     *
     * <p>原来是 0.25 的部分跟随，配一套「把舞台缩小 + 把镜头行程夹住」的保护，目的是让整行永远不出视口。
     * 这套保护本身就是<b>字被裁掉与叠字的来源</b>：</p>
     * <ul>
     *   <li>部分跟随意味着<b>正在唱的那一块也不在中心</b>。它偏在一侧，另一侧的字照样被裁 ——
     *       想读的那一块和读不到的那一块同时存在，比"远处的字滑出画面"糟得多。</li>
     *   <li>为了兜住这件事，舞台被缩掉 20%，字号档位再低一档，九块字挤在更小的舞台上 → 叠字。</li>
     * </ul>
     *
     * <p>全跟随反而不需要这套保护：焦点字恒在正中，<b>该读的那一块永远可见</b>，其余字词随镜头进出画面
     * 正是词云该有的表现（参考实现连裁剪框都没有，字直接飘出屏幕）。</p>
     */
    private static final double CAMERA_DAMP = 1.0;

    /**
     * 舞台四周留给视口边界的空白。
     *
     * <p>字词盒子的落点已经把「放大余量」算进去了（{@link #boxReserve}），这一档空白是给发光那几圈
     * 环形偏移用的：发光半径最大 {@code 1 + 2.4} 再乘 1.7，约 6 像素 —— 所以下限就是 6，
     * 而且<b>不能随字号缩小</b>，发光半径是绝对像素、跟字号无关。</p>
     *
     * <p>上限 12 是留白的观感值。小视口上按比例取，不然 12 像素的双边留白在两百像素宽的歌词区里
     * 就吃掉一成宽度，本来就排不开的行会被逼着再降一档字号。</p>
     */
    private static final double VIEW_MARGIN_MIN = 6.0;
    private static final double VIEW_MARGIN_MAX = 12.0;

    /** 见 {@link #VIEW_MARGIN_MIN}。传视口的宽或高，两个方向各算一次。 */
    private static double viewMargin(double viewSize) {
        return clamp(viewSize * .05, VIEW_MARGIN_MIN, VIEW_MARGIN_MAX);
    }

    /**
     * 舞台中心在视口里的纵向位置。
     *
     * <p>偏上是有意的：下面要留给进度条与控件。它同时被 {@link #rebuild}（算舞台高度）与
     * {@link #render}（算绘制中心）使用，两处<b>必须</b>是同一个数，否则舞台的上下边界会算错。</p>
     */
    private static final double STAGE_CENTER_Y = .46;

    /** 一行最多拆成这么多块，再多就并成更长的块。 */
    private static final int MAX_TOKENS = 9;

    /**
     * 字号的<b>展示空间约束</b>：所有块横排一遍的总宽度不得超过舞台宽度的这个比例。
     *
     * <p>参考实现的字号只由屏幕宽度决定：{@code px = clamp(scrW * .030, 22, 64)}，而 {@code px} 就是
     * 可视字高。把它的典型排版代进去 —— 全屏舞台、八九块、每块四五个字母 —— 横排一遍大约占掉屏幕宽度的
     * 四分之三，剩下的四分之一是螺旋散布时向两侧扩张的余地。</p>
     *
     * <p>这里不能直接抄那个 {@code .030}：它假设舞台是 16:9 的整屏，而歌词视口只有播放器的半边宽、
     * 形状还偏高，同一个系数换到这里算出来的字号毫无意义。所以抄它的<b>结果</b>——「横排一遍占舞台宽度的
     * 多少」。这个比例与舞台形状无关，可以直接用本项目真实量出来的字宽去算。</p>
     *
     * <p>它是<b>叠字的真正闸门</b>。原来的约束只有「盒子面积之和不超过舞台面积的一半」，而歌词视口又高又窄：
     * 九块 25 像素高的字，面积加起来确实不到舞台一半，可横排要 290 像素、舞台只有 211 像素宽，
     * 必然被逼着堆成两三行 —— 堆出来就是叠字。</p>
     */
    private static final double REFERENCE_ROW_FILL = .78;

    /** 小于这个面积的重叠当作没有重叠：落点是浮点算出来的，不留容差会为了 1e-13 白降一档字号。 */
    private static final double OVERLAP_EPSILON = 1e-6;

    /** 排布尝试次数。每次换一种顺序或锚点密度，取重叠最小的那次。 */
    private static final int LAYOUT_ATTEMPTS = 3;

    /** 整层开合的时间常数（秒）。 */
    private static final double OPEN_TAU = .11;

    /**
     * 节拍平滑的时间常数：上行快、下行慢。
     *
     * <p>对称的平滑会把鼓点抹成一条缓坡——冲击还没到顶就开始往回走。攻击给 28 ms 是为了让画面
     * 「打」在拍上，释放给 140 ms 是为了让它落回去时是滑下来而不是掉下来。</p>
     */
    private static final double BEAT_ATTACK_TAU = .028;
    private static final double BEAT_RELEASE_TAU = .14;

    /** 实时包络不可用（或暂停）时，字词进度脉冲能顶到的上限。 */
    private static final double WORD_PULSE_FLOOR = .5;

    /** 正在唱的字词的呼吸幅度，与参考实现一致。 */
    private static final double BREATH_STRENGTH = .03;

    /** 正在唱的字词的提亮量，与参考实现一致。 */
    private static final double ACTIVE_BRIGHTEN = .55;

    /** hero / 普通字词的基础尺寸。 */
    private static final double HERO_BASE_SCALE = 1.10;
    private static final double NORMAL_BASE_SCALE = 1.02;

    /**
     * 旋转超过这个角度就不画发光。
     *
     * <p>发光是靠把同一串文本按环形偏移多画几遍做出来的，整体被旋转之后那几圈偏移会跟着歪，
     * 看起来是重影而不是光。参考实现同样在这个角度上掐掉。</p>
     */
    private static final double GLOW_ROTATION_LIMIT = 25.0;

    /**
     * 字词盒子在布局里预留的放大余量。
     *
     * <p>它同时被字号档位选择与落点夹取使用：{@link #pickFontTierIndex} 用它判断「这一档放得下吗」，
     * {@link #place} 用它把落点夹进舞台。两处必须用同一个数，否则会出现
     * 「档位说放得下、夹取却把它推出舞台」的矛盾。</p>
     *
     * <p>返回值不是常数，而是<b>按当前配置算出来的真实峰值倍数</b>：基础尺寸 × 呼吸 × 逐字放大。
     * 写死成常数的话，用户把「逐字放大程度」拉到上限时盒子就比实际画出来的小，
     * 落在舞台边缘的字词会在脉冲顶点被裁掉一角；而按上限写死又会让所有人都白白用小一号的字。</p>
     */
    static double boxReserve(boolean hero) {
        double base = hero ? HERO_BASE_SCALE : NORMAL_BASE_SCALE;
        double word = HudConfig.currentLyricEffectsEnabled
                ? Math.max(0.0, Math.min(.28, HudConfig.currentWordScale))
                : 0.0;
        return base * (1.0 + BREATH_STRENGTH) * (1.0 + word);
    }

    /**
     * 字词碰撞盒在文本之外撑开的留白（横向/纵向都是<b>单侧</b>的量）。
     *
     * <p>两块互不重叠时的实际净空是这个值的两倍。比例项按字高走，让大字有相称的呼吸感；
     * 绝对下限是给<b>发光</b>留的 —— 发光靠环形偏移多画几遍做出来，半径是绝对像素、与字号无关
     * （最大约 6px，见 {@code VIEW_MARGIN_MIN} 那段）。只按比例留白时，小字号下 {@code .22 × 14 ≈ 3px}
     * 的净空压根盖不住 6px 的光晕，隔壁块的光直接糊到这块字上 —— 看起来就是"字叠在一起"。</p>
     *
     * <p>纵向比例比横向小：字高本身已经包含行距，字形墨迹只占其中一部分，纵向的视觉净空天生比
     * 名义值大；横向没有这层富余。</p>
     */
    private static final double BOX_PAD_X_FACTOR = .30;
    private static final double BOX_PAD_Y_FACTOR = .22;
    private static final double BOX_PAD_MIN = 5.0;

    /** 见 {@link #BOX_PAD_X_FACTOR}。传字高，横向单侧留白。 */
    private static double boxPadX(double height) {
        return Math.max(height * BOX_PAD_X_FACTOR, BOX_PAD_MIN);
    }

    /** 见 {@link #BOX_PAD_X_FACTOR}。传字高，纵向单侧留白。 */
    private static double boxPadY(double height) {
        return Math.max(height * BOX_PAD_Y_FACTOR, BOX_PAD_MIN);
    }

    /**
     * 相邻两块在锚点场里的间距。
     *
     * <p>必须覆盖<b>两侧</b>的盒子留白：只算一侧的话，锚点场本身就是互相重叠的，全靠
     * {@link #place} 的螺旋一个个把它们推开 —— 推不开就是叠字。锚点场自己不重叠，螺旋才只是
     * "微调"而不是"抢救"。</p>
     */
    private static double blockGap(CFontRenderer font) {
        double height = font.getHeight();
        return font.getStringWidthD(" ") * .6 + boxPadX(height) * 2.0;
    }

    /** 进出场动画种类。 */
    private enum Anim {
        FADE, JUMP, DROP, SPIN, BOOMERANG, SLIDE, ZOOM, SWIRL
    }

    /**
     * 字号档位：{hero 字体, 普通字体}。窄视口从后面的档位里挑。
     *
     * <p>最后四档是<b>兜底档</b>，正常尺寸的播放器永远走不到：
     * {@link #rebuild} 会在第一个排得开的档位上停下，只有把播放器缩到歌词区不足百来像素宽时，
     * 前面几档才会全部排不开。它们存在的唯一理由是「宁可字小，也不要叠字」——
     * 没有这两档时，极窄视口下九个块在 66×118 的舞台上是真的塞不下的。</p>
     */
    private static CFontRenderer[][] fontTiers() {
        return new CFontRenderer[][] {
                {FontManager.pf65bold, FontManager.pf50bold},
                {FontManager.pf50bold, FontManager.pf40bold},
                {FontManager.pf40bold, FontManager.pf34bold},
                {FontManager.pf34bold, FontManager.pf28bold},
                {FontManager.pf28bold, FontManager.pf20bold},
                {FontManager.pf20bold, FontManager.pf14bold},
                {FontManager.pf14bold, FontManager.pf12bold},
                {FontManager.pf12bold, FontManager.pf10bold}
        };
    }

    private static final class Token {
        String text;
        long startMs;
        long endMs;
        CFontRenderer font;
        double width;
        double height;
        double homeX;
        double homeY;
        boolean hero;
        int color;
        /** 折行算出来的理想落点。实际落点由 {@link #place} 从这里出发找空位。 */
        double anchorX;
        double anchorY;
        Anim enter = Anim.FADE;
        Anim exit = Anim.FADE;
        double direction = 1.0;
        double angle;
    }

    /** 一次绘制用到的位姿。复用同一个实例，避免每字词都 new。 */
    private static final class Pose {
        double dx;
        double dy;
        double rotation;
        double scale;
        double enterAlpha;
        double exitAlpha;

        void reset() {
            dx = 0;
            dy = 0;
            rotation = 0;
            scale = 1;
            enterAlpha = 1;
            exitAlpha = 0;
        }
    }

    private final List<Token> tokens = new ArrayList<Token>();
    private final List<Token> outgoing = new ArrayList<Token>();
    private final Pose pose = new Pose();
    private final CadenzaDecorLayer decor = new CadenzaDecorLayer();

    private final SpringAnimation cameraX = new SpringAnimation(new SpringParams(1.0, 6.0, 30.0, false));
    private final SpringAnimation cameraY = new SpringAnimation(new SpringParams(1.0, 6.0, 30.0, false));

    private LyricLine laidOutLine;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;

    /** 最近一次布局用的舞台尺寸。 */
    private double stageWidth;
    private double stageHeight;

    private long lastFrameNanos;
    private long exitStartNanos;
    private double elapsedSeconds;

    /** 整层的开合量 0..1。{@link #reset()} 把它归零，于是每次重开歌词页都会重新滑进来一次。 */
    private double openK;

    /** 平滑后的节拍强度，背景与装饰共用。 */
    private double beatSmooth;

    /** 换歌/换歌词时把状态清干净，否则上一首的字词会残留一帧。 */
    public void reset() {
        tokens.clear();
        outgoing.clear();
        laidOutLine = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        cameraX.setPosition(0);
        cameraY.setPosition(0);
        openK = 0;
        beatSmooth = 0;
        decor.reset();
        // 归零而不是记当前时间：下一帧的 dt 会被算成 0，整层从「完全收起」开始推进。
        lastFrameNanos = 0L;
    }

    /**
     * 画一帧。
     *
     * @param currentLine 当前行，{@code null} 表示这一刻没有歌词
     * @param positionMs  本帧的播放毫秒，与经典样式共用同一份快照
     */
    public void render(double viewLeft, double viewTop, double viewWidth, double viewHeight,
                       float alpha, double positionMs, LyricLine currentLine) {
        if (viewWidth <= 4 || viewHeight <= 4 || alpha <= 0.004f) return;

        long now = System.nanoTime();
        double frameSeconds = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        if (frameSeconds > MAX_FRAME_SECONDS) frameSeconds = MAX_FRAME_SECONDS;
        if (frameSeconds < 0) frameSeconds = 0;
        elapsedSeconds += frameSeconds;

        boolean viewportChanged = Math.abs(viewWidth - laidOutWidth) >= 2.0
                || Math.abs(viewHeight - laidOutHeight) >= 2.0;
        if (currentLine != laidOutLine || viewportChanged) {
            if (currentLine != laidOutLine) {
                outgoing.clear();
                outgoing.addAll(tokens);
                exitStartNanos = now;
            }
            rebuild(currentLine, viewWidth, viewHeight);
            laidOutWidth = viewWidth;
            laidOutHeight = viewHeight;
        }

        // 整层开合：目标恒为 1，所以只有 reset() 之后会看到一次「从下面滑上来」。
        openK += (1.0 - openK) * (1.0 - Math.exp(-frameSeconds / OPEN_TAU));
        if (openK > .998) openK = 1.0;
        double opened = smoothstep(openK);
        double slide = (1.0 - opened) * viewHeight;

        // 「律动」强度：优先用实时音频包络，退回当前字词自己的演唱进度。两者取大值，
        // 于是暂停或降级时画面仍然会跟着歌词起伏，而不是彻底不动。
        Token focus = focusToken(positionMs);
        double wordPulse = 0;
        if (focus != null && focus.endMs > focus.startMs) {
            double progress = (positionMs - focus.startMs) / (double) (focus.endMs - focus.startMs);
            if (progress >= 0 && progress <= 1) wordPulse = Math.sin(Math.PI * progress);
        }
        double audioBeat = PlaybackPulse.isAvailable() ? PlaybackPulse.beat() : 0.0;
        double beatTarget = Math.max(audioBeat, wordPulse * WORD_PULSE_FLOOR);
        double beatTau = beatTarget > beatSmooth ? BEAT_ATTACK_TAU : BEAT_RELEASE_TAU;
        beatSmooth += (beatTarget - beatSmooth) * (1.0 - Math.exp(-frameSeconds / beatTau));
        if (beatSmooth < .001) beatSmooth = 0;

        // 不夹取：夹住之后焦点字词就不在中心了，"该读的那一块一定可见"这条保证会失效。
        // 参考实现同样是把 -focus.home 直接当目标（它连裁剪框都没有）。
        double targetX = focus == null ? 0 : -focus.homeX * CAMERA_DAMP;
        double targetY = focus == null ? 0 : -focus.homeY * CAMERA_DAMP;
        cameraX.setTargetPosition(targetX);
        cameraY.setTargetPosition(targetY);
        cameraX.update(frameSeconds);
        cameraY.update(frameSeconds);

        double centerX = viewLeft + viewWidth * .5 + cameraX.getCurrentPosition();
        double centerY = viewTop + viewHeight * STAGE_CENTER_Y + cameraY.getCurrentPosition();

        // 裁剪必须在平移之前建立：模板是靠画一个矩形写进去的，跟着平移会把裁剪框一起挪走。
        StencilClipManager.beginClip(new Runnable() {
            public void run() {
                Rect.draw(viewLeft, viewTop, viewWidth, viewHeight, -1);
            }
        });
        ScissorClipManager.begin(viewLeft, viewTop, viewWidth, viewHeight);
        boolean sliding = slide > .5;
        if (sliding) {
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().translate(0, slide, 0);
        }
        try {
            float layerAlpha = (float) (alpha * opened);
            decor.render(viewLeft, viewTop, viewWidth, viewHeight, elapsedSeconds,
                    beatSmooth, PlaybackPulse.loudness(), layerAlpha);

            if (!outgoing.isEmpty()) {
                double exitProgress = (now - exitStartNanos) / 1.0e9 / EXIT_SECONDS;
                if (exitProgress >= 1) {
                    outgoing.clear();
                } else {
                    drawTokens(outgoing, positionMs, centerX, centerY, layerAlpha, exitProgress);
                }
            }
            drawTokens(tokens, positionMs, centerX, centerY, layerAlpha, 0);
        } finally {
            if (sliding) api.getGLStateManager().popMatrix();
            ScissorClipManager.end();
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();
        }
    }

    // ==================== 布局 ====================

    /**
     * 舞台宽度 = 视口宽度减两侧留白，<b>不再为镜头行程缩档</b>。
     *
     * <p>之前这里除以 {@code 1 + CAMERA_DAMP}，是为了配合部分跟随的镜头保证"整行永远不出视口"。
     * 现在镜头是全跟随（见 {@link #CAMERA_DAMP}），焦点字词恒在正中，该读的那一块永远可见，
     * 舞台因此可以用满视口 —— 直接多出 20% 的可用宽高，字号档位相应能高一档，
     * 而字词之间也有了真正排得开的空间。参考实现同样是把整块可视区域当舞台。</p>
     */
    private static double stageWidth(double viewWidth) {
        return Math.max(40.0, viewWidth - viewMargin(viewWidth) * 2);
    }

    /**
     * 舞台高度。
     *
     * <p>舞台中心是偏上的（{@link #STAGE_CENTER_Y}）：上方可用高度比下方少，所以取两者的<b>较小值</b>
     * 来定半高，否则舞台会从视口顶上冒出去。同 {@link #stageWidth}，不再为镜头行程缩档。</p>
     */
    private static double stageHeight(double viewHeight) {
        double margin = viewMargin(viewHeight);
        double halfAbove = viewHeight * STAGE_CENTER_Y - margin;
        double halfBelow = viewHeight * (1.0 - STAGE_CENTER_Y) - margin;
        double usable = 2.0 * Math.min(halfAbove, halfBelow);
        return Math.max(40.0, Math.min(viewHeight * .74, usable));
    }

    private void rebuild(LyricLine line, double viewWidth, double viewHeight) {
        tokens.clear();
        laidOutLine = line;
        // 舞台尺寸先算：镜头夹取每帧都要用它，而这个方法后面有好几条提前 return 的路径。
        double stageWidth = stageWidth(viewWidth);
        double stageHeight = stageHeight(viewHeight);
        this.stageWidth = stageWidth;
        this.stageHeight = stageHeight;
        if (line == null) return;

        List<Token> raw = split(line);
        if (raw.isEmpty()) return;

        int heroIndex = pickHero(raw);
        raw.get(heroIndex).hero = true;

        assignColors(raw, heroIndex, line);

        // 字号档位不是选一次就算了：档位选择只是个面积估算，估算说「放得下」也可能真的排不开。
        // 所以从估出来的档位开始往小试，第一个能排到完全不重叠的档位就是它。宁可字小一号，
        // 也不要叠字——叠字是直接看不懂歌词，小一号只是没那么震撼。
        CFontRenderer[][] tiers = fontTiers();
        int startTier = pickFontTierIndex(raw, heroIndex, stageWidth, stageHeight);
        for (int t = startTier; t < tiers.length; t++) {
            applyTier(raw, heroIndex, tiers[t], stageWidth, stageHeight);
            double overlap = placeAll(raw, heroIndex, tiers[t], stageWidth, stageHeight);
            if (overlap <= OVERLAP_EPSILON) break;
        }

        Random random = new Random(seedOf(line));

        for (Token token : raw) {
            Anim[] pool = Anim.values();
            token.enter = pool[random.nextInt(pool.length)];
            token.exit = pool[random.nextInt(pool.length)];
            token.direction = random.nextBoolean() ? 1 : -1;
            token.angle = random.nextDouble() * Math.PI * 2;
        }

        tokens.addAll(raw);
    }

    /**
     * 把一行拆成若干块。
     *
     * <p>优先用逐字歌词自带的时间轴。中文逐字歌词经常一个字一个 {@code Word}，
     * 二十几个单字散开会变成一团噪点，所以相邻的短块会先并起来：合并后的块沿用
     * 第一个字的开始时间与最后一个字的结束时间。没有逐字时间轴时按空白切，
     * 并把整行时长均分（这也是参考实现的做法）。</p>
     */
    private List<Token> split(LyricLine line) {
        List<Token> raw = new ArrayList<Token>();
        if (line.hasTimedWords()) {
            for (LyricLine.Word word : line.words) {
                if (word == null || word.word == null) continue;
                String text = word.word.trim();
                if (text.isEmpty()) continue;
                Token token = new Token();
                token.text = text;
                token.startMs = word.timestamp;
                token.endMs = word.duration > 0 ? word.timestamp + word.duration : word.timestamp + 400L;
                raw.add(token);
            }
            return merge(raw);
        }

        String text = line.lyric == null ? "" : line.lyric.trim();
        if (text.isEmpty()) return raw;
        String[] parts = text.split("\\s+");
        long span = Math.max(1L, line.duration > 0 ? line.duration : 3000L);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            Token token = new Token();
            token.text = parts[i];
            token.startMs = line.timestamp + span * i / parts.length;
            token.endMs = line.timestamp + span * (i + 1) / parts.length;
            raw.add(token);
        }
        return merge(raw);
    }

    /** 相邻短块合并到不超过 {@link #MAX_TOKENS} 块，每块最多 5 个字符。 */
    private List<Token> merge(List<Token> raw) {
        if (raw.size() <= MAX_TOKENS) {
            boolean allShort = true;
            for (Token token : raw) {
                if (token.text.length() > 1) allShort = false;
            }
            if (!allShort) return raw;
        }

        int groups = Math.min(MAX_TOKENS, Math.max(1, raw.size()));
        int perGroup = (int) Math.ceil(raw.size() / (double) groups);
        perGroup = Math.max(1, Math.min(5, perGroup));

        List<Token> merged = new ArrayList<Token>();
        int index = 0;
        while (index < raw.size()) {
            Token head = raw.get(index);
            StringBuilder builder = new StringBuilder(head.text);
            long endMs = head.endMs;
            int count = 1;
            while (count < perGroup && index + count < raw.size() && builder.length() < 5) {
                Token next = raw.get(index + count);
                builder.append(next.text);
                endMs = next.endMs;
                count++;
            }
            Token token = new Token();
            token.text = builder.toString();
            token.startMs = head.startMs;
            token.endMs = Math.max(endMs, head.startMs + 1L);
            merged.add(token);
            index += count;
        }
        return merged;
    }

    /** 最长且靠中间的那一块当主角。 */
    private int pickHero(List<Token> raw) {
        int heroIndex = 0;
        double best = -1;
        for (int i = 0; i < raw.size(); i++) {
            double centreBias = 1.0 - Math.abs(i - (raw.size() - 1) * .5) / Math.max(1.0, raw.size());
            double score = raw.get(i).text.length() * .6 + centreBias * 1.4;
            if (score > best) {
                best = score;
                heroIndex = i;
            }
        }
        return heroIndex;
    }

    /**
     * 选字号档位的<b>起点</b>，返回档位下标。
     *
     * <p>歌词视口只有播放器的半边宽，65 号字碰上长句会一块都放不下。这里从大到小试：
     * 最宽的一块要能塞进舞台宽度，所有块的面积之和也不能超过舞台面积的一半（留出散布的空隙）。</p>
     *
     * <p>注意面积那一条只是个<b>估算</b>：面积够不等于摆得开（矩形装箱不是这么算的）。所以
     * {@link #rebuild} 拿到这个下标之后还会真的排一遍，排不开就继续往小降。以前这里是一锤定音的，
     * 于是「估算说放得下、实际排不开」直接表现为叠字。</p>
     *
     * <p>真正决定字号量级的是 {@link #REFERENCE_ROW_FILL} 那一条「横排一遍装得进舞台」——
     * 它是从参考实现的字号公式反推出来的展示空间约束，也是这里唯一一条形状无关的判据。</p>
     */
    private int pickFontTierIndex(List<Token> raw, int heroIndex,
                                  double stageWidth, double stageHeight) {
        CFontRenderer[][] tiers = fontTiers();
        double stageArea = stageWidth * stageHeight;
        for (int t = 0; t < tiers.length; t++) {
            CFontRenderer heroFont = tiers[t][0];
            CFontRenderer normalFont = tiers[t][1];
            // 和 placeAll() 完全相同的块间距，否则这里算出来的横排宽度和真正排布时的不是一回事。
            double gap = blockGap(normalFont);
            double widestBox = 0;
            double tallestBox = 0;
            double area = 0;
            double rowWidth = 0;
            for (int i = 0; i < raw.size(); i++) {
                boolean hero = i == heroIndex;
                CFontRenderer font = hero ? heroFont : normalFont;
                double width = font.getStringWidthD(raw.get(i).text);
                double height = font.getHeight();
                // 和 place() 完全相同的盒子尺寸，包括 hero 的放大余量。只比较文本宽度的话，
                // 一句 20 个字的长句会被判成「放得下」，随后夹取时又发现盒子比舞台还宽。
                double reserve = boxReserve(hero);
                widestBox = Math.max(widestBox, (width * .5 + boxPadX(height)) * reserve * 2);
                tallestBox = Math.max(tallestBox, (height * .5 + boxPadY(height)) * reserve * 2);
                area += (width + boxPadX(height) * 2) * height * 1.3;
                rowWidth += width + gap;
            }
            rowWidth = Math.max(0.0, rowWidth - gap);
            boolean fits = widestBox <= stageWidth * .96 && tallestBox <= stageHeight * .96
                    && area <= stageArea * .5
                    && rowWidth <= stageWidth * REFERENCE_ROW_FILL;
            if (fits || t == tiers.length - 1) return t;
        }
        return tiers.length - 1;
    }

    private void assignColors(List<Token> raw, int heroIndex, LyricLine line) {
        int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT) | 0xFF000000;
        int neutral = 0xFFF3F0FA;
        int accentSoft = mixRgb(accent, neutral, .45);
        long seed = seedOf(line);
        for (int i = 0; i < raw.size(); i++) {
            Token token = raw.get(i);
            if (i == heroIndex) {
                token.color = accent;
                continue;
            }
            int hash = Math.abs((int) (seed * 31 + i * 2654435761L));
            token.color = hash % 3 == 0 ? accentSoft : neutral;
        }
    }

    /** 把一档字号套到每一块上，重算宽高。降档重排时必须重算，否则盒子还是上一档的尺寸。 */
    /**
     * 逐级变小的字体梯子。前六档与 {@link #fontTiers} 里出现过的字号一致，后面两档只在
     * {@link #shrinkToStage} 里用。
     */
    private static CFontRenderer[] fontLadder() {
        return new CFontRenderer[] {
                FontManager.pf65bold, FontManager.pf50bold, FontManager.pf40bold,
                FontManager.pf34bold, FontManager.pf28bold, FontManager.pf20bold,
                FontManager.pf14bold, FontManager.pf12bold, FontManager.pf10bold
        };
    }

    private void applyTier(List<Token> raw, int heroIndex, CFontRenderer[] tier,
                           double stageWidth, double stageHeight) {
        CFontRenderer[] ladder = fontLadder();
        for (int i = 0; i < raw.size(); i++) {
            Token token = raw.get(i);
            boolean hero = i == heroIndex;
            CFontRenderer font = shrinkToStage(token.text, hero ? tier[0] : tier[1], ladder,
                    boxReserve(hero), stageWidth, stageHeight);
            token.font = font;
            token.width = font.getStringWidthD(token.text);
            token.height = font.getHeight();
        }
    }

    /**
     * 单块自己就比舞台还大时，只把这一块的字号往下降。
     *
     * <p>降档位（{@link #rebuild} 里那个循环）是整行一起降的，解决的是「几块之间排不开」。
     * 但还有一种情况是<b>一块自己就装不下</b>：{@link #merge} 为了不让画面变成一团噪点会把相邻短块并起来，
     * 一句很长的歌词并完可能是四十来个字的一整块，即使降到最小档也比舞台宽。这种块以前会被
     * {@link #assignAnchors} 直接接受（注释里写着「折行救不了」），然后在绘制时被裁剪切掉一截 ——
     * 表现就是有一部分歌词溢出歌词框、看不见。</p>
     *
     * <p>所以这里对每一块单独兜底：沿字体梯子往下找第一个连同放大余量都塞得进舞台的字号。整梯子都塞不进
     * （极端窄的视口 + 极长的一块）时返回最小字号 —— 那时候无论如何都要被裁，至少裁得最少。</p>
     */
    private static CFontRenderer shrinkToStage(String text, CFontRenderer font, CFontRenderer[] ladder,
                                               double reserve, double stageWidth, double stageHeight) {
        int start = 0;
        for (int i = 0; i < ladder.length; i++) {
            if (ladder[i] == font) {
                start = i;
                break;
            }
        }
        for (int i = start; i < ladder.length; i++) {
            CFontRenderer candidate = ladder[i];
            double height = candidate.getHeight();
            double boxWidth = (candidate.getStringWidthD(text) * .5 + boxPadX(height)) * 2.0 * reserve;
            double boxHeight = (height * .5 + boxPadY(height)) * 2.0 * reserve;
            if (boxWidth <= stageWidth && boxHeight <= stageHeight) return candidate;
        }
        return ladder[ladder.length - 1];
    }

    /**
     * 决定这一行每一块字词落在哪里。
     *
     * <h3>之前会叠字的三个原因</h3>
     * <ol>
     *   <li><b>锚点场排成了一条比舞台还宽的长队。</b>原来是把所有块按阅读顺序排在一行上、以 0 为中心，
     *       一句长歌词的总宽度轻易就超过舞台宽度，于是两头的锚点全落在舞台外，随后被夹回同一条边界上
     *       ——一堆块的起点变成同一个点。现在改成<b>按舞台宽度折行</b>，锚点场天生就在舞台里。</li>
     *   <li><b>落位顺序是阅读顺序。</b>小块先占掉中间，大块再来时已经没有连续的空地了。现在默认
     *       <b>按盒子面积从大到小</b>放（hero 仍然最先），小块去填大块之间的缝。</li>
     *   <li><b>只试一种排法。</b>贪心排布没有回溯，某一种顺序排不开不等于排不下。现在会试
     *       {@link #LAYOUT_ATTEMPTS} 种（面积序 / 锚点向中心收紧 / 阅读序），取重叠最小的那一次；
     *       一旦某次做到零重叠就立刻停。</li>
     * </ol>
     *
     * @return 残余重叠面积。0 表示这一档字号排得开，调用方据此决定要不要降一档。
     */
    private double placeAll(List<Token> raw, int heroIndex, CFontRenderer[] tier,
                            double stageWidth, double stageHeight) {
        double gap = blockGap(tier[1]);
        double rowHeight = tier[0].getHeight() * 1.25;

        assignAnchors(raw, gap, stageWidth);
        // hero 仍然锚在正中偏上：镜头是跟着它走的，它跑到角上整个画面就一直偏在一侧。
        double heroAnchorX = 0;
        double heroAnchorY = -rowHeight * .15;

        int count = raw.size();
        int[] byArea = placementOrder(raw, heroIndex, true);
        int[] byReading = placementOrder(raw, heroIndex, false);

        double bestOverlap = Double.MAX_VALUE;
        double[] bestX = new double[count];
        double[] bestY = new double[count];

        for (int attempt = 0; attempt < LAYOUT_ATTEMPTS; attempt++) {
            int[] order = attempt == 2 ? byReading : byArea;
            // 把锚点往中心收紧一档：起点更密，螺旋就会把放不下的块往外推到真正的空地上，
            // 而不是在一片各自为政的锚点之间来回挤。
            double squeeze = attempt == 1 ? .72 : 1.0;

            List<double[]> placed = new ArrayList<double[]>();
            for (int i = 0; i < count; i++) {
                int index = order[i];
                Token token = raw.get(index);
                double anchorX = index == heroIndex ? heroAnchorX : token.anchorX * squeeze;
                double anchorY = index == heroIndex ? heroAnchorY : token.anchorY * squeeze;
                place(token, anchorX, anchorY, placed, stageWidth, stageHeight);
            }

            double overlap = totalOverlap(placed);
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                for (int i = 0; i < count; i++) {
                    bestX[i] = raw.get(i).homeX;
                    bestY[i] = raw.get(i).homeY;
                }
            }
            if (bestOverlap <= OVERLAP_EPSILON) break;
        }

        for (int i = 0; i < count; i++) {
            raw.get(i).homeX = bestX[i];
            raw.get(i).homeY = bestY[i];
        }
        return bestOverlap;
    }

    /** 已落位的盒子之间的重叠面积总和。 */
    private static double totalOverlap(List<double[]> placed) {
        double total = 0;
        for (int i = 0; i < placed.size(); i++) {
            double[] left = placed.get(i);
            for (int j = i + 1; j < placed.size(); j++) {
                double[] right = placed.get(j);
                double dx = (left[2] + right[2]) - Math.abs(left[0] - right[0]);
                double dy = (left[3] + right[3]) - Math.abs(left[1] - right[1]);
                if (dx > 0 && dy > 0) total += dx * dy;
            }
        }
        return total;
    }

    /**
     * 锚点场：按阅读顺序折行，每行不超过舞台宽度，整块场地在竖直方向居中。
     *
     * <p>这只是「理想位置」，真正的落点由 {@link #place} 从这里出发找。折行的意义是让理想位置本身
     * 就是分散的、并且在舞台内——搜索算法再好，也救不了一堆全都指向同一个点的起点。</p>
     */
    private void assignAnchors(List<Token> raw, double gap, double stageWidth) {
        int count = raw.size();
        int[] rowOf = new int[count];
        double[] rowWidth = new double[count];
        double[] rowHeight = new double[count];

        int rows = 0;
        double used = 0;
        for (int i = 0; i < count; i++) {
            Token token = raw.get(i);
            // 单块本身就比舞台宽时也要接受它：这时候折行救不了，字号档位才是该拦住它的地方。
            if (used > 0 && used + gap + token.width > stageWidth) {
                rows++;
                used = 0;
            }
            if (used > 0) used += gap;
            used += token.width;
            rowOf[i] = rows;
            rowWidth[rows] = used;
            rowHeight[rows] = Math.max(rowHeight[rows], token.height);
        }
        rows++;

        // 行带高度必须把纵向留白算进去（上下各一份），否则锚点场里上下相邻的两行天生就是重叠的
        // ——原来是固定乘 1.14，只有 .14 倍字高的净空，小字号下连发光都盖不住。
        double[] band = new double[rows];
        double totalHeight = 0;
        for (int r = 0; r < rows; r++) {
            band[r] = rowHeight[r] + boxPadY(rowHeight[r]) * 2.0;
            totalHeight += band[r];
        }

        double[] cursor = new double[rows];
        double[] centreY = new double[rows];
        double y = -totalHeight * .5;
        for (int r = 0; r < rows; r++) {
            centreY[r] = y + band[r] * .5;
            cursor[r] = -rowWidth[r] * .5;
            y += band[r];
        }

        for (int i = 0; i < count; i++) {
            Token token = raw.get(i);
            int r = rowOf[i];
            token.anchorX = cursor[r] + token.width * .5;
            token.anchorY = centreY[r];
            cursor[r] += token.width + gap;
        }
    }

    /**
     * hero 最先，其余按盒子面积从大到小（{@code byArea}）或按阅读顺序。面积相同按原顺序，
     * 保证同一行每次算出来都一样——布局必须是行的纯函数，否则同一行每帧都会跳。
     */
    private static int[] placementOrder(List<Token> raw, int heroIndex, boolean byArea) {
        int count = raw.size();
        int[] order = new int[count];
        boolean[] taken = new boolean[count];
        order[0] = heroIndex;
        taken[heroIndex] = true;

        if (!byArea) {
            int slot = 1;
            for (int i = 0; i < count; i++) {
                if (!taken[i]) order[slot++] = i;
            }
            return order;
        }

        double[] area = new double[count];
        for (int i = 0; i < count; i++) {
            Token token = raw.get(i);
            double reserve = boxReserve(token.hero);
            area[i] = (token.width * .5 + boxPadX(token.height)) * reserve
                    * (token.height * .5 + boxPadY(token.height)) * reserve;
        }
        for (int slot = 1; slot < count; slot++) {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (taken[i]) continue;
                if (best < 0 || area[i] > area[best]) best = i;
            }
            order[slot] = best;
            taken[best] = true;
        }
        return order;
    }

    /**
     * 从锚点出发找一个不与已放置块重叠的落点。
     *
     * <h3>为什么越界的候选点是跳过而不是夹取</h3>
     * <p>原来每个候选点都被 {@code clamp} 到舞台内，于是一整圈上互不相同的候选点会被折到同一个边界点上
     * ——名义上试了几百个位置，实际只试了一两个，长句在窄视口里必然叠字。现在越界就直接跳过，
     * 每个被真正评估的候选点都是不同的位置。</p>
     *
     * <p>螺旋之后还有一遍<b>全域网格</b>：螺旋是从锚点往外扩的，锚点被别人压死时它可能整圈都在别人的
     * 盒子里；舞台上任何一个真正的空位都好过「最不挤的重叠位」，所以宁可再扫一遍。两者都失败才接受
     * 重叠最小的那个位置——那种情况说明这一行确实塞不下，属于字号档位该处理的问题。</p>
     */
    private void place(Token token, double anchorX, double anchorY, List<double[]> placed,
                       double stageWidth, double stageHeight) {
        double halfWidth = token.width * .5 + boxPadX(token.height);
        double halfHeight = token.height * .5 + boxPadY(token.height);
        double scaleUp = boxReserve(token.hero);
        double boxWidth = halfWidth * scaleUp;
        double boxHeight = halfHeight * scaleUp;

        double limitX = Math.max(0.0, stageWidth * .5 - boxWidth);
        double limitY = Math.max(0.0, stageHeight * .5 - boxHeight);

        double originX = clamp(anchorX, -limitX, limitX);
        double originY = clamp(anchorY, -limitY, limitY);

        double bestX = originX;
        double bestY = originY;
        double bestOverlap = overlapArea(originX, originY, boxWidth, boxHeight, placed);
        if (bestOverlap <= 0) {
            commit(token, originX, originY, boxWidth, boxHeight, placed);
            return;
        }

        double step = Math.max(6.0, Math.min(boxWidth, boxHeight) * .5);
        double maxRadius = Math.max(limitX, limitY) * 2.0 + Math.max(boxWidth, boxHeight);
        for (double radius = step; radius <= maxRadius; radius += step) {
            int samples = Math.max(10, (int) (radius / step) * 6);
            for (int s = 0; s < samples; s++) {
                double angle = (Math.PI * 2 * s) / samples + radius * .35;
                double x = originX + Math.cos(angle) * radius;
                double y = originY + Math.sin(angle) * radius * .85;
                if (x < -limitX || x > limitX || y < -limitY || y > limitY) continue;
                double overlap = overlapArea(x, y, boxWidth, boxHeight, placed);
                if (overlap <= 0) {
                    commit(token, x, y, boxWidth, boxHeight, placed);
                    return;
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        int cells = 29;
        for (int ix = 0; ix < cells; ix++) {
            double x = cells == 1 ? 0 : -limitX + 2.0 * limitX * ix / (cells - 1);
            for (int iy = 0; iy < cells; iy++) {
                double y = cells == 1 ? 0 : -limitY + 2.0 * limitY * iy / (cells - 1);
                double overlap = overlapArea(x, y, boxWidth, boxHeight, placed);
                if (overlap <= 0) {
                    commit(token, x, y, boxWidth, boxHeight, placed);
                    return;
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        commit(token, bestX, bestY, boxWidth, boxHeight, placed);
    }

    private static void commit(Token token, double x, double y, double boxWidth, double boxHeight,
                               List<double[]> placed) {
        token.homeX = x;
        token.homeY = y;
        placed.add(new double[] {x, y, boxWidth, boxHeight});
    }

    private static double overlapArea(double x, double y, double halfWidth, double halfHeight,
                                      List<double[]> placed) {
        double total = 0;
        for (int i = 0; i < placed.size(); i++) {
            double[] box = placed.get(i);
            double dx = Math.abs(x - box[0]) - (halfWidth + box[2]);
            double dy = Math.abs(y - box[1]) - (halfHeight + box[3]);
            if (dx < 0 && dy < 0) total += (-dx) * (-dy);
        }
        return total;
    }

    /**
     * 镜头要跟的那一块：<b>已经开始出现的字词里最新的那一个</b>。
     *
     * <p>为什么不能取「第一个落在时间区间内的」：逐字歌词的词区间本来就会互相重叠——TTML 的对唱段落、
     * {@link #merge} 把相邻短字并起来之后被拉长的块、以及没带时长的词一律按 400ms 估算，都会让同一时刻
     * 有好几块同时算「正在唱」。按列表顺序取到的是其中<i>最旧</i>的那块，于是画面上新字已经亮起来了，
     * 镜头却还压在上一个字上，正好是重叠时最糟的选择。</p>
     *
     * <p>判定还提前了 {@value #ENTER_LEAD_MS}：字词正是从这一刻开始淡入的，镜头必须同时起步，弹簧才能
     * 在字完全显现时大致到位。这只改变镜头<b>看哪里</b>与<b>何时动身</b>，字词自己的入场、呼吸、退场动画
     * 一行都没有碰。</p>
     *
     * <p><b>这一处刻意不照抄参考实现。</b>参考的 {@code focusWord} 取「第一个 {@code start<=pos<=end} 的」，
     * 那对它是够的：它的字词直接来自单行 TTML 音节，区间基本不重叠。本项目的区间必然重叠 ——
     * TTML 的对唱段落、{@link #merge} 把相邻短字并起来之后被拉长的块、以及没带时长的词一律按 400ms 估算，
     * 都会让同一时刻有好几块算「正在唱」。按列表顺序取到的是其中<i>最旧</i>那块，画面上新字已经亮起来了、
     * 镜头却压在上一个字上。在全跟随（{@link #CAMERA_DAMP} = 1.0）之下这个偏差会被直接放大成
     * 「镜头停在错的字上」，所以必须取最新的那一块。</p>
     */
    private Token focusToken(double positionMs) {
        if (tokens.isEmpty()) return null;
        int index = newestVisibleIndex(tokens.size(), positionMs, ENTER_LEAD_MS,
                new StartEndAccess() {
                    public long startOf(int i) {
                        return tokens.get(i).startMs;
                    }

                    public long endOf(int i) {
                        return tokens.get(i).endMs;
                    }
                });
        return tokens.get(index);
    }

    /** 让 {@link #newestVisibleIndex} 既能服务 {@link Token} 又能被离线校验。 */
    interface StartEndAccess {
        long startOf(int index);

        long endOf(int index);
    }

    /**
     * {@code count} 个时间区间里，镜头此刻该跟的那一个的下标。
     *
     * <p>规则，按优先级：</p>
     * <ol>
     *   <li>已经开始出现（{@code positionMs >= start - enterLeadMs}）的当中，{@code start} 最大的那个；</li>
     *   <li>{@code start} 相同时取 {@code end} 更靠后的，也就是这一刻更主导的那块；</li>
     *   <li>再相同则取列表里更靠后的，保证同一帧的结果稳定、不会来回跳；</li>
     *   <li>一个都还没开始时取<b>最早开始</b>的那个，让镜头先候在那儿而不是等字出来才动。</li>
     * </ol>
     *
     * <p>已经唱完的块不需要单独排除：它们的 {@code start} 更小，永远竞争不过后来的块。所以一行唱完之后
     * 镜头会停在最后一块上，直到下一行重排。</p>
     *
     * @return 永远是 {@code [0, count)} 里的合法下标；{@code count <= 0} 时返回 0
     */
    static int newestVisibleIndex(int count, double positionMs, long enterLeadMs, StartEndAccess access) {
        if (count <= 0 || access == null) return 0;
        int newest = -1;
        int earliest = 0;
        for (int i = 0; i < count; i++) {
            long start = access.startOf(i);
            if (start < access.startOf(earliest)) earliest = i;
            if (positionMs < start - enterLeadMs) continue;
            if (newest < 0) {
                newest = i;
                continue;
            }
            long best = access.startOf(newest);
            if (start > best || (start == best && access.endOf(i) >= access.endOf(newest))) {
                newest = i;
            }
        }
        return newest >= 0 ? newest : earliest;
    }

    // ==================== 绘制 ====================

    private void drawTokens(List<Token> list, double positionMs, double centerX, double centerY,
                            float alpha, double exitProgress) {
        boolean effects = HudConfig.currentLyricEffectsEnabled;
        double wordScale = effects ? Math.max(0.0, HudConfig.currentWordScale) : 0.0;

        for (int i = 0; i < list.size(); i++) {
            Token token = list.get(i);
            if (token.font == null) continue;

            boolean active = positionMs >= token.startMs - ENTER_LEAD_MS && positionMs <= token.endMs;
            double enter = clamp01((positionMs - (token.startMs - ENTER_LEAD_MS)) / (double) ENTER_DURATION_MS);

            pose.reset();
            pose.scale = token.hero ? HERO_BASE_SCALE : NORMAL_BASE_SCALE;
            if (active) {
                // 呼吸：与参考实现同一条公式。相位里掺进 startMs 是为了让同一行里的字词不要齐步走。
                pose.scale *= 1.0 + Math.sin(positionMs * .01 + token.startMs * .005) * BREATH_STRENGTH;
            }
            if (active && wordScale > 0) {
                double own = token.endMs > token.startMs
                        ? clamp01((positionMs - token.startMs) / (double) (token.endMs - token.startMs))
                        : 1.0;
                pose.scale *= 1.0 + wordScale * Math.sin(Math.PI * own);
            }
            applyEnterPose(token, enter);
            if (exitProgress > 0) applyExitPose(token, exitProgress);

            float drawAlpha = (float) (alpha * pose.enterAlpha * (1.0 - pose.exitAlpha));
            if (drawAlpha <= 0.01f) continue;

            double x = centerX + token.homeX + pose.dx;
            double y = centerY + token.homeY + pose.dy;
            double left = x - token.width * .5;
            double top = y - token.height * .5;

            boolean transformed = Math.abs(pose.scale - 1.0) > 0.0001 || Math.abs(pose.rotation) > 0.05;
            if (transformed) {
                api.getGLStateManager().pushMatrix();
                if (Math.abs(pose.rotation) > 0.05) rotateAtPos(x, y, (float) pose.rotation);
                if (Math.abs(pose.scale - 1.0) > 0.0001) scaleAtPos(x, y, pose.scale);
            }
            try {
                double glow = 0;
                if (effects && positionMs >= token.startMs && positionMs <= token.endMs
                        && token.endMs > token.startMs) {
                    double own = clamp01((positionMs - token.startMs) / (double) (token.endMs - token.startMs));
                    glow = Math.max(0.0, 1.0 - Math.abs(own - .35) * 1.6);
                }
                if (glow > 0.02 && Math.abs(pose.rotation) < GLOW_ROTATION_LIMIT) {
                    drawGlow(token, left, top, glow, drawAlpha);
                }
                int color = active ? brighten(token.color, ACTIVE_BRIGHTEN) : token.color;
                token.font.drawString(token.text, left, top, reAlpha(color, drawAlpha));
            } finally {
                if (transformed) api.getGLStateManager().popMatrix();
            }
        }
    }

    /**
     * 字词发光。
     *
     * <p>1.8.9 没有可用的后处理管线，沿用项目里其它发光的做法：把同一串文本按环形偏移多画几遍、
     * 逐层降低透明度。强度上限走 {@link HudConfig#fullscreenGlowStrength}，和经典样式共用同一个滑块。</p>
     */
    private void drawGlow(Token token, double x, double y, double strength, float alpha) {
        double limit = Math.max(0f, Math.min(1f, HudConfig.fullscreenGlowStrength));
        double effective = limit * clamp01(strength);
        if (effective <= .004 || alpha <= .004f) return;

        double radius = 1.0 + 2.4 * effective;
        drawGlowRing(token, x, y, radius, (float) (alpha * effective * .34));
        drawGlowRing(token, x, y, radius * 1.7, (float) (alpha * effective * .15));
    }

    private void drawGlowRing(Token token, double x, double y, double radius, float ringAlpha) {
        if (ringAlpha <= .002f) return;
        int cardinal = reAlpha(token.color, ringAlpha);
        int diagonal = reAlpha(token.color, ringAlpha * .72f);
        double diagonalRadius = radius * .7071;
        CFontRenderer font = token.font;
        font.drawString(token.text, x - radius, y, cardinal);
        font.drawString(token.text, x + radius, y, cardinal);
        font.drawString(token.text, x, y - radius, cardinal);
        font.drawString(token.text, x, y + radius, cardinal);
        font.drawString(token.text, x - diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(token.text, x + diagonalRadius, y - diagonalRadius, diagonal);
        font.drawString(token.text, x - diagonalRadius, y + diagonalRadius, diagonal);
        font.drawString(token.text, x + diagonalRadius, y + diagonalRadius, diagonal);
    }

    private void applyEnterPose(Token token, double progress) {
        if (progress >= 1) return;
        double height = token.height;
        double inverse = 1.0 - easeOutCubic(progress);
        double inverseBack = 1.0 - easeOutBack(progress);
        double direction = token.direction;
        switch (token.enter) {
            case FADE:
                pose.dy += inverse * height * .6;
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case JUMP:
                pose.dy += inverseBack * height * 2.6;
                pose.enterAlpha = clamp01(progress * 2);
                pose.scale *= 1.0 + inverse * .15;
                break;
            case DROP:
                pose.dy -= (1.0 - easeOutBounce(progress)) * height * 3.2;
                pose.enterAlpha = clamp01(progress * 3);
                break;
            case SPIN:
                pose.rotation += inverse * -540;
                pose.enterAlpha = easeOutCubic(progress);
                pose.scale *= .6 + .4 * easeOutCubic(progress);
                break;
            case BOOMERANG:
                pose.dx += inverseBack * direction * height * 6;
                pose.dy -= Math.sin(progress * Math.PI) * height * 2;
                pose.rotation += inverse * direction * 360;
                pose.enterAlpha = clamp01(progress * 2);
                break;
            case SLIDE:
                pose.dx += inverse * direction * height * 4.5;
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case ZOOM:
                pose.scale *= .15 + .85 * easeOutBack(progress);
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case SWIRL:
                double swirl = token.angle + progress * Math.PI * 2 * direction;
                pose.dx += Math.cos(swirl) * inverse * height * 3.5;
                pose.dy += Math.sin(swirl) * inverse * height * 3.5;
                pose.rotation += inverse * direction * 300;
                pose.enterAlpha = clamp01(progress * 2);
                break;
            default:
                break;
        }
    }

    private void applyExitPose(Token token, double progress) {
        if (progress <= 0) return;
        double height = token.height;
        double eased = easeInCubic(progress);
        double direction = token.direction;
        switch (token.exit) {
            case FADE:
                pose.dy -= eased * height * .5;
                break;
            case JUMP:
                pose.dy -= eased * height * 3;
                pose.scale *= 1.0 - .3 * progress;
                break;
            case DROP:
                pose.dy += progress * progress * height * 4;
                pose.rotation += progress * direction * 180;
                break;
            case SPIN:
                pose.rotation += eased * direction * 540;
                pose.scale *= 1.0 - .4 * progress;
                break;
            case BOOMERANG:
                pose.dx += easeInBack(progress) * direction * height * 7;
                pose.dy -= Math.sin(progress * Math.PI) * height * 1.8;
                pose.rotation += progress * direction * 360;
                break;
            case SLIDE:
                pose.dx += eased * direction * height * 5;
                break;
            case ZOOM:
                pose.scale *= 1.0 + .8 * progress;
                break;
            case SWIRL:
                double swirl = token.angle + progress * Math.PI * 2 * direction;
                pose.dx += Math.cos(swirl) * progress * height * 4;
                pose.dy += Math.sin(swirl) * progress * height * 4;
                pose.rotation += progress * direction * 320;
                break;
            default:
                break;
        }
        pose.exitAlpha = clamp01(progress);
    }

    // ==================== 小工具 ====================

    private static long seedOf(LyricLine line) {
        if (line == null) return 0L;
        String text = line.lyric == null ? "" : line.lyric;
        return line.timestamp * 31L + text.hashCode();
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * 参考实现的开合曲线。比线性多两个「两端零导数」的性质：入场不会一开始就窜出去，
     * 收尾也不会硬停。
     */
    static double smoothstep(double t) {
        double x = clamp01(t);
        return x * x * (3.0 - 2.0 * x);
    }

    static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }

    static double easeOutCubic(double t) {
        double clamped = clamp01(t);
        double u = 1.0 - clamped;
        return 1.0 - u * u * u;
    }

    static double easeInCubic(double t) {
        double clamped = clamp01(t);
        return clamped * clamped * clamped;
    }

    static double easeOutBack(double t) {
        double clamped = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double u = clamped - 1.0;
        return 1.0 + c3 * u * u * u + c1 * u * u;
    }

    static double easeInBack(double t) {
        double clamped = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        return c3 * clamped * clamped * clamped - c1 * clamped * clamped;
    }

    static double easeOutBounce(double t) {
        double clamped = clamp01(t);
        double n1 = 7.5625;
        double d1 = 2.75;
        if (clamped < 1.0 / d1) return n1 * clamped * clamped;
        if (clamped < 2.0 / d1) {
            double u = clamped - 1.5 / d1;
            return n1 * u * u + .75;
        }
        if (clamped < 2.5 / d1) {
            double u = clamped - 2.25 / d1;
            return n1 * u * u + .9375;
        }
        double u = clamped - 2.625 / d1;
        return n1 * u * u + .984375;
    }

    private static int brighten(int argb, double amount) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = (int) Math.round(r + (255 - r) * amount);
        g = (int) Math.round(g + (255 - g) * amount);
        b = (int) Math.round(b + (255 - b) * amount);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int mixRgb(int left, int right, double weight) {
        double w = clamp01(weight);
        int r = (int) Math.round(((left >> 16) & 0xFF) * (1 - w) + ((right >> 16) & 0xFF) * w);
        int g = (int) Math.round(((left >> 8) & 0xFF) * (1 - w) + ((right >> 8) & 0xFF) * w);
        int b = (int) Math.round((left & 0xFF) * (1 - w) + (right & 0xFF) * w);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
