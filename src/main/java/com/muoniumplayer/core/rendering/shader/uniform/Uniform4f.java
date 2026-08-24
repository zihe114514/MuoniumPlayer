package com.muoniumplayer.core.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;

public class Uniform4f {

    private final int location;
    private float value1, value2, value3, value4;

    public Uniform4f(ShaderProgram shader, String uniformName) {
        this.location = shader.isUsable() ? GL20.glGetUniformLocation(shader.getProgramId(), uniformName) : -1;
    }

    public void setValue(float value1, float value2, float value3, float value4) {
        if (this.location < 0) {
            return;
        }

        if (this.value1 != value1 || this.value2 != value2 || this.value3 != value3 || this.value4 != value4) {
            GL20.glUniform4f(location, value1, value2, value3, value4);
            this.value1 = value1;
            this.value2 = value2;
            this.value3 = value3;
            this.value4 = value4;
        }

    }
}