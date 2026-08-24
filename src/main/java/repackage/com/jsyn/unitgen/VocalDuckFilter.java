package repackage.com.jsyn.unitgen;

import repackage.com.jsyn.ports.UnitInputPort;

/**
 * The automix deck filter: {@link FilterLowShelf} plus a band-limited centre canceller that pulls the
 * lead vocal out of a track while it fades in.
 *
 * <h3>Why a second processor and not another volume curve</h3>
 * <p>The bass swap already stops two kick drums from stacking, but the artefact a listener can actually
 * name is two lead vocals at once. That one cannot be shelved away: a voice occupies the same band as
 * the guitars and the snare, so any EQ that removes it removes the arrangement with it. What does
 * separate a lead vocal from most of the rest of a mix is <em>where</em> it is - dead centre, identical in
 * both channels. Subtracting the (band-limited) mid signal therefore takes the vocal out while leaving
 * anything panned wide almost untouched, which is exactly the classic karaoke trick, and it is enough for
 * the arriving track to enter as an instrumental bed and only become a song once the outgoing vocal is
 * gone.</p>
 *
 * <h3>How the side channel gets here</h3>
 * <p>{@code Effect} runs two independent filter instances, one per channel, and each only sees its own
 * signal - so a centre estimate is impossible without the other channel. The extra {@link #side} input is
 * how it arrives: {@code UnitGenerator.pullData} walks every {@code ConnectableInput} port before calling
 * {@code generate}, so declaring the port is all that is needed for the framework to fetch it, and
 * because a unit generator only produces one block per frame count the shared source is not generated
 * twice.</p>
 *
 * <h3>Three deliberate properties</h3>
 * <ul>
 *   <li><b>Bit-exact bypass.</b> At depth 0 nothing is added on top of the shelf's own output, so an
 *       always-attached chain still cannot colour ordinary playback.</li>
 *   <li><b>Smoothed depth.</b> The driver ticks every 10 ms; stepping the cancellation would be heard as
 *       the vocal being switched, so the depth follows its target with a one pole slew.</li>
 *   <li><b>Mono fallback.</b> A mono file is panned to two identical channels, where the centre <em>is</em>
 *       the signal and a full subtraction would silence the whole band. {@link #setMonoFallback(boolean)}
 *       switches to a partial mid-band dip instead, which is weaker but musical, and it is the default so
 *       a chain whose side channel could not be wired degrades quietly rather than eating the mix.</li>
 * </ul>
 */
public class VocalDuckFilter extends FilterLowShelf {

    /**
     * The cancelled band. Below ~180 Hz there is no vocal fundamental worth removing but there is all of
     * the bass, and above ~4.2 kHz there is mostly sibilance and cymbals - taking either out is audible
     * as the mix losing weight or air rather than as the vocal leaving.
     */
    private static final double HIGH_PASS_HZ = 180.0;
    private static final double LOW_PASS_HZ = 4_200.0;
    /** Butterworth Q for both band edges: the flattest response with no ripple. */
    private static final double BAND_Q = .70710678;
    /** Depth changes below this are inaudible, and treated as "unchanged". */
    private static final double DEPTH_EPSILON = .002;
    /** Slew time constant. Long enough to be inaudible, short enough to track a 10 ms driver. */
    private static final double SLEW_SECONDS = .035;
    /**
     * How much of the band a mono source has dipped at full depth. Less than the stereo path takes,
     * because here there is no centre to isolate: this is an EQ notch on the whole signal, and going
     * further just makes the track sound broken.
     */
    private static final double MONO_DIP_SCALE = .55;

    /** The other channel's pre-effect signal, used only to estimate the centre. */
    public UnitInputPort side;

    private volatile double targetDepth;
    /** Defaults to the safe mode: without a wired side channel a full subtraction would be wrong. */
    private volatile boolean monoFallback = true;

    private double currentDepth;
    private int cachedRate;
    private int computedRate;

    private double highB0 = 1.0, highB1, highB2, highA1, highA2;
    private double lowB0 = 1.0, lowB1, lowB2, lowA1, lowA2;
    private double highX1, highX2, highY1, highY2;
    private double lowX1, lowX2, lowY1, lowY2;

    public VocalDuckFilter() {
        addPort(side = new UnitInputPort("Side"));
    }

    /**
     * @param depth 0 leaves the signal alone, 1 is full cancellation of the band-limited centre.
     */
    public void setDepth(float depth) {
        if (Float.isNaN(depth) || Float.isInfinite(depth)) return;
        this.targetDepth = Math.max(0.0, Math.min(1.0, depth));
    }

    public float getDepth() {
        return (float) this.targetDepth;
    }

    /** Jumps to a depth without slewing, for a deck that has not started sounding yet. */
    public void resetDepthTo(float depth) {
        setDepth(depth);
        this.currentDepth = this.targetDepth;
        clearBandState();
    }

    /** True when this channel has no usable side signal and has to fall back to a mid-band dip. */
    public void setMonoFallback(boolean mono) {
        this.monoFallback = mono;
    }

    @Override
    public void generate(int start, int limit) {
        // The shelf writes the output first; the cancellation is applied on top of it.
        super.generate(start, limit);

        int frames = limit - start;
        if (frames <= 0) return;

        int rate = this.cachedRate;
        if (rate <= 0) {
            try {
                rate = getFrameRate();
            } catch (Throwable ignored) {
                rate = 0;
            }
            if (rate <= 0) rate = 44_100;
            this.cachedRate = rate;
        }

        double target = this.targetDepth;
        double coefficient = 1.0 - Math.exp(-frames / (SLEW_SECONDS * rate));
        this.currentDepth += (target - this.currentDepth) * coefficient;
        if (Math.abs(target - this.currentDepth) < DEPTH_EPSILON) {
            this.currentDepth = target;
        }
        if (this.currentDepth < DEPTH_EPSILON && target < DEPTH_EPSILON) {
            // Off: leave the shelf's output byte for byte and forget the band state, so re-engaging
            // later cannot ring out an old buffer into the mix.
            clearBandState();
            return;
        }

        if (rate != this.computedRate) {
            updateBand(rate);
            this.computedRate = rate;
        }

        double[] outputs = output.getValues();
        double[] own = input.getValues();
        double[] other = this.side.getValues();
        boolean mono = this.monoFallback;
        double depth = this.currentDepth;
        for (int i = start; i < limit; i++) {
            double centre = mono ? own[i] * MONO_DIP_SCALE : (own[i] + other[i]) * .5;
            double band = bandPass(centre);
            double value = outputs[i] - depth * band;
            if (value != value || Double.isInfinite(value)) {
                // Same guard as the shelf: a NaN from a broken decoder must not lock the canceller into
                // producing garbage for the rest of the track.
                clearBandState();
                value = outputs[i];
            }
            outputs[i] = value;
        }
    }

    /** Cascaded high pass then low pass, i.e. the band the vocal lives in. */
    private double bandPass(double x0) {
        double high = this.highB0 * x0 + this.highB1 * this.highX1 + this.highB2 * this.highX2
                - this.highA1 * this.highY1 - this.highA2 * this.highY2;
        this.highX2 = this.highX1;
        this.highX1 = x0;
        this.highY2 = this.highY1;
        this.highY1 = high;

        double low = this.lowB0 * high + this.lowB1 * this.lowX1 + this.lowB2 * this.lowX2
                - this.lowA1 * this.lowY1 - this.lowA2 * this.lowY2;
        this.lowX2 = this.lowX1;
        this.lowX1 = high;
        this.lowY2 = this.lowY1;
        this.lowY1 = low;
        return low;
    }

    private void clearBandState() {
        this.highX1 = 0.0;
        this.highX2 = 0.0;
        this.highY1 = 0.0;
        this.highY2 = 0.0;
        this.lowX1 = 0.0;
        this.lowX2 = 0.0;
        this.lowY1 = 0.0;
        this.lowY2 = 0.0;
    }

    /** Robert Bristow-Johnson cookbook high pass and low pass, derived for the actual frame rate. */
    private void updateBand(int rate) {
        double highHz = Math.max(20.0, Math.min(rate * .45, HIGH_PASS_HZ));
        double w0 = 2.0 * Math.PI * highHz / rate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * BAND_Q);
        double a0 = 1.0 + alpha;
        this.highB0 = ((1.0 + cos) * .5) / a0;
        this.highB1 = (-(1.0 + cos)) / a0;
        this.highB2 = this.highB0;
        this.highA1 = (-2.0 * cos) / a0;
        this.highA2 = (1.0 - alpha) / a0;

        double lowHz = Math.max(highHz * 2.0, Math.min(rate * .45, LOW_PASS_HZ));
        w0 = 2.0 * Math.PI * lowHz / rate;
        cos = Math.cos(w0);
        alpha = Math.sin(w0) / (2.0 * BAND_Q);
        a0 = 1.0 + alpha;
        this.lowB0 = ((1.0 - cos) * .5) / a0;
        this.lowB1 = (1.0 - cos) / a0;
        this.lowB2 = this.lowB0;
        this.lowA1 = (-2.0 * cos) / a0;
        this.lowA2 = (1.0 - alpha) / a0;

        clearBandState();
    }
}
