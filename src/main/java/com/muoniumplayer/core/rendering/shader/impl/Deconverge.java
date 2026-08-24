package com.muoniumplayer.core.rendering.shader.impl;

import org.lwjgl.opengl.Display;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform1i;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform2f;
import com.muoniumplayer.core.rendering.shader.uniform.Uniform3f;

/**
 * @author IzumiiKonata
 * Date: 2025/9/30 09:57
 */
public class Deconverge {

    private final ShaderProgram deconvergeProgram = new ShaderProgram("deconverge.frag", "vertex.vsh");

    Uniform1i textureIn = new Uniform1i(deconvergeProgram, "textureIn");
    Uniform2f texelSize = new Uniform2f(deconvergeProgram, "texelSize");

    Uniform3f convX = new Uniform3f(deconvergeProgram, "convX");
    Uniform3f convY = new Uniform3f(deconvergeProgram, "convY");
    Uniform3f radConvX = new Uniform3f(deconvergeProgram, "radConvX");
    Uniform3f radConvY = new Uniform3f(deconvergeProgram, "radConvY");

    public void render(float randomNess) {
        // 编译失败的着色器整段跳过，详见 ShaderProgram#start()。
        if (!this.deconvergeProgram.start()) return;
        textureIn.setValue(0);
        texelSize.setValue(1.0F / Display.getWidth(), 1.0F / Display.getHeight());

        this.setConvergeX(this.nextFloat(-randomNess, randomNess), this.nextFloat(-randomNess, randomNess),
                this.nextFloat(-randomNess, randomNess));

        this.setConvergeY(this.nextFloat(-randomNess, randomNess), this.nextFloat(-randomNess, randomNess),
                this.nextFloat(-randomNess, randomNess));

        ShaderProgram.drawQuadFlipped();
        ShaderProgram.stop();
    }

    private float nextFloat(final float startInclusive, final float endInclusive) {
        if(startInclusive == endInclusive || endInclusive - startInclusive <= 0F)
            return startInclusive;

        return (float) (startInclusive + ((endInclusive - startInclusive) * Math.random()));
    }

    public void setConvergeX(float red, float green, float blue) {
        convX.setValue(red, green, blue);
    }

    public void setConvergeY(float red, float green, float blue) {
        convY.setValue(red, green, blue);
    }

}
