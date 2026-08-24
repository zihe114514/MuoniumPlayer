package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1i;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;

public class VFFadeoutShader implements SharedConstants {

    private final ShaderProgram program = new ShaderProgram("vf_fadeout.frag", "vertex.vsh");

    private final Uniform1i textureIn = new Uniform1i(program, "textureIn");
    private final Uniform2f u_size = new Uniform2f(program, "u_size");
    private final Uniform1f u_alpha = new Uniform1f(program, "u_alpha");
    private final Uniform1f u_control_perc = new Uniform1f(program, "u_control_perc");

    public void draw(float x, float y, float width, float height, final float controlPerc, final float alpha) {

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
        this.u_alpha.setValue(alpha);
        this.u_control_perc.setValue(controlPerc);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().enableAlpha();
        api.getGLStateManager().alphaFunc(GL11.GL_GREATER, 0.0F);
        ShaderProgram.drawQuad(x, y, width, height);
        ShaderProgram.stop();
    }

    public void draw(final double x, final double y, final double width, final double height, final double controlPerc, final float alpha) {
        draw((float) x, (float) y, (float) width, (float) height, (float) controlPerc, alpha);
    }


}
