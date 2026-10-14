import test from 'node:test';
import assert from 'node:assert/strict';
import { armyCounts, subtractUnits, hasExactMarchUnits, isSameRepeat } from './march-evidence.mjs';
const units = [{ unitId: 'infantry', count: 10 }];
const first = { toX: 4, toY: 7, units, heroes: [], action: 'GATHER', requestId: 'first' };
const second = { ...first, requestId: 'second' };
const reply = id => ({ code: 0, data: { march: { marchId: id, units, heroes: [], action: 'GATHER', to: { x: 4, y: 7 } } } });
test('real repeat requires fresh request and march identities with identical full business fields', () => {
  assert.equal(isSameRepeat(first, second, reply('a'), reply('b'), units), true);
  assert.equal(isSameRepeat(first, { ...second, requestId: 'first' }, reply('a'), reply('b'), units), false);
  assert.equal(isSameRepeat(first, second, reply('a'), reply('a'), units), false);
});
test('different coordinates, action and heroes cannot be hidden by unchanged troop totals', () => {
  for (const changed of [{ toX: 8 }, { action: 'ATTACK' }, { heroes: ['other'] }]) {
    assert.equal(isSameRepeat(first, { ...second, ...changed }, reply('a'), reply('b'), units), false);
  }
});
test('same total with wrong unit identity is rejected in both request and reply', () => {
  const swapped = [{ unitId: 'cavalry', count: 10 }];
  assert.equal(hasExactMarchUnits({ ...first, units: swapped }, reply('a'), units), false);
  const wrongReply = reply('a'); wrongReply.data.march.units = swapped;
  assert.equal(hasExactMarchUnits(first, wrongReply, units), false);
});
test('authority counts retain zero rows and detect returned troops changing species', () => {
  const baseline = armyCounts({ units: [{ unitId: 'infantry', count: 20 }, { unitId: 'cavalry', count: 0 }] });
  assert.deepEqual(subtractUnits(baseline, units), { cavalry: 0, infantry: 10 });
  assert.notDeepEqual(armyCounts({ units: [{ unitId: 'infantry', count: 10 }, { unitId: 'cavalry', count: 10 }] }), baseline);
});
test('ambiguous, missing and negative count inputs fail closed', () => {
  assert.throws(() => armyCounts({ units: [{ unitId: 'x', count: 1 }, { unitId: 'x', count: 1 }] }));
  assert.throws(() => armyCounts({ units: [{ unitId: 'x', count: -1 }] }));
  assert.throws(() => subtractUnits({ infantry: 0 }, units));
  assert.throws(() => subtractUnits({ cavalry: 10 }, units));
});
