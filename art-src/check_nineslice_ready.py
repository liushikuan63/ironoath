#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
职责：九宫格 / 装饰件素材的**上屏前**可机器判据 —— 只判三件确实能数的事，退码非 0 即不许进收编环节。
用法：
    python art-src/check_nineslice_ready.py <图1> [<图2> ...] --kind frame|plain
为什么只有三条：第四件本来最想自动化的事（"边带中段有没有混进独立饰块"）**测不出**——
那块暗红皮与周围皮革差在色相不差在亮度，而标准差只度量亮度离散度；三种 σ 写法
（四边平均÷角部 / 四边最差÷角部 / 同边正中÷旁侧）在真有缺陷的样本上分别给出
0.60（判过）、0.96、1.13（判过），与无缺陷样本的 0.52 / 0.68 / 1.03 分不开
（取证见 art-src/弹窗面板美术风格_VibeCoding规格.md §4.6 与台账 #796）。
⇒ 那一维**只能目视四条边的正中**，本工具会把它显式印成"未判"，不许拿统计量替眼睛签字。

--kind 的三种档**判据不同，别共用一条统计量当通用门**（本文件第一版就是这么错的两次）：
  frame  带角饰的主底板（A 档）：装饰必须集中在外圈 ⇒ 判「环带 σ ≥ 3× 中心 σ」——
         这条有功能含义：A 档中心要能被拉伸成任意尺寸，混进装饰就会在宽面板上糊开。
         实测 v0=3.89 / v1=4.04 判过，蓝底负向对照判红。
  plain  条行 / chip（B、C 档）：**不跑 σ 判据**，只跑背景纯绿 + 残留绿 + 比例。
         原稿曾要求"整幅均匀、环带 σ 接近中心 σ"，实测**被否证**：一张完全合格的 B 档条行
         （目视确认无铆钉无角饰无边中 medallion）因上下两道铜边是设计要求而给出 rel=2.77 ⇒ 稳定假红。
  art    整图装饰件（匾额 / 顶饰 / 火漆 / 分隔线）：**不参与 σ 判据** —— 它按原比例整幅缩放、
         永远不被拉伸，所以"中心必须干净"对它没有意义（规格 §二 已写明装饰件不参与退化判据）。
         只判背景纯绿与残留绿两条。误用 frame 判它必然假红（实测匾额中心含矛杆，σ 反而高于环带）。
"""
import argparse
import sys
from PIL import Image

GREEN_HI = 110      # 与 process_generated.py 同口径，两处改要一起改
GREEN_LO = 30
RESIDUAL_LIMIT = 0.5   # 百分比，与 process_generated.py 的 RESIDUAL_LIMIT 一致


def alpha_of(px, x, y):
    r, g, b, _ = px[x, y]
    d = g - max(r, b)
    if d <= GREEN_LO:
        return 255
    if d >= GREEN_HI:
        return 0
    return int(255 * (GREEN_HI - d) / (GREEN_HI - GREEN_LO))


def corner_green_ok(px, w, h):
    """四角 8x8 是否真的是纯绿底 —— 不是的话抠绿会整批失败。"""
    for cx, cy in ((0, 0), (w - 8, 0), (0, h - 8), (w - 8, h - 8)):
        rs = gs = bs = n = 0
        for y in range(cy, cy + 8):
            for x in range(cx, cx + 8):
                r, g, b, _ = px[x, y]
                rs += r; gs += g; bs += b; n += 1
        if not (gs // n > 215 and rs // n < 45 and bs // n < 45):
            return False, (rs // n, gs // n, bs // n)
    return True, None


def luma_std(px, mask, x0, y0, x1, y1, step=2):
    vals = []
    for y in range(y0, y1, step):
        for x in range(x0, x1, step):
            if 0 <= y < len(mask) and 0 <= x < len(mask[0]) and mask[y][x]:
                r, g, b, _ = px[x, y]
                vals.append(0.299 * r + 0.587 * g + 0.114 * b)
    if len(vals) < 20:
        return None
    m = sum(vals) / len(vals)
    return (sum((v - m) ** 2 for v in vals) / len(vals)) ** 0.5


def bevel_band_ratio(px, mask, l, t, r, b):
    """量「上下边带厚度占图高比」—— C/B 档的可见性判据（G9 实测 1.7% 铺到 32px 就消失 ⇒ 下限 7%）。

    <p>不要求亮带从最外沿连续：素材最外一圈常是暗描边（实测 button-iron-v0 顶行 luma 45.7、
    第 12 行才 175.4），从边缘数连续亮像素会得到 0 这种离谱读数。改成在上下 1/4 区内数亮行数。
    """
    bh = b - t + 1
    bw = r - l + 1
    if bh < 8 or bw < 8:
        return None
    lumas = []
    for y in range(t, b + 1):
        xs = [x for x in range(l + bw // 3, l + 2 * bw // 3) if mask[y][x]]
        if not xs:
            continue
        seg = [0.299 * px[x, y][0] + 0.587 * px[x, y][1] + 0.114 * px[x, y][2] for x in xs]
        lumas.append((y, sum(seg) / len(seg)))
    if len(lumas) < 8:
        return None
    vs = [v for _, v in lumas]
    thr = min(vs) + (max(vs) - min(vs)) * 0.45
    q = max(1, len(lumas) // 4)
    top = sum(1 for _, v in lumas[:q] if v >= thr)
    bot = sum(1 for _, v in lumas[-q:] if v >= thr)
    return (top + bot) / float(bh)


def check(path, kind):
    im = Image.open(path).convert('RGBA')
    w, h = im.size
    px = im.load()
    fails, notes = [], []

    ok, bad = corner_green_ok(px, w, h)
    notes.append('背景四角纯绿 : %s%s' % (ok, '' if ok else '  取样 %s' % (bad,)))
    if not ok:
        fails.append('bg-not-pure-green')

    mask = [[alpha_of(px, x, y) > 160 for x in range(w)] for y in range(h)]
    xs = [x for y in range(h) for x in range(w) if mask[y][x]]
    ys = [y for y in range(h) for x in range(w) if mask[y][x]]
    if not xs:
        print('%s  主体为空（抠绿后没有任何像素）' % path)
        return ['empty-subject'], []
    l, t, r, b = min(xs), min(ys), max(xs), max(ys)
    bw, bh = r - l + 1, b - t + 1

    soft = sum(1 for y in range(h) for x in range(w) if 0 < alpha_of(px, x, y) < 255)
    resid = soft / float(w * h) * 100.0
    notes.append('抠绿残留     : %.3f%%（阈值 %.1f%%）' % (resid, RESIDUAL_LIMIT))
    if resid > RESIDUAL_LIMIT:
        fails.append('green-residual-over-limit')

    ratio = bw / float(bh)
    notes.append('bbox         : %dx%d  比例 %.3f:1' % (bw, bh, ratio))

    cx0, cy0 = l + int(bw * 0.2), t + int(bh * 0.2)
    cx1, cy1 = r - int(bw * 0.2), b - int(bh * 0.2)
    center = luma_std(px, mask, cx0, cy0, cx1, cy1)
    hb, vb = max(4, int(bh * 0.12)), max(4, int(bw * 0.12))
    rings = [v for v in (
        luma_std(px, mask, l, t, r + 1, t + hb),
        luma_std(px, mask, l, b - hb, r + 1, b + 1),
        luma_std(px, mask, l, t, l + vb, b + 1),
        luma_std(px, mask, r - vb, t, r + 1, b + 1)) if v is not None]
    ring = sum(rings) / len(rings) if rings else None
    if kind != 'frame':
        # σ 判据只对 A 档有意义：它的"中心必须干净"是九宫格能拉伸的**功能前提**。
        # plain 档上下两道铜边是设计要求，环带 σ 必然高于中心（实测 rel=2.77 而素材合格）；
        # art 档永不拉伸，中心含装饰是设计。⇒ 对这两类跑 σ 判据只会稳定假红。
        notes.append('σ 判据       : 不适用（%s 档；中心/边带的亮度差是设计，不是缺陷）' % kind)
        if kind == 'plain':
            br = bevel_band_ratio(px, mask, l, t, r, b)
            if br is None:
                notes.append('边带占高比   : 无法度量（采样区太小）')
            else:
                # **只印不判**：本量法与目视不一致（合格条行印出 4.4%、目视约 6% 的按钮印出 10.2%，
                # 方向相反），说明"宽度中段 1/3 + 45% 亮度分位"这套取法测的不是边带厚度本身。
                # 拿一条抓不住真值的判据当门，会同时放过缺陷和误杀好素材 ⇒ 降级为提示，
                # 有效量法待 V25-b 后续用剖面峰值法或人工标尺重建（规格 §4.6 的"边厚未验证"仍未关）。
                notes.append('边带占高比   : %.1f%%（**仅提示，未验证**：量法与目视不一致，不作判据）' % (br * 100,))
    elif center is None or ring is None:
        fails.append('sigma-unmeasurable')
        notes.append('σ 中心/环带  : 采样区太小，无法度量')
    else:
        rel = ring / center if center else 99.0
        notes.append('σ 中心=%.1f 环带=%.1f  环带/中心=%.2f' % (center, ring, rel))
        if kind == 'frame':
            notes.append('（frame）装饰集中在外圈 : %s' % (rel >= 3.0,))
            if rel < 3.0:
                fails.append('frame-ornament-not-concentrated-in-band')
        else:
            notes.append('（plain）整幅均匀 : %s' % (rel <= 2.0,))
            if rel > 2.0:
                fails.append('plain-band-carries-ornament')

    notes.append('** 未判（只能目视）：四条边的正中是否混入独立饰块 —— σ 测不出色相差异，见台账 #796')
    return fails, notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('paths', nargs='+')
    ap.add_argument('--kind', choices=('frame', 'plain', 'art'), default='frame')
    a = ap.parse_args()
    bad = 0
    for p in a.paths:
        fails, notes = check(p, a.kind)
        print('\n[%s] %s' % (a.kind, p))
        for n in notes:
            print('  ' + n)
        if fails:
            bad += 1
            print('  判据失败：%s' % ' / '.join(fails))
        else:
            print('  三条可机器判据全过（第四维仍须目视）')
    print('\n[check-nineslice] %d 份检查 / %d 份判据失败' % (len(a.paths), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
