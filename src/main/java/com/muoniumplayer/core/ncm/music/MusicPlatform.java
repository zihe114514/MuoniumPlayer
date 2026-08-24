package com.muoniumplayer.core.ncm.music;

/**
 * 当前播放器所使用的音乐平台。该枚举属于宿主 MOD，避免 UI 直接依赖 Cadence 的实现类型。
 */
public enum MusicPlatform {
    NETEASE("网易云", 0xE94747),
    QQ("QQ音乐", 0x31C27C),
    KUGOU("酷狗音乐", 0x1F9CF0),
    GD("GD音乐台", 0x3D5AFE);

    private final String displayName;
    private final int brandColor;

    MusicPlatform(String displayName, int brandColor) {
        this.displayName = displayName;
        this.brandColor = brandColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getBrandColor() {
        return brandColor;
    }

    /**
     * 该平台是否由 Cadence（{@code music-api} 库）承载。
     *
     * <p>酷狗与 GD 都是宿主自己实现的，{@link #toCadenceSource()} 对它们只能给出一个不正确的兜底值；
     * 任何要走 Cadence 的调用点都必须先问这个方法，而不是直接转换。</p>
     */
    public boolean isCadenceBacked() {
        return this == NETEASE || this == QQ;
    }

    /**
     * @deprecated 只对 {@link #isCadenceBacked()} 为真的平台有意义；其余平台会落到 NETEASE 兜底。
     */
    public top.fpsmaster.music.MusicSource toCadenceSource() {
        return this == QQ ? top.fpsmaster.music.MusicSource.QQ : top.fpsmaster.music.MusicSource.NETEASE;
    }

    public static MusicPlatform fromCadence(top.fpsmaster.music.MusicSource source) {
        return source == top.fpsmaster.music.MusicSource.QQ ? QQ : NETEASE;
    }
}
