package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;

/**
 * The position, scale, and built-in editor appearance settings for the music HUDs.
 *
 * <p>Position is stored as a 0..1 fraction. Desktop-lyric appearance settings live
 * here so the built-in HUD editor can preview and persist them without depending on
 * the external ClickGUI value store.</p>
 */
public final class HudConfig {

    public static final float SCALE_MIN = 0.5f;
    public static final float SCALE_MAX = 2.0f;

    /** 灵动岛样式索引上限,与 {@code DownloadDynamicIsland.DynamicIslandStyle} 的数量保持一致。 */
    public static final int DYNAMIC_ISLAND_STYLE_MAX = 6;

    private static final File FILE = ConfigPaths.HUD;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Song information card: top-left corner. */
    public static float infoX = 0.02f;
    public static float infoY = 0.02f;
    public static float infoScale = 1.0f;

    /**
     * Player output volume, normalized to {@code 0.0..1.0}.  This is stored in
     * the mod-owned HUD configuration rather than relying on the external value
     * store, which may be recreated between Minecraft launches.
     */
    public static float playerVolume = 0.10f;

    /** Lyrics: horizontally centered near the bottom. */
    public static float lyricX = 0.5f;
    public static float lyricY = 0.85f;
    public static float lyricScale = 1.0f;

    /** Appearance controls exposed in {@code GuiHudEditor}. */
    public static int lyricColorRgb = 0xFFFFFFFF;
    public static int currentLyricColorRgb = 0xFFFFFFFF;

    // Current lyric / active KTV word.
    /** Master switch for current-line glow, bloom and breathing. */
    public static boolean currentLyricEffectsEnabled = true;
    /** Master switch for OSD per-character glow and retained scale emphasis. */
    public static boolean osdKaraokeEmphasisEnabled = true;
    /** Master switch for ordinary lyric-row glow and bloom. */
    public static boolean normalLyricEffectsEnabled = true;
    /** Allows the viewport-edge fade to be disabled without changing its configured size. */
    public static boolean lyricEdgeFadeEnabled = true;

    public static float currentLineScale = 1.04f;
    public static float currentWordScale = 0.10f;
    public static float currentGlowStrength = 0.62f;
    public static float currentGlowRadius = 2.40f;
    public static float currentBloomStrength = 0.42f;
    public static float currentTransitionWidth = 14.0f;
    public static float currentBreathStrength = 0.015f;

    /**
     * 全屏歌词页逐字发光的上限强度。
     *
     * <p>发光是"随这个字已经唱了多久"逐渐变亮的：每个字的发光透明度 = 该字的演唱进度 ×
     * 这个上限。写成单独一个上限而不是复用 {@link #currentGlowStrength}，是因为后者同时
     * 作用于 OSD 桌面歌词的整行光晕，调大它会让桌面歌词一起过曝。</p>
     */
    public static float fullscreenGlowStrength = 0.55f;

    // OSD KTV animation. These values are independent from the full-screen lyric renderer.
    public static float osdKaraokeTransitionWidth = 14.0f;
    public static float osdKaraokeGlowStrength = 0.68f;
    public static float osdKaraokeBloomStrength = 0.46f;
    public static float osdKaraokePulseStrength = 0.06f;
    public static float osdKaraokeSmoothing = 0.85f;
    /**
     * 桌面歌词里对唱两行的左右分列幅度。
     *
     * <p>{@code 1} = 一行完全贴内容区左边缘、另一行完全贴右边缘（改动之前的行为）；{@code 0} = 两行
     * 都摆在正中间（还是分两行渲染，只是不再左右分开）。中间值是在「完全居中」和「完全贴边」之间线性
     * 插值，所以调小只会让两行往中间收，永远不会把文字推出内容区。</p>
     *
     * <p>只作用于桌面歌词（OSD）。全屏歌词页的对唱分列走的是 {@code LyricDuetGroups.columnLeft}
     * 那条独立的分栏逻辑，不受这一项影响。</p>
     */
    public static float osdDuetSpread = 0.55f;

    // Ordinary (non-current) lyric rows.
    public static float normalOpacity = 0.48f;
    public static float normalScale = 0.94f;
    public static float normalGlowStrength = 0.14f;
    public static float normalBloomStrength = 0.08f;
    public static float normalLineSpacing = 10.0f;
    public static float edgeFadeSize = 30.0f;
    public static float scrollSmoothness = 0.65f;
    public static float secondaryOpacity = 0.78f;

    /** Global download Dynamic Island. */
    public static boolean dynamicIslandEnabled = true;
    public static float dynamicIslandScale = 0.88f;
    /** Dynamic Island typography scale. Long content is reduced automatically before it is truncated. */
    public static float dynamicIslandTextScale = 1.0f;
    /** Preferred resting width. Long notices may animate wider to keep their complete text visible. */
    public static float dynamicIslandMaxWidth = 250.0f;
    /** Progress bar thickness in logical pixels. */
    public static float dynamicIslandProgressHeight = 1.35f;
    /** Seconds to keep the completed state visible before hiding. */
    public static float dynamicIslandCompletionHoldSeconds = 1.80f;
    /** When multiple notices are queued, seconds to show each queued notice. */
    public static float dynamicIslandQueueIntervalSeconds = 1.00f;
    /**
     * Visual preset for the global island: 0 pill, 1 glass, 2 compact, 3 card, 4 system card,
     * 5 music focus, 6 liquid glass.
     */
    public static int dynamicIslandStyle = 0;

    // 灵动岛动画节奏。1.0 表示历史默认速度,数值越大越快;时长以毫秒计。
    /** 弹出/展开阶段的逼近速度倍率。 */
    public static float dynamicIslandExpandSpeed = 1.0f;
    /** 收起/隐藏阶段的逼近速度倍率。 */
    public static float dynamicIslandCollapseSpeed = 1.0f;
    /** 文案切换、成功态变形等内容过渡的速度倍率。 */
    public static float dynamicIslandContentSpeed = 1.0f;
    /** 首次入场动画时长(毫秒)。 */
    public static float dynamicIslandEntranceDuration = 420.0f;
    /** 入场回弹幅度倍率,0 为完全不回弹。 */
    public static float dynamicIslandOvershoot = 1.0f;
    /** 加载指示器的旋转速度倍率。 */
    public static float dynamicIslandSpinnerSpeed = 1.0f;

    /**
     * 常驻灵动岛。打开后即使没有下载和通知,灵动岛也留在屏幕顶部显示实时状态;
     * 通知到来时它只做内容交叉淡入,不会重播入场动画,排队机制与关闭常驻时完全一致。
     */
    public static boolean dynamicIslandAlwaysOn = false;
    /** 常驻时显示游戏帧率。 */
    public static boolean dynamicIslandAmbientFps = true;
    /** 常驻时显示服务器延迟。单机或未进服时显示 "--"。 */
    public static boolean dynamicIslandAmbientPing = true;
    /** 常驻时显示系统时间(时:分)。 */
    public static boolean dynamicIslandAmbientClock = true;
    /**
     * 常驻时把"正在播放"作为一页参与显示:左侧换成歌曲封面(有动态封面时优先),右侧显示歌名与歌手。
     * 没有正在播放的歌曲时这一页自动跳过,不会留一张空卡片。
     */
    public static boolean dynamicIslandAmbientNowPlaying = true;
    /**
     * 常驻内容轮换。打开后"状态页(帧率/延迟/时间)"与"正在播放页"交替显示;关掉则固定停在状态页
     * (状态三项全关时才固定显示正在播放页)。两页各自的停留时长分别由
     * {@link #dynamicIslandAmbientStatusSeconds} 与 {@link #dynamicIslandAmbientNowPlayingSeconds} 决定。
     */
    public static boolean dynamicIslandAmbientRotate = true;
    /**
     * 状态页(帧率/延迟/时间)的停留时长(秒)。
     *
     * <p>与歌曲页分开配置的原因很实际:状态页是三个数字,扫一眼就够了;歌曲页要读歌名和歌手,
     * 常常还是长标题,需要更久才看得完。两者用同一个间隔时,要么状态页停得过久,要么歌名还没读完
     * 就翻页了。</p>
     */
    public static float dynamicIslandAmbientStatusSeconds = 5.0f;
    /** 正在播放页的停留时长(秒)。默认比状态页长一些,留出读完歌名与歌手的时间。 */
    public static float dynamicIslandAmbientNowPlayingSeconds = 8.0f;

    /**
     * 网易云动态封面(需要外部 ffmpeg 抽帧)。关掉后桌面歌曲信息、全屏歌词页与播放条一律用静态封面。
     * 这里是唯一的真实来源,HUD 编辑器与模块开关都读写它。
     */
    public static boolean animatedCoverEnabled = true;

    /**
     * HUD 设置界面用哪一套外观:{@code true} 走现代界面(侧边栏分类 + 卡片分组 + 每行带说明),
     * {@code false} 走经典界面(右侧一列可折叠长面板)。
     *
     * <p>两套界面的设置项完全一样(都来自 {@code HudSettingsModel}),只是呈现不同;两边都有
     * 互相切换的按钮,点了之后这里会被记住,下次打开直接是选中的那一套。刻意不做成"新界面替换旧界面":
     * 经典界面把全部滑块平铺成一列,熟悉之后找某一项其实更快。</p>
     */
    public static boolean hudEditorModernUi = true;

    /**
     * 现代 HUD 设置面板左上角的位置,归一化到「可摆放范围」的 0..1。
     *
     * <p>{@code -1} 是哨兵值,意思是「用户从没拖过」,此时面板仍走默认摆位(靠右、纵向居中)。
     * 用哨兵而不是直接存 0..1 的默认值:面板尺寸会随窗口大小变,把默认位置存成具体比例的话,
     * 换个分辨率再打开就会偏到奇怪的地方;而只要没拖过,默认摆位每次都按当前窗口重新算。</p>
     */
    public static float hudEditorPanelX = -1.0f;
    /** 见 {@link #hudEditorPanelX}。 */
    public static float hudEditorPanelY = -1.0f;

    private HudConfig() {
    }

    /** Restores only desktop-lyric appearance, leaving HUD positions untouched. */
    public static void resetLyricAppearance() {
        lyricColorRgb = 0xFFFFFFFF;
        currentLyricColorRgb = 0xFFFFFFFF;
        currentLyricEffectsEnabled = true;
        osdKaraokeEmphasisEnabled = true;
        normalLyricEffectsEnabled = true;
        lyricEdgeFadeEnabled = true;
        currentLineScale = 1.04f;
        currentWordScale = 0.10f;
        currentGlowStrength = 0.62f;
        currentGlowRadius = 2.40f;
        currentBloomStrength = 0.42f;
        currentTransitionWidth = 14.0f;
        currentBreathStrength = 0.015f;
        fullscreenGlowStrength = 0.55f;
        osdKaraokeTransitionWidth = 14.0f;
        osdKaraokeGlowStrength = 0.68f;
        osdKaraokeBloomStrength = 0.46f;
        osdKaraokePulseStrength = 0.06f;
        osdKaraokeSmoothing = 0.85f;
        osdDuetSpread = 0.55f;
        normalOpacity = 0.48f;
        normalScale = 0.94f;
        normalGlowStrength = 0.14f;
        normalBloomStrength = 0.08f;
        normalLineSpacing = 10.0f;
        edgeFadeSize = 30.0f;
        scrollSmoothness = 0.65f;
        secondaryOpacity = 0.78f;
    }

    /** Restores the global download-island appearance. */
    public static void resetDynamicIslandAppearance() {
        dynamicIslandEnabled = true;
        dynamicIslandScale = 0.88f;
        dynamicIslandTextScale = 1.0f;
        dynamicIslandMaxWidth = 250.0f;
        dynamicIslandProgressHeight = 1.35f;
        dynamicIslandCompletionHoldSeconds = 1.80f;
        dynamicIslandQueueIntervalSeconds = 1.00f;
        dynamicIslandStyle = 0;
        dynamicIslandExpandSpeed = 1.0f;
        dynamicIslandCollapseSpeed = 1.0f;
        dynamicIslandContentSpeed = 1.0f;
        dynamicIslandEntranceDuration = 420.0f;
        dynamicIslandOvershoot = 1.0f;
        dynamicIslandSpinnerSpeed = 1.0f;
        dynamicIslandAlwaysOn = false;
        dynamicIslandAmbientFps = true;
        dynamicIslandAmbientPing = true;
        dynamicIslandAmbientClock = true;
        dynamicIslandAmbientNowPlaying = true;
        dynamicIslandAmbientRotate = true;
        dynamicIslandAmbientStatusSeconds = 5.0f;
        dynamicIslandAmbientNowPlayingSeconds = 8.0f;
    }

    /** 恢复封面相关外观。 */
    public static void resetCoverAppearance() {
        animatedCoverEnabled = true;
    }

    /** Loads saved settings; malformed or out-of-range values fall back safely. */
    public static void load() {
        if (!FILE.exists()) {
            return;
        }
        try {
            JsonObject o = JsonConfigStorage.readObject(FILE, GSON);
            if (o == null) {
                return;
            }
            infoX = clampPos(getFloat(o, "infoX", infoX));
            infoY = clampPos(getFloat(o, "infoY", infoY));
            infoScale = clampScale(getFloat(o, "infoScale", infoScale));
            playerVolume = clamp(getFloat(o, "playerVolume", playerVolume), 0.0f, 1.0f);
            lyricX = clampPos(getFloat(o, "lyricX", lyricX));
            lyricY = clampPos(getFloat(o, "lyricY", lyricY));
            lyricScale = clampScale(getFloat(o, "lyricScale", lyricScale));
            lyricColorRgb = getInt(o, "lyricColorRgb", lyricColorRgb);
            currentLyricColorRgb = getInt(o, "currentLyricColorRgb", currentLyricColorRgb);

            currentLyricEffectsEnabled = getBoolean(o, "currentLyricEffectsEnabled", currentLyricEffectsEnabled);
            osdKaraokeEmphasisEnabled = getBoolean(o, "osdKaraokeEmphasisEnabled", osdKaraokeEmphasisEnabled);
            normalLyricEffectsEnabled = getBoolean(o, "normalLyricEffectsEnabled", normalLyricEffectsEnabled);
            lyricEdgeFadeEnabled = getBoolean(o, "lyricEdgeFadeEnabled", lyricEdgeFadeEnabled);

            currentLineScale = clamp(getFloat(o, "currentLineScale", currentLineScale), 0.95f, 1.16f);
            currentWordScale = clamp(getFloat(o, "currentWordScale", currentWordScale), 0.0f, 0.28f);
            currentGlowStrength = clamp01(getFloat(o, "currentGlowStrength", currentGlowStrength));
            currentGlowRadius = clamp(getFloat(o, "currentGlowRadius", currentGlowRadius), 0.5f, 5.0f);
            currentBloomStrength = clamp01(getFloat(o, "currentBloomStrength", currentBloomStrength));
            currentTransitionWidth = clamp(getFloat(o, "currentTransitionWidth", currentTransitionWidth), 4.0f, 32.0f);
            currentBreathStrength = clamp(getFloat(o, "currentBreathStrength", currentBreathStrength), 0.0f, 0.08f);
            fullscreenGlowStrength = clamp01(getFloat(o, "fullscreenGlowStrength", fullscreenGlowStrength));

            osdKaraokeTransitionWidth = clamp(getFloat(o, "osdKaraokeTransitionWidth", osdKaraokeTransitionWidth), 4.0f, 32.0f);
            osdKaraokeGlowStrength = clamp01(getFloat(o, "osdKaraokeGlowStrength", osdKaraokeGlowStrength));
            osdKaraokeBloomStrength = clamp01(getFloat(o, "osdKaraokeBloomStrength", osdKaraokeBloomStrength));
            osdKaraokePulseStrength = clamp(getFloat(o, "osdKaraokePulseStrength", osdKaraokePulseStrength), 0.0f, 0.15f);
            osdKaraokeSmoothing = clamp01(getFloat(o, "osdKaraokeSmoothing", osdKaraokeSmoothing));
            osdDuetSpread = clamp01(getFloat(o, "osdDuetSpread", osdDuetSpread));

            normalOpacity = clamp(getFloat(o, "normalOpacity", normalOpacity), 0.15f, 1.0f);
            normalScale = clamp(getFloat(o, "normalScale", normalScale), 0.85f, 1.05f);
            normalGlowStrength = clamp(getFloat(o, "normalGlowStrength", normalGlowStrength), 0.0f, 0.65f);
            normalBloomStrength = clamp(getFloat(o, "normalBloomStrength", normalBloomStrength), 0.0f, 0.50f);
            normalLineSpacing = clamp(getFloat(o, "normalLineSpacing", normalLineSpacing), 4.0f, 22.0f);
            edgeFadeSize = clamp(getFloat(o, "edgeFadeSize", edgeFadeSize), 10.0f, 80.0f);
            scrollSmoothness = clamp01(getFloat(o, "scrollSmoothness", scrollSmoothness));
            secondaryOpacity = clamp(getFloat(o, "secondaryOpacity", secondaryOpacity), 0.30f, 1.0f);

            dynamicIslandEnabled = getBoolean(o, "dynamicIslandEnabled", dynamicIslandEnabled);
            dynamicIslandScale = clamp(getFloat(o, "dynamicIslandScale", dynamicIslandScale), 0.60f, 1.35f);
            dynamicIslandTextScale = clamp(getFloat(o, "dynamicIslandTextScale", dynamicIslandTextScale), 0.82f, 1.18f);
            dynamicIslandMaxWidth = clamp(getFloat(o, "dynamicIslandMaxWidth", dynamicIslandMaxWidth), 160.0f, 720.0f);
            dynamicIslandProgressHeight = clamp(getFloat(o, "dynamicIslandProgressHeight", dynamicIslandProgressHeight), 0.75f, 4.0f);
            dynamicIslandCompletionHoldSeconds = clamp(getFloat(o, "dynamicIslandCompletionHoldSeconds", dynamicIslandCompletionHoldSeconds), 0.5f, 6.0f);
            dynamicIslandQueueIntervalSeconds = clamp(getFloat(o, "dynamicIslandQueueIntervalSeconds", dynamicIslandQueueIntervalSeconds), 0.5f, 6.0f);
            dynamicIslandStyle = clampInt(getInt(o, "dynamicIslandStyle", dynamicIslandStyle), 0,
                    DYNAMIC_ISLAND_STYLE_MAX);
            dynamicIslandExpandSpeed = clamp(getFloat(o, "dynamicIslandExpandSpeed", dynamicIslandExpandSpeed), 0.40f, 2.50f);
            dynamicIslandCollapseSpeed = clamp(getFloat(o, "dynamicIslandCollapseSpeed", dynamicIslandCollapseSpeed), 0.40f, 2.50f);
            dynamicIslandContentSpeed = clamp(getFloat(o, "dynamicIslandContentSpeed", dynamicIslandContentSpeed), 0.40f, 2.50f);
            dynamicIslandEntranceDuration = clamp(getFloat(o, "dynamicIslandEntranceDuration", dynamicIslandEntranceDuration), 160.0f, 1200.0f);
            dynamicIslandOvershoot = clamp(getFloat(o, "dynamicIslandOvershoot", dynamicIslandOvershoot), 0.0f, 2.00f);
            dynamicIslandSpinnerSpeed = clamp(getFloat(o, "dynamicIslandSpinnerSpeed", dynamicIslandSpinnerSpeed), 0.30f, 3.00f);
            dynamicIslandAlwaysOn = getBoolean(o, "dynamicIslandAlwaysOn", dynamicIslandAlwaysOn);
            dynamicIslandAmbientFps = getBoolean(o, "dynamicIslandAmbientFps", dynamicIslandAmbientFps);
            dynamicIslandAmbientPing = getBoolean(o, "dynamicIslandAmbientPing", dynamicIslandAmbientPing);
            dynamicIslandAmbientClock = getBoolean(o, "dynamicIslandAmbientClock", dynamicIslandAmbientClock);
        dynamicIslandAmbientNowPlaying = getBoolean(o, "dynamicIslandAmbientNowPlaying", dynamicIslandAmbientNowPlaying);
        hudEditorModernUi = getBoolean(o, "hudEditorModernUi", hudEditorModernUi);
        hudEditorPanelX = clamp(getFloat(o, "hudEditorPanelX", hudEditorPanelX), -1.0f, 1.0f);
        hudEditorPanelY = clamp(getFloat(o, "hudEditorPanelY", hudEditorPanelY), -1.0f, 1.0f);
        dynamicIslandAmbientRotate = getBoolean(o, "dynamicIslandAmbientRotate", dynamicIslandAmbientRotate);
        // 1.3.0 及更早只有一个「常驻轮换间隔」。拆成两页各自的时长后，用旧键当两个新键的默认值,
        // 这样此前手动调过间隔的配置会平移到两边,而不是被悄悄重置回新默认值。
        float legacyRotateSeconds = clamp(getFloat(o, "dynamicIslandAmbientRotateSeconds", 0.0f), 0.0f, 30.0f);
        float statusFallback = legacyRotateSeconds >= 2.0f ? legacyRotateSeconds : dynamicIslandAmbientStatusSeconds;
        float playingFallback = legacyRotateSeconds >= 2.0f ? legacyRotateSeconds : dynamicIslandAmbientNowPlayingSeconds;
        dynamicIslandAmbientStatusSeconds = clamp(getFloat(o, "dynamicIslandAmbientStatusSeconds",
                statusFallback), 2.0f, 30.0f);
        dynamicIslandAmbientNowPlayingSeconds = clamp(getFloat(o, "dynamicIslandAmbientNowPlayingSeconds",
                playingFallback), 2.0f, 30.0f);

            animatedCoverEnabled = getBoolean(o, "animatedCoverEnabled", animatedCoverEnabled);
        } catch (Throwable ignored) {
        }
    }

    /** Persists settings after an editor interaction. */
    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("infoX", infoX);
            o.addProperty("infoY", infoY);
            o.addProperty("infoScale", infoScale);
            o.addProperty("playerVolume", playerVolume);
            o.addProperty("lyricX", lyricX);
            o.addProperty("lyricY", lyricY);
            o.addProperty("lyricScale", lyricScale);
            o.addProperty("lyricColorRgb", lyricColorRgb);
            o.addProperty("currentLyricColorRgb", currentLyricColorRgb);

            o.addProperty("currentLyricEffectsEnabled", currentLyricEffectsEnabled);
            o.addProperty("osdKaraokeEmphasisEnabled", osdKaraokeEmphasisEnabled);
            o.addProperty("normalLyricEffectsEnabled", normalLyricEffectsEnabled);
            o.addProperty("lyricEdgeFadeEnabled", lyricEdgeFadeEnabled);
            o.addProperty("currentLineScale", currentLineScale);
            o.addProperty("currentWordScale", currentWordScale);
            o.addProperty("currentGlowStrength", currentGlowStrength);
            o.addProperty("currentGlowRadius", currentGlowRadius);
            o.addProperty("currentBloomStrength", currentBloomStrength);
            o.addProperty("currentTransitionWidth", currentTransitionWidth);
            o.addProperty("currentBreathStrength", currentBreathStrength);
            o.addProperty("fullscreenGlowStrength", fullscreenGlowStrength);
            o.addProperty("osdKaraokeTransitionWidth", osdKaraokeTransitionWidth);
            o.addProperty("osdKaraokeGlowStrength", osdKaraokeGlowStrength);
            o.addProperty("osdKaraokeBloomStrength", osdKaraokeBloomStrength);
            o.addProperty("osdKaraokePulseStrength", osdKaraokePulseStrength);
            o.addProperty("osdKaraokeSmoothing", osdKaraokeSmoothing);
            o.addProperty("osdDuetSpread", osdDuetSpread);

            o.addProperty("normalOpacity", normalOpacity);
            o.addProperty("normalScale", normalScale);
            o.addProperty("normalGlowStrength", normalGlowStrength);
            o.addProperty("normalBloomStrength", normalBloomStrength);
            o.addProperty("normalLineSpacing", normalLineSpacing);
            o.addProperty("edgeFadeSize", edgeFadeSize);
            o.addProperty("scrollSmoothness", scrollSmoothness);
            o.addProperty("secondaryOpacity", secondaryOpacity);

            o.addProperty("dynamicIslandEnabled", dynamicIslandEnabled);
            o.addProperty("dynamicIslandScale", dynamicIslandScale);
            o.addProperty("dynamicIslandTextScale", dynamicIslandTextScale);
            o.addProperty("dynamicIslandMaxWidth", dynamicIslandMaxWidth);
            o.addProperty("dynamicIslandProgressHeight", dynamicIslandProgressHeight);
            o.addProperty("dynamicIslandCompletionHoldSeconds", dynamicIslandCompletionHoldSeconds);
            o.addProperty("dynamicIslandQueueIntervalSeconds", dynamicIslandQueueIntervalSeconds);
            o.addProperty("dynamicIslandStyle", dynamicIslandStyle);
            o.addProperty("dynamicIslandExpandSpeed", dynamicIslandExpandSpeed);
            o.addProperty("dynamicIslandCollapseSpeed", dynamicIslandCollapseSpeed);
            o.addProperty("dynamicIslandContentSpeed", dynamicIslandContentSpeed);
            o.addProperty("dynamicIslandEntranceDuration", dynamicIslandEntranceDuration);
            o.addProperty("dynamicIslandOvershoot", dynamicIslandOvershoot);
            o.addProperty("dynamicIslandSpinnerSpeed", dynamicIslandSpinnerSpeed);
            o.addProperty("dynamicIslandAlwaysOn", dynamicIslandAlwaysOn);
            o.addProperty("dynamicIslandAmbientFps", dynamicIslandAmbientFps);
            o.addProperty("dynamicIslandAmbientPing", dynamicIslandAmbientPing);
            o.addProperty("dynamicIslandAmbientClock", dynamicIslandAmbientClock);
        o.addProperty("dynamicIslandAmbientNowPlaying", dynamicIslandAmbientNowPlaying);
        o.addProperty("dynamicIslandAmbientRotate", dynamicIslandAmbientRotate);
        o.addProperty("hudEditorModernUi", hudEditorModernUi);
        o.addProperty("hudEditorPanelX", hudEditorPanelX);
        o.addProperty("hudEditorPanelY", hudEditorPanelY);
        o.addProperty("dynamicIslandAmbientStatusSeconds", dynamicIslandAmbientStatusSeconds);
        o.addProperty("dynamicIslandAmbientNowPlayingSeconds", dynamicIslandAmbientNowPlayingSeconds);

            o.addProperty("animatedCoverEnabled", animatedCoverEnabled);

            JsonConfigStorage.writeObject(FILE, GSON, o);

        } catch (Throwable ignored) {
        }
    }

    public static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float clampScale(float v) {
        return clamp(v, SCALE_MIN, SCALE_MAX);
    }

    public static float clampPos(float v) {
        return clamp01(v);
    }

    private static float clamp01(float v) {
        return clamp(v, 0.0f, 1.0f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float getFloat(JsonObject o, String key, float def) {
        try {
            return o.has(key) ? o.get(key).getAsFloat() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean getBoolean(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int getInt(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }
}
