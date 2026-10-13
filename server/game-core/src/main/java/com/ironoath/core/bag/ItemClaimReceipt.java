package com.ironoath.core.bag;
import java.util.*;
/** 免费或支付材料的持久回执，与同批物品数量在同一背包聚合一次保存。 */
public record ItemClaimReceipt(String businessKey,String source,String sourceRef,Map<String,Long> requested,
        Map<String,Long> credited,long acceptedAt,Set<String> facts,String overflowMailId) {
    public ItemClaimReceipt {
        if(businessKey==null||businessKey.isBlank()||!("paid_item".equals(source)||"paid_items".equals(source)||"free_item".equals(source))||sourceRef==null||sourceRef.isBlank()||acceptedAt<=0||requested==null||requested.isEmpty()||credited==null)
            throw new IllegalArgumentException("材料回执需要业务键、来源、数量和接受时间");
        requested=Collections.unmodifiableMap(new LinkedHashMap<>(requested));credited=Collections.unmodifiableMap(new LinkedHashMap<>(credited));
        for(var e:requested.entrySet()) if(e.getKey()==null||e.getKey().isBlank()||e.getValue()==null||e.getValue()<=0)throw new IllegalArgumentException("材料回执数量无效");
        for(var e:credited.entrySet()) if(e.getValue()==null||e.getValue()<0||!requested.containsKey(e.getKey())||e.getValue()>requested.get(e.getKey()))throw new IllegalArgumentException("材料入账超出请求");
        facts=facts==null?Set.of():Set.copyOf(facts);
        if("paid_items".equals(source)&&!facts.isEmpty())throw new IllegalArgumentException("旧材料回执不能推造领取事实");
        if(overflowMailId!=null&&overflowMailId.isBlank())throw new IllegalArgumentException("材料溢出邮件号不能为空");
    }
    public Map<String,Long> overflow(){var m=new LinkedHashMap<String,Long>();requested.forEach((id,n)->{long rest=n-credited.getOrDefault(id,0L);if(rest>0)m.put(id,rest);});return Collections.unmodifiableMap(m);}
    public boolean pendingOverflow(){return !overflow().isEmpty()&&overflowMailId==null;}
    public ItemClaimReceipt withOverflowMail(String id){if(id==null||id.isBlank())throw new IllegalArgumentException("mailId不能为空");if(overflowMailId!=null&&!overflowMailId.equals(id))throw new IllegalStateException("材料回执邮件不可替换");return new ItemClaimReceipt(businessKey,source,sourceRef,requested,credited,acceptedAt,facts,id);}
}
