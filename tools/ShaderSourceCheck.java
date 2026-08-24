import com.muoniumplayer.core.rendering.shader.ShaderSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 离线校验 {@link ShaderSource}：不需要 GL 上下文，也不需要 Minecraft/LWJGL。
 *
 * <pre>
 * javac -encoding UTF-8 -d %TEMP%\shadercheck ^
 *     src\main\java\com\muoniumplayer\core\rendering\shader\ShaderSource.java tools\ShaderSourceCheck.java
 * java -cp %TEMP%\shadercheck ShaderSourceCheck src\main\resources\muonium\shaders
 * </pre>
 *
 * <p>核心断言：任何字符集、任何注释内容下，消毒后的源码经过 LWJGL 2 的
 * 「char 截成低 8 位」上传都必须仍然是原来的行数、且不含 {@code \n} 之外的控制字符与反斜杠。
 * 这就是 rqg.frag 在玩家机器上编译失败（GBK 乱码在注释里插出一个 0x0A）的那个坑。</p>
 */
public final class ShaderSourceCheck {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "src/main/resources/muonium/shaders");
        File[] files = dir.listFiles();
        if (files == null) {
            throw new IOException("Not a directory: " + dir.getAbsolutePath());
        }

        for (File file : files) {
            byte[] raw = readAll(new FileInputStream(file));
            String sanitized;
            InputStream in = new ByteArrayInputStream(raw);
            try {
                sanitized = ShaderSource.read(in);
            } finally {
                in.close();
            }

            String utf8Text = new String(raw, Charset.forName("UTF-8"));
            int expectedLines = utf8Text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).length;

            check(file.getName() + ": 消毒后仍是纯 ASCII", isPrintableAscii(sanitized));
            check(file.getName() + ": 行数不变（编译器报的行号能对上源文件）",
                    sanitized.split("\n", -1).length == expectedLines);
            check(file.getName() + ": LWJGL 低字节截断后不会多出换行",
                    truncateLikeLwjgl(sanitized).split("\n", -1).length == expectedLines);
            check(file.getName() + ": 首行是 #version 或注释（BOM 已去掉）",
                    sanitized.startsWith("#version") || sanitized.startsWith("//") || sanitized.trim().isEmpty());
            check(file.getName() + ": 代码本体未被改动",
                    stripComments(sanitized).equals(stripComments(asciiOnlyReference(utf8Text))));

            // 同一份字节按 GBK 解码（中文 Windows 客户端的默认字符集）也必须得到一样的结果。
            String viaGbk = ShaderSource.sanitize(decode(raw, "GBK"));
            check(file.getName() + ": GBK 误解码后代码本体依旧一致",
                    stripComments(viaGbk).replaceAll("[ \t]+", " ")
                            .equals(stripComments(sanitized).replaceAll("[ \t]+", " ")));
        }

        // 定向回归：注释里塞一个低字节等于 0x0A 的非 ASCII 字符（GBK 乱码就是这样出现的）。
        String landmine = "#version 120\n// \u940A comment\nvoid main() { gl_FragColor = vec4(1.0); }\n";
        check("landmine: 截断前有 4 行", landmine.split("\n", -1).length == 4);
        check("landmine: 未消毒时 LWJGL 截断会多出一行",
                truncateLikeLwjgl(landmine).split("\n", -1).length == 5);
        check("landmine: 消毒后行数恢复正常",
                truncateLikeLwjgl(ShaderSource.sanitize(landmine)).split("\n", -1).length == 4);
        check("landmine: 消毒后 main() 还在同一行",
                ShaderSource.sanitize(landmine).split("\n")[2].startsWith("void main()"));

        check("sanitize(null) 返回 null", ShaderSource.sanitize(null) == null);
        check("\\r\\n 归一成 \\n", ShaderSource.sanitize("a\r\nb").equals("a\nb"));
        check("单独的 \\r 也归一成 \\n", ShaderSource.sanitize("a\rb").equals("a\nb"));
        check("BOM 被去掉", ShaderSource.sanitize("\uFEFF#version 120").equals("#version 120"));
        check("制表符保留", ShaderSource.sanitize("a\tb").equals("a\tb"));

        System.out.println();
        System.out.println(failures == 0
                ? "OK: " + checks + " 项断言全部通过"
                : "FAILED: " + failures + " / " + checks);
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 复刻 LWJGL 2 的 {@code glShaderSource(int, CharSequence)}：每个 char 只取低 8 位。 */
    private static String truncateLikeLwjgl(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            out.append((char) (source.charAt(i) & 0xFF));
        }
        return out.toString();
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') {
                continue;
            }
            if (c < ' ' || c > '~') {
                return false;
            }
        }
        return true;
    }

    /** 去掉行注释与块注释，只留代码本体，用来断言消毒没有动到代码。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 参照实现：正确解码后把非 ASCII 换成空格，与 {@link ShaderSource#sanitize} 应当一致。 */
    private static String asciiOnlyReference(String utf8Text) {
        String normalized = utf8Text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            out.append(c == '\n' || c == '\t' || (c >= ' ' && c <= '~') ? c : ' ');
        }
        return out.toString();
    }

    private static String decode(byte[] raw, String charset) {
        return new String(raw, Charset.forName(charset));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) != -1; ) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + what);
        }
    }
}
