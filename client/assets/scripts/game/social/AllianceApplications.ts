/**
 * 职责：「入盟申请」那一屏的判定（B26 S8）—— 纯逻辑，不碰引擎。
 * 依赖：只有协议类型（`net/generated`）。
 *
 * <p>与另两份发现型列表同一条分工：① 昵称与主城等级都用服务端下发的那一份，
 * 客户端没有玩家表，拿 id 猜名字就是第二真源；② 有界列表要说清总量；
 * ③ **空表与读不到是两句话** —— 前者是"暂时没有待处理的申请"（功能在、此刻没活），
 * 后者是"读取中"（过一会儿会自己好），把后者写成前者，盟主会以为自己没收到过申请。
 *
 * <p>能不能审**不在这里判**：那一格由 `permissions` 里的 `APPROVE_APPLICATION` 决定，
 * 编排层据此决定拉不拉这一份、画不画这一段（客户端不自己按职位推权限）。
 */
import type { AllianceApplicationListResp, ApplicantView } from '../../net/generated/SocialProtocol'

/** 一行申请。两颗按钮一按就发，不做"两下才算数"（那套留给不可逆的组织去留）。 */
export interface ApplicantRow {
  readonly id: string
  /** 「社交测试」 */
  readonly titleText: string
  /** 「主城 3 级」 */
  readonly detailText: string
}

export interface ApplicationView {
  readonly rows: readonly ApplicantRow[]
  /** 那句总量/状态说明。空串表示不需要说明（全部都已画出且有行）。 */
  readonly notice: string
}

export const EMPTY_APPLICATIONS: ApplicationView = { rows: [], notice: '' }

export function buildApplications(resp: AllianceApplicationListResp | null): ApplicationView {
  if (resp === null) {
    return { rows: [], notice: '申请名单读取中' }
  }
  const rows = resp.applicants.map((a: ApplicantView): ApplicantRow => ({
    id: a.playerId,
    titleText: a.nickname,
    detailText: `主城 ${a.mainCityLevel} 级`,
  }))
  return {
    rows,
    notice: resp.total > resp.applicants.length
      ? `共 ${resp.total} 条待处理，这里只显示前 ${resp.applicants.length} 条`
      : resp.total === 0 ? '暂时没有待处理的申请' : '',
  }
}
