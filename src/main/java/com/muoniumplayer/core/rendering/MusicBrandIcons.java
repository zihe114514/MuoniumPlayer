package com.muoniumplayer.core.rendering;

/** Code points from the separately bundled provider-brand icon font. */
public final class MusicBrandIcons {

    /** QQ Music brand glyph from fontello-54446a4c; separate from the QQ login icon. */
    public static final String QQ_MUSIC = "\ue888";

    public static final String NETEASE_CLOUD_MUSIC = "\ue801";

    /**
     * 酷狗音乐的字标。
     *
     * <p>这里是一个<b>汉字</b>而不是私有区码位：music-brand-icons 与 qq-music-icons 两份图标字体里
     * 都没有酷狗的品牌字形，塞一个不存在的码位进去只会画出空白（QQ 的 U+E888 之前就踩过这个坑）。
     * 用正文字体里必然存在的「酷」当字标，任何字号下都画得出来，也仍然跟随卡片的前景色。等以后补上
     * 真正的品牌字形，只需要把这一个常量和取字体的那一处换掉。</p>
     */
    public static final String KUGOU_MONOGRAM = "酷";
    public static final String PERSONAL_FM = "\ue802";

    private MusicBrandIcons() {
    }
}
