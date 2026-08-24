package repackage.processing.sound;

/**
 * WSOLA（waveform-similarity-based overlap-add）时间伸缩：<b>改变速度但不改变音高</b>。
 *
 * <p>移植自 TarsosDSP 的 {@code be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd}
 * （Joren Six / IPEM，Ghent University；其本身参考了 Olli Parviainen 的 SoundTouch {@code TDStretch.cpp}），
 * 算法出自 Verhelst &amp; Roelands 1993 的 WSOLA 论文。</p>
 *
 * <h3>为什么不能直接用 TarsosDSP 的类</h3>
 * <ul>
 *   <li>它是 {@code AudioProcessor}，必须挂在 {@code AudioDispatcher} 上，由后者按
 *       {@code stepSize/overlap} 推帧。本项目的播放链是 JSyn 的 {@code VariableRateStereoReader}
 *       <b>按绝对下标向 FloatSample 拉一帧</b>，方向完全相反，没法把 dispatcher 塞进去。</li>
 *   <li>它只处理单声道：{@code precalcCorrReferenceMono} 与 {@code calcCrossCorr} 直接把缓冲当一维数组扫，
 *       喂立体声进去会把左右声道当成相邻采样做互相关，选出来的接缝位置是错的。</li>
 * </ul>
 *
 * <p>所以这里保留它的<b>算法与参数</b>（三档参数表、抛物线加权的相关参考、偏向搜索窗中部的启发式罚项、
 * 线性交叉淡化的 overlap-add），把 I/O 改成「按绝对交织下标从随机读源拉窗口」，并把互相关扩展成多声道求和。</p>
 *
 * <h3>与 TarsosDSP 的两处刻意不同</h3>
 * <ol>
 *   <li><b>归一化用的是被比较窗口的能量，不是参考的。</b>TarsosDSP 的 {@code calcCrossCorr} 里
 *       {@code norm += mixingPos[i] * mixingPos[i]} 累的是参考自己的能量 —— 那个值在整个搜索里是常数，
 *       除以它不改变排序，等于没有归一化，于是「响的地方」总是赢过「对得准的地方」。
 *       SoundTouch 原版累的是 {@code compare} 的能量。这里按 SoundTouch 来。</li>
 *   <li><b>tempo 恰好为 1 时直接取偏移 0，不做搜索。</b>偏移 0 在数学上就是恒等变换
 *       （mid 正好等于新窗口的头 {@code ovl} 帧，自己和自己交叉淡化 = 原样），但那条偏向中部的罚项
 *       会给偏移 0 打 0.75 折，可能让一个对不准的中部偏移赢过完美的 0。不搜索才能保证
 *       「没在变速时输出与输入逐采样相同」。</li>
 * </ol>
 *
 * <p><b>线程约束</b>：{@link #read} 只能由音频线程调用，{@link #setTempo} 可以从别的线程调用
 * （只写一个 {@code volatile double}，下一个块生效）。</p>
 */
public final class WsolaTimeStretch {

    /** 输入源：按绝对交织采样下标随机读。越界返回 0。 */
    public interface Samples {
        double at(int interleavedIndex);

        /** 交织采样总数。 */
        int length();
    }

    /** tempo 的允许范围。和 {@code AutomixSettings} 的速率上下限同量级，留了余量。 */
    public static final double MIN_TEMPO = 0.5;
    public static final double MAX_TEMPO = 2.0;

    /** 小于这个偏差就认为「没在变速」，走恒等快路径。 */
    private static final double IDENTITY_EPSILON = 1e-4;

    private final Samples source;
    private final int channels;

    /** 单个处理序列的长度（帧）。TarsosDSP 的 {@code sequenceMs}。 */
    private final int seq;
    /** 搜索最佳接缝的窗口长度（帧）。TarsosDSP 的 {@code seekWindowMs}。 */
    private final int seek;
    /** 交叉淡化长度（帧）。TarsosDSP 的 {@code overlapMs}。 */
    private final int ovl;

    private volatile double tempo = 1.0;

    /** 上一序列的尾巴，用来和下一序列的头做交叉淡化（TarsosDSP 的 {@code pMidBuffer}）。 */
    private final float[] mid;
    /** {@code mid} 乘上抛物线权重后的相关参考（TarsosDSP 的 {@code pRefMidBuffer}）。 */
    private final float[] ref;
    /** 每个块从源里拉进来的输入窗口。 */
    private final float[] window;
    /** 一次产出的输出块，长度恒为 {@code (seq - ovl) * channels}。 */
    private final float[] block;

    private boolean midReady;
    /** 下一个窗口在源里的起始帧，带小数（tempo 通常不是整数帧）。 */
    private double cursorFrames;
    /** {@code block[0]} 对应的绝对输出交织下标；{@code -1} 表示还没锚定。 */
    private long blockBase = -1L;
    /** 已经跑过一次 {@link #anchor}。 */
    private boolean anchored;
    /** 越界重锚的次数，只用于诊断。 */
    private int reanchorCount;

    /**
     * @param source     输入源，必须支持任意下标随机读
     * @param channels   声道数（1 或 2）
     * @param sampleRate 采样率，用来把毫秒参数换成帧数
     */
    public WsolaTimeStretch(Samples source, int channels, int sampleRate) {
        if (source == null) throw new IllegalArgumentException("source");
        this.source = source;
        this.channels = Math.max(1, Math.min(2, channels));
        int rate = sampleRate > 0 ? sampleRate : 44100;
        // TarsosDSP 的 Parameters.musicDefaults：82 / 28 / 12 ms。
        // 不用 speechDefaults(40/15/12)：更短的序列在音乐上会把持续音切出可听的颤动，
        // 而 82ms 的序列正好比大多数曲子的十六分音符长，接缝落在乐句内部而不是每个音头上。
        this.seq = frames(rate, 82.0);
        this.seek = frames(rate, 28.0);
        this.ovl = frames(rate, 12.0);
        this.mid = new float[ovl * this.channels];
        this.ref = new float[ovl * this.channels];
        this.block = new float[(seq - ovl) * this.channels];
        // 窗口按 tempo 上限预分配一次，之后 setTempo 不再触发分配（音频线程上不能 new）。
        int maxSkip = (int) Math.ceil(MAX_TEMPO * (seq - ovl)) + 1;
        this.window = new float[(Math.max(maxSkip + ovl, seq) + seek) * this.channels];
    }

    /** 毫秒换帧，至少 1 帧。 */
    public static int frames(int sampleRate, double millis) {
        return Math.max(1, (int) Math.round(sampleRate * millis / 1000.0));
    }

    /**
     * 设置伸缩因子，SoundTouch 语义：{@code > 1} 更快更短（每产出一帧要吃掉更多输入），
     * {@code < 1} 更慢更长。音高<b>始终不变</b>。
     */
    public void setTempo(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        this.tempo = Math.max(MIN_TEMPO, Math.min(MAX_TEMPO, value));
    }

    public double getTempo() {
        return this.tempo;
    }

    public int getChannels() {
        return this.channels;
    }

    /** 一次产出多少帧。也就是输出时间轴每前进这么多帧，就要跑一次 {@link #produce}。 */
    public int getBlockFrames() {
        return seq - ovl;
    }

    public int getReanchorCount() {
        return this.reanchorCount;
    }

    /**
     * 把输出时间轴的 {@code outputIndex} 对齐到输入的同一位置，并清空历史。
     *
     * <p>接管播放（从直通切到伸缩）与随机定位之后都必须调一次，否则输出会从上一次停下的地方接着长。</p>
     */
    public void anchor(int outputIndex) {
        int safe = Math.max(0, outputIndex);
        this.cursorFrames = (double) (safe / channels);
        this.blockBase = -1L;
        this.midReady = false;
        this.anchored = true;
        java.util.Arrays.fill(mid, 0f);
    }

    /**
     * 读输出时间轴上的一个采样。
     *
     * <p>调用方必须<b>单调递增</b>地读（JSyn 的 {@code VariableRateStereoReader} 正是逐帧、逐声道
     * 顺序拉的）。乱序或跳跃会触发一次重锚：能继续出声，但接缝处会听到一次跳变，
     * 所以 {@link #getReanchorCount} 不为 0 就说明上游访问模式和假设不符。</p>
     */
    public double read(int outputIndex) {
        if (outputIndex < 0) return 0.0;
        if (!anchored) anchor(outputIndex);
        if (blockBase < 0L) {
            blockBase = outputIndex;
            produce();
        }
        long offset = outputIndex - blockBase;
        while (offset >= block.length) {
            blockBase += block.length;
            offset -= block.length;
            produce();
        }
        if (offset < 0L) {
            // 上游回跳了。没法从当前状态往回长，只能就地重锚。
            reanchorCount++;
            anchor(outputIndex);
            blockBase = outputIndex;
            produce();
            offset = 0L;
        }
        return block[(int) offset];
    }

    /** 产出一个块，并把输入游标向前推 {@code tempo * (seq - ovl)} 帧。 */
    private void produce() {
        double factor = tempo;
        boolean identity = Math.abs(factor - 1.0) < IDENTITY_EPSILON;

        double nominalSkip = factor * (seq - ovl);
        int skip = (int) Math.floor(nominalSkip + 0.5);
        if (skip < 1) skip = 1;
        int windowFrames = Math.max(skip + ovl, seq) + seek;
        if (windowFrames * channels > window.length) {
            windowFrames = window.length / channels;
        }

        int startFrame = (int) Math.floor(cursorFrames);
        fillWindow(startFrame, windowFrames);

        // 恒等快路径见类注释：偏移 0 就是原样，搜索反而可能被罚项带偏。
        int offset = (identity || !midReady) ? 0 : seekBestOverlap(windowFrames);

        if (midReady) {
            // 线性交叉淡化：mid 淡出、新窗口淡入，长度 ovl 帧。
            for (int f = 0; f < ovl; f++) {
                float rise = (float) f / (float) ovl;
                float fall = 1f - rise;
                int mi = f * channels;
                int wi = (offset + f) * channels;
                for (int c = 0; c < channels; c++) {
                    block[mi + c] = mid[mi + c] * fall + window[wi + c] * rise;
                }
            }
        } else {
            // 第一个块没有尾巴可混，头 ovl 帧原样送出。
            System.arraycopy(window, offset * channels, block, 0, ovl * channels);
            midReady = true;
        }

        // 序列中段原样拷贝。
        int flatFrames = seq - 2 * ovl;
        if (flatFrames > 0) {
            System.arraycopy(window, (offset + ovl) * channels,
                    block, ovl * channels, flatFrames * channels);
        }

        // 留下这一序列的尾巴当下一次的 mid。
        int tailFrame = offset + Math.max(ovl, seq - ovl);
        if ((tailFrame + ovl) * channels > window.length) {
            tailFrame = Math.max(0, window.length / channels - ovl);
        }
        System.arraycopy(window, tailFrame * channels, mid, 0, ovl * channels);

        cursorFrames += nominalSkip;
    }

    /** 把源里 {@code [startFrame, startFrame + count)} 帧读进 {@link #window}。 */
    private void fillWindow(int startFrame, int count) {
        int total = count * channels;
        int base = startFrame * channels;
        int length = source.length();
        for (int i = 0; i < total; i++) {
            int index = base + i;
            window[i] = (index >= 0 && index < length) ? (float) source.at(index) : 0f;
        }
        for (int i = total; i < window.length; i++) {
            window[i] = 0f;
        }
    }

    /**
     * 在 {@code [0, seek)} 里找让新窗口的头和 {@code mid} 最像的偏移。
     *
     * <p>「最像」= 归一化互相关最大。参考先乘一条抛物线权重（SoundTouch 的
     * {@code precalcCorrReference}）：两端权重趋零，于是接缝更看重 overlap 中央对不对得上，
     * 边缘的一两个采样对不齐不至于左右结果。</p>
     */
    private int seekBestOverlap(int windowFrames) {
        for (int f = 0; f < ovl; f++) {
            float weight = (float) f * (float) (ovl - f);
            int mi = f * channels;
            for (int c = 0; c < channels; c++) {
                ref[mi + c] = mid[mi + c] * weight;
            }
        }

        int limit = Math.min(seek, windowFrames - Math.max(ovl, seq - ovl) - ovl);
        if (limit < 1) return 0;

        int bestOffset = 0;
        double bestScore = -Double.MAX_VALUE;
        for (int offset = 0; offset < limit; offset++) {
            double corr = 0.0;
            double norm = 0.0;
            int base = offset * channels;
            for (int i = channels; i < ref.length; i++) {
                double compare = window[base + i];
                corr += ref[i] * compare;
                norm += compare * compare;
            }
            double score = norm < 1e-8 ? corr : corr / Math.sqrt(norm);
            // SoundTouch 的启发式罚项：轻微偏向搜索窗中部。纯粹取最大相关会让接缝位置在
            // 连续几个序列里一路漂到窗口边缘，听起来是一个恒定频率在游走。
            double normalized = (double) (2 * offset - limit) / limit;
            score = (score + 0.1) * (1.0 - 0.25 * normalized * normalized);
            if (score > bestScore) {
                bestScore = score;
                bestOffset = offset;
            }
        }
        return bestOffset;
    }
}
