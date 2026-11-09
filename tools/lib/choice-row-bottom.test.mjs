import test from 'node:test'
import assert from 'node:assert/strict'
import { assessChoiceRowBottom } from './choice-row-bottom.mjs'

test('choice last row stays inside its real viewport and clear of footer', () => {
  const actual = assessChoiceRowBottom({
    row: { x: 20, y: 110, width: 160, height: 50 },
    viewport: { x: 0, y: 100, width: 200, height: 200 },
    footer: { x: 50, y: 40, width: 100, height: 40 },
  })
  assert.equal(actual.pass, true)
  assert.equal(actual.rowViewportClearance, 10)
  assert.equal(actual.rowBottomClearance, 10)
  assert.equal(actual.rowFooterClearance, 30)
  assert.equal(actual.viewportFooterClearance, 20)
})

test('choice geometry rejects a clipped last row and row/footer overlap', () => {
  const base = {
    viewport: { x: 0, y: 100, width: 200, height: 200 },
    footer: { x: 50, y: 40, width: 100, height: 40 },
  }
  assert.equal(assessChoiceRowBottom({ ...base, row: { x: 20, y: 90, width: 160, height: 50 } }).pass, false)
  assert.equal(assessChoiceRowBottom({ ...base, row: { x: 20, y: 70, width: 160, height: 50 } }).pass, false)
})
test('vertical content capacity derives row count, row pitch, and footer geometry', async () => {
  const { assessDialogVerticalCapacity } = await import('./choice-row-bottom.mjs')
  assert.equal(typeof assessDialogVerticalCapacity, 'function')
  const rows = [
    { bottom: 244, top: 300 },
    { bottom: 184, top: 240 },
    { bottom: 124, top: 180 },
    { bottom: 64, top: 120 },
  ]
  const footer = { bottom: -12, top: 26 }
  assert.deepEqual(assessDialogVerticalCapacity({ netContentHeight: 280, rows, footer }), {
    pass: true, visibleRowCount: 4, rowPitch: 60, footerHeight: 38,
    requiredHeight: 278, netContentHeight: 280, slack: 2, footerGap: 38,
  })
  assert.equal(assessDialogVerticalCapacity({ netContentHeight: 277, rows, footer }).pass, false)
  assert.equal(assessDialogVerticalCapacity({ netContentHeight: 280, rows, footer: { bottom: 60, top: 98 } }).pass, false)
})

test('vertical capacity rejects rows taller than their measured pitch even with sufficient net height', async () => {
  const { assessDialogVerticalCapacity } = await import('./choice-row-bottom.mjs')
  const rows = [240, 200, 160, 120].map(bottom => ({ bottom, top: bottom + 44 }))
  const result = assessDialogVerticalCapacity({ netContentHeight: 280, rows, footer: { bottom: 20, top: 58 } })
  assert.equal(result.pass, false, 'adjacent 44px rows at 40px pitch overlap by 4px')
})


const labelFixture = () => ({
  plate: { left: 0, right: 200, bottom: 0, top: 220 }, insets: { left: 10, right: 10, top: 10, bottom: 10 },
  labels: {
    title: { visible: true, rect: { left: 20, right: 180, bottom: 160, top: 180 } },
    coord: { visible: true, rect: { left: 20, right: 180, bottom: 130, top: 150 } },
    crest: { active: true, rect: { left: 80, right: 120, bottom: 185, top: 210 } },
    total: { visible: true, rect: { left: 20, right: 180, bottom: 15, top: 35 }, clips: [{ enabled: true, left: 10, right: 190, bottom: 10, top: 210 }] },
    rows: [{ labels: [{ visible: true, rect: { left: 20, right: 100, bottom: 80, top: 100 } }, { visible: true, rect: { left: 120, right: 180, bottom: 80, top: 100 } }] }],
  },
})

for (const issue of ['none', 'header', 'locked-row', 'summary', 'summary-clip', 'hidden-title', 'hidden-row', 'summary-no-mask', 'summary-disabled-mask']) {
  test(`compose text geometry ${issue === 'none' ? 'accepts separated production regions' : 'rejects ' + issue}`, async () => {
    const { assessComposeLabelGeometry } = await import('./choice-row-bottom.mjs')
    assert.equal(typeof assessComposeLabelGeometry, 'function')
    const input = labelFixture()
    if (issue === 'header') input.labels.crest.rect = { ...input.labels.title.rect }
    if (issue === 'locked-row') input.labels.rows[0].labels[1].rect.left = 90
    if (issue === 'summary') input.labels.total.rect.bottom = 0
    if (issue === 'summary-clip') input.labels.total.clips[0].bottom = 20
    if (issue === 'hidden-title') input.labels.title.visible = false
    if (issue === 'hidden-row') input.labels.rows[0].labels[0].visible = false
    if (issue === 'summary-no-mask') input.labels.total.clips = []
    if (issue === 'summary-disabled-mask') input.labels.total.clips[0].enabled = false
    const actual = assessComposeLabelGeometry(input)
    assert.equal(actual.pass, issue === 'none')
    if (issue === 'header') assert.equal(actual.headerClear, false)
    if (issue === 'locked-row') assert.equal(actual.rowsClear, false)
    if (issue === 'summary' || issue === 'summary-clip') assert.equal(actual.summaryContained, false)
  })
}
