package repackage.processing.sound;

import repackage.com.jsyn.data.FloatSample;

/**
 * 把一个 {@link FloatSample} 包一层「变速不变调」。
 *
 * <p>{@code VariableRateStereoReader} 只会做重采样：把 {@code rate} 端口拧到 1.03 倍，速度和音高会
 * 一起涨 —— 用户听到的就是旧歌在交接时突然升高半个音。这个类换一条路：<b>把素材本身按 WSOLA 时间伸缩</b>，
 * 读头仍然以 1.0 倍速走，于是速度变了而音高没动。</p>
 *
 * <h3>为什么能装在这一层</h3>
 * <ul>
 *   <li>读头是<b>严格顺序</b>拉的：{@code advanceToNextFrame()} 每帧调一次
 *       {@code readCurrentChannelDouble(0/1)}，落到 {@code readDouble(frameIndex * channels + ch)}，
 *       下标每次 +1，永不回头。{@link WsolaTimeStretch} 要的正是这种访问模式。</li>
 *   <li>输入侧可以<b>随机读</b>：真正的样本是 {@code RafFloatSample}（WAV 走 RandomAccessFile，
 *       窗口未命中就重读一段）或整块在内存里的 {@code FloatSample}。WSOLA 需要向前看
 *       {@code seq + seek} 帧来找接缝，这一层能满足。</li>
 * </ul>
 *
 * <h3>直通与接管</h3>
 * <p>{@code tempo == 1} 时是<b>真直通</b>：{@code readDouble} 直接转发给内层，一个采样都不经过 WSOLA。
 * 这一点是刻意的 —— 正常听歌的那条路必须和没有这个类时逐采样一致，不能因为「以后可能要变速」
 * 就让每首歌都多走一遍 overlap-add。</p>
 *
 * <p>一旦 {@link #setTempo} 给了非 1 的值就<b>就地接管</b>：以当前输出下标为锚点开始跑 WSOLA。
 * 接管瞬间 tempo 还非常接近 1（速率曲线是从 1.0 斜坡上去的），WSOLA 在 tempo=1 时输出等于输入，
 * 所以接缝听不出来。</p>
 *
 * <p>接管之后<b>不再退回直通</b>，即使 tempo 又回到 1。因为输出时间轴已经和输入错开了
 * （变速期间吃掉的输入比产出的输出多），退回直通等于让源位置往回跳一大段，那是一次实打实的爆音。
 * tempo=1 的 WSOLA 本身就是恒等变换，留着不花什么代价。</p>
 */
public final class WsolaStretchSample extends FloatSample {

    /** 小于这个偏差视为「没在变速」。和 {@link WsolaTimeStretch} 的恒等阈值一致。 */
    private static final double IDLE_EPSILON = 1e-4;

    private final FloatSample inner;
    private final int innerLength;
    private final WsolaTimeStretch stretch;

    private volatile double tempo = 1.0;
    /** 是否已经接管。只由音频线程在 {@link #readDouble} 里翻，翻了就不会翻回去。 */
    private volatile boolean engaged;

    private WsolaStretchSample(FloatSample inner, int channels, int numFrames, double frameRate) {
        this.inner = inner;
        this.innerLength = numFrames * channels;
        // 这里<b>不能</b>用 setNumFrames()，两个坑叠在一起：
        //  1. SequentialDataCommon#setNumFrames 会把入参夹到 maxFrames，而 maxFrames 只由
        //     allocate() 设置 —— 本类刻意把 allocate() 改成空实现（不持有缓冲区），于是 maxFrames
        //     恒为 0，setNumFrames(n) 直接被夹成 0；
        //  2. AudioSample 又重新声明了一个 numFrames 字段，把 SequentialDataCommon 的那个遮住了。
        //     getNumFrames() 读的是 AudioSample 的那一个，setNumFrames() 写的是被遮住的那一个,
        //     就算不被夹也写不到会被读到的地方。
        // 结果是 getNumFrames() 恒为 0 -> SoundFile.frames() 为 0 -> AudioPlayer 判定
        // "Audio file contains no usable frames" 把每一首歌都拒了。FloatSample#allocate 本身就是
        // 直接写字段绕开这个 setter 的，这里照它来，并把 maxFrames 一起补上，免得以后谁调 setter 又踩坑。
        this.maxFrames = numFrames;
        this.numFrames = numFrames;
        setChannelsPerFrame(channels);
        setFrameRate(frameRate);
        this.stretch = new WsolaTimeStretch(new WsolaTimeStretch.Samples() {
            public double at(int index) {
                return WsolaStretchSample.this.inner.readDouble(index);
            }

            public int length() {
                return WsolaStretchSample.this.innerLength;
            }
        }, channels, (int) Math.round(frameRate));
    }

    /**
     * 包一层，包不了就把原样本原封不动地还回来。
     *
     * <p>包不了的情况：{@code null}、帧数或声道数不合法、时长短到装不满一个 WSOLA 序列。
     * 这些都不是错误，只是「这首歌不值得也没法做时间伸缩」，调用方拿到的就是原来那条链路。</p>
     */
    public static FloatSample wrap(FloatSample inner) {
        if (inner == null) return null;
        try {
            int channels = inner.getChannelsPerFrame();
            int numFrames = inner.getNumFrames();
            double frameRate = inner.getFrameRate();
            if (channels < 1 || channels > 2 || numFrames <= 0 || frameRate <= 0) return inner;
            // 至少要够两个序列 + 一个搜索窗，不然第一个块就读到文件尾。
            int minimumFrames = WsolaTimeStretch.frames((int) Math.round(frameRate), 82.0 * 2 + 28.0);
            if (numFrames < minimumFrames) return inner;
            return new WsolaStretchSample(inner, channels, numFrames, frameRate);
        } catch (Throwable ignored) {
            return inner;
        }
    }

    /**
     * 设置伸缩因子（SoundTouch 语义，{@code > 1} 更快更短）。
     *
     * @return {@code true} 表示这一层会负责变速，调用方<b>不要</b>再去动读头的 {@code rate} 端口
     */
    public boolean setTempo(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return engaged;
        double safe = Math.max(WsolaTimeStretch.MIN_TEMPO, Math.min(WsolaTimeStretch.MAX_TEMPO, value));
        this.tempo = safe;
        this.stretch.setTempo(safe);
        return engaged || Math.abs(safe - 1.0) >= IDLE_EPSILON;
    }

    public double getTempo() {
        return this.tempo;
    }

    /** 是否已经在跑 WSOLA（而不是直通）。 */
    public boolean isEngaged() {
        return this.engaged;
    }

    /** 越界重锚次数。正常播放应当恒为 0；不为 0 说明读头不是顺序拉的。 */
    public int getReanchorCount() {
        return this.stretch.getReanchorCount();
    }

    /** 定位之后必须调：把输出时间轴重新对齐到源的同一位置。 */
    public void resync() {
        this.engaged = false;
    }

    @Override
    public double readDouble(int index) {
        if (!engaged) {
            if (Math.abs(tempo - 1.0) < IDLE_EPSILON) {
                return inner.readDouble(index);
            }
            // 只在帧边界接管。{@link WsolaTimeStretch#anchor} 把输出下标整除声道数当作起始帧，
            // 而块的第 0 个采样恒为某一帧的第 0 声道；若在右声道的下标上锚定，从这一刻起左右声道
            // 整体错开一格 —— 听起来是立体声像被镜像，而且接管之后不退回直通，再也纠不回来。
            // 读头是逐采样顺序拉的，最多等一个采样就落到帧边界上。
            int channels = getChannelsPerFrame();
            if (channels > 1 && (index % channels) != 0) {
                return inner.readDouble(index);
            }
            // 就地接管：锚点就是当前这个下标，源位置因此完全连续。
            stretch.anchor(index);
            engaged = true;
        }
        return stretch.read(index);
    }

    @Override
    public void writeDouble(int index, double value) {
        // 只读装饰器。写进来的值没有去处，静默忽略比抛异常安全：写路径只有录音用得到。
    }

    @Override
    public boolean isIndexOverflow(int index) {
        return index >= innerLength;
    }

    @Override
    public void allocate(int numFrames, int channelsPerFrame) {
        // 不持有缓冲区，什么都不用分配。父类会 new 一个 numFrames*channels 的 float[]，
        // 对一首五分钟的立体声曲子是 50 MB 的白扔。
    }

    @Override
    public void read(int startFrame, float[] data, int startIndex, int numFrames) {
        int channels = getChannelsPerFrame();
        int total = numFrames * channels;
        int base = startFrame * channels;
        for (int i = 0; i < total; i++) {
            data[startIndex + i] = (float) readDouble(base + i);
        }
    }

    @Override
    public double interpolate(double fractionalIndex) {
        int index = (int) fractionalIndex;
        double phase = fractionalIndex - index;
        double source = readDouble(index);
        double target = readDouble(index + 1);
        return ((target - source) * phase) + source;
    }

    @Override
    public void cleanUp() {
        try {
            inner.cleanUp();
        } catch (Throwable ignored) {
        }
    }
}
