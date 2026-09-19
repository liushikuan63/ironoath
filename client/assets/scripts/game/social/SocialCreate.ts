/**
 * 职责：创建小队/联盟这一屏的判定（B26 S2）—— 纯逻辑，不碰引擎。
 *
 * <p>三条纪律：
 * ① **门槛与数额只看服务端下发的政策**：主城几级、开服第几天、扣多少，全写在
 *    squad_config/alliance_config 与 global 里，客户端一份都不抄 —— 抄了就是第二真相：
 *    表一改，界面说着「还差 2 级」而服务端其实已经放行（反过来会把能点的人挡在门外）。
 * ② **只判服务端也会判的那几条**：非空（服务端那句「联盟名与标签都不得为空」）与钱够不够
 *    （服务端那句「需要金币 X，当前 Y」）。标签长度服务端**不判**，这里也不判 ——
 *    客户端凭空加一条规则，玩家就会被一个并不存在的门槛挡住，而界面说不出为什么（#296 同族）。
 * ③ 资源中文名走 `ui/ResourceNames.ts` 那唯一一份，不在这里再写一遍「金币」。
 */
import type { SocialCreatePolicy, SocialCreatePolicyResp } from '../../net/generated/SocialProtocol'
import { resourceName } from '../ui/ResourceNames'

/** 两个层级共用一份表单，差别只在要不要标签。 */
export type CreateScope = 'squad' | 'alliance'

/** 面板上「创建」那一行（只在没加入那个层级的时候画）。 */
export interface CreateEntry {
  readonly actionText: string
  readonly enabled: boolean
  /** 灰的时候原样转述服务端那句原因；亮的时候写要扣什么。 */
  readonly detailText: string
}

/**
 * 创建那一行。
 *
 * <p>钱不够**不**把这一行按灰：打开表单不是一个会被服务端拒绝的动作，把「还差 300」写在行上、
 * 让玩家进去看清差额再取消，比连表单都打不开更有信息量。真正拦得住的是表单的「确认」。
 */
export function createEntry(scope: CreateScope, policy: SocialCreatePolicy | null,
                            balance: number | null): CreateEntry {
  const label = scope === 'squad' ? '创建小队' : '创建联盟'
  if (policy === null) {
    return { actionText: label, enabled: false, detailText: '创建条件读取中' }
  }
  if (!policy.canCreate) {
    return { actionText: label, enabled: false, detailText: policy.reason ?? '现在不能创建' }
  }
  return { actionText: label, enabled: true, detailText: costText(policy, balance) }
}

/**
 * 两个层级各一行「创建」，面板按当前页签取用。
 *
 * <p>余额由编排层算好传进来：视图自己去翻资源快照等于把"哪个字段叫什么"这件事散到第二处。
 */
export function createEntries(
  resp: SocialCreatePolicyResp | null,
  squadBalance: number | null,
  allianceBalance: number | null,
): Record<CreateScope, CreateEntry> {
  return {
    squad: createEntry('squad', resp?.squad ?? null, squadBalance),
    alliance: createEntry('alliance', resp?.alliance ?? null, allianceBalance),
  }
}

/** 「消耗 500 金币 · 还差 300」那一行；数额与类型都来自政策。 */export function costText(policy: SocialCreatePolicy | null, balance: number | null): string {
  if (policy === null) {
    return '消耗读取中'
  }
  if (policy.costGold <= 0) {
    return '不消耗资源'
  }
  const cost = `消耗 ${policy.costGold} ${resourceName(policy.costResource)}`
  if (balance === null) {
    return cost
  }
  return balance >= policy.costGold
    ? `${cost} · 我有 ${balance}`
    : `${cost} · 还差 ${policy.costGold - balance}`
}

/** 表单那一屏要画的全部文字与「确认」能不能点。 */
export interface CreateForm {
  readonly titleText: string
  readonly nameLabel: string
  /** 小队没有标签，返回 null 让视图连输入框都不画（画一个填了也没用的框是骗人）。 */
  readonly tagLabel: string | null
  readonly costText: string
  readonly hint: string
  readonly canSubmit: boolean
  /**
   * 回显当前输入。输入态存在编排层（视图自己记一份就会出现"关掉再开还留着上次的字"），
   * 而弹层重画时要按它填回 EditBox —— 少了这两个字段，打字这件事会把玩家刚敲的字吃掉。
   */
  readonly name: string
  readonly tag: string
}

/**
 * 组装创建表单。
 *
 * @param balance 该资源的余额；没读到传 null —— 读不到不等于零，不能因此把确认按死
 */
export function buildCreateForm(scope: CreateScope, policy: SocialCreatePolicy | null,
                                balance: number | null, name: string, tag: string): CreateForm {
  const tagLabel = scope === 'squad' ? null : '联盟标签'
  const blocked = policy === null ? '创建条件读取中，稍后再试'
    : !policy.canCreate ? (policy.reason ?? '现在不能创建')
      : name.trim() === '' ? (scope === 'squad' ? '先给小队起个名字' : '先给联盟起个名字')
        : tagLabel !== null && tag.trim() === '' ? '还要填一个标签'
          : shortBy(policy, balance)
  return {
    titleText: scope === 'squad' ? '创建小队' : '创建联盟',
    nameLabel: scope === 'squad' ? '小队名' : '联盟名',
    tagLabel,
    costText: costText(policy, balance),
    hint: blocked ?? '',
    canSubmit: blocked === null,
    name,
    tag,
  }
}

/** 服务端会判「需要金币 X，当前 Y」，所以钱不够时客户端也不发这一枪。 */
function shortBy(policy: SocialCreatePolicy, balance: number | null): string | null {
  if (balance === null || policy.costGold <= 0 || balance >= policy.costGold) {
    return null
  }
  return `还差 ${policy.costGold - balance} ${resourceName(policy.costResource)}`
}
