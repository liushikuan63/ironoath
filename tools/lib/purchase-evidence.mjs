/** Independent quantity oracle: request intent, response count and authoritative inventory must agree. */
export function hasExactPurchaseQuantity({ expectedCount, receiptCount, beforeItem, afterItem }) {
  return [expectedCount, receiptCount, beforeItem, afterItem].every(Number.isSafeInteger)
    && expectedCount > 0 && beforeItem >= 0 && afterItem >= 0
    && receiptCount === expectedCount && afterItem - beforeItem === expectedCount;
}
