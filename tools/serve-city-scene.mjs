/** Local static preview for the current web-mobile city build. */
import { startPreviewServer } from './lib/preview-server.mjs'

const port = Number(process.env.CITY_PREVIEW_PORT ?? 8196)
const backend = process.env.BACKEND_ORIGIN ?? 'http://localhost:8157'
const root = process.env.CITY_PREVIEW_ROOT ?? 'client/build/web-mobile'
const preview = await startPreviewServer({
  root,
  backend,
  port,
})
console.log(`[city-preview] ${preview.origin} -> ${root} -> ${backend}`)
