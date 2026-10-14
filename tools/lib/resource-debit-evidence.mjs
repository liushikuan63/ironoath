/** Isolate a debit between two authoritative city snapshots with constant, integral production.
 * Reject capped or unsupported windows instead of interpreting their inventory difference as expense.
 */
export function accountedResourceDebit(before, after, type) {
  const a = before.resources?.[type], b = after.resources?.[type];
  for (const value of [before.serverNow, after.serverNow, a?.current, b?.current, a?.cap, b?.cap,
    a?.perHour, b?.perHour, a?.lastSettle, b?.lastSettle]) {
    if (!Number.isSafeInteger(value) || value < 0) throw Error('Unsafe resource snapshot');
  }
  if (a.perHour <= 0 || a.perHour !== b.perHour || a.cap !== b.cap || a.current >= a.cap || b.current >= b.cap)
    throw Error('Changing, capped or unsupported resource window');
  const hour = 3600000n, rate = BigInt(a.perHour);
  if (hour % rate !== 0n) throw Error('Non-integral production quantum');
  const quantum = hour / rate, start = BigInt(a.lastSettle), end = BigInt(after.serverNow);
  if (before.serverNow > after.serverNow || start > BigInt(before.serverNow)
    || BigInt(before.serverNow) - start >= quantum || end < start)
    throw Error('Unsettled or non-monotonic snapshot');
  // Server time independently determines the credits; the settlement timestamp must agree exactly.
  const produced = (end - start) / quantum;
  if (produced >= BigInt(a.cap - a.current)) throw Error('An intermediate capacity cap cannot be excluded');
  if (BigInt(b.lastSettle) !== start + produced * quantum)
    throw Error('Settlement timestamp disagrees with authoritative time');
  const debited = BigInt(a.current) + produced - BigInt(b.current);
  if (debited < 0n || debited > BigInt(Number.MAX_SAFE_INTEGER)) throw Error('Unaccounted resource change');
  return { produced: Number(produced), debited: Number(debited), quantumMs: Number(quantum) };
}
