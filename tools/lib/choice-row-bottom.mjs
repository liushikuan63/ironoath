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