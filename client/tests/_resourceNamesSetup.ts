/**
 * 职责：给**每一个**客户端单测装上「服务端下发的资源中文名」。
 * 依赖：node:fs / node:path / `assets/scripts/game/ui/ResourceNames`。
 *
 * <p><b>为什么需要它</b>：资源中文名现在由服务端随 `/player/init` 下发（`PlayerInitResp.resourceNames`），
 * 客户端只建一张运行时映射。而**二十来条既有用例**断言的是面板上的**显示文案**
 * （「木材 1200/5000」这类），它们要看到名字 ⇒ 必须有一份下发好的映射。
 *
 * <p><b>它模拟的是服务端，不是「客户端的副本」</b>：它读的是
 * `contract/config/resource.json` —— **服务端读的是同一张表**，
 * 所以「服务端下发的名字」与「表里的名字」逐字相同是事实，不是巧合。
 * 换句话说：这里模拟的是**服务端的行为**（读表 ⇒ 递出去），
 * 而客户端那份「不读表」的性质由 `ResourceNames.test.ts` 那三条断言单独钉着
 * （清空下发 ⇒ 客户端自己给不出「木材」）。
 *
 * <p><b>为什么放在 tests/ 而不是 client/ 的运行时代码里</b>：
 * 它是**测试夹具**，不是产品逻辑。放进 `client/assets/scripts` 就等于把表又搬进了客户端。
 *
 * <p><b>怎么被加载</b>：`scripts/test-client.sh` 给 `node --test` 传
 * `--import ./build-test/tests/_resourceNamesSetup.js`。
 * 实测（Node 20）：`--import` 会**传播到每个测试子进程**（测试文件跑在各自的子进程里），
 * 所以每个文件里的模块状态都是「已下发」的状态。⇒
 * 断言「客户端自己给不出名字」的那条用例必须**自己先清空**（它就是这么写的）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { applyResourceNames } from '../assets/scripts/game/ui/ResourceNames'

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'resource.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/resource.json')
}

const rows = (JSON.parse(
  fs.readFileSync(path.join(repoRoot(), 'contract', 'config', 'resource.json'), 'utf8'),
).rows as { id: string, name: string }[])

applyResourceNames(rows.map((row) => ({ type: row.id, name: row.name })))
