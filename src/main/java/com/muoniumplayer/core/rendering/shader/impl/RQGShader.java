package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform4f;

import java.awt.*;

public class RQGShader implements SharedConstants {

    private final ShaderProgram program = new ShaderProgram("rqg.frag", "vertex.vsh");

    private final Uniform2f rectSize = new Uniform2f(program, "rectSize");
    private final Uniform1f radius = new Uniform1f(program, "radius");
    private final Uniform4f color1 = new Uniform4f(program, "color1");
    private final Uniform4f color2 = new Uniform4f(program, "color2");
    private final Uniform4f color3 = new Uniform4f(program, "color3");
    private final Uniform4f color4 = new Uniform4f(program, "color4");
    private final Uniform1f alp = new Uniform1f(program, "alp");



    /**
     * Draws a rounded rectangle at the given coordinates with the given lengths
     *
     * @param x      The top left x coordinate of the rectangle
     * @param y      The top y coordinate of the rectangle
     * @param width  The width which is used to determine the second x rectangle
     * @param height The height which is used to determine the second y rectangle
     * @param radius The radius for the corners of the rectangles (>0)
     */
    public void draw(float x, float y, float width, float height, final float radius, final float alpha, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {

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

        final int programId = this.program.getProgramId();
        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.program.start()) return;

        this.rectSize.setValue(width, height);
        this.radius.setValue(radius);
        this.color1.setValue(bottomLeft.getRed() * RenderSystem.DIVIDE_BY_255, bottomLeft.getGreen() * RenderSystem.DIVIDE_BY_255, bottomLeft.getBlue() * RenderSystem.DIVIDE_BY_255, bottomLeft.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.color2.setValue(topLeft.getRed() * RenderSystem.DIVIDE_BY_255, topLeft.getGreen() * RenderSystem.DIVIDE_BY_255, topLeft.getBlue() * RenderSystem.DIVIDE_BY_255, topLeft.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.color3.setValue(bottomRight.getRed() * RenderSystem.DIVIDE_BY_255, bottomRight.getGreen() * RenderSystem.DIVIDE_BY_255, bottomRight.getBlue() * RenderSystem.DIVIDE_BY_255, bottomRight.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.color4.setValue(topRight.getRed() * RenderSystem.DIVIDE_BY_255, topRight.getGreen() * RenderSystem.DIVIDE_BY_255, topRight.getBlue() * RenderSystem.DIVIDE_BY_255, topRight.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.alp.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().disableAlpha();
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        ShaderProgram.stop();
    }

    /**
     * Draws a rounded rectangle at the given coordinates with the given lengths
     *
     * @param x      The top left x coordinate of the rectangle
     * @param y      The top y coordinate of the rectangle
     * @param width  The width which is used to determine the second x rectangle
     * @param height The height which is used to determine the second y rectangle
     * @param radius The radius for the corners of the rectangles (>0)
     */
    public void draw(final double x, final double y, final double width, final double height, final double radius, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {
//        api.getGLStateManager().pushAttrib();
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, 1.0f, bottomLeft, topLeft, bottomRight, topRight);
//        api.getGLStateManager().popAttrib();
    }

    public void draw(final double x, final double y, final double width, final double height, final double radius, final float alpha, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {
//        api.getGLStateManager().pushAttrib();
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, alpha, bottomLeft, topLeft, bottomRight, topRight);
//        api.getGLStateManager().popAttrib();
    }
}
