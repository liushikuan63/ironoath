# 美术资源署名

## Kenney UI Pack - Adventure

- 来源：<https://github.com/iwenzhou/kenney>
- 提交：`b7729283e47d38f203d85560cfda5b2c1fd6fc7e`
- 许可证：CC0 1.0 Universal
- 本地路径：`art-src/kenney/ui-pack-adventure/`

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

接入界面后必须在游戏内“制作人员 / 开源许可”页保留：

```text
Icons made by Delapouite, Lorc, Skoll, Heavenly Dog from https://game-icons.net
```

`art-src/game-icons/png/` 与 `art-src/game-icons/icons-atlas.png` 是由上述 SVG 确定性栅格化得到的派生文件，仍沿用 CC BY 3.0 署名要求。

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
