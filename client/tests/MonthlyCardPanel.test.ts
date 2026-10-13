import test from 'node:test'
import assert from 'node:assert/strict'
import { buildMonthlyCardPanel, monthlyClaimBody, monthlyPurchaseId, monthlyClaimResultText } from '../assets/scripts/game/shop/MonthlyCardPanel'
import type { CardStatusResp, MonthlyCardView } from '../assets/scripts/net/generated/PayProtocol'
const NOW=1_900_000_000_000
function card(overrides: Partial<MonthlyCardView>={}): MonthlyCardView {
  return {productId:'gold-sku',name:'金币月卡',saleable:true,priceCents:3000,durationDays:30,initialGold:300,
    dailyRewards:[{type:'RESOURCE',id:'GOLD',name:'金币',count:90}],ownedDailyRewards:[],active:false,expireAt:null,claimableDays:0,adFree:false,bonusQueues:0,earlyCollectAll:false,nextRefreshAt:NOW+86400000,
    offerAdFree:false,offerBonusQueues:0,offerEarlyCollectAll:false,...overrides}
}
function resp(cards:MonthlyCardView[]):CardStatusResp {return {active:false,expireAt:null,claimedToday:false,claimableDays:0,adFree:false,bonusQueues:0,serverNow:NOW,cards}}
test('server prices and named daily rewards compose two different offers',()=>{
  const material=card({productId:'mat-sku',name:'材料月卡',priceCents:1800,initialGold:0,offerAdFree:true,offerEarlyCollectAll:true,dailyRewards:[{type:'ITEM',id:'private-speed-id',name:'1小时建造令',count:1},{type:'ITEM',id:'private-wood-id',name:'1万木材包',count:1}]})
  const model=buildMonthlyCardPanel(resp([material,card()]))
  assert.equal(model.rows.length,2);assert.equal(model.rows[0]!.priceText,'18元 / 30天');assert.equal(model.rows[1]!.priceText,'30元 / 30天')
  assert.match(model.rows[0]!.offerText,/1小时建造令 ×1/);assert.match(model.rows[0]!.offerText,/免广告/);assert.match(model.rows[0]!.offerText,/四种手动教学/)
  assert.match(model.rows[1]!.offerText,/购买赠300金币/);assert.match(model.rows[1]!.offerText,/金币 ×90/)
  assert.doesNotMatch(JSON.stringify(model.rows.map(r=>[r.name,r.offerText,r.statusText])),/private-speed-id|private-wood-id|mat-sku|gold-sku/)
})
test('price duration and amounts never use hardcoded monthly estimates',()=>{
  const row=buildMonthlyCardPanel(resp([card({priceCents:1987,durationDays:17,initialGold:321,dailyRewards:[{type:'RESOURCE',id:'GOLD',name:'金币',count:93}]})])).rows[0]!
  assert.equal(row.priceText,'19.87元 / 17天');assert.match(row.offerText,/321/);assert.match(row.offerText,/93/)
})
test('server active and claimable flags control claim even when local clock differs',()=>{
  const prior=Date.now;try {Date.now=()=>NOW+900000000000;const row=buildMonthlyCardPanel(resp([card({active:true,claimableDays:2,expireAt:NOW+1})])).rows[0]!;assert.equal(row.claimable,true);assert.deepEqual(monthlyClaimBody(row),{productId:'gold-sku'})}finally{Date.now=prior}
})
test('new purchase offer and active owned snapshot are displayed separately',()=>{
  const row=buildMonthlyCardPanel(resp([card({active:true,claimableDays:1,ownedDailyRewards:[{type:'RESOURCE',id:'GOLD',name:'金币',count:7}]})])).rows[0]!
  assert.match(row.offerText,/90/);assert.match(row.statusText,/已购日包/);assert.match(row.statusText,/7/)
})
test('inactive and settled cards retain concrete disabled claim reason',()=>{
  const rows=buildMonthlyCardPanel(resp([card(),card({active:true})])).rows
  assert.equal(monthlyClaimBody(rows[0]!),null);assert.equal(monthlyClaimBody(rows[1]!),null);assert.match(rows[0]!.statusText,/尚未生效/);assert.match(rows[1]!.statusText,/当前日包已结清/)
})
test('retired owned legacy is claimable but cannot start another purchase',()=>{
  const row=buildMonthlyCardPanel(resp([card({productId:'legacy-sku',name:'已购综合月卡',saleable:false,active:true,claimableDays:1,bonusQueues:1})])).rows[0]!
  assert.equal(monthlyPurchaseId(row),null);assert.deepEqual(monthlyClaimBody(row),{productId:'legacy-sku'});assert.match(row.statusText,/已购权益保留/)
})
test('actual older JSON missing cards can claim only legacy without invented price or sku',()=>{
  const older=JSON.parse(JSON.stringify({...resp([]),active:true,claimableDays:1,cards:undefined})) as CardStatusResp
  const row=buildMonthlyCardPanel(older).rows[0]!;assert.equal(row.productId,null);assert.equal(row.priceText,null);assert.equal(monthlyPurchaseId(row),null);assert.deepEqual(monthlyClaimBody(row),{productId:null})
})
test('missing older list without ownership does not invent an offer',()=>{
  const old={...resp([]),cards:undefined} as unknown as CardStatusResp;const model=buildMonthlyCardPanel(old);assert.equal(model.rows.length,0);assert.match(model.noticeText??'',/暂未提供/)
})
test('unknown reward names use player friendly fallback without internal identifiers',()=>{
  const row=buildMonthlyCardPanel(resp([card({dailyRewards:[{type:'ITEM',id:'internal-id',name:'',count:1}]})])).rows[0]!
  assert.match(row.offerText,/奖励 ×1/);assert.doesNotMatch(row.offerText,/internal-id/)
})
test('purchase requires authoritative sale flag and valid server price',()=>{
  const rows=buildMonthlyCardPanel(resp([card(),card({saleable:false}),card({priceCents:0})])).rows
  assert.equal(monthlyPurchaseId(rows[0]!),'gold-sku');assert.equal(monthlyPurchaseId(rows[1]!),null);assert.equal(monthlyPurchaseId(rows[2]!),null)
})
test('claimed receipt text uses actual amounts and existing server reward names',()=>{
  const row=buildMonthlyCardPanel(resp([card()])).rows[0]!
  assert.match(monthlyClaimResultText({claimedDays:1,rewards:[{type:'RESOURCE',id:'GOLD',count:7}],expireAt:null,serverNow:NOW,productId:'gold-sku'},row),/金币 ×7/)
  assert.doesNotMatch(monthlyClaimResultText({claimedDays:1,rewards:[{type:'ITEM',id:'secret-id',count:2}],expireAt:null,serverNow:NOW,productId:'gold-sku'},row),/secret-id/)
})
test('fully overflowing accepted receipt remains settlement text without invented credited amount',()=>{
  const row=buildMonthlyCardPanel(resp([card()])).rows[0]!
  const text=monthlyClaimResultText({claimedDays:2,rewards:[],expireAt:null,serverNow:NOW,productId:'gold-sku'},row);assert.match(text,/已结算2天日包/);assert.doesNotMatch(text,/90|180|到账/)
})
