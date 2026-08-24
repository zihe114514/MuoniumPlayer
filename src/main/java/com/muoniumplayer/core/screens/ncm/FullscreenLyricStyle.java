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

    /** 只显示当前一行，把它拆成字词散布开，镜头跟着正在唱的那个词走。 */
    CADENZA("律动词云");

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
        FullscreenLyricStyle[] values = values();
        int index = (Math.max(0, HudConfig.fullscreenLyricStyle) + 1) % values.length;
        HudConfig.fullscreenLyricStyle = index;
        HudConfig.save();
        return values[index];
    }
}
