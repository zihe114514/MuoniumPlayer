package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * Persisted automix (seamless track handover) preferences.
 *
 * <p>Automix keeps the outgoing track audible while the next one is already decoded and running on a
 * second deck, so a queue advance becomes an overlap instead of the old close-player/sleep(250ms)/
 * download/decode gap. Everything here is opt-out: with automix disabled the playback path is
 * byte-for-byte the original single-deck sequence.</p>
 */
public final class AutomixSettings {

    /** Shortest overlap that still reads as a blend rather than a cut. */
    public static final float MIN_OVERLAP_SECONDS = 1.5f;
    /** Longest overlap; beyond this the two tracks fight each other for too long. */
    public static final float MAX_OVERLAP_SECONDS = 10.0f;
    public static final float DEFAULT_OVERLAP_SECONDS = 4.0f;

    /**
     * Corner frequency of the automix low shelf. 200 Hz is where a kick drum's body and a bass line
     * live: cutting below it takes the mud out of an overlap without thinning the vocals.
     */
    public static final float BASS_SHELF_HZ = 200f;
    /** How far the outgoing deck's bass is pulled down at the point where the incoming one takes over. */
    public static final float BASS_CUT_DB = -18f;
    /** How far the incoming deck's bass starts suppressed before it is allowed into the mix. */
    public static final float BASS_ENTRY_CUT_DB = -12f;

    /**
     * Bounds on the playback rate the outgoing deck may be bent to. Shared by the planner (which picks
     * the target) and the plan (which schedules the ramp and the phase correction on top of it), so the
     * two can never disagree about what is reachable.
     *
     * <p>±6 % is not arbitrary: one semitone is 2^(1/12) = +5.946 %, so the budget is exactly wide
     * enough for a whole-semitone transposition and no wider. That is what makes the key snap either
     * land in tune or not happen at all - a rate between two semitones is audibly out of tune, which is
     * worse than leaving the track alone.</p>
     */
    public static final float MIN_PLAYBACK_RATE = .94f;
    public static final float MAX_PLAYBACK_RATE = 1.06f;
    /** One semitone as a playback-rate factor. */
    public static final float SEMITONE_RATIO = 1.0594630943592953f;

    /**
     * How hard the incoming deck's vocal band is cancelled while it fades in.
     *
     * <p>Two lead vocals on top of each other is the one artefact a listener can name, and unlike two
     * kick drums it cannot be fixed by a shelf: voices live in the same band as everything else.
     * Cancelling the band-limited centre of the arriving track lets it enter as an instrumental bed and
     * only become a song once the outgoing vocal is gone. 1 would be full cancellation, which also eats
     * the snare and the bass DI; a little over half keeps the entry musical.</p>
     */
    public static final float VOCAL_DUCK_DEPTH = .62f;

    /**
     * How early the next track starts being resolved/downloaded/decoded, on top of the overlap.
     * Decoding a normal track costs a few seconds; a fragmented-MP4 one can cost more, so the arm
     * window is generous. An arm that is not ready in time simply degrades to the ordinary switch.
     */
    static final long ARM_LEAD_MILLIS = 30_000L;

    /**
     * A second deck doubles the resident PCM, so automix refuses to arm oversized tracks. 120 MB of
     * 16-bit CD audio is about 11 minutes — long enough for ordinary music, short enough that two
     * live decks cannot exhaust the heap the way an hour-long upload would.
     */
    private static final long ABSOLUTE_DECK_LIMIT_BYTES = 120L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static boolean loaded;
    private static boolean enabled = true;
    private static boolean beatAlign = true;
    private static boolean bassSwap = true;
    private static boolean tempoLock = true;
    private static boolean vocalDuck = true;
    private static boolean pitchMatch = true;
    private static boolean skipIntro = true;
    private static float overlapSeconds = DEFAULT_OVERLAP_SECONDS;

    private AutomixSettings() {
    }

    /** Largest decoded file automix may hold on the idle deck. */
    static long maxDeckBytes() {
        return Math.min(ABSOLUTE_DECK_LIMIT_BYTES, PlaybackMemoryLimits.maxDecodedPcmBytes() / 2L);
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    /** Whether the handover may be nudged onto a detected bar boundary. */
    public static boolean isBeatAlignEnabled() {
        load();
        synchronized (LOCK) {
            return beatAlign;
        }
    }

    public static void setBeatAlignEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (beatAlign == value) return;
            beatAlign = value;
            saveLocked();
        }
    }

    /**
     * Whether the blend swaps the low band between the two decks instead of only crossfading volume.
     *
     * <p>Two tracks overlapping put two kick drums and two bass lines into the same octave, which sums
     * into a boom that no volume curve can fix - it is the single most obvious "this is a crossfade"
     * artefact. Pulling the low shelf out of the outgoing deck and holding the incoming one's bass back
     * until it owns the mix is what club DJs do, and it is what makes the handover sound deliberate.</p>
     */
    public static boolean isBassSwapEnabled() {
        load();
        synchronized (LOCK) {
            return bassSwap;
        }
    }

    public static void setBassSwapEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (bassSwap == value) return;
            bassSwap = value;
            saveLocked();
        }
    }

    /**
     * Whether the outgoing deck may be bent onto the incoming track's tempo for the length of the blend.
     *
     * <p>Without it, two tracks that are 4 % apart drift by a third of a beat over a four second
     * overlap, so a handover that started beat matched ends up flamming. The correction is capped and
     * only ever applied to the deck that is disappearing.</p>
     */
    public static boolean isTempoLockEnabled() {
        load();
        synchronized (LOCK) {
            return tempoLock;
        }
    }

    public static void setTempoLockEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (tempoLock == value) return;
            tempoLock = value;
            saveLocked();
        }
    }

    /**
     * Whether the arriving deck's vocal band is cancelled while it fades in, and restored once it owns
     * the mix.
     *
     * <p>Off by preference rather than by safety: the cancellation is a band-limited centre subtraction,
     * so on a mix whose lead is not centred it takes out less than it should, and on a mono file it
     * degrades to a mid-band dip. Both are still an improvement over two vocals colliding, but a user
     * who wants the plain equal-power blend can have it.</p>
     */
    public static boolean isVocalDuckEnabled() {
        load();
        synchronized (LOCK) {
            return vocalDuck;
        }
    }

    public static void setVocalDuckEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (vocalDuck == value) return;
            vocalDuck = value;
            saveLocked();
        }
    }

    /**
     * Whether the outgoing deck may be transposed by a whole semitone to make the two keys agree.
     *
     * <p>Only ever used where the tempo lock has nothing to do - a mix-out that lands in an outro or a
     * pad, i.e. where the beat grid is too weak to be worth locking. Both cannot be served at once:
     * JSyn's rate port moves tempo and pitch together, and the whole ±6 % budget is one semitone.</p>
     */
    public static boolean isPitchMatchEnabled() {
        load();
        synchronized (LOCK) {
            return pitchMatch;
        }
    }

    public static void setPitchMatchEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (pitchMatch == value) return;
            pitchMatch = value;
            saveLocked();
        }
    }

    /**
     * 交接时是否跳过新歌的前奏，直接从主体进来。
     *
     * <p>关掉之后新歌一律从第 0 毫秒开始，也就是回到「淡入整首歌的开头」这一最朴素的行为。前奏是不是
     * 「废话」终究是主观判断：铺垫式的长前奏对一部分听众正是这首歌的一半，被自动切掉会觉得歌被剪了。</p>
     *
     * <p>注意这只改变<b>从哪里开始出声</b>，不影响分析：前奏位置照样要测出来，节拍与调性必须在有内容的
     * 段落上分析，否则安静的前奏会把 BPM 和调性一起测废，连节奏锁定与和声判断都跟着失效。</p>
     */
    public static boolean isSkipIntroEnabled() {
        load();
        synchronized (LOCK) {
            return skipIntro;
        }
    }

    public static void setSkipIntroEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (skipIntro == value) return;
            skipIntro = value;
            saveLocked();
        }
    }

    public static float getOverlapSeconds() {
        load();
        synchronized (LOCK) {
            return overlapSeconds;
        }
    }

    public static long getOverlapMillis() {
        return (long) (getOverlapSeconds() * 1000f);
    }

    public static void setOverlapSeconds(float value) {
        load();
        synchronized (LOCK) {
            float clamped = clamp(value);
            if (Math.abs(clamped - overlapSeconds) < .01f) return;
            overlapSeconds = clamped;
            saveLocked();
        }
    }

    /**
     * 重叠时长的可选档位。设置界面用点击循环而不是滑块：这一项的有效范围只有 1.5~10 秒，
     * 而且用户真正会选的就是这几个整数值，一格一格拖没有意义。
     */
    private static final float[] OVERLAP_PRESETS = {1.5f, 2f, 3f, 4f, 6f, 8f, 10f};

    /** 当前重叠时长的显示文案。 */
    public static String getOverlapLabel() {
        float seconds = getOverlapSeconds();
        if (Math.abs(seconds - Math.round(seconds)) < .05f) {
            return Math.round(seconds) + " 秒";
        }
        return String.format("%.1f 秒", seconds);
    }

    /** 切到下一个重叠档位，走到末尾回到第一个。 */
    public static void cycleOverlapSeconds() {
        float current = getOverlapSeconds();
        int index = 0;
        // 找当前值所在的档位；配置文件里手填的中间值会落到「下一个比它大的档位」。
        for (int i = 0; i < OVERLAP_PRESETS.length; i++) {
            if (current >= OVERLAP_PRESETS[i] - .05f) index = i;
        }
        setOverlapSeconds(OVERLAP_PRESETS[(index + 1) % OVERLAP_PRESETS.length]);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return DEFAULT_OVERLAP_SECONDS;
        return Math.max(MIN_OVERLAP_SECONDS, Math.min(MAX_OVERLAP_SECONDS, value));
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.AUTOMIX;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
                if (root.has("beatAlign") && !root.get("beatAlign").isJsonNull()) {
                    beatAlign = root.get("beatAlign").getAsBoolean();
                }
                if (root.has("bassSwap") && !root.get("bassSwap").isJsonNull()) {
                    bassSwap = root.get("bassSwap").getAsBoolean();
                }
                if (root.has("tempoLock") && !root.get("tempoLock").isJsonNull()) {
                    tempoLock = root.get("tempoLock").getAsBoolean();
                }
                if (root.has("vocalDuck") && !root.get("vocalDuck").isJsonNull()) {
                    vocalDuck = root.get("vocalDuck").getAsBoolean();
                }
                if (root.has("skipIntro") && !root.get("skipIntro").isJsonNull()) {
                    skipIntro = root.get("skipIntro").getAsBoolean();
                }
                if (root.has("pitchMatch") && !root.get("pitchMatch").isJsonNull()) {
                    pitchMatch = root.get("pitchMatch").getAsBoolean();
                }
                if (root.has("overlapSeconds") && !root.get("overlapSeconds").isJsonNull()) {
                    overlapSeconds = clamp(root.get("overlapSeconds").getAsFloat());
                }
            } catch (Throwable ignored) {
                // A damaged automix config must never stop playback from starting.
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.AUTOMIX.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("beatAlign", beatAlign);
            root.addProperty("bassSwap", bassSwap);
            root.addProperty("tempoLock", tempoLock);
            root.addProperty("vocalDuck", vocalDuck);
            root.addProperty("pitchMatch", pitchMatch);
            root.addProperty("skipIntro", skipIntro);
            root.addProperty("overlapSeconds", overlapSeconds);
            JsonConfigStorage.writeObject(ConfigPaths.AUTOMIX, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
