package com.muoniumplayer.core.screens.ncm;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Mouse;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * 全屏歌词页左上角的「A-Z」快捷面板：歌词样式、逐字特效、以及「律动词云」的背景配色。
 *
 * <p>为什么单独做一个入口：这些项只影响全屏歌词页，边调边看才调得准。内置 HUD 编辑器里也有同样的
 * 滑块（{@link HudSetting#FULLSCREEN_GLOW} / {@link HudSetting#CURRENT_WORD_SCALE}），但那是另一个
 * 界面，看不到全屏歌词的实际效果。</p>
 *
 * <p>实时预览不需要任何额外机制：渲染器每帧直接读 {@link HudConfig} 的静态字段，拖动滑块的同一帧
 * 就会生效。落盘只在松开鼠标（或点一下开关）时发生一次，避免拖动过程中反复写文件。</p>
 *
 * <h3>行布局是一张表，不是一串偏移量</h3>
 * <p>面板的行数会随「当前是哪种歌词样式」「配色是不是自定义」变化，早先那种「每个 rowY 各自用常量
 * 累加算」的写法每加一行都要同步改 {@code panelHeight()} 和后面所有行的偏移，漏一处就是「看得见
 * 但点不到」。现在改成 {@link #layout()} 每帧顺序摆一次行、把 y 与高度记进 {@link #rows}，
 * 渲染与命中<b>共用同一张表</b>，加行只需要在 {@code layout()} 里插一句。</p>
 */
final class LyricStyleQuickPanel implements SharedRenderingConstants, SharedConstants {

    /** 面板里的通用浮点滑块，顺序即显示顺序。 */
    private static final HudSetting[] SETTINGS = {
            HudSetting.FULLSCREEN_GLOW,
            HudSetting.CURRENT_WORD_SCALE
    };

    private static final int KIND_STYLE = 0;
    private static final int KIND_SLIDER = 1;
    private static final int KIND_SECTION = 2;
    private static final int KIND_DECOR_MODE = 3;
    private static final int KIND_BEATS = 4;
    private static final int KIND_COLOR_COUNT = 5;
    private static final int KIND_SWATCHES = 6;
    private static final int KIND_HSB = 7;
    /** 当前样式专属的浮点滑块。 */
    private static final int KIND_STYLE_SLIDER = 8;
    /** 当前样式专属的开关 / 枚举，点一下换下一档。 */
    private static final int KIND_STYLE_TOGGLE = 9;

    // ---- 各样式专属参数表 ----
    // 做成 static final 而不是每次现算：layout() 每帧都会跑一遍，没必要每帧 new 一堆小数组。

    private static final HudSetting[] NO_SETTINGS = new HudSetting[0];
    private static final int[] NO_TOGGLES = new int[0];

    // 「歌词字号」放在每套样式参数的第一条：它对这几套样式都有效，但对「逐行滚动」「律动词云」无效
    // （那两套有自己的字号机制），所以不能塞进上面那个通用的 SETTINGS 里 —— 否则会出现「调了没反应」。
    private static final HudSetting[] LUMINOUS_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.LUMINOUS_WORD_SPACING, HudSetting.LUMINOUS_FLOAT
    };
    private static final HudSetting[] PARTITA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.PARTITA_STAGGER_MIN, HudSetting.PARTITA_STAGGER_MAX
    };
    private static final HudSetting[] FUME_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.FUME_TEXT_HOLD, HudSetting.FUME_CAMERA_SPEED, HudSetting.FUME_HERO_SCALE
    };
    private static final HudSetting[] CAPPELLA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.CAPPELLA_BUBBLE_WIDTH, HudSetting.CAPPELLA_EMOJI_CHANCE
    };
    private static final HudSetting[] TILT_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.TILT_SPLIT, HudSetting.TILT_STYLE
    };
    private static final HudSetting[] DIORAMA_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.DIORAMA_DEPTH
    };
    private static final HudSetting[] PENDOLO_SETTINGS = {
            HudSetting.LYRIC_FONT_SCALE,
            HudSetting.PENDOLO_ARC_RADIUS, HudSetting.PENDOLO_ARC_ANGLE,
            HudSetting.PENDOLO_ACTIVE_SCALE
    };

    private static final int TOGGLE_LUMINOUS_ROTATION = 1;
    private static final int TOGGLE_PARTITA_GUIDES = 2;
    private static final int TOGGLE_FUME_BACKGROUND = 3;
    private static final int TOGGLE_CAPPELLA_AVATAR = 4;
    private static final int TOGGLE_TILT_COLORS = 5;
    private static final int TOGGLE_DIORAMA_DUST = 6;
    private static final int TOGGLE_PENDOLO_GEARS = 7;
    private static final int TOGGLE_CAPPELLA_EMOJI = 8;

    private static final int[] LUMINOUS_TOGGLES = {TOGGLE_LUMINOUS_ROTATION};
    private static final int[] PARTITA_TOGGLES = {TOGGLE_PARTITA_GUIDES};
    private static final int[] FUME_TOGGLES = {TOGGLE_FUME_BACKGROUND};
    private static final int[] CAPPELLA_TOGGLES = {TOGGLE_CAPPELLA_AVATAR, TOGGLE_CAPPELLA_EMOJI};
    private static final int[] TILT_TOGGLES = {TOGGLE_TILT_COLORS};
    private static final int[] DIORAMA_TOGGLES = {TOGGLE_DIORAMA_DUST};
    private static final int[] PENDOLO_TOGGLES = {TOGGLE_PENDOLO_GEARS};

    private static final String[] TILT_COLOR_NAMES = {"默认", "互换", "全强调", "全主色"};

    /** 第 0 档是「左侧歌曲封面 + 右侧登录账号头像」，未登录时右侧自动退回封面。 */
    private static final String[] CAPPELLA_AVATAR_NAMES = {"封面+我的头像", "主题色", "内置头像"};

    private static HudSetting[] styleSettings() {
        switch (FullscreenLyricStyle.current()) {
            case LUMINOUS: return LUMINOUS_SETTINGS;
            case PARTITA: return PARTITA_SETTINGS;
            case FUME: return FUME_SETTINGS;
            case CAPPELLA: return CAPPELLA_SETTINGS;
            case TILT: return TILT_SETTINGS;
            case DIORAMA: return DIORAMA_SETTINGS;
            case PENDOLO: return PENDOLO_SETTINGS;
            default: return NO_SETTINGS;
        }
    }

    private static int[] styleToggles() {
        switch (FullscreenLyricStyle.current()) {
            case LUMINOUS: return LUMINOUS_TOGGLES;
            case PARTITA: return PARTITA_TOGGLES;
            case FUME: return FUME_TOGGLES;
            case CAPPELLA: return CAPPELLA_TOGGLES;
            case TILT: return TILT_TOGGLES;
            case DIORAMA: return DIORAMA_TOGGLES;
            case PENDOLO: return PENDOLO_TOGGLES;
            default: return NO_TOGGLES;
        }
    }

    private static String toggleLabel(int id) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION: return "字词旋转";
            case TOGGLE_PARTITA_GUIDES: return "指示线";
            case TOGGLE_FUME_BACKGROUND: return "背景几何体";
            case TOGGLE_CAPPELLA_AVATAR: return "头像来源";
            case TOGGLE_CAPPELLA_EMOJI: return "表情气泡";
            case TOGGLE_TILT_COLORS: return "配色方案";
            case TOGGLE_DIORAMA_DUST: return "浮尘";
            case TOGGLE_PENDOLO_GEARS: return "齿轮装饰";
            default: return "";
        }
    }

    private static String toggleValue(int id) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION: return HudConfig.luminousWordRotation ? "开" : "关";
            case TOGGLE_PARTITA_GUIDES: return HudConfig.partitaGuideLines ? "开" : "关";
            case TOGGLE_FUME_BACKGROUND: return HudConfig.fumeGeometricBackground ? "开" : "关";
            case TOGGLE_CAPPELLA_AVATAR:
                return CAPPELLA_AVATAR_NAMES[Math.max(0, Math.min(CAPPELLA_AVATAR_NAMES.length - 1,
                        HudConfig.cappellaAvatarSource))];
            case TOGGLE_CAPPELLA_EMOJI: return HudConfig.cappellaEmojiEnabled ? "开" : "关";
            case TOGGLE_TILT_COLORS:
                return TILT_COLOR_NAMES[Math.max(0, Math.min(TILT_COLOR_NAMES.length - 1,
                        HudConfig.tiltColorScheme))];
            case TOGGLE_DIORAMA_DUST: return HudConfig.dioramaDust ? "开" : "关";
            case TOGGLE_PENDOLO_GEARS: return HudConfig.pendoloGearDecor ? "开" : "关";
            default: return "";
        }
    }

    /** 换下一档。{@code back} 为 true 表示往回退一档（多档枚举才有区别）。 */
    private static void cycleToggle(int id, boolean back) {
        switch (id) {
            case TOGGLE_LUMINOUS_ROTATION:
                HudConfig.luminousWordRotation = !HudConfig.luminousWordRotation;
                break;
            case TOGGLE_PARTITA_GUIDES:
                HudConfig.partitaGuideLines = !HudConfig.partitaGuideLines;
                break;
            case TOGGLE_FUME_BACKGROUND:
                HudConfig.fumeGeometricBackground = !HudConfig.fumeGeometricBackground;
                break;
            case TOGGLE_CAPPELLA_AVATAR: {
                int span = CAPPELLA_AVATAR_NAMES.length;
                int next = HudConfig.cappellaAvatarSource + (back ? -1 : 1);
                HudConfig.cappellaAvatarSource = ((next % span) + span) % span;
                break;
            }
            case TOGGLE_CAPPELLA_EMOJI:
                HudConfig.cappellaEmojiEnabled = !HudConfig.cappellaEmojiEnabled;
                break;
            case TOGGLE_TILT_COLORS: {
                int span = TILT_COLOR_NAMES.length;
                int next = HudConfig.tiltColorScheme + (back ? -1 : 1);
                HudConfig.tiltColorScheme = ((next % span) + span) % span;
                break;
            }
            case TOGGLE_DIORAMA_DUST:
                HudConfig.dioramaDust = !HudConfig.dioramaDust;
                break;
            case TOGGLE_PENDOLO_GEARS:
                HudConfig.pendoloGearDecor = !HudConfig.pendoloGearDecor;
                break;
            default:
                return;
        }
        HudConfig.save();
    }

    private static final double MARGIN = 12.0;
    private static final double BUTTON_SIZE = 24.0;
    private static final double PANEL_WIDTH = 178.0;
    private static final double PANEL_PADDING = 10.0;
    private static final double TITLE_HEIGHT = 18.0;
    private static final double ROW_HEIGHT = 30.0;
    private static final double TRACK_HEIGHT = 4.0;
    /** 整行按钮的高度（样式切换、配色模式、颜色数量），不需要滑块那么高。 */
    private static final double PICK_ROW_HEIGHT = 26.0;
    /** 分组小标题。 */
    private static final double SECTION_HEIGHT = 17.0;
    /** 色块行。 */
    private static final double SWATCH_ROW_HEIGHT = 26.0;
    private static final double SWATCH_HEIGHT = 18.0;
    private static final double SWATCH_GAP = 4.0;
    /** 色相 / 饱和度 / 明度三条紧凑滑块。 */
    private static final double HSB_ROW_HEIGHT = 20.0;
    private static final double HSB_TRACK_HEIGHT = 6.0;
    /** HSB 行左侧标签占的宽度。 */
    private static final double HSB_LABEL_WIDTH = 34.0;

    /**
     * 面板整体的最小缩放。
     *
     * <p>展开全部配色控件时面板要 300 像素上下，在 GUI 缩放 3~4 档下（视口只有 360 / 270 高）会伸到
     * 屏幕外面——那部分就是「看得见但点不到」甚至「完全看不见」。所以空间不够时整块面板等比缩小，
     * 缩到这个下限就不再缩（再小文字就没法看了；到那一档为止都还塞得进最小的视口）。</p>
     */
    private static final double MIN_UI_SCALE = .62;

    /** 色相条画成多少段。段数够多就看不出台阶，又不至于为了一条 158 像素的轨道画上百个矩形。 */
    private static final int HUE_SEGMENTS = 26;
    private static final int RAMP_SEGMENTS = 16;

    private static final String[] HSB_LABELS = {"色相", "饱和", "明度"};

    /** 一行控件的位置。{@link #layout()} 每帧重建，渲染与命中共用。 */
    private static final class Row {
        int kind;
        int index;
        double y;
        double height;
    }

    private final List<Row> rows = new ArrayList<Row>();
    private double layoutHeight;

    /**
     * 面板的整体缩放，1 表示原尺寸。
     *
     * <p>布局与命中判定全部在<b>未缩放</b>的坐标系里做，只有绘制时套一次
     * {@code scaleAtPos(anchorX, panelTop, uiScale)}；鼠标坐标在进入面板逻辑之前用
     * {@link #toLayoutX}/{@link #toLayoutY} 反变换回去。这样「点得到的地方」和「画出来的地方」
     * 永远是同一处，不需要在每个命中框上重复乘一遍缩放。</p>
     */
    private double uiScale = 1.0;

    /** 面板底部最多能画到哪（绝对坐标），由调用方给的视口高度算出。 */
    private double panelSpaceBottom = Double.NaN;

    private boolean expanded;
    private float expandAnimation;
    private boolean pendingSave;

    /** 正在拖的是哪一行；{@code dragKind < 0} 表示没在拖。 */
    private int dragKind = -1;
    private int dragIndex = -1;

    /** 正在编辑调色板的第几槽。 */
    private int editSlot;
    /** 上一次同步过的槽颜色，用来发现「颜色被别处改了」而重算 HSB。 */
    private int editRgbCache = -1;
    private float editHue;
    private float editSat;
    private float editBri;

    /** 上一帧的按钮/面板位置，供 {@code consumeClick} 复用（点击链路拿不到布局参数）。 */
    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private float lastAlpha;

    /**
     * @param viewHeight 歌词页视口的高度，用来决定面板要不要整体缩小（见 {@link #MIN_UI_SCALE}）
     */
    void render(double mouseX, double mouseY, double posX, double posY, double viewHeight, float alpha) {
        this.anchorX = posX + MARGIN;
        this.anchorY = posY + MARGIN;
        this.lastAlpha = alpha;
        this.panelSpaceBottom = posY + viewHeight - MARGIN;

        // 面板收起时不再跟随鼠标，拖动状态也要清掉，否则收起后仍然会改数值。
        if (!expanded && dragKind >= 0) {
            releaseDrag();
        }

        layout();
        updateUiScale();
        syncEditColor();
        updateDrag(mouseX, mouseY);

        expandAnimation = Interpolations.interpolate(expandAnimation, expanded ? 1f : 0f, .22f);

        renderPanel(toLayoutX(mouseX), toLayoutY(mouseY), alpha);
        // 按钮画在缩放之外，所以用未变换的鼠标坐标。
        renderButton(mouseX, mouseY, alpha);
    }

    /** 视口装不下整块面板时等比缩小。装得下就恒为 1，行为与加这段之前完全一致。 */
    private void updateUiScale() {
        double needed = panelHeight();
        double available = panelSpaceBottom - panelTop();
        if (Double.isNaN(available) || available <= 8 || needed <= available || needed <= 0) {
            uiScale = 1.0;
            return;
        }
        uiScale = Math.max(MIN_UI_SCALE, available / needed);
    }

    /** 屏幕坐标 -> 布局坐标。缩放是绕（anchorX, panelTop）做的，所以反变换也绕这一点。 */
    private double toLayoutX(double mouseX) {
        return anchorX + (mouseX - anchorX) / Math.max(1.0e-3, uiScale);
    }

    private double toLayoutY(double mouseY) {
        double top = panelTop();
        return top + (mouseY - top) / Math.max(1.0e-3, uiScale);
    }

    /**
     * 按当前配置顺序摆一遍行。
     *
     * <p>背景配色那几行只在「律动词云」样式下出现——它们只对那一种样式有效果，在经典样式下摆出来
     * 只会让人以为调了没反应。</p>
     */
    private void layout() {
        rows.clear();
        double top = panelTop();
        double y = top + PANEL_PADDING + TITLE_HEIGHT;
        y = addRow(KIND_STYLE, 0, y, PICK_ROW_HEIGHT);
        for (int i = 0; i < SETTINGS.length; i++) {
            y = addRow(KIND_SLIDER, i, y, ROW_HEIGHT);
        }
        HudSetting[] styleSliders = styleSettings();
        int[] toggles = styleToggles();
        if (styleSliders.length > 0 || toggles.length > 0) {
            y = addRow(KIND_SECTION, 1, y, SECTION_HEIGHT);
            for (int i = 0; i < styleSliders.length; i++) {
                y = addRow(KIND_STYLE_SLIDER, i, y, ROW_HEIGHT);
            }
            for (int i = 0; i < toggles.length; i++) {
                y = addRow(KIND_STYLE_TOGGLE, i, y, PICK_ROW_HEIGHT);
            }
        }
        if (FullscreenLyricStyle.current() == FullscreenLyricStyle.CADENZA) {
            y = addRow(KIND_SECTION, 0, y, SECTION_HEIGHT);
            y = addRow(KIND_DECOR_MODE, 0, y, PICK_ROW_HEIGHT);
            if (HudConfig.cadenzaDecorColorMode == 1) {
                y = addRow(KIND_BEATS, 0, y, ROW_HEIGHT);
                y = addRow(KIND_COLOR_COUNT, 0, y, PICK_ROW_HEIGHT);
                y = addRow(KIND_SWATCHES, 0, y, SWATCH_ROW_HEIGHT);
                for (int i = 0; i < HSB_LABELS.length; i++) {
                    y = addRow(KIND_HSB, i, y, HSB_ROW_HEIGHT);
                }
            }
        }
        layoutHeight = y - top + PANEL_PADDING;
    }

    private double addRow(int kind, int index, double y, double height) {
        Row row = new Row();
        row.kind = kind;
        row.index = index;
        row.y = y;
        row.height = height;
        rows.add(row);
        return y + height;
    }

    /**
     * @return true 表示这次点击已被本面板吃掉，调用方不应再往下派发
     */
    boolean consumeClick(double mouseX, double mouseY, int mouseButton) {
        if (Double.isNaN(anchorX) || lastAlpha <= .5f) {
            return false;
        }

        if (isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE)) {
            if (mouseButton == 0) {
                expanded = !expanded;
                if (!expanded) {
                    releaseDrag();
                }
            }
            return true;
        }

        if (!expanded) {
            return false;
        }

        // 面板内部的一切判定都在布局坐标系里做，先把鼠标反变换过去。
        double layoutX = toLayoutX(mouseX);
        double layoutY = toLayoutY(mouseY);

        if (!isInside(layoutX, layoutY, anchorX, panelTop(), PANEL_WIDTH, panelHeight())) {
            // 点面板外面就收起来，并且这一下不吃掉：让它照常落到下面的控件上。
            expanded = false;
            releaseDrag();
            return false;
        }

        if (mouseButton == 0 || mouseButton == 1) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (!isInside(layoutX, layoutY, trackLeft(), row.y - 2, trackWidth(), row.height)) {
                    continue;
                }
                handleRowClick(row, layoutX, mouseButton);
                break;
            }
        }
        return true;
    }

    private void handleRowClick(Row row, double mouseX, int mouseButton) {
        boolean right = mouseButton == 1;
        if (row.kind == KIND_STYLE) {
            // 右键往回退一档：九种样式只能单向轮换的话，想回上一种得点八下。
            if (right) FullscreenLyricStyle.previous();
            else FullscreenLyricStyle.next();
            return;
        }
        if (row.kind == KIND_DECOR_MODE) {
            if (!right) {
                HudConfig.cadenzaDecorColorMode = HudConfig.cadenzaDecorColorMode == 1 ? 0 : 1;
                HudConfig.save();
            }
            return;
        }
        if (row.kind == KIND_COLOR_COUNT) {
            // 右键往回退一档：只能往前加的话想从 6 回到 2 得点四下。
            int count = HudConfig.cadenzaColorCount() + (right ? -1 : 1);
            int span = HudConfig.CADENZA_PALETTE_SLOTS - HudConfig.CADENZA_COLOR_COUNT_MIN + 1;
            int normalized = ((count - HudConfig.CADENZA_COLOR_COUNT_MIN) % span + span) % span;
            HudConfig.cadenzaDecorColorCount = HudConfig.CADENZA_COLOR_COUNT_MIN + normalized;
            if (editSlot >= HudConfig.cadenzaColorCount()) {
                editSlot = HudConfig.cadenzaColorCount() - 1;
                editRgbCache = -1;
            }
            HudConfig.save();
            return;
        }
        if (row.kind == KIND_SWATCHES) {
            int slot = swatchAt(mouseX);
            if (slot < 0) return;
            if (right) {
                // 右键把这一槽恢复出厂色：调砸了以后总得有条退路。
                HudConfig.setCadenzaSlotColor(slot, HudConfig.cadenzaDefaultSlotColor(slot));
                if (slot == editSlot) editRgbCache = -1;
                HudConfig.save();
                return;
            }
            if (slot != editSlot) {
                editSlot = slot;
                editRgbCache = -1;
            }
            return;
        }
        if (row.kind == KIND_STYLE_TOGGLE) {
            int[] toggles = styleToggles();
            if (row.index >= 0 && row.index < toggles.length) {
                cycleToggle(toggles[row.index], right);
            }
            return;
        }
        if (right) {
            return;
        }
        if (row.kind == KIND_SLIDER || row.kind == KIND_BEATS || row.kind == KIND_HSB
                || row.kind == KIND_STYLE_SLIDER) {
            dragKind = row.kind;
            dragIndex = row.index;
            applyDrag(mouseX);
        }
    }

    private void updateDrag(double mouseX, double mouseY) {
        if (dragKind < 0) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            releaseDrag();
            return;
        }
        applyDrag(toLayoutX(mouseX));
    }

    private void applyDrag(double mouseX) {
        if (dragKind == KIND_SLIDER) {
            if (dragIndex < 0 || dragIndex >= SETTINGS.length) return;
            HudSetting setting = SETTINGS[dragIndex];
            double fraction = fractionAt(mouseX, trackLeft(), trackWidth());
            setting.setValue((float) (setting.getMin() + (setting.getMax() - setting.getMin()) * fraction));
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_STYLE_SLIDER) {
            HudSetting[] sliders = styleSettings();
            if (dragIndex < 0 || dragIndex >= sliders.length) return;
            HudSetting setting = sliders[dragIndex];
            double fraction = fractionAt(mouseX, trackLeft(), trackWidth());
            setting.setValue((float) (setting.getMin() + (setting.getMax() - setting.getMin()) * fraction));
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_BEATS) {
            double fraction = fractionAt(mouseX, trackLeft(), trackWidth());
            int span = HudConfig.CADENZA_BEATS_PER_COLOR_MAX - HudConfig.CADENZA_BEATS_PER_COLOR_MIN;
            HudConfig.cadenzaBeatsPerColor = HudConfig.CADENZA_BEATS_PER_COLOR_MIN
                    + (int) Math.round(fraction * span);
            pendingSave = true;
            return;
        }
        if (dragKind == KIND_HSB) {
            // 先同步一次：刚换过槽或刚右键恢复过出厂色时，另外两个通道还是上一槽的值，
            // 直接写回去会把颜色写成别的东西（最糟的情况是饱和度与明度都是 0，写出纯黑）。
            // 渲染每帧都会同步，所以正常操作下这里是空转；但正确性不该依赖「点击之前一定渲染过」。
            syncEditColor();
            double fraction = fractionAt(mouseX, hsbTrackLeft(), hsbTrackWidth());
            if (dragIndex == 0) {
                editHue = (float) fraction;
            } else if (dragIndex == 1) {
                editSat = (float) fraction;
            } else {
                // 明度压不到 0：全黑的装饰等于把背景关掉，而这一层本来就只是叠光，
                // 用户看到的会是「调到底就没了」，比留一点底色更像 bug。
                editBri = (float) Math.max(.08, fraction);
            }
            writeEditColor();
        }
    }

    private static double fractionAt(double mouseX, double left, double width) {
        double fraction = (mouseX - left) / Math.max(1.0, width);
        return Math.max(0.0, Math.min(1.0, fraction));
    }

    private void releaseDrag() {
        dragKind = -1;
        dragIndex = -1;
        if (pendingSave) {
            pendingSave = false;
            HudConfig.save();
        }
    }

    /** 把正在编辑的那一槽颜色读成 HSB。只有颜色真的变了才重算，否则拖色相时会被自己写回来的值抖掉。 */
    private void syncEditColor() {
        if (editSlot < 0 || editSlot >= HudConfig.CADENZA_PALETTE_SLOTS) {
            editSlot = 0;
            editRgbCache = -1;
        }
        int rgb = HudConfig.cadenzaSlotColor(editSlot);
        if (rgb == editRgbCache) {
            return;
        }
        editRgbCache = rgb;
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        editHue = hsb[0];
        editSat = hsb[1];
        editBri = hsb[2];
    }

    private void writeEditColor() {
        int rgb = Color.HSBtoRGB(editHue, editSat, editBri) & 0xFFFFFF;
        editRgbCache = rgb;
        HudConfig.setCadenzaSlotColor(editSlot, rgb);
        pendingSave = true;
    }

    private void renderButton(double mouseX, double mouseY, float alpha) {
        boolean hover = isInside(mouseX, mouseY, anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        float backgroundAlpha = alpha * (expanded ? .34f : (hover ? .26f : .16f));
        roundedRect(anchorX, anchorY, BUTTON_SIZE, BUTTON_SIZE, 7, hexColor(1f, 1f, 1f, backgroundAlpha));

        String label = "A-Z";
        double textX = anchorX + (BUTTON_SIZE - FontManager.pf12bold.getStringWidthD(label)) * .5;
        double textY = anchorY + (BUTTON_SIZE - FontManager.pf12bold.getFontHeight()) * .5;
        FontManager.pf12bold.drawString(label, textX, textY,
                hexColor(1f, 1f, 1f, alpha * (hover || expanded ? 1f : .82f)));
    }

    private void renderPanel(double mouseX, double mouseY, float alpha) {
        if (expandAnimation <= .01f) {
            return;
        }

        double eased = Easing.EASE_IN_OUT_QUAD.getFunction().apply((double) expandAnimation);
        float panelAlpha = (float) (alpha * eased);
        double panelTop = panelTop();
        double height = panelHeight();

        api.getGLStateManager().pushMatrix();
        // 从按钮左上角展开：只放大不平移，收起时看起来是被按钮"吸"回去的。
        // uiScale 是「装不下就整体缩小」，展开动画那一档只在动画期间偏离 1。
        scaleAtPos(anchorX, panelTop, uiScale * (.90 + .10 * eased));
        try {
            roundedRect(anchorX, panelTop, PANEL_WIDTH, height, 10, hexColor(.06f, .07f, .09f, panelAlpha * .88f));
            roundedOutline(anchorX, panelTop, PANEL_WIDTH, height, 10, .8,
                    new java.awt.Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, panelAlpha * .16f))));

            FontManager.pf14bold.drawString("歌词显示", anchorX + PANEL_PADDING,
                    panelTop + PANEL_PADDING, hexColor(1f, 1f, 1f, panelAlpha));

            for (int i = 0; i < rows.size(); i++) {
                renderRow(rows.get(i), mouseX, mouseY, panelAlpha);
            }
        } finally {
            api.getGLStateManager().popMatrix();
        }
    }

    private void renderRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        if (row.kind == KIND_STYLE) {
            renderPickRow(row, "歌词样式", FullscreenLyricStyle.current().getDisplayName(),
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_SLIDER) {
            renderSettingRow(row, SETTINGS[row.index], mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_STYLE_SLIDER) {
            HudSetting[] sliders = styleSettings();
            if (row.index >= 0 && row.index < sliders.length) {
                renderSettingRow(row, sliders[row.index], mouseX, mouseY, panelAlpha);
            }
            return;
        }
        if (row.kind == KIND_STYLE_TOGGLE) {
            int[] toggles = styleToggles();
            if (row.index >= 0 && row.index < toggles.length) {
                int id = toggles[row.index];
                renderPickRow(row, toggleLabel(id), toggleValue(id), mouseX, mouseY, panelAlpha);
            }
            return;
        }
        if (row.kind == KIND_SECTION) {
            String title = row.index == 1
                    ? FullscreenLyricStyle.current().getDisplayName() + " 参数"
                    : "背景配色";
            FontManager.pf12bold.drawString(title, trackLeft(), row.y + 3,
                    hexColor(1f, 1f, 1f, panelAlpha * .52f));
            return;
        }
        if (row.kind == KIND_DECOR_MODE) {
            String value = HudConfig.cadenzaDecorColorMode == 1 ? "自定义" : "跟随主题";
            renderPickRow(row, "元素取色", value, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_BEATS) {
            renderBeatsRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_COLOR_COUNT) {
            renderPickRow(row, "颜色数量", HudConfig.cadenzaColorCount() + " 色",
                    mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_SWATCHES) {
            renderSwatchRow(row, mouseX, mouseY, panelAlpha);
            return;
        }
        if (row.kind == KIND_HSB) {
            renderHsbRow(row, mouseX, mouseY, panelAlpha);
        }
    }

    /** 整行按钮：左边标签、右边当前值，点一下换下一个。 */
    private void renderPickRow(Row row, String label, String value,
                               double mouseX, double mouseY, float panelAlpha) {
        double left = trackLeft();
        double width = trackWidth();
        double barHeight = row.height - 4;
        boolean hover = isInside(mouseX, mouseY, left, row.y, width, barHeight);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }

        roundedRect(left, row.y, width, barHeight, 6,
                hexColor(1f, 1f, 1f, panelAlpha * (hover ? .16f : .08f)));

        double textY = row.y + (barHeight - FontManager.pf12bold.getFontHeight()) * .5;
        FontManager.pf12bold.drawString(label, left + 6, textY,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
        FontManager.pf12bold.drawString(value,
                left + width - 6 - FontManager.pf12bold.getStringWidthD(value), textY,
                reAlpha(accent, panelAlpha));
    }

    private void renderSettingRow(Row row, HudSetting setting,
                                  double mouseX, double mouseY, float panelAlpha) {
        double left = trackLeft();
        double width = trackWidth();
        double trackY = row.y + 16;

        FontManager.pf12bold.drawString(setting.getLabel(), left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        String value = String.format("%.2f", setting.getValue());
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        double range = Math.max(1.0e-4, setting.getMax() - setting.getMin());
        double fraction = Math.max(0.0, Math.min(1.0, (setting.getValue() - setting.getMin()) / range));
        boolean hover = (dragKind == row.kind && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    private void renderBeatsRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        double left = trackLeft();
        double width = trackWidth();
        double trackY = row.y + 16;

        FontManager.pf12bold.drawString("换色节拍", left, row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .82f));

        String value = "每 " + HudConfig.cadenzaBeatsPerColor() + " 拍";
        FontManager.pf12bold.drawString(value,
                left + width - FontManager.pf12bold.getStringWidthD(value), row.y,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        int span = HudConfig.CADENZA_BEATS_PER_COLOR_MAX - HudConfig.CADENZA_BEATS_PER_COLOR_MIN;
        double fraction = (HudConfig.cadenzaBeatsPerColor() - HudConfig.CADENZA_BEATS_PER_COLOR_MIN)
                / (double) Math.max(1, span);
        boolean hover = dragKind == KIND_BEATS
                || isInside(mouseX, mouseY, left, trackY - 6, width, TRACK_HEIGHT + 12);
        renderTrack(left, trackY, width, fraction, hover, panelAlpha);
    }

    private void renderTrack(double left, double trackY, double width, double fraction,
                             boolean hover, float panelAlpha) {
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        roundedRect(left, trackY, width, TRACK_HEIGHT, TRACK_HEIGHT * .5,
                hexColor(1f, 1f, 1f, panelAlpha * .18f));
        double fillWidth = width * Math.max(0.0, Math.min(1.0, fraction));
        if (fillWidth > .5) {
            int accent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT);
            roundedRect(left, trackY, fillWidth, TRACK_HEIGHT,
                    Math.min(TRACK_HEIGHT * .5, fillWidth * .5),
                    reAlpha(accent, panelAlpha));
        }
        double knobRadius = hover ? 5.0 : 4.0;
        roundedRect(left + fillWidth - knobRadius, trackY + TRACK_HEIGHT * .5 - knobRadius,
                knobRadius * 2, knobRadius * 2, knobRadius, hexColor(1f, 1f, 1f, panelAlpha));
    }

    /**
     * 色块行：左键选中要编辑的槽，右键把那一槽恢复出厂色。
     *
     * <p>只画前 {@code cadenzaColorCount()} 槽——没参与轮换的槽画出来会让人以为它也在生效。</p>
     */
    private void renderSwatchRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        int count = HudConfig.cadenzaColorCount();
        double cellWidth = swatchWidth(count);
        double y = row.y + (row.height - SWATCH_HEIGHT) * .5;
        for (int i = 0; i < count; i++) {
            double x = trackLeft() + i * (cellWidth + SWATCH_GAP);
            boolean hover = isInside(mouseX, mouseY, x, y, cellWidth, SWATCH_HEIGHT);
            if (hover) {
                CursorUtils.setOverride(CursorUtils.HAND);
            }
            int rgb = HudConfig.cadenzaSlotColor(i) | 0xFF000000;
            roundedRect(x, y, cellWidth, SWATCH_HEIGHT, 5, reAlpha(rgb, panelAlpha));
            boolean selected = i == editSlot;
            if (selected || hover) {
                float outlineAlpha = panelAlpha * (selected ? .95f : .45f);
                roundedOutline(x - 1, y - 1, cellWidth + 2, SWATCH_HEIGHT + 2, 6,
                        selected ? 1.2 : .8,
                        new Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, outlineAlpha))));
            }
        }
    }

    /**
     * 色相 / 饱和度 / 明度三条紧凑滑块。
     *
     * <p>轨道本身就是分段实色画出来的预览条，所以拖之前就能看出往哪边是什么颜色。刻意不用
     * {@code roundedRectGradient*}：那条路径会丢掉颜色的 alpha（见 {@code CadenzaDecorLayer}
     * 里关于 {@code rqg.frag} 的注释），而这里的轨道是要跟着面板一起淡入淡出的。</p>
     */
    private void renderHsbRow(Row row, double mouseX, double mouseY, float panelAlpha) {
        double left = hsbTrackLeft();
        double width = hsbTrackWidth();
        double trackY = row.y + (row.height - HSB_TRACK_HEIGHT) * .5;

        FontManager.pf12bold.drawString(HSB_LABELS[row.index], trackLeft(),
                row.y + (row.height - FontManager.pf12bold.getFontHeight()) * .5,
                hexColor(1f, 1f, 1f, panelAlpha * .62f));

        int segments = row.index == 0 ? HUE_SEGMENTS : RAMP_SEGMENTS;
        double segmentWidth = width / segments;
        for (int i = 0; i < segments; i++) {
            double t = segments == 1 ? 0 : i / (double) (segments - 1);
            int rgb;
            if (row.index == 0) {
                // 色相条固定用较高的饱和度与明度，否则调暗之后整条彩虹会看不出区别。
                rgb = Color.HSBtoRGB((float) t, Math.max(.45f, editSat), Math.max(.65f, editBri));
            } else if (row.index == 1) {
                rgb = Color.HSBtoRGB(editHue, (float) t, Math.max(.35f, editBri));
            } else {
                rgb = Color.HSBtoRGB(editHue, editSat, (float) t);
            }
            double x = left + i * segmentWidth;
            // 段与段之间多铺半像素，避免缩放后出现细缝。
            roundedRect(x, trackY, segmentWidth + .5, HSB_TRACK_HEIGHT, 0,
                    reAlpha(rgb | 0xFF000000, panelAlpha * .92f));
        }
        roundedOutline(left, trackY, width, HSB_TRACK_HEIGHT, HSB_TRACK_HEIGHT * .5, .8,
                new Color(1f, 1f, 1f, Math.max(0f, Math.min(1f, panelAlpha * .22f))));

        double fraction = row.index == 0 ? editHue : (row.index == 1 ? editSat : editBri);
        boolean hover = (dragKind == KIND_HSB && dragIndex == row.index)
                || isInside(mouseX, mouseY, left, trackY - 5, width, HSB_TRACK_HEIGHT + 10);
        if (hover) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        double knobRadius = hover ? 4.5 : 3.6;
        double knobX = left + width * Math.max(0.0, Math.min(1.0, fraction));
        roundedRect(knobX - knobRadius, trackY + HSB_TRACK_HEIGHT * .5 - knobRadius,
                knobRadius * 2, knobRadius * 2, knobRadius, hexColor(1f, 1f, 1f, panelAlpha));
    }

    /** 鼠标横坐标落在第几个色块上；不在任何色块上返回 -1。 */
    private int swatchAt(double mouseX) {
        int count = HudConfig.cadenzaColorCount();
        double cellWidth = swatchWidth(count);
        for (int i = 0; i < count; i++) {
            double x = trackLeft() + i * (cellWidth + SWATCH_GAP);
            if (mouseX >= x && mouseX <= x + cellWidth) return i;
        }
        return -1;
    }

    private double swatchWidth(int count) {
        int safeCount = Math.max(1, count);
        return (trackWidth() - SWATCH_GAP * (safeCount - 1)) / safeCount;
    }

    private double panelTop() {
        return anchorY + BUTTON_SIZE + 6;
    }

    /** 面板高度来自最近一次 {@link #layout()}；还没摆过时给一个只够标题的高度。 */
    private double panelHeight() {
        return layoutHeight > 0 ? layoutHeight : PANEL_PADDING * 2 + TITLE_HEIGHT;
    }

    private double trackLeft() {
        return anchorX + PANEL_PADDING;
    }

    private double trackWidth() {
        return PANEL_WIDTH - PANEL_PADDING * 2;
    }

    private double hsbTrackLeft() {
        return trackLeft() + HSB_LABEL_WIDTH;
    }

    private double hsbTrackWidth() {
        return trackWidth() - HSB_LABEL_WIDTH;
    }

    private boolean isInside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
