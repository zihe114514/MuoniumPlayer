package com.muoniumplayer.core.rendering;

/**
 * 灵动岛"在什么场合可以出现"的判定。
 *
 * <p>拆成独立类的理由和 {@link IslandAmbientStatus} 一样：判定本身是纯函数，可以离线验证；
 * 读取 Minecraft 状态的那一小段单独放在 {@link #capture()} 里，渲染层只消费结果。</p>
 */
final class IslandVisibilityGate {

    /** 一次采样的场合信息。字段全部只读。 */
    static final class Scene {
        /** 是否已经进入世界（有世界且有玩家实体）。主菜单、连接中、断线界面都为 false。 */
        final boolean inWorld;
        /** 玩家列表（Tab）是否正在显示。它和灵动岛都占据屏幕顶部中央。 */
        final boolean playerListVisible;
        /** 播放器界面（含全屏歌词）是否打开。 */
        final boolean playerUiOpen;

        Scene(boolean inWorld, boolean playerListVisible, boolean playerUiOpen) {
            this.inWorld = inWorld;
            this.playerListVisible = playerListVisible;
            this.playerUiOpen = playerUiOpen;
        }
    }

    static final Scene UNKNOWN = new Scene(true, false, false);

    private IslandVisibilityGate() {
    }

    /**
     * 灵动岛表面是否允许出现。
     *
     * <p>两条限制：<b>没进世界不画</b>——主菜单、多人列表、连接界面属于"游戏外"，那里弹出一张
     * 播放器卡片既突兀又会盖住原版按钮；<b>玩家列表打开时不画</b>——两者都固定在屏幕顶部中央，
     * 一定会互相遮挡，与其争图层不如让临时按住 Tab 的那几秒把顶部让给玩家列表。</p>
     */
    static boolean allowSurface(Scene scene) {
        if (scene == null) return true;
        return scene.inWorld && !scene.playerListVisible;
    }

    /**
     * 常驻状态栏是否允许出现。播放器界面（含全屏歌词）里一律不允许：那里本来就在显示歌曲信息、
     * 封面与歌词，再挂一条常驻状态栏纯属重复，还会压住全屏歌词的顶部。通知不受这条限制，
     * 因为通知是"刚刚发生了什么"的即时反馈，在播放器里同样需要看到。
     */
    static boolean allowAmbient(Scene scene) {
        return scene == null || !scene.playerUiOpen;
    }

    /** 读取真实场合。任何取值异常都退化成"允许显示"，绝不因为判定失败把灵动岛整个弄没。 */
    static Scene capture() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null) return UNKNOWN;
            boolean inWorld = mc.theWorld != null && mc.thePlayer != null;
            boolean playerList = false;
            if (inWorld && mc.gameSettings != null && mc.gameSettings.keyBindPlayerList != null) {
                // 只看按键是否按住：原版就是按住 Tab 才渲染玩家列表。
                playerList = mc.gameSettings.keyBindPlayerList.isKeyDown();
            }
            boolean playerUi = mc.currentScreen instanceof com.muoniumplayer.core.screens.ncm.NCMScreen;
            return new Scene(inWorld, playerList, playerUi);
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }
}
