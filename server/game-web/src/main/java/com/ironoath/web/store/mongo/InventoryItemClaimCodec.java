package com.ironoath.web.store.mongo;

import com.ironoath.core.bag.ItemClaimReceipt;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import java.util.*;

/** Inventory-local codec: preserve the two historical item receipt wire families and identities. */
final class InventoryItemClaimCodec {
    private static final String CURRENT=ItemClaimReceipt.class.getName();
    private static final String LEGACY=com.ironoath.core.reward.ItemClaimReceipt.class.getName();
    private static final Set<String> FIELDS=Set.of("businessKey","source","sourceRef","requested","credited","acceptedAt","facts","overflowMailId","_class");
    private InventoryItemClaimCodec() {}

    static Map<String,ItemClaimReceipt> read(Object stored) {
        if(stored==null)return Map.of();
        if(!(stored instanceof Map<?,?> rows))throw invalid("itemClaims must be a document");
        var result=new LinkedHashMap<String,ItemClaimReceipt>();
        rows.forEach((key,value)->{
            String businessKey=text(key,"receipt key");
            if(!(value instanceof Map<?,?> row))throw invalid("receipt must be a document");
            for(Object field:row.keySet())if(!FIELDS.contains(field))throw invalid("unknown receipt field");
            String source=text(row.get("source"),"source");
            boolean legacy="paid_items".equals(source);
            if(!legacy&&!"paid_item".equals(source)&&!"free_item".equals(source))throw invalid("unknown receipt source");
            Object hint=row.get("_class");
            if(hint!=null&&!(legacy?LEGACY:CURRENT).equals(hint))throw invalid("receipt type conflicts with source");
            if(!businessKey.equals(text(row.get("businessKey"),"businessKey")))throw invalid("receipt key mismatch");
            Map<String,Long> requested=amounts(row.get("requested"),legacy,false);
            Map<String,Long> credited=amounts(row.get("credited"),legacy,true);
            Set<String> facts=facts(row.get("facts"));
            Object mail=row.get("overflowMailId");
            result.put(businessKey,new ItemClaimReceipt(businessKey,source,text(row.get("sourceRef"),"sourceRef"),
                    requested,credited,whole(row.get("acceptedAt"),"acceptedAt"),facts,
                    mail==null?null:text(mail,"overflowMailId")));
        });
        return Collections.unmodifiableMap(result);
    }

    static Document write(Map<String,ItemClaimReceipt> receipts,MongoConverter converter) {
        var result=new Document();
        receipts.forEach((key,receipt)->{
            if(!key.equals(receipt.businessKey()))throw invalid("receipt key mismatch");
            Object wire=receipt;
            if("paid_items".equals(receipt.source()))wire=new com.ironoath.core.reward.ItemClaimReceipt(
                    receipt.businessKey(),receipt.source(),receipt.sourceRef(),items(receipt.requested()),
                    items(receipt.credited()),receipt.acceptedAt(),receipt.overflowMailId());
            var row=new Document();converter.write(wire,row);result.put(key,row);
        });
        return result;
    }

    private static List<RewardItem> items(Map<String,Long> amounts) {
        return amounts.entrySet().stream().filter(e->e.getValue()>0)
                .map(e->new RewardItem(RewardType.ITEM,e.getKey(),e.getValue())).toList();
    }

    private static Map<String,Long> amounts(Object raw,boolean legacy,boolean credited) {
        var result=new LinkedHashMap<String,Long>();
        if(legacy) {
            if(!(raw instanceof List<?> list))throw invalid("legacy item amounts must be an array");
            for(Object element:list) {
                if(!(element instanceof Map<?,?> item)||!"ITEM".equals(item.get("type")))throw invalid("legacy receipt must contain only items");
                Object id=item.get("id"),storedId=item.get("_id");
                if(id!=null&&storedId!=null&&!id.equals(storedId))throw invalid("item id aliases conflict");
                String key=text(storedId!=null?storedId:id,"item id");
                long amount=whole(item.get("count"),"item count");
                if(amount<=0||result.putIfAbsent(key,amount)!=null)throw invalid("invalid or duplicate legacy item");
            }
        } else {
            if(!(raw instanceof Map<?,?> map))throw invalid("current item amounts must be a document");
            map.forEach((id,value)->{
                long amount=whole(value,"item count");
                if(amount<0||(!credited&&amount==0))throw invalid("invalid item count");
                result.put(text(id,"item id"),amount);
            });
        }
        if(!credited&&result.isEmpty())throw invalid("empty requested items");
        return result;
    }

    private static Set<String> facts(Object raw) {
        if(raw==null)return Set.of();
        if(!(raw instanceof Collection<?> collection))throw invalid("facts must be an array");
        var result=new LinkedHashSet<String>();
        for(Object value:collection)if(!result.add(text(value,"fact")))throw invalid("duplicate fact");
        return result;
    }
    static long whole(Object value,String field) {
        if(!(value instanceof Long)&&!(value instanceof Integer))throw invalid(field+" must be a BSON integer");
        return ((Number)value).longValue();
    }
    private static String text(Object value,String field) {
        if(!(value instanceof String text)||text.isBlank())throw invalid(field+" must be nonempty text");
        return text;
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid inventory item receipt: "+reason);
    }
}
