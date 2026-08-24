package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * Builds the {@link AutomixPlan} for a handover between two already decoded decks.
 *
 * <p>The structural choices follow the DJ logic the reference implementation documents: mix out of the
 * outgoing track once its vocals are done and its arrangement thins out, mix into the incoming track
 * where its intro actually ends, land the handover on a bar boundary, and match loudness so neither
 * side appears to drop.</p>
 *
 * <p>On top of that the plan carries the things that actually separate a DJ blend from a crossfade.</p>
 * <ul>
 *   <li><b>The low band is swapped, not summed.</b> Two tracks overlapping put two kick drums and two
 *       bass lines into the same octave, and that sums into a boom no volume curve can fix, so the
 *       outgoing deck's low shelf is pulled out early and the incoming one's is held back until it owns
 *       the mix.</li>
 *   <li><b>The arriving track enters without its vocal.</b> Two lead vocals at once is the artefact a
 *       listener can actually name, and it cannot be shelved away because voices share their band with
 *       everything else - so the incoming deck's band-limited centre is cancelled and released.</li>
 *   <li><b>The two beat grids are phase locked, not just tempo matched.</b> The mix-out is quantised onto
 *       an outgoing downbeat and the cue onto an incoming one; what is left over after that quantisation
 *       is measured and handed to the plan's rhythm buffer, which walks it off over the middle of the
 *       ramp. Matching tempo without matching phase produces two grids a fraction of a beat apart for
 *       the whole overlap, which is exactly what flamming is.</li>
 *   <li><b>Where there is no beat, the rate budget is spent on key instead.</b> One rate port cannot serve
 *       tempo and pitch at once, and the whole ±6 % budget happens to be one semitone. So a mix-out that
 *       lands in an outro or on a pad - measurably weak beat grid - gets a whole-semitone transposition
 *       that makes the two keys agree, while a mix-out with a real beat gets the tempo lock.</li>
 * </ul>
 *
 * <p>Only the deck that is leaving is ever bent: JSyn's rate control shifts pitch as well as tempo, and a
 * few percent on a track that is already several dB down and on its way out is inaudible, whereas doing
 * it to the arriving track would detune the rest of the song.</p>
 *
 * <p><b>Analysis is never a requirement</b>: when a window cannot be measured the plan degrades to a
 * plain equal-power crossfade instead of falling back to a hard cut, because the whole point is that
 * the queue never falls silent between two tracks.</p>
 */
final class AutomixPlanner {

    /** Window used for tempo induction; a few seconds are needed to lock onto a beat period. */
    private static final long BEAT_WINDOW_MILLIS = 12_000L;
    /**
     * How much of an incoming intro may be skipped. Deliberately conservative: the point is to avoid
     * blending into dead air, not to edit the song, so a long deliberate intro is played in full.
     */
    private static final long MAX_INTRO_SKIP_MILLIS = 12_000L;
    /** An intro is never allowed to eat more than this share of a short track. */
    private static final double MAX_INTRO_SKIP_FRACTION = .12;
    /** Beat alignment is only claimed inside this tempo band, mirroring the reference's vibe check. */
    private static final float MIN_TEMPO_RATIO = .92f;
    private static final float MAX_TEMPO_RATIO = 1.08f;
    /**
     * How confident a beat grid has to be before anything is built on it.
     *
     * <p>Tempo induction returns a number for any input, including a string pad. Acting on a grid that
     * was not really there moves the mix-out by up to two seconds onto an imaginary bar line and claims
     * the result is beat matched, which is worse than not aligning at all. The same threshold decides
     * whether the rate budget goes to tempo or to key: below it there is no beat to keep locked.</p>
     */
    private static final float MIN_GRID_CONFIDENCE = .28f;
    /** A bend this small is inaudible and not worth touching the rate port for. */
    private static final float MIN_USEFUL_RATE_DELTA = .004f;
    /**
     * Safety factor on the two grids' own tempo precision, used to build the dead band inside which the
     * two tempos are treated as identical.
     *
     * <p>The analysis is good to a fraction of a percent on paper, but the two grids are measured on
     * different windows of different tracks - one of them typically a thinning outro - and in practice
     * they disagree by more than the ideal figure. Doubling the combined tolerance is what stops the lock
     * from "correcting" a pair that was already in sync: bending 1.5 % over a four second overlap adds
     * 60 ms of drift where there was none, which is exactly the flamming the lock exists to prevent.</p>
     */
    private static final double TEMPO_NOISE_FACTOR = 2.0;
    /** Loudness compensation stays gentle; a big correction would just sound like a volume jump. */
    private static final float MIN_GAIN = .65f;
    private static final float MAX_GAIN = 1.60f;
    /** Below this the outgoing track is too short for an overlap to be anything but a cut. */
    private static final long MIN_OUTGOING_MILLIS = 12_000L;
    private static final long MIN_INCOMING_MILLIS = 8_000L;
    /**
     * How much of the outgoing tail may be traded for an earlier, more musical mix-out. Mixing out at
     * the structural outro is what makes a transition sound intentional, but cutting the end off a song
     * is exactly what users notice and complain about, so the trim is bounded both absolutely and as a
     * share of the track - and a mix-out estimate that falls outside those bounds is discarded rather
     * than clamped onto them.
     */
    private static final long MAX_TRIM_MILLIS = 12_000L;
    private static final double MIN_TRIM_POSITION = .60;
    /** Tail scanned for the structural outro when no lyric boundary is available. */
    private static final long OUTRO_SCAN_MILLIS = 45_000L;
    /** A plain LRC line has no end time; assume the last word rings out roughly this long. */
    private static final long LYRIC_TAIL_MILLIS = 1_500L;

    /**
     * Largest residual grid offset the rhythm buffer is asked to absorb. Beyond this the two grids were
     * not really describing the same pulse, and pushing the outgoing deck around for half a beat would
     * be heard as the deck stumbling rather than as a correction.
     */
    private static final long MAX_PHASE_ERROR_MILLIS = 140L;
    /** A residual this small is already inside the ear's flam threshold. */
    private static final long MIN_PHASE_ERROR_MILLIS = 6L;

    /** A key estimate below this is treated as "no key measured". */
    private static final float MIN_KEY_CONFIDENCE = .35f;
    /** Only a clash at least this bad is worth transposing a deck to avoid. */
    private static final float MIN_CLASH_TO_TRANSPOSE = .50f;
    /** …and the transposition has to buy at least this much improvement to be applied. */
    private static final float MIN_HARMONY_GAIN = .25f;
    /** From here up the two keys are fighting, and the blend is kept short. */
    private static final float CLASH_TRIM_PENALTY = .70f;
    private static final double CLASH_OVERLAP_SHARE = .55;
    private static final long MIN_CLASH_OVERLAP_MILLIS = 1_200L;
    /** Extra vocal cancellation for a clashing pair, on top of the configured depth. */
    private static final float CLASH_EXTRA_DUCK = .12f;
    private static final float MAX_VOCAL_DUCK = .85f;

    private AutomixPlanner() {
    }

    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming) {
        return plan(outgoing, incoming, -1L);
    }

    /**
     * @param lastVocalEndMillis absolute position of the end of the outgoing track's last lyric line,
     *                           or a non-positive value when no timed lyrics are available. Mixing out
     *                           after the last vocal is the single biggest reason a blend sounds
     *                           seamless: two sets of vocals never collide.
     * @return the plan, or {@code null} only when the pair is fundamentally too short to blend
     */
    static AutomixPlan plan(AudioPlayer outgoing, AudioPlayer incoming, long lastVocalEndMillis) {
        if (outgoing == null || incoming == null) return null;
        if (!outgoing.isUsable() || !incoming.isUsable()) return null;

        long outgoingMillis = (long) outgoing.getTotalTimeMillis();
        long incomingMillis = (long) incoming.getTotalTimeMillis();
        if (outgoingMillis < MIN_OUTGOING_MILLIS || incomingMillis < MIN_INCOMING_MILLIS) return null;

        // The overlap can never exceed a quarter of the outgoing track or half of the incoming one,
        // otherwise a short track would be blended almost end to end.
        long overlap = AutomixSettings.getOverlapMillis();
        overlap = Math.min(overlap, outgoingMillis / 4L);
        overlap = Math.min(overlap, incomingMillis / 2L);
        if (overlap < 800L) return null;

        // Analysis reads the decoded cache files with their own handles. It must never read the live
        // JSyn samples: those stream through one shared sliding window, and moving it under the audio
        // thread stops playback dead (see PcmWindowReader).
        AnalysisAudio outgoingAudio = AnalysisAudioSource.open(outgoing.getSourceFile());
        AnalysisAudio incomingAudio = AnalysisAudioSource.open(incoming.getSourceFile());
        try {
            return plan(outgoingMillis, incomingMillis, overlap, lastVocalEndMillis,
                    outgoingAudio, incomingAudio);
        } finally {
            if (outgoingAudio != null) outgoingAudio.close();
            if (incomingAudio != null) incomingAudio.close();
        }
    }

    private static AutomixPlan plan(long outgoingMillis, long incomingMillis, long overlap,
                                    long lastVocalEndMillis, AnalysisAudio outgoingAudio,
                                    AnalysisAudio incomingAudio) {
        // Analysis is a bonus, not a requirement. A pair whose cache files cannot be parsed still gets
        // a plain equal-power crossfade rather than degrading to the old silent gap.
        boolean analysable = outgoingAudio != null && incomingAudio != null;
        int outgoingRate = analysable ? outgoingAudio.sampleRate() : 0;
        int incomingRate = analysable ? incomingAudio.sampleRate() : 0;
        if (outgoingRate <= 0 || incomingRate <= 0) analysable = false;
        boolean beatAlign = AutomixSettings.isBeatAlignEnabled();

        // -- Incoming side: skip a dead intro, then snap onto its first downbeat. --
        long cue = 0L;
        long incomingGridOrigin = 0L;
        AutomixAnalyzer.BeatGrid incomingGrid = null;
        AutomixAnalyzer.KeyEstimate incomingKey = null;
        if (analysable) {
            // Where the intro ends is measured either way, even when the setting says to play it. The beat
            // grid and the key have to be read off material that actually has content: a quiet intro
            // measures as no pulse and no tonic, which would silently disable the tempo lock, the phase
            // buffer and the harmony gate for the entire handover. Only the *entry point* is optional.
            long introEnd = 0L;
            long introScan = Math.min(MAX_INTRO_SKIP_MILLIS + BEAT_WINDOW_MILLIS, incomingMillis / 2L);
            float[] introWindow = incomingAudio.readMono(0L, introScan);
            if (introWindow.length > 0) {
                long detected = (long) AutomixAnalyzer.detectIntroEnd(introWindow, incomingRate);
                long introCap = Math.min(MAX_INTRO_SKIP_MILLIS,
                        (long) (incomingMillis * MAX_INTRO_SKIP_FRACTION));
                introEnd = Math.max(0L, Math.min(introCap, detected));
            }

            incomingGridOrigin = introEnd;
            float[] incomingBeatWindow = incomingAudio.readMono(introEnd,
                    Math.min(BEAT_WINDOW_MILLIS, incomingMillis - introEnd));
            incomingGrid = AutomixAnalyzer.analyzeBeats(incomingBeatWindow, incomingRate);
            // The same window answers the key question. Reading a second one would cost another ffmpeg
            // decode for exactly the material already in hand.
            incomingKey = AutomixAnalyzer.analyzeKey(incomingBeatWindow, incomingRate);
            if (AutomixSettings.isSkipIntroEnabled()) {
                cue = introEnd;
                if (beatAlign && usableGrid(incomingGrid)) {
                    long snapped = cue + (long) incomingGrid.barPhaseMillis;
                    // Only accept the snap when it still leaves a full overlap of audio behind it.
                    if (snapped + overlap < incomingMillis) cue = snapped;
                }
            }
            // cue stays 0 otherwise. The residual-phase maths below folds each grid against its own
            // origin and wraps into ±half a beat, so a cue that sits before the grid origin is handled
            // by the same expression - nothing there assumes cue >= incomingGridOrigin.
        }

        // -- Outgoing side: mix out where the song is done talking, not where the file ends. --
        long latestFire = outgoingMillis - overlap;
        long earliestFire = Math.max((long) (outgoingMillis * MIN_TRIM_POSITION),
                outgoingMillis - MAX_TRIM_MILLIS);
        if (earliestFire > latestFire) earliestFire = latestFire;

        long preferredFire = -1L;
        String mixOutReason = "尾部混出";
        if (lastVocalEndMillis > 0L) {
            long candidate = lastVocalEndMillis + LYRIC_TAIL_MILLIS;
            // Only trusted inside the window this track may be trimmed by. A lyric end far earlier than
            // that is not a long outro, it is bad data - a failed lyric load leaves the previous track's
            // timeline in place - and clamping it onto the boundary would silently cut the maximum
            // allowed amount off the end of every song.
            if (candidate >= earliestFire && candidate <= latestFire) {
                preferredFire = candidate;
                mixOutReason = "唱完后混出";
            }
        }
        if (preferredFire <= 0L && analysable) {
            long scanStart = Math.max(0L, latestFire - OUTRO_SCAN_MILLIS);
            float[] tail = outgoingAudio.readMono(scanStart, outgoingMillis - scanStart);
            float landing = AutomixAnalyzer.detectOutroLanding(tail, outgoingRate);
            if (landing >= 0f) {
                // Measured from the audio itself, so a landing beyond the trim budget is real evidence
                // and only gets pulled back to the boundary.
                preferredFire = scanStart + (long) landing;
                mixOutReason = "尾奏混出";
            }
        }

        long fire = latestFire;
        if (preferredFire > 0L) {
            fire = Math.max(earliestFire, Math.min(latestFire, preferredFire));
        }

        // -- Land the handover on a bar boundary of the outgoing track. --
        long outgoingGridOrigin = 0L;
        AutomixAnalyzer.BeatGrid outgoingGrid = null;
        AutomixAnalyzer.KeyEstimate outgoingKey = null;
        boolean barAligned = false;
        if (analysable) {
            // Analysed backwards from the mix-out point: a window that ran forwards would overrun the
            // end of the track exactly when the mix-out sits near it, and return nothing.
            long gridStart = Math.max(0L, fire - BEAT_WINDOW_MILLIS);
            outgoingGridOrigin = gridStart;
            float[] outgoingBeatWindow = outgoingAudio.readMono(gridStart, fire - gridStart);
            outgoingGrid = AutomixAnalyzer.analyzeBeats(outgoingBeatWindow, outgoingRate);
            outgoingKey = AutomixAnalyzer.analyzeKey(outgoingBeatWindow, outgoingRate);
            if (beatAlign && usableGrid(outgoingGrid)) {
                float barPeriod = outgoingGrid.barPeriodMillis();
                if (barPeriod > 200f && barPeriod < 8_000f) {
                    long firstBar = gridStart + (long) outgoingGrid.barPhaseMillis;
                    long nearest = Math.round((fire - firstBar) / (double) barPeriod);
                    // The nearest bar line very often sits just outside the window the mix-out is allowed
                    // in - the default mix-out *is* latestFire, so half the time the nearest bar is past
                    // it - and the old code then gave up on alignment entirely. The bar before is still a
                    // bar line and still musical, so the neighbours are tried in order of distance.
                    long[] bars = {nearest, nearest - 1L, nearest + 1L, nearest - 2L, nearest + 2L};
                    for (int index = 0; index < bars.length; index++) {
                        long candidate = firstBar + (long) (bars[index] * barPeriod);
                        if (candidate >= earliestFire && candidate <= latestFire && candidate > 0L) {
                            fire = candidate;
                            barAligned = true;
                            break;
                        }
                    }
                }
            }
        }

        // -- Loudness match across the two zones that will actually overlap. --
        float gain = 1f;
        if (analysable) {
            float[] outgoingZone = outgoingAudio.readMono(fire, Math.min(overlap, outgoingMillis - fire));
            float[] incomingZone = incomingAudio.readMono(cue, Math.min(overlap, incomingMillis - cue));
            // K-weighted first: it is what the ear does. RMS is only the fallback for a window the
            // gating rejects, e.g. an overlap that lands on a near-silent tail.
            float outgoingLufs = AutomixAnalyzer.kWeightedLoudness(outgoingZone, outgoingRate);
            float incomingLufs = AutomixAnalyzer.kWeightedLoudness(incomingZone, incomingRate);
            if (!Float.isNaN(outgoingLufs) && !Float.isNaN(incomingLufs)) {
                gain = (float) Math.pow(10.0, (outgoingLufs - incomingLufs) / 20.0);
            } else {
                float outgoingRms = AutomixAnalyzer.rms(outgoingZone);
                float incomingRms = AutomixAnalyzer.rms(incomingZone);
                if (outgoingRms > .001f && incomingRms > .001f) {
                    gain = outgoingRms / incomingRms;
                }
            }
            gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));
        }

        // -- Vibe check: only call it beat matched when the tempos really are close. --
        boolean gridsUsable = usableGrid(outgoingGrid) && usableGrid(incomingGrid);
        boolean tempoMatched = false;
        if (gridsUsable) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float ratio = outgoingGrid.bpm / normalisedIncoming;
            tempoMatched = ratio >= MIN_TEMPO_RATIO && ratio <= MAX_TEMPO_RATIO;
        }

        // -- The rate budget. Tempo or pitch, never both: one port moves them together, and the whole
        //    ±6 % it may be moved by is exactly one semitone. --
        float tempoRatio = 1f;
        int semitoneShift = 0;
        long phaseError = 0L;
        boolean tempoLocked = false;
        if (tempoMatched && AutomixSettings.isTempoLockEnabled()) {
            float normalisedIncoming = AutomixAnalyzer.octaveNormalise(incomingGrid.bpm, outgoingGrid.bpm);
            float desired = normalisedIncoming / outgoingGrid.bpm;
            tempoRatio = Math.max(AutomixSettings.MIN_PLAYBACK_RATE,
                    Math.min(AutomixSettings.MAX_PLAYBACK_RATE, desired));
            // Dead band from the measurements' own precision, never smaller than the audibility floor.
            double deadband = Math.max(MIN_USEFUL_RATE_DELTA, TEMPO_NOISE_FACTOR
                    * Math.hypot(outgoingGrid.tempoTolerance, incomingGrid.tempoTolerance));
            if (Math.abs(tempoRatio - 1f) < deadband) tempoRatio = 1f;
            tempoLocked = true;
            // Phase locking only means something once both grids are believed and the mix-out really
            // sits on an outgoing downbeat; otherwise the "residual" is measured against a line that
            // was never used.
            if (barAligned) {
                phaseError = residualPhaseMillis(outgoingGrid, outgoingGridOrigin, fire,
                        incomingGrid, incomingGridOrigin, cue);
            }
        } else if (AutomixSettings.isPitchMatchEnabled() && !tempoMatched
                && confidentKey(outgoingKey) && confidentKey(incomingKey)) {
            // Reached exactly when there is no beat relationship to protect: either a grid was too weak
            // to believe (an outro, a fade, a pad) or the two tempos are more than 8 % apart, in which
            // case they are going to drift regardless and a few percent more changes nothing. The rate
            // budget is therefore free, and what a listener hears in that situation is the harmony. Note
            // that a matched pair whose lock happens to come out as 1.000 does *not* land here: bending
            // that deck would break a beat match that already existed.
            float straight = AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, 0);
            if (straight >= MIN_CLASH_TO_TRANSPOSE) {
                int shift = AutomixAnalyzer.bestSemitoneShift(outgoingKey, incomingKey, 1);
                float shifted = AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, shift);
                if (shift != 0 && shifted <= straight - MIN_HARMONY_GAIN) {
                    semitoneShift = shift;
                    tempoRatio = shift > 0
                            ? AutomixSettings.SEMITONE_RATIO
                            : 1f / AutomixSettings.SEMITONE_RATIO;
                }
            }
        }

        // -- Harmony gate. Two keys a semitone or a tritone apart are the one pairing that gets worse
        //    the longer it lasts, so the blend is shortened and the arriving vocal held down harder.
        //    Only ever shortened: a longer overlap would invalidate the mix-out window above. --
        boolean keysKnown = confidentKey(outgoingKey) && confidentKey(incomingKey);
        float harmony = keysKnown
                ? AutomixAnalyzer.harmonicPenalty(outgoingKey, incomingKey, semitoneShift)
                : .5f;
        boolean clashing = keysKnown && harmony >= CLASH_TRIM_PENALTY;
        if (clashing) {
            long trimmed = Math.max(MIN_CLASH_OVERLAP_MILLIS, (long) (overlap * CLASH_OVERLAP_SHARE));
            if (trimmed < overlap) overlap = trimmed;
        }

        boolean bassSwap = AutomixSettings.isBassSwapEnabled();
        float vocalDuck = AutomixSettings.isVocalDuckEnabled() ? AutomixSettings.VOCAL_DUCK_DEPTH : 0f;
        if (vocalDuck > 0f && clashing) {
            vocalDuck = Math.min(MAX_VOCAL_DUCK, vocalDuck + CLASH_EXTRA_DUCK);
        }

        boolean beatMatched = barAligned && tempoMatched;
        String summary = describe(overlap, cue, gain, beatMatched, mixOutReason, outgoingGrid,
                incomingGrid, bassSwap, tempoRatio, semitoneShift, phaseError, vocalDuck,
                keysKnown ? outgoingKey : null, keysKnown ? incomingKey : null);
        System.out.println("[Automix] plan: fire=" + fire + "/" + outgoingMillis + "ms overlap=" + overlap
                + "ms cue=" + cue + "ms gain=" + String.format(Locale.ROOT, "%.2f", gain)
                + " (" + mixOutReason + (beatMatched ? ", 小节对齐" : "") + (bassSwap ? ", 低频交换" : "")
                + (vocalDuck > 0f ? String.format(Locale.ROOT, ", 去人声 %.0f%%", vocalDuck * 100f) : "")
                + (tempoRatio == 1f ? "" : String.format(Locale.ROOT,
                        tempoLocked ? ", 速率 %.3f" : ", 移调 %.3f", tempoRatio))
                + (semitoneShift == 0 ? "" : (semitoneShift > 0 ? ", +1 半音" : ", -1 半音"))
                + (phaseError == 0L ? "" : ", 相位 " + phaseError + "ms")
                + (keysKnown ? ", 调性 " + outgoingKey.label() + "→" + incomingKey.label()
                        + String.format(Locale.ROOT, "(%.2f)", harmony) : "")
                + (analysable ? "" : ", 无分析") + ")");
        // semitoneShift != 0 是「移调」分支的唯一标记：那时 tempoRatio 表达的是音高倍率，
        // 交给 WSOLA 当速度用会把意图整个搞反（见 AutomixPlan#pitchShift）。
        return new AutomixPlan(fire, overlap, cue, gain, beatMatched, bassSwap, tempoRatio,
                semitoneShift != 0, phaseError, vocalDuck, summary);
    }

    /** A grid is only usable when it exists, has a sane tempo, and was measured with confidence. */
    private static boolean usableGrid(AutomixAnalyzer.BeatGrid grid) {
        return grid != null && grid.bpm > 1f && grid.confidence >= MIN_GRID_CONFIDENCE;
    }

    private static boolean confidentKey(AutomixAnalyzer.KeyEstimate key) {
        return key != null && key.confidence >= MIN_KEY_CONFIDENCE;
    }

    /**
     * How far the outgoing deck has to be nudged forward for the two downbeat grids to line up at the
     * handover, in ms. Positive means the outgoing downbeat arrives late.
     *
     * <p>Both sides are measured as "how far past a downbeat is this deck at the moment of the
     * handover", using each grid's own origin, and the difference is wrapped into ±half a beat. Half a
     * <em>beat</em>, not half a bar: what the ear hears when two grids disagree is the beat flamming, and
     * pushing a deck by three beats to line up bar numbers would be a far bigger intervention than the
     * problem.</p>
     *
     * <p>Returns 0 when the answer is already inside the flam threshold or so large that the two grids
     * clearly were not describing the same pulse.</p>
     */
    private static long residualPhaseMillis(AutomixAnalyzer.BeatGrid outgoingGrid, long outgoingOrigin,
                                            long fire, AutomixAnalyzer.BeatGrid incomingGrid,
                                            long incomingOrigin, long cue) {
        if (outgoingGrid == null || incomingGrid == null) return 0L;
        double outgoingBeat = outgoingGrid.beatPeriodMillis();
        double incomingBeat = incomingGrid.beatPeriodMillis();
        if (outgoingBeat <= 20.0 || incomingBeat <= 20.0) return 0L;

        double outgoingPhase = wrapSigned(
                fire - (outgoingOrigin + outgoingGrid.barPhaseMillis), outgoingBeat);
        double incomingPhase = wrapSigned(
                cue - (incomingOrigin + incomingGrid.barPhaseMillis), incomingBeat);

        double reference = (outgoingBeat + incomingBeat) * .5;
        long error = Math.round(wrapSigned(incomingPhase - outgoingPhase, reference));
        if (Math.abs(error) < MIN_PHASE_ERROR_MILLIS) return 0L;
        if (Math.abs(error) > MAX_PHASE_ERROR_MILLIS) return 0L;
        return error;
    }

    /** Signed distance to the nearest multiple of {@code period}, in [-period/2, period/2]. */
    private static double wrapSigned(double value, double period) {
        if (period <= 0.0 || Double.isNaN(value)) return 0.0;
        double wrapped = value % period;
        if (wrapped > period * .5) wrapped -= period;
        if (wrapped < -period * .5) wrapped += period;
        return wrapped;
    }

    private static String describe(long overlap, long cue, float gain, boolean beatMatched,
                                   String mixOutReason,
                                   AutomixAnalyzer.BeatGrid outgoingGrid,
                                   AutomixAnalyzer.BeatGrid incomingGrid,
                                   boolean bassSwap, float tempoRatio, int semitoneShift,
                                   long phaseError, float vocalDuck,
                                   AutomixAnalyzer.KeyEstimate outgoingKey,
                                   AutomixAnalyzer.KeyEstimate incomingKey) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.ROOT, "%.1fs 重叠", overlap / 1000f));
        builder.append(" · ").append(mixOutReason);
        if (beatMatched && outgoingGrid != null && incomingGrid != null) {
            builder.append(" · 小节对齐 ")
                    .append(Math.round(outgoingGrid.bpm)).append("→").append(Math.round(incomingGrid.bpm))
                    .append(" BPM");
        } else {
            builder.append(" · 等功率淡化");
        }
        if (cue > 400L) {
            builder.append(String.format(Locale.ROOT, " · 跳过前奏 %.1fs", cue / 1000f));
        }
        if (bassSwap) {
            builder.append(" · 低频交换");
        }
        if (vocalDuck > 0f) {
            builder.append(String.format(Locale.ROOT, " · 去人声 %.0f%%", vocalDuck * 100f));
        }
        if (semitoneShift != 0) {
            builder.append(semitoneShift > 0 ? " · 移调 +1 半音" : " · 移调 -1 半音");
        } else if (tempoRatio != 1f) {
            builder.append(String.format(Locale.ROOT, " · 节奏锁定 %+.1f%%", (tempoRatio - 1f) * 100f));
        }
        if (phaseError != 0L) {
            builder.append(String.format(Locale.ROOT, " · 相位补偿 %+dms", phaseError));
        }
        if (outgoingKey != null && incomingKey != null) {
            builder.append(" · ").append(outgoingKey.label()).append("→").append(incomingKey.label());
        }
        if (Math.abs(gain - 1f) > .08f) {
            builder.append(String.format(Locale.ROOT, " · 音量补偿 %.2fx", gain));
        }
        return builder.toString();
    }
}
