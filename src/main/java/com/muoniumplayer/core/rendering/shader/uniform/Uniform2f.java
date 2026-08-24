package com.muoniumplayer.core.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;

/**
 * @author IzumiiKonata
 * @since 2024/10/29 21:38
 */
public class Uniform2f {

    private final int location;
    private float value1, value2;

    public Uniform2f(ShaderProgram shader, String uniformName) {
        this.location = shader.isUsable() ? GL20.glGetUniformLocation(shader.getProgramId(), uniformName) : -1;
    }

    public void setValue(float value1, float value2) {
        if (this.location < 0) {
            return;
        }

        if (this.value1 != value1 || this.value2 != value2) {
            GL20.glUniform2f(location, value1, value2);
            this.value1 = value1;
            this.value2 = value2;
        }

    }

}
