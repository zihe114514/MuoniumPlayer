# MuoniumPlayer 移植交接文档：1.8.9 Forge → 1.20.1 Fabric

## 致接手 AI：任务卡（先读完这一节再动手）

**你的任务**：把本项目从 Minecraft 1.8.9 Forge 移植到 **1.20.1 Fabric**，功能与视觉效果一比一保持。

**起点状态**（实测，可能已过期，自己用 `git log -1 --oneline` 和 `git status --short` 复核）：
- 分支 `main`，`HEAD = ccf5135`，版本号 `1.3.0`（`build.gradle` 与 `MuoniumPlayerMod.MOD_VERSION` 两处）。
- 工作区有 12 项未提交改动（上一批灵动岛音量图标 + 苹果歌词样式），**不是你的，别回滚也别顺手提交**。
- 阶段 0 尚未开始。任何 Fabric 工程都还没建。

**硬约束（违反即任务失败）**：
1. **不改功能与效果**。第 11 节是逐条可验收的红线清单，移植完成的定义就是那些条目全部实测通过。
2. **一次只改一个文件，改完立刻编译 + 验证**。不许批量改几十个文件再一起编译。
3. **绝不以未测试代码收尾**。每轮结束前必须跑过第 12 节的编译流程；能离线断言的必须写探针断言。
4. **不许猜 API**。1.20.1 / Fabric / Loom 的类名、方法签名、Fabric API 模块名一律实测确认（解包 jar、查官方文档、看 Loom 生成的源码），**不许凭记忆写**。本文里的 API 名字也要当作待核实的线索，不是权威。
5. **不许扩大范围**。移植期间不接新需求、不做性能优化、不重命名、不重排代码、不"顺手清理"。Java 8→17 能用的新语法一律留到移植完成后。
6. **用户说"不要动 X"就绝对不动**，包括 OSD 桌面歌词样式这类明确点名过的部分。
7. **只有用户明确要求才 commit / push / 改版本号**。按"一个文件一个提交"的粒度组织改动，但提交时机由用户决定。
8. **本文的所有数字都可能过期**，动手前先跑附录 A 的命令自己核实。

**每轮工作循环**：
```
读代码搞清现状 → 说清计划 → 改一个文件 → 编译（第 12 节两段式）
→ 探针断言 / 让用户进游戏验证 → 按下面格式汇报 → 下一个文件
```

**汇报格式**（用户明确要求过）：
- 分「**已验证**」与「**只能进游戏 / 需真实账号验证**」两栏。
- 说明「只有 javac，没跑 lint」。
- **用户没要求但你顺带改了的任何可见变化，必须显式点名**，并说明可单独回退。
- 修 bug 必须先用探针或实测数据证明真根因，不许"改了看看好不好"。

**反幻觉要求**：报告里出现的每个数字、每个 API 名、每个文件路径，都必须来自你这一轮实际跑过的命令或读过的文件。没验证的就写"未验证"。

---

> 以下是移植方案本体。所有数字都是在 `main` 分支上实测出来的，不是估算。
> 基线版本：`1.3.0`，源树 `F:\donked\Tritium1.8.9-f84613f77b4191420a09da289503c8b64b33fe30`。

## 0. 目标与硬约束

**目标**：把项目搬到 Minecraft 1.20.1 + Fabric，**功能与视觉效果一比一保持**。

"不影响功能和效果"在本项目里是可验收的，不是主观判断。它指：

1. 所有界面的**像素级观感**不变：字体粗细/清晰度、圆角半径、发光半径、动画时长与曲线、灵动岛伸缩节奏。
2. 所有**业务行为**不变，特别是第 11 节列的那批不变量（它们大多是用户反复提过的要求，改坏了就是回归）。
3. 所有**已验证过的几何常量不许"顺手调"**：项目里带注释的魔数（例如图标 em 方格、`.35em` 墨迹中心、`getFontHeight()` 的 `-8.5`）都是实测结论，换渲染后端不改变它们的含义。

**不在本次范围内**：新功能、UI 重设计、性能优化（除渲染后端本身带来的）。移植期间不接需求。

## 1. 结论：路线

```
阶段 0  留在 1.8.9：把 MC / GL 依赖全部收口到门面后面     ← 唯一不能省的投资
阶段 1  1.20.1 Fabric：只让 mod 加载 + 业务层跑通，不画 UI
阶段 2  门面最小实现：矩形 + 贴图能画对
阶段 3  字体图集（GL_RED + shader）
阶段 4  stencil / 后处理 / 逐界面开回来
```

**为什么不经过 1.16.5**：1.16.5 的红利只有"GL 代码零改动"，但那只是把渲染重写推迟，代价是多付一整套 loader + Gradle + 适配层迁移。隔离变量靠阶段 0~4 的模块化推进即可，不必用一个不打算发布的中间版本去买。

**为什么是 Fabric**：见第 9 节的对照表。一句话是——项目需要的 hook Fabric API 全部现成（不用写 mixin），映射跨版本稳定所以以后往 1.21 / 26.x 走最便宜，而 Forge 的 registry / capability / 网络那套本项目一样都用不到（`clientSideOnly = true`）。**代价是 stencil 没有官方 API，见第 8 节。**

## 2. 现状实测：耦合面有多小

```
718 个 java 文件 / 134,561 行
import net.minecraft 的：仅 14 个文件
直接调 org.lwjgl.opengl 的：48 个文件
javax.sound 音频链：14 个文件（与 MC 完全无关，零移植成本）
```

碰 `net.minecraft` 的 14 个文件，分三类：

| 类别 | 文件 | 处理方式 |
|---|---|---|
| 门面实现 | `today/opai/api/impl/{OpenAPIImpl,FontUtilImpl,VanillaFont,WindowResolutionImpl}`、`api/features/ExtensionScreen` | **重写**（阶段 2） |
| 中文输入 coremod | `lain/mods/inputfix/*`（5 个文件，含 `IClassTransformer`） | **整包删除**：1.13+ 原版自带 IME |
| 入口与 Screen | `MuoniumPlayerMod`、`GuiFfmpegNotice`、`GuiHudEditor`、`GuiHudEditorPro` | **重写入口**，3 个 Screen 换基类 |

另外 `core/rendering/Framebuffer.java:400` 有 `ensureMinecraftStencilAttachment(net.minecraft.client.shader.Framebuffer)`，直接给 MC 主帧缓冲挂 stencil —— 这是第 8 节的专项。

## 3. 移植边界：`today.opai.api.OpenAPI`

**项目不依赖 OpAI 客户端**，那个包名是历史遗留。`OpenAPI` 是抽象类，`MuoniumPlayerMod` 第 87-88 行自己注入实现：

```java
this.api = new OpenAPIImpl();
OpenAPI.setInstance(this.api);
```

业务侧只通过 `OpenAPI.getInstance()` 和 17 个类型接触它，用得最多的是 `EventHandler`（6 个文件）、`OpenAPI`（5）、`ExtensionModule`（5）、`ExtensionWidget`（4）、`EventRender2D`（3）以及几个 value 类型。**这就是移植边界**：业务代码一行不用改。

需要重写的实现层（共 666 行，`today/opai/api/impl/`）：

| 文件 | 行数 | 碰 mc | 直接 GL | 移植难度 |
|---|---|---|---|---|
| `GLStateManagerImpl` | 214 | 0 | 40 | **高**（见第 7 节 blend/state 一致性） |
| `OpenAPIImpl` | 101 | 2 | 0 | 低（事件注册 + 聊天输出） |
| `RenderUtilImpl` | 25 | 0 | 12 | 中 |
| `WindowResolutionImpl` | 29 | 2 | 0 | 低（`ScaledResolution` → `Window`） |
| `FontUtilImpl` / `VanillaFont` | 18 / 28 | 1 / 1 | 0 | 低 |
| 6 个 value 实现 | 237 | 0 | 0 | **零**（纯数据，直接搬） |
## 4. 阶段 0（留在 1.8.9，必须先做完）

目的：让 1.20.1 上要改的地方从"48 个文件"缩到"一个门面的内部实现"。每一条都能在 1.8.9 上编译 + 进游戏验证 + 单独提交回滚。

**0.1 给 immediate mode 造抽象**
项目现在有 9 个文件、155 处 `glBegin/glVertex/glTexCoord`。在 `core/rendering/rendersystem/` 下加一个 `VertexSink`（自己的 Tessellator）：`begin(mode, format)` / `pos(x,y)` / `tex(u,v)` / `color(argb)` / `end()`。1.8.9 的实现直接转发 `GL11`，1.20.1 的实现转发 `BufferBuilder`。改完这 9 个文件后，全项目不再出现 `glBegin`。

按 GL 调用密度排的工作面（前 12 个占了绝大多数）：

```
82  core/rendering/Image.java                     ← 最大单点
46  core/rendering/Framebuffer.java
40  today/opai/api/impl/GLStateManagerImpl.java
36  core/rendering/font/CFontRenderer.java
34  core/rendering/StencilClipManager.java
34  core/rendering/rendersystem/RenderSystem.java
33  core/screens/hud/GuiHudEditorPro.java
30  core/screens/hud/GuiHudEditor.java
27  core/rendering/shader/ShaderProgram.java
26  core/rendering/DownloadDynamicIsland.java
22  core/screens/ncm/ThemeTransitionRenderer.java
19  core/rendering/OpenGlHelper.java
```
（完整 48 个文件用 `Get-ChildItem -Recurse -Filter *.java src\main\java | Select-String -Pattern 'org\.lwjgl\.opengl'` 复现。）

**0.2 `GL_QUADS` → 三角形**
5 个文件 11 处。`CFontRenderer` 已经是 `GL_TRIANGLES`，照它改。core profile 没有 quads。

**0.3 删掉显示列表路径**
`glGenLists/glNewList/glCallList/glDeleteLists` 共 4 个文件 12 处，而 `CFontRenderer` 里 `boolean callLists = false` 已经把这条路关了。直接删死代码，别带到新版本。

**0.4 `glAlphaFunc` / `GL_ALPHA_TEST` 收口**
7 个文件 19 处。core profile 没有 alpha test，等价物是 shader 里 `discard`。阶段 0 先把它们收进门面的一个 `setAlphaCutoff(float)`，1.8.9 实现转发 `glAlphaFunc`。

**0.5 `glColor4f/3f` 收口**
7 个文件 19 处 → 统一走顶点色或门面的 `setShaderColor`。

**0.6 矩阵栈收口**
`glOrtho/glMatrixMode/glLoadIdentity`（2 文件 7 处）+ `glPushMatrix`（6 文件 11 处）全部走 `GLStateManager` 的 push/pop/translate/scale/rotate。1.20.1 换成 `PoseStack`。注意：项目大量用 `api.getGLStateManager().scale(...)` 配合 `scaleAtPos`，这些调用点**不需要改**，只改门面内部。

**0.7 `GLStateManagerImpl` 改成"可切换后端"**
它现在 40 处直连 GL、完全绕开 MC 的状态管理。1.8.9 上这样没事（vanilla `GlStateManager` 缓存很浅），**1.20.1 上会炸**：`com.mojang.blaze3d.systems.RenderSystem` 缓存 blend / depth / texture 状态，你在它背后改 GL，它下次以为状态没变就不重设，表现是别的 UI 或世界渲染莫名花屏，且极难定位。阶段 0 就把这 40 处按语义收敛成有限几个方法（enableBlend / blendFunc / depthMask / bindTexture / …），1.20.1 的实现逐个转发给 `RenderSystem`。

**0.8 验收**：阶段 0 做完后，`Select-String -Pattern 'org\.lwjgl\.opengl'` 命中的文件应该只剩 `core/rendering/**`（门面与其实现）+ `shader/**`，业务与界面代码里为 0。进游戏逐界面对比，观感必须与阶段 0 之前完全一致。

## 5. 阶段 1：Fabric 骨架 + 业务层跑通（不画 UI）

- 新建 Loom 工程，**推荐用 Mojang 官方映射（mojmap）而不是 yarn**：将来若要再加一份 Forge/NeoForge 实现，两边类名一致，`common` 模块能直接共享。本文后面的类名都是 mojmap。
- 入口 `ClientModInitializer#onInitializeClient` 里做原 `preInit`/`init` 的事：注入 `OpenAPIImpl`、初始化 `MuoniumPlayerExtension`、注册按键、注册事件桥。
- 把 718 个 java 文件整体搬过去，`lain.mods.inputfix` 五个文件删掉，`mcmod.info` 换成 `fabric.mod.json`。
- 门面实现全部先返回空/no-op，让 UI 不画。
- **验收**：进游戏不崩；能登录网易云 / QQ / 酷狗、能搜索、能播歌、歌词能解析（看日志与音频输出）。这一步会证明 13.5 万行业务代码与 MC 无关。

**资源路径这里有个好消息**：除了 `assets/muonium/lang`，其余资源都在 `src/main/resources/muonium/**`（fonts / shaders / textures / yrc），代码用 classpath 直接读（`/muonium/fonts/xxx.ttf`），**不走 `ResourceLocation` 和资源包系统**，所以 1.13 的扁平化与后续资源系统变化对它们零影响。保持这个做法，不要"顺手改成标准资源路径"。

**文件路径**：`new File("NCMCookie.txt")`、`new File("MusicCache")`、`new File("config","muonium")` 都是相对工作目录（`.minecraft`），Fabric 下行为一致。建议改成 `FabricLoader.getInstance().getGameDir()` 拼接，但**必须保持同一个落盘位置**，否则用户的 cookie / 缓存 / 歌词缓存全丢。
## 6. 阶段 2~4：渲染后端

### 阶段 2：能画对矩形和贴图
实现 `GLStateManagerImpl`（转发 `RenderSystem`）、`VertexSink`（转发 `BufferBuilder` + `GameRenderer.getPositionColorShader()` / `getPositionTexColorShader()`）、`WindowResolutionImpl`（`Minecraft.getInstance().getWindow().getGuiScaledWidth/Height()`）。
**验收**：灵动岛的圆角胶囊、进度条、封面贴图位置与 1.8.9 截图逐像素比对。

### 阶段 3：字体图集
这是**最大的技术风险**，先读第 7 节的 `GL_ALPHA` 条目。项目有 20 多个 `CFontRenderer` 实例，每个持一张 2048² 图集。
`CFontRenderer` 的几何契约必须原样保留（换后端不改这些）：
- `create(N, name)` → AWT 字号 N，落屏 N×0.5 px；
- 图集单元 = `ceil(advance + max(0,-lsb))` × `(ascent+descent)`，字形基线画在 `ascent`；
- `getStringWidthD()` = 单元宽/2；`getFontHeight()` = (单元高 − 8.5)/2；`drawString` 内部把 y 抬 2px；
- 图集是 2 倍超采样、落屏固定乘 0.5，**任何额外倍率都会重采样发虚** —— 图标字体因此按"落屏边长 × 2"选字号。

**验收**：用 `island-volume-icons`（U+E902~E906）当基准，重跑一次墨迹几何断言（历史探针见第 12 节），墨迹中心落点误差必须 < 0.5 屏幕像素。

### 阶段 4：stencil、后处理、逐界面
12 个 GLSL 文件（`muonium/shaders/`：bloom / blur / deconverge / stencil / rq* / roq / vf_fadeout / vertex.vsh）目前是 **GLSL 120 写法**（`varying`、`attribute`、`texture2D`、`gl_FragColor`，且只有 1 个文件写了 `#version`）。全部要改成 150 core：`in/out`、`texture()`、显式 `out vec4`，并补 `#version 150 core`。
项目已有自己的 `ShaderProgram` / `ShaderCompiler` / `Uniform*` 体系（27+15 处 GL 调用），**建议继续自己管 program，不要改用 MC 的 `ShaderInstance` + json**：自管的迁移面小，且 `ShaderInstance` 的顶点格式约束反而更麻烦。

## 7. Core profile 债务清单（1.17 起，编译能过、运行必崩/必黑）

| 项目里的东西 | 实测用量 | 1.20.1 对策 |
|---|---|---|
| `glBegin/glVertex/glTexCoord` | 9 文件 155 处 | `BufferBuilder` + 内置 shader（阶段 0 已收口） |
| `GL_QUADS` | 5 文件 11 处 | 拆三角形 |
| **`GL_ALPHA` 纹理** | 9 文件 17 处 | **`GL_RED` + shader 取 `.r` 当 alpha**。不能图省事换 `GL_RGBA`：2048² × 4 字节 × 20 多档字体 ≈ 300MB+ 显存 |
| `glAlphaFunc` / `GL_ALPHA_TEST` | 7 文件 19 处 | shader 里 `discard` |
| `glColor4f/3f` | 7 文件 19 处 | 顶点色 / `RenderSystem.setShaderColor` |
| 显示列表 | 4 文件 12 处 | 删（已是死代码） |
| `glOrtho/glMatrixMode/glLoadIdentity` | 2 文件 7 处 | `PoseStack` + `RenderSystem.setProjectionMatrix` |
| `Framebuffer`（自实现 + vanilla） | 17 文件 358 处 | `com.mojang.blaze3d.pipeline.RenderTarget` |
| `glStencil` | 2 文件 20 处 | 见第 8 节 |
| `OpenGlHelper`（自实现） | 19 处 | 直接用 `GL20`/`RenderSystem`，删掉这个兼容层 |
| GLSL 120 | 12 个文件 | 150 core |

## 8. 专项：stencil 在 Fabric 上没有官方 API

`core/rendering/Framebuffer.java:400` 现在的做法是给 **MC 主帧缓冲**挂 stencil 附件（`ensureMinecraftStencilAttachment`）。Forge 有官方口子（`enableStencilBits`），**Fabric 没有**。三条路，按推荐顺序：

1. **自建带 stencil 的 `RenderTarget`**（推荐）：`StencilClipManager` 需要裁剪时切到自己的 target，画完再合回主 target。改动集中在 `StencilClipManager`（34 处 GL）+ `Framebuffer`，与 MC 主 target 解耦，最干净，也顺带解决"别的 mod 也想改主 target"的冲突。
2. **mixin 改 `RenderTarget` 初始化**，给 main target 加 stencil renderbuffer。改动最小，但与其他 mod 冲突风险高。
3. **退回 `ScissorClipManager`**：项目已经有这条路径（11 处 GL）。只能做矩形裁剪，**会丢圆角裁剪效果，属于"影响效果"，只能当临时降级**，不可作为最终方案。

决定用哪条之前，先确认 stencil 现在实际服务于哪些效果（圆角裁剪？主题过渡？全屏歌词遮罩？），把受影响的界面列出来再选。

## 9. Forge → Fabric hook 对照

| 现在（Forge 1.8.9） | 1.20.1 Fabric | 备注 |
|---|---|---|
| `@Mod` + `FMLPreInitializationEvent` | `ClientModInitializer#onInitializeClient` | `clientSideOnly` → `fabric.mod.json` 的 `"environment": "client"` |
| `RenderGameOverlayEvent` | `HudRenderCallback.EVENT`（fabric-rendering-v1） | 1.20.1 回调收 `GuiGraphics` + tickDelta |
| `GuiScreenEvent`（Screen 画完叠灵动岛） | `ScreenEvents.AFTER_RENDER`（fabric-screen-api-v1） | 比 Forge 那套更干净，正好满足"灵动岛不依赖播放器界面" |
| `TickEvent` | `ClientTickEvents.END_CLIENT_TICK` | |
| `ClientRegistry.registerKeyBinding` | `KeyBindingHelper.registerKeyBinding`（fabric-key-binding-api-v1） | 用户要求快捷键留在原版「选项 → 控制」里，Fabric 同样在那里 |
| `GuiScreen` | `Screen`，`render(GuiGraphics, int, int, float)` | 3 个界面：`GuiFfmpegNotice` / `GuiHudEditor` / `GuiHudEditorPro` |
| `ScaledResolution` | `Minecraft.getInstance().getWindow()` | |
| `ChatComponentText` | `Component.literal(...)` | |
| `IClassTransformer`（InputFix） | 删除 | 1.13+ 原版支持 IME |
## 10. 工具链迁移

| 现在 | 1.20.1 Fabric |
|---|---|
| Gradle 4.6 + ForgeGradle 2.1-SNAPSHOT | Gradle 8.x + Fabric Loom 1.5+ |
| Java 8（`sourceCompatibility = 1.8`） | **Java 17** |
| `mappings = "stable_20"`（MCP） | Mojang 官方映射（mojmap），理由见阶段 1 |
| shadow 2.0.4 + relocation（zxing / jna 迁到 `repackage/`） | 继续用 shadow；relocation 仍然要保留，避免和其他 mod 撞库 |
| `libs/kotlin-stdlib-2.4.0.jar` + `prepareForgeCompatibleKotlinStdlib`（过滤 `META-INF/versions/**` 绕开 Forge 1.8.9 的 ASM5） | **直接用官方 kotlin-stdlib，删掉整个 hack 任务** |
| `libs/zstd-jni-1.5.6-9-forge189.jar`（定制包） | 换官方 zstd-jni |
| `libs/music-api-0.1.1.jar` | 原样保留（纯 java 库） |
| lombok（compileOnly + 手动 `-processorpath`） | 升到支持 Java 17 的版本，Gradle 8 下用标准 `annotationProcessor`，删掉手写 processorpath |
| `mcmod.info` | `fabric.mod.json` |
| InputFix coremod（`fml.coreMods.load`） | 删除 |

Java 8 → 17 之后可以用上的东西（**移植期间不要动，留到移植完成后**）：`java.net.http` 替换手写 `HttpURLConnection`、record 表达歌词模型、switch 表达式。移植期唯一目标是行为等价。

## 11. 红线：功能与效果不变量

这些是历史上用户明确要求过的行为，**移植后逐条验收**。改坏任何一条都算回归，不是"顺手优化"。

**歌词链路**
- 逐字歌词永远优先于普通歌词。优先级：AMLL TTML → QQ 音乐逐字（匹配度最高的那条，且必须确认真有逐字轴）→ 普通歌词兜底。
- **没有逐字轴不许伪装逐字**，直接按整行展示。
- 全屏歌词的各种样式（浮名 / 流光 / 心象 / 云阶 / 群唱 / 倾诉 / 镜台 / 时计 / 律动 / 苹果）各自的排版氛围、可调参数、响应式布局必须保留；心象要保证不同字号下字不重叠；群唱左侧封面、右侧登录账号头像（无头像回退封面）。
- OSD 桌面歌词的样式**不许动**，只保证渲染清晰、不重叠、不溢出；对唱部分左右分列且"稍微居中"那个配置项要在。

**播放行为**
- 解析失败**不自动下一曲**，停下等用户操作。
- 选了官方源就不能用第三方源搜索。
- 官方网易云 / QQ 的搜索与播放行为不得随意改动。
- 搜索结果里点歌 → 后续播放走"最近播放"列表，不是搜索结果列表。
- 无缝切换：WSOLA 变速不变调；"跳过前奏"与"保留旧的平滑切换"是两个可配开关，默认行为不变。
- 私人 FM 预取下一首以支撑无缝切换。
- 一起听：房主有 VIP、房员没有时的处理逻辑保持现状。

**灵动岛**
- 通知**排队**机制不变；网易云听歌时长上报的通知优先级最低。
- 不在游戏外 / 主菜单显示；玩家列表不被遮挡；全屏歌词界面与播放器界面只允许通知类型，常驻态不显示。
- 文字过长时灵动岛自行伸缩适配，**不用省略号**；展开/收起动画过程文字不许溢出。
- 延迟图标（U+E903）**不跟主题强调色染色**；液态玻璃样式不被染色。
- 常驻态：FPS / ping / 时间 / 正在播放轮换，轮换开关与两个独立时长配置项都要在。

**图标与字体（约定，不是偏好）**
- 每一批 fontello 导出单独成一份字体 + 单独一个常量类。**不同字体可以有相同码位但字形完全不同**（`island-status-icons` 与 `island-volume-icons` 在 U+E902~E904 就是这种情况），取字形必须连字体一起对上。
- 图标字体的字号按"落屏边长 × 2"选，让绘制倍率恰好为 1。
- 音量图标按 em 方格定位、四档共用同一缩放（喇叭锥体不许随档位跳动、不许变大小），墨迹中心对齐基线上方 `.35em`。

**其它**
- 动态封面：正放 → 倒放 → 正放循环；没装 ffmpeg 要有兜底 + 配置开关。
- 歌词缓存有最大容量配置项（含兜底保护）。
- HUD 配置界面新旧两套 UI 可切换。
- 网易云上报开关前的危险操作确认 Modal（压暗背景、背景不可操作）。

## 12. 接手者工作约定

**用户长期要求用命令行操作**（PowerShell 7 / pwsh），所有读写编辑构建都走 shell。

- 1.8.9 阶段编译（两段式，必须）：
  ```powershell
  .\gradlew.bat compileJava --no-daemon --offline 2>&1 | Out-File -Encoding utf8 build\c.txt
  Get-Content build\c.txt -Encoding utf8 | Select-String -Pattern 'error:|BUILD SUCCESSFUL|BUILD FAILED|\.java:'
  ```
  第一次等待给 30s 必然不够，要再空写一次 stdin 等 150s。全量 60~150 秒。控制台是 GBK，javac 输出**必须** `Out-File -Encoding utf8` 再 `Get-Content -Encoding utf8`，否则中文全乱。收尾删 `build\c.txt`。
- 锚点替换脚本 `build\ed.ps1`（主要编辑手段）：`pwsh -NoProfile -File build\ed.ps1 -Path $f -From $from -To $to`，锚点必须唯一。坑：`-From`/`-To` 的值首字符不能是 `-`；here-string 用单引号版 `@'...'@`；**项目源码是 CRLF**，新建文件要转 CRLF。
- 写大文件分块，每块 ≤150 行（更大会报 `os error 206`），`WriteAllText` 起头 + `AppendAllText` 续写。
- `Remove-Item -Recurse/-Force` 在这台机器上被拒，用 `[System.IO.Directory]::Delete($abs,$true)` / `[System.IO.File]::Delete($abs)`。
- 网络：`github.com:443` 直连不通，只有 `http://127.0.0.1:7890`。`git -c http.proxy=http://127.0.0.1:7890 push origin main`（**不要写进 git config**）。远端 `https://github.com/zihe114514/MuoniumPlayer.git`，分支 `main`。
- 中文提交信息：写文件再 `git -c i18n.commitEncoding=UTF-8 commit -F build\commitmsg.txt`，之后删掉。
- `java -Djava.awt.headless=true` 在 pwsh 里必须加引号：`java '-Djava.awt.headless=true' ...`。
- ffmpeg / ffprobe 已装；**这台机器没有 python、没有 fontTools**（字体处理只能用 Java 写工具）。
- **只有明确要求才 commit / push / 改版本号。**

**离线探针套路**（本项目验证渲染几何的主要手段，移植期同样适用）：探针放 `build\probe\<包路径>\XxxProbe.java`，声明与被测类相同 package 以访问包级成员，private 用反射 + `setAccessible(true)`；classpath 计算 + javac + java 写进 `build\runprobe.ps1`。已验证技巧：
1. 用 `Unsafe.allocateInstance` 造 `CFontRenderer` 子类、重写度量方法，可以在**没有 GL 的环境**下验证排版与图标几何。
2. `MuoniumPlayerExtension` 离线必崩（静态初始化链会走到 GUI），凡是会碰它的路径只能 try/catch 标 SKIPPED。
3. 验证图标几何时，直接用 AWT 复刻 `GlyphGenerator` 的图集单元生成 + `CFontRenderer.drawString` 的几何（单元左上角落在 `(x, y-2×scale)`，1 图集像素 = `0.5×scale` 屏幕像素），再逐像素量墨迹包围盒。这套已经抓出过"图标发虚 + 尺寸不一致"的真根因。
- 收尾必须删 `build\probe`、`build\runprobe.ps1`、`build\pr.txt`、`build\c.txt`、`config\`，并 `git status --short` 复核。

**汇报格式**：分「已验证」与「只能进游戏 / 需真实账号验证」两栏；说明「只有 javac，没跑 lint」；**用户没要求但顺带改了的可见变化必须显式点名**并说明可单独回退。
## 13. 验收：怎么证明"没影响功能和效果"

### 13.1 截图逐像素比对（主要手段）

移植**开始前**，在 1.8.9 上把下面每一项各截一张图（固定窗口尺寸、固定主题、固定同一首带逐字歌词的歌），存进 `docs/baseline/`。每个阶段结束后重截、比对。

| 界面 | 入口类 |
|---|---|
| 播放器主界面 + 左侧栏目切换动画 | `NCMScreen` / `NavigateBar` / `HomePanel` |
| 歌单 / 最近播放 / 排行榜 / 私人 FM | `PlaylistPanel` / `NeteaseDiscoveryPanel` / `PersonalFmPanel` |
| 加载动画 | `NavigationLoadingOverlay` / `PanelLoadingWidget` |
| 搜索 + 联想下拉 | `NCMScreen`（联想面板要验证不穿透点击） |
| 下一首播放列表（拖拽排序 + 放大反馈） | `PlayQueuePanel` |
| 音乐来源二级菜单 / 音质菜单 / 主题调色盘 | `MusicSourceOverlay` / `ThemePaletteOverlay` |
| 登录（含二维码） / 账号管理 | `LoginRenderer` / `AccountManagerOverlay` |
| 一起听 | `ListenTogetherOverlay` |
| 危险操作确认 Modal | `ConfirmationOverlay` |
| 加入歌单 | `AddToPlaylistOverlay` |
| 封面流 | `CoverflowOverlay` |
| 主题过渡动画 | `ThemeTransitionRenderer` |
| **全屏歌词 10 种样式**（逐个截） | `FumeLyricRenderer` 浮名 / `LuminousLyricRenderer` 流光 / `DioramaLyricRenderer` 心象 / `CadenzaLyricRenderer` 云阶 / `CappellaLyricRenderer` 群唱 / `PartitaLyricRenderer` 倾诉 / `TiltLyricRenderer` 镜台 / `PendoloLyricRenderer` 时计 / 律动 / `AppleLyricRenderer` 苹果 |
| 全屏歌词 A-Z 配置面板 | `LyricStyleQuickPanel` |
| 灵动岛：通知态 / 常驻态 / 紧凑态 / 液态玻璃 | `DownloadDynamicIsland` |
| 灵动岛音量四档图标（0 / 35 / 60 / 80） | `IslandVolumeIcons` |
| OSD 桌面歌词（含对唱分列） | `MusicLyricsWidget` / `KaraokeTextCompositor` |
| 桌面歌曲信息（动态封面） | `MusicInfoWidget` |
| HUD 配置新 UI / 旧 UI | `GuiHudEditorPro` / `GuiHudEditor` |
| ffmpeg 缺失提示 | `GuiFfmpegNotice` |

比对重点（这些是最容易在换后端时坏掉的）：字体边缘锐度、圆角半径、发光/模糊半径与强度、半透明叠加层的最终颜色（blend 顺序错了颜色就偏）、裁剪边界、逐字歌词遮罩与字形是否同步放大（历史上出过"遮罩放大字体没放大 → 重影脱节"的 bug）。

### 13.2 离线断言

按第 12 节的探针套路，至少覆盖：字体度量与图标 em 方格几何、歌词解析（逐字轴识别 / 对唱 slot / TTML `x-bg` 标记）、排版折行与不重叠、音量分档映射。这些不需要 GL，能在每次改完立刻跑。

### 13.3 手动测试矩阵（需要真实账号）

网易云 / QQ / 酷狗登录与搜索、灰色歌曲解灰、GD 音乐台各音源、B 站 m4s 解析与转码、私人 FM 预取、一起听（含房主 VIP + 房员非 VIP）、无缝切换（含跳过前奏开 / 关）、听歌时长上报、歌单同步刷新、快捷键、动态封面（有 / 无 ffmpeg 两种环境）。

### 13.4 逐条过第 11 节红线
移植完成的定义 = 第 11 节每一条都实测确认，而不是"看起来没问题"。

## 14. 立即开始：第一步

不要先建 Fabric 工程。**第一件事是阶段 0.1**：

1. 跑一遍附录 A 的命令，自己核实本文所有数字（本文可能已过期）。
2. 读 `core/rendering/rendersystem/RenderSystem.java`、`today/opai/api/impl/GLStateManagerImpl.java`、`core/rendering/Image.java`，搞清现有绘制门面的形状。
3. 在 `core/rendering/rendersystem/` 下新增 `VertexSink` 抽象 + 1.8.9 实现（转发 `GL11`）。
4. 用它改掉 `Image.java`（82 处 GL，最大单点）里的 immediate mode，**只改这一个文件**，编译 + 进游戏确认封面/贴图渲染无变化，单独提交。
5. 之后逐个文件重复第 4 步。一次一个文件，一次一提交。

这个顺序的意义：任何一步出问题都能 `git revert` 单个提交定位，而不是在几万行改动里找。

## 附录 A：现状复现命令

```powershell
$j = Get-ChildItem -Recurse -Filter *.java src\main\java
# 规模
$j.Count; ($j | Get-Content -Encoding utf8 | Measure-Object -Line).Lines
# 碰 mc 的文件
$j | Select-String -Encoding utf8 -Pattern 'import net\.minecraft' |
    Select-Object -ExpandProperty Path -Unique
# 直接调 GL 的文件（按密度排序）
$j | ForEach-Object {
  $c = Get-Content $_.FullName -Encoding utf8
  $n = ($c | Select-String -Pattern 'GL11\.|GL12\.|GL13\.|GL14\.|GL20\.|GL30\.|EXTFramebufferObject|ARBShaderObjects').Count
  if ($n -gt 0) { [pscustomobject]@{ n = $n; p = $_.FullName } }
} | Sort-Object n -Descending
# core profile 债务逐项计数
foreach ($pat in @('glBegin','glVertex','GL_QUADS','GL_ALPHA','glAlphaFunc|GL_ALPHA_TEST',
                   'glColor4f|glColor3f','glCallList|glGenLists','glOrtho|glMatrixMode',
                   'Framebuffer','glStencil')) {
  $r = $j | Select-String -Encoding utf8 -Pattern $pat
  "{0,-34} files={1,-4} hits={2}" -f $pat, ($r | Select-Object -ExpandProperty Path -Unique).Count, $r.Count
}
# GLSL 现状
Get-ChildItem -Recurse src\main\resources\muonium\shaders |
    Select-String -Pattern '#version|texture2D|gl_FragColor|varying|attribute'
```

## 附录 B：关键文件地图

```
com/muoniumplayer/
  MuoniumPlayerMod.java              Forge 入口（重写）
  core/
    MuoniumPlayerExtension.java      业务总入口（离线初始化会崩，探针要绕开）
    rendering/
      rendersystem/RenderSystem.java 绘制门面（阶段 0 的主战场）
      Image.java                     82 处 GL，最大单点
      Framebuffer.java               自实现 FBO + 给 MC 主 target 挂 stencil（:400）
      StencilClipManager.java        圆角裁剪（Fabric 无官方 stencil，见第 8 节）
      ScissorClipManager.java        矩形裁剪（降级方案）
      OpenGlHelper.java              自实现兼容层，1.20.1 可整个删
      DownloadDynamicIsland.java     灵动岛（2200+ 行，通知队列 + 常驻态）
      font/CFontRenderer.java        自绘字体，几何契约见阶段 3
      font/GlyphGenerator.java       图集单元生成（探针要复刻它）
      font/TextureAtlas.java         2048² GL_ALPHA 图集 → 改 GL_RED
      shader/                        自管 shader program + Uniform 体系
      IslandStatusIcons.java         fontello 批次 A 常量
      IslandVolumeIcons.java         fontello 批次 B 常量（码位与 A 重叠、字形不同）
    management/FontManager.java      所有字体档位注册（20+ 个 CFontRenderer）
    screens/                         全部界面，见 13.1
    widget/impl/                     OSD 桌面歌词 + 桌面歌曲信息
    ncm/                             网易云 / QQ / 酷狗 / GD 音源 / 音频链（与 MC 无关）
    settings/HudConfig.java          全部配置项
today/opai/api/                      移植边界门面（34 个文件，impl 666 行）
lain/mods/inputfix/                  中文输入 coremod（删）
src/main/resources/muonium/          fonts / shaders / textures / yrc（classpath 直读，不走 ResourceLocation）
src/main/resources/assets/muonium/lang/  唯一走标准资源路径的东西
```