package com.muoniumplayer.core.rendering.shader;

import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

@Getter
public class ShaderProgram implements SharedConstants {

    private final int programId;
    /** 编译失败的程序对象。任何调用方都必须先问 {@link #isUsable()}，否则会拿 0 去调 GL。 */
    private final boolean usable;

    public ShaderProgram(final String fragmentPath, final String vertexPath) {
        this.programId = ShaderCompiler.compile(fragmentPath, vertexPath);
        this.usable = this.programId > 0;
        if (!this.usable) {
            // 这里以前是 throw。抛出来的后果远比「少一个着色器」严重：这些对象都是 Shaders 的静态字段，
            // 构造异常会让 Shaders.<clinit> 失败，之后每一次画圆角矩形都抛 NoClassDefFoundError；
            // 异常从 drawScreen 逃出去（OptiFine 只记日志不崩），glPushAttrib/glPushMatrix 再没人配对，
            // 十几帧后就是满屏 GL 1283 Stack overflow。改成记一次日志并把自己标成不可用。
            System.err.println("[MuoniumPlayer] Shader disabled (failed to compile)"
                    + "\n    Fragment: " + fragmentPath
                    + "\n    Vertex: " + vertexPath);
        }
    }

    /** 编译成功、可以 {@link #start()} 的才返回 {@code true}。 */
    public boolean isUsable() {
        return usable;
    }

    public static void drawQuadFlipped(final double x, final double y, final double width, final double height) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex2d(x, y);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex2d(x, y + height);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex2d(x + width, y);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex2d(x + width, y + height);
        GL11.glEnd();
    }

    public static void drawQuad(final double x, final double y, final double width, final double height) {
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2d(0, 0);
        GL11.glVertex2d(x, y);
        GL11.glTexCoord2d(0, 1);
        GL11.glVertex2d(x, y + height);
        GL11.glTexCoord2d(1, 0);
        GL11.glVertex2d(x + width, y);
        GL11.glTexCoord2d(1, 1);
        GL11.glVertex2d(x + width, y + height);
        GL11.glEnd();
    }

    static double lastWidth = -1, lastHeight = -1;
    static double lastWidthSr = -1, lastHeightSr = -1;
    static int callList = -1;
    static int callListSr = -1;

    public static void drawQuadFlipped() {

        if (lastWidth != RenderSystem.getWidth() || lastHeight != RenderSystem.getHeight()) {
            lastWidth = RenderSystem.getWidth();
            lastHeight = RenderSystem.getHeight();

            if (callList != -1)
                GL11.glDeleteLists(callList, 1);

            callList = GL11.glGenLists(1);

            GL11.glNewList(callList, GL11.GL_COMPILE);
            drawQuadFlipped(0.0, 0.0, RenderSystem.getWidth(), RenderSystem.getHeight());
            GL11.glEndList();

        }

        GL11.glCallList(callList);

//        drawQuadFlipped(0.0, 0.0, RenderSystem.getWidth(), RenderSystem.getHeight());
    }

    /**
     * 绑定程序对象。
     *
     * @return {@code false} 表示这个着色器不可用，调用方应当整段跳过（继续画会用错固定管线状态，
     *         设置 uniform 还会刷 GL_INVALID_OPERATION）
     */
    public boolean start() {
        if (!usable) {
            return false;
        }
        GL20.glUseProgram(programId);
        return true;
    }

    public static void stop() {
        GL20.glUseProgram(0);
    }

}