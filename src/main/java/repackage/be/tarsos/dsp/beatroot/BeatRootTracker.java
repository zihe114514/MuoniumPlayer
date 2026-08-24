package repackage.be.tarsos.dsp.beatroot;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * BeatRoot 的多智能体节拍跟踪。
 *
 * <p>移植自 TarsosDSP 的 {@code be.tarsos.dsp.beatroot} 包（Simon Dixon 的 BeatRoot，
 * 由 Joren Six 移入 TarsosDSP），算法出自 Dixon, "Automatic Extraction of Tempo and Beat
 * from Expressive Performances", JNMR 2001。</p>
 *
 * <h3>它比自相关强在哪里</h3>
 * <p>本项目原来测速用的是 onset 包络的自相关：找一个让包络和自己最像的滞后量。那个做法只回答
 * 「平均隔多久有一下」，不回答「每一下具体在哪」—— 于是相位只能靠「把脉冲串套上去，哪个偏移
 * 累加的 onset 最多」倒推，而节奏一旦有推拉（现场鼓手、渐快、段落之间掉一拍），自相关会把它们
 * 平均成一个既不对头也不对尾的数。</p>
 * <p>BeatRoot 反过来：先由 IOI（onset 间隔）聚类给出十个左右的速度假设，每个假设派一个
 * agent 从头往后逐个 onset 地「认拍」，认得准就加分、认不上就分裂出一个新 agent 走另一条路，
 * 最后取总分最高的那个 agent 的<b>整条拍点列表</b>。所以它给出的是真实拍点时刻，速度只是这条
 * 列表的斜率 —— 相位、速度、以及「这条网格有多少拍是真的踩在 onset 上」这三件事都能直接读出来。</p>
 *
 * <h3>与上游的刻意不同</h3>
 * <ol>
 *   <li><b>并成一个文件、砍掉 MIDI 与文件 I/O。</b>上游是 6 个类，其中 {@code Event} 带 11 个
 *       MIDI 字段、{@code EventList} 大半是 .match/.mid 读写。本项目只从 onset 包络喂时间与
 *       salience 两个数进去，那些都用不上。</li>
 *   <li><b>可变静态字段全部变成常量或实例字段。</b>上游 {@code Agent.innerMargin /
 *       correctionFactor / expiryTime / decayFactor} 是 static，且每次 {@code init()} 都重新赋一遍
 *       同样的值；{@code AgentList.count / thresholdBI / thresholdBT} 也是 static。本项目的分析跑在
 *       后台线程池上（最多两条并发），两首歌同时分析会互相写这些字段。既然它们从来没被真正改过，
 *       这里直接写成 {@code static final} 常量。{@code idCounter} 与 {@code count} 只服务于调试打印，
 *       连带删掉。</li>
 *   <li><b>{@code decayFactor} 恒为 0，实时打分那条分支是死代码，删掉。</b>上游注释就写了
 *       "For scoring Agents in a (non-existent) real-time version (otherwise not used)"。</li>
 *   <li><b>拍点带 {@code interpolated} 标记。</b>上游 {@code fillBeats} 插进去的补拍和真拍长得
 *       一模一样（都是 pitch 56 / velocity 64），事后分不出来。而「多少拍是真的踩在 onset 上」
 *       正是本项目要的置信度，所以补拍显式打标。</li>
 *   <li><b>多算一个最小二乘。</b>对所有<b>真拍</b>做 时刻~拍号 的线性回归，斜率就是节拍周期、
 *       截距就是网格原点、斜率的标准误就是这次测速自己的精度。上游只有 agent 末态的
 *       {@code beatInterval}（一个被逐拍微调过的值，末尾那几拍的权重过大）。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：{@link #track} 没有任何共享可变状态，可以并发调用。</p>
 */
public final class BeatRootTracker {

    // ---- Peaks（上游 Peaks 的默认值） ----
    /** 相对门限时，参考均值往前取几个 width。 */
    private static final int PEAK_PRE = 3;
    /** …往后取几个 width。 */
    private static final int PEAK_POST = 1;

    // ---- 上游 BeatRootSpectralFluxOnsetDetector.processingFinished() 里的取峰参数 ----
    /** 峰值必须比局部均值高出这么多（包络已 z-score 归一化，所以单位是标准差）。 */
    private static final double ONSET_THRESHOLD = 0.35;
    /** 局部均值的指数衰减率：越大越「记仇」，前一个大峰会压制紧随其后的小峰。 */
    private static final double ONSET_DECAY_RATE = 0.84;
    /** 两个 onset 之间至少隔这么久（秒）。 */
    private static final double ONSET_MIN_SEPARATION = 0.06;
    /** onset 时刻的量化粒度（秒）。上游 {@code BeatRootOnsetEventHandler} 就是取到 10ms。 */
    private static final double ONSET_TIME_QUANTUM = 0.01;

    // ---- Induction（上游 Induction 的默认值） ----
    private static final double CLUSTER_WIDTH = 0.025;
    private static final double MIN_IOI = 0.070;
    private static final double MAX_IOI = 2.500;
    /** 最短拍间隔 = 最快速度，0.3s = 200 BPM。 */
    private static final double MIN_IBI = 0.3;
    /** 最长拍间隔 = 最慢速度，1.0s = 60 BPM。 */
    private static final double MAX_IBI = 1.0;
    private static final int TOP_N = 10;

    // ---- Agent（上游 Agent 的默认值） ----
    private static final double POST_MARGIN_FACTOR = 0.3;
    private static final double PRE_MARGIN_FACTOR = 0.15;
    private static final double INNER_MARGIN = 0.040;
    private static final double MAX_CHANGE = 0.2;
    private static final double CONF_FACTOR = 0.5;
    private static final double CORRECTION_FACTOR = 50.0;
    private static final double EXPIRY_TIME = 10.0;

    // ---- AgentList（上游 AgentList 的默认值） ----
    private static final double THRESHOLD_BI = 0.02;
    private static final double THRESHOLD_BT = 0.04;
    /** 上游 {@code useAverageSalience}：false 表示总分用累加，偏好更快的速度层级。 */
    private static final boolean USE_AVERAGE_SALIENCE = false;

    private BeatRootTracker() {
    }

    /** 跟踪结果。{@code null} 表示这段素材上没能立起一条网格。 */
    public static final class Result {
        /** 拍点时刻，秒，升序，含补拍。 */
        public final double[] beatTimes;
        /** 其中真的踩在 onset 上的拍数。 */
        public final int matchedBeats;
        /** 检出的 onset 总数。 */
        public final int onsetCount;
        /** 最小二乘拟合出的节拍周期，秒。 */
        public final double beatIntervalSeconds;
        /** 最小二乘拟合出的网格原点（第 0 拍的时刻），秒。可能为负。 */
        public final double gridOriginSeconds;
        /** 节拍周期的相对标准误，例如 {@code .004} 表示「这个速度准到 0.4%」。 */
        public final double tempoTolerance;
        /** 拍点相对拟合网格的 RMS 偏差，秒。 */
        public final double residualSeconds;
        /** 获胜 agent 的 phaseScore（累加的加权 salience）。 */
        public final double phaseScore;

        Result(double[] beatTimes, int matchedBeats, int onsetCount, double beatIntervalSeconds,
               double gridOriginSeconds, double tempoTolerance, double residualSeconds,
               double phaseScore) {
            this.beatTimes = beatTimes;
            this.matchedBeats = matchedBeats;
            this.onsetCount = onsetCount;
            this.beatIntervalSeconds = beatIntervalSeconds;
            this.gridOriginSeconds = gridOriginSeconds;
            this.tempoTolerance = tempoTolerance;
            this.residualSeconds = residualSeconds;
            this.phaseScore = phaseScore;
        }

        public double bpm() {
            return beatIntervalSeconds > 1e-6 ? 60.0 / beatIntervalSeconds : 0.0;
        }

        /** 真拍占全部拍的比例：网格有多少是被 onset 支持的，而不是插值补出来的。 */
        public double matchedFraction() {
            return beatTimes.length > 0 ? matchedBeats / (double) beatTimes.length : 0.0;
        }
    }

    /**
     * 在一条 onset 强度包络上跑完整条 BeatRoot 流水线。
     *
     * @param onsetFunction  onset 强度，等间隔采样（谱通量最合适）。<b>会被原地归一化</b>
     * @param hopTimeSeconds 相邻两个包络样点相隔多少秒
     * @return 结果，或 {@code null}（包络太短、没有 onset、或所有 agent 都死了）
     */
    public static Result track(double[] onsetFunction, double hopTimeSeconds) {
        if (onsetFunction == null || onsetFunction.length < 32) return null;
        if (hopTimeSeconds <= 0.0 || Double.isNaN(hopTimeSeconds)) return null;

        normalise(onsetFunction);
        // salience 用归一化后的值减归一化后的最小值 —— 上游正是先 normalise，
        // 再 findOnsets，再在 findOnsets 里取 Peaks.min，顺序一致。
        double normalisedMin = min(onsetFunction);
        if (Double.isNaN(normalisedMin) || Double.isInfinite(normalisedMin)) return null;

        int width = (int) Math.round(ONSET_MIN_SEPARATION / hopTimeSeconds);
        if (width < 1) width = 1;
        List<Integer> peaks = findPeaks(onsetFunction, width, ONSET_THRESHOLD, ONSET_DECAY_RATE);
        if (peaks.size() < 8) return null;

        EventList onsets = new EventList();
        for (int index = 0; index < peaks.size(); index++) {
            int hop = peaks.get(index).intValue();
            double time = Math.round(hop * hopTimeSeconds / ONSET_TIME_QUANTUM) * ONSET_TIME_QUANTUM;
            Event event = new Event(time);
            event.salience = onsetFunction[hop] - normalisedMin;
            onsets.add(event);
        }

        AgentList agents = beatInduction(onsets);
        if (agents.ag == null) return null;
        agents.beatTrack(onsets);
        Agent best = agents.bestAgent();
        if (best == null || best.events.list.size() < 4) return null;
        best.fillBeats();

        // 一次性摊平成数组：events.list 是 LinkedList，下面要按下标访问两遍。
        int total = best.events.list.size();
        double[] times = new double[total];
        boolean[] interpolated = new boolean[total];
        int matched = 0;
        int index = 0;
        for (Event event : best.events.list) {
            times[index] = event.time;
            interpolated[index] = event.interpolated;
            if (!event.interpolated) matched++;
            index++;
        }
        if (matched < 4) return null;

        // 真拍的 时刻 ~ 拍号 线性回归。拍号用的是在完整拍表里的下标，所以中间掉的拍
        // 不会把后面的拍号挤到前面去。
        double sumIndex = 0.0;
        double sumIndexSquared = 0.0;
        double sumTime = 0.0;
        double sumProduct = 0.0;
        int count = 0;
        for (int beat = 0; beat < total; beat++) {
            if (interpolated[beat]) continue;
            double x = beat;
            double y = times[beat];
            sumIndex += x;
            sumIndexSquared += x * x;
            sumTime += y;
            sumProduct += x * y;
            count++;
        }
        double denominator = count * sumIndexSquared - sumIndex * sumIndex;
        if (count < 3 || Math.abs(denominator) < 1e-9) return null;
        double slope = (count * sumProduct - sumIndex * sumTime) / denominator;
        double intercept = (sumTime - slope * sumIndex) / count;
        if (slope <= 1e-6 || Double.isNaN(slope)) return null;

        double squaredError = 0.0;
        for (int beat = 0; beat < total; beat++) {
            if (interpolated[beat]) continue;
            double error = times[beat] - (intercept + slope * beat);
            squaredError += error * error;
        }
        double residual = Math.sqrt(squaredError / count);
        // 斜率的标准误。count == 3 时自由度只剩 1，估计很糙但仍然是个上界。
        double variance = count > 2 ? squaredError / (count - 2) : squaredError;
        double slopeError = Math.sqrt(variance * count / denominator);
        double tolerance = slope > 1e-6 ? slopeError / slope : 1.0;

        return new Result(times, matched, onsets.list.size(), slope, intercept,
                tolerance, residual, best.phaseScore);
    }

    // ================================================================== Peaks

    /** 上游 {@code Peaks.min}。 */
    private static double min(double[] data) {
        double smallest = data[0];
        for (int index = 1; index < data.length; index++) {
            if (data[index] < smallest) smallest = data[index];
        }
        return smallest;
    }

    /** 上游 {@code Peaks.normalise}：原地 z-score。 */
    private static void normalise(double[] data) {
        double sum = 0.0;
        double sumSquared = 0.0;
        for (int index = 0; index < data.length; index++) {
            sum += data[index];
            sumSquared += data[index] * data[index];
        }
        double mean = sum / data.length;
        double deviation = Math.sqrt((sumSquared - sum * mean) / data.length);
        // 全等于均值时 sd 为 0，上游用 1 顶上以免除零。
        if (deviation == 0.0 || Double.isNaN(deviation)) deviation = 1.0;
        for (int index = 0; index < data.length; index++) {
            data[index] = (data[index] - mean) / deviation;
        }
    }

    /**
     * 上游 {@code Peaks.findPeaks(data, width, threshold, decayRate, true)}。
     *
     * <p>一个位置要成为峰，必须同时满足：是 ±{@code width} 内的最大值；不低于带保持的指数
     * 衰减均值；并且比 {@code [-PRE_PRE·width, +POST·width]} 的局部平均高出 {@code threshold}。</p>
     */
    private static List<Integer> findPeaks(double[] data, int width, double threshold,
                                           double decayRate) {
        List<Integer> peaks = new LinkedList<Integer>();
        double average = data[0];
        int end = data.length;
        for (int mid = 0; mid < end; mid++) {
            average = decayRate * average + (1.0 - decayRate) * data[mid];
            if (average < data[mid]) average = data[mid];
            int index = mid - width;
            if (index < 0) index = 0;
            int stop = mid + width + 1;
            if (stop > data.length) stop = data.length;
            int peak = index;
            for (index++; index < stop; index++) {
                if (data[index] > data[peak]) peak = index;
            }
            if (peak != mid) continue;
            if (overThreshold(data, peak, width, threshold, average)) {
                peaks.add(Integer.valueOf(peak));
            }
        }
        return peaks;
    }

    /** 上游 {@code Peaks.overThreshold}，{@code isRelative} 恒为 true 的那一支。 */
    private static boolean overThreshold(double[] data, int index, int width, double threshold,
                                         double average) {
        if (data[index] < average) return false;
        int start = index - PEAK_PRE * width;
        if (start < 0) start = 0;
        int stop = index + PEAK_POST * width;
        if (stop > data.length) stop = data.length;
        double sum = 0.0;
        int count = stop - start;
        if (count <= 0) return false;
        for (int scan = start; scan < stop; scan++) {
            sum += data[scan];
        }
        return data[index] > sum / count + threshold;
    }

    // ============================================================== Induction

    /**
     * 上游 {@code Induction.beatInduction}：把 IOI 聚成簇，按簇内数量与簇之间的整数倍关系打分，
     * 取前 {@link #TOP_N} 个作为速度假设，每个假设生成一个还没有拍点的 agent。
     */
    private static AgentList beatInduction(EventList events) {
        int maxClusterCount = (int) Math.ceil((MAX_IOI - MIN_IOI) / CLUSTER_WIDTH);
        double[] clusterMean = new double[maxClusterCount];
        int[] clusterSize = new int[maxClusterCount];
        int[] clusterScore = new int[maxClusterCount];
        int intervals = 0;

        List<Event> all = events.list;
        for (int i = 0; i < all.size(); i++) {
            double from = all.get(i).time;
            for (int j = i + 1; j < all.size(); j++) {
                double ioi = all.get(j).time - from;
                if (ioi < MIN_IOI) continue;
                if (ioi > MAX_IOI) break;
                int bin;
                for (bin = 0; bin < intervals; bin++) {
                    if (Math.abs(clusterMean[bin] - ioi) < CLUSTER_WIDTH) {
                        if (bin < intervals - 1
                                && Math.abs(clusterMean[bin + 1] - ioi)
                                < Math.abs(clusterMean[bin] - ioi)) {
                            bin++;
                        }
                        clusterMean[bin] = (clusterMean[bin] * clusterSize[bin] + ioi)
                                / (clusterSize[bin] + 1);
                        clusterSize[bin]++;
                        break;
                    }
                }
                if (bin == intervals) {
                    // 没有合适的簇，插一个新的，保持按 mean 升序。
                    if (intervals == maxClusterCount) continue;
                    intervals++;
                    for (; bin > 0 && clusterMean[bin - 1] > ioi; bin--) {
                        clusterMean[bin] = clusterMean[bin - 1];
                        clusterSize[bin] = clusterSize[bin - 1];
                    }
                    clusterMean[bin] = ioi;
                    clusterSize[bin] = 1;
                }
            }
        }

        // 合并靠得太近的簇。
        for (int bin = 0; bin < intervals; bin++) {
            for (int other = bin + 1; other < intervals; other++) {
                if (Math.abs(clusterMean[bin] - clusterMean[other]) < CLUSTER_WIDTH) {
                    clusterMean[bin] = (clusterMean[bin] * clusterSize[bin]
                            + clusterMean[other] * clusterSize[other])
                            / (clusterSize[bin] + clusterSize[other]);
                    clusterSize[bin] = clusterSize[bin] + clusterSize[other];
                    --intervals;
                    for (int shift = other + 1; shift <= intervals; shift++) {
                        clusterMean[shift - 1] = clusterMean[shift];
                        clusterSize[shift - 1] = clusterSize[shift];
                    }
                }
            }
        }
        if (intervals == 0) return new AgentList();

        for (int bin = 0; bin < intervals; bin++) {
            clusterScore[bin] = 10 * clusterSize[bin];
        }
        int[] bestn = new int[TOP_N];
        bestn[0] = 0;
        int bestCount = 1;
        for (int bin = 0; bin < intervals; bin++) {
            for (int rank = 0; rank <= bestCount; rank++) {
                if (rank < TOP_N
                        && (rank == bestCount || clusterScore[bin] > clusterScore[bestn[rank]])) {
                    if (bestCount < TOP_N) bestCount++;
                    for (int shift = bestCount - 1; shift > rank; shift--) {
                        bestn[shift] = bestn[shift - 1];
                    }
                    bestn[rank] = bin;
                    break;
                }
            }
        }

        // 互为整数倍的簇彼此加分：一个真的节拍会同时在 1/2/3/4 倍处留下簇。
        for (int bin = 0; bin < intervals; bin++) {
            for (int other = bin + 1; other < intervals; other++) {
                double ratio = clusterMean[bin] / clusterMean[other];
                boolean submultiple = ratio < 1.0;
                int degree = submultiple ? (int) Math.round(1.0 / ratio) : (int) Math.round(ratio);
                if (degree < 2 || degree > 8) continue;
                double error = submultiple
                        ? Math.abs(clusterMean[bin] * degree - clusterMean[other])
                        : Math.abs(clusterMean[bin] - clusterMean[other] * degree);
                if (error < (submultiple ? CLUSTER_WIDTH : CLUSTER_WIDTH * degree)) {
                    degree = degree >= 5 ? 1 : 6 - degree;
                    clusterScore[bin] += degree * clusterSize[other];
                    clusterScore[other] += degree * clusterSize[bin];
                }
            }
        }

        AgentList agents = new AgentList();
        for (int rank = 0; rank < bestCount; rank++) {
            int bin = bestn[rank];
            double weightedSum = clusterMean[bin] * clusterScore[bin];
            int weight = clusterScore[bin];
            for (int other = 0; other < intervals; other++) {
                if (other == bin) continue;
                double ratio = clusterMean[bin] / clusterMean[other];
                if (ratio < 1.0) {
                    int degree = (int) Math.round(1.0 / ratio);
                    if (degree >= 2 && degree <= 8
                            && Math.abs(clusterMean[bin] * degree - clusterMean[other])
                            < CLUSTER_WIDTH) {
                        weightedSum += clusterMean[other] / degree * clusterScore[other];
                        weight += clusterScore[other];
                    }
                } else {
                    int degree = (int) Math.round(ratio);
                    if (degree >= 2 && degree <= 8
                            && Math.abs(clusterMean[bin] - degree * clusterMean[other])
                            < CLUSTER_WIDTH * degree) {
                        weightedSum += clusterMean[other] * degree * clusterScore[other];
                        weight += clusterScore[other];
                    }
                }
            }
            double beat = weightedSum / weight;
            while (beat < MIN_IBI) beat *= 2.0;
            while (beat > MAX_IBI) beat /= 2.0;
            if (beat >= MIN_IBI) agents.add(new Agent(beat));
        }
        return agents;
    }

    // ================================================================= Event

    /** 一个 onset 或一个拍点。上游 {@code Event} 去掉 MIDI 字段之后剩下的部分。 */
    private static final class Event {
        final double time;
        double salience;
        /** {@link Agent#fillBeats} 插进来的补拍。上游没有这个标记，见类注释。 */
        boolean interpolated;

        Event(double time) {
            this.time = time;
        }
    }

    /** 上游 {@code EventList} 的核心：一条按时间升序的链表。 */
    private static final class EventList {
        final LinkedList<Event> list;

        EventList() {
            this.list = new LinkedList<Event>();
        }

        EventList(EventList other) {
            this.list = new LinkedList<Event>(other.list);
        }

        void add(Event event) {
            list.add(event);
        }

        Event last() {
            return list.getLast();
        }

        ListIterator<Event> listIterator() {
            return list.listIterator();
        }
    }

    // ================================================================= Agent

    /** 上游 {@code Agent}：一个速度假设 + 它认下的拍点 + 它的得分。 */
    private static final class Agent {
        final double preMargin;
        final double postMargin;
        double phaseScore;
        double tempoScore;
        double topScoreTime;
        int beatCount;
        double beatInterval;
        final double initialBeatInterval;
        double beatTime;
        EventList events;

        Agent(double ibi) {
            this.beatInterval = ibi;
            this.initialBeatInterval = ibi;
            this.postMargin = ibi * POST_MARGIN_FACTOR;
            this.preMargin = ibi * PRE_MARGIN_FACTOR;
            this.phaseScore = 0.0;
            this.tempoScore = 0.0;
            this.topScoreTime = 0.0;
            this.beatCount = 0;
            this.beatTime = -1.0;
            this.events = new EventList();
        }

        /** 上游的拷贝构造：分裂出一条「不认这一拍」的分支。 */
        Agent(Agent clone) {
            this.phaseScore = clone.phaseScore;
            this.tempoScore = clone.tempoScore;
            this.topScoreTime = clone.topScoreTime;
            this.beatCount = clone.beatCount;
            this.beatInterval = clone.beatInterval;
            this.initialBeatInterval = clone.initialBeatInterval;
            this.beatTime = clone.beatTime;
            this.events = new EventList(clone.events);
            this.postMargin = clone.postMargin;
            this.preMargin = clone.preMargin;
        }

        void accept(Event event, double error, int beats) {
            beatTime = event.time;
            events.add(event);
            // 速度微调，但不允许偏离初始假设超过 MAX_CHANGE。
            if (Math.abs(initialBeatInterval - beatInterval - error / CORRECTION_FACTOR)
                    < MAX_CHANGE * initialBeatInterval) {
                beatInterval += error / CORRECTION_FACTOR;
            }
            beatCount += beats;
            double confidence = 1.0 - CONF_FACTOR * error / (error > 0 ? postMargin : -preMargin);
            phaseScore += confidence * event.salience;
        }

        /**
         * 上游 {@code considerAsBeat}：这个 onset 能不能算作本 agent 的下一拍。
         *
         * <p>落在内窗里直接认；落在外窗但出了内窗，认下的同时分裂出一个不认它的 agent
         * （避免相位被一个偏得多的 onset 一把拽走）；离上一次认拍超过
         * {@link #EXPIRY_TIME} 就把自己标记为待删。</p>
         */
        boolean considerAsBeat(Event event, AgentList agents) {
            if (beatTime < 0.0) {
                accept(event, 0.0, 1);
                return true;
            }
            if (event.time - events.last().time > EXPIRY_TIME) {
                phaseScore = -1.0;
                return false;
            }
            double beats = Math.round((event.time - beatTime) / beatInterval);
            double error = event.time - beatTime - beats * beatInterval;
            if (beats > 0 && -preMargin <= error && error <= postMargin) {
                if (Math.abs(error) > INNER_MARGIN) agents.add(new Agent(this));
                accept(event, error, (int) beats);
                return true;
            }
            return false;
        }

        /** 上游 {@code fillBeats(-1)}：把认拍之间掉掉的拍插值补齐。 */
        void fillBeats() {
            double previous = 0.0;
            ListIterator<Event> cursor = events.listIterator();
            if (cursor.hasNext()) {
                previous = cursor.next().time;
                cursor.previous();
            }
            for (; cursor.hasNext(); cursor.next()) {
                double next = cursor.next().time;
                cursor.previous();
                // -0.01 是上游的「宁慢勿快」偏置：正好卡在 n.5 时取小的那个。
                double beats = Math.round((next - previous) / beatInterval - 0.01);
                double interval = (next - previous) / beats;
                for (; beats > 1.5; beats--) {
                    previous += interval;
                    Event filler = new Event(previous);
                    filler.interpolated = true;
                    cursor.add(filler);
                }
                previous = next;
            }
        }
    }

    // ============================================================= AgentList

    /**
     * 上游 {@code AgentList}：以 {@code ag == null} 结尾的手写单链表。
     *
     * <p>这里刻意<b>没有</b>换成 {@code ArrayList}。{@code beatTrack} 在遍历的同时往表里插新
     * agent，并且靠「插入点恰好在当前节点之前」这一点来判断要不要跳过一格；换成随机访问容器
     * 之后插入位置与遍历顺序都会变，跟踪结果也就跟着变。</p>
     */
    private static final class AgentList {
        Agent ag;
        AgentList next;

        AgentList() {
            this(null, null);
        }

        AgentList(Agent agent, AgentList tail) {
            this.ag = agent;
            this.next = tail;
            if (this.next == null && this.ag != null) {
                this.next = new AgentList();
            }
        }

        /** 按 beatInterval 升序插入。 */
        void add(Agent newAgent) {
            if (newAgent == null) return;
            AgentList node;
            for (node = this; node.ag != null; node = node.next) {
                if (newAgent.beatInterval <= node.ag.beatInterval) {
                    node.next = new AgentList(node.ag, node.next);
                    node.ag = newAgent;
                    return;
                }
            }
            node.next = new AgentList();
            node.ag = newAgent;
        }

        /** 冒泡排序：表基本有序，所以这样最省。 */
        void sort() {
            boolean sorted = false;
            while (!sorted) {
                sorted = true;
                for (AgentList node = this; node.ag != null; node = node.next) {
                    if (node.next.ag != null
                            && node.ag.beatInterval > node.next.ag.beatInterval) {
                        Agent temporary = node.ag;
                        node.ag = node.next.ag;
                        node.next.ag = temporary;
                        sorted = false;
                    }
                }
            }
        }

        void remove(AgentList node) {
            node.ag = node.next.ag;
            node.next = node.next.next;
        }

        /** 速度与相位都在容差内的两个 agent 只留分高的那个。 */
        void removeDuplicates() {
            sort();
            for (AgentList node = this; node.ag != null; node = node.next) {
                if (node.ag.phaseScore < 0.0) continue;
                for (AgentList other = node.next; other.ag != null; other = other.next) {
                    if (other.ag.beatInterval - node.ag.beatInterval > THRESHOLD_BI) break;
                    if (Math.abs(node.ag.beatTime - other.ag.beatTime) > THRESHOLD_BT) continue;
                    if (node.ag.phaseScore < other.ag.phaseScore) {
                        node.ag.phaseScore = -1.0;
                        if (other.ag.topScoreTime < node.ag.topScoreTime) {
                            other.ag.topScoreTime = node.ag.topScoreTime;
                        }
                        break;
                    } else {
                        other.ag.phaseScore = -1.0;
                        if (node.ag.topScoreTime < other.ag.topScoreTime) {
                            node.ag.topScoreTime = other.ag.topScoreTime;
                        }
                    }
                }
            }
            for (AgentList node = this; node.ag != null; ) {
                if (node.ag.phaseScore < 0.0) {
                    remove(node);
                } else {
                    node = node.next;
                }
            }
        }

        /** 上游 {@code beatTrack(el, -1)}。 */
        void beatTrack(EventList onsets) {
            boolean phaseGiven = ag != null && ag.beatTime >= 0.0;
            ListIterator<Event> cursor = onsets.listIterator();
            while (cursor.hasNext()) {
                Event event = cursor.next();
                boolean created = phaseGiven;
                double previousInterval = -1.0;
                for (AgentList node = this; node.ag != null; node = node.next) {
                    Agent current = node.ag;
                    if (current.beatInterval != previousInterval) {
                        // 同一个速度假设下还没有人认下这个 onset，而且还在开头 5 秒内：
                        // 再开一条相位不同的分支。开头之后不再开，否则 agent 数会爆。
                        if (previousInterval >= 0.0 && !created && event.time < 5.0) {
                            Agent fresh = new Agent(previousInterval);
                            fresh.considerAsBeat(event, this);
                            add(fresh);
                        }
                        previousInterval = current.beatInterval;
                        created = phaseGiven;
                    }
                    if (current.considerAsBeat(event, this)) created = true;
                    // 刚才在当前位置插了一个新 agent，跳过它。
                    if (current != node.ag) node = node.next;
                }
                removeDuplicates();
            }
        }

        Agent bestAgent() {
            double best = -1.0;
            Agent winner = null;
            for (AgentList node = this; node.ag != null; node = node.next) {
                if (node.ag.events.list.isEmpty()) continue;
                double score = (node.ag.phaseScore + node.ag.tempoScore)
                        / (USE_AVERAGE_SALIENCE ? (double) node.ag.beatCount : 1.0);
                if (score > best) {
                    winner = node.ag;
                    best = score;
                }
            }
            return winner;
        }
    }
}
