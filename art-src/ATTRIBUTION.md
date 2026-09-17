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
