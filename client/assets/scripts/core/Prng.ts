/**
 * 职责：带 seed 的可复现伪随机数发生器 —— 服务端 game-common `Rng` 的 TypeScript 镜像（mulberry32）。
 * 依赖：无（引擎无关）。
 *
 * ⚠️ 用途边界（B00 跨语言一致性策略第 1 条）：客户端<b>不实现战斗复算</b>，
 *   本类只用于表现层随机（待机动作、飘字位置、装饰性粒子抖动）。
 *   战报重放 = 重播服务端下发的逐回合快照，不是用 seed 在客户端重算。
 *   seed 存在战报里是给服务端自查、问题复现与反外挂用的。
 *
 * 一致性保证：`next()` 与 `nextBits()` 与服务端 Java 实现<b>逐位相同</b>，
 * 由 golden vector 双端单测锁定（见 test/Prng.test.ts 与 RngTest）。
 * 一旦有人改了其中一端的算法，两边的单测会同时红 —— 这是刻意的强约束。
 *
 * 32 位限制：JS 没有原生 64 位整数，因此整数区间随机只提供 32 位版本。
 * 服务端 Rng 的 64 位 range 用于结算（兵数、资源量），客户端永远不需要它。
 */

/** mulberry32 每轮递增量。与服务端 Rng.INCREMENT 一致。 */
const INCREMENT = 0x6d2b79f5

/** 32 位雪崩混淆常量，与服务端 Rng.MIX_C1 / MIX_C2 一致。 */
const MIX_C1 = 0x7feb352d
const MIX_C2 = 0x846ca68b

/** 2^32。 */
const UINT32_SPACE = 4294967296

/** 定点 1.0，与 core/FixedPoint 的 SCALE 一致。此处内联避免 core 之间的循环依赖。 */
const FIXED_ONE = 10000

/** 32 位雪崩混淆，保证相邻 seed / salt 产生完全不同的初始状态。 */
function mix(x: number): number {
  let v = x | 0
  v ^= v >>> 16
  v = Math.imul(v, MIX_C1)
  v ^= v >>> 15
  v = Math.imul(v, MIX_C2)
  v ^= v >>> 16
  return v | 0
}

/** 把 JS number 形式的 seed 折叠成 32 位，与服务端 `(int)(seed ^ (seed >>> 32))` 等价。 */
function foldSeed(seed: number): number {
  if (!Number.isSafeInteger(seed)) {
    throw new RangeError(`seed 必须是安全整数（|seed| < 2^53），实际=${seed}`)
  }
  const low = seed | 0
  const high = Math.floor(seed / UINT32_SPACE) | 0
  return (low ^ high) | 0
}

/** 可复现随机流。非线程/非重入问题在 JS 单线程模型下不存在，但实例仍应按随机域独立持有。 */
export class Prng {
  private state: number

  private constructor(state: number) {
    this.state = state
  }

  /** 由 seed 创建。相同 seed 必然产生相同序列。 */
  static of(seed: number): Prng {
    return new Prng(mix(foldSeed(seed)))
  }

  /**
   * 派生子随机流。与服务端 `Rng.fork` 语义一致：
   * 子流推进不影响父流，父流推进也不影响已 fork 出的子流。
   */
  fork(salt: number): Prng {
    return new Prng(mix(this.state ^ mix(foldSeed(salt))))
  }

  /** mulberry32 单轮推进，返回有符号 32 位原始输出。与服务端 nextBits 逐位一致。 */
  nextBits(): number {
    this.state = (this.state + INCREMENT) | 0
    let t = Math.imul(this.state ^ (this.state >>> 15), this.state | 1)
    t = (t + Math.imul(t ^ (t >>> 7), t | 61)) ^ t
    return t ^ (t >>> 14)
  }

  /** [0, 1) 均匀随机。与服务端 next() 逐位一致。 */
  next(): number {
    return (this.nextBits() >>> 0) / UINT32_SPACE
  }

  /**
   * [0, bound) 均匀整数，无偏（拒绝采样）。
   *
   * 不用 `Math.floor(next() * bound)`：bound 较大时 next() 的 53 位尾数分布会让某些值概率偏高。
   */
  nextInt(bound: number): number {
    if (!Number.isSafeInteger(bound) || bound <= 0) {
      throw new RangeError(`bound 必须是正整数，实际=${bound}`)
    }
    if (bound === 1) {
      return 0
    }
    if (bound > UINT32_SPACE) {
      throw new RangeError(`客户端 PRNG 只支持 32 位区间，bound=${bound} 超出上限`)
    }
    const limit = Math.floor(UINT32_SPACE / bound) * bound
    let r = 0
    do {
      r = this.nextBits() >>> 0
    } while (r >= limit)
    return r % bound
  }

  /** 闭区间 [a, b] 均匀整数。 */
  range(a: number, b: number): number {
    if (!Number.isSafeInteger(a) || !Number.isSafeInteger(b) || b < a) {
      throw new RangeError(`range 要求 a <= b 且均为整数，实际 a=${a}, b=${b}`)
    }
    return a + this.nextInt(b - a + 1)
  }

  /**
   * 概率判定。probabilityFixed 为放大 10000 倍的概率（1500 = 15%）。
   * 与服务端 Rng.chance 同语义：0 恒 false，1.0 恒 true，越界抛错。
   */
  chance(probabilityFixed: number): boolean {
    if (probabilityFixed < 0) {
      throw new RangeError(`概率不得为负：${probabilityFixed}`)
    }
    if (probabilityFixed === 0) {
      return false
    }
    if (probabilityFixed > FIXED_ONE) {
      throw new RangeError(`概率不得超过定点 1.0（${FIXED_ONE}），实际=${probabilityFixed}`)
    }
    if (probabilityFixed === FIXED_ONE) {
      return true
    }
    return this.nextInt(FIXED_ONE) < probabilityFixed
  }

  /** 从数组随机取一个元素。空数组抛错，绝不返回 undefined。 */
  pick<T>(arr: readonly T[]): T {
    if (arr.length === 0) {
      throw new RangeError('pick 的入参数组不得为空')
    }
    const value = arr[this.nextInt(arr.length)]
    if (value === undefined) {
      throw new RangeError('pick 取到了越界下标，这不应发生')
    }
    return value
  }

  /** 随机打乱，返回新数组，不修改入参（Fisher-Yates）。 */
  shuffle<T>(arr: readonly T[]): T[] {
    const copy = [...arr]
    for (let i = copy.length - 1; i > 0; i--) {
      const j = this.nextInt(i + 1)
      const tmp = copy[i] as T
      copy[i] = copy[j] as T
      copy[j] = tmp
    }
    return copy
  }

  /** 不输出 state 明文：泄漏 state 等于泄漏后续全部随机序列。 */
  toString(): string {
    return `Prng(hash=${(mix(this.state) >>> 0).toString(16)})`
  }
}
