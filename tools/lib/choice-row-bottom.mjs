const finiteRect = rect => rect !== null && typeof rect === 'object'
  && ['x', 'y', 'width', 'height'].every(key => Number.isFinite(rect[key]))
  && rect.width > 0 && rect.height > 0

/** Check actual world-space UITransform bounds. Positive Y clearance means separated. */
export function assessChoiceRowBottom({ row, viewport, footer }) {
  if (![row, viewport, footer].every(finiteRect)) {
    return { pass: false, rowViewportClearance: null, rowBottomClearance: null,
      rowFooterClearance: null, viewportFooterClearance: null }
  }
  const left = row.x - viewport.x
  const right = viewport.x + viewport.width - row.x - row.width
  const rowBottomClearance = row.y - viewport.y
  const top = viewport.y + viewport.height - row.y - row.height
  const rowViewportClearance = Math.min(left, right, rowBottomClearance, top)
  const rowFooterClearance = row.y - footer.y - footer.height
  const viewportFooterClearance = viewport.y - footer.y - footer.height
  const epsilon = 0.5
  return {
    pass: rowViewportClearance >= -epsilon
      && rowFooterClearance >= -epsilon
      && viewportFooterClearance >= -epsilon,
    rowViewportClearance,
    rowBottomClearance,
    rowFooterClearance,
    viewportFooterClearance,
  }
}
/** Assess whether the actual active row stack and footer fit inside the panel's inset content box. */
export function assessDialogVerticalCapacity({ netContentHeight, rows, footer }) {
  const validBounds = bounds => bounds !== null && typeof bounds === 'object'
    && Number.isFinite(bounds.bottom) && Number.isFinite(bounds.top) && bounds.top > bounds.bottom
  if (!Number.isFinite(netContentHeight) || netContentHeight <= 0
      || !Array.isArray(rows) || rows.length === 0 || !rows.every(validBounds) || !validBounds(footer)) {
    return { pass: false, visibleRowCount: Array.isArray(rows) ? rows.length : 0,
      rowPitch: null, footerHeight: null, requiredHeight: null,
      netContentHeight: Number.isFinite(netContentHeight) ? netContentHeight : null,
      slack: null, footerGap: null }
  }
  const centers = rows.map(row => (row.top + row.bottom) / 2).sort((left, right) => right - left)
  const pitches = centers.slice(0, -1).map((center, index) => center - centers[index + 1])
  const rowPitch = pitches.length > 0
    ? Math.max(...pitches) : rows[0].top - rows[0].bottom
  const footerHeight = footer.top - footer.bottom
  const requiredHeight = rows.length * rowPitch + footerHeight
  const slack = netContentHeight - requiredHeight
  const footerGap = Math.min(...rows.map(row => row.bottom)) - footer.top
  const epsilon = 0.5
  const orderedRows = [...rows].sort((left, right) => right.top - left.top)
  // Capacity alone cannot detect a row taller than the pitch: reject actual overlapping bounds.
  const rowsSeparated = orderedRows.slice(1).every((row, index) => orderedRows[index].bottom >= row.top - epsilon)
  return {
    pass: rowsSeparated && rowPitch > 0 && slack >= -epsilon && footerGap >= -epsilon,
    visibleRowCount: rows.length,
    rowPitch,
    footerHeight,
    requiredHeight,
    netContentHeight,
    slack,
    footerGap,
  }
}

/** #817: measure the actual title/decor, row text and long summary bounds, never text length guesses. */
export function assessComposeLabelGeometry({ plate, insets, labels }) {
  const box = r => r && ['left', 'right', 'bottom', 'top'].every(k => Number.isFinite(r[k]))
    && r.right > r.left && r.top > r.bottom
  const epsilon = 0.5
  const separate = (a, b) => box(a) && box(b) && (a.right <= b.left + epsilon
    || b.right <= a.left + epsilon || a.top <= b.bottom + epsilon || b.top <= a.bottom + epsilon)
  const title = labels?.title?.rect, coord = labels?.coord?.rect
  const crest = labels?.crest, total = labels?.total?.rect
  const headerClear = labels?.title?.visible === true && labels?.coord?.visible === true && separate(title, coord) && (crest?.active === false
    || (crest?.active === true && separate(title, crest.rect) && separate(coord, crest.rect)))
  const rowsClear = Array.isArray(labels?.rows) && labels.rows.length > 0
    && labels.rows.every(row => row.labels?.length >= 2 && row.labels[0].visible === true
      && row.labels[1].visible === true && separate(row.labels[0].rect, row.labels[1].rect))
  const summaryContained = labels?.total?.visible === true && box(plate) && box(total) && insets
    && ['left', 'right', 'top', 'bottom'].every(k => Number.isFinite(insets[k]) && insets[k] >= 0)
    && total.left >= plate.left + insets.left - epsilon && total.right <= plate.right - insets.right + epsilon
    && total.bottom >= plate.bottom + insets.bottom - epsilon && total.top <= plate.top - insets.top + epsilon
    && Array.isArray(labels.total.clips) && labels.total.clips.length > 0
    && labels.total.clips.every(clip => clip.enabled === true && box(clip)
      && total.left >= clip.left - epsilon && total.right <= clip.right + epsilon
      && total.bottom >= clip.bottom - epsilon && total.top <= clip.top + epsilon)
  return { pass: Boolean(headerClear && rowsClear && summaryContained), headerClear: Boolean(headerClear),
    rowsClear: Boolean(rowsClear), summaryContained: Boolean(summaryContained) }
}
