package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.OptionsUtil;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * Tracks real local NetEase playback time and submits one final duration event
 * when the playback session ends. Paused time and seek jumps are never counted.
 */
public final class NeteasePlaybackHistoryReporter {

    /** A stalled client/render thread must not turn a freeze into listening time. */
    private static final long MAXIMUM_OBSERVED_STEP_NANOS = 1_500_000_000L;

    private static final Object LOCK = new Object();
    private static ActiveSession activeSession;

    private NeteasePlaybackHistoryReporter() {
    }

    public enum EndReason {
        COMPLETED,
        REPLACED
    }

    /** Begins local timing only after audio playback has successfully started. */
    public static void start(Music song, PlayList source, AudioPlayer player) {
        if (song == null || player == null || !song.isNetease()
                || !NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()) {
            return;
        }

        synchronized (LOCK) {
            // Flush the previous local session if a replacement starts without a normal stop callback.
            finishLocked(null, EndReason.REPLACED, true);
            activeSession = new ActiveSession(song, source, player, System.nanoTime());
        }

        // Recent-play history is reported immediately and independently from duration reporting.
        final Music startedSong = song;
        final PlayList startedSource = source;
        MultiThreadingUtil.runAsync(() -> {
            // 上报开始也给一条反馈：走的是和上报结果同一条最低优先级排队通道，不会抢占任何
            // 用户主动触发的卡片，只是让"正在上报"这一段有可见状态而不是凭空出现结果。
            DownloadDynamicIsland.showNeteaseUploadStarted("听歌历史上报");
            NeteaseClientLogUploader.UploadResult result =
                    NeteaseClientLogUploader.uploadPlayView(startedSong, startedSource);
            if (result.isSuccess()) {
                DownloadDynamicIsland.showRecentPlayUploadSuccess(result.getElapsedMillis());
            } else {
                DownloadDynamicIsland.showRecentPlayUploadFailure(result.getElapsedMillis(), result.getMessage());
            }
        });
    }

    /** Samples the already-running player; paused state never accumulates. */
    public static void observe(AudioPlayer player) {
        if (player == null) return;
        synchronized (LOCK) {
            if (activeSession != null && activeSession.player == player) {
                sampleLocked(activeSession, System.nanoTime());
            }
        }
    }

    /**
     * Stops local timing and submits exactly one cumulative duration event for this session.
     * There is no duration threshold: any positive whole second that was actually played is sent.
     */
    public static void finish(AudioPlayer player, EndReason reason) {
        synchronized (LOCK) {
            finishLocked(player, reason == null ? EndReason.REPLACED : reason, true);
        }
    }

    private static void finishLocked(AudioPlayer player, EndReason reason, boolean submit) {
        ActiveSession session = activeSession;
        if (session == null || (player != null && session.player != player)) return;

        sampleLocked(session, System.nanoTime());
        activeSession = null;
        if (!submit || !isEligible(session)) return;

        long playedSeconds = session.playedNanos / 1_000_000_000L;
        if (playedSeconds <= 0L) return;

        final Music song = session.song;
        final PlayList source = session.source;
        final int finalPlayedSeconds = (int) Math.min(Integer.MAX_VALUE, playedSeconds);
        final EndReason finalReason = reason;
        MultiThreadingUtil.runAsync(() -> {
            DownloadDynamicIsland.showNeteaseUploadStarted("听歌时长上报");
            NeteaseClientLogUploader.UploadResult result =
                    NeteaseClientLogUploader.uploadPlayDuration(song, source, finalPlayedSeconds, finalReason);
            if (result.isSuccess()) {
                DownloadDynamicIsland.showListeningDurationUploadSuccess(finalPlayedSeconds,
                        result.getElapsedMillis());
            } else {
                DownloadDynamicIsland.showListeningDurationUploadFailure(finalPlayedSeconds,
                        result.getElapsedMillis(), result.getMessage());
            }
        });
    }

    private static void sampleLocked(ActiveSession session, long now) {
        long elapsed = Math.max(0L, now - session.lastObservedNanos);
        session.lastObservedNanos = now;
        if (!session.player.isPausing()) {
            session.playedNanos += Math.min(elapsed, MAXIMUM_OBSERVED_STEP_NANOS);
        }
    }

    private static boolean isEligible(ActiveSession session) {
        return NCMPlayerConfig.isNeteaseListeningHistorySyncEnabled()
                && session.song.getId() > 0L
                && session.song.isNetease()
                && OptionsUtil.getCookie() != null
                && !OptionsUtil.getCookie().trim().isEmpty();
    }

    private static final class ActiveSession {
        private final Music song;
        private final PlayList source;
        private final AudioPlayer player;
        private long lastObservedNanos;
        private long playedNanos;

        private ActiveSession(Music song, PlayList source, AudioPlayer player, long now) {
            this.song = song;
            this.source = source;
            this.player = player;
            this.lastObservedNanos = now;
        }
    }
}
