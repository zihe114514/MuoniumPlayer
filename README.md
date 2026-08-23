# MuoniumPlayer

**在 Minecraft 里放歌的独立播放器 Mod。** 网易云 / QQ 音乐登录、逐字歌词、桌面 HUD、灵动岛通知、无缝切歌，全部在游戏内完成，不需要挂后台播放器。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.8.9-green.svg)](#)
[![Forge](https://img.shields.io/badge/Forge-11.15.1.2318-orange.svg)](#)
[![Java](https://img.shields.io/badge/Java-8-blue.svg)](#)
[![Version](https://img.shields.io/badge/version-1.3.0-blueviolet.svg)](CHANGELOG.md)

> 基于 [tritium-music](https://github.com/IzumiiKonata/tritium-music) 二次开发。

## ⚠️ 兼容性

- **已内置 InputFix**，与其它中文输入类 Mod 冲突，二者只能装一个。
- **不支持 OptiFine 的「快速渲染」**（Fast Render），开启后界面会花屏，请关掉。
- 仅 **Java 8**。

## 功能

### 音乐来源

| 类别 | 内容 |
| --- | --- |
| 官方源 | 网易云音乐、QQ 音乐（扫码登录，多账号切换，读取自己的歌单 / 收藏 / 网盘） |
| 在线聚合源 | GD 音乐台：网易云、JOOX、哔哩哔哩、酷我（其余平台已按文档接入，上游恢复后无需更新客户端即可选用） |
| 音质 | Standard / Higher / ExHigh / 无损 / HiRes / 高清环绕 / 沉浸环绕 / 母带 |
| 解码 | MP3、AAC / M4A、FLAC 内置解码；哔哩哔哩的 m4s 等容器交给 ffmpeg 转码 |

### 浏览与播放

- 主页、排行榜、热搜、最近播放、**私人 FM**（会预取下一首）、我的歌单、收藏歌单。
- 搜索歌曲与歌单；专辑封面墙（Coverflow）。
- 聚合源解析失败时会自动换源重试，并在灵动岛上说明实际用的是哪个源。
- **下一首播放队列**：任意歌曲右侧一键插队，可拖拽改排序，队列放完自动回到原歌单。
- **无缝切歌（Automix）**：自动 / 手动切歌都有交叠淡入淡出，可选节拍对齐、低频交接、时长锁定，交叠 1.5~10 秒可调。
- 全局快捷键控制上一曲 / 下一曲 / 音量 / 暂停，登录网易云后可选上报听歌记录。

### 歌词

- **逐字歌词永远优先于普通歌词**，按顺序尝试：[AMLL TTML 词库](https://github.com/amll-dev/amll-ttml-db) → QQ 音乐 QRC → 云盘歌词 / 内嵌标签 / 平台行歌词兜底。
- **全屏歌词页**：逐字填涂、随时长渐强的发光、首字放大强调、对唱分列双行同时渲染、翻译行，左上角可实时调参。
- **桌面 OSD 歌词**：离屏合成的 KTV 染色，缩放下依然锐利；对唱可配置收拢幅度。

### HUD 与外观

- **歌曲信息卡** + **桌面歌词**两块 HUD，可拖拽、缩放、居中吸附。
- **灵动岛通知**：7 种样式（经典胶囊 / 通透玻璃 / 紧凑状态 / 浮层卡片 / 系统通知 / 音乐聚焦 / 液态玻璃），弹出收起速度可调，通知按优先级排队，文字过长自动伸缩。
- **常驻灵动岛**：轮换显示 FPS / 延迟 / 时间与正在播放的歌曲，两页时长分别可配。
- **动态封面**：网易云的 MP4 动态封面会在歌曲信息卡、全屏歌词页和灵动岛一起动起来（需要 ffmpeg，没有就退回静态封面）。
- **15 套主题预设** + 实时预览调色盘（右键控件调出），主题切换有过渡动画。
- **HUD 设置界面两套皮肤**：现代双栏（侧边栏分类 + 卡片分组 + 每行带说明 + 全局搜索 + 面板可拖动）与经典单列长面板，随时互切，设置项完全一致。

## 特点

- **一切都在游戏进程里**。自带 MP3 / AAC / FLAC 解码与音频设备管理，不依赖外部播放器或系统媒体控制。
- **渲染是自绘的**。圆角矩形走 shader，字体走自建字形图集（2 倍超采样 + 精确 0.5 倍缩小），所以在任何 HUD 缩放下都不发虚。
- **逐字歌词是一等公民**，而不是「有就用」。四条链路按「是不是真的逐字」判定，行级歌词只做兜底。
- **兜底优先于报错**。ffmpeg 没装、音源挂了、字体没就绪、量测失败——每一处都有明确的退化路径，不会把整个界面带下去。
- **几何与命中共用一份坐标**。设置界面每帧只算一次行矩形，绘制与点击判定都读它，不会出现「点到隔壁那一项」。
- **改动都有离线断言**。布局、动画时序、图标度量、字体码位冲突等，用 `Unsafe` 造壳在无 GL / 无窗口的环境下直接跑，改完先跑断言再进游戏。

## 快捷键

游戏内 **选项 → 控制** 可改。

| 默认键 | 功能 |
| --- | --- |
| `Right Shift` | 打开播放器 |
| `H` | 打开 HUD 设置 |
| `Page Up` / `Page Down` | 上一曲 / 下一曲 |
| `小键盘 +` / `-` | 音量增减 |
| `小键盘 *` | 暂停 / 继续 |

## 安装

1. 装好 Minecraft **1.8.9** 与 Forge **11.15.1.2318**，运行环境为 **Java 8**。
2. 把 jar 丢进 `.minecraft/mods/`。
3. 进游戏按 `Right Shift` 打开播放器，扫码登录。

**可选：安装 ffmpeg。** 动态封面与哔哩哔哩音源要用到它。首次启动检测不到会弹一次提示（可以选「永不展示」）。放在 `PATH` 里，或丢到 `.minecraft/config/muonium/bin/` 下即可。

## 构建

```bat
gradlew.bat build --no-daemon
```

产物在 `build/libs/`。日常只想看能不能编译过：

```bat
gradlew.bat compileJava processResources --no-daemon
```

## 配置文件

| 路径 | 内容 |
| --- | --- |
| `muoniumplayer_hud.json` | HUD、歌词、灵动岛、封面的全部外观参数 |
| `muoniumplayer_config.json` | 播放器行为 |
| `muoniumplayer_theme.json` | 主题与自定义配色 |
| `config/muonium/` | 账号、音源选择、Automix、提示状态等 |

旧版本（`tritium_*` / `muonium_*` / `deuteriummusic_*`）的配置会在首次启动自动迁移，新文件已存在时不覆盖。

## 致谢

- [tritium-music](https://github.com/IzumiiKonata/tritium-music) — 播放器 UI 框架与音频处理主体
- [Cadence](https://github.com/FPSMasterTeam/Cadence) — 网易云 / QQ 音乐 API
- [api-enhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced) — 网易云音乐 API
- [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) — 人工校对的逐字歌词词库
- [GD 音乐台](https://music.gdstudio.xyz/) — 在线聚合音源
- [Material Design Icons](https://github.com/marella/material-design-icons)（Apache-2.0）— 设置界面图标

## 许可

Forge 与第三方许可声明保留在仓库根目录与 `docs/licenses/`。
