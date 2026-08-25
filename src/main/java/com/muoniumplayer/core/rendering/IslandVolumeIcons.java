package com.muoniumplayer.core.rendering;

/**
 * 灵动岛音量/仪表图标的码位（字体资源 {@code muonium/fonts/island-volume-icons.ttf}）。
 *
 * <p>又单独开一份字体，原因和 {@link IslandStatusIcons} 一样：fontello 的每一批导出都从
 * 私用区起始码位重新编号，合并会互相覆盖。<b>注意这份字体的 U+E902/E903/E904 与
 * {@code island-status-icons} 的对勾/链条/心跳码位相同但字形完全不同</b>，取字形时必须连字体
 * 一起对上（{@code FontManager.islandVolumeIcon12/16}），不能只看码位。</p>
 *
 * <p>字形来源是 fontello 的 elusive 一组（原包码位 U+E800 volume-off、U+E801 volume-down、
 * U+E802 volume、U+E803 volume-up、U+E804 gauge），为了对上用户给的编号，导出后把 cmap
 * 逆序重映射到 U+E902~U+E906，字形轮廓逐像素未变。</p>
 */
public final class IslandVolumeIcons {

    /** 仪表盘：帧率（常驻状态行与静止时的左侧标记）。 */
    public static final String GAUGE = "\ue902";
    /** 喇叭 + 三道声波：音量高档。 */
    public static final String VOLUME_HIGH = "\ue903";
    /** 喇叭 + 两道声波：音量中档。 */
    public static final String VOLUME_MID = "\ue904";
    /** 喇叭 + 一道声波：音量低档。 */
    public static final String VOLUME_LOW = "\ue905";
    /** 无声波的喇叭：静音 / 极低音量。 */
    public static final String VOLUME_MUTE = "\ue906";

    private IslandVolumeIcons() {
    }
}
