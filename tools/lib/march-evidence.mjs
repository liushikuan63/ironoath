import { isDeepStrictEqual } from 'node:util';

/** Keep zero-count rows and reject ambiguous duplicate identities. */
export function armyCounts(army) {
  const entries = army.units.map(({ unitId, count }) => {
    if (typeof unitId !== 'string' || !unitId || !Number.isSafeInteger(count) || count < 0) throw Error('Invalid army row');
    return [unitId, count];
  }).sort(([a], [b]) => a.localeCompare(b));
  if (new Set(entries.map(([id]) => id)).size !== entries.length) throw Error('Duplicate army unit');
  return Object.fromEntries(entries);
}

export function subtractUnits(baseline, dispatched) {
  const expected = { ...baseline };
  for (const { unitId, count } of dispatched) {
    if (!Number.isSafeInteger(count) || count <= 0 || !Object.hasOwn(expected, unitId) || expected[unitId] < count) throw Error('Invalid dispatched unit');
    expected[unitId] -= count;
  }
  return expected;
}

export function hasExactMarchUnits(body, reply, expectedUnits) {
  return reply?.code === 0 && isDeepStrictEqual(body?.units, expectedUnits)
    && isDeepStrictEqual(reply.data?.march?.units, expectedUnits)
    && reply.data.march.action === body.action
    && reply.data.march.to?.x === body.toX && reply.data.march.to?.y === body.toY
    && isDeepStrictEqual(reply.data.march.heroes, body.heroes);
}

export function isSameRepeat(first, second, firstReply, secondReply, expectedUnits) {
  const { requestId: firstId, ...firstBusiness } = first;
  const { requestId: secondId, ...secondBusiness } = second;
  return typeof firstId === 'string' && firstId.length > 0
    && typeof secondId === 'string' && secondId.length > 0 && firstId !== secondId
    && isDeepStrictEqual(firstBusiness, secondBusiness)
    && hasExactMarchUnits(first, firstReply, expectedUnits)
    && hasExactMarchUnits(second, secondReply, expectedUnits)
    && typeof firstReply.data.march.marchId === 'string' && firstReply.data.march.marchId.length > 0
    && typeof secondReply.data.march.marchId === 'string' && secondReply.data.march.marchId.length > 0
    && firstReply.data.march.marchId !== secondReply.data.march.marchId;
}
