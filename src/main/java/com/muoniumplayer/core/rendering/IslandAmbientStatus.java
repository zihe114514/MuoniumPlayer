package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.settings.HudConfig;

import java.util.Calendar;

/**
 * 常驻灵动岛的内容采样与状态判定。
 *
 * <p>拆成独立类有两个原因：一是把「读游戏状态」和「画灵动岛」分开，渲染层只消费不可变快照；
 * 二是这里的判定与格式化全是纯函数，可以离线探针验证，不必进游戏才能确认排队/常驻的交互。</p>
 *
 * <p>宽度模板是关键设计：FPS 与延迟每秒都在变，如果按真实字宽排版，灵动岛会一直微微伸缩。
 * 每个条目都按固定位数的模板预留宽度，数值居中绘制，于是常驻状态下宽度完全静止。</p>
 */
final class IslandAmbientStatus {

    /** 常驻条目类别。左侧小图标与单位都由类别决定。 */
    enum Kind {
        FPS,
        PING,
        CLOCK,
        /** 正在播放：歌名 + 歌手，左侧大图标换成封面。 */
        NOW_PLAYING
    }

    /** 常驻内容分页。轮换就是在这两页之间切换。 */
    enum Page {
        /** 帧率 / 延迟 / 时间。 */
        STATUS,
        /** 正在播放的歌曲。 */
        NOW_PLAYING
    }

    /** 数值健康度。渲染层据此选色，判定本身与主题、GL 无关，便于离线校验。 */
    enum Health {
        NEUTRAL,
        GOOD,
        WARN,
        BAD
    }

    /** 采样间隔。常驻内容不需要每帧刷新，250ms 已经比人眼分辨得出的更新更快。 */
    static final long SAMPLE_INTERVAL_MILLIS = 250L;

    private static final Chip[] NO_CHIPS = new Chip[0];
    /** 复用同一个 Calendar：常驻渲染每 250ms 才取一次时间，且只在渲染线程上使用。 */
    private static final Calendar CALENDAR = Calendar.getInstance();

    private IslandAmbientStatus() {
    }

    static final class Chip {
        final Kind kind;
        /** 实际绘制的数值文本。 */
        final String value;
        /** 只参与宽度计算的等位数模板，保证数值跳动时布局不动。 */
        final String widthTemplate;
        /** 数值后面的灰色单位，没有单位时为空串。 */
        final String unit;
        final Health health;

        /** 是否在数值左边画一枚小图标。正在播放页交给左侧封面，条目本身不再重复画图标。 */
        final boolean showIcon;

        private Chip(Kind kind, String value, String widthTemplate, String unit, Health health) {
            this(kind, value, widthTemplate, unit, health, true);
        }

        private Chip(Kind kind, String value, String widthTemplate, String unit, Health health,
                     boolean showIcon) {
            this.kind = kind;
            this.value = value;
            this.widthTemplate = widthTemplate;
            this.unit = unit;
            this.health = health;
            this.showIcon = showIcon;
        }
    }

    /** 一次采样的不可变结果。渲染线程只读，不会在绘制中途变化。 */
    static final class Snapshot {
        final Chip[] chips;
        final long sampledAt;
        /** 本次采样展示的是哪一页。 */
        final Page page;
        /** 玩家此刻是否在移动。状态页的左侧图标据此在心跳字形与跑步动画之间切换。 */
        final boolean moving;

        private Snapshot(Chip[] chips, long sampledAt) {
            this(chips, sampledAt, Page.STATUS, false);
        }

        private Snapshot(Chip[] chips, long sampledAt, Page page, boolean moving) {
            this.chips = chips;
            this.sampledAt = sampledAt;
            this.page = page;
            this.moving = moving;
        }

        boolean isEmpty() {
            return chips.length == 0;
        }

        boolean isNowPlaying() {
            return page == Page.NOW_PLAYING;
        }
    }

    static final Snapshot EMPTY = new Snapshot(NO_CHIPS, 0L);

    /** 最近一次判定为"在移动"的时刻。用于给移动状态加一点滞回，避免图标在原地抖着换。 */
    private static long lastMovingAt;

    /** 移动状态的滞回窗口：停下后仍按"移动"再显示这么久，跑步动画不会一步一停。 */
    static final long MOVING_HOLD_MILLIS = 450L;

    /*
     * 歌名/歌手的硬字数上限。
     *
     * 这两个数字只是防御异常元数据（整段简介塞进歌名之类）的最后一道闸，不再承担排版职责：
     * 灵动岛现在会自己伸长去装下全文（见 DynamicIslandLayoutCalculator.GROWTH_SCREEN_FRACTION），
     * 放不下时先缩字号。原来的 22 / 14 是按"卡在基础宽度上"定的，很多正常歌名刚过一半就被
     * 截成"前半段…"，宽度明明还有余量。
     */
    private static final int NOW_PLAYING_TITLE_LIMIT = 64;
    private static final int NOW_PLAYING_ARTIST_LIMIT = 40;

    /**
     * 按配置组装常驻条目。三个开关全关时返回空快照，灵动岛会退回紧凑胶囊而不是画一条空白行。
     *
     * @param fps        当前帧率，负数视为未知
     * @param ping       当前延迟毫秒，未知时配合 {@code pingKnown=false}
     * @param pingKnown  是否拿到了服务器延迟（单机/未进服时拿不到）
     */
    static Snapshot describe(boolean showFps, boolean showPing, boolean showClock,
                             int fps, int ping, boolean pingKnown, long epochMillis) {
        int count = (showFps ? 1 : 0) + (showPing ? 1 : 0) + (showClock ? 1 : 0);
        if (count == 0) return EMPTY;
        Chip[] chips = new Chip[count];
        int index = 0;
        if (showFps) {
            int safeFps = Math.max(0, Math.min(9999, fps));
            // 模板必须容得下真实数值，否则居中绘制会溢出预留槽压到下一个条目上。
            // 破千帧确实会发生（低画质小窗口），所以只在这时才升到四位模板。
            chips[index++] = new Chip(Kind.FPS, fps < 0 ? "--" : Integer.toString(safeFps),
                    safeFps >= 1000 ? "8888" : "888", "FPS",
                    fps < 0 ? Health.NEUTRAL : fpsHealth(safeFps));
        }
        if (showPing) {
            int safePing = Math.max(0, Math.min(9999, ping));
            chips[index++] = new Chip(Kind.PING, pingKnown ? Integer.toString(safePing) : "--",
                    "8888", "ms", pingKnown ? pingHealth(safePing) : Health.NEUTRAL);
        }
        if (showClock) {
            chips[index] = new Chip(Kind.CLOCK, formatClock(epochMillis), "88:88", "", Health.NEUTRAL);
        }
        return new Snapshot(chips, epochMillis);
    }

    /**
     * 组装"正在播放"页。没有歌曲时返回空快照，调用方据此跳过这一页。
     *
     * <p>宽度模板直接用真实文本（而不是等位数模板）：歌名不像帧率那样每秒都在跳，用真实宽度
     * 排版更好看；换歌时的宽度变化本来就该看得出来。</p>
     */
    static Snapshot describeNowPlaying(String title, String artist, long epochMillis, boolean moving) {
        String safeTitle = trimText(title, NOW_PLAYING_TITLE_LIMIT);
        if (safeTitle.isEmpty()) return EMPTY;
        String safeArtist = trimText(artist, NOW_PLAYING_ARTIST_LIMIT);
        // 歌手前面补一个分隔点：常驻行的"单位"槽间距只有 2.2 像素，直接接歌名会读成一个词。
        String artistLabel = safeArtist.isEmpty() ? "" : "· " + safeArtist;
        Chip[] chips = new Chip[] {
                new Chip(Kind.NOW_PLAYING, safeTitle, safeTitle, artistLabel, Health.NEUTRAL, false)
        };
        return new Snapshot(chips, epochMillis, Page.NOW_PLAYING, moving);
    }

    /** 截断到指定长度并补省略号。null / 空白一律返回空串。 */
    private static String trimText(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, Math.max(1, limit - 1)) + "…";
    }

    /**
     * 该显示哪一页。
     *
     * <p>刻意写成 {@code now} 的纯函数（不保存"上次翻页时刻"）：常驻岛可能被玩家列表、界面切换
     * 反复打断，用累计计时会在每次恢复时错一段相位。按绝对时间分片则无论中间中断多久，
     * 回来时都落在正确的节拍上。</p>
     *
     * @param intervalMillis 轮换间隔，非正数时按 6 秒兜底
     */
    static Page pageFor(long now, boolean statusAvailable, boolean nowPlayingAvailable,
                        boolean rotate, double intervalMillis) {
        if (!nowPlayingAvailable) return Page.STATUS;
        if (!statusAvailable) return Page.NOW_PLAYING;
        if (!rotate) return Page.STATUS;
        double interval = intervalMillis > 0.0 ? intervalMillis : 6000.0;
        long slot = (long) Math.floor(now / interval);
        return (slot & 1L) == 0L ? Page.STATUS : Page.NOW_PLAYING;
    }

    /** 玩家是否在移动。带滞回：停下后 {@link #MOVING_HOLD_MILLIS} 内仍算移动。 */
    static boolean sampleMoving(long now) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                lastMovingAt = 0L;
                return false;
            }
            double delta = Math.abs(mc.thePlayer.posX - mc.thePlayer.prevPosX)
                    + Math.abs(mc.thePlayer.posY - mc.thePlayer.prevPosY)
                    + Math.abs(mc.thePlayer.posZ - mc.thePlayer.prevPosZ);
            if (delta > 0.0035) lastMovingAt = now;
        } catch (Throwable ignored) {
            return false;
        }
        return lastMovingAt > 0L && now - lastMovingAt <= MOVING_HOLD_MILLIS;
    }

    /** 读取真实游戏状态。任何取值失败都退化成「未知」，绝不让常驻显示把渲染打断。 */
    static Snapshot capture(long now) {
        boolean moving = sampleMoving(now);
        Page page = pageFor(now, HudConfig.dynamicIslandAmbientFps || HudConfig.dynamicIslandAmbientPing
                        || HudConfig.dynamicIslandAmbientClock,
                HudConfig.dynamicIslandAmbientNowPlaying && hasCurrentTrack(),
                HudConfig.dynamicIslandAmbientRotate,
                HudConfig.dynamicIslandAmbientRotateSeconds * 1000.0);
        if (page == Page.NOW_PLAYING) {
            Snapshot nowPlaying = captureNowPlaying(now, moving);
            // 歌曲信息临时取不到（切歌瞬间）时退回状态页，而不是显示一张空卡片。
            if (!nowPlaying.isEmpty()) return nowPlaying;
        }
        return captureStatus(now, moving);
    }

    /** 当前是否有正在播放的歌曲。任何异常都当作"没有"。 */
    private static boolean hasCurrentTrack() {
        try {
            return com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Snapshot captureNowPlaying(long now, boolean moving) {
        try {
            com.muoniumplayer.core.ncm.music.dto.Music music =
                    com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying;
            if (music == null) return EMPTY;
            return describeNowPlaying(music.getName(), music.getArtistsName(), now, moving);
        } catch (Throwable ignored) {
            return EMPTY;
        }
    }

    private static Snapshot captureStatus(long now, boolean moving) {
        boolean showFps = HudConfig.dynamicIslandAmbientFps;
        boolean showPing = HudConfig.dynamicIslandAmbientPing;
        boolean showClock = HudConfig.dynamicIslandAmbientClock;
        if (!showFps && !showPing && !showClock) return EMPTY;
        int fps = -1;
        int ping = 0;
        boolean pingKnown = false;
        if (showFps) {
            try {
                fps = net.minecraft.client.Minecraft.getDebugFPS();
            } catch (Throwable ignored) {
                fps = -1;
            }
        }
        if (showPing) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
                if (mc != null && mc.thePlayer != null && mc.getNetHandler() != null) {
                    net.minecraft.client.network.NetworkPlayerInfo info =
                            mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                    if (info != null) {
                        ping = info.getResponseTime();
                        pingKnown = true;
                    }
                }
            } catch (Throwable ignored) {
                pingKnown = false;
            }
        }
        Snapshot status = describe(showFps, showPing, showClock, fps, ping, pingKnown, now);
        // describe 保持原签名（离线探针在用），移动状态在这里补进快照。
        return status.isEmpty() ? EMPTY : new Snapshot(status.chips, now, Page.STATUS, moving);
    }

    private static Health fpsHealth(int fps) {
        if (fps >= 90) return Health.GOOD;
        if (fps >= 45) return Health.NEUTRAL;
        if (fps >= 25) return Health.WARN;
        return Health.BAD;
    }

    private static Health pingHealth(int ping) {
        if (ping <= 60) return Health.GOOD;
        if (ping <= 120) return Health.NEUTRAL;
        if (ping <= 250) return Health.WARN;
        return Health.BAD;
    }

    private static String formatClock(long epochMillis) {
        CALENDAR.setTimeInMillis(epochMillis);
        int hour = CALENDAR.get(Calendar.HOUR_OF_DAY);
        int minute = CALENDAR.get(Calendar.MINUTE);
        return twoDigits(hour) + ":" + twoDigits(minute);
    }

    private static String twoDigits(int value) {
        int safe = Math.max(0, Math.min(99, value));
        return safe < 10 ? "0" + safe : Integer.toString(safe);
    }

    /**
     * 灵动岛是否应该可见。常驻只是多了一条「没有任何事件也留在屏幕上」的理由，
     * 下载/完成停留/通知这三条既有理由完全不变，所以排队机制的时序不受影响。
     */
    static boolean shouldShow(boolean enabled, boolean alwaysOn, boolean activeDownload,
                              boolean holdingCompletion, boolean activeNotice) {
        return enabled && (alwaysOn || activeDownload || holdingCompletion || activeNotice);
    }

    /** 常驻内容只在「确实无事可报」时出现，永远不会抢下载或通知的展示位。 */
    static boolean isAmbient(boolean enabled, boolean alwaysOn, boolean activeDownload,
                             boolean holdingCompletion, boolean activeNotice) {
        return enabled && alwaysOn && !activeDownload && !holdingCompletion && !activeNotice;
    }

    /**
     * 常驻且已经稳定停在屏幕上时，必须抑制入场动画：否则每来一条通知都会重播一次
     * 缩放入场，看起来像灵动岛在原地抽动。阈值取 .55 是为了让「刚打开常驻」那一次入场照常播完。
     */
    static boolean suppressEntry(boolean alwaysOn, float visibility) {
        return alwaysOn && visibility > .55f;
    }

    /** 常驻但三项内容全关时收成紧凑胶囊，而不是展开一条空白卡片。 */
    static double expansionTarget(boolean shouldShow, boolean ambient, boolean hasChips) {
        if (!shouldShow) return 0.0;
        return ambient && !hasChips ? 0.0 : 1.0;
    }
}
