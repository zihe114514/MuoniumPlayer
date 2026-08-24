package com.muoniumplayer.core.rendering;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;

import java.nio.ByteBuffer;

public class StencilClipManager implements SharedConstants {

    private static final BooleanState stencilState = new BooleanState(GL11.GL_STENCIL_TEST);
    // LWJGL 2 requires glGetBoolean buffers to have room for its maximum 16-value result.
    // Reuse one native buffer because clipping runs on the render thread and can execute many times per frame.
    private static final ByteBuffer booleanStateBuffer = BufferUtils.createByteBuffer(16);

    public static class StencilState {
        final int stencilValue;
        final boolean redMask;
        final boolean greenMask;
        final boolean blueMask;
        final boolean alphaMask;
        final boolean depthMask;
        final boolean depthTest;
        final int stencilWriteMask;
        final Runnable clipShape;

        StencilState(int stencilValue, boolean redMask, boolean greenMask, boolean blueMask,
                     boolean alphaMask, boolean depthMask, boolean depthTest,
                     int stencilWriteMask, Runnable clipShape) {
            this.stencilValue = stencilValue;
            this.redMask = redMask;
            this.greenMask = greenMask;
            this.blueMask = blueMask;
            this.alphaMask = alphaMask;
            this.depthMask = depthMask;
            this.depthTest = depthTest;
            this.stencilWriteMask = stencilWriteMask;
            this.clipShape = clipShape;
        }
    }

    public static boolean stencilClipping() {
        return !Framebuffer.currentlyBinding.stencilStack.isEmpty();
    }

    public static void disableStencilTest() {
        stencilState.setDisabled();
    }

    public static void enableStencilTest() {
        stencilState.setEnabled();
    }

    public static boolean isStencilEnabled() {
        return stencilState.currentState;
    }

    /**
     * The game and the auxiliary framebuffers may restore raw OpenGL state without going
     * through {@link BooleanState}. Force the real GL flag and its cache to agree before
     * every stencil write/read operation; otherwise a nested clip can silently render as
     * if no stencil test existed and leave later widgets outside their viewport.
     */
    private static void forceStencilTest(boolean enabled) {
        stencilState.currentState = enabled;
        if (enabled) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
        } else {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }

    public static void initialize() {
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        forceStencilTest(true);
    }

    public static void beginClip() {
        beginClipInternal(null);
    }

    private static void beginClipInternal(Runnable clipShape) {
        booleanStateBuffer.clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, booleanStateBuffer);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);

        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            initialize();
        }

        Framebuffer.currentlyBinding.stencilStack.push(new StencilState(
                Framebuffer.currentlyBinding.currentStencilValue,
                booleanStateBuffer.get(0) != 0,
                booleanStateBuffer.get(1) != 0,
                booleanStateBuffer.get(2) != 0,
                booleanStateBuffer.get(3) != 0,
                depthMask,
                depthTest,
                stencilWriteMask,
                clipShape));

        forceStencilTest(true);
        api.getGLStateManager().colorMask(false, false, false, false);
        api.getGLStateManager().depthMask(false);
        api.getGLStateManager().disableDepth();
        GL11.glStencilMask(0xFF);

        if (Framebuffer.currentlyBinding.currentStencilValue == 0) {
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        } else {
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        }

        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INCR);
    }

    public static void updateClip() {
        if (Framebuffer.currentlyBinding.stencilStack.isEmpty()) {
            System.err.println("Cannot update stencil clip without beginning one");
            return;
        }

        Framebuffer.currentlyBinding.currentStencilValue++;
        restoreRenderState(Framebuffer.currentlyBinding.stencilStack.peek());

        forceStencilTest(true);
        GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    public static void beginClip(Runnable drawClipShape) {
        if (drawClipShape == null) {
            throw new IllegalArgumentException("Clip shape cannot be null");
        }

        beginClipInternal(drawClipShape);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        boolean shapeDrawn = false;
        try {
            drawClipShape.run();
            shapeDrawn = true;
        } finally {
            GL11.glPopAttrib();
            if (!shapeDrawn) {
                // 裁剪形状没画完就抛异常了（典型情况：绘制回调里的着色器/资源出问题）。
                // 这时 endClip 永远不会被调用：stencilStack 每帧多一层、stencil 测试一直开着，
                // 之后所有绘制都被误裁。所以就地回滚这一层，把异常照原样抛给上层。
                rollbackClip();
            }
        }
        updateClip();
    }

    /** 撤销刚刚 {@link #beginClipInternal} 建立的那一层裁剪。只用于异常路径。 */
    private static void rollbackClip() {
        if (Framebuffer.currentlyBinding == null || Framebuffer.currentlyBinding.stencilStack.isEmpty()) {
            return;
        }
        StencilState state = Framebuffer.currentlyBinding.stencilStack.pop();
        Framebuffer.currentlyBinding.currentStencilValue = state.stencilValue;
        restoreRenderState(state);
        if (Framebuffer.currentlyBinding.currentStencilValue > 0) {
            forceStencilTest(true);
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        } else {
            // 回到最外层：模板缓冲里可能残留半个形状写进去的计数，
            // 下一次从零开始的 beginClip 会走 initialize() 把它清掉。
            disable();
        }
    }

    public static void endClip() {
        if (Framebuffer.currentlyBinding.stencilStack.isEmpty()) {
            System.err.println("Stencil stack underflow");
            return;
        }

        StencilState state = Framebuffer.currentlyBinding.stencilStack.pop();
        int currentStencilValue = Framebuffer.currentlyBinding.currentStencilValue;

        // 嵌套裁剪使用 GL_INCR 写入下一层。退出时必须把本层像素减回父层，
        // 否则父层随后绘制的文字会在这些像素上被 GL_EQUAL 拒绝。
        if (state.clipShape != null && currentStencilValue > state.stencilValue) {
            // The clip shape renderer changes texture/blend/alpha state. Isolate those
            // changes while keeping the stencil-buffer decrement itself. Also force the
            // stencil test on because framebuffer switches can desynchronise BooleanState.
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GL11.glEnable(GL11.GL_STENCIL_TEST);
                GL11.glColorMask(false, false, false, false);
                GL11.glDepthMask(false);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glStencilMask(0xFF);
                GL11.glStencilFunc(GL11.GL_EQUAL, currentStencilValue, 0xFF);
                GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_DECR);
                state.clipShape.run();
            } finally {
                GL11.glPopAttrib();
            }
        }

        Framebuffer.currentlyBinding.currentStencilValue = state.stencilValue;
        restoreRenderState(state);

        if (Framebuffer.currentlyBinding.currentStencilValue > 0) {
            forceStencilTest(true);
            GL11.glStencilFunc(GL11.GL_EQUAL, Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        } else {
            disable();
        }
    }

    private static void restoreRenderState(StencilState state) {
        api.getGLStateManager().colorMask(state.redMask, state.greenMask, state.blueMask, state.alphaMask);
        api.getGLStateManager().depthMask(state.depthMask);
        if (state.depthTest) {
            api.getGLStateManager().enableDepth();
        } else {
            api.getGLStateManager().disableDepth();
        }
        GL11.glStencilMask(state.stencilWriteMask);
    }

    /**
     * Re-applies the currently active clip after a shader or framebuffer pass.
     *
     * Auxiliary framebuffer passes are allowed to disable stencil testing while they
     * render their own textures.  When they return to the Minecraft framebuffer, the
     * active parent clip must be restored before the next quad is drawn; otherwise a
     * full-size texture/quad can paint the rounded container's transparent corners.
     */
    public static void restoreActiveClip() {
        if (Framebuffer.currentlyBinding == null
                || Framebuffer.currentlyBinding.stencilStack.isEmpty()
                || Framebuffer.currentlyBinding.currentStencilValue <= 0) {
            return;
        }

        StencilState state = Framebuffer.currentlyBinding.stencilStack.peek();
        restoreRenderState(state);
        forceStencilTest(true);
        GL11.glStencilFunc(GL11.GL_EQUAL,
                Framebuffer.currentlyBinding.currentStencilValue, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }
    public static void disable() {
        forceStencilTest(false);
        Framebuffer.currentlyBinding.currentStencilValue = 0;
    }

    public static void clear() {
        Framebuffer.currentlyBinding.currentStencilValue = 0;
        Framebuffer.currentlyBinding.stencilStack.clear();
    }
}
