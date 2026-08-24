package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1i;

/**
 * @author IzumiiKonata
 * Date: 2025/10/23 17:55
 */
public class StencilShader implements SharedConstants {

    private final ShaderProgram stencilProgram = new ShaderProgram("stencil.frag", "vertex.vsh");
    private final Uniform1i mixTexture = new Uniform1i(stencilProgram, "mixTexture");
    private final Uniform1i stencilTexture = new Uniform1i(stencilProgram, "stencilTexture");

    public void draw(int baseTexture, int stencilTexture, double x, double y) {

        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.stencilProgram.start()) return;

        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE0);
        api.getGLStateManager().bindTexture(stencilTexture);
        RenderSystem.linearFilter();
        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE16);
        api.getGLStateManager().bindTexture(baseTexture);
        RenderSystem.linearFilter();

        this.mixTexture.setValue(16);
        this.stencilTexture.setValue(0);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().disableAlpha();
        ShaderProgram.drawQuadFlipped();
        ShaderProgram.stop();

        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE16);
        api.getGLStateManager().bindTexture(0);
        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE0);
    }

    public void draw(int baseTexture, int stencilTexture, double x, double y, double width, double height) {

        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.stencilProgram.start()) return;

        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE0);
        api.getGLStateManager().bindTexture(stencilTexture);
        RenderSystem.linearFilter();
        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE16);
        api.getGLStateManager().bindTexture(baseTexture);
        RenderSystem.linearFilter();

        this.mixTexture.setValue(16);
        this.stencilTexture.setValue(0);

        api.getGLStateManager().enableBlend();
        api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        api.getGLStateManager().disableAlpha();
        ShaderProgram.drawQuadFlipped(x, y, width, height);
        ShaderProgram.stop();

        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE16);
        api.getGLStateManager().bindTexture(0);
        api.getGLStateManager().setActiveTexture(GL13.GL_TEXTURE0);
    }

}
