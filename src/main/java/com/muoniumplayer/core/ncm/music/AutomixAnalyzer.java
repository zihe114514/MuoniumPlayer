package com.muoniumplayer.core.ncm.music;

/**
 * Offline analysis used to place an automix handover. Pure DSP: every method takes a mono window that
 * {@link PcmWindowReader} has already read out of the decoded cache file.
 *
 * <p>The windows deliberately do <em>not</em> come from the live JSyn sample. That sample streams from a
 * shared sliding window, so reading an arbitrary position out of a deck that is playing repositions the
 * buffer under the audio thread and kills playback - see {@link PcmWindowReader} for the failure this
 * caused in practice.</p>
 *
 * <p>Three questions are answered:
 * <ul>
 *   <li><b>Beat grid</b> — tempo plus the phase of the first beat and of the first bar, so the
 *       handover can land on a phrase boundary rather than mid-measure. Classic onset-autocorrelation
 *       tempo induction on a time-domain energy envelope (no FFT needed for beat spacing).</li>
 *   <li><b>Intro end</b> — the first sustained rise in energy, i.e. where the incoming track actually
 *       starts, so the overlap is not spent on dead air.</li>
 *   <li><b>Loudness</b> — K-weighted loudness (ITU-R BS.1770-4), so a quiet incoming track can be
 *       lifted to match the outgoing one instead of appearing to drop out. Plain RMS is kept as the
 *       fallback, but it over-rates bass-heavy material: a track with a big kick and no top end reads
 *       louder than it sounds, and matching on it makes the blend lurch.</li>
 * </ul>
 *
 * <p>These are estimates. Every caller treats a {@code null} / non-positive answer as "no structural
 * information" and falls back to a plain equal-power crossfade.</p>
 */
final class AutomixAnalyzer {

    /** Envelope hop in samples (~11.6 ms at 44.1 kHz) — fine enough for beat spacing. */
    private static final int HOP = 512;
    private static final float MIN_BPM = 70f;
    private static final float MAX_BPM = 180f;
    /** Coarser hop for the outro envelope (~23 ms): structure, not beats. */
    private static final int OUTRO_HOP = 1024;
    /** The entrance is nicer just before the kick-in than exactly on it. */
    private static final float INTRO_BACKOFF_MILLIS = 200f;
    /**
     * Normalised autocorrelation that counts as full confidence in a beat grid. Measured rather than
     * guessed: four-on-the-floor material lands around .4-.6, a band with a live drummer around .3, and
     * a pad or a spoken passage below .1. Mapping .35 to 1 therefore puts "there is a beat here" above
     * the threshold and "this is an outro" below it.
     */
    private static final float FULL_CONFIDENCE_CORRELATION = .35f;
    /**
     * How well a parabolic fit locates the autocorrelation peak, as a fraction of one lag bin. A tenth of
     * a bin is the usual rule of thumb for a smooth peak; the resulting relative tempo precision is this
     * divided by the lag, i.e. better for slow tracks (more hops per beat) than for fast ones.
     */
    private static final double PEAK_FIT_PRECISION = .12;

    /**
     * 谱通量的帧长与帧移，秒。取 BeatRoot 原始参数（44.1kHz 下的 2048 / 512 点）。
     *
     * <p>不能改成本类原有的 {@link #HOP}：22.05kHz 下那是 23.2ms 一格，而 BeatRoot 的整套参数
     * （IOI 聚类宽度 25ms、内窗 40ms）都是按 11.6ms 分辨率的 onset 时刻调出来的，喂 23.2ms 的
     * 量化时刻进去，聚类宽度里只能装下一格，速度假设会碎成一堆。</p>
     */
    private static final double FLUX_FRAME_SECONDS = .04644;
    private static final double FLUX_HOP_SECONDS = .011609;
    /**
     * BeatRoot 网格低于这个置信度就不如退回自相关。
     *
     * <p>比 {@code AutomixPlanner.MIN_GRID_CONFIDENCE}（.28）低一档：这里只是决定「两个测速
     * 方法用哪个」，真正「这条网格能不能用」还要再过一遍那个门槛。</p>
     */
    private static final float MIN_BEATROOT_CONFIDENCE = .18f;
    /** 两种测速方法的速度相差在这个比例内就算彼此印证。 */
    private static final float TEMPO_AGREEMENT = .03f;
    /** 彼此印证时给置信度的加成。 */
    private static final float AGREEMENT_BONUS = 1.15f;
    /** 彼此矛盾时给置信度打的折。 */
    private static final float DISAGREEMENT_DISCOUNT = .70f;
    /** selectivity 到这里就算健康：鼓上带镲时只有一半 onset 会被采纳为拍。 */
    private static final float MIN_HEALTHY_SELECTIVITY = .45f;
    /** 拍点残差按拍长归一后到这个比例就算完全不规整。 */
    private static final double RESIDUAL_TOLERANCE = .06;
    /** 绝对静音门限（dB）。低于这个电平的一段当作「文件里的空白」，不是「安静的音乐」。 */
    private static final double SILENCE_FLOOR_DB = repackage.be.tarsos.dsp.SilenceDetector
            .DEFAULT_SILENCE_THRESHOLD;
    /** 稳健响度参考取到这个分位，避免一声拍手把参考电平顶上去。 */
    private static final double LOUDNESS_PERCENTILE = .95;
    /** 入场门限，相对稳健参考的 dB。-8dB 约等于原来的「峰值 40%」。 */
    private static final double ENTRANCE_DB = -8.0;
    /** 入场之后要维持住的门限，相对稳健参考的 dB。-10.5dB 约等于原来的「峰值 30%」。 */
    private static final double SUSTAIN_DB = -10.5;
    /** {@code FourierMath} 懒初始化静态表的发布锁，见 {@link #spectralFlux}。 */
    private static final Object FFT_TABLE_LOCK = new Object();

    /**
     * Block length for the chroma analysis, ~372 ms at 22.05 kHz. The Goertzel bin is then ~2.7 Hz wide,
     * which still separates adjacent semitones at the bottom of the analysed range (7.8 Hz apart at C3)
     * while being short enough that a chord change does not smear across the whole window.
     */
    private static final int CHROMA_BLOCK = 8_192;
    /** C3 to B6 in MIDI numbers. Below C3 the bins stop resolving semitones; above B6 there is mostly
     *  harmonic content of notes already counted. */
    private static final int CHROMA_LOWEST_MIDI = 48;
    private static final int CHROMA_HIGHEST_MIDI = 83;

    /**
     * Krumhansl-Kessler key profiles: the tonal hierarchies measured in the probe-tone experiments,
     * still the standard template for template-matching key finders.
     */
    private static final double[] MAJOR_PROFILE =
            {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR_PROFILE =
            {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /**
     * Harmonic distance between two keys, indexed by the interval between their positions on the Camelot
     * wheel expressed in semitones of their relative major.
     *
     * <p>This is the wheel written out. One step around it is a perfect fifth (7 semitones) or fourth
     * (5), which is what DJs mix on; two steps is a stretch; and a semitone or a tritone apart is the
     * classic key clash that makes an otherwise clean blend sound broken. A semitone up is a legitimate
     * <em>sequential</em> energy-boost move, but it is the worst thing two <em>simultaneous</em> tracks can
     * be, which is the only case here.</p>
     *
     * <p>Comparing relative majors rather than literal tonics is deliberate and does double duty. It is
     * how the wheel is actually defined - A minor and C major share one slot - and it makes the whole
     * table immune to the one mistake chroma template matching reliably makes: a minor key and its
     * relative major have the same pitch-class set, so no amount of tuning will separate them, and a
     * table indexed on the literal tonic would read G major against E minor as four semitones apart
     * (a clash) when they are the same slot.</p>
     */
    private static final double[] WHEEL_PENALTY =
            {.00, 1.00, .45, .55, .70, .18, .95, .15, .70, .55, .45, 1.00};
    /**
     * Surcharge for a major against a minor in the same wheel slot, i.e. a relative pair. Small on
     * purpose: their scales are identical and only the centre of gravity differs, so it is a colour
     * difference rather than a collision - and it has to stay below the threshold that triggers a
     * transposition, because a relative pair is exactly what a misread key looks like.
     */
    private static final double MODE_MISMATCH_PENALTY = .12;

    private AutomixAnalyzer() {
    }

    /** Estimated tempo plus the beat and bar phase, both in ms from the start of the window. */
    static final class BeatGrid {
        final float bpm;
        final float phaseMillis;
        final float barPhaseMillis;
        /**
         * How much the grid is worth trusting, 0..1.
         *
         * <p>Tempo induction always returns <em>a</em> number: the autocorrelation has a maximum even on
         * a string pad. Without this the planner would happily quantise a mix-out onto an imaginary bar
         * line in an outro and claim it was beat matched, which moves the handover by up to two seconds
         * for no reason. It is also what decides whether the rate budget is spent on tempo (a real beat
         * to keep locked) or on pitch (no beat, so key agreement is the only thing audible).</p>
         */
        final float confidence;
        /**
         * The measurement's own relative precision, e.g. {@code .006} for "this tempo is good to about
         * 0.6 %".
         *
         * <p>Tempo comes out of an autocorrelation over whole envelope hops, so its resolution depends on
         * how many hops long the beat is: a fast track is measured on fewer hops and less precisely. The
         * planner needs the number because bending a deck by less than the analysis error is not a
         * correction, it is acting on noise - and two windows of the <em>same</em> track routinely disagree
         * by a percent, which used to be enough to earn a 1.5 % "tempo lock" on a pair already in sync.</p>
         */
        final float tempoTolerance;

        BeatGrid(float bpm, float phaseMillis, float barPhaseMillis, float confidence) {
            // Conservative default for callers that did not measure a precision.
            this(bpm, phaseMillis, barPhaseMillis, confidence, .01f);
        }

        BeatGrid(float bpm, float phaseMillis, float barPhaseMillis, float confidence,
                 float tempoTolerance) {
            this.bpm = bpm;
            this.phaseMillis = phaseMillis;
            this.barPhaseMillis = barPhaseMillis;
            this.confidence = Float.isNaN(confidence) ? 0f
                    : Math.max(0f, Math.min(1f, confidence));
            this.tempoTolerance = Float.isNaN(tempoTolerance) ? .01f
                    : Math.max(.002f, Math.min(.05f, tempoTolerance));
        }

        float beatPeriodMillis() {
            return 60_000f / bpm;
        }

        /** One 4-beat bar at this tempo. */
        float barPeriodMillis() {
            return 4f * 60_000f / bpm;
        }
    }

    /** Estimated key: tonic pitch class (0 = C … 11 = B), mode, and 0..1 confidence. */
    static final class KeyEstimate {
        final int tonic;
        final boolean major;
        final float confidence;

        KeyEstimate(int tonic, boolean major, float confidence) {
            this.tonic = ((tonic % 12) + 12) % 12;
            this.major = major;
            this.confidence = Float.isNaN(confidence) ? 0f
                    : Math.max(0f, Math.min(1f, confidence));
        }

        /** e.g. {@code "F#m"}. */
        String label() {
            return NOTE_NAMES[this.tonic] + (this.major ? "" : "m");
        }
    }

    private static final String[] NOTE_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /**
     * Integrated K-weighted loudness of the window in LUFS, or {@link Float#NaN} when it cannot be
     * measured (empty window, unusable rate, or nothing above the absolute gate).
     *
     * <p>Follows ITU-R BS.1770-4 for a single channel: the two stage K-weighting filter (a ~+4 dB high
     * shelf standing in for the head related transfer function, then a 38 Hz high pass), mean square
     * over 400 ms blocks at 75 % overlap, the −70 LUFS absolute gate and the −10 LU relative gate. The
     * filter coefficients are derived for the window's own sample rate rather than hard-coded for
     * 48 kHz, so a 44.1 kHz track is not measured through a detuned filter.</p>
     *
     * <p>This is what the blend matches on. Two tracks with the same RMS can differ by several LU, and
     * a few LU is exactly the size of step a listener hears as "the volume jumped at the handover".</p>
     */
    static float kWeightedLoudness(float[] mono, int sampleRate) {
        if (mono == null || mono.length == 0 || sampleRate <= 0) return Float.NaN;

        double[] filtered = kWeight(mono, sampleRate);

        int blockFrames = (int) Math.round(sampleRate * .4);
        if (blockFrames <= 0) return Float.NaN;
        int step = Math.max(1, blockFrames / 4);

        if (filtered.length < blockFrames) {
            // Too short for the standard's block structure: report the ungated mean square instead of
            // refusing to answer, because a 1.5 s overlap still needs a level to match on.
            double mean = meanSquare(filtered, 0, filtered.length);
            return mean > 1e-12 ? (float) (-.691 + 10.0 * Math.log10(mean)) : Float.NaN;
        }

        int blocks = (filtered.length - blockFrames) / step + 1;
        double[] blockPower = new double[blocks];
        int kept = 0;
        double gatedSum = 0.0;
        for (int block = 0; block < blocks; block++) {
            double mean = meanSquare(filtered, block * step, blockFrames);
            blockPower[block] = mean;
            // Absolute gate: -70 LUFS.
            if (mean > 1e-12 && -.691 + 10.0 * Math.log10(mean) > -70.0) {
                gatedSum += mean;
                kept++;
            }
        }
        if (kept == 0) return Float.NaN;

        // Relative gate: 10 LU below the level of everything that passed the absolute gate.
        double relativeThreshold = -.691 + 10.0 * Math.log10(gatedSum / kept) - 10.0;
        double finalSum = 0.0;
        int finalCount = 0;
        for (int block = 0; block < blocks; block++) {
            double mean = blockPower[block];
            if (mean <= 1e-12) continue;
            double level = -.691 + 10.0 * Math.log10(mean);
            if (level > -70.0 && level > relativeThreshold) {
                finalSum += mean;
                finalCount++;
            }
        }
        if (finalCount == 0) {
            return (float) (-.691 + 10.0 * Math.log10(gatedSum / kept));
        }
        return (float) (-.691 + 10.0 * Math.log10(finalSum / finalCount));
    }

    private static double meanSquare(double[] values, int offset, int length) {
        if (length <= 0) return 0.0;
        double sum = 0.0;
        int end = Math.min(values.length, offset + length);
        for (int i = offset; i < end; i++) {
            sum += values[i] * values[i];
        }
        int used = end - offset;
        return used <= 0 ? 0.0 : sum / used;
    }

    /**
     * Applies the BS.1770 K-weighting pair. Both stages are biquads whose coefficients come from the
     * standard's reference design (high shelf: 1681.97 Hz, Q 0.7071, +3.9998 dB; high pass: 38.135 Hz,
     * Q 0.5003), mapped to the actual sample rate through the bilinear transform.
     */
    private static double[] kWeight(float[] mono, int sampleRate) {
        double[] output = new double[mono.length];

        // Stage 1: high shelf.
        double shelfGainDb = 3.999843853973347;
        double shelfQ = .7071752369554196;
        double shelfHz = 1681.974450955533;
        double k = Math.tan(Math.PI * shelfHz / sampleRate);
        double vh = Math.pow(10.0, shelfGainDb / 20.0);
        double vb = Math.pow(vh, .4996667741545416);
        double denominator = 1.0 + k / shelfQ + k * k;
        double b0 = (vh + vb * k / shelfQ + k * k) / denominator;
        double b1 = 2.0 * (k * k - vh) / denominator;
        double b2 = (vh - vb * k / shelfQ + k * k) / denominator;
        double a1 = 2.0 * (k * k - 1.0) / denominator;
        double a2 = (1.0 - k / shelfQ + k * k) / denominator;

        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int i = 0; i < mono.length; i++) {
            double x0 = mono[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }

        // Stage 2: high pass (RLB weighting).
        double passQ = .5003270373238773;
        double passHz = 38.13547087602444;
        k = Math.tan(Math.PI * passHz / sampleRate);
        denominator = 1.0 + k / passQ + k * k;
        a1 = 2.0 * (k * k - 1.0) / denominator;
        a2 = (1.0 - k / passQ + k * k) / denominator;
        b0 = 1.0;
        b1 = -2.0;
        b2 = 1.0;

        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
        for (int i = 0; i < output.length; i++) {
            double x0 = output[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            output[i] = y0;
        }
        return output;
    }

    /** Root mean square of the window; 0 when there is nothing to measure. */
    static float rms(float[] mono) {
        if (mono == null || mono.length == 0) return 0f;
        double squared = 0.0;
        for (float value : mono) {
            squared += (double) value * value;
        }
        return (float) Math.sqrt(squared / mono.length);
    }

    /**
     * Estimates the beat grid of a mono window.
     *
     * @return the grid, or {@code null} when the window is too short or effectively silent.
     */
    static BeatGrid analyzeBeats(float[] mono, int sampleRate) {
        BeatGrid autocorrelated = autocorrelationGrid(mono, sampleRate);
        // 自相关都立不起网格（窗口太短、或者整段几乎没有能量变化）时不让 BeatRoot 顶上来：
        // 那种素材上「测不出节拍」本身就是正确答案，硬凑一条网格出来只会让 planner 去对齐一条
        // 不存在的小节线。
        if (autocorrelated == null) return null;
        return preferBeatRoot(mono, sampleRate, autocorrelated);
    }

    /**
     * 原来的测速路径：onset 能量通量的自相关。现在只作为 {@link #preferBeatRoot} 的兜底与对照。
     *
     * @return the grid, or {@code null} when the window is too short or effectively silent.
     */
    static BeatGrid autocorrelationGrid(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return null;
        int hops = mono.length / HOP;
        if (hops < 16) return null;

        // 1. Onset envelope: half-wave rectified energy flux tracks percussive onsets well enough
        //    for beat spacing, and needs no FFT.
        float[] onset = new float[hops];
        float previousEnergy = 0f;
        double total = 0.0;
        for (int hop = 0; hop < hops; hop++) {
            int base = hop * HOP;
            float energy = 0f;
            for (int index = 0; index < HOP; index++) {
                float value = mono[base + index];
                energy += value * value;
            }
            energy = (float) Math.sqrt(energy / HOP);
            float flux = energy - previousEnergy;
            onset[hop] = flux > 0f ? flux : 0f;
            previousEnergy = energy;
            total += onset[hop];
        }
        if (total < 1e-4) return null;

        // Mean-remove so the autocorrelation measures periodicity instead of the DC level.
        float mean = (float) (total / hops);
        for (int hop = 0; hop < hops; hop++) {
            onset[hop] -= mean;
        }

        float envelopeRate = (float) sampleRate / HOP;
        int minLag = Math.max(1, Math.round(envelopeRate * 60f / MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(envelopeRate * 60f / MIN_BPM));
        if (maxLag <= minLag) return null;

        // 2. Autocorrelation across the tempo lag range.
        int lagCount = maxLag - minLag + 1;
        double[] scores = new double[lagCount];
        int bestLag = minLag;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double correlation = 0.0;
            for (int hop = lag; hop < hops; hop++) {
                correlation += (double) onset[hop] * onset[hop - lag];
            }
            // A slight bias toward faster tempi cancels the natural falloff at long lags.
            correlation *= 1.0 + 0.0005 * (maxLag - lag);
            scores[lag - minLag] = correlation;
            if (correlation > bestScore) {
                bestScore = correlation;
                bestLag = lag;
            }
        }

        // 2a. Sub-hop refinement, by fitting a parabola through the winning lag and its two neighbours.
        //
        //     Without this the tempo is quantised to whole envelope hops, and that quantisation is
        //     coarse enough to matter: at 22.05 kHz a hop is 23.2 ms, so a 120 BPM beat is 21.5 hops and
        //     rounds to either 117.5 or 123.1 BPM. Two windows of the *same* track can therefore land on
        //     opposite sides and look 4.8 % apart - which the tempo lock would then faithfully "correct"
        //     by bending a deck 5 % for no reason. Interpolating the peak brings the error under half a
        //     percent, which is what makes a tempo lock worth applying at all.
        double refinedLag = bestLag;
        int peak = bestLag - minLag;
        if (peak > 0 && peak < lagCount - 1) {
            double left = scores[peak - 1];
            double centre = scores[peak];
            double right = scores[peak + 1];
            double curvature = left - 2.0 * centre + right;
            // Negative curvature means it really is a maximum; anything else is a plateau or noise and
            // the integer lag is kept.
            if (curvature < -1e-12) {
                double delta = .5 * (left - right) / curvature;
                if (delta > .5) delta = .5;
                if (delta < -.5) delta = -.5;
                refinedLag = bestLag + delta;
            }
        }
        if (refinedLag < 1.0) refinedLag = 1.0;
        float bpm = (float) (60.0 * envelopeRate / refinedLag);
        if (bpm < MIN_BPM) bpm = MIN_BPM;
        if (bpm > MAX_BPM) bpm = MAX_BPM;

        // 2b. Confidence: the *normalised* autocorrelation at the winning lag. The raw score above is
        //     not usable for this - it is biased by the fast-tempo weighting and scales with the window
        //     energy. Normalising by the energy of the two overlapping halves separately also removes
        //     the systematic penalty long lags would otherwise take for having fewer terms.
        double product = 0.0;
        double energyLate = 0.0;
        double energyEarly = 0.0;
        for (int hop = bestLag; hop < hops; hop++) {
            double late = onset[hop];
            double early = onset[hop - bestLag];
            product += late * early;
            energyLate += late * late;
            energyEarly += early * early;
        }
        double normaliser = Math.sqrt(energyLate * energyEarly);
        double normalised = normaliser > 1e-12 ? product / normaliser : 0.0;
        float confidence = (float) Math.max(0.0,
                Math.min(1.0, normalised / FULL_CONFIDENCE_CORRELATION));

        // 3. Beat phase: the pulse-train offset with the highest onset sum.
        int bestOffset = 0;
        float bestSum = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < bestLag; offset++) {
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += bestLag) {
                sum += onset[hop];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestOffset = offset;
            }
        }
        float millisPerHop = HOP * 1000f / sampleRate;
        float phaseMillis = bestOffset * millisPerHop;

        // 3b. Bar phase: re-fit at four times the period. Only the four offsets that are whole beats
        //     away from the beat phase are tested, so the answer is a real downbeat (beat 1 of 4) and
        //     not an arbitrary point inside a beat. Stepping by a fraction of a beat here — as is easy
        //     to do by accident — returns offsets that are not on the grid at all.
        int barLag = bestLag * 4;
        float tolerance = (float) (PEAK_FIT_PRECISION / refinedLag);
        if (barLag >= hops) {
            return new BeatGrid(bpm, phaseMillis, phaseMillis, confidence, tolerance);
        }

        float bestBarSum = Float.NEGATIVE_INFINITY;
        int bestBarOffset = bestOffset;
        for (int beat = 0; beat < 4; beat++) {
            int offset = bestOffset + beat * bestLag;
            if (offset >= hops) break;
            float sum = 0f;
            for (int hop = offset; hop < hops; hop += barLag) {
                sum += onset[hop];
            }
            if (sum > bestBarSum) {
                bestBarSum = sum;
                bestBarOffset = offset;
            }
        }
        return new BeatGrid(bpm, phaseMillis, bestBarOffset * millisPerHop, confidence, tolerance);
    }

    /**
     * 用 BeatRoot 的多智能体节拍跟踪重测一次，测得比自相关好就用它的结果。
     *
     * <p>自相关只回答「平均隔多久有一下」，相位得靠脉冲串套出来；BeatRoot 直接给出<b>每一拍在哪</b>，
     * 于是相位是真实拍点、速度是这条拍点列表的回归斜率、精度是斜率的标准误 —— 三个数都不再是估的。</p>
     *
     * <p>两者的关系是<b>互相印证</b>而不是替换：速度对得上就给置信度加成，对不上就给 BeatRoot 打折
     * （仍然用它的速度：它才是有拍点证据的那一方），BeatRoot 立不起网格时原样退回自相关的结果。
     * 所以这条路只会让结果更好或者不变，不会凭空变差。</p>
     *
     * @param fallback 自相关测出来的网格，非 {@code null}
     */
    private static BeatGrid preferBeatRoot(float[] mono, int sampleRate, BeatGrid fallback) {
        try {
            int hopSize = (int) Math.round(sampleRate * FLUX_HOP_SECONDS);
            if (hopSize < 32) return fallback;
            int fftSize = 1;
            int wanted = (int) Math.round(sampleRate * FLUX_FRAME_SECONDS);
            while (fftSize < wanted) fftSize <<= 1;
            if (mono.length < fftSize + hopSize * 32) return fallback;

            double[] flux = spectralFlux(mono, fftSize, hopSize);
            if (flux == null) return fallback;
            // track() 会原地归一化，而下面挑小节相位还要用原始通量，所以留一份。
            double[] rawFlux = new double[flux.length];
            System.arraycopy(flux, 0, rawFlux, 0, flux.length);

            double hopSeconds = hopSize / (double) sampleRate;
            repackage.be.tarsos.dsp.beatroot.BeatRootTracker.Result result =
                    repackage.be.tarsos.dsp.beatroot.BeatRootTracker.track(flux, hopSeconds);
            if (result == null) return fallback;

            // 1. 节拍层级。BeatRoot 只保证把周期折进 60~200 BPM，它经常锁在八分音符上
            //    （鼓 + 闭合镲的素材实测就是这样），于是一首 100 BPM 的歌会报 200。
            //    用自相关那个数当锚点来折，比硬折进 [MIN_BPM, MAX_BPM] 靠得住得多：后者对
            //    「88 BPM 被报成 176」这种恰好落在范围内的情况完全无能为力。
            float bpm = octaveNormalise((float) result.bpm(), fallback.bpm);
            while (bpm < MIN_BPM) bpm *= 2f;
            while (bpm > MAX_BPM) bpm /= 2f;
            if (bpm < MIN_BPM || bpm > MAX_BPM) return fallback;

            // 2. 相位。谱通量把 onset 时刻系统性地报早了：一帧覆盖 fftSize 个采样，鼓头落进
            //    第一个<b>包含</b>它的帧，而那一帧的起点最早可以比鼓头早整整一个帧长。取帧中心
            //    作为时刻是通行做法，实测正好把偏差从 -27ms 拉回 0 附近。
            float frameCentreMillis = fftSize * 500f / sampleRate;
            float beatPeriod = 60_000f / bpm;
            double originMillis = result.gridOriginSeconds * 1000.0 + frameCentreMillis;
            // 网格原点可能是负的（回归外推到第 0 拍），取模后补回正区间。
            float phaseMillis = (float) (originMillis % beatPeriod);
            if (phaseMillis < 0f) phaseMillis += beatPeriod;

            // 3. 置信度。这里<b>不能</b>用「拍点拟合得多好」当证据：onset 密的素材上（噪声、
            //    持续音、念白）总能挑出一串间隔相等的 onset 来，拟合残差一样小，
            //    matchedFraction 甚至能到 1.0 —— 实测一段没有节拍的双音 pad 就是这样。
            //    真正能区分「有没有拍」的是两件事：
            //      selectivity：被采纳为拍的 onset 占全部 onset 的比例。真有鼓的素材上 onset
            //        本来就都落在拍或八分位上（实测 0.5~1.0），而噪声与持续音的 onset 满地都是
            //        （实测 0.25~0.34）。0.45 以上都算正常，所以到那里就封顶。
            //      regularity：拍点相对拟合网格的残差，按拍长归一。真鼓 1~2%，念白 5%，噪声 15%。
            float selectivity = result.onsetCount > 0
                    ? result.matchedBeats / (float) result.onsetCount : 0f;
            float selectivityScore = Math.min(1f, selectivity / MIN_HEALTHY_SELECTIVITY);
            float regularity = (float) (1.0 - result.residualSeconds
                    / (RESIDUAL_TOLERANCE * Math.max(1e-6, result.beatIntervalSeconds)));
            if (regularity < 0f) regularity = 0f;
            if (regularity > 1f) regularity = 1f;
            float quality = selectivityScore * regularity;

            boolean agrees = Math.abs(bpm - fallback.bpm) <= fallback.bpm * TEMPO_AGREEMENT;
            float confidence;
            if (agrees) {
                // 两个独立方法测出同一个速度，取高的那个再给一点加成。
                confidence = Math.min(1f,
                        Math.max(quality, fallback.confidence) * AGREEMENT_BONUS);
            } else {
                // 各说各话：取低的那个再打折。仍然用 BeatRoot 的速度与相位（它才有逐拍证据），
                // 但绝不让这种情况下的网格顶着高置信度去触发节奏锁定。
                confidence = Math.min(quality, fallback.confidence) * DISAGREEMENT_DISCOUNT;
            }
            if (confidence < MIN_BEATROOT_CONFIDENCE) return fallback;

            float barPhaseMillis = pickBarPhase(rawFlux, hopSeconds, frameCentreMillis,
                    phaseMillis, beatPeriod);
            return new BeatGrid(bpm, phaseMillis, barPhaseMillis, confidence,
                    (float) result.tempoTolerance);
        } catch (RuntimeException ignored) {
            // 分析永远不能让一次交接失败：测不出来就用自相关那份。
            return fallback;
        }
    }

    /**
     * 在 {@code phaseMillis + k·beatPeriod}（{@code k} 取 0..3）里挑一个当小节头：四个候选各自
     * 隔四拍取一次通量求和，最响的那一路就是 downbeat。
     *
     * <p>只测这四个偏移，所以答案一定落在拍上，而不是拍与拍之间的某个点。</p>
     */
    private static float pickBarPhase(double[] flux, double hopSeconds, float frameCentreMillis,
                                      float phaseMillis, float beatPeriod) {
        double barPeriod = beatPeriod * 4.0;
        float best = phaseMillis;
        double bestSum = Double.NEGATIVE_INFINITY;
        for (int beat = 0; beat < 4; beat++) {
            double offset = phaseMillis + beat * (double) beatPeriod;
            double sum = 0.0;
            int counted = 0;
            for (double at = offset; ; at += barPeriod) {
                // 时刻里含了帧中心的偏移，回到帧下标时要减掉。
                int hop = (int) Math.round((at - frameCentreMillis) / 1000.0 / hopSeconds);
                if (hop >= flux.length) break;
                if (hop >= 0) {
                    sum += flux[hop];
                    counted++;
                }
            }
            // 四个候选覆盖的拍数可能差一格，按拍数取平均才公平。
            double score = counted > 0 ? sum / counted : Double.NEGATIVE_INFINITY;
            if (score > bestSum) {
                bestSum = score;
                best = (float) offset;
            }
        }
        return best;
    }

    /**
     * 谱通量 onset 强度：相邻两帧幅度谱里<b>变大</b>的那部分求和。
     *
     * <p>移植自 TarsosDSP 的 {@code BeatRootSpectralFluxOnsetDetector}（它自己也是 Simon Dixon 的
     * BeatRoot 那一版）。比本类原有的能量通量灵敏得多：能量通量只看总响度的变化，一个和弦换成
     * 另一个等响的和弦它一点反应都没有，而谱通量会看到能量在频率上搬了家。</p>
     *
     * <h3>与上游的不同</h3>
     * <ul>
     *   <li>窗函数用 Hamming 的精确系数（25/46、21/46），上游是 {@code ScaledHammingWindow}
     *       —— 同一个形状乘一个常数。后面 {@code Peaks.normalise} 要做 z-score，常数会被完全约掉，
     *       所以这个差别在结果上是零。</li>
     *   <li>只算幅度不算相位：上游的 {@code powerPhaseFFTBeatRootOnset} 会顺带填一个相位数组，
     *       但通量只用到幅度。</li>
     *   <li>没有上游那套 {@code frames[][]} / {@code energy[]} / {@code ltAverage} 归一化。那些是给
     *       画频谱图和别的检测器用的，通量本身不经过它们。</li>
     * </ul>
     */
    static double[] spectralFlux(float[] mono, int fftSize, int hopSize) {
        if (mono == null || fftSize < 8 || hopSize < 1) return null;
        int frames = (mono.length - fftSize) / hopSize + 1;
        if (frames < 32) return null;

        float[] window = new float[fftSize];
        double factor = 2.0 * Math.PI / (fftSize - 1);
        for (int index = 0; index < fftSize; index++) {
            window[index] = (float) (25.0 / 46.0 - 21.0 / 46.0 * Math.cos(factor * index));
        }

        int bins = fftSize / 2;
        float[] real = new float[fftSize];
        float[] imaginary = new float[fftSize];
        float[] previous = new float[bins];
        float[] magnitude = new float[bins];
        double[] flux = new double[frames];

        for (int frame = 0; frame < frames; frame++) {
            int base = frame * hopSize;
            for (int index = 0; index < fftSize; index++) {
                real[index] = mono[base + index] * window[index];
                imaginary[index] = 0f;
            }
            if (frame == 0) {
                // FourierMath 的正弦表与位反转表是懒初始化的静态数组，没有任何同步。分析跑在
                // 后台线程池上（最多两条并发），第一次调用统一在一个锁里做，另一条线程在同一个
                // 锁上进出一次之后就一定能看到建好的表，后面的帧不用再进锁。
                synchronized (FFT_TABLE_LOCK) {
                    repackage.com.jsyn.util.FourierMath.transform(1, fftSize, real, imaginary);
                }
            } else {
                repackage.com.jsyn.util.FourierMath.transform(1, fftSize, real, imaginary);
            }
            double sum = 0.0;
            for (int bin = 0; bin < bins; bin++) {
                float value = (float) Math.sqrt(real[bin] * real[bin]
                        + imaginary[bin] * imaginary[bin]);
                magnitude[bin] = value;
                if (value > previous[bin]) sum += value - previous[bin];
            }
            // 第 0 帧没有上一帧可比，通量按 0 记。上游那里 previous 是全零，于是第 0 帧的通量等于
            // 整帧幅度之和 —— 上游从文件开头（通常近乎静音）起算所以无害，而本项目的分析窗可能
            // 从副歌中间开始，那个离群的巨值会把后面 z-score 的标准差顶高一大截，真正的鼓点反而
            // 全被 0.35σ 的门限挡掉。
            flux[frame] = frame == 0 ? 0.0 : sum;
            float[] swap = previous;
            previous = magnitude;
            magnitude = swap;
        }
        return flux;
    }

    /**
     * Finds where an intro ends: the first hop that reaches a meaningful share of the window peak and
     * then stays there for about a second. A single drum hit is not an entrance, so the sustain check
     * matters more than the step itself.
     *
     * @return offset in ms from the start of the window, or 0 when no clear entrance was found.
     */
    static float detectIntroEnd(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return 0f;
        int hops = mono.length / HOP;
        if (hops < 16) return 0f;

        float[] levels = new float[hops];
        for (int hop = 0; hop < hops; hop++) {
            levels[hop] = (float) repackage.be.tarsos.dsp.SilenceDetector
                    .calculateRMS(mono, hop * HOP, HOP);
        }

        float millisPerHop = HOP * 1000f / sampleRate;
        // 1. 文件里真正开始有信号的位置。要求连续 120ms 都在绝对静音门限之上，一次爆音不算。
        int audibleHops = Math.max(2, Math.round(120f / millisPerHop));
        int audioStart = firstAudibleHop(levels, audibleHops);
        if (audioStart < 0) return 0f;

        // 2. 稳健响度参考。原来这里用的是窗口峰值，于是整首歌里最响的那一下（一次拍手、一次
        //    军鼓滚奏的尖峰）会把参考电平顶高，入场门限跟着抬上去，前奏就再也测不出来。
        //    取 95 分位数之后单个离群样点影响不到参考。
        float reference = percentile(levels, LOUDNESS_PERCENTILE);
        if (reference < .001f) return 0f;

        float entranceFloor = (float) (reference * Math.pow(10.0, ENTRANCE_DB / 20.0));
        float sustainFloor = (float) (reference * Math.pow(10.0, SUSTAIN_DB / 20.0));
        int sustainHops = Math.max(4, Math.round(1000f / millisPerHop));

        for (int hop = audioStart; hop < hops; hop++) {
            if (levels[hop] < entranceFloor) continue;
            int end = Math.min(hops, hop + sustainHops);
            boolean sustained = true;
            for (int probe = hop; probe < end; probe++) {
                if (levels[probe] < sustainFloor) {
                    sustained = false;
                    break;
                }
            }
            // Back off a hair so the entry lands on the last quiet beat rather than clipping the
            // downbeat that starts the song. 但退不到曲首之前 —— 那边是文件空白，不是音乐。
            if (sustained) {
                float entry = hop * millisPerHop - INTRO_BACKOFF_MILLIS;
                return Math.max(audioStart * millisPerHop, Math.max(0f, entry));
            }
        }

        // 测不到入场（整首都很轻、或者开头是念白）：至少把文件开头那段空白跳掉。原来这里返回 0，
        // 于是一首前面挂了两秒静音的歌，「跳过前奏」一秒都不跳，新歌淡入时听到的是两秒无声。
        return audioStart * millisPerHop;
    }

    /**
     * 第一个「连续 {@code needed} 格都不是静音」的位置，没有则 {@code -1}。
     *
     * <p>用的是绝对 dB 门限（见 {@link #SILENCE_FLOOR_DB}）。相对门限在这里根本没法用：它只有
     * 「比别处安静」没有「安静」，一段纯数字静音和一段极轻的铺底在它眼里一模一样。</p>
     */
    private static int firstAudibleHop(float[] levels, int needed) {
        int run = 0;
        for (int hop = 0; hop < levels.length; hop++) {
            boolean audible = repackage.be.tarsos.dsp.SilenceDetector
                    .linearToDecibel(levels[hop]) >= SILENCE_FLOOR_DB;
            if (audible) {
                run++;
                if (run >= needed) return hop - run + 1;
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** 最后一个「连续 {@code needed} 格都不是静音」的段落的末格，没有则 {@code -1}。 */
    private static int lastAudibleHop(float[] levels, int limit, int needed) {
        int run = 0;
        for (int hop = Math.min(limit, levels.length) - 1; hop >= 0; hop--) {
            boolean audible = repackage.be.tarsos.dsp.SilenceDetector
                    .linearToDecibel(levels[hop]) >= SILENCE_FLOOR_DB;
            if (audible) {
                run++;
                if (run >= needed) return hop + run - 1;
            } else {
                run = 0;
            }
        }
        return -1;
    }

    /** {@code fraction} 分位数（0..1），用一份排序副本算，不动入参。 */
    private static float percentile(float[] values, double fraction) {
        if (values.length == 0) return 0f;
        float[] sorted = new float[values.length];
        System.arraycopy(values, 0, sorted, 0, values.length);
        java.util.Arrays.sort(sorted);
        int index = (int) Math.round(fraction * (sorted.length - 1));
        if (index < 0) index = 0;
        if (index >= sorted.length) index = sorted.length - 1;
        return sorted[index];
    }

    /**
     * Finds where the outgoing track's structural outro begins: the end of the last sustained
     * high-energy section, followed by the drop into the calmer ending. That is where a DJ mixes out,
     * and it lands on a musical boundary instead of on the literal last seconds of the file.
     *
     * <p>Falls back to the last sustained quiet gap (fade-out endings have no drop to find), and then
     * to the quietest hop near the end. Returns {@code -1} only when the window carries no level at
     * all, in which case the caller keeps its positional default.</p>
     *
     * @return offset in ms from the start of the window, or {@code -1} when nothing could be measured
     */
    static float detectOutroLanding(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return -1f;
        int hop = OUTRO_HOP;
        int hops = mono.length / hop;
        if (hops < 8) return -1f;

        float[] levels = new float[hops];
        for (int index = 0; index < hops; index++) {
            levels[index] = (float) repackage.be.tarsos.dsp.SilenceDetector
                    .calculateRMS(mono, index * hop, hop);
        }

        float millisPerHop = hop * 1000f / sampleRate;
        // 文件尾巴上那段空白不算音乐。不切掉的话下面两条兜底都会掉进去：「最后一段持续安静的空隙」
        // 会命中静音本身，「最后 15 秒里最轻的一格」更是必然落在静音里，于是混出点被摆到
        // 已经没声音的地方，听起来是旧歌提前几秒就没了。
        int audibleHops = Math.max(2, Math.round(120f / millisPerHop));
        int lastAudible = lastAudibleHop(levels, hops, audibleHops);
        if (lastAudible < 0) return -1f;
        hops = lastAudible + 1;
        if (hops < 8) return -1f;

        double sum = 0.0;
        float peak = 0f;
        for (int index = 0; index < hops; index++) {
            sum += levels[index];
            if (levels[index] > peak) peak = levels[index];
        }
        float mean = (float) (sum / hops);
        if (mean < 1e-4f) return -1f;
        float highFloor = peak * .70f;
        float dropFloor = mean * .55f;
        int neededHigh = Math.max(2, Math.round(2000f / millisPerHop));

        // The END of the last sustained loud section - the final chorus or drop.
        int lastHighEnd = -1;
        int index = 0;
        while (index < hops) {
            if (levels[index] < highFloor) {
                index++;
                continue;
            }
            int start = index;
            while (index < hops && levels[index] >= highFloor) index++;
            if (index - start >= neededHigh) lastHighEnd = index;
        }
        if (lastHighEnd >= 0 && lastHighEnd < hops - 1) {
            int lookahead = Math.max(2, Math.round(300f / millisPerHop));
            for (int probe = lastHighEnd; probe < hops - lookahead; probe++) {
                boolean below = true;
                for (int inner = probe; inner < probe + lookahead; inner++) {
                    if (levels[inner] >= dropFloor) {
                        below = false;
                        break;
                    }
                }
                if (below) return probe * millisPerHop;
            }
            // The loud section runs all the way out: mix right after it instead.
            return lastHighEnd * millisPerHop;
        }

        // Fade-out ending: the last sustained quiet gap is the only breathing point there is.
        int needed = Math.max(1, Math.round(600f / millisPerHop));
        for (int probe = hops - needed; probe >= 0; probe--) {
            boolean quiet = true;
            for (int inner = probe; inner < probe + needed; inner++) {
                if (levels[inner] > dropFloor) {
                    quiet = false;
                    break;
                }
            }
            if (quiet) return probe * millisPerHop;
        }

        // Dense tail: settle for the quietest moment in the last 15 seconds.
        int scanFrom = Math.max(0, hops - Math.round(15_000f / millisPerHop));
        int quietest = scanFrom;
        for (int probe = scanFrom; probe < hops; probe++) {
            if (levels[probe] < levels[quietest]) quietest = probe;
        }
        return quietest * millisPerHop;
    }

    /**
     * Folds a tempo into the same octave as the reference, so a half/double-time detection does not
     * look like a mismatch.
     */
    static float octaveNormalise(float bpm, float reference) {
        if (bpm <= 1f || reference <= 1f) return bpm;
        float best = bpm;
        float bestDistance = Math.abs(bpm - reference);
        float[] candidates = {bpm * .5f, bpm * 2f, bpm * (2f / 3f), bpm * 1.5f};
        for (float candidate : candidates) {
            if (candidate < MIN_BPM || candidate > MAX_BPM) continue;
            float distance = Math.abs(candidate - reference);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Twelve-bin chroma (pitch class) profile of the window, normalised to unit sum.
     *
     * <p>Computed with a bank of Goertzel resonators rather than an FFT: only 36 frequencies are wanted,
     * one per semitone from C3 to B6, and a Goertzel costs one multiply-add per sample per frequency
     * with no transform, no windowing decisions and no bin-to-note interpolation. Magnitudes are summed
     * rather than powers, because in the power domain one loud bass note buries the entire chord above
     * it and the profile stops describing the harmony.</p>
     *
     * @return the profile, or {@code null} when the window is too short or carries no tonal energy
     */
    static float[] chroma(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0) return null;
        int block = Math.min(CHROMA_BLOCK, mono.length);
        if (block < 2_048) return null;
        int blocks = mono.length / block;
        if (blocks < 1) return null;

        int notes = CHROMA_HIGHEST_MIDI - CHROMA_LOWEST_MIDI + 1;
        double[] coefficients = new double[notes];
        double nyquistGuard = sampleRate * .45;
        for (int note = 0; note < notes; note++) {
            double frequency = 440.0 * Math.pow(2.0, (CHROMA_LOWEST_MIDI + note - 69) / 12.0);
            coefficients[note] = frequency >= nyquistGuard
                    ? Double.NaN
                    : 2.0 * Math.cos(2.0 * Math.PI * frequency / sampleRate);
        }

        float[] chroma = new float[12];
        double total = 0.0;
        for (int index = 0; index < blocks; index++) {
            int base = index * block;
            for (int note = 0; note < notes; note++) {
                double coefficient = coefficients[note];
                if (Double.isNaN(coefficient)) continue;
                double s1 = 0.0;
                double s2 = 0.0;
                for (int n = 0; n < block; n++) {
                    double s = mono[base + n] + coefficient * s1 - s2;
                    s2 = s1;
                    s1 = s;
                }
                double power = s2 * s2 + s1 * s1 - coefficient * s1 * s2;
                if (Double.isNaN(power) || power <= 0.0) continue;
                float magnitude = (float) (Math.sqrt(power) / block);
                chroma[(CHROMA_LOWEST_MIDI + note) % 12] += magnitude;
                total += magnitude;
            }
        }
        if (total < 1e-9) return null;
        for (int bin = 0; bin < 12; bin++) {
            chroma[bin] = (float) (chroma[bin] / total);
        }
        return chroma;
    }

    /**
     * Estimates the key of the window by correlating its chroma profile against the 24 rotations of the
     * Krumhansl-Kessler major and minor templates.
     *
     * <p>The confidence deliberately needs two things at once: a high correlation with the winner
     * <em>and</em> a gap to the runner-up. A window that fits C major and G major almost equally well has
     * not identified a key, it has identified a diatonic set, and transposing a track on that basis is
     * how a "harmonic" mix ends up a semitone wrong.</p>
     *
     * @return the estimate, or {@code null} when no key could be measured
     */
    static KeyEstimate analyzeKey(float[] mono, int sampleRate) {
        float[] chroma = chroma(mono, sampleRate);
        if (chroma == null) return null;

        double best = -2.0;
        double runnerUp = -2.0;
        int bestTonic = 0;
        boolean bestMajor = true;
        for (int tonic = 0; tonic < 12; tonic++) {
            for (int mode = 0; mode < 2; mode++) {
                double score = correlateProfile(chroma, mode == 0 ? MAJOR_PROFILE : MINOR_PROFILE, tonic);
                if (score > best) {
                    runnerUp = best;
                    best = score;
                    bestTonic = tonic;
                    bestMajor = mode == 0;
                } else if (score > runnerUp) {
                    runnerUp = score;
                }
            }
        }
        if (best <= 0.0) return null;

        double margin = Math.max(0.0, best - runnerUp);
        double confidence = Math.min(1.0, best / .55) * Math.min(1.0, margin / .08);
        return new KeyEstimate(bestTonic, bestMajor, (float) confidence);
    }

    /** Pearson correlation of the chroma against a template rotated so its tonic sits on {@code rotation}. */
    private static double correlateProfile(float[] chroma, double[] profile, int rotation) {
        double meanChroma = 0.0;
        double meanProfile = 0.0;
        for (int bin = 0; bin < 12; bin++) {
            meanChroma += chroma[bin];
            meanProfile += profile[bin];
        }
        meanChroma /= 12.0;
        meanProfile /= 12.0;

        double covariance = 0.0;
        double varianceChroma = 0.0;
        double varianceProfile = 0.0;
        for (int degree = 0; degree < 12; degree++) {
            double a = chroma[(degree + rotation) % 12] - meanChroma;
            double b = profile[degree] - meanProfile;
            covariance += a * b;
            varianceChroma += a * a;
            varianceProfile += b * b;
        }
        double denominator = Math.sqrt(varianceChroma * varianceProfile);
        return denominator > 1e-12 ? covariance / denominator : 0.0;
    }

    /**
     * Harmonic penalty in [0,1] for hearing {@code incoming} on top of {@code outgoing} after the
     * outgoing track has been transposed by {@code semitoneShift}. 0 is "these are the same key",
     * 1 is "a semitone apart", which is the worst thing two simultaneous tracks can be.
     *
     * <p>An unknown key on either side returns a neutral .5 rather than 0: "no information" must not be
     * mistaken for "measured to be compatible", or every unanalysable pair would look like a perfect
     * harmonic match and earn itself the longest possible overlap.</p>
     */
    static float harmonicPenalty(KeyEstimate outgoing, KeyEstimate incoming, int semitoneShift) {
        if (outgoing == null || incoming == null) return .5f;
        int from = ((relativeMajorTonic(outgoing) + semitoneShift) % 12 + 12) % 12;
        int to = relativeMajorTonic(incoming);
        int interval = ((to - from) % 12 + 12) % 12;
        double penalty = WHEEL_PENALTY[interval];
        if (outgoing.major != incoming.major) penalty += MODE_MISMATCH_PENALTY;
        return (float) Math.min(1.0, penalty);
    }

    /** The wheel slot: a minor key sits in the same slot as the major a minor third above it. */
    private static int relativeMajorTonic(KeyEstimate key) {
        return key.major ? key.tonic : (key.tonic + 3) % 12;
    }

    /**
     * The whole-semitone transposition of {@code outgoing} inside ±{@code maxShift} that agrees best
     * with {@code incoming}.
     *
     * <p>Ties go to 0: bending a deck that is on its way out is only worth doing when it buys a real
     * improvement. The caller also has to check that the improvement is worth the pitch move at all -
     * this method answers "which shift is best", not "should there be one".</p>
     */
    static int bestSemitoneShift(KeyEstimate outgoing, KeyEstimate incoming, int maxShift) {
        if (outgoing == null || incoming == null) return 0;
        int limit = Math.max(0, Math.min(6, maxShift));
        int best = 0;
        float bestPenalty = harmonicPenalty(outgoing, incoming, 0);
        for (int shift = -limit; shift <= limit; shift++) {
            if (shift == 0) continue;
            float penalty = harmonicPenalty(outgoing, incoming, shift);
            // Strictly better, and the closer shift wins a tie between +1 and -1.
            if (penalty < bestPenalty - 1e-4f
                    || (Math.abs(penalty - bestPenalty) <= 1e-4f && Math.abs(shift) < Math.abs(best))) {
                bestPenalty = penalty;
                best = shift;
            }
        }
        return best;
    }
}
