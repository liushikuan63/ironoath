#!/usr/bin/env node
/** B22 限流真实浏览器验收。只使用独立 dev 后端和新设备，不触已有账号。
 * BACKEND_ORIGIN 必填；CHAT_PORT/CHAT_ROOT/CHAT_RATE_OUT 可覆盖。
 * 真实 HTTP 占满同内容窗口，真实键盘和画布点击发送；无响应伪造或组件事件注入。
 * Guide 隐藏仅为隔离验收遮罩；Web 读数不替代微信原生端。
 */
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import { startPreviewServer } from './lib/preview-server.mjs';
import { hideGuideBoard } from './lib/guide-overlay.mjs';
import { redactEvidence } from './lib/runtime-evidence-redaction.mjs';

const backend = process.env.BACKEND_ORIGIN;
if (!backend) { console.error('缺 BACKEND_ORIGIN，拒绝回退到其他后端'); process.exit(2); }
const root = path.resolve(process.env.CHAT_ROOT ?? 'client/build/web-mobile');
const out = path.resolve(process.env.CHAT_RATE_OUT ?? `tmp/chat-rate-limit-${randomUUID()}`);
const port = Number(process.env.CHAT_PORT ?? 8096);
fs.mkdirSync(out, { recursive: true });
const checks = [], requests = [], pageErrors = [], consoleErrors = [];
let browser, preview, page, exit = 1, failure, browserClosed = false, previewClosed = false;
function check(label, ok) {
  checks.push({ label, passed: !!ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`);
  if (!ok) throw Error(label);
}
async function api(route, uid, body) {
  const response = await fetch(backend + route, { method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(uid ? { 'X-Player-Id': uid } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  const reply = await response.json();
  requests.push(redactEvidence({ kind: 'HTTP', route, uid, body, status: response.status, reply }));
  return reply;
}
function sceneHelpers() {
  const nodes = () => {
    const result = [], stack = [window.cc.director.getScene()];
    while (stack.length) { const node = stack.pop(); if (!node.activeInHierarchy) continue;
      result.push(node); stack.push(...node.children); }
    return result;
  };
  window.__chatRate = {
    visible: name => nodes().some(n => n.name === name),
    labels: () => nodes().map(n => n.getComponent('cc.Label')?.string).filter(Boolean),
    input: () => nodes().find(n => n.name === 'ChatInput')?.getComponent('cc.EditBox')?.string,
    point: name => {
      const all = nodes(), target = all.find(n => n.name === name);
      const camera = all.map(n => n.getComponent('cc.Camera')).find(Boolean);
      if (!target || !camera) throw Error('缺可见控件或摄像机：' + name);
      const p = camera.worldToScreen(target.worldPosition);
      const canvas = document.getElementById('GameCanvas'), rect = canvas.getBoundingClientRect();
      return { x: rect.left + p.x / canvas.width * rect.width,
        y: rect.top + (1 - p.y / canvas.height) * rect.height };
    },
  };
}
async function click(name) {
  await page.waitForFunction(name => window.__chatRate.visible(name), name);
  const p = await page.evaluate(name => window.__chatRate.point(name), name);
  await page.mouse.click(p.x, p.y);
}
async function type(text) {
  await click('ChatInput');
  await page.waitForFunction(() => ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName));
  await page.keyboard.press('Control+A'); await page.keyboard.type(text);
  await page.waitForFunction(text => window.__chatRate.input() === text, text);
}
async function send(uid, text) {
  const waiting = page.waitForResponse(r => r.url() === backend + '/chat/send' && r.request().method() === 'POST');
  await click('ChatSend'); const response = await waiting; const reply = await response.json();
  const body = response.request().postDataJSON();
  requests.push(redactEvidence({ kind: 'browser', route: '/chat/send', uid, body, status: response.status(), reply }));
  check('浏览器请求确实发送当前输入且属于隔离账号', body.content === text && response.request().headers()['x-player-id'] === uid);
  return reply;
}
async function list(uid) {
  const reply = await api('/chat/list', uid, { channel: 'WORLD', toPlayerId: null, beforeMessageId: null, limit: 50 });
  check('后端聊天列表读取成功', reply.code === 0 && Array.isArray(reply.data?.messages));
  return reply.data.messages;
}
try {
  check('现有Web构建产物存在', fs.existsSync(path.join(root, 'index.html')));
  const device = 'rate-browser-' + randomUUID();
  const init = await api('/player/init', null, { requestId: randomUUID(), deviceId: device, nickName: '限流验收', clientTime: Date.now() });
  const uid = init.data?.playerId; check('真实后端创建隔离设备身份', init.code === 0 && typeof uid === 'string');
  preview = await startPreviewServer({ root, backend, port });
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addInitScript(device => localStorage.setItem('ironoath.deviceId', device), device);
  page = await context.newPage(); page.setDefaultTimeout(30000);
  page.on('pageerror', e => pageErrors.push(redactEvidence(e.message)));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(redactEvidence(m.text())); });
  const initialized = page.waitForResponse(r => r.url() === backend + '/player/init' && r.request().method() === 'POST', { timeout: 60000 });
  await page.goto(preview.origin, { waitUntil: 'domcontentloaded' });
  const browserInit = await (await initialized).json();
  check('浏览器登录同一隔离UID', browserInit.code === 0 && browserInit.data.playerId === uid);
  await page.waitForFunction(() => !!window.cc?.director?.getScene()?.getChildByName('Canvas'), null, { timeout: 60000 });
  await page.evaluate(sceneHelpers);
  await page.waitForFunction(() => window.__chatRate.visible('Nav-more') || window.__chatRate.visible('Nav-social'), null, { timeout: 60000 });
  // 本量具固定创建未走引导的新设备；等待真实异步引导板挂载后再隐藏，不能藏一次空树就继续点导航。
  await page.waitForFunction(() => window.__chatRate.visible('GuideNext'));
  check('新账号引导板实际挂载后隐藏验收遮罩', await page.evaluate(hideGuideBoard));
  await page.waitForFunction(() => !window.__chatRate.visible('GuideNext'));
  if (await page.evaluate(() => window.__chatRate.visible('离线汇总知道了'))) {
    await click('离线汇总知道了');
    await page.waitForFunction(() => !window.__chatRate.visible('离线汇总知道了'));
  }
  if (!await page.evaluate(() => window.__chatRate.visible('Nav-social'))) await click('Nav-more');
  await click('Nav-social'); await click('Tab_chat'); await click('Channel_WORLD');
  await page.waitForFunction(() => window.__chatRate.labels().some(s => s.includes('世界频道')));
  const repeated = '限流同句验收' + Date.now(); await type(repeated);
  let denied, accepted = 0;
  for (let i = 0; i < 10; i++) {
    const reply = await api('/chat/send', uid, { requestId: randomUUID(), channel: 'WORLD', content: repeated, toPlayerId: null, share: null });
    if (reply.code === 10044) { denied = reply; break; }
    check('窗口预置发送走真实成功响应', reply.code === 0); accepted++;
  }
  check('真实同内容窗口被服务端10044拒绝且此前有成功发送', !!denied && accepted > 0);
  const before = await list(uid), rejected = await send(uid, repeated);
  check('真实浏览器发送命中相同限流错误码', rejected.code === 10044);
  await page.waitForFunction(() => window.__chatRate.labels().some(s => /同一句话|发得太快/.test(s) && /秒后|稍后/.test(s)));
  const visible = await page.evaluate(() => window.__chatRate.labels());
  const hint = visible.filter(s => /同一句话|发得太快/.test(s));
  check('可见中文限流提示无内部错误码或枚举', hint.length > 0 && hint.every(s => !/10044|SOCIAL_CHAT|RATE_LIMIT/.test(s)));
  check('拒绝后保留玩家输入', await page.evaluate(() => window.__chatRate.input()) === repeated);
  const after = await list(uid);
  check('拒绝不增加权威消息条数', before.filter(m => m.content === repeated).length === accepted && after.filter(m => m.content === repeated).length === accepted);
  fs.writeFileSync(path.join(out, 'limited-labels.actual.json'), JSON.stringify(redactEvidence({ uid, accepted, rejected, hint, visible }), null, 2));
  await page.screenshot({ path: path.join(out, 'limited.png') });
  const different = '不同内容恢复验收' + Date.now(); await type(different);
  const success = await send(uid, different);
  check('同一窗口不同内容实际浏览器发送成功', success.code === 0);
  await page.waitForFunction(text => window.__chatRate.input() === '' && window.__chatRate.labels().some(s => s.includes(text)), different);
  check('不同内容已真实落入后端列表', (await list(uid)).some(m => m.content === different));
  const stillLimited = await api('/chat/send', uid, { requestId: randomUUID(), channel: 'WORLD', content: repeated, toPlayerId: null, share: null });
  check('不同内容成功时原同句窗口仍被10044拒绝', stillLimited.code === 10044);
  const retrySeconds = Number((rejected.detail ?? '').match(/(\d+)\s*秒后/)?.[1]);
  check('服务端提供有限的重试窗口', Number.isFinite(retrySeconds) && retrySeconds > 0 && retrySeconds <= 60);
  await type(repeated);
  // 此等待验证服务端返回的业务时间窗口，不用于补偿页面加载或控件竞态。
  await new Promise(resolve => setTimeout(resolve, retrySeconds * 1000 + 200));
  const retried = await send(uid, repeated);
  check('真实限流窗口届满后同句浏览器重发成功', retried.code === 0);
  await page.waitForFunction(() => window.__chatRate.input() === '');
  check('届满重发只新增一条权威消息', (await list(uid)).filter(m => m.content === repeated).length === accepted + 1);
  await page.screenshot({ path: path.join(out, 'recovered.png') });
  preview.assertRewritten(); check('页面及控制台错误均为零', pageErrors.length === 0 && consoleErrors.length === 0);
  exit = 0;
} catch (error) {
  failure = redactEvidence({ name: error.name, message: error.message, stack: error.stack });
  console.error(failure); if (page) {
    await page.screenshot({ path: path.join(out, 'failure.png') }).catch(() => {});
    const visible = await page.evaluate(() => window.__chatRate?.labels()).catch(() => null);
    fs.writeFileSync(path.join(out, 'failure-labels.actual.json'), JSON.stringify(redactEvidence(visible)));
  }
} finally {
  if (browser) { await browser.close(); browserClosed = true; }
  if (preview) { await preview.close(); previewClosed = true; }
  fs.writeFileSync(path.join(out, 'requests.actual.json'), JSON.stringify(requests, null, 2));
  const result = { exit, passed: checks.filter(c => c.passed).length, failed: checks.filter(c => !c.passed).length,
    checks, failure, pageErrors, consoleErrors, backend, root, cleanup: { browserClosed, previewClosed },
    fixtureBoundary: '新设备身份；真实HTTP占满窗口；隐藏Guide验收遮罩。生产限流与浏览器键盘/画布点击，无响应mock；普通1×后端，不代表微信原生。' };
  fs.writeFileSync(path.join(out, 'result.actual.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ exit, passed: result.passed, failed: result.failed, failure, pageErrors, consoleErrors, browserClosed, previewClosed }));
}
process.exitCode = exit;
