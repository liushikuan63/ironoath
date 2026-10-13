package com.ironoath.web.pay;
import java.util.*;
import com.ironoath.common.*;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.pay.MonthlyCardPurchase;
import com.ironoath.core.player.*;
import com.ironoath.core.reward.*;
import com.ironoath.web.dto.generated.CardClaimResp;
import com.ironoath.web.dto.generated.MonthlyCardView;
import com.ironoath.web.dto.generated.MonthlyCardRewardView;

/** 两新SKU独立每日领取；调用者必须持有该玩家锁。旧综合卡留在原路径。 */
public final class MonthlyCardService {
    private final PlayerRepository players;private final InventoryRepository inventories;
    private final RewardService rewards;private final PaidProducts catalog;private final ConfigRegistry configs;private final com.ironoath.web.reward.RewardNames names;
    public MonthlyCardService(PlayerRepository players,InventoryRepository inventories,RewardService rewards,PaidProducts catalog,ConfigRegistry configs,com.ironoath.web.reward.RewardNames names){this.players=players;this.inventories=inventories;this.rewards=rewards;this.catalog=catalog;this.configs=configs;this.names=names;}
    private List<MonthlyCardRewardView> namedRewards(List<RewardItem> items){return items.stream().map(item->new MonthlyCardRewardView(item.type().name(),item.id(),names.nameOf(item),item.count())).toList();}
    private PlayerSave require(String uid){return players.findByPlayerId(uid).orElseThrow();}
    private boolean newKind(PayProductCfg p){return p.kind()==PayProductCfg.Kind.MONTHLY_MATERIAL_CARD||p.kind()==PayProductCfg.Kind.MONTHLY_GOLD_CARD;}
    private String key(String order,long day){return "monthly:daily:"+order+":"+day;}
    private long acceptedDay(String order,long total,String businessKey,String ref,Set<String> facts){
        if(!order.equals(ref))return 0;
        for(String fact:facts)if(fact.startsWith("monthly-day:")){
            try{long day=Long.parseLong(fact.substring("monthly-day:".length()));if(day>0&&day<=total&&key(order,day).equals(businessKey))return day;}catch(NumberFormatException ignored){}
        }
        return 0;
    }
    public PlayerSave reconcile(String uid) {
        rewards.recoverItemClaims(uid);
        var save=require(uid);var itemReceipts=inventories.findByPlayerId(uid).map(i->i.itemClaims().values()).orElse(List.of());
        boolean changed=false;var paid=save.paid();
        for(var p:paid.monthlyPurchases().values()){
            long accepted=p.settledDays();
            for(var receipt:save.goldClaims().values())if("paid_gold".equals(receipt.source())){
                long day=acceptedDay(p.orderId(),p.totalDays(),receipt.businessKey(),receipt.sourceRef(),receipt.facts());
                if(day>0&&receipt.pendingOverflow())rewards.grant(uid,List.of(new RewardItem(RewardType.RESOURCE,"GOLD",receipt.requested())),new RewardContext(receipt.source(),receipt.sourceRef(),receipt.businessKey(),true,receipt.facts()));
                accepted=Math.max(accepted,day);
            }
            for(var receipt:itemReceipts)accepted=Math.max(accepted,acceptedDay(p.orderId(),p.totalDays(),receipt.businessKey(),receipt.sourceRef(),receipt.facts()));
            if(accepted>p.settledDays()){paid=paid.withMonthlyPurchase(p.withSettledDays(accepted));changed=true;}
        }
        if(changed){save=require(uid);save.setPaid(paid);players.save(save);}
        return require(uid);
    }
    public List<MonthlyCardView> views(String uid,long now) {
        PlayerPaid paid=reconcile(uid).paid();var result=new ArrayList<MonthlyCardView>();
        for(var row:configs.all(PayProductCfg.class)){
            if(!newKind(row))continue;var offer=catalog.cardTerms(row);
            var purchases=paid.monthlyPurchases().values().stream().filter(p->p.productId().equals(row.id())).toList();
            var active=purchases.stream().filter(p->p.active(now)).findFirst().orElse(null);
            Long expiry=purchases.stream().map(MonthlyCardPurchase::expiresAt).max(Long::compareTo).orElse(null);
            long days=purchases.stream().mapToLong(p->p.claimableDays(now)).sum();
            result.add(new MonthlyCardView(row.id(),row.name(),catalog.saleable(row),catalog.priceCents(row),offer.durationDays(),offer.initialGold(),namedRewards(offer.dailyRewards()),
                active==null?List.of():namedRewards(active.terms().dailyRewards()),active!=null,expiry,days,active!=null&&active.terms().adFree(),active==null?0:active.terms().extraQueues(),active!=null&&active.terms().earlyCollectAll(),DayKey.startOfBusinessDayPlusDays(now,1),offer.adFree(),offer.extraQueues(),offer.earlyCollectAll()));
        }
        if(paid.cardExpireAt()!=null){var old=catalog.requireKind(PayProductCfg.Kind.MONTHLY_CARD);var plan=catalog.toDomainRewards(catalog.alwaysRows(old.id()),1);
            result.add(new MonthlyCardView(old.id(),old.name()+"（已购权益保留）",false,catalog.priceCents(old),old.durationDays(),0,namedRewards(plan),paid.cardActive(now)?namedRewards(plan):List.of(),
                paid.cardActive(now),paid.cardExpireAt(),com.ironoath.core.pay.PaidEntitlements.claimableDays(paid,now),paid.cardActive(now)&&old.adFree(),paid.cardActive(now)?old.extraQueues():0,false,DayKey.startOfDayPlusDays(now,1),old.adFree(),old.extraQueues(),false));
        }
        return List.copyOf(result);
    }
    public CardClaimResp claim(String uid,String sku,long now) {
        if(sku==null||sku.isBlank()||!catalog.known(sku)||!newKind(catalog.require(sku)))throw new BizException(ErrorCode.PAY_NOT_ENTITLED,"请选择可领取的月卡。");
        var save=reconcile(uid);var purchases=save.paid().monthlyPurchases().values().stream().filter(p->p.productId().equals(sku)&&p.active(now)).toList();
        if(purchases.isEmpty())throw new BizException(ErrorCode.PAY_CARD_INACTIVE,"这张月卡当前未生效或已经到期。");
        long count=purchases.stream().mapToLong(p->p.claimableDays(now)).sum();if(count==0)throw new BizException(ErrorCode.PAY_ALREADY_CLAIMED,"本业务日的月卡奖励已领取，请在北京时间04:00刷新后再来。");
        var granted=new LinkedHashMap<String,RewardItem>();long actualDays=0;
        for(var initial:purchases){long through=initial.settleThroughDays(now),days=initial.claimableDays(now);
            if(days==0)continue;
            // 本次补领整批原子接受，收据记末日；跨04:00或到期恢复不能再裁剪未发完的半批。
            var batch=initial.terms().dailyRewards().stream().map(item->item.withCount(Math.multiplyExact(item.count(),days))).toList();
            String source=batch.stream().allMatch(x->x.type()==RewardType.ITEM)?"paid_item":"paid_gold";
            var ctx=new RewardContext(source,initial.orderId(),key(initial.orderId(),through),true,Set.of("monthly-day:"+through));
            var result=rewards.grant(uid,batch,ctx);
            if(result.hasCompensation())throw new IllegalStateException("月卡日包等待补发");
            for(var got:result.granted()){String k=got.type().name()+":"+got.id();granted.merge(k,got,(a,b)->a.withCount(Math.addExact(a.count(),b.count())));}
            var current=require(uid);var period=current.paid().monthlyPurchases().get(initial.orderId());current.setPaid(current.paid().withMonthlyPurchase(period.withSettledDays(through)));players.save(current);actualDays=Math.addExact(actualDays,days);
        }
        Long expiry=require(uid).paid().monthlyPurchases().values().stream().filter(p->p.productId().equals(sku)).map(MonthlyCardPurchase::expiresAt).max(Long::compareTo).orElse(null);
        return new CardClaimResp(actualDays,PaidProducts.toProtocol(List.copyOf(granted.values())),expiry,now,sku);
    }
}
