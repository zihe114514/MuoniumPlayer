package com.muoniumplayer;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import today.opai.api.OpenAPI;
import today.opai.api.events.EventRender2D;
import today.opai.api.features.ExtensionModule;
import today.opai.api.features.ExtensionWidget;
import today.opai.api.impl.OpenAPIImpl;
import today.opai.api.impl.WindowResolutionImpl;
import today.opai.api.interfaces.EventHandler;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.MusicHotkeys;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.FfmpegNotice;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.screens.GuiFfmpegNotice;
import com.muoniumplayer.core.screens.hud.GuiHudEditor;
import com.muoniumplayer.core.screens.hud.GuiHudEditorPro;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * Forge 1.8.9 Mod 入口。
 *
 * 历史客户端扩展通过 Opai 客户端的 OpenAPI 加载；
 * 独立 Mod 化后，本类承担：
 *  1. @Mod 注解让 Forge 识别并加载本 mod；
 *  2. preInit 阶段注入 OpenAPIImpl 单例，并初始化 MuoniumPlayerExtension；
 *  3. 注册可在「选项 → 控制」中自定义的按键绑定；
 *  4. 把 Forge 的 TickEvent / RenderGameOverlayEvent 桥接到 OpenAPI 的事件系统；
 *  5. 在 GuiScreen 绘制完成后渲染全局下载灵动岛，使其不依赖播放器界面。
 */
@Mod(
        modid = MuoniumPlayerMod.MOD_ID,
        name = MuoniumPlayerMod.MOD_NAME,
        version = MuoniumPlayerMod.MOD_VERSION,
        clientSideOnly = true
)
public class MuoniumPlayerMod {

    public static final String MOD_ID = "muonium";
    public static final String MOD_NAME = "MuoniumPlayer";
    public static final String MOD_VERSION = "1.4.0";

    /** 按键分类名，在「选项 → 控制」中作为分组标题显示。 */
    public static final String KEY_CATEGORY = "key.categories.muonium";

    // ==================== 可配置按键绑定 ====================
    /** 打开网易云音乐主界面，默认右 Shift。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyOpenMusic;
    /** 打开 HUD 位置/缩放编辑器，默认 H。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyEditHud;
    /** 切换到上一曲，默认 Page Up。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyPreviousTrack;
    /** 切换到下一曲，默认 Page Down。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyNextTrack;
    /** 增加播放器音量，默认数字键盘 +。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyVolumeUp;
    /** 降低播放器音量，默认数字键盘 -。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyVolumeDown;
    /** 暂停 / 继续播放，默认数字键盘 *。可在「选项 → 控制」中修改。 */
    public static KeyBinding keyTogglePause;

    @Mod.Instance(MOD_ID)
    public static MuoniumPlayerMod instance;

    private OpenAPIImpl api;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 1. 注入 OpenAPI 单例（必须在任何引用 Extension.getAPI() / SharedConstants.api 的类加载之前完成）
        this.api = new OpenAPIImpl();
        OpenAPI.setInstance(this.api);

        // 2. 初始化 MuoniumPlayer 音乐扩展（注册事件处理器、模块、Widget）
        MuoniumPlayerExtension.getInstance().init(this.api);

        // 3. 注册可配置按键绑定（出现在「选项 → 控制 → MuoniumPlayer」分组下）
        keyOpenMusic = new KeyBinding("key.muonium.open_music", Keyboard.KEY_RSHIFT, KEY_CATEGORY);
        keyEditHud = new KeyBinding("key.muonium.edit_hud", Keyboard.KEY_H, KEY_CATEGORY);
        // Page Up / Page Down avoid collisions with the vanilla movement and inventory keys,
        // while remaining fully rebindable through Options -> Controls.
        keyPreviousTrack = new KeyBinding("key.muonium.previous_track", Keyboard.KEY_PRIOR, KEY_CATEGORY);
        keyNextTrack = new KeyBinding("key.muonium.next_track", Keyboard.KEY_NEXT, KEY_CATEGORY);
        keyVolumeUp = new KeyBinding("key.muonium.volume_up", Keyboard.KEY_ADD, KEY_CATEGORY);
        keyVolumeDown = new KeyBinding("key.muonium.volume_down", Keyboard.KEY_SUBTRACT, KEY_CATEGORY);
        // 和音量的小键盘 +/- 放在一起，构成一组"媒体键"；原版没有占用小键盘 *，因此默认不冲突。
        keyTogglePause = new KeyBinding("key.muonium.toggle_pause", Keyboard.KEY_MULTIPLY, KEY_CATEGORY);
        ClientRegistry.registerKeyBinding(keyOpenMusic);
        ClientRegistry.registerKeyBinding(keyEditHud);
        ClientRegistry.registerKeyBinding(keyPreviousTrack);
        ClientRegistry.registerKeyBinding(keyNextTrack);
        ClientRegistry.registerKeyBinding(keyVolumeUp);
        ClientRegistry.registerKeyBinding(keyVolumeDown);
        ClientRegistry.registerKeyBinding(keyTogglePause);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Forge 1.8.9 使用两条独立事件总线：
        // - MinecraftForge.EVENT_BUS：HUD、GuiScreen 等 Forge 客户端事件；
        // - FMLCommonHandler bus：ClientTickEvent 等 FML 游戏循环事件。
        // 只注册 Forge EVENT_BUS 会导致 onClientTick 永远不执行，按键和模块循环因此看起来像“Mod 未加载”。
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);

        // 动态封面与 B 站音频解析都要外部 ffmpeg。这里只是在守护线程上探一次并把结论缓存下来：
        // 缺少时由 GuiFfmpegNotice 在主菜单弹一次全屏提示，同时也让 HUD 编辑器的「未检测到 ffmpeg」
        // 标签在用户放第一首歌之前就有结论。探测会创建子进程，绝不能放在主线程上。
        FfmpegNotice.beginProbe();
    }

    // ==================== Forge 事件桥接 ====================

    /**
     * 客户端 Tick：
     *  - 驱动 OpenAPI EventHandler.onLoop()
     *  - 驱动所有模块（含模块自身的 eventHandler）的 onTick()
     *  - 检测可配置按键绑定（打开音乐界面 / HUD 编辑器）
     * 只在 END 阶段触发一次，避免每 tick 重复执行。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // ---- 驱动所有注册的 EventHandler（如 MuoniumPlayerEventHandler 的调度队列）----
        for (EventHandler handler : this.api.getEventHandlers()) {
            handler.onLoop();
        }

        // ---- 驱动所有模块：onTick + 模块自身 eventHandler 的 onLoop ----
        for (ExtensionModule module : this.api.getModules()) {
            // 模块自身的 EventHandler（MusicInfoWidget / MusicLyricsWidget / OpenNCMScreen 均 setEventHandler(this)）
            EventHandler moduleHandler = module.getEventHandler();
            if (moduleHandler != null) {
                moduleHandler.onLoop();
            }
            // 启用的模块执行 onTick（OpenNCMScreen.onTick 会自动 setEnabled(false)，实现按钮式触发）
            if (module.isEnabled()) {
                module.onTick();
            }
        }

        // ---- 缺少 ffmpeg 的一次性全屏提示 ----
        // 每 tick 问一次而不是在 GuiOpenEvent 里换掉主菜单：探测在后台线程上跑，主菜单出现的那一刻
        // 结论往往还没出来，换 GUI 只有一次机会，轮询则下一 tick 再问一次就行。内部自带
        // 「本次启动只弹一次」与「永不展示」两道闸，条件不满足时只是一次布尔判断。
        GuiFfmpegNotice.tickAutoShow(mc);

        // ---- 可配置按键检测（仅在未打开 GUI 时响应，避免与界面内输入冲突）----
        // isPressed() 为上升沿触发：按下一次返回 true 一次，按住不重复，无需手动维护上一帧状态。
        if (mc.thePlayer != null && mc.currentScreen == null) {
            if (keyOpenMusic.isPressed()) {
                MuoniumPlayerExtension.getInstance().musicPlayerModule.setEnabled(true);
            }
            if (keyEditHud.isPressed()) {
                // 打开哪一套 HUD 设置界面由配置决定；两套界面里都有互相切换的按钮，
                // 点了之后写回 HudConfig.hudEditorModernUi，所以这里读到的总是用户最后一次的选择。
                mc.displayGuiScreen(HudConfig.hudEditorModernUi
                        ? new GuiHudEditorPro() : new GuiHudEditor());
            }

            // Reuse the exact player-control path used by the previous/next buttons.
            // isPressed() is edge-triggered, so holding a key never queues repeated track changes.
            if (keyPreviousTrack.isPressed()
                    && CloudMusic.player != null
                    && CloudMusic.currentlyPlaying != null) {
                CloudMusic.prev();
            }
            if (keyNextTrack.isPressed()
                    && CloudMusic.player != null
                    && CloudMusic.currentlyPlaying != null) {
                CloudMusic.next();
            }

            // The same persisted audio path is used by both player sliders, so a
            // hotkey change survives track switches and reports its exact percentage.
            if (keyVolumeUp.isPressed()) {
                CloudMusic.adjustVolume(.05f);
            }
            if (keyVolumeDown.isPressed()) {
                CloudMusic.adjustVolume(-.05f);
            }

            // 暂停 / 继续与播放器界面的空格键共用同一条 CloudMusic.togglePlayPause()，反馈也在那里统一给出。
            if (keyTogglePause.isPressed()) {
                MusicHotkeys.togglePlayback();
            }
        }
    }

    /**
     * HUD 渲染：
     *  - 驱动 OpenAPI EventHandler.onRender2D()
     *  - 驱动所有模块自身 eventHandler 的 onRender2D()
     *  - 驱动满足条件的 Widget.render()
     * 使用 TEXT 阶段之后，确保在所有原版 HUD 元素之上渲染。
     */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        // GuiScreen 使用 DrawScreenEvent.Post 单独绘制全局灵动岛，避免同一帧重复更新动画。
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT
                || Minecraft.getMinecraft().currentScreen != null) {
            return;
        }

        // HUD 内部会使用 Shader、纹理和 alpha test 绘制圆角卡片/歌词。部分 Shader
        // （例如 RQShader）为了绘制无纹理几何体会关闭 GL_ALPHA_TEST；如果不恢复，
        // 后续 Minecraft FontRenderer 绘制的字形纹理会把透明背景当成不透明矩形，
        // 从而导致计分板和其他 Mod 的文字显示为实心方块。
        //
        // 在整个扩展 HUD 边界建立 OpenGL 状态隔离，而不是修改 Minecraft 的
        // FontRenderer 或字体纹理路径。这样所有 HUD/歌词实现仍可保持原有行为，
        // 但不会把全局状态泄漏给同帧后续的原生文字渲染。
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        api.getGLStateManager().pushMatrix();
        try {
            EventRender2D renderEvent = new EventRender2D(new WindowResolutionImpl());

            // 驱动所有注册的 EventHandler 的 2D 渲染
            for (EventHandler handler : this.api.getEventHandlers()) {
                handler.onRender2D(renderEvent);
            }

            // 驱动所有模块自身 eventHandler 的 2D 渲染
            for (ExtensionModule module : this.api.getModules()) {
                EventHandler moduleHandler = module.getEventHandler();
                if (moduleHandler != null) {
                    moduleHandler.onRender2D(renderEvent);
                }
            }

            // 驱动所有满足渲染条件的 Widget（歌曲信息卡片 / 歌词等）
            for (ExtensionWidget widget : this.api.getWidgets()) {
                if (widget.renderPredicate()) {
                    widget.render();
                }
            }
        } finally {
            api.getGLStateManager().popMatrix();
            GL11.glPopAttrib();
        }
    }

    /**
     * GuiScreen 顶层覆盖：灵动岛不再由 NCMScreen 调用，因此玩家打开播放器、背包或其他界面时
     * 都使用同一套全局坐标和尺寸。HUD 编辑器已经绘制专用实时预览，避免重复叠加。
     */
    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        RenderSystem.refreshResolution();
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();

        // 两套 HUD 设置界面都自己画灵动岛的专用预览，这里再画一次会叠成双层。
        if (!(event.gui instanceof GuiHudEditor) && !(event.gui instanceof GuiHudEditorPro)) {
            DownloadDynamicIsland.render();
        }
    }
}


