/**
 * 职责：自动续训 / 自动补兵的**客户端那一半** —— 开关按钮说什么、发什么请求（B25 §四 裁决③(a)）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>客户端不算账、不定策略</b>：预算、停止原因、还剩几批、队列与资源够不够，全部由服务端裁定
 * （它保存着一份有预算的策略并惰性地执行）。这里只做两件客户端必须自己做的事：
 * 把服务端下发的策略翻成一句玩家看得懂的话，以及**把「续训的是哪一批」这件事记住**。
 *
 * <p><b>为什么"续训哪一批"要客户端记</b>：裁决③(a) 把策略存在服务端，但"哪一批值得重复"只有玩家的
 * 上一手操作知道 —— 服务端记的是一份策略，不是一个"最近一次训练"的历史（那要新开一本账，
 * 见裁决② 的 (c) 被否掉的理由）。所以客户端记住**最后一次成功训练**的兵种与数量，
 * 开启时把它当作"要续的那一批"报给服务端；这条与一键重复出征的「上一次参数」是同一条口径。
 *
 * <p><b>记的是成功的那一次</b>：被拒的训练不进记忆 —— 否则玩家点了一次"兵营没到 10 级"，
 * 之后开的自动续训就一直盯着一支永远排不出来的兵种。
 */

import type { AutoTrainReq, AutoTrainView } from '../../net/generated/ArmyProtocol'

/**
 * 一次开启最多自动排几批。
 *
 * <p><b>这是 UI 默认值，不是权威</b>：服务端另有上限（表 `global.AUTO_TRAIN_MAX_BATCHES`，现 5），
 * 超了会被 `/army/autoTrain` 当场拒并带回上限本身。取 3 而不是取满：一个"排三次"的默认
 * 够玩家睡一觉回来还有一批在训，而它花掉的是他自己看得到的资源。
 * 客户端读不到那张表（`gen.sh` 不生成 global），所以这个数写在客户端 —— 只当默认值用。
 */
export const AUTO_TRAIN_BATCH_BUDGET = 3

/** 客户端记住的「上一次训练」：自动续训按它来续。 */
export interface TrainMemory {
  readonly unitId: string
  readonly count: number
}

/**
 * 记下一次**成功**的训练。数量必须为正 —— 0/负数不是"一批兵"，而续训会照着它一直排下去。
 *
 * <p>没有"上一次"入参：记忆只保留最近一次，调用方直接覆盖即可（要按新旧做判断的语义
 * 不属于这里 —— 那是"要不要记住"的决策，属于编排层）。
 */
export function rememberTrain(unitId: string, count: number): TrainMemory {
  if (unitId === null || unitId === undefined || unitId === '') {
    throw new Error('兵种不得为空')
  }
  if (!Number.isFinite(count) || count <= 0) {
    throw new Error(`训练数量必须为正，实际=${String(count)}`)
  }
  return { unitId, count }
}

/** 开关按钮上的字：开着还是关着，一眼看出来。 */
export function autoTrainToggleCaption(policy: AutoTrainView): string {
  return policy.enabled ? '停止自动' : '自动续训'
}

/**
 * 开着的时候那行状态：「重步兵 ×50 · 还剩 2 批」/「重步兵 补到 800 · 还剩 1 批」。
 * 没开时为 null（关着的时候不该有一行"正在盯着什么"的说明）。
 */
export function autoTrainRunningText(policy: AutoTrainView, unitName: string): string | null {
  if (!policy.enabled) {
    return null
  }
  const target = policy.targetCount > 0 ? `补到 ${policy.targetCount}` : `×${policy.batchCount}`
  return `${unitName} ${target} · 还剩 ${policy.batchBudget} 批`
}

/**
 * 停止原因原样透传：那是服务端写给人看的一句话（"资源不够，自动续训已停下（不会自动恢复…）"），
 * 客户端重写一遍只会丢信息 —— 而"为什么停了"正是玩家回来最想知道的。
 */
export function autoTrainStopText(policy: AutoTrainView): string | null {
  return policy.stopReason
}

/**
 * 现在能不能开。不能开就说清为什么 —— 返回 null 表示可以开。
 *
 * <p>唯一的门槛是"没有可续的那一批"：自动续训 = 按上一次训练再来一批，
 * 没训过就无从续起（服务端也能接受任意兵种+数量的开启，但那是另一个产品决策：
 * 谁来决定第一次训多少？玩家自己点一次比替他决定更诚实）。
 */
export function autoTrainBlockedReason(memory: TrainMemory | null): string | null {
  if (memory === null) {
    return '先手动训一批，「自动续训」就按那一批一直排下去'
  }
  return null
}

/**
 * 开关请求体（requestId 由传输层生成，与其它 `mutate` 调用一致）。
 *
 * <p>关掉只需要 `enabled:false` —— <b>不必再报一遍目标</b>，否则玩家会以为关不掉（契约里明写了这条）。
 */
export function autoTrainRequest(
  policy: AutoTrainView, memory: TrainMemory | null,
): Omit<AutoTrainReq, 'requestId'> {
  if (policy.enabled) {
    return { enabled: false, unitId: null, count: null, batchBudget: null, targetCount: null }
  }
  const blocked = autoTrainBlockedReason(memory)
  if (blocked !== null || memory === null) {
    throw new Error(blocked ?? '没有可续的训练')
  }
  return {
    enabled: true,
    unitId: memory.unitId,
    count: memory.count,
    batchBudget: AUTO_TRAIN_BATCH_BUDGET,
    targetCount: null,
  }
}
