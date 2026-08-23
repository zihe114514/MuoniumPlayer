package com.muoniumplayer.core.screens.ncm;

/** Stateless viewport, timing and sizing calculations for the full-screen lyric panel. */
public final class LyricsPanelGeometry {

    private LyricsPanelGeometry() {
    }

    static double contentScale(float alpha) {
        return .96 + (Math.max(0.0f, Math.min(1.0f, alpha)) * .04);
    }

    static double smoothKaraokeProgress(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    static double characterKaraokeProgress(double wordProgress, int characterIndex) {
        return smoothKaraokeProgress(wordProgress - characterIndex);
    }

    static double lyricLineSpacing() {
        return 20;
    }

    static double lyricAnchorFraction() {
        return .25;
    }

    static Viewport createViewport(double posX, double posY, double width, double height, double borderThickness) {
        double horizontalInset = Math.max(8.0, width * .04);
        double verticalInset = Math.max(borderThickness, Math.min(10.0, height * .018));
        double left = posX + width * .48;
        double right = Math.max(left, posX + width - horizontalInset);
        double top = posY + verticalInset;
        double bottom = Math.max(top, posY + height - verticalInset);
        return new Viewport(left, top, right - left, bottom - top);
    }

    /**
     * 逐字高亮把当前字词以某个锚点放大（{@code currentWordScale}），布局坐标保持不变。
     *
     * <p>默认锚点是字词中心，但行首字词的左边缘正好压在歌词视口的裁剪边界上，按中心放大
     * 会有一半的放大量落到视口外被裁掉，表现为"首字被切掉一块"。这里把锚点约束到能让
     * 放大后的矩形完整留在 {@code [clipLeft, clipRight]} 内的区间：能用中心就用中心，
     * 贴边的字词则把锚点收拢到边界，只向视口内侧生长。</p>
     *
     * @param left     字词矩形在布局坐标系中的左边缘
     * @param width    字词矩形宽度（未放大）
     * @param scale    本帧的放大倍率，{@code <= 1} 时直接返回中心
     * @return 应当传给 translate/scale/translate 的 X 锚点
     */
    public static double clampScaleAnchorX(double left, double width, double scale, double clipLeft, double clipRight) {
        double center = left + width * .5;
        if (scale <= 1.0001 || width <= 0) return center;

        double right = left + width;
        // 放大后左边缘 = scale * left + anchor * (1 - scale)，要求 >= clipLeft；右边缘同理。
        double maxAnchor = (scale * left - clipLeft) / (scale - 1.0);
        double minAnchor = (scale * right - clipRight) / (scale - 1.0);

        // 放大后比视口还宽，无解：保持原来的中心放大行为。
        if (minAnchor > maxAnchor) return center;

        return Math.max(minAnchor, Math.min(maxAnchor, center));
    }

    static double coverSizeMax(double width, double height) {
        return Math.min(height * .46, width * .36);
    }

    static String formatDuration(float totalMillis) {
        float totalSeconds = totalMillis / 1000;
        float hours = totalSeconds / 3600;
        float minutes = (totalSeconds % 3600) / 60;
        float seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if ((int) hours > 0) {
            builder.append(String.format("%02d:", (int) hours));
        }
        builder.append(String.format("%02d:", (int) minutes));
        builder.append(String.format("%02d", (int) seconds));
        return builder.toString();
    }

    static final class Viewport {
        final double left;
        final double top;
        final double width;
        final double height;

        private Viewport(double left, double top, double width, double height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }
}