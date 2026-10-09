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
preprocessor = load_module('iron_preprocessor', 'process_generated.py')


def prepare_source(source, row, size):
    """复用既有草稿裁边/方形化，去掉肉眼不可见的alpha噪声撑框。

    只供声明的UI格式收编；不重绘、不改RGB，不覆盖ImageGen原图。
    非方形采用版仍由acceptor的3%比例门阻止压扁。
    """
    if not row.get('prepareBounds'):
        return source
    if not row.get('transparent'):
        raise ValueError('不透明地貌不能走透明裁边')
    prepared = ROOT / 'art-src/generated/drafts/prepared/iron-unified-v3' / source.name
    prepared.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as image:
        rgba = image.convert('RGBA')
        alpha = rgba.getchannel('A')
        rgba.putalpha(alpha.point(lambda value: value if value > preprocessor.TRIM_ALPHA else 0))
        if rgba.getchannel('A').getbbox() is None:
            raise ValueError(f'无可见主体：{source}')
        if size[0] == size[1]:
            preprocessor.square_and_save(rgba, str(prepared), size[0], row['id'])
        else:
            rgba.save(prepared, optimize=True)
    return prepared


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


def write_building_geometry(rows):
    """以采用版实体下边界生成显示枢轴，不抄服务端玩法配表。"""
    values = []
    for row in rows:
        if not any('/buildings/building-' in path for path in row['runtime']):
            continue
        with Image.open(ROOT / row['accepted']) as image:
            solid = image.convert('RGBA').getchannel('A').point(lambda value: 255 if value > 128 else 0)
            bbox = solid.getbbox()
            if bbox is None:
                raise ValueError(f'建筑采用版没有实体：{row["id"]}')
            ratio = (image.height - bbox[3]) / image.height
        values.append((row['id'].replace('-', '_'), ratio))
    if len(values) != 15 or len({key for key, _ in values}) != 15:
        raise ValueError('建筑几何须覆盖15个唯一实际素材；不能空表或遗漏')
    destination = ROOT / 'client/assets/scripts/game/art/ArtFamilies.ts'
    source = destination.read_text(encoding='utf-8-sig')
    start = '// BEGIN GENERATED BUILDING_ART_GEOMETRY'
    end = '// END GENERATED BUILDING_ART_GEOMETRY'
    if source.count(start) != 1 or source.count(end) != 1:
        raise ValueError('建筑几何生成区缺失或不唯一，禁止覆盖其它源码')
    block = start + '''
/** 采用版实体(alpha>128)下缘到原canvas底的比例，只是贴图几何，不是玩法配表。
 * install_unified_v3.py --write-building-geometry现读；Sprite.trim=false保Creator裁边后接地。
 */
const BUILDING_ART_FOOT_RATIO: Readonly<Record<string, number>> = {
''' + ''.join(f'  {key}: {ratio!r},\n' for key, ratio in sorted(values)) + '''}

export function buildingArtFootRatio(configId: string): number {
  return BUILDING_ART_FOOT_RATIO[configId] ?? 0
}
''' + end
    prefix, tail = source.split(start)
    _, suffix = tail.split(end)
    destination.write_text(prefix + block + suffix, encoding='utf-8')
    print(f'[unified-v3] 15 adopted建筑几何已生成到声明区，未知配置图退0，服务端格位不变')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', default='art-src/unified-v3-assets.json')
    parser.add_argument('--only', help='仅收编指定id，逗号分隔；未提供时处理全部')
    parser.add_argument('--write-building-geometry', action='store_true', help='实际读取15个采用版生成显示枢轴')
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
        prepared = prepare_source(source, row, size)
        byte_size = acceptor.accept(str(prepared), str(accepted), size)
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
    if args.write_building_geometry:
        write_building_geometry(rows)
    print(f'[unified-v3] 实际写入{count}个生产路径；原图与资源UUID保留，Cocos几何待真实导入复验')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
