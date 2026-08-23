package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland.DynamicIslandStyle;
import com.muoniumplayer.core.settings.HudConfig;

/** Calculates text-aware Dynamic Island bounds without mutating animation state. */
final class DynamicIslandLayoutCalculator {

    /** Reference base width the per-style minimum widths were tuned against. */
    static final double DEFAULT_BASE_WIDTH = 250.0;
    static final double MIN_BASE_WIDTH = 160.0;
    static final double MAX_BASE_WIDTH = 720.0;

    /**
     * 文案放不下时，灵动岛最多可以伸长到「屏幕可用宽度」的这个比例。
     *
     * <p>「灵动岛基础宽度」是<b>静息宽度</b>而不是硬上限：短文案时灵动岛停在基础宽度上（滑块
     * 依旧直接可见），文案更长时它自己伸长去装下全文，而不是把后半段用省略号吃掉。这里留出
     * 约两成余量，是为了让再长的文案也不会顶到屏幕左右边缘、始终看得出这是一枚悬浮胶囊。</p>
     *
     * <p>伸长之后如果<i>连屏幕都装不下</i>，才会依次退化为「缩字号」→「省略号」，
     * 见 {@link #calculate} 里的三级降级。</p>
     */
    static final double GROWTH_SCREEN_FRACTION = .82;

    private DynamicIslandLayoutCalculator() {
    }

    static LayoutData calculate(DynamicIslandStyle style, boolean noticeMode, boolean volumeNotice,
                                String noticeTitleValue, String noticeBodyValue, String speedValue,
                                boolean preview, double configuredScale, double screenWidth,
                                double compactWidth, double minAutoTextScale, double maxAutoTextScale) {
        String title;
        String value;
        if (noticeMode) {
            title = safeValue(noticeTitleValue, "状态");
            value = safeValue(noticeBodyValue, "—");
        } else {
            String speed = speedValue == null ? "" : speedValue.trim();
            title = preview ? "灵动岛预览" : "正在加载歌曲";
            value = preview ? speed : (speed.isEmpty() || "0 b/s".equalsIgnoreCase(speed)
                    ? "正在连接音频源" : speed);
        }

        boolean systemCard = style == DynamicIslandStyle.SYSTEM_CARD;
        boolean musicFocus = style == DynamicIslandStyle.MUSIC_FOCUS;
        double minWidth = styleMinWidth(style, noticeMode);
        double targetHeight = styleTargetHeight(style, noticeMode);

        if (volumeNotice) {
            targetHeight += systemCard ? 8.0 : 7.0;
        }

        /*
         * 必须按「渲染时真正用的那个字体」量文本，否则量出来的宽度和实际排版对不上，
         * 岛会按偏小的宽度定尺寸、真正绘制时反而放不下，于是又出现省略号。
         *
         * 通知态：标题 pf12、正文 pf14bold。
         * 下载态：标题 pf14bold（"正在加载歌曲" / "加载完成"）、正文 pf12（速度 / "可以开始播放"）。
         * 这两组是反过来的，原来统一按通知态量，下载态的标题因此被低估了一号字。
         */
        double titleWidth = noticeMode
                ? FontManager.pf12.getStringWidthD(title)
                : FontManager.pf14bold.getStringWidthD(title);
        double valueWidth = noticeMode
                ? FontManager.pf14bold.getStringWidthD(value)
                : FontManager.pf12.getStringWidthD(value);
        double widestText = Math.max(titleWidth, valueWidth);
        double configuredTextScale = DynamicIslandMath.clamp(HudConfig.dynamicIslandTextScale,
                minAutoTextScale, maxAutoTextScale);
        double sideReserve = styleSideReserve(style);
        if (!noticeMode) {
            sideReserve += Math.max(31.0, FontManager.pf12bold.getStringWidthD("100%")
                    * configuredTextScale + 14.0);
        }

        double screenMaxWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        /*
         * "灵动岛基础宽度" is a real base width, not a passive ceiling. Previously it was only
         * fed into Math.max(configured, desired), so the natural text width always won and dragging
         * the slider changed nothing. It therefore scales the per-style minimum (resting) width.
         *
         * 但它<b>不再</b>同时当作硬上限：长文案会被截成"前半段…"，后半段完全看不到。现在改成
         * 三级降级 —— ① 优先让灵动岛自己伸长去装下全文（上限是屏幕可用宽度的
         * GROWTH_SCREEN_FRACTION）；② 连屏幕都装不下才缩字号（下限 minAutoTextScale）；
         * ③ 缩到底还是放不下才由渲染层补省略号。日常长度的歌名/失败原因在第 ① 级就解决了。
         */
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        double baseWidthRatio = configuredBaseWidth / DEFAULT_BASE_WIDTH;
        double scaledMinWidth = minWidth * baseWidthRatio;
        double maxWidth = growthCeiling(scaledMinWidth, configuredBaseWidth, screenMaxWidth);
        double safeMinWidth = Math.min(scaledMinWidth, maxWidth);
        double textScale = configuredTextScale;
        if (widestText > .01) {
            double availableAtMax = Math.max(28.0, maxWidth - sideReserve);
            textScale = Math.min(textScale, Math.max(minAutoTextScale, availableAtMax / widestText));
        }
        double targetWidth = DynamicIslandMath.clamp(sideReserve + widestText * textScale, safeMinWidth, maxWidth);
        return new LayoutData(title, value, textScale, targetWidth, targetHeight);
    }

    /**
     * 常驻状态的尺寸。常驻卡片与通知卡片都是「左图标 + 一行内容」的同一种版式,
     * 因此直接复用通知态的最小宽度与高度表,不再维护第二套数字。
     *
     * @param contentWidth 已按固定位数模板量好的常驻内容宽度,数值跳动不会改变它
     */
    static LayoutData calculateAmbient(DynamicIslandStyle style, double contentWidth,
                                       double configuredScale, double screenWidth, double compactWidth) {
        double minWidth = styleMinWidth(style, true);
        double targetHeight = styleTargetHeight(style, true);
        double sideReserve = styleSideReserve(style);
        double screenMaxWidth = Math.max(compactWidth, (screenWidth - 12.0) / configuredScale);
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        double scaledMinWidth = minWidth * (configuredBaseWidth / DEFAULT_BASE_WIDTH);
        // 与通知态同一条伸长规则：常驻的「正在播放」页歌名长度差异很大，卡在基础宽度上就只能
        // 截断。宽度变化本身有动画，换歌时看到的是一次平滑变形而不是跳变。
        double maxWidth = growthCeiling(scaledMinWidth, configuredBaseWidth, screenMaxWidth);
        double safeMinWidth = Math.min(scaledMinWidth, maxWidth);
        double targetWidth = DynamicIslandMath.clamp(sideReserve + Math.max(0.0, contentWidth),
                safeMinWidth, maxWidth);
        return new LayoutData("", "", 1.0, targetWidth, targetHeight);
    }

    /** 常驻内容可用的横向空间,渲染层据此决定要不要丢掉优先级最低的条目。 */
    static double ambientContentBudget(DynamicIslandStyle style, double configuredScale, double screenWidth) {
        double screenMaxWidth = Math.max(60.0, (screenWidth - 12.0) / configuredScale);
        double configuredBaseWidth = DynamicIslandMath.clamp(HudConfig.dynamicIslandMaxWidth,
                MIN_BASE_WIDTH, MAX_BASE_WIDTH);
        // 预算同样按伸长后的上限给，否则常驻内容会先被"缩字号"这一级吃掉，
        // 灵动岛还没来得及伸长就已经把歌名压扁了。
        double maxWidth = growthCeiling(0.0, configuredBaseWidth, screenMaxWidth);
        return Math.max(24.0, maxWidth - styleSideReserve(style));
    }

    /**
     * 灵动岛允许伸长到的最大宽度。
     *
     * <p>至少要能装下按样式最小宽度和基础宽度（否则窄屏上滑块会被无声吞掉），并允许一直长到
     * 屏幕可用宽度的 {@link #GROWTH_SCREEN_FRACTION}；最终一定不超过屏幕可用宽度。</p>
     */
    private static double growthCeiling(double scaledMinWidth, double configuredBaseWidth,
                                        double screenMaxWidth) {
        double preferred = Math.max(Math.max(scaledMinWidth, configuredBaseWidth),
                screenMaxWidth * GROWTH_SCREEN_FRACTION);
        return Math.min(screenMaxWidth, preferred);
    }

    private static double styleMinWidth(DynamicIslandStyle style, boolean noticeMode) {
        if (style == DynamicIslandStyle.COMPACT) return noticeMode ? 118.0 : 146.0;
        if (style == DynamicIslandStyle.CARD) return noticeMode ? 146.0 : 170.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return noticeMode ? 172.0 : 194.0;
        if (style == DynamicIslandStyle.SYSTEM_CARD) return noticeMode ? 154.0 : 176.0;
        // 液态玻璃要给折射亮带和边缘光留出余量,所以比通透玻璃再宽一点。
        if (style == DynamicIslandStyle.LIQUID_GLASS) return noticeMode ? 142.0 : 168.0;
        if (style == DynamicIslandStyle.GLASS) return noticeMode ? 132.0 : 158.0;
        return noticeMode ? 126.0 : 152.0;
    }

    private static double styleTargetHeight(DynamicIslandStyle style, boolean noticeMode) {
        if (style == DynamicIslandStyle.COMPACT) return noticeMode ? 30.0 : 32.0;
        if (style == DynamicIslandStyle.CARD) return noticeMode ? 38.0 : 42.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return noticeMode ? 50.0 : 56.0;
        if (style == DynamicIslandStyle.SYSTEM_CARD) return noticeMode ? 46.0 : 52.0;
        if (style == DynamicIslandStyle.LIQUID_GLASS) return noticeMode ? 38.0 : 42.0;
        if (style == DynamicIslandStyle.GLASS) return noticeMode ? 34.0 : 38.0;
        return noticeMode ? 34.0 : 38.0;
    }

    /** 左侧图标托盘与右侧留白合计要占掉的宽度。 */
    private static double styleSideReserve(DynamicIslandStyle style) {
        if (style == DynamicIslandStyle.SYSTEM_CARD) return 60.0;
        if (style == DynamicIslandStyle.MUSIC_FOCUS) return 68.0;
        if (style == DynamicIslandStyle.LIQUID_GLASS) return 50.0;
        if (style == DynamicIslandStyle.CARD) return 48.0;
        return 46.0;
    }

    private static String safeValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static final class LayoutData {
        final String title;
        final String value;
        final double textScale;
        final double targetWidth;
        final double targetHeight;

        private LayoutData(String title, String value, double textScale, double targetWidth, double targetHeight) {
            this.title = title;
            this.value = value;
            this.textScale = textScale;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }
    }
}