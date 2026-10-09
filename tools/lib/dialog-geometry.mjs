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
  const frameSprite = frame?.getComponent('cc.Sprite')
  const frameGraphics = frame?.getComponent('cc.Graphics')
  const fill = frameGraphics?.fillColor, stroke = frameGraphics?.strokeColor
  const vectorFrame = frameGraphics?.enabled === true && fill?.r === 22 && fill?.g === 18 && fill?.b === 16
    && stroke?.r === 184 && stroke?.g === 134 && stroke?.b === 11 && frameGraphics.lineWidth === 2
  const frameLayerVisible = frame?.activeInHierarchy === true && frame.layer === host.layer
  const bitmapFrame = frameLayerVisible && frameSprite?.enabled === true && !!frameSprite.spriteFrame
  const footer = (spec.footer ?? []).map(name => {
    const node = find(host, name)
    const box = bounds(node)
    const sprite = node?.getComponent('cc.Sprite')
    return { name, box, active: node?.activeInHierarchy === true, layer: node?.layer, inArea: inside(box, area),
      listening: !!node?.hasEventListener('touch-start') || !!node?.hasEventListener('touch-end'),
      material: node?.activeInHierarchy === true && node.layer === host.layer && sprite?.enabled === true && !!sprite.spriteFrame }
  })
  const separation = footer.every(row => row.box && clip && row.box.y + row.box.height <= clip.y + 1)
  const seen = []
  if (scroll) {
    const content = scroll.content
    const transform = content.getComponent('cc.UITransform')
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
    return { area, clip, frameBox, frameInArea: inside(frameBox, area), clipInArea: inside(clip, area),
      masked: !!viewport.getComponent('cc.Mask'), footer, separation, seen,
      material: bitmapFrame || (frameLayerVisible && vectorFrame), frameStyle: bitmapFrame ? 'A-bitmap' : frameLayerVisible && vectorFrame ? 'token-vector' : 'missing',
      hostLayer: host.layer, frameLayer: frame?.layer, viewportCount: host.children.filter(node => node.name === (spec.viewport ?? 'DialogContent')).length,
      frameName: bitmapFrame ? frameSprite.spriteFrame.name : null,
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
