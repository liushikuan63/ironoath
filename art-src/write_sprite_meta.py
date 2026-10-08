#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给已收编进 `client/assets/**` 的 png 生成 Cocos 3.8 的 `.png.meta`（九宫格 border 的唯一真源）。

为什么要有这个脚本：`accept_to_runtime.py` 只产图不产 meta，而 `check-ts-meta` 那类门只管 `.ts` 不管 `.png`
⇒ 新素材进包后"没有 meta"或"meta 的 border 不对"都不会有任何门报错，只会在运行时静默切错角
（收口清单 #213 那口"两份数字"的债就是这么欠下的）。V25-c 第一次是手抄一份既有 meta 改的，
一次改一张太慢且容易抄漏字段 ⇒ 这里按**已在俗且已验证的 panel-iron-v1.png.meta 的 schema** 批量生成。

字段公式（无 trim、pivot 0.5，与 Cocos 自己导入的结果逐值一致）：
    rawPosition = [-w/2,-h/2,0, w/2,-h/2,0, -w/2,h/2,0, w/2,h/2,0]
    uv          = [0,h, w,h, 0,0, w,0]
    minPos/maxPos = ±(w/2, h/2, 0)
三处 uuid 引用必须自洽：顶层 `uuid`、texture 子档的 `imageUuidOrDatabaseUri`、
spriteFrame 的 `imageUuidOrDatabaseUri`、以及顶层 `userData.redirect`。

用法：
    python art-src/write_sprite_meta.py <png 路径...> --border L,R,T,B
    python art-src/write_sprite_meta.py <png...> --plain      # 装饰件整图，border 全 0（不参与九宫格）
    python art-src/write_sprite_meta.py <png...> --border 80,80,72,72 --force   # 覆盖既有 meta

⚠ 本脚本**不判对错**：写完必须用运行时回读证（`tools/verify-ui-v25-runtime.mjs` 比 `frame.inset*` 与 meta），
   构建日志的 `missing or invalid = 0` 只证明文件被导入，不证明几何自洽。
"""
import argparse
import io
import json
import os
import sys
import uuid as _uuid

from PIL import Image

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass   # 输出崩不许把判据带崩：本仓实测过 GBK 控制台把量具打成假红（台账 #807）

VER = '1.0.27'


def build_meta(w, h, border):
    top, bottom, left, right = border
    u = str(_uuid.uuid4())
    hw, hh = w / 2.0, h / 2.0
    verts = {
        'rawPosition': [-hw, -hh, 0, hw, -hh, 0, -hw, hh, 0, hw, hh, 0],
        'indexes': [0, 1, 2, 2, 1, 3],
        'uv': [0, h, w, h, 0, 0, w, 0],
        'nuv': [0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0],
        'minPos': [-hw, -hh, 0],
        'maxPos': [hw, hh, 0],
    }
    tex = {
        'importer': 'texture',
        'uuid': u + '@6c48a',
        'displayName': os.path.basename('').replace('.png', ''),
        'id': '6c48a',
        'name': 'texture',
        'userData': {
            'wrapModeS': 'clamp-to-edge', 'wrapModeT': 'clamp-to-edge',
            'imageUuidOrDatabaseUri': u, 'isUuid': True, 'visible': False,
            'minfilter': 'linear', 'magfilter': 'linear', 'mipfilter': 'none', 'anisotropy': 0,
        },
        'ver': VER, 'imported': True, 'files': ['.json'],
        'subMetas': {}, 'extension': '.json',
    }
    frame = {
        'importer': 'sprite-frame',
        'uuid': u + '@f9941',
        'displayName': '',
        'id': 'f9941',
        'name': 'spriteFrame',
        'userData': {
            'trimThreshold': 1, 'rotated': False, 'offsetX': 0, 'offsetY': 0,
            'trimX': 0, 'trimY': 0, 'width': w, 'height': h, 'rawWidth': w, 'rawHeight': h,
            'borderTop': top, 'borderBottom': bottom, 'borderLeft': left, 'borderRight': right,
            'packable': True, 'pixelsToUnit': 100, 'pivotX': 0.5, 'pivotY': 0.5,
            'meshType': 0, 'vertices': verts,
            'rawTextureUuid': u,
        },
        'ver': VER, 'imported': True, 'files': ['.json'],
        'subMetas': {}, 'extension': '.json',
    }
    frame['userData'].pop('rawTextureUuid')
    frame['userData']['imageUuidOrDatabaseUri'] = u + '@6c48a'
    frame['userData']['visible'] = False
    return {
        'ver': VER, 'importer': 'image', 'imported': True, 'uuid': u,
        'files': ['.json', '.png'],
        'subMetas': {'6c48a': tex, 'f9941': frame},
        'userData': {
            'type': 'sprite-frame', 'hasAlpha': True,
            'fixAlphaTransparencyArtifacts': False,
            'redirect': u + '@6c48a',
        },
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('paths', nargs='+')
    ap.add_argument('--border', default=None, help='L,R,T,B 四值，例 80,80,72,72')
    ap.add_argument('--plain', action='store_true', help='装饰件整图：border 全 0')
    ap.add_argument('--force', action='store_true', help='允许覆盖已存在的 .meta')
    a = ap.parse_args()
    if not a.plain and not a.border:
        print('[FAIL] 要么给 --border L,R,T,B，要么给 --plain', file=sys.stderr)
        return 2
    if a.plain:
        border = (0, 0, 0, 0)
    else:
        nums = [int(x) for x in a.border.split(',')]
        if len(nums) != 4 or any(n < 0 for n in nums):
            print('[FAIL] --border 要四个非负整数 L,R,T,B', file=sys.stderr)
            return 2
        l, r, t, b = nums
        border = (t, b, l, r)

    bad = 0
    for p in a.paths:
        if not os.path.isfile(p):
            print('[FAIL] 图不存在 %s' % p); bad += 1; continue
        meta_path = p + '.meta'
        if os.path.exists(meta_path) and not a.force:
            print('[SKIP] meta 已存在（要覆盖加 --force）%s' % meta_path); continue
        with Image.open(p) as im:
            w, h = im.size
        io.open(meta_path, 'w', encoding='utf-8', newline='\n').write(
            json.dumps(build_meta(w, h, border), ensure_ascii=False, indent=2) + '\n')
        print('[OK ] %-46s %dx%d border L%d R%d T%d B%d' % (
            os.path.basename(p), w, h, border[2], border[3], border[0], border[1]))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
