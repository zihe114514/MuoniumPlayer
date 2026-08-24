package repackage.processing.sound;

import repackage.com.jsyn.unitgen.VocalDuckFilter;

/**
 * The per-deck automix processing chain: a stereo low shelf for the bass swap plus a band-limited centre
 * canceller for the vocal duck.
 *
 * <p>Both channels are driven from the same parameters, which is what a DJ mixer's low band does. See
 * {@link VocalDuckFilter} for why the blend needs real filters rather than more volume curves.</p>
 *
 * <p>This was {@code BassSwap} until the vocal duck was added. The rename is not cosmetic: a circuit only
 * has one effect slot, so everything the handover wants to do to a deck's signal has to live in this one
 * object, and calling it "bass swap" made the next thing added to it look misplaced.</p>
 */
public class AutomixDeckChain extends Effect<VocalDuckFilter> {

    public AutomixDeckChain() {
        super();
    }

    @Override
    protected VocalDuckFilter newInstance() {
        return new VocalDuckFilter();
    }

    /** Shelf gain in dB applied to the low band of both channels; 0 is transparent. */
    public void gainDb(float db) {
        this.left.setGainDb(db);
        this.right.setGainDb(db);
    }

    public float gainDb() {
        return this.left.getGainDb();
    }

    /** Corner frequency of the shelf in Hz. */
    public void frequency(float hz) {
        this.left.setFrequency(hz);
        this.right.setFrequency(hz);
    }

    /** Vocal-band cancellation depth for both channels, 0..1; 0 is transparent. */
    public void duckDepth(float depth) {
        this.left.setDepth(depth);
        this.right.setDepth(depth);
    }

    public float duckDepth() {
        return this.left.getDepth();
    }

    /**
     * Whether this deck has a real stereo image to work with.
     *
     * <p>A mono file is panned into two identical channels, so its "centre" is the whole signal and
     * subtracting it would silence the cancelled band outright. Passing {@code false} switches both
     * channels to the partial mid-band dip instead.</p>
     */
    public void stereo(boolean stereo) {
        this.left.setMonoFallback(!stereo);
        this.right.setMonoFallback(!stereo);
    }

    /**
     * Whether this effect is really wired into the given source's circuit right now.
     *
     * <p>{@link Effect#isProcessing()} cannot answer that: {@code AudioSample.pause()} goes through
     * {@code stop()}, which tears the effect out of the circuit but leaves it in the effect's own list
     * of inputs. A deck resumed after a pause would then look attached while its audio actually bypasses
     * the filter - during an automix blend that means the processing quietly stops working halfway
     * through.</p>
     */
    public boolean isAttachedTo(SoundObject source) {
        if (source == null) return false;
        JSynCircuit circuit = source.getUnitGenerator();
        return circuit != null && circuit.effect == this;
    }

    /** Forgets a source whose circuit dropped this effect on its own, so it can be attached again. */
    public void forget(SoundObject source) {
        if (source != null) this.inputs.remove(source);
    }

    /**
     * Attaches the chain and, on top of what {@link Effect#process(SoundObject)} wires, cross-connects the
     * two channels' side inputs.
     *
     * <p>{@code JSynCircuit.setEffect} only ever connects each channel to its own filter, which is all a
     * shelf needs. The centre canceller additionally needs the <em>other</em> channel, so the left filter's
     * side port is fed from pre-effect part 1 and the right one's from part 0.</p>
     *
     * <p>If that cannot be wired the chain drops to the mono fallback instead of guessing: a half-signal
     * centre estimate would attenuate the band on material that has no centred vocal at all.</p>
     */
    @Override
    public void process(SoundObject input) {
        super.process(input);
        try {
            JSynCircuit circuit = input == null ? null : input.getUnitGenerator();
            if (circuit == null || circuit.preEffect == null || circuit.preEffect.getNumParts() < 2) {
                stereo(false);
                return;
            }
            circuit.preEffect.connect(1, this.left.side, 0);
            circuit.preEffect.connect(0, this.right.side, 0);
        } catch (Throwable ignored) {
            stereo(false);
        }
    }

    /**
     * Detaches from every source and releases the filter units.
     *
     * <p>Unlike {@link Effect#stop()} this tolerates a source that has already dropped the effect (so it
     * does not log a spurious error), and it still returns the units to the engine even when the input
     * list has been emptied that way. The side connections are undone explicitly: nothing else in the
     * circuit knows they exist, so leaving them behind would keep this chain's filters pulled by a source
     * that has already moved on.</p>
     */
    public void release() {
        for (SoundObject source : new java.util.HashSet<>(this.inputs)) {
            unwireSide(source);
            if (isAttachedTo(source)) source.removeEffect(this);
        }
        this.inputs.clear();
        Engine.getEngine().remove(this.left);
        Engine.getEngine().remove(this.right);
    }

    private void unwireSide(SoundObject source) {
        try {
            JSynCircuit circuit = source == null ? null : source.getUnitGenerator();
            if (circuit == null || circuit.preEffect == null || circuit.preEffect.getNumParts() < 2) return;
            circuit.preEffect.disconnect(1, this.left.side, 0);
            circuit.preEffect.disconnect(0, this.right.side, 0);
        } catch (Throwable ignored) {
        }
    }

    /** Jumps to a shelf gain and duck depth without slewing, for a deck that has not started sounding yet. */
    public void resetTo(float db, float depth) {
        this.left.resetTo(db);
        this.right.resetTo(db);
        this.left.resetDepthTo(depth);
        this.right.resetDepthTo(depth);
    }
}
