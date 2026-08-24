package com.muoniumplayer.core.rendering.shader.impl;


import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1i;

/**
 * @author IzumiiKonata
 * @since 2024/12/3 21:17
 */
public class BlendShader {

    private final ShaderProgram blendProgram = new ShaderProgram("blend.frag", "vertex.vsh");
    private final Uniform1i textureIn = new Uniform1i(blendProgram, "textureIn");

    public void render() {
//        api.getGLStateManager().disableAlpha();

        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.blendProgram.start()) return;
        textureIn.setValue(0);

        ShaderProgram.drawQuadFlipped();
//        api.getGLStateManager().disableBlend();

        ShaderProgram.stop();
    }


}
