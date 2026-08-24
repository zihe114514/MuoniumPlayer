package com.muoniumplayer.core.screens.hud;

/** Immutable geometry snapshot for one HUD editor frame. */
final class HudEditorLayout {

    private HudEditorLayout() {
    }

    static Metrics calculate(int screenHeight, boolean settingsCollapsed,
                             boolean currentExpanded, boolean normalExpanded, boolean islandExpanded,
                             boolean coverExpanded, boolean automixExpanded, boolean storageExpanded,
                             boolean themeExpanded,
                             int settingsMargin, int collapsedSettingsHeight, int settingsHeaderHeight,
                             int sectionHeight, int colorRowHeight, int sliderRowHeight,
                             int currentSliderCount, int normalSliderCount, int islandSliderCount,
                             int themeGridHeight, int pickerWidth) {
        int panelHeight = settingsCollapsed ? collapsedSettingsHeight
                : Math.max(132, screenHeight - settingsMargin - 42);
        // 当前部分 / 普通部分 / 灵动岛 / 封面 / 无缝切换 / 存储 / 主题，七个分组标题始终占位。
        int contentHeight = sectionHeight * 7 + 8;
        if (currentExpanded) contentHeight += colorRowHeight * 3 + currentSliderCount * sliderRowHeight;
        if (normalExpanded) contentHeight += colorRowHeight * 3 + normalSliderCount * sliderRowHeight;
        // 灵动岛分组：开关、样式、常驻总开关、常驻三项状态内容、常驻正在播放、常驻轮换，
        // 共 8 行等高控件。
        if (islandExpanded) contentHeight += colorRowHeight * 8 + islandSliderCount * sliderRowHeight;
        if (coverExpanded) contentHeight += colorRowHeight;
        // 无缝切换分组：总开关、重叠时长、跳过前奏、小节对齐、低频交换、去人声淡入、节奏锁定、
        // 音高跳跃，共 8 行等高控件。
        if (automixExpanded) contentHeight += colorRowHeight * 8;
        // 存储分组：缓存上限、立即清理，两行等高控件。
        if (storageExpanded) contentHeight += colorRowHeight * 2;
        if (themeExpanded) contentHeight += colorRowHeight + themeGridHeight;
        return new Metrics(panelHeight, contentHeight, settingsMargin, settingsHeaderHeight, pickerWidth);
    }

    static final class Metrics {
        final int panelHeight;
        final int contentHeight;
        private final int settingsMargin;
        private final int settingsHeaderHeight;
        private final int pickerWidth;

        private Metrics(int panelHeight, int contentHeight, int settingsMargin,
                        int settingsHeaderHeight, int pickerWidth) {
            this.panelHeight = panelHeight;
            this.contentHeight = contentHeight;
            this.settingsMargin = settingsMargin;
            this.settingsHeaderHeight = settingsHeaderHeight;
            this.pickerWidth = pickerWidth;
        }

        int maxScroll(int renderedPanelHeight) {
            return Math.max(0, contentHeight - (renderedPanelHeight - settingsHeaderHeight - 2));
        }

        int clampScroll(int value, int renderedPanelHeight) {
            return Math.max(0, Math.min(maxScroll(renderedPanelHeight), value));
        }

        int pickerX(int panelX) {
            return Math.max(settingsMargin, panelX - pickerWidth - 8);
        }

        int pickerY(int panelY) {
            return panelY + settingsHeaderHeight;
        }
    }
}