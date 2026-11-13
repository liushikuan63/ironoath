/** 浏览器侧量具：坐标来自真实 UITransform，离屏正文按 Mask 裁剪，不能当成导航侵占。 */
export function readDialogGeometry(spec) {
  const cc = window.cc
  const game = cc.director.getScene()?.getChildByName('Canvas')?.getChildByName('Game')
  if (!game) return { error: 'no-game' }
  const find = (root, name) => {
    if (root.name === name) return root
    for (const child of root.children) { const hit = find(child, name); if (hit) return hit }
    return null
  }
  const host = find(game, spec.host)
  if (!host?.activeInHierarchy) return { error: 'no-active-host', host: spec.host }
  const gameBox = game.getComponent('cc.UITransform')
  const bounds = node => {
    const box = node?.getComponent('cc.UITransform')
    if (!box) return null
    // 3.8.7 getBoundingBoxToWorld会并入直接子节点，Mask不能把离屏content算进自己的裁剪窗。
    const left = -box.width * box.anchorX, bottom = -box.height * box.anchorY
    const points = [[left, bottom], [left + box.width, bottom], [left, bottom + box.height], [left + box.width, bottom + box.height]]
      .map(([x, y]) => gameBox.convertToNodeSpaceAR(box.convertToWorldSpaceAR(new cc.Vec3(x, y, 0))))
    const minX = Math.min(...points.map(point => point.x)), minY = Math.min(...points.map(point => point.y))
    return { x: minX, y: minY, width: Math.max(...points.map(point => point.x)) - minX,
      height: Math.max(...points.map(point => point.y)) - minY }
  }
  const inside = (inner, outer) => inner && outer && inner.x >= outer.x - 1 && inner.y >= outer.y - 1
    && inner.x + inner.width <= outer.x + outer.width + 1 && inner.y + inner.height <= outer.y + outer.height + 1
  const nav = game.getComponent('PanelNav')
  const area = nav?.contentRectFor(nav.current())
  if (!area) return { error: 'no-real-content-rect' }
  const viewport = find(host, spec.viewport ?? 'DialogContent')
  const scroll = viewport?.getComponent('cc.ScrollView')
  const clip = bounds(viewport)
  const frame = find(host, spec.frame ?? 'card')
  const frameBox = bounds(frame)
  const frameRender = frame?.getChildByName('DialogFrameArt')?.activeInHierarchy
    ? frame.getChildByName('DialogFrameArt') : frame?.getChildByName('DialogFrameVector')?.activeInHierarchy
      ? frame.getChildByName('DialogFrameVector') : frame
  const frameSprite = frameRender?.getComponent('cc.Sprite')
  const frameGraphics = frameRender?.getComponent('cc.Graphics')
  const fill = frameGraphics?.fillColor, stroke = frameGraphics?.strokeColor
  const vectorFrame = frameRender?._uiProps.uiComp === frameGraphics && frameGraphics?.enabled === true && fill?.r === 22 && fill?.g === 18 && fill?.b === 16
    && stroke?.r === 184 && stroke?.g === 134 && stroke?.b === 11 && frameGraphics.lineWidth === 2
  const frameLayerVisible = frameRender?.activeInHierarchy === true && frameRender.layer === host.layer
  const bitmapFrame = frameLayerVisible && frameRender?._uiProps.uiComp === frameSprite && frameSprite?.enabled === true && !!frameSprite.spriteFrame
  // 卡的材质也可能由「铺满卡片的子节点」承担（现在生产路径是 PanelSurface + panel-iron-v1 九宫格）。
  // 判据不放宽成「随便哪个子节点」，要求：卡片自己的子节点、铺满卡片（两维都 ≥90%、误差 12px 内）、
  // 在宿主层且 active，并且真的带位图帧或有 alpha 的填充 —— 空卡片仍然判红。
  const coversCard = box => box !== null && frameBox !== null
    && box.width >= frameBox.width * 0.9 - 12 && box.height >= frameBox.height * 0.9 - 12
    && box.width <= frameBox.width * 1.1 + 12 && box.height <= frameBox.height * 1.1 + 12
  const surface = (frame?.children ?? []).map(child => {
    const box = bounds(child)
    const sprite = child.getComponent('cc.Sprite')
    const graphics = child.getComponent('cc.Graphics')
    const visible = child.activeInHierarchy && child.layer === host.layer && coversCard(box)
    const bitmap = visible && sprite !== null && sprite.enabled === true && !!sprite.spriteFrame
    const vector = visible && graphics !== null && graphics.enabled === true && (graphics.fillColor?.a ?? 0) > 0
    return bitmap ? { name: child.name, style: 'card-surface', spriteFrame: sprite.spriteFrame.name }
      : vector ? { name: child.name, style: 'card-vector', spriteFrame: null } : null
  }).filter(Boolean)[0] ?? null
  const footer = (spec.footer ?? []).map(name => {
    const node = find(host, name)
    const box = bounds(node)
    const render = node?.getChildByName('DialogButtonArt') ?? node
    const sprite = render?.getComponent('cc.Sprite')
    return { name, box, active: node?.activeInHierarchy === true, layer: node?.layer, inArea: inside(box, area),
      listening: !!node?.hasEventListener('touch-start') || !!node?.hasEventListener('touch-end'),
      material: node?.activeInHierarchy === true && node.layer === host.layer && render?.activeInHierarchy === true
        && render.layer === host.layer && render._uiProps.uiComp === sprite && sprite?.enabled === true && !!sprite.spriteFrame }
  })
  const separation = footer.every(row => row.box && clip && row.box.y + row.box.height <= clip.y + 1)
  const seen = []
  if (scroll) {
    const content = scroll.content
    const transform = content.getComponent('cc.UITransform')
    // 正文的同胞自身框用于查标题/坐标与整条行相压；不把条行内部文字的合理叠层混进来。
    const bodyBoxes = content.children.filter(node => node.activeInHierarchy)
      .map(node => ({ node, box: bounds(node), label: node.getComponent('cc.Label') }))
      .filter(row => row.box && (!row.label || row.label.string.length > 0))
      .map(row => ({ name: row.node.name, text: row.label?.string ?? null, box: row.box,
        anchorY: row.node.getComponent('cc.UITransform').anchorY }))
    const bodyOverlaps = []
    for (let index = 0; index < bodyBoxes.length; index++) {
      for (const other of bodyBoxes.slice(index + 1)) {
        const row = bodyBoxes[index], a = row.box, b = other.box
        const width = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
        const height = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
        if (width > 1 && height > 1) bodyOverlaps.push({ a: row.name, aText: row.text, b: other.name, bText: other.text, width, height })
      }
    }
    const labels = []
    const collect = (node, key) => { labels.push({ node, key }); node.children.forEach((child, index) => collect(child, `${key}.${index}`)) }
    content.children.forEach((node, index) => collect(node, String(index)))
    for (const { node, key } of labels) {
      const label = node.getComponent('cc.Label')
      const box = bounds(node)
      if (!box || !node.activeInHierarchy || !label || !label.string) continue
      const visibleHeight = Math.max(0, Math.min(box.y + box.height, clip.y + clip.height) - Math.max(box.y, clip.y))
      seen.push({ key, text: label.string, box, visibleHeight,
        visibleFrom: Math.max(0, box.y + box.height - (clip.y + clip.height)),
        visibleTo: Math.min(box.height, box.y + box.height - clip.y), width: box.width, overflow: label.overflow })
    }
    // 诊断用：材质判定说不清「为什么没有」时，把框节点与它的 DialogFrame* 子节点原样带出来。
    const describe = node => node === null || node === undefined ? null : {
      name: node.name, active: node.activeInHierarchy, layer: node.layer,
      comps: (node.components ?? []).map(component => component.constructor?.name ?? 'unknown'),
      uiComp: node._uiProps?.uiComp?.constructor?.name ?? null,
      spriteFrame: node.getComponent('cc.Sprite')?.spriteFrame?.name ?? null,
      graphicsFill: node.getComponent('cc.Graphics')?.enabled === true ? 'enabled' : 'off',
    }
    const frameChildren = (frame?.children ?? []).filter(child => child.name.startsWith('DialogFrame')).map(describe)
    // 诊断用：框不在 NationCard 上时就沿宿主树找「谁带着渲染件」，只带名字/渲染件/尺寸，深度与条数都有上限。
    const rendererOf = node => {
      const sprite = node.getComponent('cc.Sprite')
      const graphics = node.getComponent('cc.Graphics')
      return { sprite: sprite?.spriteFrame?.name ?? (sprite === null ? null : '(no frame)'),
        graphics: graphics?.enabled === true ? { fill: graphics.fillColor ? [graphics.fillColor.r, graphics.fillColor.g, graphics.fillColor.b] : null } : null }
    }
    const tree = (node, depth) => {
      if (depth > 3) return null
      const box = bounds(node)
      return { name: node.name, active: node.activeInHierarchy, layer: node.layer,
        renderer: rendererOf(node), box: box && { w: Math.round(box.width), h: Math.round(box.height) },
        children: depth === 3 ? [] : node.children.map(child => tree(child, depth + 1)).filter(Boolean) }
    }
    const hostTree = tree(host, 0)
    return { area, clip, frameBox, frameInArea: inside(frameBox, area), clipInArea: inside(clip, area),
      frameNode: describe(frame), frameChildren, hostTree,
      masked: !!viewport.getComponent('cc.Mask'), footer, separation, seen, bodyBoxes, bodyOverlaps,
      material: bitmapFrame || (frameLayerVisible && vectorFrame) || surface !== null,
      frameStyle: bitmapFrame ? 'A-bitmap' : frameLayerVisible && vectorFrame ? 'token-vector' : surface !== null ? `${surface.style}:${surface.name}` : 'missing',
      frameSurface: surface,
      hostLayer: host.layer, frameLayer: frameRender?.layer, frameRenderer: frameRender?._uiProps.uiComp?.constructor?.name,
      viewportCount: host.children.filter(node => node.name === (spec.viewport ?? 'DialogContent')).length,
      frameName: bitmapFrame ? frameSprite.spriteFrame.name : surface?.spriteFrame ?? null,
      contentHeight: transform.height, maxOffset: Math.max(0, transform.height - clip.height),
      offset: scroll.getScrollOffset().y, visibleSize: cc.view.getVisibleSize() }
  }
  return { error: 'no-production-scroll-view', area, clip, frameBox }
}

/** 滚动只经引擎公开 API；用于逐段采样公示/署名，真实拖动另由调用方验证。 */
export function scrollDialogTo(spec) {
  const cc = window.cc
  const game = cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
  const find = (root, name) => {
    if (root.name === name) return root
    for (const child of root.children) { const hit = find(child, name); if (hit) return hit }
    return null
  }
  const host = find(game, spec.host)
  const viewport = host && find(host, spec.viewport ?? 'DialogContent')
  const scroll = viewport?.getComponent('cc.ScrollView')
  if (!scroll) throw new Error('缺生产 ScrollView')
  scroll.stopAutoScroll()
  scroll.scrollToOffset(new cc.Vec2(0, spec.offset), 0)
  return scroll.getScrollOffset().y
}

/** 把指定生产节点的中心放入真实Mask净区；按钮再交共用点击口径自命中。 */
export function revealDialogNode(spec) {
  const cc = window.cc
  const game = cc.director.getScene().getChildByName('Canvas').getChildByName('Game')
  const find = (root, name) => {
    if (root.name === name) return root
    for (const child of root.children) { const hit = find(child, name); if (hit) return hit }
    return null
  }
  const host = find(game, spec.host), viewport = find(host, spec.viewport ?? 'DialogContent')
  const target = find(viewport, spec.name), scroll = viewport.getComponent('cc.ScrollView')
  if (!target || !scroll) throw new Error('缺要定位的生产滚动节点')
  const box = target.getComponent('cc.UITransform')
  const center = box.convertToWorldSpaceAR(new cc.Vec3(box.width * (0.5 - box.anchorX), box.height * (0.5 - box.anchorY), 0))
  const local = viewport.getComponent('cc.UITransform').convertToNodeSpaceAR(center)
  const height = scroll.content.getComponent('cc.UITransform').height
  const max = Math.max(0, height - viewport.getComponent('cc.UITransform').height)
  scroll.stopAutoScroll()
  scroll.scrollToOffset(new cc.Vec2(0, Math.min(max, Math.max(0, scroll.getScrollOffset().y - local.y))), 0)
  return scroll.getScrollOffset().y
}
