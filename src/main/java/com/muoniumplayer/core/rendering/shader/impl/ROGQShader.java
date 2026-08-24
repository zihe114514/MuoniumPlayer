package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform4f;

import java.awt.*;

// Rounded Outline Gradient Quad
public class ROGQShader implements SharedConstants {

    private final ShaderProgram program = new ShaderProgram("rogq.frag", "vertex.vsh");

    private final Uniform2f u_size = new Uniform2f(program, "u_size");
    private final Uniform1f u_radius = new Uniform1f(program, "u_radius");
    private final Uniform1f u_border_size = new Uniform1f(program, "u_border_size");
    private final Uniform4f u_color_1 = new Uniform4f(program, "u_color_1");
    private final Uniform4f u_color_2 = new Uniform4f(program, "u_color_2");
    private final Uniform4f u_color_3 = new Uniform4f(program, "u_color_3");
    private final Uniform4f u_color_4 = new Uniform4f(program, "u_color_4");

    public void draw(float x, float y, float width, float height, float radius, float borderSize, Color color1, Color color2, Color color3, Color color4) {

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

        int programId = this.program.getProgramId();
        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.program.start()) return;
        
        this.u_size.setValue(width, height);
        this.u_radius.setValue(radius - 2f);
        this.u_border_size.setValue(borderSize);
        this.u_color_1.setValue(color1.getRed() * RenderSystem.DIVIDE_BY_255, color1.getGreen() * RenderSystem.DIVIDE_BY_255, color1.getBlue() * RenderSystem.DIVIDE_BY_255, color1.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.u_color_2.setValue(color2.getRed() * RenderSystem.DIVIDE_BY_255, color2.getGreen() * RenderSystem.DIVIDE_BY_255, color2.getBlue() * RenderSystem.DIVIDE_BY_255, color2.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.u_color_3.setValue(color3.getRed() * RenderSystem.DIVIDE_BY_255, color3.getGreen() * RenderSystem.DIVIDE_BY_255, color3.getBlue() * RenderSystem.DIVIDE_BY_255, color3.getAlpha() * RenderSystem.DIVIDE_BY_255);
        this.u_color_4.setValue(color4.getRed() * RenderSystem.DIVIDE_BY_255, color4.getGreen() * RenderSystem.DIVIDE_BY_255, color4.getBlue() * RenderSystem.DIVIDE_BY_255, color4.getAlpha() * RenderSystem.DIVIDE_BY_255);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        api.getGLStateManager().disableBlend();
        ShaderProgram.stop();
    }

    public void draw(double x, double y, double width, double height, double radius, double borderSize, Color color1, Color color2, Color color3, Color color4) {
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, (float) borderSize, color1, color2, color3, color4);
    }
}
