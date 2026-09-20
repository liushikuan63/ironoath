/**
 * 职责：联盟科技那几行的判定用例（B26 S9）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯三条：名字与价格都用服务端下发的（客户端没有表）、两条门各说一件事且顺序不能反、
 * 灰态每一态都有一句人话。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { buildTechRows } from '../assets/scripts/game/social/AllianceTechCatalog'
import type { ResearchGate } from '../assets/scripts/game/social/AllianceTechCatalog'
import type { AllianceTechView } from '../assets/scripts/net/generated/SocialProtocol'

const tech = (over: Partial<AllianceTechView>): AllianceTechView => ({
  techId: 'atech_atk', level: 1, levelCap: 40, effectFixed: 150,
  name: '联盟锋刃', nextLevelCost: 2000, canResearch: true, reason: null, ...over,
})

const allowed: ResearchGate = { allowed: true, reason: null }
const denied: ResearchGate = { allowed: false, reason: '你当前的职位不能做这件事' }

test('能研究的那一行：名字与价格都用表里那一份，按钮写「研究」', () => {
  const rows = buildTechRows([tech({})], allowed)
  assert.deepEqual(rows.map(r => [r.titleText, r.detailText, r.actionText, r.enabled]), [
    ['联盟锋刃', 'Lv1/40 · 下一级 2000', '研究', true],
  ])
  assert.equal(rows[0]?.reason, null)
})

test('职位不够时优先说职位：不能让人以为"捐钱就能研究"', () => {
  const rows = buildTechRows([tech({ canResearch: false, reason: '联盟资金还不够，多捐一些就能研究' })],
    denied)
  assert.equal(rows[0]?.actionText, '不能研究')
  assert.equal(rows[0]?.enabled, false)
  assert.equal(rows[0]?.reason, '你当前的职位不能做这件事',
    '说的是职位那句，不是资金那句')
})

test('到上限与钱不够是两句话：前者要升联盟等级，后者要捐钱', () => {
  const atCap = buildTechRows([tech({ level: 40, canResearch: false, reason: '本盟等级下已经研究到头了' })],
    allowed)
  assert.deepEqual([atCap[0]?.actionText, atCap[0]?.reason],
    ['已满', '本盟等级下已经研究到头了'])
  const poor = buildTechRows([tech({ canResearch: false, reason: '联盟资金还不够，多捐一些就能研究' })],
    allowed)
  assert.deepEqual([poor[0]?.actionText, poor[0]?.reason],
    ['钱不够', '联盟资金还不够，多捐一些就能研究'])
})

test('0 级的那几项照画不误：目录少了它们，玩家就看不见这功能存在', () => {
  const rows = buildTechRows([tech({ level: 0, effectFixed: 0 })], allowed)
  assert.equal(rows[0]?.detailText, 'Lv0/40 · 下一级 2000')
  assert.equal(rows[0]?.enabled, true, '0 级且资金够 ⇒ 按钮是亮的')
})
