/**
 * 职责：邮件面板的展示组装（B12 §2）—— 把 MailListResp 变成一屏行、算出一键领取按钮该不该亮。
 * 依赖：只有协议类型（`net/generated/MailProtocol`）。
 *
 * <p><b>本模块不做任何业务判定</b>（铁律 2）：能不能领、读过没有、还剩几天，
 * 全部是服务端 `MailView` 上已经算好的布尔与时刻。这里只把它们<b>说成一句人话</b>。
 *
 * <p><b>为什么"还剩几天"要在这里算而不是等下发一个字符串</b>：那一格是相对时间，
 * 服务端下发的瞬间就可能过时（玩家把面板开着放十分钟），而本作没有任何推送通道。
 * 让客户端拿服务端的两个时刻相减是展示，不是判定 —— 它不会改变谁能领什么。
 */
import type { MailListResp, MailView } from '../../net/generated/MailProtocol'

/** 一行的展示数据。全部字段都是字符串或布尔，渲染层不需要再算。 */
export interface MailRow {
  readonly mailId: string
  readonly title: string
  readonly body: string
  /** 附件摘要（「金币 ×500 · 加速卡 ×2」）；无附件为空串。 */
  readonly attachmentText: string
  /** 右上状态：未读加粗、已领写「已领取」。 */
  readonly statusText: string
  readonly unread: boolean
  /** 可领 = 有附件且没领过（两个布尔都来自服务端，这里只做与运算）。 */
  readonly claimable: boolean
  /** 「N 天后过期」；不足一天写成小时，玩家不该为"三天"和"三天前"分不出先后。 */
  readonly expiresIn: string
}

export interface MailPanelView {
  readonly rows: readonly MailRow[]
  /** 顶部一行：封数 + 未读 + 可领。空邮箱有专门一句话，因为「0 封」与「没连上」玩家要分得开。 */
  readonly headerText: string
  /** 一键领取按钮是否可用。false 时点下去只会拿到一个 claimed=0 的空响应。 */
  readonly claimAllEnabled: boolean
  readonly unreadCount: number
  readonly claimableCount: number
}

const DAY_MS = 86_400_000

/** 空邮箱与"一封都没领过"是两句话，所以这里分开生成。 */
const EMPTY_HEADER = '邮箱是空的'

/**
 * 组装面板数据。
 *
 * @param resp 服务端 `/mail/list` 的原样响应
 * @param now 服务端时刻（由 TimeSync 给，不用本地墙钟 —— 那会让"还剩几天"跟着手机时钟漂移）
 */
export function buildMailPanel(resp: MailListResp, now: number): MailPanelView {
  const rows = resp.mails.map(mail => rowOf(mail, now))
  const claimableCount = rows.filter(row => row.claimable).length
  const headerText = rows.length === 0
    ? EMPTY_HEADER
    : `${rows.length} 封 · 未读 ${resp.unreadCount} · 可领 ${claimableCount}`
  return {
    rows,
    headerText,
    claimAllEnabled: claimableCount > 0,
    unreadCount: resp.unreadCount,
    claimableCount,
  }
}

/**
 * 一键领取该发哪一句提示（领完之后）。
 *
 * <p>失败那几封必须说出来：它们<b>还在邮箱里</b>，而玩家如果不知道，
 * 下一次点开会以为「刚才那一下没生效」然后反复点。
 */
export function claimOutcomeText(claimed: number, rewardTexts: readonly string[],
                                 failed: readonly { mailId: string, reason: string }[]): string {
  const parts: string[] = []
  if (claimed > 0) {
    parts.push(`已领取 ${claimed} 封：${rewardTexts.join('、')}`)
  }
  if (failed.length > 0) {
    parts.push(`${failed.length} 封没领到（${failed[0]?.reason ?? '原因未知'}），仍留在邮箱里`)
  }
  if (parts.length === 0) {
    return '没有可领的附件'
  }
  return parts.join('；')
}

function rowOf(mail: MailView, now: number): MailRow {
  const claimable = !mail.claimed && mail.rewards.length > 0
  return {
    mailId: mail.mailId,
    title: mail.title,
    body: mail.text,
    attachmentText: mail.rewards.map(r => `${r.name} ×${r.count}`).join(' · '),
    /**
     * 右上状态。**先看有没有附件再看 claimed**：服务端的 `claimed` 对纯通知也是 true
     * （契约里写明「无附件可领」与「领过了」对玩家是同一句话），反过来看客户端就会把
     * 一封公告画成「已领取」，玩家以为是自己点掉的。
     */
    statusText: mail.rewards.length === 0
      ? '纯通知'
      : (mail.claimed ? '已领取' : '可领取'),
    unread: !mail.read,
    claimable,
    expiresIn: expiresInText(mail.expireAt - now),
  }
}

/** 剩余时间的一句话。负数按「今天到期」而不是「已过期」—— 过期的一封服务端根本不会下发。 */
function expiresInText(millis: number): string {
  if (millis <= 0) {
    return '今天到期'
  }
  if (millis < DAY_MS) {
    return `${Math.max(1, Math.ceil(millis / 3_600_000))} 小时内到期`
  }
  return `${Math.floor(millis / DAY_MS)} 天后到期`
}
