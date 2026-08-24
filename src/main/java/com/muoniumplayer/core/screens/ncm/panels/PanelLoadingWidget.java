package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

import java.util.function.Supplier;

/**
 * 播放器内容区通用的「正在加载」指示：12 段 iOS/macOS 风格转子 + 一行说明文字。
 *
 * <p>刻意<b>不画底板</b>：内容区背后是主题背景与液态玻璃，压一层半透明底板会让它看起来像个
 * 阻塞式对话框，而这里想要的是"页面自己在忙"。</p>
 *
 * <p>这份实现原来是 {@code PlaylistPanel} 的私有内部类，只服务歌单页。主页、发现页、
 * 以及导航栏那几个"点了要等网络"的入口都需要同一个东西，所以提到这里 —— 三处各画一份转子，
 * 迟早会在段数、转速、配色上分叉。</p>
 *
 * <h3>两种用法</h3>
 * <ul>
 *   <li>作为控件加进面板：布局照常走 {@code setBounds} / {@code setAlpha}，{@link #onRender} 自己画；</li>
 *   <li>不进控件树、由外部直接画（{@code NCMScreen} 的导航等待遮罩）：持有一个实例，
 *       每帧调 {@link #paint}。转子角度存在实例上，所以两种用法都是与帧率无关的匀速旋转。</li>
 * </ul>
 */
public final class PanelLoadingWidget extends AbstractWidget<PanelLoadingWidget> {

    private static final int SEGMENT_COUNT = 12;

    /** 转子中心相对控件中心上移这么多，给下面那行文字腾位置。 */
    private static final double SPINNER_CENTER_LIFT = 12.0;

    /** 文字基线相对转子中心下移这么多。 */
    private static final double TEXT_BASELINE_DROP = 17.0;

    private final Supplier<String> text;
    private float rotation;
    private boolean spinning = true;

    public PanelLoadingWidget(String text) {
        final String fixed = text == null ? "" : text;
        this.text = () -> fixed;
    }

    /** 文案会变时用这个重载（例如导航等待遮罩要显示点的是哪一项）。 */
    public PanelLoadingWidget(Supplier<String> text) {
        this.text = text == null ? () -> "" : text;
    }

    /**
     * 关掉转子、只留那一行字。用于「获取失败」这类终态提示 —— 转子的含义是"还在忙"，
     * 配在失败文案后面等于自相矛盾。
     */
    public PanelLoadingWidget setSpinning(boolean spinning) {
        this.spinning = spinning;
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        paint(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.getAlpha(), this.spinning);
    }

    /**
     * 在给定矩形的中心画一枚转子和一行文字，并推进转子角度。
     *
     * <p>角度增量取自 {@link RenderSystem#getFrameDeltaTime()}（约 10 ms 一个单位），
     * 不是"每帧固定加几度"：掉帧时转子走大步而不是变慢。</p>
     *
     * @param alpha    0..1；调用方负责淡入淡出，{@code <= .015} 时连角度都不推进（不可见就别转）
     * @param spinning {@code false} 只画那一行字。失败提示不该配着转子 ——
     *                 转子的含义是"还在忙"，配在"获取失败"后面等于自相矛盾
     */
    public void paint(double x, double y, double width, double height, float alpha, boolean spinning) {
        if (alpha <= .015f) return;

        double centerX = x + width * .5;
        double centerY = y + height * .5 - SPINNER_CENTER_LIFT;

        if (spinning) {
            rotation = (float) ((rotation + RenderSystem.getFrameDeltaTime() * 5.0) % 360.0);
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
                // 尾迹：越靠后的段越淡，平方一下让"头部"更集中，转起来才有方向感。
                float trail = 1f - segment / (float) SEGMENT_COUNT;
                float segmentAlpha = alpha * (.14f + .86f * trail * trail);

                api.getGLStateManager().pushMatrix();
                api.getGLStateManager().translate(centerX, centerY, 0);
                api.getGLStateManager().rotate(rotation + segment * (360f / SEGMENT_COUNT), 0, 0, 1);
                this.roundedRect(-1.65, -12, 3.3, 6.2, 1.65, RenderSystem.reAlpha(accent, segmentAlpha));
                api.getGLStateManager().popMatrix();
            }
        }

        String label = text.get();
        if (label == null || label.isEmpty()) return;
        double textWidth = FontManager.pf14.getStringWidthD(label);
        FontManager.pf14.drawString(label, centerX - textWidth * .5, centerY + TEXT_BASELINE_DROP,
                RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), alpha));
    }
}