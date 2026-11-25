#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[1.4.24·R34] 从 App 原厂按钮位图里「只抠出图标」，供通知栏自绘布局使用。

背景：
  App 里的小播放栏按钮（floatplay_btn_* / thumbnail_floatplay_btn_prev）是
  「白色圆底 + 灰色图标」的 144×144 位图（夜间版是「深灰圆底 + 更深的灰图标」）。
  通知栏自绘布局原本想直接用它们，但用 setColorFilter 整图染色后，
  圆底和图标被染成同一个颜色 → 变成实心圆点，图标形状全丢（用户实机截图）。

做法：
  1. 统计不透明像素的颜色众数 = 圆底色；再取与圆底色差足够大的众数 = 图标本体色；
  2. 按「像素颜色在 圆底色→图标色 上的投影」反算 coverage（1-圆底、0-图标），
     生成干净的抗锯齿 alpha 遮罩。这样紧贴图标的一圈近圆底色像素会被算成低 alpha
     甚至 0，彻底去掉 R59 的「灰图标外一圈白边 / 夜间脏边」；
  3. 图标本体统一染成通知主文字色（日间 #101010 / 夜间 #ffffffff），保留原 alpha；
  4. 裁剪到包围盒、按尺寸输出透明底 PNG。

[1.4.24·R59] 图标颜色改为「跟通知文字一致」：主文字色 notif_text_primary，
  浅色 #101010 / 深色 #ffffffff（见 values/colors.xml、values-night/colors.xml）。
[1.4.24·R62] 新增播放模式键 notif_big_mode_*（5 种状态，仅展开态大按钮用）：
  原厂 btn_playing_*.9.png 是 169×167 满幅画布、图形只占中间一小块，先裁到
  1px 九宫格导引边以内的内容 bbox，再按 96px 撑满输出。

产物：
  app/src/main/res/drawable-xxhdpi/notif_flat_*.png        （24dp 视觉，收起态按钮）
  app/src/main/res/drawable-xxhdpi/notif_big_*.png         （32dp 视觉，展开态大按钮）
  app/src/main/res/drawable-xxhdpi/notif_big_mode_*.png    （32dp 视觉，播放模式键）
  app/src/main/res/drawable-night-xxhdpi/notif_flat_*.png
  app/src/main/res/drawable-night-xxhdpi/notif_big_*.png
  app/src/main/res/drawable-night-xxhdpi/notif_big_mode_*.png
"""

import math
import os
from collections import Counter
from PIL import Image

SRC_DAY = "app/src/main/res/drawable-xxhdpi"
SRC_NIGHT = "app/src/main/res/drawable-night-xxhdpi"
# 输出到 xxhdpi（3x）：让系统按密度缩放到目标 dp，图形不会被二次缩放糊掉。
# [1.4.24·R35] 原来输出到 drawable-nodpi：nodpi 表示「1px = 1dp」，96px 的图在
# 任何设备上都被当成 96dp，再被 ImageView 缩到 26dp 内容区 → 图形实际只剩 ~7dp，
# 这就是用户说的「按钮小得跟鸡巴一样」。
OUT_DAY = "app/src/main/res/drawable-xxhdpi"
OUT_NIGHT = "app/src/main/res/drawable-night-xxhdpi"

# [1.4.24·R59] 图标统一取通知主文字色 notif_text_primary（日间 #101010 / 夜间 #ffffffff）。
# 必须与 app/src/main/res/values/colors.xml、values-night/colors.xml 保持一致。
PRIMARY_DAY = (0x10, 0x10, 0x10)
PRIMARY_NIGHT = (0xFF, 0xFF, 0xFF)

# [1.4.25·R59 补充] 收藏态必须能一眼分辨。
# R59 把全套图标统一染成主文字色后，favorite_add（未喜欢）与 favorite_cancel（已喜欢）
# 变成同一颗同色心，像素数几乎一致（6276 vs 6302），通知栏上看不出自己点没点上。
# 原厂语义是「灰心=未喜欢 / 红心=已喜欢」，这里让 favorite_cancel 保留品牌红，
# 其余图标继续跟通知文字同色。
FAVORITE_RED = (0xE6, 0x40, 0x40)
COLOR_OVERRIDE = {
    "notif_flat_favorite_cancel": FAVORITE_RED,
    "notif_big_favorite_cancel": FAVORITE_RED,
}

# 原厂资源名 -> 输出名
MAPPING = {
    "floatplay_btn_play": "notif_flat_play",
    "floatplay_btn_pause": "notif_flat_pause",
    "floatplay_btn_next": "notif_flat_next",
    "thumbnail_floatplay_btn_prev": "notif_flat_prev",
    "floatplay_btn_favorite_add": "notif_flat_favorite_add",
    "floatplay_btn_favorite_cancel": "notif_flat_favorite_cancel",
}

# [1.4.24·R62] 播放模式键：原厂九宫格 .9.png -> notif_big_mode_*。
# 只输出展开态大按钮那一套（收起态没有模式键）。
MODE_MAPPING = {
    "btn_playing_shuffle_on": "notif_big_mode_shuffle_on",
    "btn_playing_shuffle_off": "notif_big_mode_shuffle_off",
    "btn_playing_repeat_on": "notif_big_mode_repeat_on",
    "btn_playing_cycle_on": "notif_big_mode_cycle_on",
    "btn_playing_cycle_off": "notif_big_mode_cycle_off",
}

# [1.4.24·R35] 两套尺寸：
#   flat = 24dp 视觉（收起态按钮用，xxhdpi → 72px）
#   big  = 32dp 视觉（展开态大按钮用，xxhdpi → 96px）
SIZES = {"notif_flat": 72, "notif_big": 96}
MODE_TARGET = 96


def _color_dist(a, b):
    """两色 RGB 欧氏距离（含 3 个通道，所以最大约 441）。"""
    return math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2)


def _detect_bg_and_icon(im):
    """返回 (圆底色, 图标本体色)。均取不透明像素的众数。"""
    w, h = im.size
    px = im.load()

    counter = Counter()
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a > 200:
                counter[(r, g, b)] += 1
    if not counter:
        return None, None
    bg = counter.most_common(1)[0][0]

    # 图标本体：与圆底色差 ≥80 的像素里出现最多的颜色。
    far = Counter()
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a > 200 and _color_dist((r, g, b), bg) >= 80:
                far[(r, g, b)] += 1
    icon = far.most_common(1)[0][0] if far else bg
    return bg, icon


def extract_icon(path, target_rgb):
    """返回只含图标的 RGBA 图像：透明底，图标已染成 target_rgb。

    [1.4.24·R59] 关键修复（去白边）：
      旧算法膨胀 1px 后**原样保留**该区域的所有像素，于是紧贴图标的一圈
      「白色圆底 / 夜间深灰圆底」也被保留 → 灰图标外一圈白边（实测日间
      notif_flat_play 不透明像素主色第一名是纯白 293px）。
      新算法把每个像素看成「圆底色 bg 与图标色 icon 的线性混合」，反算覆盖率：
          t = |pixel - bg| / |icon - bg|   （夹到 0..1）
      t 就是该像素属于图标的比例。近圆底色的残留像素 t→0，alpha 自然衰减到透明；
      图标本体 t→1，alpha 保持。这样既不残留白边，也不会把图形本体抠没。

    投影/膨胀/连通域仍沿用旧思路：先用「高 alpha + 色差远」圈出图标主体，
    再膨胀 2px 纳入抗锯齿边；阴影是低 alpha 黑色，不在强图标区域里，会被丢掉。
    """
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()

    bg, icon_color = _detect_bg_and_icon(im)
    if bg is None:
        return None
    span = max(1.0, _color_dist(icon_color, bg))

    # 1) 图标主体：**核心**像素（几乎全不透明 + 与圆底色差很远）。
    #    这里把 alpha 门槛提到 200：圆底边缘那圈半透明灰 (alpha 约 100~160)
    #    色差虽然也够远，但不是图标，若不排除会在图形下方拖出一段浅灰圆弧脏边
    #    （日间暂停键实测）。色差门槛保持 80，夜间图标与深灰圆底色差仅约 97，
    #    抬到 100 会把图标下半截抠没。
    strong = [[False] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a >= 200 and _color_dist((r, g, b), bg) >= 80:
                strong[y][x] = True

    # 2) 膨胀 3px：把核心外的抗锯齿软边纳入处理区域（软边一般 1~2px）。
    #    近底色的像素会被下面的覆盖率算法清零，不会重新引入白边。
    radius = 3
    region = [row[:] for row in strong]
    for y in range(h):
        for x in range(w):
            if not strong[y][x]:
                continue
            for dy in range(-radius, radius + 1):
                for dx in range(-radius, radius + 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w:
                        region[ny][nx] = True

    # 3) 覆盖率反算 alpha，颜色统一替换为 target_rgb（保留抗锯齿软边）
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            if not region[y][x]:
                continue
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            t = min(1.0, _color_dist((r, g, b), bg) / span)
            if t < 0.06:          # 基本就是圆底色（含紧贴图标的残留白圈）→ 透明
                continue
            out_a = int(round(a * t))
            if out_a <= 2:
                continue
            opx[x, y] = (target_rgb[0], target_rgb[1], target_rgb[2], out_a)

    if not out.getbbox():
        return None

    # 4) 只保留面积 >= 最大块 30% 的连通域：图标是一整块，圆底描边/投影弧是
    #    环绕的孤立碎片，8 邻域连通后与图标不连通，直接丢掉。
    return keep_largest_component(out)


def keep_largest_component(image, keep_ratio=0.3, alpha_threshold=8):
    """8 邻域连通域分析，保留面积 >= 最大块 30% 的所有块。

    只留最大块会把「暂停」的两根竖条切掉一根（它们本就不连通）；
    圆底描边/投影碎片是细弧，面积远小于图标主体，30% 阈值能把它们挡掉。

    [1.4.24·R59] 覆盖率反算会留下 alpha 只有个位数的极淡软边，若用 a != 0 判连通，
    这些几乎看不见的像素会把不相干的小块粘成一大片；改用 alpha_threshold 过滤。
    """
    w, h = image.size
    px = image.load()
    seen = [[False] * w for _ in range(h)]
    components = []
    for y in range(h):
        for x in range(w):
            if seen[y][x] or px[x, y][3] < alpha_threshold:
                continue
            stack = [(x, y)]
            seen[y][x] = True
            component = []
            while stack:
                cx, cy = stack.pop()
                component.append((cx, cy))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx]:
                            if px[nx, ny][3] >= alpha_threshold:
                                seen[ny][nx] = True
                                stack.append((nx, ny))
            components.append(component)

    if not components:
        return image
    max_size = max(len(c) for c in components)
    keep = [c for c in components if len(c) >= max_size * keep_ratio]

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    opx = out.load()
    for component in keep:
        for x, y in component:
            opx[x, y] = px[x, y]
    return out


def crop_to_content(image, padding_ratio=0.0):
    """裁掉四周透明边，只留图形本身。

    [1.4.24·R35] 这是上一版「按钮小得跟鸡巴一样」的根因：抠完之后**没有裁剪**，
    96×96 的画布里图形只占中间 28×28，布局再把整张 96px 缩进 26dp 的内容区，
    用户看到的图形实际只有约 7dp。裁到 bbox 后图形撑满整个位图，显示多大全由布局决定。
    """
    bbox = image.getbbox()
    if not bbox:
        return image
    cropped = image.crop(bbox)
    if padding_ratio <= 0:
        return cropped
    pad = int(max(cropped.size) * padding_ratio)
    out = Image.new(
        "RGBA",
        (cropped.width + pad * 2, cropped.height + pad * 2),
        (0, 0, 0, 0),
    )
    out.alpha_composite(cropped, (pad, pad))
    return out


def extract_mode_icon(path, target_rgb):
    """[1.4.24·R62] 处理播放模式键的原厂 .9.png。

    原厂九宫格图 169×167，四周 1px 是拉伸导引边（黑色/透明），图形只占中间一小块
    （如 shuffle_on 内容仅 62×46）。这里：
      1. 先裁掉 1px 九宫格导引边，避免黑色描边被当成图形；
      2. 保留原 alpha（off 态原厂就是半透明，用来区分开/关，不能抹平）；
      3. 颜色统一染成通知主文字色；
      4. 由主流程裁到 alpha 包围盒并按 96px 撑满。
    """
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    # 去掉 1px 导引边
    inner = im.crop((1, 1, w - 1, h - 1))
    px = inner.load()
    out = Image.new("RGBA", inner.size, (0, 0, 0, 0))
    opx = out.load()
    for y in range(inner.height):
        for x in range(inner.width):
            a = px[x, y][3]
            if a <= 2:
                continue
            opx[x, y] = (target_rgb[0], target_rgb[1], target_rgb[2], a)
    return out


def _find_source(src_dir, name):
    for ext in (".png", ".9.png", ".webp"):
        candidate = os.path.join(src_dir, name + ext)
        if os.path.exists(candidate):
            return candidate
    return None


def main():
    for src_dir, out_dir, primary in (
        (SRC_DAY, OUT_DAY, PRIMARY_DAY),
        (SRC_NIGHT, OUT_NIGHT, PRIMARY_NIGHT),
    ):
        os.makedirs(out_dir, exist_ok=True)

        # 原有播放控制键（收藏/上一首/播放/下一首/播放/暂停）——flat + big 两套
        for src_name, out_name in MAPPING.items():
            path = _find_source(src_dir, src_name)
            if path is None:
                print("跳过（源缺失）:", src_name)
                continue
            # [1.4.25·R59 补充] 已喜欢（favorite_cancel）保留品牌红，其余跟通知文字同色。
            target_color = COLOR_OVERRIDE.get(out_name, primary)
            icon = extract_icon(path, target_color)
            if icon is None:
                print("跳过（未找到图标）:", src_name)
                continue
            # [1.4.24·R35] 先裁到内容 bbox，再按目标尺寸输出两套。
            icon = crop_to_content(icon)
            for prefix, target in SIZES.items():
                scale = target / max(icon.size)
                size = (max(1, int(icon.width * scale)), max(1, int(icon.height * scale)))
                scaled = icon.resize(size, Image.LANCZOS)
                dest = os.path.join(out_dir, prefix + "_" + out_name.replace("notif_flat_", "") + ".png")
                scaled.save(dest)
                print("写出:", dest, scaled.size, "源图", os.path.basename(path))

        # [1.4.24·R62] 播放模式键——只输出展开态 big（96px）一套
        for src_name, out_name in MODE_MAPPING.items():
            path = _find_source(src_dir, src_name)
            if path is None:
                print("跳过（模式源缺失）:", src_name)
                continue
            icon = extract_mode_icon(path, primary)
            if not icon.getbbox():
                print("跳过（模式图未找到图形）:", src_name)
                continue
            icon = crop_to_content(icon)
            scale = MODE_TARGET / max(icon.size)
            size = (max(1, int(icon.width * scale)), max(1, int(icon.height * scale)))
            scaled = icon.resize(size, Image.LANCZOS)
            dest = os.path.join(out_dir, out_name + ".png")
            scaled.save(dest)
            print("写出:", dest, scaled.size, "源图", os.path.basename(path))


if __name__ == "__main__":
    main()
