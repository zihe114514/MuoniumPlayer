package com.muoniumplayer.core.screens.ncm;

import java.util.List;

/**
 * 对唱（duet）分组：把时间轴上真正重叠的歌词行归成一组，供渲染层同时点亮。
 *
 * <p>一首歌里的对唱段落在 TTML 里就是两个时间区间互相重叠的 &lt;p&gt;（Apple Music 用
 * {@code ttm:agent="v1"/"v2"} 区分歌手）。普通歌词一行接一行，任何时刻只有一行在唱；对唱段落
 * 里两位歌手同时在唱，只点亮其中一行会让另一行看起来像已经唱过或还没开始，而"当前行"还会在
 * 两行之间来回跳。分组在歌词提交时算一次并写在行对象上，渲染层只读不算。</p>
 */
public final class LyricDuetGroups {

    /** 判定为对唱所需的最小交集时长。 */
    public static final long MIN_OVERLAP_MILLIS = 500L;

    /**
     * 交集还必须占到较短那一行的这个比例。
     *
     * <p>只看绝对交集会误判：解析 TTML 时，一行的窗口会顺延到它自己的和声（{@code x-bg}）唱完，
     * 而和声经常唱进下一行，于是相邻两行会出现一两秒的"尾巴重叠"。真正的对唱是两人唱同一段，
     * 重叠占据整行的大部分；和声尾巴只占一小截。没有这道护栏，和声密集的歌会被整首判成对唱。</p>
     */
    private static final double MIN_OVERLAP_RATIO = 0.45;

    /** 一组最多容纳的行数。超出上限一律当作解析异常，宁可退回普通单行显示。 */
    public static final int MAX_GROUP_SIZE = 3;

    private LyricDuetGroups() {
    }

    /**
     * 重新计算整条时间轴的分组编号。列表必须已按时间戳排序（{@code prepare} 里已排过）。
     * 每行的 {@link LyricLine#duetGroup} 会被覆盖写入，{@code -1} 表示独唱行。
     */
    public static void mark(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return;
        for (LyricLine line : lines) {
            if (line != null) {
                line.duetGroup = -1;
                line.duetSlot = -1;
            }
        }

        int nextGroup = 0;
        int index = 0;
        while (index < lines.size() - 1) {
            if (!overlaps(lines.get(index), lines.get(index + 1))) {
                index++;
                continue;
            }

            int end = index + 1;
            while (end + 1 < lines.size()
                    && end - index + 1 < MAX_GROUP_SIZE
                    && overlaps(lines.get(end), lines.get(end + 1))) {
                end++;
            }
            // 链条比上限还长，说明这份歌词的行窗口整体不可信，整段都不进入对唱模式。
            boolean overlong = end + 1 < lines.size()
                    && end - index + 1 >= MAX_GROUP_SIZE
                    && overlaps(lines.get(end), lines.get(end + 1));
            if (!overlong) {
                int group = nextGroup++;
                for (int i = index; i <= end; i++) {
                    lines.get(i).duetGroup = group;
                    // 组内序号决定左右列：第一位歌手靠左，第二位靠右，三人组再从左边开始。
                    lines.get(i).duetSlot = i - index;
                }
            }
            index = end + 1;
        }
    }

    /** 两行是否属于同一个对唱组。独唱行（组号 -1）永远返回 false，包括与自己比较。 */
    public static boolean sharesGroup(LyricLine one, LyricLine other) {
        if (one == null || other == null) return false;
        return one.duetGroup >= 0 && one.duetGroup == other.duetGroup;
    }

    /** 这一行是否应当与当前行同时点亮：它就是当前行，或与当前行同组。 */
    public static boolean isActive(LyricLine line, LyricLine current) {
        if (line == null || current == null) return false;
        return line == current || sharesGroup(line, current);
    }

    /** 给定行所在组的第一行下标；不在组里时返回 {@code index} 本身。 */
    public static int groupStart(List<LyricLine> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) return index;
        LyricLine line = lines.get(index);
        if (line == null || line.duetGroup < 0) return index;
        int start = index;
        while (start > 0 && sharesGroup(lines.get(start - 1), line)) start--;
        return start;
    }

    /** 给定行所在组的最后一行下标；不在组里时返回 {@code index} 本身。 */
    public static int groupEnd(List<LyricLine> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) return index;
        LyricLine line = lines.get(index);
        if (line == null || line.duetGroup < 0) return index;
        int end = index;
        while (end + 1 < lines.size() && sharesGroup(lines.get(end + 1), line)) end++;
        return end;
    }

    /**
     * 对唱行占歌词区宽度的比例。
     *
     * <p>不取 0.5：一半宽度会让中文歌词几乎每句都折行，两列各三四行叠在一起反而更难读。0.74
     * 是"左右能明显看出错开、又不会把常见句长逼到折行"的折中，两列在中间重叠约一半区域，
     * 视觉上正是 Apple Music 那种交错排布。</p>
     */
    public static final double COLUMN_RATIO = 0.74;

    /** 这一行的可用宽度。独唱行占满整个歌词区。 */
    public static double columnWidth(double width, int duetSlot) {
        return duetSlot < 0 ? width : width * COLUMN_RATIO;
    }

    /** 这一行的左边界。偶数序号贴左，奇数序号贴右。 */
    public static double columnLeft(double left, double width, int duetSlot) {
        if (duetSlot < 0 || !rightAligned(duetSlot)) return left;
        return left + width - columnWidth(width, duetSlot);
    }

    /** 这一行是否右对齐（右列）。 */
    public static boolean rightAligned(int duetSlot) {
        return duetSlot >= 0 && (duetSlot & 1) == 1;
    }

    /** 两行时间窗口的交集毫秒数；任一行没有可用窗口时返回 0。 */
    public static long overlapMillis(LyricLine one, LyricLine other) {
        if (one == null || other == null) return 0L;
        if (one.duration <= 0L || other.duration <= 0L) return 0L;
        long start = Math.max(one.getTimestamp(), other.getTimestamp());
        long end = Math.min(one.getTimestamp() + one.duration, other.getTimestamp() + other.duration);
        return Math.max(0L, end - start);
    }

    private static boolean overlaps(LyricLine one, LyricLine other) {
        if (one == null || other == null) return false;
        // 间奏占位行没有歌手，永远不参与对唱。
        if (one.isBreakLine || other.isBreakLine) return false;
        long overlap = overlapMillis(one, other);
        if (overlap < MIN_OVERLAP_MILLIS) return false;
        long shorter = Math.min(one.duration, other.duration);
        return shorter > 0L && overlap >= shorter * MIN_OVERLAP_RATIO;
    }
}
