package com.muoniumplayer.core.ncm.music;

import repackage.processing.sound.Amplitude;
import repackage.processing.sound.SoundFile;

/**
 * 正在播放的那一路音频的实时「节拍强度」。
 *
 * <h3>为什么是包络而不是 BPM</h3>
 * <p>项目里已经有 {@link AutomixAnalyzer#analyzeBeats} 能测 BPM，但它读的是
 * {@link PcmWindowReader}，而后者只认 WAV：mp3 / flac 交给 JSyn 自己解码，缓存目录里根本没有
 * 对应的 WAV，于是离线测速对绝大多数歌都拿不到结果。所以这里改成在<b>正在响的那一路信号上挂一个
 * 峰值跟随器</b>（JSyn 的 {@code PeakFollower}，经 {@code processing.sound.Amplitude} 暴露），
 * 每帧读一次它的当前值，再做 onset 检测。这条路对任何能播的格式都成立。</p>
 *
 * <h3>onset 而不是响度</h3>
 * <p>直接把响度画到画面上会几乎不动——整首歌的平均音量本来就平。所以这里跟参考实现一样：用一条很慢的
 * 基线跟踪环境能量，只有「超过基线且比上一帧更响」时才触发一次脉冲，然后让脉冲指数衰减。这样数值会
 * 在鼓点上冲高、在鼓点之间落下，也就是真的踩在拍子上。</p>
 *
 * <h3>三条安全约束（改这个类前必读）</h3>
 * <ol>
 *   <li><b>绝不读 JSyn 的采样窗口。</b>{@link PcmWindowReader} 的类注释解释过：live sample 是一条
 *       共享滑动窗口，在音频线程底下挪它会直接把播放搞停。峰值跟随器只是接在电路输出端上的一个旁路
 *       单元，不碰采样位置。</li>
 *   <li><b>只在真的在响的时候取值。</b>deck 停下来之后 JSyn 会把输出端「摊平」成最后一个值，
 *       照读会得到一个卡住的直流。所以没在播就直接返回 0，和参考实现的
 *       {@code isPlaying() ? BEAT : 0} 一致。</li>
 *   <li><b>任何异常都必须永久降级。</b>这是音频图，出问题的代价是没声音。所以整条链路包在
 *       {@code catch (Throwable)} 里，一次失败就把 {@link #unavailable} 置起来再也不试，
 *       调用方拿到 {@code 0} 后自己退回「按字词进度算脉冲」。</li>
 * </ol>
 */
public final class PlaybackPulse {

    /** 一帧最多推进这么多秒。卡顿一下不该让脉冲瞬间归零。 */
    private static final double MAX_FRAME_SECONDS = .05;

    /** 两次推进之间的最小间隔：同一帧里多个调用方各读一次，不该把状态推进多次。 */
    private static final double MIN_ADVANCE_SECONDS = .002;

    /**
     * 峰值跟随器的半衰期。
     *
     * <p>比参考实现的默认 0.1 s 短：0.1 s 会把鼓点抹平成一条缓坡，60 fps 采样下几乎看不出起伏。
     * 但也不能太短——太短的话峰值在两帧之间就衰减完了，采样会整拍漏掉。0.045 s 是「一帧一定能采到
     * 上一次冲击的残余」与「拍与拍之间能落回来」的折中。</p>
     */
    private static final float ANALYZER_HALF_LIFE = .045f;

    /**
     * 基线时间常数。参考实现按「每个约 93 ms 的解码窗口逼近 4% / 10%」写的，这里换算成时间常数
     * （{@code tau = -dt / ln(1 - k)}），于是掉帧时行为不变。上行比下行慢：持续大声的段落不该把
     * 基线抬上去把灵敏度吃掉。
     */
    private static final double BASELINE_RISE_TAU = 2.28;
    private static final double BASELINE_FALL_TAU = .885;

    /** 脉冲衰减时间常数，同样由参考实现的「每窗口乘 0.62」换算而来。 */
    private static final double PULSE_DECAY_TAU = .194;

    /** 响度层的平滑时间常数。它只用来让背景整体呼吸，不需要跟到单个鼓点。 */
    private static final double LOUDNESS_TAU = .28;

    /** 要算作一次冲击，至少得比基线高这么多倍。 */
    private static final float ONSET_MARGIN = 1.10f;

    /**
     * 冲击强度的增益。
     *
     * <p>参考实现用的是绝对增益（{@code excess * 11}），因为它拿到的是原始 PCM 的 RMS。这里拿到的是
     * 峰值、而且还除过一次音量，绝对值不可比，所以改成<b>相对基线</b>的比值：能量翻一倍就打满。
     * 这样无论峰值比 RMS 高多少、音量归一化差多少，灵敏度都一样。</p>
     */
    private static final float ONSET_RELATIVE_GAIN = 1.6f;

    /** 基线的下限，避免安静段落里除出一个巨大的比值。 */
    private static final float MIN_REFERENCE = .02f;

    /** 音量归一化的下限：音量本来就接近 0 时不要把噪声放大成节拍。 */
    private static final float MIN_AMPLITUDE = .05f;

    /** 响度归一化系数：母带过的音乐峰值大多落在 0.6~0.95。 */
    private static final float LOUDNESS_SCALE = 1.25f;

    /**
     * 计一拍的不应期（秒）。
     *
     * <p>{@link #beat()} 那条连续量允许一次鼓点的余波再抬一次，画面上只是亮一点；但
     * {@link #beatCount()} 是离散计数，同一次鼓点被数成两拍会让「每 N 拍换色」直接快一倍。
     * 0.16 s 对应 375 BPM 的四分音符，比任何正常曲子的鼓点都密，所以不会漏拍，
     * 又足够长到能吃掉一次冲击的上升沿抖动。</p>
     */
    private static final double BEAT_REFRACTORY_SECONDS = .16;

    /**
     * 计一拍的最小强度。
     *
     * <p>比 {@link #beat()} 的门槛严：连续量画的是「有多用力」，弱冲击画出来只是微微一亮，
     * 无所谓；而计数是要拿来换色的，把呼吸声、混响尾巴都数成拍会让颜色乱跳。</p>
     */
    private static final float BEAT_COUNT_THRESHOLD = .18f;

    /** onset 检测器的全部状态。抽成静态类是为了让 {@link #step} 能在没有音频设备时离线断言。 */
    static final class State {
        float baseline;
        float previous;
        float pulse;
        float loudness;
        /** 从当前这一路音频开始以来数到的拍数，只增不减。 */
        int beats;
        /** 不应期剩余秒数；大于 0 时不再计新的拍。 */
        double refractory;

        void clear() {
            baseline = 0f;
            previous = 0f;
            pulse = 0f;
            loudness = 0f;
            beats = 0;
            refractory = 0.0;
        }
    }

    private static final State STATE = new State();

    private static Amplitude analyzer;
    private static SoundFile attached;
    private static boolean unavailable;
    private static long lastNanos;

    private PlaybackPulse() {
    }

    /** 实时包络这条路是否还可用。一旦挂过一次就永久为 {@code false}。 */
    public static boolean isAvailable() {
        return !unavailable;
    }

    /**
     * 当前的节拍脉冲，0..1。鼓点上冲高、鼓点之间落下；没在播放或这条路不可用时为 0。
     *
     * <p>同一帧里被多次调用是安全的：推进按墙上时钟做，间隔小于
     * {@link #MIN_ADVANCE_SECONDS} 时直接返回上次的值。</p>
     */
    public static float beat() {
        advance();
        return STATE.pulse;
    }

    /** 当前的平滑响度，0..1。变化比 {@link #beat()} 慢得多，适合驱动整体亮度。 */
    public static float loudness() {
        advance();
        return STATE.loudness;
    }

    /**
     * 从当前这一路音频开始以来数到的拍数，单调不减。
     *
     * <p>拿来做「每 N 拍换一次」这类离散节奏事件。换歌（deck 换成另一个 {@code SoundFile}）会归零，
     * 因为基线也要跟着重建，硬接着数会在新歌头几秒数出一堆假拍。</p>
     *
     * <p>这不是 BPM：它数的是<b>能量冲击</b>的个数。密集打点的段落会数得比乐理上的拍快，
     * 安静段落会数得慢。对「跟着音乐换颜色」来说这恰好是想要的效果。</p>
     */
    public static int beatCount() {
        advance();
        return STATE.beats;
    }

    /** 主动断开旁路单元。切歌本身不需要调用——{@link #advance} 会自己重新指向新的 deck。 */
    public static synchronized void detach() {
        SoundFile previous = attached;
        attached = null;
        STATE.clear();
        if (analyzer == null || previous == null) return;
        try {
            analyzer.removeInput();
        } catch (Throwable ignored) {
            unavailable = true;
        }
    }

    private static synchronized void advance() {
        if (unavailable) return;

        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1.0e9;
        if (dt < MIN_ADVANCE_SECONDS && lastNanos != 0L) return;
        lastNanos = now;
        if (dt > MAX_FRAME_SECONDS) dt = MAX_FRAME_SECONDS;
        if (dt <= 0) dt = MIN_ADVANCE_SECONDS;

        float level;
        try {
            level = readLevel();
        } catch (Throwable failure) {
            // 音频图出任何问题都不值得为了一个背景特效冒风险：永久降级，调用方自己退回字词脉冲。
            unavailable = true;
            attached = null;
            analyzer = null;
            STATE.clear();
            System.err.println("[Pulse] 实时包络不可用，已退回字词脉冲: " + failure);
            return;
        }
        step(STATE, level, dt);
    }

    /**
     * 读一次当前 deck 的归一化峰值。
     *
     * <p>除掉音量是必须的：跟随器接在电路输出端，而音量是在读取器上乘的，不除的话把音量调到 25%
     * 画面律动就弱到看不见。除完之后视觉强度只跟音乐本身有关。</p>
     */
    private static float readLevel() {
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        AudioPlayer deck = snapshot == null ? null : snapshot.player;
        if (deck == null || !deck.isUsable() || deck.isPausing()) {
            attached = null;
            return 0f;
        }

        SoundFile sound = deck.player;
        if (sound == null) {
            attached = null;
            return 0f;
        }

        if (analyzer == null) {
            analyzer = new Amplitude();
            analyzer.halfLife(ANALYZER_HALF_LIFE);
        }
        if (attached != sound) {
            analyzer.input(sound);
            attached = sound;
            STATE.clear();
        }

        float raw = analyzer.analyze();
        if (Float.isNaN(raw) || Float.isInfinite(raw) || raw <= 0f) return 0f;

        float amplitude = deck.volume * deck.getFadeGain();
        if (Float.isNaN(amplitude) || Float.isInfinite(amplitude)) return 0f;
        if (amplitude < MIN_AMPLITUDE) amplitude = MIN_AMPLITUDE;
        float level = raw / amplitude;
        return level > 4f ? 4f : level;
    }

    /**
     * 推进一步 onset 检测。纯函数式：只读 {@code level} 与 {@code dt}，只写 {@code state}，
     * 因此可以在没有音频设备的环境里直接断言。
     */
    static void step(State state, float level, double dt) {
        if (state == null || dt <= 0) return;
        float sample = Float.isNaN(level) || Float.isInfinite(level) || level < 0f ? 0f : level;

        if (state.refractory > 0.0) {
            state.refractory -= dt;
            if (state.refractory < 0.0) state.refractory = 0.0;
        }

        double tau = sample > state.baseline ? BASELINE_RISE_TAU : BASELINE_FALL_TAU;
        float approach = (float) (1.0 - Math.exp(-dt / tau));
        state.baseline += (sample - state.baseline) * approach;

        state.pulse *= (float) Math.exp(-dt / PULSE_DECAY_TAU);

        float excess = sample - state.baseline * ONSET_MARGIN;
        float rising = sample - state.previous;
        state.previous = sample;
        if (excess > 0f && rising > 0f) {
            float reference = Math.max(MIN_REFERENCE, state.baseline);
            float hit = clamp01(excess / reference * ONSET_RELATIVE_GAIN);
            if (hit > state.pulse) state.pulse = hit;
            // 计数走自己的门槛与不应期，跟上面那条连续量互不影响。
            if (hit >= BEAT_COUNT_THRESHOLD && state.refractory <= 0.0) {
                state.beats++;
                state.refractory = BEAT_REFRACTORY_SECONDS;
            }
        }
        if (state.pulse < .002f) state.pulse = 0f;

        float target = clamp01(sample * LOUDNESS_SCALE);
        state.loudness += (target - state.loudness) * (float) (1.0 - Math.exp(-dt / LOUDNESS_TAU));
        if (state.loudness < .002f) state.loudness = 0f;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
