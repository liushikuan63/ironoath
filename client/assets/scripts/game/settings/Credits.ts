/**
 * 职责：第三方素材署名清单（`art-src/ATTRIBUTION.md` 要求在游戏内「制作人员 / 开源许可」页保留的那一份）。
 * 依赖：无 —— 纯数据，引擎无关，可脱离 Cocos 跑单测（B00 铁律 2）。
 *
 * <p><b>为什么它必须存在</b>：Game-icons 是 **CC BY 3.0**，署名不是礼貌而是**分发的前提** ——
 * 不署名就没有权利使用那份素材。`art-src/ATTRIBUTION.md` 已经把要在界面上保留的那段原文写好了，
 * 本模块是它的机器可读版本；界面只负责画，不负责记。
 *
 * <p><b>条目一律照 LICENSE 原文，不改写、不缩写</b>：授权文本改一个字就不是原作者给出的那份许可了。
 * 所以下面 `credit` 那一行是**逐字复制**的，单测里也逐字钉住（见 `client/tests/Credits.test.ts`）。
 *
 * <p><b>CC0 的素材不要求署名</b>（Kenney 那六个包），本项目仍登记备查 ——
 * 但措辞要写清"不强制"，否则玩家会以为那也是一条必须保留的授权条件。
 */

/** 一条署名。 */
export interface CreditEntry {
  readonly key: string
  /** 这一组素材是什么（玩家看得懂的名字，不是目录名） */
  readonly title: string
  /** 作者与来源：**照 ATTRIBUTION.md / LICENSE 原文**，不改写不缩写 */
  readonly credit: string
  /** 许可全名（照 LICENSE 原文） */
  readonly license: string
  /** 一句话说清这份许可以对我们意味着什么（要不要署名 / 能不能商用） */
  readonly note: string
}

/**
 * 全部署名条目。
 *
 * <p>顺序即界面顺序：**要求署名的排最前**（那是授权条件），登记备查的在后。
 * 将来接入新素材时在这里加一条，并同步 `art-src/ATTRIBUTION.md` —— 两处都要改，缺一处就是漏署名。
 */
export const CREDITS: readonly CreditEntry[] = [
  {
    key: 'game-icons',
    title: 'Game-icons 图标',
    // 逐字来自 art-src/ATTRIBUTION.md 的原文块（CC BY 3.0 要求保留的那一段）
    credit: 'Icons made by Delapouite, Lorc, Skoll, Heavenly Dog, Sbed, Faithtoken, Andy Meneely from https://game-icons.net',
    license: 'Creative Commons Attribution 3.0（CC BY 3.0）',
    note: '按 CC BY 3.0 的要求保留署名 —— 这条不是礼貌，是使用这份素材的前提',
  },
  {
    key: 'kenney',
    title: 'Kenney 素材包（UI Pack 系列等 6 个包）',
    credit: 'Kenney · https://kenney.nl',
    license: 'CC0 1.0 Universal',
    note: 'CC0 不要求署名，本项目仍在此登记备查',
  },
  {
    key: 'generated',
    title: '本项目自有的生成素材',
    credit: '由本项目使用生成式模型产出并加工',
    license: '项目自有',
    note: '生成批次未使用第三方受版权保护的参考图',
  },
]

/**
 * 把一行长署名折成多行。
 *
 * <p><b>为什么在逻辑层折、不交给 Label 的 overflow</b>：署名那一行是英文作者名列表
 * （`Icons made by Delapouite, Lorc, … from https://game-icons.net`），
 * 让引擎按像素自动换行会在不同分辨率下折出不同的断点，而**作者名被劈成两半**是这一类页面最不该出的错。
 * 这里按**词**折，只有单个词本身超宽（超长 URL、中文长串）才按字符硬切。
 *
 * @param perLine 每行最多几个字符（按显示宽度粗算：中文一个字算一个）
 */
export function wrapCredit(text: string, perLine = 46): readonly string[] {
  if (!Number.isInteger(perLine) || perLine < 1) {
    throw new Error(`perLine 必须是正整数，实际=${perLine}`)
  }
  const lines: string[] = []
  let current = ''
  for (const word of text.split(' ')) {
    const width = Array.from(word).length
    if (width > perLine) {
      if (current !== '') {
        lines.push(current)
        current = ''
      }
      let rest = Array.from(word)
      while (rest.length > perLine) {
        lines.push(rest.slice(0, perLine).join(''))
        rest = rest.slice(perLine)
      }
      current = rest.join('')
      continue
    }
    const candidate = current === '' ? word : `${current} ${word}`
    if (Array.from(candidate).length > perLine) {
      lines.push(current)
      current = word
    } else {
      current = candidate
    }
  }
  if (current !== '') {
    lines.push(current)
  }
  return lines
}
