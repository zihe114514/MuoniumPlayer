package com.muoniumplayer.core.rendering.shader.uniform;

import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.rendering.shader.ShaderProgram;

/**
 * @author IzumiiKonata
 * @since 2024/10/29 21:38
 */
public class Uniform1f {

    private final int location;
    private float value;

    public Uniform1f(ShaderProgram shader, String uniformName) {
        // 着色器编译失败时 programId 是 -1，拿它去查 uniform 只会刷 GL 错误。
        this.location = shader.isUsable() ? GL20.glGetUniformLocation(shader.getProgramId(), uniformName) : -1;
    }

    public void setValue(float value) {
        if (this.location < 0) {
            return;
        }

        if (this.value != value) {
            GL20.glUniform1f(location, value);
            this.value = value;
        }

    }

}
