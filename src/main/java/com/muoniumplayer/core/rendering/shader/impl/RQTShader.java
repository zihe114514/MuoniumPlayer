package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1i;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;

public class RQTShader implements SharedConstants {

    private final ShaderProgram program = new ShaderProgram("rqt.frag", "vertex.vsh");

    private final Uniform1i textureIn = new Uniform1i(program, "textureIn");
    private final Uniform2f u_size = new Uniform2f(program, "u_size");
    private final Uniform2f u_offset = new Uniform2f(program, "u_offset");
    private final Uniform2f u_scale = new Uniform2f(program, "u_scale");
    private final Uniform1f u_radius = new Uniform1f(program, "u_radius");
    private final Uniform1f u_alpha = new Uniform1f(program, "u_alpha");

    /**
     * Draws a rounded rectangle at the given coordinates with the given lengths
     *
     * @param x      The top left x coordinate of the rectangle
     * @param y      The top y coordinate of the rectangle
     * @param width  The width which is used to determine the second x rectangle
     * @param height The height which is used to determine the second y rectangle
     * @param radius The radius for the corners of the rectangles (>0)
     * @param alpha  The color used to draw the rectangle
     */
    public void draw(float x, float y, float width, float height, final float radius, final float alpha) {

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

        this.textureIn.setValue(0);
        this.u_size.setValue(width, height);
        this.u_offset.setValue(0, 0);
        this.u_scale.setValue(1, 1);
        this.u_radius.setValue(radius);
        this.u_alpha.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        ShaderProgram.drawQuad(x, y, width, height);
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
     * @param alpha  The color used to draw the rectangle
     */
    public void draw(final double x, final double y, final double width, final double height, final double radius, final float alpha) {
        draw((float) x, (float) y, (float) width, (float) height, (float) radius, alpha);
    }

    public void draw(float x, float y, float width, float height, final float texX, final float texY, final float u, final float v, final float radius, final float alpha) {

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

        this.textureIn.setValue(0);
        this.u_size.setValue(width, height);
        this.u_offset.setValue(texX, texY);
        this.u_scale.setValue(u - texX, v - texY);
        this.u_radius.setValue(radius);
        this.u_alpha.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuad(x, y, width, height);
        ShaderProgram.stop();
    }

    public void drawUpsideDown(float x, float y, float width, float height, final float radius, final float alpha) {

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

        this.textureIn.setValue(0);
        this.u_size.setValue(width, height);
        this.u_offset.setValue(0, 0);
        this.u_scale.setValue(1, 1);
        this.u_radius.setValue(radius);
        this.u_alpha.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        ShaderProgram.stop();
    }

    public void drawUpsideDown(float x, float y, float width, float height, final float texX, final float texY, final float u, final float v, final float radius, final float alpha) {

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

        this.textureIn.setValue(0);
        this.u_size.setValue(width, height);
        this.u_offset.setValue(texX, texY);
        this.u_scale.setValue(u - texX, v - texY);
        this.u_radius.setValue(radius);
        this.u_alpha.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        ShaderProgram.stop();
    }

    public void draw(final double x, final double y, final double width, final double height, final double texX, final double texY, final double u, final double v, final double radius, final float alpha) {
        draw((float) x, (float) y, (float) width, (float) height, (float) texX, (float) texY, (float) u, (float) v, (float) radius, alpha);
    }

    public void drawSpecial(final double x, final double y, final double width, final double height, final double texX, final double texY, final double scaleX, final double scaleY, final double radius, final float alpha) {
        drawSpecial((float) x, (float) y, (float) width, (float) height, (float) texX, (float) texY, (float) scaleX, (float) scaleY, (float) radius, alpha);
    }

    public void drawSpecial(float x, float y, float width, float height, final float texX, final float texY, final float scaleX, final float scaleY, final float radius, final float alpha) {

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

        this.textureIn.setValue(0);
        this.u_size.setValue(width, height);
        this.u_offset.setValue(texX, texY);
        this.u_scale.setValue(scaleX, scaleY);
        this.u_radius.setValue(radius);
        this.u_alpha.setValue(alpha);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().disableAlpha();
//        glStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuad(x, y, width, height);
        ShaderProgram.stop();
    }
}
