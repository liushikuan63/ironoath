#!/usr/bin/env node
/** B24: new isolated UID, actual season settlement then real canvas purchase and balance/bag readback.
 * SEASON_SHOP_BACKEND/SEASON_SHOP_OPS_TOKEN/SEASON_SHOP_OWNER required; use an owned memory1x instance with
 * a declared deployment anchor in SETTLE. No direct coin grant, response mock or component click emit.
 */
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import { startPreviewServer } from './lib/preview-server.mjs';
import { hideGuideBoard } from './lib/guide-overlay.mjs';
import { redactEvidence } from './lib/runtime-evidence-redaction.mjs';
import { hasExactPurchaseQuantity } from './lib/purchase-evidence.mjs';
import { resolveCocosClickPoint } from './lib/cocos-click.mjs';

const backend = process.env.SEASON_SHOP_BACKEND, ops = process.env.SEASON_SHOP_OPS_TOKEN;
const ownerFile = process.env.SEASON_SHOP_OWNER;
if (!backend || !ops || !ownerFile) { console.error('缺独立后端、归属回执或运维令牌，拒绝回退'); process.exit(2); }
let owner;
try {
  owner = JSON.parse(fs.readFileSync(ownerFile, 'utf8'));
  const origin = new URL(backend);
  if (origin.origin !== backend || origin.hostname !== '127.0.0.1' || Number(origin.port) !== owner.port ||
      owner.storage !== 'memory' || owner.timeSpeed !== 1 || owner.newUidOnly !== true ||
      !process.env.CODEX_THREAD_ID || owner.threadId !== process.env.CODEX_THREAD_ID ||
      !Number.isSafeInteger(owner.pid) || owner.pid <= 0) throw Error('归属不匹配');
  process.kill(owner.pid, 0);
} catch { console.error('本任务自有进程/端口归属未验证，拒绝全服结算'); process.exit(2); }
const root = path.resolve(process.env.SEASON_SHOP_ROOT ?? 'client/build/web-mobile');
const out = path.resolve(process.env.SEASON_SHOP_OUT ?? `tmp/season-shop-${randomUUID()}`);
const port = Number(process.env.SEASON_SHOP_PORT ?? 9846);
fs.mkdirSync(out, { recursive: true });
const checks = [], requests = [], pageErrors = [], consoleErrors = [];
const expectedPurchaseCount = 1;
let browser, preview, page, uid, selected, beforeBalance, afterBalance, exit = 1, failure;
let browserClosed = false, previewClosed = false;
let browserPurchases = 0;
function check(label, ok) {
  checks.push({ label, passed: !!ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`);
  if (!ok) throw Error(label);
}
async function api(method, route, body, asOps = false) {
  const headers = { 'Content-Type': 'application/json', ...(uid && !asOps ? { 'X-Player-Id': uid } : {}),
    ...(asOps ? { 'X-Ops-Token': ops } : {}) };
  const response = await fetch(backend + route, { method, headers,
    ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(15000) });
  const reply = await response.json();
  requests.push(redactEvidence({ kind: 'HTTP', method, route, uid, body, status: response.status, reply }));
  check(`真实接口 ${method} ${route} 成功`, reply.code === 0);
  return reply.data;
}
function attachScene() {
  const nodes = () => {
    const result = [], stack = [window.cc.director.getScene()];
    while (stack.length) { const node = stack.pop(); if (!node.activeInHierarchy) continue;
      result.push(node); stack.push(...node.children); }
    return result;
  };
  window.__seasonShop = {
    visible: name => nodes().some(n => n.name === name),
    labels: () => nodes().map(n => n.getComponent('cc.Label')?.string).filter(Boolean),
    balance: () => nodes().find(n => n.name === 'Balance' && n.parent?.name === 'shop')?.getComponent('cc.Label')?.string,
    targetUuid: (name, itemName) => {
      const all = nodes();
      const target = itemName
        ? all.find(n => n.name === 'BuyButton' && n.parent?.name === 'ShopRow' &&
            n.parent.getChildByName('Name')?.getComponent('cc.Label')?.string === itemName)
        : all.find(n => n.name === name);
      if (!target) throw Error('缺可见控件：' + name);
      return target.uuid;
    },
  };
}
async function click(name, itemName) {
  if (!itemName) await page.waitForFunction(name => window.__seasonShop.visible(name), name);
  const uuid = await page.evaluate(({ name, itemName }) => window.__seasonShop.targetUuid(name, itemName), { name, itemName });
  const point = await page.evaluate(resolveCocosClickPoint, { uuid });
  requests.push({ kind: 'cocos-click', name, uuid, point });
  check(`画布点击${name}通过引擎往返和自命中核验`, point.verified);
  await page.mouse.click(point.x, point.y);
}
async function bagCount(itemId) {
  const bag = await api('GET', '/bag/list');
  return bag.items.find(item => item.itemId === itemId)?.count ?? 0;
}
try {
  check('现有新Web产物存在', fs.existsSync(path.join(root, 'index.html')));
  check('全服结算前本任务新memory1x进程归属已核验', !!owner);
  const status = await api('GET', '/season/status');
  check('显式锚点使本实例真实进入配置结算期', status.phase === 'SETTLE');
  const device = 'season-coin-browser-' + randomUUID();
  const init = await api('POST', '/player/init', { requestId: randomUUID(), deviceId: device, nickName: '赛季消费验收', clientTime: Date.now() });
  uid = init.playerId; check('创建独立新设备UID', typeof uid === 'string');
  const initialShelf = await api('GET', '/shop/list?currency=SEASON_COIN');
  check('新号赛季币余额为零且页开放', initialShelf.open && initialShelf.balance === 0);
  await api('POST', '/city/upgrade', { requestId: randomUUID(), configId: 'main_city' });
  const ranked = await api('GET', '/season/status');
  check('真实城市写入触发战力重算并在结算前上榜', ranked.myRank >= 1);
  const award = await api('POST', '/season/settle', { requestId: randomUUID() }, true);
  const earned = await api('GET', '/shop/list?currency=SEASON_COIN');
  check('真实结算只给本新号发奖并由账本读回', award.settledPlayers === 1 && award.distributedSeasonCoin > 0 && earned.balance === award.distributedSeasonCoin);
  for (let repeat = 0; repeat < 2; repeat++) {
    const again = await api('POST', '/season/settle', { requestId: randomUUID() }, true);
    const balance = await api('GET', '/shop/list?currency=SEASON_COIN');
    check(`不同请求第${repeat + 2}次真实结算不重复发币`, again.settledPlayers === 0 && again.distributedSeasonCoin === 0 && balance.balance === earned.balance);
  }
  selected = earned.rows.find(row => row.itemId && row.purchasable && row.price > 0 && row.remaining === expectedPurchaseCount);
  check('本轮前提为服务端给出限购剩一件的可兑换道具行', !!selected && selected.currency === 'SEASON_COIN' && Number.isSafeInteger(selected.price) && Number.isSafeInteger(earned.balance));
  beforeBalance = earned.balance;
  const beforeItem = await bagCount(selected.itemId);
  preview = await startPreviewServer({ root, backend, port });
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addInitScript(device => localStorage.setItem('ironoath.deviceId', device), device);
  page = await context.newPage(); page.setDefaultTimeout(30000);
  page.on('pageerror', e => pageErrors.push(redactEvidence(e.message)));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(redactEvidence(m.text())); });
  page.on('request', r => { if (r.url() === backend + '/shop/buy' && r.method() === 'POST') browserPurchases++; });
  const initialized = page.waitForResponse(r => r.url() === backend + '/player/init' && r.request().method() === 'POST', { timeout: 60000 });
  await page.goto(preview.origin, { waitUntil: 'domcontentloaded' });
  const browserInit = await (await initialized).json();
  check('浏览器真实登录同一获奖UID', browserInit.code === 0 && browserInit.data.playerId === uid);
  await page.waitForFunction(() => !!window.cc?.director?.getScene()?.getChildByName('Canvas'), null, { timeout: 60000 });
  await page.evaluate(attachScene);
  await page.waitForFunction(() => window.__seasonShop.visible('GuideNext'));
  check('新账号引导真实挂载后隐藏验收遮罩', await page.evaluate(hideGuideBoard));
  if (await page.evaluate(() => window.__seasonShop.visible('离线汇总知道了'))) await click('离线汇总知道了');
  if (!await page.evaluate(() => window.__seasonShop.visible('Nav-shop'))) await click('Nav-more');
  await click('Nav-shop');
  await page.waitForFunction(() => window.__seasonShop.visible('Tab_SEASON_COIN'));
  const shelfResponse = page.waitForResponse(r => r.url().includes('/shop/list?currency=SEASON_COIN'));
  await click('Tab_SEASON_COIN');
  const actualShelf = await (await shelfResponse).json();
  check('真实画布切页请求返回获奖余额', actualShelf.code === 0 && actualShelf.data.balance === beforeBalance);
  await page.waitForFunction(balance => window.__seasonShop.balance()?.includes(String(balance)), beforeBalance);
  const labels = await page.evaluate(() => window.__seasonShop.labels());
  check('可见余额与商品均用人话且金额真实', labels.some(s => s.includes('赛季币') && s.includes(String(beforeBalance))) && labels.includes(selected.name) && !labels.some(s => s.includes(selected.rowId) || s.includes(selected.itemId)));
  await page.screenshot({ path: path.join(out, 'earned.png') });
  const purchaseResponse = page.waitForResponse(r => r.url() === backend + '/shop/buy' && r.request().method() === 'POST');
  await click('BuyButton', selected.name);
  const response = await purchaseResponse, request = response.request(), body = request.postDataJSON(), bought = await response.json();
  requests.push(redactEvidence({ kind: 'browser-purchase', route: '/shop/buy', uid, body, reply: bought }));
  check('浏览器兑换真实请求含本行本币本UID与幂等键', body.rowId === selected.rowId && body.currency === 'SEASON_COIN' && body.count === expectedPurchaseCount && typeof body.requestId === 'string' && request.headers()['x-player-id'] === uid);
  check('真实兑换成功且花费由回执给出', bought.code === 0 && bought.data.itemId === selected.itemId && bought.data.currency === 'SEASON_COIN' && bought.data.spent === selected.price);
  const afterShelf = await api('GET', '/shop/list?currency=SEASON_COIN'); afterBalance = afterShelf.balance;
  const afterItem = await bagCount(selected.itemId);
  check('权威余额按本单实际花费减少', afterBalance === beforeBalance - bought.data.spent && bought.data.balance === afterBalance);
  check('成交回执与真实背包都独立对应请求的一件道具', hasExactPurchaseQuantity({ expectedCount: expectedPurchaseCount, receiptCount: bought.data.count, beforeItem, afterItem }));
  const afterRow = afterShelf.rows.find(row => row.rowId === selected.rowId);
  check('本季限购状态由服务端消费本单', afterRow.used === selected.used + 1 && afterRow.remaining === selected.remaining - 1);
  await page.waitForFunction(balance => window.__seasonShop.balance()?.includes(String(balance)), afterBalance);
  check('页面真实重拉并显示兑换后的余额', (await page.evaluate(() => window.__seasonShop.balance())).includes(String(afterBalance)));
  await page.screenshot({ path: path.join(out, 'spent.png') });
  check('本次画布操作只发一条实际兑换请求', browserPurchases === 1);
  if (afterRow.remaining === 0) {
    check('限购耗尽时服务端给出不可兑换及人话理由', !afterRow.purchasable && typeof afterRow.lockReason === 'string' && afterRow.lockReason.length > 0);
    await click('BuyButton', selected.name);
    await page.waitForTimeout(350);
    check('耗尽后再次真实点击不发第二单', browserPurchases === 1);
    const unchanged = await api('GET', '/shop/list?currency=SEASON_COIN');
    const unchangedItem = await bagCount(selected.itemId);
    check('第二次点击没有重复扣币或发道具', unchanged.balance === afterBalance && unchangedItem === afterItem);
    check('页面保留服务端限购原因', (await page.evaluate(() => window.__seasonShop.labels())).some(s => s.includes(afterRow.lockReason)));
  }
  preview.assertRewritten(); check('页面及控制台错误为零', pageErrors.length === 0 && consoleErrors.length === 0);
  exit = 0;
} catch (e) {
  failure = redactEvidence({ name: e.name, message: e.message, stack: e.stack }); console.error(failure);
  if (page) await page.screenshot({ path: path.join(out, 'failure.png') }).catch(() => {});
} finally {
  if (browser) { await browser.close(); browserClosed = true; }
  if (preview) { await preview.close(); previewClosed = true; }
  const result = { exit, passed: checks.filter(c => c.passed).length, failed: checks.filter(c => !c.passed).length,
    checks, failure, pageErrors, consoleErrors, uid, selected, beforeBalance, afterBalance, requests,
    fixtureBoundary: 'Controlled deployment anchor in owned memory1x/newUID; actual production settlement; no45-day wall wait, direct coin grants or real payment',
    cleanup: { browserStarted: !!browser, previewStarted: !!preview, browserClosed, previewClosed } };
  fs.writeFileSync(path.join(out, 'result.actual.json'), JSON.stringify(redactEvidence(result), null, 2));
  console.log(JSON.stringify({ exit, passed: result.passed, failed: result.failed, failure, browserClosed, previewClosed }));
}
process.exit(exit);
