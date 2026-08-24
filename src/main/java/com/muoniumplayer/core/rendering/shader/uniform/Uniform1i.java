package com.muoniumplayer.core.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;

/**
 * @author IzumiiKonata
 * @since 2024/10/29 21:38
 */
public class Uniform1i {

    private final int location;
    private int value;

    public Uniform1i(ShaderProgram shader, String uniformName) {
        this.location = shader.isUsable() ? GL20.glGetUniformLocation(shader.getProgramId(), uniformName) : -1;
    }

    public void setValue(int value) {
        if (this.location < 0) {
            return;
        }

        if (this.value != value) {
            GL20.glUniform1i(location, value);
            this.value = value;
        }

    }

}
