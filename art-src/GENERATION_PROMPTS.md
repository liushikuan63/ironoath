# 生成美术提示词

生成目标是与现有暗红、铜金、冷兵器乱世风格统一的横屏 SLG 资源，不带文字、不带水印、不带现代 UI 元素。

## 1. 主面板九宫格

```text
Use case: stylized-concept
Asset type: 2D mobile strategy game UI nine-slice panel
Primary request: ornate dark iron and aged copper panel frame for a medieval Chinese-inspired strategy game
Composition/framing: wide rectangular panel, clean empty center, thick readable border, matching corner geometry
Color palette: charcoal brown, aged bronze, muted dark red accents
Materials/textures: hammered iron, worn leather, subtle soot, crisp game-ready alpha edges
Constraints: no text, no letters, no symbols, no watermark, seamless nine-slice edges, centered symmetrical frame
Avoid: blue sci-fi accents, neon, glossy mobile-game gradients, photorealistic perspective
```

## 2. 按钮状态组

```text
Use case: stylized-concept
Asset type: 2D strategy game UI button sprite sheet
Primary request: primary, pressed, disabled, and hover states for a medieval copper-and-iron command button
Composition/framing: four separate wide buttons, equal size, centered, generous padding for Chinese labels
Color palette: dark charcoal base, warm copper border, muted brick-red pressed state, desaturated disabled state
Materials/textures: aged metal and leather, readable at small size
Constraints: no text, no letters, no watermark, transparent background, consistent nine-slice-safe edges
Avoid: glossy candy buttons, neon glow, rounded mobile app styling
```

## 3. 世界地图地块图集

```text
Use case: stylized-concept
Asset type: top-down strategy game terrain tile atlas
Primary request: seamless square terrain tiles for grass, forest, rocky hills, mountain, river, farmland, dirt road, and wasteland
Composition/framing: 4 by 4 atlas, each tile exactly the same square size, flat orthographic top-down view, tile edges must join cleanly
Color palette: muted olive green, umber, charcoal stone, desaturated water blue, dusty grain gold
Materials/textures: painterly game art, restrained detail, readable when zoomed out
Constraints: no text, no icons, no characters, no watermark, no borders between cells, seamless edges
Avoid: realistic satellite imagery, cartoon candy colors, perspective buildings, high-contrast noise
```

## 4. 地图实体图标组

```text
Use case: stylized-concept
Asset type: top-down strategy game map entity sprites
Primary request: separate player city, neutral monster camp, resource point, alliance building, and marching army markers drawn in the same medieval military style
Composition/framing: one centered object per image, orthographic top-down, readable silhouette, transparent background
Color palette: dark red player city, charcoal monster camp, moss green resource point, steel blue alliance building, aged gold march marker
Materials/textures: painted metal, timber, stone, cloth banners, game-ready edges
Constraints: no text, no letters, no watermark, no map base tile, transparent background
Avoid: perspective camera, modern icons, excessive glow, tiny unreadable detail
```

生成后的第一轮只做低/中质量草稿筛选，采用版本再进入 `art-src/generated/accepted/`，淘汰版本不打包。

## 已采用

- `ui-panel-kingdom-v1.png`：1024×1024 低质量草稿，抠绿、裁边后压到 512×354。
- `ui-button-command-v1*.png`：按钮母版抠绿、裁边后压到 512×191，并派生 hover / pressed / disabled。
- `map-terrain-atlas-v1.png`：4×4 地块图集，最终压缩到 512×512，单元格 128×128。
- `map-player-city-v1.png`、`map-monster-camp-v1.png`、`map-resource-point-v1.png`、`map-alliance-building-v1.png`、`map-march-marker-v1.png`：3×2 母版拆成 5 张 256×256 透明 PNG。
