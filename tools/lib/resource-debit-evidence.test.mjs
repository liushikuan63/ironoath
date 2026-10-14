import test from 'node:test';
import assert from 'node:assert/strict';
import { accountedResourceDebit } from './resource-debit-evidence.mjs';
const snapshot = (current, lastSettle, serverNow, extra = {}) => ({ serverNow,
  resources: { IRON: { current, cap: 10000, perHour: 100, lastSettle, ...extra } } });
test('separates credited production from actual expense', () => {
  assert.deepEqual(accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9218, 72000, 80000), 'IRON'),
    { produced: 2, debited: 30, quantumMs: 36000 });
});
test('wrong fee is not hidden by identical-looking wallet reductions', () => {
  assert.equal(accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9238, 72000, 80000), 'IRON').debited, 10);
  assert.notEqual(accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9238, 72000, 80000), 'IRON').debited, 30);
});
test('free training with a forged future settlement stamp cannot self-certify an expense', () => {
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9248, 108000, 72000), 'IRON'), /timestamp/);
});
test('overcharging with a lagging settlement stamp also fails closed', () => {
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9246, 36000, 72000), 'IRON'), /timestamp/);
});
test('capacity caps and changed rates are outside this isolated ledger oracle', () => {
  assert.throws(() => accountedResourceDebit(snapshot(9999, 0, 10000), snapshot(9990, 72000, 80000), 'IRON'), /capacity/);
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9218, 72000, 80000, { perHour: 200 }), 'IRON'), /Changing/);
});
test('unsafe numbers, backwards time and non-integral or stale production are rejected', () => {
  assert.throws(() => accountedResourceDebit(snapshot(Number.MAX_SAFE_INTEGER + 1, 0, 10000), snapshot(9218, 72000, 80000), 'IRON'), /Unsafe/);
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 10000), snapshot(9218, 0, 9000), 'IRON'), /monotonic/);
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 10000, { perHour: 7 }), snapshot(9218, 0, 80000, { perHour: 7 }), 'IRON'), /quantum/);
  assert.throws(() => accountedResourceDebit(snapshot(9246, 0, 40000), snapshot(9218, 72000, 80000), 'IRON'), /Unsettled/);
});
