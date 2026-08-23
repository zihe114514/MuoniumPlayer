package com.muoniumplayer.core.rendering;

/**
 * 灵动岛状态/结果图标的码位（字体资源 {@code muonium/fonts/island-status-icons.ttf}）。
 *
 * <p>刻意与 {@link FontelloIcons} 分成两份字体：两者都是 fontello 导出，但批次不同，
 * 私用区码位互相冲突（旧包已经占了 U+E800~E802）。合并成一份会让登录/来源图标错位，
 * 所以新批次单独成字体、单独成常量类。</p>
 */
public final class IslandStatusIcons {

    /** A-Z：排序 / 字体设置。 */
    public static final String SORT_AZ = "\ue803";
    /** 上传托盘：网易云上报进行中。 */
    public static final String UPLOAD = "\ue807";
    /** 下载托盘：下载进行中 / 下载相关结果。 */
    public static final String DOWNLOAD = "\ue808";
    /** 时钟：系统时间。 */
    public static final String CLOCK = "\ue901";
    /** 对勾：一切"成功"结果统一用这一枚。 */
    public static final String SUCCESS = "\ue902";
    /** 链条：连接成功 / 网络延迟。 */
    public static final String LINK = "\ue903";
    /** 心跳：帧率（静止时的常驻标记）。 */
    public static final String PULSE = "\ue904";

    private IslandStatusIcons() {
    }
}
