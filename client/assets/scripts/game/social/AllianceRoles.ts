/**
 * 职责：「任命职位」那个小弹层的选项（B26 S11）—— 纯逻辑，不碰引擎。
 * 依赖：`SocialPanel.allianceRoleText`（职位中文名只这一份，弹层与行标签共用）。
 *
 * <p>两条口径：
 * ① **盟主不在选项里**：把盟主给出去是另一件事（转让，且要两下确认），
 *    混在这里等于一颗按钮同时管两种后果完全不同的操作。
 * ② 这里**不判"我能不能任命"**：那一格由 `/social/permissions` 的 `SET_ROLE` 说，
 *    也不判"这个职位我压不压得下去"——那是 `Alliance.setRole` 的域内规则
 *    （不能任命高于自己、不能降级盟主），客户端猜一份就是第二真相。
 */
import type { AllianceRole } from '../../net/generated/SocialProtocol'
import { allianceRoleText } from './SocialPanel'

export interface RoleChoice {
  readonly id: AllianceRole
  readonly label: string
  readonly detail: string
  /** 这个人现在就是这个职位 —— 标出来，但仍然可点（点下去服务端不会重复扣版本，也没坏处） */
  readonly current: boolean
}

/** 能被任命成的职位（不含盟主）。 */
export const ASSIGNABLE_ROLES: readonly AllianceRole[] = ['OFFICER', 'ELDER', 'MEMBER']

export function roleChoices(current: AllianceRole | string | null): RoleChoice[] {
  return ASSIGNABLE_ROLES.map((role): RoleChoice => ({
    id: role,
    label: allianceRoleText(role),
    detail: role === 'MEMBER' ? '收回职位，降为普通成员' : '能在成员名单上做的事多一项',
    current: role === current,
  }))
}

/** 弹层标题：说清在给谁任命。 */
export function rolePickerTitle(nickname: string): string {
  return `给 ${nickname} 任命职位`
}
