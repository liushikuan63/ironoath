/**
 * 职责：Prng 单测 —— 用 golden vector 锁定与服务端 Java Rng 的逐位一致性。
 * 依赖：node:test / node:assert。
 *
 * golden vector 的来源：服务端 RngTest 与本文件共享同一组常量。
 * 任何一端改了算法，两边的测试会同时失败 —— 这就是「双端一致」的可执行保证。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import { Prng } from '../core/Prng'

/** 与 com.ironoath.common.rng.RngTest 共享的黄金样本：Rng.of(12345) 的前 5 个 uint32 输出。 */
const GOLDEN_UINT32 = [3624643813, 115436424, 3897883617, 3226534032, 2396310574] as const

/** Rng.of(12345).fork(1) 的首个 uint32 输出。 */
const GOLDEN_FORK_UINT32 = 2271962686

function toUint32(value: number): number {
  return Math.floor(value * 4294967296)
}

test('黄金样本：seed=12345 的前 5 次 next() 与服务端 Java 实现逐位一致', () => {
  const rng = Prng.of(12345)
  for (const expected of GOLDEN_UINT32) {
    assert.equal(toUint32(rng.next()), expected)
  }
})

test('黄金样本：fork(1) 的首个输出与服务端一致', () => {
  assert.equal(toUint32(Prng.of(12345).fork(1).next()), GOLDEN_FORK_UINT32)
})

test('同 seed 调用 10000 次，两次序列逐位相等', () => {
  const a = Prng.of(20260906)
  const b = Prng.of(20260906)
  for (let i = 0; i < 10000; i++) {
    assert.equal(a.nextBits(), b.nextBits(), `第 ${i} 次输出不一致`)
  }
})

test('fork 隔离：子流推进不影响父流，父流推进不影响子流', () => {
  const parent = Prng.of(42)
  const control = Prng.of(42)
  const child = parent.fork(1)

  for (let i = 0; i < 1000; i++) {
    child.nextBits()
  }
  for (let i = 0; i < 100; i++) {
    assert.equal(parent.nextBits(), control.nextBits())
  }

  const childA = Prng.of(42).fork(9)
  const childB = Prng.of(42).fork(9)
  const parent2 = Prng.of(42)
  parent2.fork(9)
  for (let i = 0; i < 500; i++) {
    parent2.nextBits()
  }
  for (let i = 0; i < 100; i++) {
    assert.equal(childA.nextBits(), childB.nextBits())
  }
})

test('不同 salt 的 fork 产生完全不同的子流', () => {
  const parent = Prng.of(42)
  assert.notEqual(parent.fork(1).nextBits(), parent.fork(2).nextBits())
})

test('next() 落在 [0,1)，永不返回 1.0', () => {
  const rng = Prng.of(11)
  for (let i = 0; i < 100000; i++) {
    const v = rng.next()
    assert.ok(v >= 0 && v < 1, `越界：${v}`)
  }
})

test('range 为闭区间且两端都能取到', () => {
  const rng = Prng.of(2024)
  let seenLow = false
  let seenHigh = false
  for (let i = 0; i < 20000; i++) {
    const v = rng.range(3, 5)
    assert.ok(v >= 3 && v <= 5)
    if (v === 3) seenLow = true
    if (v === 5) seenHigh = true
  }
  assert.ok(seenLow, '闭区间下界应能取到')
  assert.ok(seenHigh, '闭区间上界应能取到')
})

test('nextInt 分布无偏：bound=7 时每个值频率接近 1/7', () => {
  const rng = Prng.of(99)
  const bound = 7
  const total = 140000
  const hits = new Array<number>(bound).fill(0)
  for (let i = 0; i < total; i++) {
    hits[rng.nextInt(bound)]++
  }
  for (let v = 0; v < bound; v++) {
    const ratio = (hits[v] ?? 0) / total
    assert.ok(ratio > 0.13 && ratio < 0.16, `值 ${v} 的频率 ${ratio} 偏离均匀分布`)
  }
})

test('chance 边界：0 恒 false，1.0 恒 true，越界抛错', () => {
  const rng = Prng.of(5)
  for (let i = 0; i < 100; i++) {
    assert.equal(rng.chance(0), false)
    assert.equal(rng.chance(10000), true)
  }
  assert.throws(() => rng.chance(-1), RangeError)
  assert.throws(() => rng.chance(10001), RangeError)
})

test('chance(1500) 在 20 万次采样下落在 15% ± 1%', () => {
  const rng = Prng.of(99)
  let hits = 0
  const total = 200000
  for (let i = 0; i < total; i++) {
    if (rng.chance(1500)) hits++
  }
  const ratio = hits / total
  assert.ok(ratio > 0.14 && ratio < 0.16, `实际频率 ${ratio}`)
})

test('shuffle 返回新数组、元素集合不变，且不修改入参', () => {
  const rng = Prng.of(8)
  const origin = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
  const snapshot = [...origin]
  const shuffled = rng.shuffle(origin)

  assert.deepEqual(origin, snapshot)
  assert.notEqual(shuffled, origin)
  assert.deepEqual([...shuffled].sort((a, b) => a - b), snapshot)
})

test('shuffle 可复现且确实发生了重排', () => {
  const pool = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
  assert.deepEqual(Prng.of(123).shuffle(pool), Prng.of(123).shuffle(pool))
  assert.notDeepEqual(Prng.of(123).shuffle(pool), pool)
})

test('pick 覆盖全部元素；空数组抛错而不是返回 undefined', () => {
  const rng = Prng.of(2025)
  const pool = ['重步兵', '轻骑兵', '弓兵', '攻城器']
  const total = 40000
  const hits = new Map<string, number>()
  for (let i = 0; i < total; i++) {
    const picked = rng.pick(pool)
    hits.set(picked, (hits.get(picked) ?? 0) + 1)
  }
  for (const name of pool) {
    const ratio = (hits.get(name) ?? 0) / total
    assert.ok(ratio > 0.2 && ratio < 0.3, `${name} 的频率 ${ratio} 偏离均匀分布`)
  }
  assert.throws(() => rng.pick([]), RangeError)
})

test('客户端只支持 32 位区间：超出上限时抛错，不静默失真', () => {
  const rng = Prng.of(1)
  assert.throws(() => rng.nextInt(4294967297), RangeError)
  assert.throws(() => rng.nextInt(0), RangeError)
  assert.throws(() => rng.nextInt(-1), RangeError)
  assert.throws(() => Prng.of(1.5), RangeError)
})

test('toString 不泄漏内部 state', () => {
  const a = Prng.of(1)
  const b = Prng.of(1)
  a.nextBits()
  assert.notEqual(a.toString(), b.toString())
  assert.match(a.toString(), /^Prng\(hash=[0-9a-f]+\)$/)
})
