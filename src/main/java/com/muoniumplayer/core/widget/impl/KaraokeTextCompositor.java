package com.muoniumplayer.core.widget.impl;

import org.lwjgl.opengl.GL11;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.Shaders;

/**
 * 桌面 OSD 逐字歌词的离屏合成器 —— 和全屏歌词页用的是同一套做法。
 *
 * <p>旧的 OSD 实现是「直接往屏幕上叠图层」：一个字先画一遍未唱底色，再画一圈 8 次偏移的光晕，
 * 再画一遍实心已唱色，最后按羽化条数（最多 10 条）把同一个字形又画 10 遍，每一遍都用
 * {@link com.muoniumplayer.core.rendering.ScissorClipManager} 裁一条竖窄条。这带来三个必然的画质问题：</p>
 *
 * <ol>
 *   <li><b>叠加层模糊</b>：同一个抗锯齿字形被 alpha 混合了十几遍，边缘覆盖率按
 *       {@code 1-(1-a)^k} 累积，软边被叠成又粗又实的一圈，字腔内部同时过曝。</li>
 *   <li><b>马赛克感</b>：scissor 裁剪框是向外取整到整屏幕像素的（{@code floor}/{@code ceil}），
 *       而 OSD 字号下每条羽化带还不到一个逻辑像素宽，取整后全部被撑成一两个像素宽并互相重叠，
 *       于是填涂前沿变成一排硬边竖块，前沿本身也只能按整像素跳。</li>
 *   <li><b>字形发虚</b>：字形图集是按 2 倍超采样再精确 0.5 倍缩小来保证锐利的，逐字放大所用的
 *       非整数倍率直接作用在屏幕上，必然重采样。</li>
 * </ol>
 *
 * <p>换成离屏合成之后，一个字词只会有「一次未唱底色 + 一次合成」两次屏幕绘制：字形与光晕画进
 * 超采样的 base 纹理，填涂进度画进同尺寸的 mask 纹理（实心矩形 + 亚像素渐变，不再受整像素约束），
 * 最后由 {@code stencil.frag} 用 {@code base.a * mask.a} 一次合成出来。纹理与四边形一一对应，
 * 逐字放大也变成套在 base 纹理上的整体变换，不会再出现遮罩放大而字形没放大的重影与脱节。</p>
 *
 * <p><b>兜底</b>：OSD 画在 Minecraft 共享的 HUD framebuffer 上，OptiFine 或其它 Mod 有可能让离屏
 * 路径不可用。因此这里整段包了 {@code Throwable} 捕获，一旦出错就永久置 {@link #unavailable}，
 * 调用方会回到原来的 scissor 逐条羽化路径，只是画质回到改动之前，功能不会丢。</p>
 */
final class KaraokeTextCompositor implements SharedConstants {

    /** 在离屏纹理的局部坐标系（左上角 0,0，单位与逻辑像素一致）里画字形与光晕。 */
    interface InkPainter {
        void paint();
    }

    /** 离屏路径一旦在当前显卡/Mod 组合下抛错就永久禁用，避免每帧反复失败。 */
    private static boolean unavailable;

    private Framebuffer baseFb;
    private Framebuffer maskFb;

    static boolean isUnavailable() {
        return unavailable;
    }

    /**
     * 把一个字词合成到屏幕上。
     *
     * @param x             合成四边形左上角的屏幕（HUD 逻辑）坐标 X
     * @param y             合成四边形左上角的屏幕（HUD 逻辑）坐标 Y
     * @param logicalWidth  离屏纹理的逻辑宽度，同时也是合成四边形的宽度（保证 1:1，无额外重采样）
     * @param logicalHeight 离屏纹理的逻辑高度，同时也是合成四边形的高度
     * @param superSample   离屏纹理的超采样倍率；取 1~4，按「一个逻辑单位在屏幕上占几个像素」定
     * @param inkRgb        墨色的 RGB。清屏色必须与墨色同色，否则字形边缘的抗锯齿会混进清屏色，
     *                      给彩色歌词描上一圈白边
     * @param solidRight    实心已唱段的右边界（局部坐标）
     * @param frontRight    真实播放前沿（局部坐标）；{@code solidRight}~{@code frontRight} 之间是羽化渐变
     * @param painter       字形与光晕的绘制回调
     * @return {@code true} 表示这一帧已经合成完毕；{@code false} 表示离屏路径不可用，调用方需要走兜底绘制
     */
    boolean composite(double x, double y, int logicalWidth, int logicalHeight, int superSample,
                      int inkRgb, double solidRight, double frontRight, InkPainter painter) {
        if (unavailable || logicalWidth <= 0 || logicalHeight <= 0 || painter == null) {
            return false;
        }

        int sample = Math.max(1, Math.min(4, superSample));
        int fbWidth = logicalWidth * sample;
        int fbHeight = logicalHeight * sample;

        // scissor 是屏幕空间的，进了离屏纹理的坐标系之后完全失效（会把整张纹理裁空）。
        // 这里整段挂起，合成回到 Minecraft framebuffer 之后再恢复，外层视口裁剪不受影响。
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        boolean attributesPushed = true;
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().loadIdentity();
            api.getGLStateManager().ortho(0.0D, logicalWidth, logicalHeight, 0.0D, 1000.0D, 3000.0D);
            api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
            api.getGLStateManager().pushMatrix();
            api.getGLStateManager().loadIdentity();
            api.getGLStateManager().translate(0.0F, 0.0F, -2000.0F);

            try {
                double solid = Math.max(0.0, Math.min(logicalWidth, solidRight));
                double front = Math.max(solid, Math.min(logicalWidth, frontRight));

                maskFb = RenderSystem.createFrameBuffer(maskFb, fbWidth, fbHeight);
                maskFb.bindFramebuffer(true);
                maskFb.setFramebufferColor(1.0F, 1.0F, 1.0F, 0.0F);
                maskFb.framebufferClearNoBinding();
                StencilClipManager.disableStencilTest();
                if (solid > 0.0) {
                    Rect.draw(0, 0, solid, logicalHeight, -1);
                }
                if (front - solid > .001) {
                    RenderSystem.drawGradientRectLeftToRight(solid, 0, front, logicalHeight, -1, 0);
                }

                baseFb = RenderSystem.createFrameBuffer(baseFb, fbWidth, fbHeight);
                baseFb.bindFramebuffer(true);
                baseFb.setFramebufferColor(inkRgb & 0xFFFFFF, 0.0F);
                baseFb.framebufferClearNoBinding();
                StencilClipManager.disableStencilTest();
                painter.paint();
            } finally {
                Framebuffer.getMcFramebuffer().bindFramebuffer(true);
                // 离屏渲染期间 stencil 测试被关掉了，合成之前必须把外层的 stencil 裁剪装回来。
                StencilClipManager.restoreActiveClip();
                api.getGLStateManager().popMatrix();
                api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
                api.getGLStateManager().popMatrix();
                api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
            }

            GL11.glPopAttrib();
            attributesPushed = false;

            Shaders.STENCIL.draw(baseFb.framebufferTexture, maskFb.framebufferTexture,
                    x, y, logicalWidth, logicalHeight);
            return true;
        } catch (Throwable throwable) {
            unavailable = true;
            return false;
        } finally {
            if (attributesPushed) {
                GL11.glPopAttrib();
            }
        }
    }
}
