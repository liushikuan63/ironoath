#!/usr/bin/env node
// 固定战略目标发布前的只读预检。仅查指定本机 Mongo 容器，不迁城、不建索引、不写档。
// 用法：node scripts/tools/check-world-target-occupancy.mjs --database <实际库名> [--container mongo27]
// 退出码：0=本次读取无冲突；1=有旧城冲突，禁止覆盖；2=输入/连接/量具失败。
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const args = process.argv.slice(2)
const options = {}
for (let i=0; i<args.length; i+=2) {
  if (!['--database','--container'].includes(args[i]) || !args[i+1] || options[args[i]]) {
    console.error('需要 --database <实际库名>，可选 --container <本机容器名>'); process.exit(2)
  }
  options[args[i]] = args[i+1]
}
const database = options['--database']
const container = options['--container'] ?? 'mongo27'
if (!database || !/^[a-zA-Z0-9_-]+$/.test(database) || !/^[a-zA-Z0-9_.-]+$/.test(container)) {
  console.error('库名/本机容器名无效；本工具不接受 URI 或凭据'); process.exit(2)
}
const table = JSON.parse(readFileSync(fileURLToPath(new URL('../../contract/config/world_target.json',import.meta.url)),'utf8'))
const global = JSON.parse(readFileSync(fileURLToPath(new URL('../../contract/config/global.json',import.meta.url)),'utf8'))
const size = Number(global.rows.find(r => r.id==='WORLD_SIZE')?.value)
const targets = table.rows.map(r => ({id:r.id,x:r.x,y:r.y}))
const keys = new Set(targets.map(t => `${t.x}:${t.y}`))
if (!Number.isSafeInteger(size) || size<=0 || !targets.length || keys.size!==targets.length
  || targets.some(t => !Number.isSafeInteger(t.x) || !Number.isSafeInteger(t.y) || t.x<0 || t.y<0 || t.x>=size || t.y>=size)) {
  console.error('目标目录为空、重复或越界'); process.exit(2)
}
// 通过 argv 传给 mongosh，绝不经过 shell；值也不作为可执行文本拼进命令行。
const query = `const name=${JSON.stringify(database)}, targets=${JSON.stringify(targets)};
const names=db.getMongo().getDBNames();
if (!names.includes(name)) throw Error('指定数据库不存在，不能把空库当通过');
const source=db.getSiblingDB(name);
if (!source.getCollectionNames().includes('world_city')) throw Error('缺world_city，不能把未初始化库当通过');
const cities=source.getCollection('world_city');
const conflicts=targets.map(t=>({...t,cities:cities.countDocuments({x:t.x,y:t.y})})).filter(t=>t.cities>0);
print(JSON.stringify({database:name,atUtc:new Date().toISOString(),targets:targets.length,
cityCount:cities.countDocuments({}),conflicts,readOnly:true,scope:'point-in-time occupancy only; no migration or attack acceptance'}));`
const result = spawnSync('docker',['exec','-i',container,'mongosh','--quiet','--norc','--eval',query],
  {encoding:'utf8',windowsHide:true,timeout:30_000,maxBuffer:1024*1024,shell:false})
if (result.error || result.status!==0) {
  console.error(result.error?.message ?? result.stderr.trim() ?? 'Mongo只读预检失败'); process.exit(2)
}
try {
  const report = JSON.parse(result.stdout.trim())
  if (!Array.isArray(report.conflicts) || report.readOnly!==true) throw Error('无有效报告')
  console.log(JSON.stringify(report))
  process.exit(report.conflicts.length ? 1 : 0)
} catch (error) {
  console.error(`只读预检结果无效：${error.message}`); process.exit(2)
}
