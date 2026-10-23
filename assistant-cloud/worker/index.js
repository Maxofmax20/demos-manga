const BASE='https://manga.demos25.me';
const VERSION='1.0.0';
const TOOLS=[{name:'library_stats',description:'Read aggregate progress and sync status from your own synchronized manga library.',inputSchema:{type:'object',properties:{},additionalProperties:false},annotations:{readOnlyHint:true,destructiveHint:false,openWorldHint:false}},{name:'search_library',description:'Search your own permitted library metadata; no chapters, images, settings or raw backups.',inputSchema:{type:'object',properties:{query:{type:'string',maxLength:200,default:''},unread_only:{type:'boolean',default:false},limit:{type:'integer',minimum:1,maximum:100,default:25}},additionalProperties:false},annotations:{readOnlyHint:true,destructiveHint:false,openWorldHint:false}}];
const HEADERS={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'};
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{...HEADERS,'Content-Type':'application/json; charset=utf-8'}});
const fail=message=>({content:[{type:'text',text:message}],isError:true});
export function argumentsFor(name,args){
 if(!args||typeof args!=='object'||Array.isArray(args))throw Error('Arguments must be an object.');
 const tool=TOOLS.find(t=>t.name===name);if(!tool)throw Error('Unknown library tool.');
 for(const key of Object.keys(args))if(!(key in tool.inputSchema.properties))throw Error('Unsupported argument: '+key);
 if(name==='library_stats')return {};
 const {query='',unread_only=false,limit=25}=args;
 if(typeof query!=='string'||query.length>200)throw Error('query must be text of at most 200 characters.');
 if(typeof unread_only!=='boolean')throw Error('unread_only must be true or false.');
 if(!Number.isInteger(limit)||limit<1||limit>100)throw Error('limit must be an integer from 1 to 100.');
 return {query,unread_only,limit};
}
export function metadata(name,data){
 const integer=v=>Number.isSafeInteger(v)&&v>=0;
 if(name==='library_stats'){
  const result={};for(const key of ['titles','total_chapters','read_chapters','unread_chapters']){if(!integer(data?.[key]))throw Error('Invalid library response.');result[key]=data[key];}
  if(typeof data.has_sync_data!=='boolean')throw Error('Invalid sync status.');result.has_sync_data=data.has_sync_data;return result;
 }
 if(!Array.isArray(data)||data.length>100)throw Error('Invalid search response.');
 return data.map(book=>{if(typeof book.title!=='string'||book.title.length>180||!['chapters','read','unread'].every(k=>integer(book[k])))throw Error('Invalid title metadata.');return {title:book.title,chapters:book.chapters,read:book.read,unread:book.unread};});
}
export async function backend(path,env,{method='GET',fetcher=(...args)=>globalThis.fetch(...args)}={}){
 if(!env.MANGA_MCP_TOKEN||env.MANGA_MCP_TOKEN.length<32)throw Error('The protected backend connection is not configured.');
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),15000);
 try{
  const response=await fetcher(BASE+path,{method,redirect:'manual',headers:{Authorization:'Bearer '+env.MANGA_MCP_TOKEN,Accept:'application/json','User-Agent':'Demos-Manga-Cloud/1.0'},signal:controller.signal});
  if(response.status===401||response.status===403)throw Error('Backend authorization rejected. The owner must check the protected connection and edge policy.');
  if(response.status>=300&&response.status<400)throw Error('Backend redirected the request; credentials were not forwarded.');
  if(!response.ok)throw Error('Backend returned HTTP '+response.status+'. No write was retried.');
  const raw=await response.text();if(raw.length>512000)throw Error('Backend response exceeds the metadata limit.');
  return JSON.parse(raw);
 }catch(error){
  if(error?.name==='AbortError')throw Error('The library server did not respond within 15 seconds.');
  if(error instanceof TypeError||error instanceof SyntaxError){console.error(JSON.stringify({event:'backend_transport',errorType:error.name}));throw Error('The library server connection or JSON response failed.');}
  throw error;
 }finally{clearTimeout(timer);}
}
export async function execute(name,args,env,fetcher){
 try{args=argumentsFor(name,args);const path=name==='library_stats'?'/api/assistant/stats':'/api/assistant/search?'+new URLSearchParams({query:args.query,unread_only:String(args.unread_only),limit:String(args.limit)});const result=metadata(name,await backend(path,env,{fetcher}));return {content:[{type:'text',text:JSON.stringify(result)}],structuredContent:{result},isError:false};}catch(error){return fail(error.message);}
}
const html=`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Demos Manga Cloud</title><style>body{background:#0d101a;color:#f4f1ff;font:16px system-ui;line-height:1.65;max-width:720px;margin:auto;padding:40px 22px}h1{font-size:clamp(30px,6vw,46px);line-height:1.15}section{padding:24px;border:1px solid #353047;border-radius:20px;background:#181725;margin:24px 0}a{color:#c7b5ff}button{background:#c7b5ff;color:#171024;padding:12px 18px;border:0;border-radius:12px;font:inherit;cursor:pointer}input{box-sizing:border-box;width:100%;padding:12px;background:#0d101a;color:#eee;border:1px solid #6e5b91;border-radius:8px}#pair{display:none}small{color:#c3bdce}</style><p>PRIVATE · READ-ONLY LIBRARY TOOLS</p><h1>Demos Manga Cloud</h1><p>Your reading library, available from ChatGPT without leaving your computer running.</p><section><h2>Use in ChatGPT</h2><p>Select <strong>@Demos Manga Cloud</strong> and ask “How many unread chapters do I have?” or “Find unread titles in my library.”</p><p>The two tools read only permitted title metadata and progress. They cannot read chapter images, private settings or raw backups. Genre filtering excludes known adult tags, but cannot guarantee comprehensive content classification.</p></section><section><h2>Connect your Android reader</h2><p>Sync is optional. Pairing creates a separate device key valid for 90 days. Nothing is synced until you enable it in your reader.</p><button id="connect" type="button">Create my device key</button><div id="pair"><p>Copy this private key into your reader’s SyncYomi API key field. It is shown only for this response. Never share it in a chat.</p><input id="key" type="password" readonly aria-label="Private device sync key"><button id="copy" type="button">Copy key</button><p>Server: <strong>https://manga.demos25.me</strong></p><p>In Demos Manga: Settings → Data &amp; storage → Sync → SyncYomi. Enable library and chapters, paste the device key, then trigger Sync. Leave private settings, accounts and source settings disabled.</p></div><p id="message" role="status"></p><details><summary>Disconnect paired devices</summary><p>This revokes device keys created here. Your library remains intact.</p><button id="revoke" type="button">Revoke paired device keys</button></details></section><p><small>This page is private to your authorized ChatGPT account. Backend credentials remain in protected hosting configuration.</small></p><script src="/app.js"></script></html>`;
const js=`let busy=false;async function action(path){if(busy)return;busy=true;try{const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});const data=await r.json();if(!r.ok)throw Error(data.error||'Connection failed.');return data;}finally{busy=false;}}document.getElementById('connect').onclick=async()=>{try{const d=await action('/pair');if(!d)return;document.getElementById('key').value=d.token;document.getElementById('pair').style.display='block';document.getElementById('message').textContent='Device key ready. Configure your reader to opt in to sync.';}catch(e){document.getElementById('message').textContent=e.message;}};document.getElementById('copy').onclick=async()=>{try{await navigator.clipboard.writeText(document.getElementById('key').value);document.getElementById('message').textContent='Copied. Paste it only in the reader SyncYomi settings.';}catch{document.getElementById('message').textContent='Select and copy the key from the field.';}};document.getElementById('revoke').onclick=async()=>{if(!confirm('Revoke all device keys created here?'))return;try{await action('/unpair');document.getElementById('key').value='';document.getElementById('pair').style.display='none';document.getElementById('message').textContent='Paired device keys revoked.';}catch(e){document.getElementById('message').textContent=e.message;}};`;
export default {async fetch(request,env){
 const url=new URL(request.url),path=url.pathname;
 if(request.method==='GET'&&path==='/')return new Response(html,{headers:{...HEADERS,'Content-Type':'text/html;charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'self'"}});
 if(request.method==='GET'&&path==='/app.js')return new Response(js,{headers:{...HEADERS,'Content-Type':'text/javascript;charset=utf-8'}});
 if(request.method==='GET'&&path==='/healthz')return json({service:'Demos Manga Cloud',version:VERSION,tools:2,configured:!!env.MANGA_MCP_TOKEN});
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
 if(Number(request.headers.get('content-length')??0)>16000)return json({error:'Request too large'},413);
 const raw=await request.text();if(raw.length>16000)return json({error:'Request too large'},413);
 let body;try{body=JSON.parse(raw);}catch{return json({jsonrpc:'2.0',id:null,error:{code:-32700,message:'Invalid JSON'}});}
 if(!body||Array.isArray(body)||body.jsonrpc!=='2.0')return json({jsonrpc:'2.0',id:null,error:{code:-32600,message:'Invalid request'}});
 const id=body.id??null,method=body.method;
 const result=value=>json({jsonrpc:'2.0',id,result:value});
 if(method==='server/discover')return result({supportedVersions:['2026-07-28'],capabilities:{tools:{}}});
 if(method==='initialize')return result({protocolVersion:body.params?.protocolVersion??'2026-07-28',capabilities:{tools:{}},serverInfo:{name:'Demos Manga Cloud',version:VERSION},instructions:'Read-only personal library metadata. Check has_sync_data before claiming current progress. Never retrieve chapters, raw backups or credentials.'});
 if(method==='notifications/initialized')return new Response(null,{status:202});
 if(method==='ping')return result({});
 if(method==='tools/list')return result({tools:TOOLS});
 if(method==='tools/call'){
  if(!request.headers.get('oai-authenticated-user-id'))return json({jsonrpc:'2.0',id,error:{code:-32001,message:'Sign in as the private Site owner.'}},401);
  return result(await execute(body.params?.name,body.params?.arguments??{},env));
 }
 return json({jsonrpc:'2.0',id,error:{code:-32601,message:'Method not found'}});
}};
