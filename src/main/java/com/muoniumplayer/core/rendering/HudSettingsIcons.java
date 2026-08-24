package com.muoniumplayer.core.rendering;

/**
 * HUD 设置界面图标的码位（字体资源 {@code muonium/fonts/hud-settings-icons.ttf}）。
 *
 * <p>字形来自 Material Design Icons 的 round 一档（Apache-2.0），用 fontTools 把 SVG 的
 * 24×24 网格整格映射到 1000 upem 生成，只保留界面实际用到的 32 枚，整个字体 6.5 KB。
 * 生成脚本在 {@code tools/make_hud_settings_icon_font.py}，可以重跑。</p>
 *
 * <p>码位落在 U+E200~E21F（外加 U+E2FF）。这一段是把 {@code muonium/fonts} 下每份字体的 cmap
 * 全扫了一遍之后挑的空段 —— 起初选的 U+E100 段并不空，{@code player-action-icons} 占了
 * U+E113 与 U+E144。项目里现有的私用区占用是：{@code fontello} 的 U+E001~E004 / U+E800~E802 /
 * U+F127 / U+F234、{@code music-brand-icons} 的 U+E800~E802、{@code qq-music-icons} 的 U+E888、
 * {@code island-status-icons} 的 U+E803 / U+E807 / U+E808 / U+E901~E904、
 * {@code player-queue-icons} 的 U+E309、{@code player-action-icons} 的 U+E113 / U+E144。
 * 每个 {@code CFontRenderer} 只认自己那一份字体，所以重叠不会当场画错东西，但一旦哪天要合并
 * 字体或者传错了渲染器，重叠的码位就会静默画出另一枚图标 —— 那种 bug 极难看出来。</p>
 *
 * <p><b>{@link #LOCATOR} 是一枚满格实心方块，不用来显示。</b>图标在 24 网格里的原始位置被
 * 完整保留（这样 chevron 这类细长字形不会被撑成和实心块一样高），代价是不能按各自的墨迹盒居中。
 * 渲染时改为量 {@code LOCATOR} 的墨迹盒 —— 它就等于 em 方格相对 {@code drawString} 原点的
 * 位置与尺寸 —— 再让这个方格居中，于是所有图标都落在与 Material 设计一致的对齐网格上。</p>
 */
public final class HudSettingsIcons {

    /** 放大镜：搜索框。 */
    public static final String SEARCH = "\ue200";
    /** 叉：关闭取色器、清空搜索。 */
    public static final String CLOSE = "\ue201";
    /** 上折角：把面板收起。 */
    public static final String COLLAPSE = "\ue202";
    /** 下折角：把面板展开。 */
    public static final String EXPAND = "\ue203";
    /** 右折角：选项行「点一下进下一个」。 */
    public static final String CHEVRON_RIGHT = "\ue204";
    /** 左折角。 */
    public static final String CHEVRON_LEFT = "\ue205";
    /** 环形箭头：恢复默认。 */
    public static final String RESTORE = "\ue206";
    /** 面板格：切到现代界面。 */
    public static final String MODERN_UI = "\ue207";
    /** 侧栏格：切到经典界面。 */
    public static final String CLASSIC_UI = "\ue208";
    /** 六点栅格：可拖动的把手。 */
    public static final String DRAG_HANDLE = "\ue209";
    /** 对勾。 */
    public static final String CHECK = "\ue20a";
    /** 警告三角：恢复默认的确认弹窗。 */
    public static final String WARNING = "\ue20b";
    /** 麦克风：当前歌词分类。 */
    public static final String MIC = "\ue20c";
    /** 字幕条：普通歌词分类。 */
    public static final String SUBTITLES = "\ue20d";
    /** 响铃：灵动岛分类。 */
    public static final String ISLAND = "\ue20e";
    /** 唱片：封面分类。 */
    public static final String ALBUM = "\ue20f";
    /** 调色盘：主题分类。 */
    public static final String PALETTE = "\ue210";
    /** 四向箭头：HUD 位置分类。 */
    public static final String MOVE = "\ue211";
    /** 推子：基础/排版分组。 */
    public static final String TUNE = "\ue212";
    /** 滴管：颜色条目。 */
    public static final String COLORIZE = "\ue213";
    /** 全屏角标：全屏歌词分组。 */
    public static final String FULLSCREEN = "\ue214";
    /** CC 标记：桌面歌词分组。 */
    public static final String CAPTION = "\ue215";
    /** 速度表：时序与动画分组。 */
    public static final String SPEED = "\ue216";
    /** 直尺：摆放分组。 */
    public static final String RULER = "\ue217";
    /** 信息圆：说明。 */
    public static final String INFO = "\ue218";
    /** 眼睛：显示分组。 */
    public static final String VISIBILITY = "\ue219";
    /** 星火：特效相关分组。 */
    public static final String SPARKLE = "\ue21a";
    /** 计时器：常驻显示分组。 */
    public static final String TIMER = "\ue21b";
    /** 音符。 */
    public static final String NOTE = "\ue21c";
    /** 刷新。 */
    public static final String REFRESH = "\ue21d";
    /** 左箭头：返回。 */
    public static final String BACK = "\ue21e";
    /** 三横线：标题栏的拖动区标记。 */
    public static final String GRIP = "\ue21f";

    public static final String STORAGE = "\ue220";
    public static final String SWEEP = "\ue221";

    /**
     * 满格实心方块，<b>只用于量测，不要画出来</b>。
     *
     * <p>量它的墨迹盒即得 em 方格相对 {@code drawString} 原点的位置，据此把图标按网格居中。</p>
     */
    public static final String LOCATOR = "\ue2ff";

    private HudSettingsIcons() {
    }
}
