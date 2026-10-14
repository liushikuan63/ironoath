/** Geometric evidence for the owned modal probe, independent of business state. */
export function verifiedModalPoints(confirm, cancel, viewport) {
  return [confirm, cancel].every(point => point?.verified === true
    && Number.isFinite(point.x) && Number.isFinite(point.y)
    && point.x >= 0 && point.x <= viewport.width
    && point.y >= 0 && point.y <= viewport.height)
    && confirm.x !== cancel.x;
}
