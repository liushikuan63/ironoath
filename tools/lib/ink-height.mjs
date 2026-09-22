/**
 * 职责：在**已解码的截图位图**上量一颗 Label 的"字形墨迹带实际高度"，把 `Overflow.SHRINK` 的
 * 落地字号从推测变成读数（横扫只能报"盒 > 下限 且 文本宽度用不满盒"，量不到字到底画成几号）。
 * 依赖：无第三方库；位图由调用方用 `./png-diff.mjs` 的 `decodePng` 解好后传进来。
 *
 * <p><b>为什么必须量像素</b>：`label.fontSize` 是**设定值**，引擎做 SHRINK 时按比例改写的是渲染数据
 * （台账 #366：20 号字给盒高 36 落地 24）。读引擎内部的"实际字号"字段不可信 —— `actualFontSize`
 * 在 release 产物里不是落地值（本轮之前已踩过），所以只有截图能作证。
 *
 * <p><b>两条路径</b>：`measureInkBand` 只看一张图，靠"区域内逐通道中位数"自适应分背景（面板深棕、
 * 地图深色都能用）；`measureInkDiff` 看改前/改后两张图，只把"变了的那部分"当墨迹 —— 量临时改出来的
 * 合成档时区域里叠着别人的字，只有差分法能摘干净（同一套分带逻辑，见 `collectBands`）。
 *
 * <p><b>背景占比</b>：墨迹只该占区域少数像元，中位数才落在底板上。所以两条路都报 `share`
 * （墨迹像元占比）：占比接近一半就说明"分不清谁是背景"，调用方必须把这一档标成不可信，不许静默。
 *
 * <p><b>坐标系</b>：Cocos 的世界原点在屏幕**左下**、y 向上，位图原点在**左上**、y 向下，
 * 且画布按视口缩放（本轮实测量 960×600 设计面对 1440×900 截图）。缩放比一律从"截图实际宽高 ÷
 * `cc.view.getVisibleSize()`"现算，横向纵向分开取，绝不写死 1.5。
 */

/** 设计坐标 → 位图像素的比例，按截图真实宽高分开算（画布有黑边时两者不等）。 */
export function pixelScaleOf(img, vis) {
  if (!(vis && vis.width > 0 && vis.height > 0)) {
    throw new Error(`vis 不合法：${JSON.stringify(vis)}`)
  }
  return { sx: img.width / vis.width, sy: img.height / vis.height }
}

/**
 * 世界盒（设计坐标，原点左下）→ 位图像素矩形（原点左上）。
 * `insetDesign` 把矩形往里缩若干设计像素，用来躲开贴着盒边的描边 / 九宫格外框 ——
 * 那些横线每个像素列都"不是背景"，会被当成墨迹上下沿，把高度量成整个盒子。
 */
export function worldBoxToPixelRect(world, vis, img, insetDesign = 0) {
  const { sx, sy } = pixelScaleOf(img, vis)
  const x0 = world.x + insetDesign
  const x1 = world.x + world.width - insetDesign
  const yTop = world.y + world.height - insetDesign
  const yBottom = world.y + insetDesign
  return {
    x: Math.round(x0 * sx),
    y: Math.round((vis.height - yTop) * sy),
    w: Math.round((x1 - x0) * sx),
    h: Math.round((yTop - yBottom) * sy),
  }
}

/** 墨迹高（像素）折回设计单位，才能和 `label.fontSize` 直接比。 */
export function designHeightOf(inkPx, sy) {
  if (!(sy > 0)) throw new Error(`sy 不合法：${sy}`)
  return inkPx / sy
}

function medianOfHistogram(hist) {
  let total = 0
  for (let v = 0; v < 256; v += 1) total += hist[v]
  if (total === 0) return 0
  const half = total / 2
  let acc = 0
  for (let v = 0; v < 256; v += 1) {
    acc += hist[v]
    if (acc >= half) return v
  }
  return 255
}

/** 由"每行有多少像元算墨迹"这份行剖面切出连续带，并挑出最高的那条（两条测量路径共用，避免各写一份分带逻辑）。 */
function collectBands(rowCount, rowFirst, rowLast, h, minInkCols) {
  const bands = []
  let runStart = -1
  for (let ry = 0; ry <= h; ry += 1) {
    const on = ry < h && rowCount[ry] >= minInkCols
    if (on && runStart < 0) runStart = ry
    if (!on && runStart >= 0) {
      let maxCols = 0
      let pixels = 0
      let colsX0 = Infinity
      let colsX1 = -Infinity
      for (let k = runStart; k < ry; k += 1) {
        maxCols = Math.max(maxCols, rowCount[k])
        pixels += rowCount[k]
        colsX0 = Math.min(colsX0, rowFirst[k])
        colsX1 = Math.max(colsX1, rowLast[k])
      }
      bands.push({ top: runStart, bottom: ry - 1, height: ry - runStart, maxCols, pixels,
        cols: { x0: colsX0, x1: colsX1 } })
      runStart = -1
    }
  }
  // 取最高的带；同高时取墨迹像元多的那条（更宽的那条更可能才是目标字）
  let band = null
  for (const b of bands) {
    if (band === null || b.height > band.height || (b.height === band.height && b.pixels > band.pixels)) band = b
  }
  return { bands, band }
}

function emptyResult(reason, used) {
  return { ok: false, reason, used, bands: [], band: null, inkH: 0, cols: null, peakCols: 0, share: 0 }
}

/** 把 `measureInkBand` / `measureInkDiff` 的公共出口拼起来。 */
function finish(rect, x0, y0, w, h, rowCount, rowFirst, rowLast, minInkCols, extra) {
  const { bands, band } = collectBands(rowCount, rowFirst, rowLast, h, minInkCols)
  let pixels = 0
  for (let i = 0; i < h; i += 1) pixels += rowCount[i]
  return {
    ok: true,
    used: { x: x0, y: y0, w, h },
    bands,
    band,
    inkH: band === null ? 0 : band.height,
    cols: band === null ? null : band.cols,
    peakCols: band === null ? 0 : band.maxCols,
    share: pixels / (w * h),
    rect,
    ...extra,
  }
}

/**
 * 量一块像素矩形里的字形墨迹带（**单图 + 自适应背景**）。
 *
 * 步骤：① 逐通道直方图取中位数当背景；② 每行统计"与背景三通道平均差 > threshold"的像元数，
 * 达到 `minInkCols` 才算墨迹行；③ 连续墨迹行合成带，**取最高的那条**当字形带
 * （旁边压进来的字通常只贡献一两行，取最高而不是取全区域上下沿，免得把邻居算进高度）。
 *
 * 只报数不判红 —— 判据（哪一倍算缺陷）由调用方标定。
 */
export function measureInkBand(img, rect, { threshold = 20, minInkCols = 2 } = {}) {
  const x0 = Math.max(0, rect.x)
  const y0 = Math.max(0, rect.y)
  const x1 = Math.min(img.width, rect.x + rect.w)
  const y1 = Math.min(img.height, rect.y + rect.h)
  const w = x1 - x0
  const h = y1 - y0
  if (w <= 0 || h <= 0) {
    return emptyResult(`矩形与图不相交（${JSON.stringify(rect)} vs ${img.width}x${img.height}）`, { x: x0, y: y0, w, h })
  }
  const ch = img.channels
  const hist = [new Int32Array(256), new Int32Array(256), new Int32Array(256)]
  for (let y = y0; y < y1; y += 1) {
    for (let x = x0; x < x1; x += 1) {
      const i = (y * img.width + x) * ch
      hist[0][img.data[i]] += 1
      hist[1][ch === 1 ? img.data[i] : img.data[i + 1]] += 1
      hist[2][ch === 1 ? img.data[i] : img.data[i + 2]] += 1
    }
  }
  const bg = [medianOfHistogram(hist[0]), medianOfHistogram(hist[1]), medianOfHistogram(hist[2])]

  const rowCount = new Int32Array(h)
  const rowFirst = new Int32Array(h).fill(-1)
  const rowLast = new Int32Array(h).fill(-1)
  for (let y = y0; y < y1; y += 1) {
    const ry = y - y0
    for (let x = x0; x < x1; x += 1) {
      const i = (y * img.width + x) * ch
      const dr = Math.abs(img.data[i] - bg[0])
      const dg = Math.abs(img.data[ch === 1 ? i : i + 1] - bg[1])
      const db = Math.abs(img.data[ch === 1 ? i : i + 2] - bg[2])
      if ((dr + dg + db) / 3 > threshold) {
        rowCount[ry] += 1
        if (rowFirst[ry] < 0) rowFirst[ry] = x
        rowLast[ry] = x
      }
    }
  }
  return finish(rect, x0, y0, w, h, rowCount, rowFirst, rowLast, minInkCols, { bg, threshold })
}

/**
 * 量"两张图之间变了的那部分墨迹"（**改前/改后差分**）：把一张合成标签自己的像素从周围那些字里摘出来。
 *
 * <p>**为什么还要这一条路**：单图法靠中位数分背景，区域里叠着别人的字时"最高的那条带"可能不是
 * 目标那颗（实测把探针挪到屏幕中央量极端放大档，区域里跳出 11 条带、对位偏 50px）。
 * 差分法不看颜色看变化：改前一张、改后一张，只有被旋钮动过的那颗字会变 —— 背景是花的不影响。
 */
export function measureInkDiff(before, after, rect, { threshold = 20, minInkCols = 2 } = {}) {
  if (before.width !== after.width || before.height !== after.height) {
    throw new Error(`两张图尺寸不同：${before.width}x${before.height} vs ${after.width}x${after.height}`)
  }
  const x0 = Math.max(0, rect.x)
  const y0 = Math.max(0, rect.y)
  const x1 = Math.min(after.width, rect.x + rect.w)
  const y1 = Math.min(after.height, rect.y + rect.h)
  const w = x1 - x0
  const h = y1 - y0
  if (w <= 0 || h <= 0) {
    return emptyResult(`矩形与图不相交（${JSON.stringify(rect)} vs ${after.width}x${after.height}）`, { x: x0, y: y0, w, h })
  }
  const ch = before.channels
  const rowCount = new Int32Array(h)
  const rowFirst = new Int32Array(h).fill(-1)
  const rowLast = new Int32Array(h).fill(-1)
  for (let y = y0; y < y1; y += 1) {
    const ry = y - y0
    for (let x = x0; x < x1; x += 1) {
      const i = (y * after.width + x) * ch
      const dr = Math.abs(after.data[i] - before.data[i])
      const dg = Math.abs(after.data[ch === 1 ? i : i + 1] - before.data[ch === 1 ? i : i + 1])
      const db = Math.abs(after.data[ch === 1 ? i : i + 2] - before.data[ch === 1 ? i : i + 2])
      if ((dr + dg + db) / 3 > threshold) {
        rowCount[ry] += 1
        if (rowFirst[ry] < 0) rowFirst[ry] = x
        rowLast[ry] = x
      }
    }
  }
  return finish(rect, x0, y0, w, h, rowCount, rowFirst, rowLast, minInkCols, { threshold })
}
