import test from 'node:test';
import assert from 'node:assert/strict';
import { hasExactPurchaseQuantity } from './purchase-evidence.mjs';

test('one requested item, one delivered item and actual one-item inventory increase agree', () => {
  assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 1, beforeItem: 3, afterItem: 4 }), true);
});
test('zero delivery and self-consistent zero response cannot validate a one-item request', () => {
  assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 0, beforeItem: 3, afterItem: 3 }), false);
});
test('extra delivery and self-consistent two-item response cannot validate a one-item request', () => {
  assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 2, beforeItem: 3, afterItem: 5 }), false);
});
test('correct response count cannot hide missing or extra inventory', () => {
  assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 1, beforeItem: 3, afterItem: 3 }), false);
  assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 1, beforeItem: 3, afterItem: 5 }), false);
});
test('unsafe, fractional and negative inventory numbers fail closed', () => {
  for (const afterItem of [-1, 4.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(hasExactPurchaseQuantity({ expectedCount: 1, receiptCount: 1, beforeItem: 3, afterItem }), false);
  }
});
