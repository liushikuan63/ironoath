#!/usr/bin/env python3
"""按声明清单收编统一母版，复用既有量化管线并保留生产资源身份。

仅做资源格式/尺寸/元数据接入；美术内容由内置image_gen生成。
不修改源图，不重新生成UUID。最终alpha裁剪几何仍由Cocos导入更新。
"""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


acceptor = load_module('iron_acceptor', 'accept_to_runtime.py')
meta_builder = load_module('iron_meta_builder', 'write_sprite_meta.py')


def install_meta(path, size, borders):
    meta_path = Path(str(path) + '.meta')
    if not meta_path.is_file():
        raise ValueError(f'生产路径缺既有meta，禁止隐式新增UUID：{meta_path}')
    previous = json.loads(meta_path.read_text(encoding='utf-8-sig'))
    updated = copy.deepcopy(previous)
    frame = updated['subMetas']['f9941']['userData']
    # 几何公式复用已有元数据生成器，只拿数值；身份、版本和纹理导入设置保留。
    generated = meta_builder.build_meta(size[0], size[1], borders)['subMetas']['f9941']['userData']
    for key in ('offsetX', 'offsetY', 'trimX', 'trimY', 'width', 'height', 'rawWidth',
                'rawHeight', 'borderTop', 'borderBottom', 'borderLeft', 'borderRight', 'vertices'):
        frame[key] = generated[key]
    identity = lambda meta: (meta['uuid'], meta['subMetas']['6c48a']['uuid'],
                             meta['subMetas']['f9941']['uuid'],
                             meta['subMetas']['6c48a']['userData']['imageUuidOrDatabaseUri'],
                             meta['subMetas']['f9941']['userData']['imageUuidOrDatabaseUri'])
    if identity(updated) != identity(previous):
        raise ValueError(f'UUID引用发生变化：{path}')
    meta_path.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return previous['uuid']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', default='art-src/unified-v3-assets.json')
    parser.add_argument('--only', help='仅收编指定id，逗号分隔；未提供时处理全部')
    args = parser.parse_args()
    rows = json.loads((ROOT / args.manifest).read_text(encoding='utf-8-sig'))['assets']
    selected = set(args.only.split(',')) if args.only else None
    if selected and selected - {row['id'] for row in rows}:
        raise ValueError('选择了清单中不存在的资产')
    count = 0
    for row in rows:
        if selected and row['id'] not in selected:
            continue
        source, accepted = ROOT / row['source'], ROOT / row['accepted']
        size = tuple(row['size'])
        with Image.open(source) as image:
            # 原有方形管线假定输入为正方形；这里显式守住，避免把非方形Sprite压扁。
            if size[0] == size[1] and image.width != image.height:
                raise ValueError(f'方形Sprite源图不是方形，须生成合格母版：{source}')
            if row.get('transparent'):
                alpha = image.convert('RGBA').getchannel('A')
                if alpha.getextrema()[0] != 0 or alpha.getbbox() is None:
                    raise ValueError(f'缺真实透明背景或图像全透明：{source}')
        accepted.parent.mkdir(parents=True, exist_ok=True)
        byte_size = acceptor.accept(str(source), str(accepted), size)
        for destination in row['runtime']:
            target = ROOT / destination
            expected = ROOT / 'client/assets/resources/ui/generated'
            if not target.resolve().is_relative_to(expected.resolve()):
                raise ValueError(f'生产资产越出指定目录：{destination}')
            if not target.is_file():
                raise ValueError(f'不允许覆盖未知生产路径：{destination}')
            borders = tuple(row.get('borders', [0, 0, 0, 0]))  # Top,Bottom,Left,Right
            # 先核meta存在再复制，避免一半PNG已换但没有合法身份。
            if not Path(str(target) + '.meta').is_file():
                raise ValueError(f'缺生产meta：{target}')
            shutil.copyfile(accepted, target)
            identity = install_meta(target, size, borders)
            print(f"{row['id']} -> {destination} {size[0]}x{size[1]} {byte_size}B UUID={identity}")
            count += 1
    if count == 0:
        raise ValueError('收编清单为空，不允许空转')
    print(f'[unified-v3] 实际写入{count}个生产路径；原图与资源UUID保留，Cocos几何待真实导入复验')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
