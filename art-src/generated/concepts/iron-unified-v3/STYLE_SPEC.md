# 铁誓统一美术母版 · v3（2026-10-10）

来源：本会话内置 `image_gen`；未调用CLI/API，未指定或推断底层模型版本。用户明确要求重制当前风格不一致的贴图。母版 `master-city-v3.png` 是风格与材质参考，不是运行时背景，不是游戏已达上线标准的证据。

统一约束：固定斜俯视正交三分之四视角，写实低魔中世纪石堡；冷灰石材/石板瓦、暖橡木、低饱和橄榄植被，左上柔和日光、右下接地阴影，古铜小五金与少量暗红布。主次体量清晰，形体优先于微纹理，无全图棕染、亮金粗框、发光魔法或Q版比例。所有功能建筑由真实存档独立绘，舞台不得烘焙它们；母版中的背景建筑只用于材质与比例参考，不增加功能或宗教设定。

后续资产均引用这张母版，逐件独立生成并保真实alpha；建筑基座在下缘、统一机位/光向/接地，地形与界面材质同组颜色。实际alpha轮廓、尺寸、九宫格边框以采用产物和Cocos导入读数为准。版本化源图由现有accepted→runtime管线接入，保生产ArtKey/UUID。

母版提示（原文）：

```text
Use case: stylized-concept. Asset type: unified art-direction master reference for the original 2D medieval strategy game Chronicles of Kings: Iron Oath; this is a reference image, not a UI screenshot. Create an exceptionally coherent, release-quality medieval stone castle town seen from a fixed elevated orthographic three-quarter camera, approximately 35-degree downward view, landscape composition. The central royal keep is the tallest landmark on a raised stone terrace, surrounded by a few carefully spaced stone-and-timber civic and military buildings and a readable cobbled main road branching into quieter paths. The camera angle, physical scale, roof geometry and ground contact must be consistent across ALL buildings. Architectural forms are grounded and realistic with clean game-readable silhouettes, hand-painted PBR-like surfaces with restrained detail, NOT miniature toy-like or chibi, NOT photomontage, NOT a screenshot of another game. Unified broad soft daylight from upper left; contact shadows fall lower right. Cool limestone and slate roofs contrast with warm oak wood, subdued olive/sage vegetation; use weathered bronze fittings and very small deep oxblood cloth accents. Keep stone cleanly gray, avoid overall yellow/brown tint, avoid brilliant gold ornament, neon colors, magical glow, blurry brown soup or repeating texture stamps. The town should feel spacious and deliberately composed, not densely packed with inconsistent building cutouts: wide negative spaces between functions, ground visually continuous, no square building platforms or grid. Main castle near upper-middle, a modest foreground road leads toward it, quiet wooded hills and distant ridge at the outer edge. Sharp large forms and exquisite material cohesion that will survive downsampling into a game. No text, no labels, no UI, no watermarks, no baked buttons, no people in the foreground. This master will be the sole visual reference for separate transparent building sprites, ground-only stage, world map and dark iron/bronze UI.
```

制作检查：已目视主堡、木石附属建筑与场景光源/石色/瓦色同组；当前母版仍含建筑，不能原样进入运行时。后续空舞台须完整去除这些实体，15类独立建筑分别采用；所有正式验收取真实页面组合图。
