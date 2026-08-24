package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform4f;

import java.awt.*;

public class RQShader implements SharedConstants {

    private final ShaderProgram program = new ShaderProgram("rq.frag", "vertex.vsh");

    private final Uniform2f u_size = new Uniform2f(program, "u_size");
    private final Uniform1f u_radius = new Uniform1f(program, "u_radius");
    private final Uniform4f u_color = new Uniform4f(program, "u_color");

    /**
     * Draws a rounded rectangle at the given coordinates with the given lengths
     *
     * @param x      The top left x coordinate of the rectangle
     * @param y      The top y coordinate of the rectangle
     * @param width  The width which is used to determine the second x rectangle
     * @param height The height which is used to determine the second y rectangle
     * @param radius The radius for the corners of the rectangles (>0)
     */
    public void draw(float x, float y, float width, float height, final float radius, float r, float g, float b, float a) {

        if (x > x + width) {
            float i = x;
            x = x + width;
            width = i - x;
        }

        if (y > y + height) {
            float j = y;
            y = y + height;
            height = j - y;
        }

        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.program.start()) return;

        this.u_size.setValue(width, height);
        this.u_radius.setValue(radius);
        this.u_color.setValue(r, g, b, a);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().disableAlpha();
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        ShaderProgram.stop();
    }

    public void draw(final double x, final double y, final double width, final double height, final double radius, float r, float g, float b, float a) {
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, r, g, b, a);
    }

    public void draw(final double x, final double y, final double width, final double height, final double radius, Color color) {
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, color.getRed() * RenderSystem.DIVIDE_BY_255, color.getGreen() * RenderSystem.DIVIDE_BY_255, color.getBlue() * RenderSystem.DIVIDE_BY_255, color.getAlpha() * RenderSystem.DIVIDE_BY_255);
    }

    public void draw(final double x, final double y, final double width, final double height, final double radius, int r, int g, int b, int a) {
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, r * RenderSystem.DIVIDE_BY_255, g * RenderSystem.DIVIDE_BY_255, b * RenderSystem.DIVIDE_BY_255, a * RenderSystem.DIVIDE_BY_255);
    }
}
