package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 全屏歌词页的展示样式。
 *
 * <p>索引会被持久化到 {@link HudConfig#fullscreenLyricStyle}，所以<b>只能往后追加</b>，
 * 不能重排已有项，否则用户的选择会指向另一种样式。</p>
 */
public enum FullscreenLyricStyle {

    /** 原有样式：整首歌逐行滚动，当前行做 KTV 填涂与逐字放大。 */
    CLASSIC("逐行滚动"),

    /**
     * 只显示当前一行，把它拆成字词散布开，镜头跟着正在唱的那个词走。
     *
     * <p>对应 folia-major 的 {@code cadenza}（心象）。这一套先于其余「文字 PV」样式实现，
     * 名字保留原来的「律动词云」，后面补上参考实现的中文名以便对照。</p>
     */
    CADENZA("律动词云 · 心象"),

    /** 浮名：整段歌词排成一篇文章，镜头在其中移动，当前行被推到中央逐字显影。 */
    FUME("浮名"),

    /** 流光：单行居中、逐字点亮，字词之间有轻微的散布与呼吸。 */
    LUMINOUS("流光"),

    /** 云阶：当前行切成若干块，左右交替错落成一段向下的阶梯。 */
    PARTITA("云阶"),

    /** 群唱：每行歌词是一条聊天气泡，左右交替，气泡内逐字浮现。 */
    CAPPELLA("群唱"),

    /** 倾诉：当前行拆成一到四段竖排，部分段落改用斜体 + 拉开字距 + 逐字错动。 */
    TILT("倾诉"),

    /** 镜台：歌词卡片摆在不同景深上，镜头一层层往里推（2.5D 近似）。 */
    DIORAMA("镜台"),

    /** 时计：歌词沿一段圆弧排布，像钟面刻度一样绕过来。 */
    PENDOLO("时计");

    private final String displayName;

    FullscreenLyricStyle(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 当前生效的样式。配置值越界时退回经典样式，绝不让歌词页画不出来。 */
    public static FullscreenLyricStyle current() {
        FullscreenLyricStyle[] values = values();
        int index = HudConfig.fullscreenLyricStyle;
        if (index < 0 || index >= values.length) return CLASSIC;
        return values[index];
    }

    /** 切到下一种样式并落盘，返回新样式。 */
    public static FullscreenLyricStyle next() {
        return shift(1);
    }

    /**
     * 切到上一种样式并落盘。
     *
     * <p>样式已经有九种，只能单向轮换的话想回到上一种得点八下。</p>
     */
    public static FullscreenLyricStyle previous() {
        return shift(-1);
    }

    private static FullscreenLyricStyle shift(int delta) {
        FullscreenLyricStyle[] values = values();
        int index = ((Math.max(0, HudConfig.fullscreenLyricStyle) + delta) % values.length
                + values.length) % values.length;
        HudConfig.fullscreenLyricStyle = index;
        HudConfig.save();
        return values[index];
    }
}
