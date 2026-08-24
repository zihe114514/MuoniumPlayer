package com.muoniumplayer.core.rendering.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.utils.Location;

import java.io.IOException;
import java.io.InputStream;

public class ShaderCompiler implements SharedConstants {

    public static int compile(final String fragment, final String vertex) {
        final String fragmentSource = getShaderResource(fragment);
        final String vertexSource = getShaderResource(vertex);

        // 以前这里判的是文件名而不是读出来的源码，资源缺失/读失败会带着 null 一路走到 glShaderSource。
        if (fragmentSource == null || vertexSource == null) {
            System.err.println("[MuoniumPlayer] Missing shader source: fragment=" + fragment + ", vertex=" + vertex);
            return -1;
        }

        final int fragmentId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        final int vertexId = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);

        GL20.glShaderSource(fragmentId, fragmentSource);
        GL20.glShaderSource(vertexId, vertexSource);
        GL20.glCompileShader(fragmentId);
        GL20.glCompileShader(vertexId);

        if (!checkIfCompiled(fragmentId, fragment)) return -1;
        if (!checkIfCompiled(vertexId, vertex)) return -1;

        final int programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, fragmentId);
        GL20.glAttachShader(programId, vertexId);
        GL20.glValidateProgram(programId);
        GL20.glLinkProgram(programId);
        GL20.glDeleteShader(fragmentId);
        GL20.glDeleteShader(vertexId);

        return programId;
    }

    private static boolean checkIfCompiled(final int shaderId, final String resource) {
        final boolean compiled = GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE;
        if (compiled) return true;

        final String shaderLog = GL20.glGetShaderInfoLog(shaderId, 8192);
        System.err.println("\n[MuoniumPlayer] Error while compiling shader: " + resource);
        System.err.println("-------------------------------");
        System.err.println(shaderLog);
        return false;
    }

    /**
     * 读取并消毒着色器源码。
     *
     * <p>必须固定 UTF-8 且过滤非 ASCII，原因见 {@link ShaderSource}：默认字符集在中文 Windows 上是
     * GBK，而 LWJGL 2 上传源码时只取每个 char 的低字节，乱码能在注释中间插出一个换行来。</p>
     */
    public static String getShaderResource(final String resource) {
        InputStream inputStream = null;
        try {
            inputStream = Location.of("/muonium/shaders/" + resource).getResourceStream();
            return ShaderSource.read(inputStream);
        } catch (final RuntimeException | IOException e) {
            System.err.println("[MuoniumPlayer] An error occurred while getting shader resource " + resource);
            e.printStackTrace();
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (final IOException ignored) {
                }
            }
        }
    }

}