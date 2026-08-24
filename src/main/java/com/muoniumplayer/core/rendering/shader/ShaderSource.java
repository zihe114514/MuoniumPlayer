package com.muoniumplayer.core.rendering.shader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * GLSL 源码的读取与消毒。刻意不依赖 LWJGL / Minecraft，可以离线单独跑。
 *
 * <p><b>为什么需要这个类</b>：着色器文件里有中文注释，而下面两件事叠起来会把 shader 直接编译废：</p>
 *
 * <ol>
 *   <li>以前用的是 {@code new InputStreamReader(stream)} —— 平台默认字符集。开发环境（Gradle 起的
 *       JVM，{@code file.encoding=UTF-8}）解码正确，而玩家的中文 Windows 客户端默认 GBK，
 *       UTF-8 的字节被按 GBK 解码成一串乱码。</li>
 *   <li>LWJGL 2 的 {@code GL20.glShaderSource(int, CharSequence)} 不做任何编码转换，直接把每个
 *       {@code char} 截成低 8 位字节。于是乱码字符会变成任意字节，其中只要有一个低字节等于
 *       {@code 0x0A}，注释行就在那里被提前结束，后面半行乱码变成真正的代码送进编译器。</li>
 * </ol>
 *
 * <p>实测 {@code rqg.frag} 就踩到了这一点：GBK 解码后第 15 行的注释里出现 {@code 0x0A}，
 * 编译器报 {@code 0(16) : error C0000: syntax error, unexpected $undefined}，接着
 * {@code createGradient} 未定义。之后 {@code RQGShader} 构造抛异常，{@link Shaders} 的静态初始化
 * 连带失败，任何画圆角矩形的界面每帧抛 {@code NoClassDefFoundError}；异常从 {@code drawScreen}
 * 逃出去（OptiFine 会吞掉并只记日志），{@code glPushAttrib}/{@code glPushMatrix} 再也没人配对，
 * 十几帧后就是满屏的 {@code 1283 Stack overflow}。</p>
 *
 * <p>所以这里做两件事：<b>固定按 UTF-8 解码</b>，以及<b>把所有非 ASCII 字符替换成空格</b>。
 * 后者让「注释里写了什么字符」永远无法影响编译结果，同时保持行数与列位置不变，
 * 编译器报的行号仍然能直接对应源文件。</p>
 */
public final class ShaderSource {

    private ShaderSource() {
    }

    /** 按 UTF-8 读取并消毒。调用方负责关流。 */
    public static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(1024, stream.available()));
        byte[] chunk = new byte[4096];
        for (int read; (read = stream.read(chunk)) != -1; ) {
            buffer.write(chunk, 0, read);
        }
        return sanitize(new String(buffer.toByteArray(), Charset.forName("UTF-8")));
    }

    /**
     * 归一化换行、去掉 BOM，并把除 {@code \n}、{@code \t} 以外的非可打印 ASCII 字符换成空格。
     *
     * <p>换成空格而不是删掉：行号与列号都不变，编译器的报错位置还能对上源文件。</p>
     */
    public static String sanitize(String source) {
        if (source == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\uFEFF') {
                // BOM。留着会顶掉 #version 必须在首行的要求。
                continue;
            }
            if (c == '\r') {
                // \r\n 与单独的 \r 都归一成 \n。
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    continue;
                }
                out.append('\n');
                continue;
            }
            if (c == '\n' || c == '\t') {
                out.append(c);
                continue;
            }
            out.append(c >= ' ' && c <= '~' ? c : ' ');
        }
        return out.toString();
    }
}
