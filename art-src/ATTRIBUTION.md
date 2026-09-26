# 美术资源署名

## Kenney UI Pack - Adventure

- 来源：<https://github.com/iwenzhou/kenney>
- 提交：`b7729283e47d38f203d85560cfda5b2c1fd6fc7e`
- 许可证：CC0 1.0 Universal
- 本地路径：`art-src/kenney/ui-pack-adventure/`

## Kenney 通用素材包（2026-09-16 增补，直连 kenney.nl 下载）

以下 6 个包均为 **CC0 1.0 Universal**（可商用、不强制署名；本项目仍登记备查）。
每个包目录内保留其自带 `License.txt`（写有包版本与 CC0 声明），并放一份完整 CC0 法律文本 `LICENSE-CC0.md`。

| 包 | 版本 | 来源 | 本地路径 |
|---|---|---|---|
| UI Pack | 2.0 | <https://kenney.nl/assets/ui-pack> | `art-src/kenney/ui-pack/` |
| UI Pack - RPG Expansion | 未标注 | <https://kenney.nl/assets/ui-pack-rpg-expansion> | `art-src/kenney/ui-pack-rpg-expansion/` |
| Fantasy UI Borders | 1.0 | <https://kenney.nl/assets/fantasy-ui-borders> | `art-src/kenney/fantasy-ui-borders/` |
| Game Icons | 未标注 | <https://kenney.nl/assets/game-icons> | `art-src/kenney/game-icons/` |
| Board Game Icons | 1.1 | <https://kenney.nl/assets/board-game-icons> | `art-src/kenney/board-game-icons/` |
| Generic Items | #1 | <https://kenney.nl/assets/generic-items> | `art-src/kenney/generic-items/` |

下载日期 2026-09-16；文件清单与用途见 `manifest.json` 的 `library.kenneyPacks`。

## Game-icons

- 来源：<https://github.com/game-icons/icons>
- 提交：`82d948812bfe3f269ef8f731dcdb07b08160edc4`
- 许可证：Creative Commons Attribution 3.0（个别图标为 CC0，仓库总许可证按 CC BY 3.0 处理）
- 本地路径：`art-src/game-icons/`

本项目采用以下作者图标：

- Delapouite，<https://delapouite.com>
- Lorc，<https://lorcblog.blogspot.com>
- Skoll
- Heavenly Dog，<https://www.gnomosygoblins.blogspot.com>
- Sbed（2026-09-16 增补：support / block / arena）
- Faithtoken（2026-09-16 增补：world-boss）
- Andy Meneely（2026-09-16 增补：report）

接入界面后必须在游戏内“制作人员 / 开源许可”页保留：

```text
Icons made by Delapouite, Lorc, Skoll, Heavenly Dog, Sbed, Faithtoken, Andy Meneely from https://game-icons.net
```

`art-src/game-icons/png/` 与 `art-src/game-icons/icons-atlas.png` 是由上述 SVG 确定性栅格化得到的派生文件，仍沿用 CC BY 3.0 署名要求。2026-09-16 增补的 34 个功能图标（`svg/systems/` 与 `png/systems/`）逐项作者归属见 `manifest.json` 的 `library.gameIconsSystems.items`。

## 项目生成资源

- 模型：`gpt-image-2`
- 调用方式：用户授权的 OpenAI 兼容图片接口
- 输出：`art-src/generated/accepted/`
- 用途：主面板、按钮四态、地图地块、地图实体
- 处理：纯色背景抠除、透明边缘柔化、透明裁边、尺寸压缩

生成批次未使用第三方受版权保护的参考图；发布前按模型提供商条款和上线检查清单完成一次法务复核即可。

## Noto CJK

- 来源：<https://github.com/notofonts/noto-cjk>
- 提交：`f8d157532fbfaeda587e826d4cd5b21a49186f7c`
- 许可证：SIL Open Font License 1.1
- 状态：来源已确认，尚未复制字体文件；接入前必须做中文字形子集化，避免首包超预算。

## 内城舞台底图（AI 重绘版）

- 构图来源：用户在 2026-09-19 对话中直接提供的目标效果图（本机剪贴板 PNG）。
  原始归档：`art-src/generated/concepts/a16-city-composition/city-reference-user-v0.png`。
- 加工：2026-09-21 经图像编辑模型重绘（`gpt-image-2.5-sunburst`，OpenAI 兼容中转）。
  区域掩码与提示词见 `tmp/city-base.py`；重绘**抹掉除主堡外的 15 类功能建筑**，
  保留地形、道路、城墙、城门、装饰民居、广场帐篷与树石。
- 运行时产物：`client/assets/resources/ui/generated/city/city-base-v1.png`
  （1792×1024，224 色调色板，约 1.38 MB）；由 `ArtFamilies.CITY_STAGE_ASSETS.reference` 登记。
- **为什么用重绘版而不是原图**：① 原图的再分发/公开发布许可尚未由用户书面确认，
  重绘版与原图不再逐像素相同，显著降低直接复用第三方画面的风险；
  ② 只有抹掉功能建筑，「未建造建筑不能预画在底图中冒充存在」（内城规格 §1.2）才成立。
- 授权边界：重绘版仍以用户提供的图为构图依据；对外发布前仍需确认用户对原始素材的权利来源。
- **原始效果图与它的旧派生版都不参与构建**：旧派生版 `city-scene-reference-v1.png`
  已于 2026-09-21 删除（不再被任何代码引用），原图仅作为 `art-src` 归档与构图依据。

### v2（2026-09-26，当前运行时底图）

- 起因：v1 的擦除是**一次调用 + 十个巨大矩形掩膜**（最大一块占画面 41%×34%），
  模型把抹空区填成带光晕的喷枪疤，玩家在内城看到的就是"贴图断裂"。
- 加工链（均可复跑）：`art-src/redraw_city_base.py` 从原图出发、紧掩膜分四pass 串行擦除
  （每pass 后归一回 1792×1024，否则掩膜随返回尺寸漂移）；`art-src/patch_city_base.py --warp`
  对草地门控区做低频位移去相关，打散模型自带的重复草纹（建筑/墙/路门控为 0、像素不动）；
  `art-src/install_city_base.py` 量化 224 色并生成新 uuid 的 meta。
- **实测事实：该中转的 `/images/edits` 不执行掩膜**（品红填充探针零命中），
  擦除效果完全由整图提示词决定；因此"逐区精修"路线被放弃，重复纹改由确定性后处理解决。
- 画中保留的礼拜堂（原学院位置）裁定为**环境建筑**：它不属于 15 类功能正稿，
  玩家的功能建筑正稿仍按锚点叠画，不构成规格 §1.2 的"未建预画"。
- 运行时产物：`client/assets/resources/ui/generated/city/city-base-v2.png`
  （1792×1024，224 色，约 0.96 MB，比 v1 小 27%）；v1 保留在仓内作回滚点，不再被引用。
