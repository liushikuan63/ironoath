/**
 * 职责：给 Playwright context 挂「读接口夹具」——统一掉 `{code:0,data}` 那层壳与 CORS 预检。
 * 依赖：playwright 的 `context.route`。
 *
 * <p><b>为什么单独一份</b>：`tools/verify-label-fit-runtime.mjs`（排版横扫）与
 * `tools/verify-plate-plant.mjs`（植入正例）都要桩同一批读接口。这两条坑各踩过一次——
 * 桩晚挂（在 `page.goto` 之后）等于那一格读到空态、OPTIONS 没回 204 会让整个请求失败——
 * 抄第二份就是再踩一次。
 */
export function makeStubRead(context) {
  const cors = (request) => ({
    'access-control-allow-origin': request.headers()['origin'] ?? '*',
    'access-control-allow-headers': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
  })
  const reply = async (route, data) => route.fulfill({
    status: 200,
    headers: { ...cors(route.request()), 'content-type': 'application/json' },
    body: JSON.stringify({ code: 0, msg: '成功', data, serverNow: Date.now() }),
  })
  /**
   * 挂一份读接口夹具：OPTIONS 先回 204，其余用 `reply` 包成 `{code:0,data}`。
   * 必须在 `page.goto` **之前**挂上（深链进面板就发请求，晚挂等于这一格读到空态）。
   * `data` 给函数时按请求 URL 现算 —— 同一个端点带不同参数（`/social/permissions?scope=`）要能各回各的。
   */
  return (pattern, data) => context.route(pattern, async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors(route.request()) })
      return
    }
    await reply(route, typeof data === 'function' ? data(route.request().url()) : data)
  })
}
