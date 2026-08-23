# -*- coding: utf-8 -*-
"""从 Material Design Icons 的 SVG 生成 HUD 设置界面用的图标字体。

设计要点：
  * upem = 1000，与项目其它图标字体一致。
  * SVG 的 24x24 网格整格映射到 0..1000（y 轴翻转）。图标在网格里的原始位置完整保留，
    所以渲染时按「em 方格」居中就能得到与 Material 设计一致的视觉对齐，
    不需要按各自的墨迹高度归一化（那会把 chevron 之类细长图标撑得和实心块一样高）。
  * 额外合成一个满格实心方块 U+E2FF 作为「定位参考框」：运行时量它的墨迹盒就等于 em 方格
    相对 drawString 原点的位置，于是可以直接复用 GlyphInkMetrics 做居中。
  * hhea 的 ascent/descent 留出 10% 余量，避免字形贴图把满格图标的边缘切掉 1px。

字形来源：@material-design-icons/svg 的 round 一档（Material Design Icons，Apache-2.0）。
用法：python make_font.py <round 目录> <输出 ttf>
"""
import os
import re
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.misc.transform import Transform
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.svgLib.path import parse_path

UPEM = 1000
GRID = 24.0
SCALE = UPEM / GRID
ASCENT = 1100
DESCENT = 100

SRC = sys.argv[1]
OUT = sys.argv[2]

# 码位 -> (字形名, MDI 文件名)
ICONS = [
    (0xE200, "search", "search"),
    (0xE201, "close", "close"),
    (0xE202, "collapse", "expand_less"),
    (0xE203, "expand", "expand_more"),
    (0xE204, "chevronRight", "chevron_right"),
    (0xE205, "chevronLeft", "chevron_left"),
    (0xE206, "restore", "restart_alt"),
    (0xE207, "modernUi", "dashboard"),
    (0xE208, "classicUi", "view_sidebar"),
    (0xE209, "dragHandle", "drag_indicator"),
    (0xE20A, "check", "check"),
    (0xE20B, "warning", "warning"),
    (0xE20C, "mic", "mic"),
    (0xE20D, "subtitles", "subtitles"),
    (0xE20E, "island", "notifications_active"),
    (0xE20F, "album", "album"),
    (0xE210, "palette", "palette"),
    (0xE211, "move", "open_with"),
    (0xE212, "tune", "tune"),
    (0xE213, "colorize", "colorize"),
    (0xE214, "fullscreen", "fullscreen"),
    (0xE215, "caption", "closed_caption"),
    (0xE216, "speed", "speed"),
    (0xE217, "ruler", "straighten"),
    (0xE218, "info", "info"),
    (0xE219, "visibility", "visibility"),
    (0xE21A, "sparkle", "auto_awesome"),
    (0xE21B, "timer", "timer"),
    (0xE21C, "note", "music_note"),
    (0xE21D, "refresh", "refresh"),
    (0xE21E, "back", "arrow_back"),
    (0xE21F, "grip", "drag_handle"),
]

FRAME_CODE = 0xE2FF
FRAME_NAME = "frame"
FRAME_PATH = "M0 0h24v24H0z"

PATH_RE = re.compile(r'\sd="([^"]+)"')


def read_paths(svg_path):
    with open(svg_path, "r", encoding="utf-8") as handle:
        text = handle.read()
    found = PATH_RE.findall(text)
    if not found:
        raise ValueError("no path in " + svg_path)
    # MDI 偶尔把图标拆成多段 path，全部收下。
    return found


def build_glyph(path_list):
    pen = TTGlyphPen(None)
    # SVG 的三次贝塞尔要先降成 TrueType 的二次曲线。0.6 unit 的误差在 1000 upem 下
    # 远小于一个渲染像素，视觉上没有区别。
    quad = Cu2QuPen(pen, max_err=0.6)
    transform = TransformPen(quad, Transform(SCALE, 0, 0, -SCALE, 0, UPEM))
    for path in path_list:
        parse_path(path, transform)
    return pen.glyph()


def main():
    glyph_order = [".notdef"]
    glyphs = {".notdef": TTGlyphPen(None).glyph()}
    cmap = {}
    missing = []

    for code, name, filename in ICONS:
        svg = os.path.join(SRC, filename + ".svg")
        if not os.path.isfile(svg):
            missing.append(filename)
            continue
        glyphs[name] = build_glyph(read_paths(svg))
        glyph_order.append(name)
        cmap[code] = name

    glyphs[FRAME_NAME] = build_glyph([FRAME_PATH])
    glyph_order.append(FRAME_NAME)
    cmap[FRAME_CODE] = FRAME_NAME

    if missing:
        print("MISSING: " + " ".join(missing))
        raise SystemExit(2)

    builder = FontBuilder(UPEM, isTTF=True)
    builder.setupGlyphOrder(glyph_order)
    builder.setupCharacterMap(cmap)
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (UPEM, 0) for name in glyph_order})
    builder.setupHorizontalHeader(ascent=ASCENT, descent=-DESCENT)
    # nameID 3(uniqueFontIdentifier) 与 4(fullName) 必须给：Java 的
    # sun.font.TrueTypeFont.initNames() 找不到 nameID 4 就直接抛
    # FontFormatException("Font name not found")，Font.createFont 整份字体加载失败。
    # 只给 familyName/styleName/psName 的字体在别的工具里能正常打开，只有 AWT 会拒绝。
    builder.setupNameTable({
        "familyName": "hud-settings-icons",
        "styleName": "Regular",
        "uniqueFontIdentifier": "hud-settings-icons;1.0",
        "fullName": "hud-settings-icons",
        "psName": "hud-settings-icons",
        "version": "1.0",
    })
    builder.setupOS2(sTypoAscender=ASCENT, sTypoDescender=-DESCENT,
                     usWinAscent=ASCENT, usWinDescent=DESCENT,
                     sxHeight=int(UPEM * 0.5), sCapHeight=UPEM)
    builder.setupPost()
    builder.save(OUT)
    print("OK glyphs=%d out=%s size=%d" % (len(cmap), OUT, os.path.getsize(OUT)))
    for code, name, _ in ICONS:
        print("  U+%04X %s" % (code, name))
    print("  U+%04X %s (locator)" % (FRAME_CODE, FRAME_NAME))


main()
