package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.ncm.music.AutomixSettings;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicAudioCache;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.HudSettingsIcons;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.widget.impl.MusicLyricsWidget;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * HUD 设置界面的内容声明：分类 → 分组 → 条目。
 *
 * <p>为什么要单独一层：新的 HUD 设置界面（{@link GuiHudEditorPro}）与经典界面
 * （{@link GuiHudEditor}）展示的必须是同一批设置项。如果两个界面各自把条目硬编码一遍，
 * 加一个开关就得改两处，漏改的那一边会静默地少一项。把「有哪些项、每项怎么读怎么写、
 * 数值怎么格式化」集中到这里，界面只负责画和命中测试。</p>
 *
 * <p>条目的取值全部走 {@code Supplier} / {@code Runnable} 而不是快照：HUD 设置是实时预览的，
 * 声明必须每帧都反映最新状态。也因此这些声明是无状态的，可以安全地缓存成静态常量。</p>
 */
public final class HudSettingsModel {

    private HudSettingsModel() {
    }

    /** 条目类型。界面按这个决定右侧画什么控件、点击时做什么。 */
    public enum Kind {
        /** 连续数值，拖动轨道。 */
        SLIDER,
        /** 布尔开关。 */
        TOGGLE,
        /** 颜色，点击打开取色器。 */
        COLOR,
        /** 在若干预设之间循环，点击进下一个。 */
        CHOICE,
        /** 主题色卡网格，占一整行且高度由预设数量决定。 */
        THEME_GRID,
        /** 一次性动作按钮。 */
        ACTION
    }

    /** 颜色条目的身份。取色器要知道自己在编辑哪一个，才能把 HSB 写回正确的字段。 */
    public enum ColorTarget {
        NONE,
        /** 当前演唱行的颜色。 */
        CURRENT,
        /** 其余歌词行的颜色。 */
        NORMAL
    }

    public static final class Entry {
        public final Kind kind;
        private final Supplier<String> label;
        /** 一句话说明这项到底影响什么。现代设置界面里这行副标题比标签本身更有用。 */
        public final String description;
        /** SLIDER 专用。 */
        public final HudSetting setting;
        private final BooleanSupplier state;
        private final Runnable action;
        private final Supplier<String> value;
        public final ColorTarget colorTarget;

        private Entry(Kind kind, Supplier<String> label, String description, HudSetting setting,
                      BooleanSupplier state, Runnable action, Supplier<String> value,
                      ColorTarget colorTarget) {
            this.kind = kind;
            this.label = label;
            this.description = description;
            this.setting = setting;
            this.state = state;
            this.action = action;
            this.value = value;
            this.colorTarget = colorTarget;
        }

        public String getLabel() {
            try {
                String text = label == null ? null : label.get();
                return text == null ? "" : text;
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        /** TOGGLE 的当前状态；其它类型恒为 false。 */
        public boolean isOn() {
            if (state == null) return false;
            try {
                return state.getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        /** CHOICE 右侧显示的当前取值；其它类型返回空串。 */
        public String getValueText() {
            if (value == null) return "";
            try {
                String text = value.get();
                return text == null ? "" : text;
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        /**
         * 触发这一项的默认动作：开关取反、选项进下一个、动作执行。
         *
         * <p>SLIDER 与 COLOR 没有默认动作（它们靠拖动），调用是空操作。任何异常都被吞掉：
         * 一次点击失败不该把设置界面整个带崩。</p>
         */
        public void activate() {
            if (action == null) return;
            try {
                action.run();
            } catch (RuntimeException ignored) {
            }
        }

        /** COLOR 专用：当前颜色。取不到时给一个中性灰，避免界面出现 null。 */
        public Color getColor() {
            MusicLyricsWidget lyrics = lyrics();
            if (lyrics == null) return new Color(0x9AA6B8);
            try {
                if (colorTarget == ColorTarget.CURRENT) return lyrics.currentLyricColor.getValue();
                if (colorTarget == ColorTarget.NORMAL) return lyrics.lyricColor.getValue();
            } catch (RuntimeException ignored) {
            }
            return new Color(0x9AA6B8);
        }

        /**
         * COLOR 专用：写入颜色。
         *
         * <p>刻意只改内存不落盘：取色器是拖着改的，每帧写一次 JSON 会明显卡顿。界面在鼠标松开时
         * 统一 {@code HudConfig.save()}。</p>
         */
        public void setColor(Color selected) {
            if (selected == null) return;
            MusicLyricsWidget lyrics = lyrics();
            if (lyrics == null) return;
            try {
                if (colorTarget == ColorTarget.CURRENT) {
                    lyrics.currentLyricColor.setValue(selected);
                    HudConfig.currentLyricColorRgb = selected.getRGB();
                } else if (colorTarget == ColorTarget.NORMAL) {
                    lyrics.lyricColor.setValue(selected);
                    HudConfig.lyricColorRgb = selected.getRGB();
                }
            } catch (RuntimeException ignored) {
            }
        }

        /** 条目是否命中搜索关键字。标签与说明都参与匹配。 */
        public boolean matches(String lowerQuery) {
            if (lowerQuery == null || lowerQuery.isEmpty()) return true;
            if (getLabel().toLowerCase().contains(lowerQuery)) return true;
            if (description != null && description.toLowerCase().contains(lowerQuery)) return true;
            return setting != null && setting.name().toLowerCase().contains(lowerQuery);
        }
    }

    public static final class Group {
        public final String title;
        /** 分组标题左侧的图标码位，取 {@link HudSettingsIcons} 的常量。 */
        public final String icon;
        public final Entry[] entries;

        private Group(String title, String icon, Entry... entries) {
            this.title = title;
            this.icon = icon;
            this.entries = entries;
        }
    }

    public static final class Category {
        public final String title;
        public final String subtitle;
        /** 分类的标识色。侧边栏色点、分组标题与选中态都用它。 */
        public final int accent;
        /** 侧边栏与标题上代表这一分类的图标码位，取 {@link HudSettingsIcons} 的常量。 */
        public final String icon;
        public final Group[] groups;

        private Category(String title, String subtitle, int accent, String icon, Group... groups) {
            this.title = title;
            this.subtitle = subtitle;
            this.accent = accent;
            this.icon = icon;
            this.groups = groups;
        }

        public int entryCount() {
            int total = 0;
            for (Group group : groups) total += group.entries.length;
            return total;
        }
    }

    // ==================== 构造工具 ====================

    private static Entry slider(HudSetting setting, String description) {
        final HudSetting target = setting;
        return new Entry(Kind.SLIDER, new Supplier<String>() {
            public String get() {
                return target.getLabel();
            }
        }, description, setting, null, null, null, ColorTarget.NONE);
    }

    private static Entry toggle(final String label, String description,
                                BooleanSupplier state, Runnable action) {
        return new Entry(Kind.TOGGLE, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, state, action, null, ColorTarget.NONE);
    }

    private static Entry toggle(Supplier<String> label, String description,
                                BooleanSupplier state, Runnable action) {
        return new Entry(Kind.TOGGLE, label, description, null, state, action, null, ColorTarget.NONE);
    }

    private static Entry color(final String label, String description, ColorTarget target) {
        return new Entry(Kind.COLOR, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, null, null, target);
    }

    private static Entry choice(final String label, String description,
                                Supplier<String> value, Runnable next) {
        return new Entry(Kind.CHOICE, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, next, value, ColorTarget.NONE);
    }

    private static Entry themeGrid() {
        return new Entry(Kind.THEME_GRID, new Supplier<String>() {
            public String get() {
                return "配色预设";
            }
        }, "点击色卡立即切换；左上角是页面底色，中间是强调色。", null, null, null, null, ColorTarget.NONE);
    }

    private static Entry action(final String label, String description, Runnable run) {
        return new Entry(Kind.ACTION, new Supplier<String>() {
            public String get() {
                return label;
            }
        }, description, null, null, run, null, ColorTarget.NONE);
    }

    private static Entry action(Supplier<String> label, String description, Runnable run) {
        return new Entry(Kind.ACTION, label, description, null, null, run, null, ColorTarget.NONE);
    }

    private static MusicLyricsWidget lyrics() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 开关改完立刻落盘：开关是离散动作，不像拖动那样每帧都在变。 */
    private static void saveNow() {
        try {
            HudConfig.save();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 动态封面依赖外部 ffmpeg。已经探测过但没找到时把结论写进标签，否则用户只会看到「开着却没动画」。
     * 这里只读缓存结论，不会在渲染线程上真的去探测（那会创建子进程并卡帧）。
     */
    public static String animatedCoverLabel() {
        try {
            return CloudMusic.animatedCoverToolingState() < 0 ? "动态封面 · 未检测到 ffmpeg" : "动态封面";
        } catch (Throwable ignored) {
            return "动态封面";
        }
    }

    /** 模块面板里的 {@code Animated Cover} 只是同一状态的另一处显示，改完要立刻跟上。 */
    public static void syncAnimatedCoverModuleValue() {
        try {
            MuoniumPlayerExtension.getInstance().musicInfo.animatedCover
                    .setValue(HudConfig.animatedCoverEnabled);
        } catch (Throwable ignored) {
            // 值存储还没准备好时无所谓：HudConfig 才是真实来源。
        }
    }

    /** 把两块 HUD 放回出厂位置与缩放。 */
    public static void resetHudPlacement() {
        HudConfig.infoX = 0.02f;
        HudConfig.infoY = 0.02f;
        HudConfig.infoScale = 1.0f;
        HudConfig.lyricX = 0.5f;
        HudConfig.lyricY = 0.85f;
        HudConfig.lyricScale = 1.0f;
        saveNow();
    }

    // ==================== 声明 ====================

    private static Category[] cache;

    /**
     * 全部分类。结果被缓存：条目本身无状态（取值都走 Supplier），重复构造只是浪费。
     */
    public static Category[] categories() {
        if (cache == null) cache = build();
        return cache;
    }

    private static Category[] build() {
        return new Category[] {
                new Category("当前歌词", "正在演唱那一行的颜色、逐字强调与发光", 0xFF8C7CFF, HudSettingsIcons.MIC,
                        new Group("基础", HudSettingsIcons.TUNE,
                                color("当前歌词颜色", "逐字填涂之后那部分文字的颜色。", ColorTarget.CURRENT),
                                toggle("当前歌词特效", "关掉之后当前行只做普通描边，不再放大、发光与呼吸。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.currentLyricEffectsEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.currentLyricEffectsEnabled = !HudConfig.currentLyricEffectsEnabled;
                                                saveNow();
                                            }
                                        }),
                                toggle("OSD 逐字强调", "桌面歌词是否随演唱进度逐字放大与发光。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.osdKaraokeEmphasisEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.osdKaraokeEmphasisEnabled = !HudConfig.osdKaraokeEmphasisEnabled;
                                                saveNow();
                                            }
                                        })),
                        new Group("全屏歌词", HudSettingsIcons.FULLSCREEN,
                                slider(HudSetting.CURRENT_LINE_SCALE, "当前行整体比其它行大多少。"),
                                slider(HudSetting.CURRENT_WORD_SCALE, "正在唱的那个字额外放大多少。"),
                                slider(HudSetting.CURRENT_GLOW, "已唱部分的发光强度。"),
                                slider(HudSetting.CURRENT_GLOW_RADIUS, "发光向外扩散的范围。"),
                                slider(HudSetting.CURRENT_BLOOM, "在发光之外再叠一层柔和晕染。"),
                                slider(HudSetting.CURRENT_TRANSITION, "填涂边缘的软化宽度，越大越像渐变。"),
                                slider(HudSetting.CURRENT_BREATH, "当前行的呼吸缩放幅度。"),
                                slider(HudSetting.FULLSCREEN_GLOW, "全屏歌词页逐字发光的总强度上限。")),
                        new Group("桌面歌词 (OSD)", HudSettingsIcons.CAPTION,
                                slider(HudSetting.OSD_TRANSITION, "桌面歌词填涂边缘的软化宽度。"),
                                slider(HudSetting.OSD_GLOW, "桌面歌词的逐字发光强度。"),
                                slider(HudSetting.OSD_BLOOM, "桌面歌词的柔和晕染强度。"),
                                slider(HudSetting.OSD_PULSE, "桌面歌词正在唱的那个字放大多少。"),
                                slider(HudSetting.OSD_SMOOTHNESS, "桌面歌词逐字动画的平滑程度。"),
                                slider(HudSetting.OSD_DUET_SPREAD, "对唱段落左右分列的间距，0 为完全居中。"))),

                new Category("普通歌词", "还没唱到与已经唱过的那些行", 0xFF65C9B5, HudSettingsIcons.SUBTITLES,
                        new Group("基础", HudSettingsIcons.TUNE,
                                color("普通歌词颜色", "非当前行的文字颜色。", ColorTarget.NORMAL),
                                toggle("普通歌词特效", "关掉之后其它行不再做微光与柔光晕染。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.normalLyricEffectsEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.normalLyricEffectsEnabled = !HudConfig.normalLyricEffectsEnabled;
                                                saveNow();
                                            }
                                        }),
                                toggle("边缘淡出", "歌词滚动到上下边界时是否渐隐，而不是被硬切掉。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.lyricEdgeFadeEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.lyricEdgeFadeEnabled = !HudConfig.lyricEdgeFadeEnabled;
                                                saveNow();
                                            }
                                        })),
                        new Group("排版与动效", HudSettingsIcons.SPARKLE,
                                slider(HudSetting.NORMAL_OPACITY, "非当前行的整体不透明度。"),
                                slider(HudSetting.NORMAL_SCALE, "非当前行的字号缩放。"),
                                slider(HudSetting.NORMAL_GLOW, "非当前行的微光强度。"),
                                slider(HudSetting.NORMAL_BLOOM, "非当前行的柔光晕染强度。"),
                                slider(HudSetting.NORMAL_SPACING, "相邻两行之间的间距。"),
                                slider(HudSetting.EDGE_FADE, "上下边缘渐隐区域的高度。"),
                                slider(HudSetting.SCROLL_SMOOTHNESS, "换行滚动的柔和程度，越大越慢越顺。"),
                                slider(HudSetting.SECONDARY_OPACITY, "译文/音译那一行的不透明度。"))),

                new Category("灵动岛", "顶部胶囊的外观、常驻内容与动画时序", 0xFF65A9FF, HudSettingsIcons.ISLAND,
                        new Group("基础", HudSettingsIcons.TUNE,
                                toggle("下载灵动岛", "总开关。关掉之后下载、通知与常驻都不再显示。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandEnabled = !HudConfig.dynamicIslandEnabled;
                                                saveNow();
                                            }
                                        }),
                                choice("灵动岛样式", "在紧凑、卡片、液态玻璃、系统卡片、音乐焦点之间循环。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return DownloadDynamicIsland.getStyleName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                DownloadDynamicIsland.cycleStyle();
                                            }
                                        }),
                                slider(HudSetting.DYNAMIC_ISLAND_SCALE, "整体大小。"),
                                slider(HudSetting.DYNAMIC_ISLAND_TEXT_SCALE, "岛内文字的字号缩放。"),
                                slider(HudSetting.DYNAMIC_ISLAND_MAX_WIDTH, "基础宽度。文案更长时仍会自动伸展。"),
                                slider(HudSetting.DYNAMIC_ISLAND_PROGRESS_HEIGHT, "下载进度条的粗细。")),
                        new Group("常驻显示", HudSettingsIcons.TIMER,
                                toggle("常驻灵动岛", "没有下载与通知时也留在屏幕顶部。通知到来只做内容淡入，不重播入场动画。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAlwaysOn;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAlwaysOn = !HudConfig.dynamicIslandAlwaysOn;
                                                saveNow();
                                            }
                                        }),
                                toggle("常驻 · 帧率", "状态页显示当前帧率。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientFps;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientFps = !HudConfig.dynamicIslandAmbientFps;
                                                saveNow();
                                            }
                                        }),
                                toggle("常驻 · 延迟", "状态页显示服务器延迟。单机或未进服时显示两个短横。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientPing;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientPing = !HudConfig.dynamicIslandAmbientPing;
                                                saveNow();
                                            }
                                        }),
                                toggle("常驻 · 时间", "状态页显示系统时间（时:分）。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientClock;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientClock = !HudConfig.dynamicIslandAmbientClock;
                                                saveNow();
                                            }
                                        }),
                                toggle("常驻 · 正在播放", "把当前歌曲作为一页参与轮换：左侧换成封面，右侧显示歌名与歌手。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientNowPlaying;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientNowPlaying = !HudConfig.dynamicIslandAmbientNowPlaying;
                                                saveNow();
                                            }
                                        }),
                                toggle("常驻 · 轮换显示", "关掉之后固定停在状态页；状态三项全关时才固定显示正在播放页。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.dynamicIslandAmbientRotate;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.dynamicIslandAmbientRotate = !HudConfig.dynamicIslandAmbientRotate;
                                                saveNow();
                                            }
                                        }),
                                slider(HudSetting.DYNAMIC_ISLAND_AMBIENT_STATUS,
                                        "状态页（帧率/延迟/时间）每次停留多久。三个数字扫一眼就够，可以短一些。"),
                                slider(HudSetting.DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING,
                                        "正在播放页每次停留多久。要读完歌名与歌手，通常需要比状态页更久。")),
                        new Group("时序与动画", HudSettingsIcons.SPEED,
                                slider(HudSetting.DYNAMIC_ISLAND_COMPLETION_HOLD, "下载完成后结果停留多久再收起。"),
                                slider(HudSetting.DYNAMIC_ISLAND_QUEUE_INTERVAL, "排队的两条通知之间至少间隔多久。"),
                                slider(HudSetting.DYNAMIC_ISLAND_EXPAND_SPEED, "展开的速度倍率。"),
                                slider(HudSetting.DYNAMIC_ISLAND_COLLAPSE_SPEED, "收起的速度倍率。"),
                                slider(HudSetting.DYNAMIC_ISLAND_CONTENT_SPEED, "岛内内容交叉淡入的速度倍率。"),
                                slider(HudSetting.DYNAMIC_ISLAND_ENTRANCE_DURATION, "入场动画的时长。"),
                                slider(HudSetting.DYNAMIC_ISLAND_OVERSHOOT, "入场结束时的回弹幅度，0 为不回弹。"),
                                slider(HudSetting.DYNAMIC_ISLAND_SPINNER_SPEED, "加载转子的转速倍率。"))),

                new Category("封面", "歌曲封面的静态与动态来源", 0xFFFFA971, HudSettingsIcons.ALBUM,
                        new Group("动态封面", HudSettingsIcons.ALBUM,
                                toggle(new Supplier<String>() {
                                    public String get() {
                                        return animatedCoverLabel();
                                    }
                                }, "打开后桌面歌曲信息、全屏歌词页与播放条使用 ffmpeg 抽帧的动态封面；关掉或没有 ffmpeg 时一律用静态封面。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return HudConfig.animatedCoverEnabled;
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                HudConfig.animatedCoverEnabled = !HudConfig.animatedCoverEnabled;
                                                syncAnimatedCoverModuleValue();
                                                saveNow();
                                            }
                                        }))),

                new Category("无缝切换", "两首歌之间的交接方式与衔接处理", 0xFF5FD3E8, HudSettingsIcons.SPEED,
                        new Group("基础", HudSettingsIcons.TUNE,
                                toggle("无缝切换", "打开后提前解码下一首并交叉淡化；关掉则切歌时先停下再重新下载解码，中间有静音间隙。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setEnabled(!AutomixSettings.isEnabled());
                                            }
                                        }),
                                choice("重叠时长", "两首歌同时出声的长度。手动按下一曲时会自动缩短到 0.9 秒，这里只影响放完自动切歌。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return AutomixSettings.getOverlapLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.cycleOverlapSeconds();
                                            }
                                        })),
                        new Group("衔接处理", HudSettingsIcons.SPARKLE,
                                toggle("跳过前奏", "打开后新歌从主体进来，跳掉没内容的前奏；关掉则一律从第 0 秒淡入，也就是旧版的平滑切换。前奏位置照样会被分析，关掉不影响对拍。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isSkipIntroEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setSkipIntroEnabled(!AutomixSettings.isSkipIntroEnabled());
                                            }
                                        }),
                                toggle("小节对齐", "把交接点挪到最近的小节线上，让两首歌的鼓点落在一起而不是互相错开。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isBeatAlignEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setBeatAlignEnabled(!AutomixSettings.isBeatAlignEnabled());
                                            }
                                        }),
                                toggle("低频交换", "重叠期间压掉一首的低频，两条鼓与贝斯叠在一起才不会糊成一团。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isBassSwapEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setBassSwapEnabled(!AutomixSettings.isBassSwapEnabled());
                                            }
                                        }),
                                toggle("去人声淡入", "新歌淡入的前半段压掉中置人声只留伴奏，避免两首歌的人声同时在唱。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isVocalDuckEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setVocalDuckEnabled(!AutomixSettings.isVocalDuckEnabled());
                                            }
                                        }),
                                toggle("节奏锁定", "重叠期间微调旧歌的速度去追上新歌的 BPM。因为是重采样，会带来最多约 1% 的音调偏移。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isTempoLockEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setTempoLockEnabled(!AutomixSettings.isTempoLockEnabled());
                                            }
                                        }),
                                toggle("音高跳跃", "两首歌速度对不上时，把旧歌整体移调一个半音去凑更协和的和声。听得出音调变化，不想要就关掉。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return AutomixSettings.isPitchMatchEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                AutomixSettings.setPitchMatchEnabled(!AutomixSettings.isPitchMatchEnabled());
                                            }
                                        }))),

                new Category("存储", "已下载音频的磁盘占用与上限", 0xFFD8A657, HudSettingsIcons.STORAGE,
                        new Group("音频缓存", HudSettingsIcons.SWEEP,
                                choice("缓存上限",
                                        "MusicCache 目录最多占多少磁盘。超出后从最久没听的歌开始整首删除，正在播放与预备播放的歌不会被删。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return NCMPlayerConfig.getMusicCacheLimitLabel();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                NCMPlayerConfig.cycleMusicCacheLimit();
                                                MusicAudioCache.enforceLimitAsync();
                                            }
                                        }),
                                action(new Supplier<String>() {
                                    public String get() {
                                        return cacheSweepLabel();
                                    }
                                }, "现在就按上限清一次，同时删掉中断的下载与转码残片。上限为「不限制」时只清残片。",
                                        new Runnable() {
                                            public void run() {
                                                sweepAudioCache();
                                            }
                                        }))),

                new Category("主题", "播放器与灵动岛的整体配色", 0xFFB68CFF, HudSettingsIcons.PALETTE,
                        new Group("配色预设", HudSettingsIcons.PALETTE,
                                choice("播放器主题", "点击在全部预设之间依次切换。",
                                        new Supplier<String>() {
                                            public String get() {
                                                return NCMTheme.getCurrentName();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                applyTheme(NCMTheme.next());
                                            }
                                        }),
                                themeGrid())),

                new Category("HUD 位置", "桌面上两块 HUD 的开关与摆放", 0xFF7FD4A0, HudSettingsIcons.MOVE,
                        new Group("显示", HudSettingsIcons.VISIBILITY,
                                toggle("歌曲信息栏", "桌面左上角那块封面 + 歌名的卡片。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return infoEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                setInfoEnabled(!infoEnabled());
                                            }
                                        }),
                                toggle("桌面歌词", "屏幕下方的逐字歌词。",
                                        new BooleanSupplier() {
                                            public boolean getAsBoolean() {
                                                return lyricsEnabled();
                                            }
                                        }, new Runnable() {
                                            public void run() {
                                                setLyricsEnabled(!lyricsEnabled());
                                            }
                                        })),
                        new Group("摆放", HudSettingsIcons.RULER,
                                action("恢复默认位置与缩放",
                                        "把信息栏放回左上、歌词放回底部中间，缩放都回到 100%。在界面外的空白处直接拖动也可以调整位置，滚轮调缩放。",
                                        new Runnable() {
                                            public void run() {
                                                resetHudPlacement();
                                            }
                                        })))
        };
    }

    /** 「立即清理」那一行的标签，顺带把当前占用显示出来。 */
    public static String cacheSweepLabel() {
        try {
            return "立即清理 · 当前 " + MusicAudioCache.formatBytes(MusicAudioCache.cachedTotalBytes());
        } catch (Throwable ignored) {
            return "立即清理";
        }
    }

    /** 手动清理走后台线程：扫目录加删文件不能压在渲染线程上。结果用灵动岛回报。 */
    public static void sweepAudioCache() {
        MultiThreadingUtil.runAsync(new Runnable() {
            public void run() {
                try {
                    MusicAudioCache.Sweep result = MusicAudioCache.enforceLimit();
                    String detail = result.freedBytes <= 0L
                            ? "没有可回收的缓存 · 占用 " + MusicAudioCache.formatBytes(result.remainingBytes)
                            : "回收 " + MusicAudioCache.formatBytes(result.freedBytes)
                                    + " · 剩余 " + MusicAudioCache.formatBytes(result.remainingBytes);
                    DownloadDynamicIsland.showCacheSweep(detail, true);
                } catch (Throwable failure) {
                    DownloadDynamicIsland.showCacheSweep("无法访问缓存目录", false);
                }
            }
        });
    }

    private static void applyTheme(NCMTheme.ThemePreset preset) {
        DownloadDynamicIsland.showTheme(preset == null ? NCMTheme.getCurrentName() : preset.getDisplayName());
    }

    private static boolean infoEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicInfo.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setInfoEnabled(boolean enabled) {
        try {
            MuoniumPlayerExtension.getInstance().musicInfo.setEnabled(enabled);
        } catch (Throwable ignored) {
        }
    }

    private static boolean lyricsEnabled() {
        try {
            return MuoniumPlayerExtension.getInstance().musicLyrics.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setLyricsEnabled(boolean enabled) {
        try {
            MuoniumPlayerExtension.getInstance().musicLyrics.setEnabled(enabled);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 数值格式化 ====================

    /**
     * 滑块右侧显示的数值文本。
     *
     * <p>放在模型里而不是各界面里：两个界面显示同一个值时必须写法一致，否则同一项在新旧界面
     * 会读出不同的单位。</p>
     */
    public static String formatValue(HudSetting setting, float value) {
        switch (setting) {
            case CURRENT_LINE_SCALE:
            case NORMAL_SCALE:
            case DYNAMIC_ISLAND_SCALE:
            case DYNAMIC_ISLAND_TEXT_SCALE:
            case CURRENT_WORD_SCALE:
            case CURRENT_GLOW:
            case CURRENT_BLOOM:
            case CURRENT_BREATH:
            case FULLSCREEN_GLOW:
            case OSD_GLOW:
            case OSD_BLOOM:
            case OSD_PULSE:
            case OSD_SMOOTHNESS:
            case OSD_DUET_SPREAD:
            case NORMAL_OPACITY:
            case NORMAL_GLOW:
            case NORMAL_BLOOM:
            case SCROLL_SMOOTHNESS:
            case SECONDARY_OPACITY:
                return Math.round(value * 100.0f) + "%";
            case CURRENT_GLOW_RADIUS:
            case DYNAMIC_ISLAND_MAX_WIDTH:
            case DYNAMIC_ISLAND_PROGRESS_HEIGHT:
                return String.format("%.1f px", value);
            case DYNAMIC_ISLAND_COMPLETION_HOLD:
            case DYNAMIC_ISLAND_QUEUE_INTERVAL:
            case DYNAMIC_ISLAND_AMBIENT_STATUS:
            case DYNAMIC_ISLAND_AMBIENT_NOW_PLAYING:
                return String.format("%.1f s", value);
            case DYNAMIC_ISLAND_ENTRANCE_DURATION:
                return Math.round(value) + " ms";
            case DYNAMIC_ISLAND_EXPAND_SPEED:
            case DYNAMIC_ISLAND_COLLAPSE_SPEED:
            case DYNAMIC_ISLAND_CONTENT_SPEED:
            case DYNAMIC_ISLAND_SPINNER_SPEED:
            case DYNAMIC_ISLAND_OVERSHOOT:
                return String.format("%.2f×", value);
            case CURRENT_TRANSITION:
            case OSD_TRANSITION:
            case NORMAL_SPACING:
            case EDGE_FADE:
                return Math.round(value) + " px";
            default:
                return String.format("%.2f", value);
        }
    }

    /** 声明里出现过的全部 {@link HudSetting}。探针用它断言没有滑块被漏掉。 */
    public static List<HudSetting> declaredSettings() {
        List<HudSetting> found = new ArrayList<HudSetting>();
        for (Category category : categories()) {
            for (Group group : category.groups) {
                for (Entry entry : group.entries) {
                    if (entry.setting != null) found.add(entry.setting);
                }
            }
        }
        return found;
    }
}
