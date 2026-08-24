package com.muoniumplayer.core.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;

import java.nio.FloatBuffer;

/**
 * @author IzumiiKonata
 * @since 2024/10/29 21:38
 */
public class Uniform4FB {

    private final int location;
    private FloatBuffer value;

    public Uniform4FB(ShaderProgram shader, String uniformName) {
        this.location = shader.isUsable() ? GL20.glGetUniformLocation(shader.getProgramId(), uniformName) : -1;
    }

    public void setValue(FloatBuffer value) {
        if (this.location < 0) {
            return;
        }

        if (this.value != value) {
            GL20.glUniform4(location, value);
            this.value = value;
        }

    }

}
