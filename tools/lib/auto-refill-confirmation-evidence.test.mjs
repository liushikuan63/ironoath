import test from 'node:test';
import assert from 'node:assert/strict';
import { verifiedModalPoints } from './auto-refill-confirmation-evidence.mjs';
const viewport = { width: 900, height: 420 };
const good = { verified: true, x: 550, y: 310 };
test('小屏单键无法命中时null坐标不能因数值强转而假绿', () => {
  assert.equal(verifiedModalPoints(good, { verified: false, x: null, y: null }, viewport), false);
});
test('小屏两颗实际验证且画布内的点接受，横向越界或非有限数拒绝', () => {
  assert.equal(verifiedModalPoints(good, { verified: true, x: 340, y: 310 }, viewport), true);
  assert.equal(verifiedModalPoints({ ...good, x: 901 }, { ...good, x: 340 }, viewport), false);
  assert.equal(verifiedModalPoints({ ...good, x: Infinity }, { ...good, x: 340 }, viewport), false);
});
