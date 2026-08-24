package repackage.be.tarsos.dsp;

/**
 * 绝对电平（dBSPL）静音判定。
 *
 * <p>移植自 TarsosDSP 的 {@code be.tarsos.dsp.SilenceDetector}（Joren Six / IPEM，Ghent University）。
 * 算法本身就三步：块内 RMS、{@code 20·log10} 转 dB、和一个<b>绝对</b>阈值比较。</p>
 *
 * <h3>为什么值得单独有这么一层</h3>
 * <p>本项目原来判断「曲子从哪里开始有声音」用的都是<b>相对</b>门限（窗口峰值的百分之多少）。
 * 相对门限有个绕不过去的毛病：它没有「安静」这个概念，只有「比别处安静」。
 * 一段纯数字静音和一段极轻的弦乐铺底在相对门限下长得一样，而一首整体压得很响的曲子里
 * 40% 峰值可能已经是副歌的电平了 —— 于是「跳过前奏」在响的曲子上一秒都不跳。
 * 绝对 dB 门限没有这个歧义：{@code -70 dB} 以下是真的没有信息，无论这首曲子整体多响。</p>
 *
 * <h3>与 TarsosDSP 的不同</h3>
 * <ul>
 *   <li>去掉 {@code AudioProcessor} 那层：上游是 {@code AudioDispatcher} 按块推，本项目是
 *       自己拿着整段 {@code float[]} 逐块扫，用不上 {@code process(AudioEvent)} 与
 *       {@code breakProcessingQueueOnSilence}。</li>
 *   <li>补了带 {@code offset/length} 的重载：本项目的调用方要在一整段 mono 上滑窗，
 *       原版每次都得先拷一个子数组出来。</li>
 * </ul>
 */
public final class SilenceDetector {

    /**
     * TarsosDSP 的默认阈值，单位 dB。
     *
     * <p>原注释给的正常取值区间是 {@code [-70, -30]}。{@code -70} 是「几乎只有量化噪声」，
     * 对判断「文件开头那段空白到哪儿结束」正合适；要判断「这里是不是一段安静的段落」
     * 得往上挪到 -45 上下。</p>
     */
    public static final double DEFAULT_SILENCE_THRESHOLD = -70.0;

    private SilenceDetector() {
    }

    /** 整块的 RMS。 */
    public static double calculateRMS(float[] buffer) {
        return buffer == null ? 0.0 : calculateRMS(buffer, 0, buffer.length);
    }

    /**
     * {@code [offset, offset + length)} 这一段的 RMS。越界部分按 0 计入，
     * 这样最后一个不满整块的尾巴不会因为除以更小的样本数而被抬高。
     */
    public static double calculateRMS(float[] buffer, int offset, int length) {
        if (buffer == null || length <= 0) return 0.0;
        double sum = 0.0;
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            if (index < 0 || index >= buffer.length) continue;
            double value = buffer[index];
            sum += value * value;
        }
        return Math.sqrt(sum / length);
    }

    /** 线性幅度转 dB。0 会得到 {@code -Infinity}，比较时天然落在任何阈值以下。 */
    public static double linearToDecibel(double value) {
        return 20.0 * Math.log10(value);
    }

    /** 整块的 dBSPL。 */
    public static double soundPressureLevel(float[] buffer) {
        return linearToDecibel(calculateRMS(buffer));
    }

    /** 一段的 dBSPL。 */
    public static double soundPressureLevel(float[] buffer, int offset, int length) {
        return linearToDecibel(calculateRMS(buffer, offset, length));
    }

    /** 整块是否静音（默认阈值）。 */
    public static boolean isSilence(float[] buffer) {
        return isSilence(buffer, DEFAULT_SILENCE_THRESHOLD);
    }

    /** 整块是否静音。 */
    public static boolean isSilence(float[] buffer, double silenceThreshold) {
        return soundPressureLevel(buffer) < silenceThreshold;
    }

    /** 一段是否静音。 */
    public static boolean isSilence(float[] buffer, int offset, int length, double silenceThreshold) {
        return soundPressureLevel(buffer, offset, length) < silenceThreshold;
    }
}
