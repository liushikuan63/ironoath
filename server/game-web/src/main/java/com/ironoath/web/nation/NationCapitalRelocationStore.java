package com.ironoath.web.nation;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.Nation;
import com.ironoath.web.world.TargetOccupationStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
/** Durable, resumable progress record for one capital relocation saga. */
public interface NationCapitalRelocationStore {
 enum Stage { PREPARED, GARRISON_MOVED, OCCUPATION_MOVED, NATION_MOVED, ANCHOR_MOVED, COMPLETED }
 record RequestReceipt(String requestId,int x,int y) {
  public RequestReceipt { if(requestId==null||requestId.isBlank())throw new IllegalArgumentException("request id is required"); }
 }
 /** Immutable operation snapshot captured before the first cross-store write. */
 record Snapshot(String nationId,String operationId,String requestId,long version,Stage stage,long nationVersion,
     int oldX,int oldY,int newX,int newY,TargetOccupationStore.Snapshot beforeOccupation,long occupationVersion,
     TargetOccupationStore.Snapshot afterOccupation,Nation.Snapshot beforeNation,Nation.Snapshot afterNation,
     Map<String,March.Snapshot> beforeGarrisons,Map<String,March.Snapshot> afterGarrisons,Map<String,Long> beforeGarrisonVersions,long completedAt,
      List<RequestReceipt> requestHistory) {
  public Snapshot {
   if(nationId==null||nationId.isBlank()||operationId==null||operationId.isBlank()||requestId==null||requestId.isBlank()
      ||version<1||stage==null||nationVersion<0||occupationVersion< -1||completedAt< -1
      ||(oldX==newX&&oldY==newY&&stage!=Stage.COMPLETED))
    throw new IllegalArgumentException("invalid capital relocation snapshot");
   beforeGarrisons=beforeGarrisons==null?Map.of():Map.copyOf(beforeGarrisons);
   afterGarrisons=afterGarrisons==null?Map.of():Map.copyOf(afterGarrisons);
   beforeGarrisonVersions=beforeGarrisonVersions==null?Map.of():Map.copyOf(beforeGarrisonVersions);
   requestHistory=requestHistory==null||requestHistory.isEmpty()?List.of(new RequestReceipt(requestId,newX,newY)):List.copyOf(requestHistory);
   if(requestHistory.stream().map(RequestReceipt::requestId).distinct().count()!=requestHistory.size())
    throw new IllegalArgumentException("duplicate relocation request receipt");
  }
   public Snapshot(String nationId,String operationId,String requestId,long version,Stage stage,long nationVersion,
       int oldX,int oldY,int newX,int newY,TargetOccupationStore.Snapshot beforeOccupation,long occupationVersion,
       TargetOccupationStore.Snapshot afterOccupation,Nation.Snapshot beforeNation,Nation.Snapshot afterNation,
       Map<String,March.Snapshot> beforeGarrisons,Map<String,March.Snapshot> afterGarrisons,Map<String,Long> beforeGarrisonVersions,long completedAt) {
    this(nationId,operationId,requestId,version,stage,nationVersion,oldX,oldY,newX,newY,beforeOccupation,occupationVersion,
       afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,beforeGarrisonVersions,completedAt,List.of());
   }
  public Snapshot(String nationId,String operationId,String requestId,long version,Stage stage,long nationVersion,
      int oldX,int oldY,int newX,int newY,TargetOccupationStore.Snapshot beforeOccupation,long occupationVersion,
      TargetOccupationStore.Snapshot afterOccupation,Nation.Snapshot beforeNation,Nation.Snapshot afterNation,
      Map<String,March.Snapshot> beforeGarrisons,Map<String,March.Snapshot> afterGarrisons,long completedAt) {
   this(nationId,operationId,requestId,version,stage,nationVersion,oldX,oldY,newX,newY,beforeOccupation,occupationVersion,
       afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,Map.of(),completedAt,List.of());
  }
  public Snapshot advance(Stage next) {
   if(next==null)throw new IllegalArgumentException("next stage is required");
   return new Snapshot(nationId,operationId,requestId,Math.addExact(version,1),next,nationVersion,oldX,oldY,newX,newY,
       beforeOccupation,occupationVersion,afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,beforeGarrisonVersions,completedAt,requestHistory);
  }
  public Snapshot withVersion(long nextVersion) {
   return new Snapshot(nationId,operationId,requestId,nextVersion,stage,nationVersion,oldX,oldY,newX,newY,
       beforeOccupation,occupationVersion,afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,beforeGarrisonVersions,completedAt,requestHistory);
  }
  public Snapshot withVersionAndRequestHistory(long nextVersion,List<RequestReceipt> history) {
   return new Snapshot(nationId,operationId,requestId,nextVersion,stage,nationVersion,oldX,oldY,newX,newY,
       beforeOccupation,occupationVersion,afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,beforeGarrisonVersions,completedAt,history);
  }
  public Snapshot withRequestHistory(List<RequestReceipt> history) {
   return new Snapshot(nationId,operationId,requestId,Math.addExact(version,1),stage,nationVersion,oldX,oldY,newX,newY,
       beforeOccupation,occupationVersion,afterOccupation,beforeNation,afterNation,beforeGarrisons,afterGarrisons,beforeGarrisonVersions,completedAt,history);
  }
 }
 Optional<Snapshot> findByNationId(String nationId);
 List<Snapshot> findPending(int limit);
 boolean compareAndSet(Snapshot next,long expectedVersion);
 static boolean allowed(Snapshot current,Snapshot next,long expectedVersion) {
  if(next==null)throw new IllegalArgumentException("next is required");
  if(current==null)return expectedVersion==0&&next.version()==1
      &&((next.stage()==Stage.PREPARED&&next.requestHistory().equals(List.of(new RequestReceipt(next.requestId(),next.newX(),next.newY()))))
      ||(next.stage()==Stage.COMPLETED&&next.oldX()==next.newX()&&next.oldY()==next.newY()
      &&next.operationId().startsWith("capital-noop:"+next.nationId()+":")
      &&next.requestHistory().equals(List.of(new RequestReceipt(next.requestId(),next.newX(),next.newY())))));
  if(current.version()!=expectedVersion||next.version()!=expectedVersion+1||!current.nationId().equals(next.nationId()))return false;
  if(current.stage()==Stage.COMPLETED&&next.stage()==Stage.COMPLETED) {
   var latest=next.requestHistory().isEmpty()?null:next.requestHistory().get(next.requestHistory().size()-1);
   return latest!=null&&latest.x()==current.newX()&&latest.y()==current.newY()
       &&next.requestHistory().size()==current.requestHistory().size()+1
       &&next.requestHistory().subList(0,current.requestHistory().size()).equals(current.requestHistory())
       &&current.withVersionAndRequestHistory(next.version(),next.requestHistory()).equals(next);
  }
  if(current.stage()==Stage.COMPLETED)return next.stage()==Stage.PREPARED&&!current.requestId().equals(next.requestId())
      &&next.operationId().startsWith("capital-move:"+next.nationId()+":")
      &&next.requestHistory().equals(append(current.requestHistory(),new RequestReceipt(next.requestId(),next.newX(),next.newY())));
  return next.stage().ordinal()==current.stage().ordinal()+1&&current.operationId().equals(next.operationId())
      &&current.requestId().equals(next.requestId())&&current.nationVersion()==next.nationVersion()
      &&current.oldX()==next.oldX()&&current.oldY()==next.oldY()&&current.newX()==next.newX()&&current.newY()==next.newY()
      &&java.util.Objects.equals(current.beforeOccupation(),next.beforeOccupation())&&current.occupationVersion()==next.occupationVersion()
      &&java.util.Objects.equals(current.afterOccupation(),next.afterOccupation())
      &&java.util.Objects.equals(current.beforeNation(),next.beforeNation())&&java.util.Objects.equals(current.afterNation(),next.afterNation())
      &&current.beforeGarrisons().equals(next.beforeGarrisons())&&current.afterGarrisons().equals(next.afterGarrisons())
       &&current.beforeGarrisonVersions().equals(next.beforeGarrisonVersions())&&current.requestHistory().equals(next.requestHistory())
      &&current.completedAt()==next.completedAt();
 }
  private static List<RequestReceipt> append(List<RequestReceipt> history,RequestReceipt receipt) {
   var result=new java.util.ArrayList<>(history);result.add(receipt);return List.copyOf(result);
  }
}
