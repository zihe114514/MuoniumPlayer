package com.muoniumplayer.core.rendering.texture;

import com.muoniumplayer.core.ncm.music.CadenceMusicService;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.utils.Location;

/**
 * 当前登录账号的头像贴图，带节流的按需加载。
 *
 * <p>为什么不能直接在渲染里调 {@link Textures#downloadTextureAndLoadAsync}：那个方法每调一次就起一条
 * 异步下载，而贴图上传是异步的 —— 从发起下载到 {@code getTexture} 能拿到东西之间有几十帧，
 * 期间「贴图还是 null」会成立几十次。每帧都调一遍就是每帧起一条 HTTP 下载。头像地址拿不到图
 * （账号头像 404、断网）时更糟，那是永久的每帧重试。</p>
 *
 * <p>所以这里记住「上一次为哪个地址发起过下载、什么时候」：同一个地址在 {@link #RETRY_INTERVAL_NANOS}
 * 之内只发一次。<b>换账号会换地址</b>，于是立刻重新下载而不用等冷却 —— 这也是为什么不需要一个
 * 显式的「退登时清缓存」入口：冷却只对同一个地址生效，而地址是跟着账号变的。</p>
 */
public final class AccountAvatar {

    /** 同一个地址的重试间隔。下载失败也就每 5 秒一次，不会把渲染循环变成压测。 */
    private static final long RETRY_INTERVAL_NANOS = 5_000_000_000L;

    private static volatile String requestedUrl = "";
    private static volatile long requestedNanos;

    private AccountAvatar() {
    }

    /**
     * 已就绪的账号头像贴图；顺便按需触发一次下载。
     *
     * @return 贴图对象，或 {@code null} —— 未登录、账号没有头像、贴图还没上传完，三种情况都返回 null，
     *         调用方一律走自己的兜底（群唱样式退回歌曲封面）
     */
    public static ITextureObject textureOrNull() {
        Location location = CadenceMusicService.getAccountAvatarLocation();
        if (location == null) return null;

        ITextureObject texture = TextureManager.getInstance().getTexture(location);
        if (texture != null) return texture;

        String url = CadenceMusicService.getAccountAvatarUrl();
        if (url == null || url.trim().isEmpty()) return null;

        long now = System.nanoTime();
        if (!shouldRequest(url, requestedUrl, requestedNanos, now)) return null;
        requestedUrl = url;
        requestedNanos = now;
        Textures.downloadTextureAndLoadAsync(url, location);
        return null;
    }

    /**
     * 这一刻该不该为 {@code url} 发起下载。
     *
     * <p>抽成纯函数是为了能离线校验：{@link #textureOrNull()} 本体要摸 GL 贴图表，测不了。</p>
     *
     * @param lastUrl   上一次发起过下载的地址；{@code ""} 表示还没发起过
     * @param lastNanos 上一次发起的时间戳（{@link System#nanoTime()} 口径）
     */
    static boolean shouldRequest(String url, String lastUrl, long lastNanos, long nowNanos) {
        if (url == null || url.trim().isEmpty()) return false;
        // 换了地址（换账号）就立刻重新下，不用等冷却结束。
        if (!url.equals(lastUrl)) return true;
        // nanoTime 的原点是任意的，理论上可能出现负差值；负差值一律当作"冷却已过"处理，
        // 否则一次时钟异常会让头像永远不再重试。
        long elapsed = nowNanos - lastNanos;
        return elapsed < 0 || elapsed >= RETRY_INTERVAL_NANOS;
    }
}