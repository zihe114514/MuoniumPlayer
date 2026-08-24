package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.rendering.animation.Interpolations;

/**
 * 内容区的「导航等待」遮罩：点了导航栏里那几个要先取网络才知道去哪的入口时，
 * 在<b>当前页面之上</b>转一枚转子，取到数据后一次性切到目标页。
 *
 * <h3>它解决的是"跳转两次"</h3>
 * <p>原来这几个入口（排行榜 / 最近播放 / 我的数字专辑）是先切到一个空的中转面板显示"正在获取…"，
 * 数据回来再切到真正的页面 —— 用户看到<b>两次</b>页面淡入，而且历史栈里被压进两条记录，
 * 按返回会退到那个中转面板、它的 {@code onInit} 又重新加载一次再把人送走，返回键实际上失效。
 * 把等待放在当前页面之上就只有一次切换、一条历史。</p>
 *
 * <h3>为什么必须有超时与自动消失</h3>
 * <p>加载线程正常结束时一定会回调清除，但网络库卡死、异常被吞掉、或者玩家在等待期间关了播放器
 * 又打开，都可能让转子永远转下去 —— 那比没有反馈更糟。所以有两道保护：
 * 加载态有 {@link #DEFAULT_TIMEOUT_NANOS} 硬上限，提示态有 {@link #MESSAGE_HOLD_NANOS} 自动过期。</p>
 */
public final class NavigationLoadingOverlay {

    /** 加载态的硬上限：超过就换成超时提示，不再无限转。 */
    static final long DEFAULT_TIMEOUT_NANOS = 25_000_000_000L;

    /** 提示态（失败/为空）停留多久后自动淡出。 */
    static final long MESSAGE_HOLD_NANOS = 2_200_000_000L;

    /** 淡入淡出速度，与面板切换的 alpha 插值同一量级。 */
    private static final float FADE_SPEED = .24f;

    private final PanelLoadingWidget spinner = new PanelLoadingWidget(this::visibleText);

    /** 当前应当显示的文案；{@code null} = 该淡出了。 */
    private volatile String text;
    private volatile boolean spinning;
    private volatile long startNanos;
    /** 自动过期时刻；{@code 0} = 不自动过期（加载态靠超时那条路）。 */
    private volatile long expiryNanos;

    /** 淡出期间沿用最后一次的文案与形态，否则字会在淡出第一帧就消失。 */
    private String fadingText = "";
    private boolean fadingSpinning;
    private float alpha;

    /**
     * 这一帧是否需要画。淡出期间 {@code text} 已经是 {@code null} 但 {@code alpha} 还没归零，
     * 此时仍要继续调 {@link #render} —— 不然透明度插值会停在半路上，遮罩卡着不消失。
     */
    public boolean isVisible() {
        return text != null || alpha > .015f;
    }

    /** 是否正在等一次导航。用来忽略等待期间的重复点击，不然连点会发好几个请求。 */
    public boolean isLoading() {
        return spinning && text != null;
    }

    /** 开始等待。{@code label} 是给用户看的目标名，例如「排行榜」。 */
    public void beginLoading(String label) {
        String safe = label == null ? "" : label.trim();
        this.text = safe.isEmpty() ? "正在获取…" : "正在获取" + safe + "…";
        this.spinning = true;
        this.startNanos = System.nanoTime();
        this.expiryNanos = 0L;
    }

    /** 换成一条会自动消失的提示（加载失败、结果为空）。 */
    public void showMessage(String message) {
        String safe = message == null ? "" : message.trim();
        this.text = safe.isEmpty() ? "获取失败" : safe;
        this.spinning = false;
        this.startNanos = System.nanoTime();
        this.expiryNanos = this.startNanos + MESSAGE_HOLD_NANOS;
    }

    /** 成功切页时调用：立刻收起，不留提示。 */
    public void clear() {
        this.text = null;
        this.spinning = false;
        this.expiryNanos = 0L;
    }

    /**
     * 每帧画一次。会顺手处理超时与自动过期。
     *
     * @param parentAlpha 播放器整体透明度，遮罩不该比播放器本身更实
     */
    public void render(double x, double y, double width, double height, float parentAlpha) {
        long now = System.nanoTime();

        String current = text;
        if (current != null) {
            if (isExpired(expiryNanos, now)) {
                clear();
                current = null;
            } else if (isTimedOut(spinning, startNanos, now, DEFAULT_TIMEOUT_NANOS)) {
                showMessage("获取超时，请稍后再试");
                current = text;
            }
        }

        if (current != null) {
            fadingText = current;
            fadingSpinning = spinning;
        }

        alpha = Interpolations.interpolate(alpha, current != null ? 1f : 0f, FADE_SPEED);
        if (alpha <= .015f) {
            alpha = 0f;
            return;
        }
        spinner.paint(x, y, width, height, alpha * Math.max(0f, Math.min(1f, parentAlpha)), fadingSpinning);
    }

    /** 供 {@link PanelLoadingWidget} 取文案。淡出期间返回的是最后一次的文案。 */
    private String visibleText() {
        String current = text;
        return current != null ? current : fadingText;
    }

    /** {@code expiry == 0} 表示不自动过期。分开成静态纯函数是为了能离线校验。 */
    static boolean isExpired(long expiryNanos, long nowNanos) {
        return expiryNanos != 0L && nowNanos - expiryNanos >= 0L;
    }

    /** 只有加载态才会超时；用差值比较而不是直接比大小，避免 nanoTime 溢出时误判。 */
    static boolean isTimedOut(boolean spinning, long startNanos, long nowNanos, long timeoutNanos) {
        return spinning && nowNanos - startNanos >= timeoutNanos;
    }
}