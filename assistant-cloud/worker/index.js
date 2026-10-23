const BASE='https://manga.demos25.me';
const VERSION='1.4.0';
const TOOLS=[{name:'library_stats',description:'Read aggregate progress and sync status from your own synchronized manga library.',inputSchema:{type:'object',properties:{},additionalProperties:false},annotations:{readOnlyHint:true,destructiveHint:false,openWorldHint:false}},{name:'search_library',description:'Search your own permitted library metadata; no chapters, images, settings or raw backups.',inputSchema:{type:'object',properties:{query:{type:'string',maxLength:200,default:''},unread_only:{type:'boolean',default:false},limit:{type:'integer',minimum:1,maximum:100,default:25}},additionalProperties:false},annotations:{readOnlyHint:true,destructiveHint:false,openWorldHint:false}}];
const safeTool=(name,description,properties,required=[],readOnly=true)=>({name,description,inputSchema:{type:'object',properties,required,additionalProperties:false},annotations:{readOnlyHint:readOnly,destructiveHint:!readOnly,openWorldHint:false}});
const HEX24=/^[0-9a-f]{24}$/;const HEX32=/^[0-9a-f]{32}$/;const DEVICE=/^[0-9a-f]{16}$/;
const ACTIONS=new Set(['category_changes','mark_read','library_set','library_add','refresh_chapters','set_setting','download_chapter','open_reader','open_screen','install_extension','uninstall_extension']);
TOOLS.push(
 safeTool('list_paired_devices','List your own paired phone IDs; credentials are never exposed.',{}),
 safeTool('list_manga_chapters','Get chapter IDs and read flags for a library title.',{manga_id:{type:'string',pattern:'^[0-9a-f]{24}$'},offset:{type:'integer',minimum:0,maximum:100000,default:0},limit:{type:'integer',minimum:1,maximum:100,default:100}},['manga_id']),
 safeTool('list_device_extensions','List non-restricted installed and available Android extensions from the most recent device report.',{device_id:{type:'string',pattern:'^[0-9a-f]{16}$'}},['device_id']),
 safeTool('install_extension','Request installation of a known, non-restricted manga extension on your paired phone. The phone owner must approve this request and Android must approve the installation.',{device_id:{type:'string',pattern:'^[0-9a-f]{16}$'},package_name:{type:'string',maxLength:180},confirmed:{type:'boolean',description:'True only after user explicitly requested installation of this exact extension.'}},['device_id','package_name','confirmed'],false),
 safeTool('uninstall_extension','Request uninstallation of a known, non-restricted manga extension. Phone and Android system approval are required.',{device_id:{type:'string',pattern:'^[0-9a-f]{16}$'},package_name:{type:'string',maxLength:180},confirmed:{type:'boolean',description:'True only after user explicitly requested removal of this exact extension.'}},['device_id','package_name','confirmed'],false),
 safeTool('request_manga_action','Queue one explicitly user-confirmed action for Android phone approval. Returns pending status, never execution success.',{device_id:{type:'string',pattern:'^[0-9a-f]{16}$'},action:{type:'string',enum:Array.from(ACTIONS)},args:{type:'object'},confirmed:{type:'boolean',description:'Must be true only after the owner explicitly authorizes this specific action.'}},['device_id','action','args','confirmed'],false),
 safeTool('get_manga_action_status','Read a requested action status (pending, rejected, failed, completed or expired).',{command_id:{type:'string',pattern:'^[0-9a-f]{32}$'}},['command_id']),
 safeTool('request_device_sync','Request a phone-side library and extension inventory sync. Requires one-time unattended-sync opt-in in Android settings; no per-sync phone approval.',{device_id:{type:'string',pattern:'^[0-9a-f]{16}$'}},['device_id'],false),
 safeTool('get_device_sync_status','Check pending, running, completed, failed, expired, or cancelled phone-side sync status.',{request_id:{type:'string',pattern:'^[0-9a-f]{32}$'}},['request_id'])
);
const categoryDevice={device_id:{type:'string',pattern:'^[0-9a-f]{16}$'}};
const paging={offset:{type:'integer',minimum:0,maximum:1000000,default:0},limit:{type:'integer',minimum:1,maximum:100,default:100}};
const categoryId={type:'string',pattern:'^(0|[1-9][0-9]{0,17})$'};
const revisionField={type:'string',pattern:'^[0-9a-f]{64}$'};
const previewField={type:'string',pattern:'^[0-9a-f]{32}$'};
const changeSchema={type:'object',properties:{manga_id:{type:'string',pattern:'^[0-9a-f]{24}$'},category_ids:{type:'array',items:{type:'string',pattern:'^[1-9][0-9]{0,17}$'},uniqueItems:true,maxItems:500},mode:{type:'string',enum:['add','remove','replace']}},required:['manga_id','category_ids','mode'],additionalProperties:false};
const CATEGORY_READS=new Set(['list_categories','get_category','list_category_manga','get_manga_categories','list_library','list_uncategorized_manga','get_category_change_history']);
const CATEGORY_WRITES=new Set(['preview_category_changes','reorder_categories','apply_category_changes','set_manga_categories','bulk_set_manga_categories','undo_category_changes']);
TOOLS.push(
 safeTool('list_categories','Read exact Android category IDs, Unicode names, ordering and visibility. Requires device read scope and an uploaded snapshot.',categoryDevice,['device_id']),
 safeTool('get_category','Read an existing category by stable ID; 0 is Android Default.',{...categoryDevice,category_id:categoryId},['device_id','category_id']),
 safeTool('list_category_manga','Paginate titles and memberships within an existing category.',{...categoryDevice,...paging,category_id:categoryId},['device_id','category_id']),
 safeTool('get_manga_categories','Read current memberships for a stable manga ID.',{...categoryDevice,manga_id:{type:'string',pattern:'^[0-9a-f]{24}$'}},['device_id','manga_id']),
 safeTool('list_library','Paginate the complete permitted Android library with category memberships. Follow next_offset beyond 100 titles.',{...categoryDevice,...paging,query:{type:'string',maxLength:200}},['device_id']),
 safeTool('list_uncategorized_manga','Paginate Android Default: titles with no explicit category membership.',{...categoryDevice,...paging},['device_id']),
 safeTool('preview_category_changes','Prepare a revision-bound preview listing every affected title and before/after membership. Does not edit Android.',{...categoryDevice,expected_revision:revisionField,changes:{type:'array',items:changeSchema,maxItems:500,minItems:1}},['device_id','expected_revision','changes'],false),
 safeTool('reorder_categories','Preview an exact order of all non-system category IDs; Default is never moved. Apply the returned preview separately.',{...categoryDevice,expected_revision:revisionField,category_order:{type:'array',items:categoryId,uniqueItems:true,maxItems:500}},['device_id','expected_revision','category_order'],false),
 ...['apply_category_changes','set_manga_categories','bulk_set_manga_categories'].map(name=>safeTool(name,'Queue only an already-reviewed category preview after exact owner approval. Android approval and a fresh revision check are still required; never claim a queued request succeeded.',{...categoryDevice,preview_id:previewField,confirmed:{type:'boolean',const:true}},['device_id','preview_id','confirmed'],false)),
 safeTool('get_category_change_history','Read paginated category preview and command history, with actual pending/completed/failed states.',{...categoryDevice,...paging},['device_id']),
 safeTool('undo_category_changes','Preview reversing a completed membership change. Rejects conflicting later edits. Apply this new preview separately with approval.',{...categoryDevice,preview_id:previewField,expected_revision:revisionField},['device_id','preview_id','expected_revision'],false)
);
for(const tool of TOOLS){
 if(['preview_category_changes','reorder_categories','undo_category_changes'].includes(tool.name))tool.annotations.destructiveHint=false;
 if(['apply_category_changes','set_manga_categories','bulk_set_manga_categories'].includes(tool.name))tool.annotations.idempotentHint=true;
}
const HEADERS={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'};
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{...HEADERS,'Content-Type':'application/json; charset=utf-8'}});
const fail=message=>({content:[{type:'text',text:message}],isError:true});
export function argumentsFor(name,args){
 if(!args||typeof args!=='object'||Array.isArray(args))throw Error('Arguments must be an object.');
 const tool=TOOLS.find(t=>t.name===name);if(!tool)throw Error('Unknown library tool.');
 for(const key of Object.keys(args))if(!(key in tool.inputSchema.properties))throw Error('Unsupported argument: '+key);
 if(CATEGORY_READS.has(name)||CATEGORY_WRITES.has(name)){
  if(!DEVICE.test(args.device_id))throw Error('Valid paired device ID required');
  for(const key of tool.inputSchema.required??[])if(args[key]===undefined)throw Error('Missing '+key);
  if(args.expected_revision!==undefined&&!/^[0-9a-f]{64}$/.test(args.expected_revision))throw Error('Invalid category revision');
  if(args.preview_id!==undefined&&!HEX32.test(args.preview_id))throw Error('Invalid preview ID');
  if(args.category_id!==undefined&&!/^(0|[1-9][0-9]{0,17})$/.test(args.category_id))throw Error('Invalid category ID');
  if(args.manga_id!==undefined&&!HEX24.test(args.manga_id))throw Error('Invalid manga ID');
  if(args.confirmed!==undefined&&args.confirmed!==true)throw Error('Explicit approval required');
  if(args.offset!==undefined&&(!Number.isInteger(args.offset)||args.offset<0||args.offset>1000000))throw Error('Invalid offset');
  if(args.limit!==undefined&&(!Number.isInteger(args.limit)||args.limit<1||args.limit>100))throw Error('Invalid limit');
  if(args.query!==undefined&&(typeof args.query!=='string'||args.query.length>200))throw Error('Invalid query');
  if(args.changes!==undefined&&(!Array.isArray(args.changes)||args.changes.length<1||args.changes.length>500))throw Error('Invalid change batch');
  return args;
 }
 if(name==='library_stats'||name==='list_paired_devices')return {};
 if(name==='list_manga_chapters'){
  if(!HEX24.test(args.manga_id)||!Number.isInteger(args.offset??0)||(args.offset??0)<0||(args.offset??0)>100000||!Number.isInteger(args.limit??100)||(args.limit??100)<1||(args.limit??100)>100)throw Error('Invalid chapter query');
  return {manga_id:args.manga_id,offset:args.offset??0,limit:args.limit??100};
 }
 if(name==='request_device_sync'){
  if(!DEVICE.test(args.device_id))throw Error('Invalid paired device ID');
  return {device_id:args.device_id};
 }
 if(name==='get_device_sync_status'){
  if(!HEX32.test(args.request_id))throw Error('Invalid remote sync ID');
  return {request_id:args.request_id};
 }
 if(name==='list_device_extensions'){
  if(!DEVICE.test(args.device_id))throw Error('Invalid paired device ID');return {device_id:args.device_id};
 }
 if(name==='install_extension'||name==='uninstall_extension'){
  const package_name=args.package_name;
  if(!DEVICE.test(args.device_id)||args.confirmed!==true||typeof package_name!=='string'||package_name.length>180
    ||!/^eu[.]kanade[.]tachiyomi[.]extension(?:[.][A-Za-z][A-Za-z0-9_]*){2,}$/.test(package_name)
    ||['nsfw','adult','hentai','porn','erotic','18plus','18+','smut'].some(flag=>package_name.toLowerCase().includes(flag)))
    throw Error('A valid non-restricted extension and explicit user approval are required');
  return {device_id:args.device_id,action:name,args:{package_name}};
 }
 if(name==='request_manga_action'){
  if(args.action==='category_changes')throw Error('Use the dedicated category preview and apply tools.');
  if(!DEVICE.test(args.device_id)||!ACTIONS.has(args.action)||args.confirmed!==true||!args.args||typeof args.args!=='object'||Array.isArray(args.args)||JSON.stringify(args.args).length>1000)throw Error('Explicit authorization and valid action parameters required');
  return {device_id:args.device_id,action:args.action,args:args.args};
 }
 if(name==='get_manga_action_status'){
  if(!HEX32.test(args.command_id))throw Error('Invalid command ID');return {command_id:args.command_id};
 }
 const {query='',unread_only=false,limit=25}=args;
 if(typeof query!=='string'||query.length>200)throw Error('query must be text of at most 200 characters.');
 if(typeof unread_only!=='boolean')throw Error('unread_only must be true or false.');
 if(!Number.isInteger(limit)||limit<1||limit>100)throw Error('limit must be an integer from 1 to 100.');
 return {query,unread_only,limit};
}
export function metadata(name,data){
 const integer=v=>Number.isSafeInteger(v)&&v>=0;
 if(name==='request_device_sync'||name==='get_device_sync_status'){
  const states=['pending','running','completed','failed','expired','cancelled'];
  if(!HEX32.test(data?.request_id)||!DEVICE.test(data?.device_id)||!states.includes(data.state)||!integer(data.expires)||!integer(data.updated))
    throw Error('Invalid remote sync status');
  return {request_id:data.request_id,device_id:data.device_id,state:data.state,updated:data.updated,expires:data.expires};
 }
 if(CATEGORY_READS.has(name)||CATEGORY_WRITES.has(name)){
  if(!data||typeof data!=='object'||Array.isArray(data))throw Error('Invalid category response');
  const allowed=['device_id','revision','updated','categories','items','total','offset','limit','next_offset','preview_id','expected_revision','changes','category_order','state','expires','command_id','action'];
  const safe={};for(const key of allowed)if(data[key]!==undefined)safe[key]=data[key];
  const cid=v=>typeof v==='string'&&/^(0|[1-9][0-9]{0,17})$/.test(v);
  const ids=v=>Array.isArray(v)&&v.length<=500&&v.every(cid)&&new Set(v).size===v.length;
  const text=v=>typeof v==='string'&&v.length<=1000;
  const book=m=>{if(!HEX24.test(m?.id)||!text(m.title)||!ids(m.category_ids))throw Error('Invalid category manga metadata');return {id:m.id,title:m.title,category_ids:m.category_ids};};
  if(data.categories!==undefined){
   if(!Array.isArray(data.categories)||data.categories.length>500)throw Error('Invalid category list');
   safe.categories=data.categories.map(c=>{if(!cid(c?.id)||!text(c.name)||!Number.isSafeInteger(c.order)||typeof c.hidden!=='boolean'||typeof c.flags!=='string')throw Error('Invalid category metadata');return {id:c.id,name:c.name,order:c.order,hidden:c.hidden,flags:c.flags};});
  }
  if(data.items!==undefined){
   if(!Array.isArray(data.items)||data.items.length>100)throw Error('Invalid category page');
   safe.items=name==='get_category_change_history'?data.items.map(h=>({preview_id:h.preview_id,created:h.created,command:h.command?{command_id:h.command.command_id,state:h.command.state,expires:h.command.expires,device_id:h.command.device_id,action:h.command.action}:null})):data.items.map(book);
  }
  if(data.changes!==undefined){
   if(!Array.isArray(data.changes)||data.changes.length>500)throw Error('Invalid preview');
   safe.changes=data.changes.map(c=>{if(!HEX24.test(c?.manga_id)||!text(c.title)||!ids(c.before)||!ids(c.after))throw Error('Invalid membership change');return {manga_id:c.manga_id,title:c.title,before:c.before,after:c.after};});
  }
  return safe;
 }
 if(name==='list_paired_devices'){
  if(!Array.isArray(data)||data.length>3)throw Error('Invalid paired devices response');
  return data.map(d=>{if(!DEVICE.test(d.device_id)||!integer(d.expires))throw Error('Invalid paired device');return {device_id:d.device_id,expires:d.expires};});
 }
 if(name==='list_manga_chapters'){
  if(!Array.isArray(data)||data.length>100)throw Error('Invalid chapter list');
  return data.map(ch=>{if(!HEX24.test(ch.id)||typeof ch.name!=='string'||ch.name.length>160||typeof ch.read!=='boolean')throw Error('Invalid chapter metadata');return {id:ch.id,name:ch.name,read:ch.read};});
 }
 if(name==='list_device_extensions'){
  if(!DEVICE.test(data?.device_id)||!(data.updated===null||integer(data.updated))||!Array.isArray(data.extensions)||data.extensions.length>400)throw Error('Invalid extension inventory');
  const extensions=data.extensions.map(e=>{if(typeof e.name!=='string'||e.name.length>120||typeof e.installed!=='boolean'||typeof e.package_name!=='string'||!/^eu[.]kanade[.]tachiyomi[.]extension[.][a-zA-Z0-9_.]+$/.test(e.package_name))throw Error('Invalid extension');return {package_name:e.package_name,name:e.name,installed:e.installed};});
  return {device_id:data.device_id,updated:data.updated,extensions};
 }
 if(['request_manga_action','get_manga_action_status','install_extension','uninstall_extension'].includes(name)){
  const states=['pending_confirmation','completed','rejected','failed','expired'];
  if(!HEX32.test(data?.command_id)||!DEVICE.test(data.device_id)||!ACTIONS.has(data.action)||!states.includes(data.state)||!integer(data.expires))throw Error('Invalid command status');
  return {command_id:data.command_id,device_id:data.device_id,action:data.action,state:data.state,expires:data.expires};
 }

 if(name==='library_stats'){
  const result={};for(const key of ['titles','total_chapters','read_chapters','unread_chapters']){if(!integer(data?.[key]))throw Error('Invalid library response.');result[key]=data[key];}
  if(typeof data.has_sync_data!=='boolean')throw Error('Invalid sync status.');result.has_sync_data=data.has_sync_data;return result;
 }
 if(!Array.isArray(data)||data.length>100)throw Error('Invalid search response.');
 return data.map(book=>{if(typeof book.title!=='string'||book.title.length>180||!['chapters','read','unread'].every(k=>integer(book[k])))throw Error('Invalid title metadata.');const entry={title:book.title,chapters:book.chapters,read:book.read,unread:book.unread};if(book.id!==undefined){if(!HEX24.test(book.id))throw Error('Invalid manga ID');entry.id=book.id;}return entry;});
}
export async function backend(path,env,{method='GET',payload,fetcher=(...args)=>globalThis.fetch(...args)}={}){
 if(!env.MANGA_MCP_TOKEN||env.MANGA_MCP_TOKEN.length<32)throw Error('The protected backend connection is not configured.');
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
 try{
  const response=await fetcher(BASE+path,{method,redirect:'manual',headers:{Authorization:'Bearer '+env.MANGA_MCP_TOKEN,Accept:'application/json','User-Agent':'Demos-Manga-Cloud/1.3',...(payload!==undefined?{'Content-Type':'application/json'}:{})},...(payload!==undefined?{body:JSON.stringify(payload)}:{}),signal:controller.signal});
  if(response.status===401||response.status===403)throw Error('Backend authorization rejected. The owner must check the protected connection and edge policy.');
  if(response.status>=300&&response.status<400)throw Error('Backend redirected the request; credentials were not forwarded.');
  if(!response.ok){
   if([400,403,404,409].includes(response.status)){
    let detail;try{detail=(await response.json()).detail;}catch{}
    const safe=['Category data unavailable','Required phone scope','Revision conflict','Category snapshot is stale','Preview expired','Unknown category','Manga is missing','Preview not found','Explicit approval','Paired device not found','A later phone edit','Only a phone-confirmed','Category reorder undo','Too many','Invalid','Empty preview','Use batches','Reorder must'];
    if(typeof detail==='string'&&detail.length<=500&&safe.some(prefix=>detail.startsWith(prefix)))throw Error(detail+' No write was retried.');
   }
   throw Error('Backend returned HTTP '+response.status+'. No write was retried.');
  }
  const raw=await response.text();if(raw.length>512000)throw Error('Backend response exceeds the metadata limit.');
  return JSON.parse(raw);
 }catch(error){
  if(error?.name==='AbortError')throw Error('The library server did not respond within 15 seconds.');
  if(error instanceof TypeError||error instanceof SyntaxError){console.error(JSON.stringify({event:'backend_transport',errorType:error.name}));throw Error('The library server connection or JSON response failed.');}
  throw error;
 }finally{clearTimeout(timer);}
}
export async function execute(name,args,env,fetcher){
 try{
  args=argumentsFor(name,args);
  let path,method='GET',payload;
  if(CATEGORY_READS.has(name)){
   const params={...args};
   if(name==='list_uncategorized_manga')params.category_id='0';
   path=(name==='list_categories'||name==='get_category'?'/api/assistant/categories':name==='get_category_change_history'?'/api/assistant/category-history':'/api/assistant/category-library')+'?'+new URLSearchParams(params);
  }else if(CATEGORY_WRITES.has(name)){
   method='POST';payload={...args};
   if(['apply_category_changes','set_manga_categories','bulk_set_manga_categories'].includes(name))path='/api/assistant/category-apply';
   else if(name==='undo_category_changes')path='/api/assistant/category-undo-preview';
   else {path='/api/assistant/category-preview';if(name==='reorder_categories')payload.changes=[];}
  }
  else if(name==='library_stats')path='/api/assistant/stats';
  else if(name==='search_library')path='/api/assistant/search?'+new URLSearchParams({query:args.query,unread_only:String(args.unread_only),limit:String(args.limit)});
  else if(name==='list_paired_devices')path='/api/assistant/devices';
  else if(name==='list_manga_chapters')path='/api/assistant/chapters?'+new URLSearchParams({manga_id:args.manga_id,offset:String(args.offset),limit:String(args.limit)});
  else if(name==='list_device_extensions')path='/api/assistant/extensions?'+new URLSearchParams({device_id:args.device_id});
  else if(name==='get_manga_action_status')path='/api/assistant/commands/'+args.command_id;
  else if(name==='request_device_sync'){path='/api/assistant/remote-sync';method='POST';payload=args;}
  else if(name==='get_device_sync_status')path='/api/assistant/remote-sync/'+args.request_id;
  else if(['request_manga_action','install_extension','uninstall_extension'].includes(name)){path='/api/assistant/commands';method='POST';payload=args;}
  else throw Error('Unknown action.');
  const result=metadata(name,await backend(path,env,{method,payload,fetcher}));
  return {content:[{type:'text',text:JSON.stringify(result)}],structuredContent:{result},isError:false};
 }catch(error){return fail(error.message);}
}
const html=`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Demos Manga Cloud</title><style>body{background:#0d101a;color:#f4f1ff;font:16px system-ui;line-height:1.65;max-width:720px;margin:auto;padding:40px 22px}h1{font-size:clamp(30px,6vw,46px);line-height:1.15}section{padding:24px;border:1px solid #353047;border-radius:20px;background:#181725;margin:24px 0}a{color:#c7b5ff}button{background:#c7b5ff;color:#171024;padding:12px 18px;border:0;border-radius:12px;font:inherit;cursor:pointer}input{box-sizing:border-box;width:100%;padding:12px;background:#0d101a;color:#eee;border:1px solid #6e5b91;border-radius:8px}#pair{display:none}small{color:#c3bdce}</style><p>PRIVATE · OWNER-APPROVED LIBRARY ACTIONS</p><h1>Demos Manga Cloud</h1><p>Your reading library, available from ChatGPT without leaving your computer running.</p><section><h2>Use in ChatGPT</h2><p>Select <strong>@Demos Manga Cloud</strong> and ask “How many unread chapters do I have?” or “Find unread titles in my library.”</p><p>Library tools read permitted title metadata and progress. Command tools may queue requests, but the Android app requires your approval before making changes. No chapter images, private settings or raw backups are returned. Genre filtering excludes known adult tags, but cannot guarantee comprehensive content classification.</p></section><section><h2>Connect your Android reader</h2><p>Sync is optional. Pairing creates a separate device key valid for 90 days. Nothing is synced until you enable it in your reader.</p><button id="connect" type="button">Create my device key</button><div id="pair"><p>Copy this private key into your reader’s SyncYomi API key field. It is shown only for this response. Never share it in a chat.</p><input id="key" type="password" readonly aria-label="Private device sync key"><button id="copy" type="button">Copy key</button><p>Server: <strong>https://manga.demos25.me</strong></p><p>In Demos Manga: Settings → Data &amp; storage → Sync → SyncYomi. Enable library and chapters, paste the device key, then trigger Sync. Leave private settings, accounts and source settings disabled.</p></div><p id="message" role="status"></p><details><summary>Disconnect paired devices</summary><p>This revokes device keys created here. Your library remains intact.</p><button id="revoke" type="button">Revoke paired device keys</button></details></section><p><small>This page is private to your authorized ChatGPT account. Backend credentials remain in protected hosting configuration.</small></p><script src="/app.js"></script></html>`;
const js=`let busy=false;async function action(path){if(busy)return;busy=true;try{const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const data=await r.json();if(!r.ok)throw Error(data.error||'Connection failed.');return data;}finally{busy=false;}}document.getElementById('connect').onclick=async()=>{try{const d=await action('/pair');if(!d)return;document.getElementById('key').value=d.token;document.getElementById('pair').style.display='block';document.getElementById('message').textContent='Device key ready. Configure your reader to opt in to sync.';}catch(e){document.getElementById('message').textContent=e.message;}};document.getElementById('copy').onclick=async()=>{try{await navigator.clipboard.writeText(document.getElementById('key').value);document.getElementById('message').textContent='Copied. Paste it only in the reader SyncYomi settings.';}catch{document.getElementById('message').textContent='Select and copy the key from the field.';}};document.getElementById('revoke').onclick=async()=>{if(!confirm('Revoke all device keys created here?'))return;try{await action('/unpair');document.getElementById('key').value='';document.getElementById('pair').style.display='none';document.getElementById('message').textContent='Paired device keys revoked.';}catch(e){document.getElementById('message').textContent=e.message;}};`;
export default {async fetch(request,env){
 const url=new URL(request.url),path=url.pathname;
 if(request.method==='GET'&&path==='/')return new Response(html,{headers:{...HEADERS,'Content-Type':'text/html;charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'self'"}});
 if(request.method==='GET'&&path==='/app.js')return new Response(js,{headers:{...HEADERS,'Content-Type':'text/javascript;charset=utf-8'}});
 if(request.method==='GET'&&path==='/healthz')return json({service:'Demos Manga Cloud',version:VERSION,tools:TOOLS.length,configured:!!env.MANGA_MCP_TOKEN});
 // Read-only diagnostic uses private Sites service access; contains no library titles or credentials.
 if(request.method==='GET'&&path==='/healthz/upstream'){
  const stats=await execute('library_stats',{},env),search=await execute('search_library',{limit:1},env);
  return json({stats:stats.isError?stats:stats.structuredContent.result,search:{ok:!search.isError,count:search.structuredContent?.result?.length??0,...(search.isError?{error:search.content[0].text}:{})}},stats.isError||search.isError?502:200);
 }
 if(request.method==='POST'&&(path==='/pair'||path==='/unpair')){
  if(!request.headers.get('oai-authenticated-user-id')||request.headers.get('origin')!==url.origin)return json({error:'Owner sign-in and same-origin action required.'},403);
  try{return json(await backend(path==='/pair'?'/api/assistant/pair':'/api/assistant/unpair',env,{method:'POST'}));}catch(e){return json({error:e.message},502);}
 }
 if(path!=='/mcp')return json({error:'Not found'},404);
 if(request.method!=='POST')return json({error:'Use JSON-RPC POST'},405);
 if(!request.headers.get('content-type')?.includes('application/json'))return json({error:'Use application/json'},415);
 if(Number(request.headers.get('content-length')??0)>160000)return json({error:'Request too large'},413);
 const raw=await request.text();if(raw.length>160000)return json({error:'Request too large'},413);
 let body;try{body=JSON.parse(raw);}catch{return json({jsonrpc:'2.0',id:null,error:{code:-32700,message:'Invalid JSON'}});}
 if(!body||Array.isArray(body)||body.jsonrpc!=='2.0')return json({jsonrpc:'2.0',id:null,error:{code:-32600,message:'Invalid request'}});
 const id=body.id??null,method=body.method;
 const result=value=>json({jsonrpc:'2.0',id,result:value});
 if(method==='server/discover')return result({supportedVersions:['2026-07-28'],capabilities:{tools:{}}});
 if(method==='initialize')return result({protocolVersion:body.params?.protocolVersion??'2026-07-28',capabilities:{tools:{}},serverInfo:{name:'Demos Manga Cloud',version:VERSION},instructions:'Read-only library metadata plus owner-confirmed requests. All action requests remain pending until the paired phone approves them. Never claim success from a queued command, or request page images, raw backups or credentials.'});
 if(method==='notifications/initialized')return new Response(null,{status:202});
 if(method==='ping')return result({});
 if(method==='tools/list')return result({tools:TOOLS});
 if(method==='tools/call'){
  if(!request.headers.get('oai-authenticated-user-id'))return json({jsonrpc:'2.0',id,error:{code:-32001,message:'Sign in as the private Site owner.'}},401);
  return result(await execute(body.params?.name,body.params?.arguments??{},env));
 }
 return json({jsonrpc:'2.0',id,error:{code:-32601,message:'Method not found'}});
}};
