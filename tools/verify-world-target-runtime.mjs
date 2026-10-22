// B13 固定图层的真实产物验收。只连接调用方明确提供的自有后端/账号，不写Mongo。
// 探索状态若由外部夹具准备，必须在证据里注明；相机调用生产focusCoord，不算玩家攻占验收。
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright'
import { startPreviewServer } from './lib/preview-server.mjs'
import { hideGuideOverlay } from './lib/guide-overlay.mjs'
import { resolveCocosClickPoint } from './lib/cocos-click.mjs'

const backend = process.env.WORLD_TARGET_BACKEND
const deviceId = process.env.WORLD_TARGET_DEVICE
const out = process.env.WORLD_TARGET_OUT
const port = Number(process.env.WORLD_TARGET_PORT ?? 8351)
if (!backend || !deviceId || !out || !existsSync('client/build/web-mobile/index.html')) {
  console.error('需要WORLD_TARGET_BACKEND/DEVICE/OUT与本轮web-mobile产物；后端账号须为自有隔离输入')
  process.exit(2)
}
mkdirSync(out,{recursive:true})
const definitions = JSON.parse(readFileSync('contract/config/world_target.json','utf8')).rows
const targets = ['CAPITAL','GATE','FORTRESS'].map(type=>{
  const row=definitions.find(r=>r.kind===type)
  if(!row)throw Error('缺少战略目标类型 '+type)
  return {id:row.id,type,x:row.x,y:row.y,name:row.name}
})
const preview = await startPreviewServer({root:'client/build/web-mobile',backend,port})
let browser, page
const errors=[], warnings=[], requests=[], network=[], rows=[]
try {
  browser = await chromium.launch({headless:true})
  const context = await browser.newContext({viewport:{width:1280,height:720}})
  await context.addInitScript(value=>localStorage.setItem('ironoath.deviceId',value),deviceId)
  page=await context.newPage()
  page.on('pageerror',e=>errors.push(e.message))
  page.on('console',m=>{if(m.type()==='warning'&&/WorldViewModel|ChunkCache/.test(m.text()))warnings.push(m.text())})
  page.on('response',async response=>{
    if (new URL(response.url()).pathname!=='/world/viewport') return
    try {
      const body=await response.json()
      const input=response.request().postDataJSON()
      requests.push({center:[input.centerX,input.centerY],versions:input.chunkVersions?.length,
        chunks:body.data?.chunks?.map(c=>c.key),stale:body.data?.staleChunks,explored:body.data?.exploredChunks})
      if(body.code===0) for(const chunk of body.data.chunks??[]) for(const entity of chunk.entities??[]) {
        if(['GATE','CAPITAL','FORTRESS'].includes(entity.type)) network.push({id:entity.id,type:entity.type,name:entity.displayName})
      }
    } catch {}
  })
  await page.goto(preview.origin+'/?panel=world',{waitUntil:'domcontentloaded'})
  await page.waitForFunction(()=>{
    const scene=window.cc?.director?.getScene?.()
    const nodes=[];const visit=n=>{if(!n)return;nodes.push(n);for(const c of n.children??[])visit(c)};visit(scene)
    return nodes.some(n=>(n.components??[]).some(c=>c.root?.api?.worldReady===true))
  },null,{timeout:35_000})
  // worldReady 先于 AppRoot.deliver(home) 置真；等生产刷新完整收口，再设置验图相机。
  // 否则尚未返回的初始化 home 回执会把镜头拉回家，误报目标没有绘制。
  await page.evaluate(async()=>{
    let host;const visit=n=>{if(!n)return;for(const c of n.components??[])if(c.root?.api?.worldReady===true)host=c
      for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene())
    await host.root.refresh('world')
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))
    const maps=[];const find=n=>{if(!n)return;for(const c of n.components??[])if(c.drawnEntities instanceof Map)maps.push(c)
      for(const child of n.children??[])find(child)};find(window.cc.director.getScene())
    for(const map of maps) {
      const render=map.render
      map.render=function(frame,model) {
        this.__probeState={center:frame.center,tiles:frame.tiles.map(t=>({key:t.key,loaded:t.loaded,fogged:t.fogged,
          targets:t.entities.filter(e=>['GATE','CAPITAL','FORTRESS'].includes(e.type)).map(e=>e.id)})),
          requested:model.requestedKeys,viewport:model.viewportKeys,stale:[...model.stale],needsFetch:model.needsFetch,
          cached:[...model.cache.entries].map(([key,e])=>({key,version:e.version,count:e.entities.length}))}
        return render.call(this,frame,model)
      }
    }
  })
  await hideGuideOverlay(page)
  for (const [index,target] of targets.entries()) {
    await page.evaluate(({target,index})=>{
      const found=[];const visit=n=>{if(!n)return;for(const c of n.components??[]) {
        if(typeof c.focusCoord==='function'&&typeof c.zoomIn==='function'&&c.drawnEntities instanceof Map)found.push(c)
      }for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene())
      if(found.length!==1)throw Error('WorldMap必须唯一')
      if(index===0)found[0].zoomIn()
      found[0].focusCoord(target.x,target.y)
    },{target,index})
    const state = await page.waitForFunction(target=>{
      let map;const visit=n=>{if(!n)return;for(const c of n.components??[])if(c.drawnEntities instanceof Map)map=c
        for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene())
      const node=map?.drawnEntities.get(`${target.type}:${target.id}`), refs=map?.refs?.get(node)
      if(!node?.activeInHierarchy || refs?.label?.string!==target.name || refs.captionNode?.activeInHierarchy!==true)return false
      const labels=[];const read=n=>{const label=n.getComponent?.('cc.Label');if(n.activeInHierarchy&&label?.enabled)labels.push(String(label.string));for(const child of n.children??[])read(child)}
      read(window.cc.director.getScene())
      return {id:target.id,type:target.type,name:refs.label.string,nodeUuid:node.uuid,captionActive:true,
        leakedIds:labels.filter(s=>s.includes(target.id)||/\b(CAPITAL|GATE|FORTRESS)\b/.test(s))}
    },target,{timeout:20_000})
    const row=await state.jsonValue();await state.dispose()
    if(row.leakedIds.length)throw Error('屏上泄露目标内部标识')
    const giftActive=await page.evaluate(()=>{
      let active=false;const visit=n=>{if(!n)return;if(n.name==='giftPopup'&&n.activeInHierarchy)active=true
        for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene());return active
    })
    if(giftActive) {
      const close=await page.evaluate(resolveCocosClickPoint,{name:'close',within:'giftPopup'})
      if(close.verified!==true)throw Error('礼包关闭点未通过引擎命中自检')
      await page.mouse.click(close.x,close.y)
      await page.waitForFunction(()=>{
        let active=false;const visit=n=>{if(!n)return;if(n.name==='giftPopup'&&n.activeInHierarchy)active=true
          for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene());return !active
      },null,{timeout:5000})
      row.giftClosedByPointer=true
    }
    const point=await page.evaluate(resolveCocosClickPoint,{uuid:row.nodeUuid})
    if(point.verified!==true)throw Error('目标点击点未通过引擎命中自检')
    await page.mouse.click(point.x,point.y)
    await page.waitForFunction(target=>{
      let selected=false;const visit=n=>{if(!n)return;for(const c of n.components??[])
        if(c.drawnEntities instanceof Map&&c.selectedKey===`${target.type}:${target.id}`)selected=true
        for(const child of n.children??[])visit(child)};visit(window.cc.director.getScene());return selected
    },target,{timeout:5000})
    row.pointerSelected=true
    rows.push(row)
    await page.screenshot({path:path.join(out,target.id+'.png')})
  }
  const missing=targets.filter(t=>!network.some(n=>n.id===t.id&&n.name===t.name))
  if(errors.length || missing.length)throw Error(`页面错误${errors.length}，缺真实HTTP目标${missing.length}`)
  const report={backend,rows,network,errors,warnings,requests,previewRewrites:preview.rewrites(),
    scope:'real HTTP + built Cocos rendering; camera/exploration fixture, no combat or occupation acceptance'}
  writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2)+'\n')
  console.log(JSON.stringify({pass:rows.length,fail:0,pageErrors:errors.length,output:out}))
} catch(error) {
  await page?.screenshot({path:path.join(out,'failure.png')}).catch(()=>{})
  const sceneState=await page?.evaluate(()=>{
    const maps=[];const visit=n=>{if(!n)return;for(const c of n.components??[])if(c.drawnEntities instanceof Map)
      maps.push({node:n.name,active:n.activeInHierarchy,model:c.__probeState,strategic:[...c.drawnEntities].filter(([k])=>/^(CAPITAL|GATE|FORTRESS):/.test(k)).map(([k,v])=>({key:k,active:v.activeInHierarchy,text:c.refs?.get(v)?.label?.string,captionActive:c.refs?.get(v)?.captionNode?.activeInHierarchy}))})
      for(const child of n.children??[])visit(child)};visit(window.cc?.director?.getScene?.());return maps
  }).catch(()=>null)
  writeFileSync(path.join(out,'failure.json'),JSON.stringify({error:error.message,rows,network,errors,warnings,requests,sceneState},null,2)+'\n')
  throw error
} finally {
  await browser?.close()
  await preview.close()
}
