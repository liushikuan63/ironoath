/** 预览只改写产物地址；真实HTTP仍直连指定后端。 */
export function matchesRankBoardResponse(url,method,expectedType,backend) {
  try {
    const request=new URL(url),endpoint=new URL(backend.replace(/\/$/,'')+'/rank/list');
    return method==='GET' && request.origin===endpoint.origin && request.pathname===endpoint.pathname
      && request.searchParams.get('type')===expectedType;
  } catch { return false; }
}

/** 面板自身四角形成的边界，不能使用会把越界子节点也包含进去的联合包围盒。 */
export function assertRowHorizontalBounds(panel) {
  const bounds=panel?.bounds;
  if(!bounds || !Number.isFinite(bounds.left) || !Number.isFinite(bounds.right) || bounds.right<=bounds.left
      || !Array.isArray(panel.rows))throw Error('面板自身横向边界缺失');
  let cellCount=0;
  for(const row of panel.rows) {
    if(!Array.isArray(row.cells) || row.cells.length===0)throw Error('可见行单元格边界缺失');
    let previousRight=-Infinity;
    for(const cell of row.cells) {
      if(!Number.isFinite(cell.left) || !Number.isFinite(cell.right) || cell.right<=cell.left)
        throw Error('单元格边界无效');
      if(cell.left<bounds.left-0.5 || cell.right>bounds.right+0.5)throw Error('可见行文字横向越出面板');
      if(cell.left<previousRight-0.5)throw Error('可见行名称和榜值列重叠');
      previousRight=cell.right;cellCount++;
    }
  }
  return {rowCount:panel.rows.length,cellCount};
}

/** 当前点击的真实HTTP响应与可见榜行逐行核对，不从“新号”推断全服空榜。 */
export function assertRankBoardEvidence(reply,expectedType,panel) {
  if(!reply || reply.status!==200 || reply.body?.code!==0)throw Error('榜单HTTP或业务响应未成功');
  const data=reply.body.data;
  if(!['POWER','KILL','WAR'].includes(expectedType) || data?.type!==expectedType)throw Error('榜类型与本次点击不一致');
  if(data.page!==1 || !Number.isSafeInteger(data.pageSize) || data.pageSize<1)throw Error('榜单页码或容量无效');
  if(!Array.isArray(data.entries) || data.entries.length>data.pageSize)throw Error('榜行缺失或超过响应容量');
  if(!panel || !Array.isArray(panel.labels) || !Array.isArray(panel.rows))throw Error('可见面板证据缺失');
  const compact=s=>String(s).replace(/\s+/g,'');
  const rows=panel.rows.filter(r=>Array.isArray(r.labels) && r.labels.some(l=>/^第\s*\d+\s*名\s+/.test(l)));
  const empty=panel.labels.some(l=>String(l).includes('这个榜还没有人'));
  if(data.entries.length===0) {
    if(!empty || rows.length!==0)throw Error('权威空榜必须显示空榜说明且不能保留旧榜行');
    return {kind:'empty',rowCount:0};
  }
  if(empty)throw Error('权威非空榜不能同时显示空榜说明');
  if(rows.length!==data.entries.length)throw Error('可见榜行数与本次响应不符');
  const ids=new Set();let previous=0,previousY=Infinity;
  for(const [index,entry] of data.entries.entries()) {
    if(!entry || typeof entry.id!=='string' || !entry.id.trim() || ids.has(entry.id)
        || typeof entry.name!=='string' || !entry.name.trim()
        || !Number.isSafeInteger(entry.rank) || entry.rank<=previous
        || !Number.isSafeInteger(entry.value) || entry.value<0)throw Error('榜行身份、名次、名称或数值无效');
    ids.add(entry.id);previous=entry.rank;
    const label=compact(`第 ${entry.rank} 名 ${entry.name}`);
    const row=rows[index];
    if(!Number.isFinite(row.y) || row.y>=previousY)throw Error('可见榜行纵向顺序无效');
    previousY=row.y;
    if(!row.labels.some(l=>compact(l)===label))throw Error(`第${entry.rank}行可见顺序、名次或名称不符`);
    const value=String(entry.value).replace(/\B(?=(\d{3})+(?!\d))/g,',');
    if(!row.labels.includes(value))throw Error(`第${entry.rank}行榜值不符`);
  }
  return {kind:'populated',rowCount:rows.length};
}
