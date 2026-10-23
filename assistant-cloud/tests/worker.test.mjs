import test from 'node:test';
import assert from 'node:assert/strict';
import worker,{execute,metadata,argumentsFor} from '../worker/index.js';

const token='test-secret-'.repeat(4),env={MANGA_MCP_TOKEN:token};
const stats={titles:0,total_chapters:0,read_chapters:0,unread_chapters:0,has_sync_data:false};
const device_id='a'.repeat(16),manga_id='b'.repeat(24),command_id='c'.repeat(32);
const rpc=(method,params={},identity=true)=>new Request('https://example.test/mcp',{method:'POST',headers:{'Content-Type':'application/json',...(identity?{'oai-authenticated-user-id':'owner'}:{})},body:JSON.stringify({jsonrpc:'2.0',id:1,method,params})});

test('discovery exposes owner-approved extension and unattended sync tools',async()=>{
 const result=await(await worker.fetch(rpc('tools/list'),env)).json();
 assert.deepEqual(result.result.tools.map(t=>t.name),[
  'library_stats','search_library','list_paired_devices','list_manga_chapters',
  'list_device_extensions','install_extension','uninstall_extension','request_manga_action','get_manga_action_status','request_device_sync','get_device_sync_status'
 ]);
 const writes=result.result.tools.filter(t=>!t.annotations.readOnlyHint);
 assert.deepEqual(writes.map(t=>t.name),['install_extension','uninstall_extension','request_manga_action','request_device_sync']);
 assert(writes.every(t=>t.annotations.destructiveHint));
});

test('anonymous MCP and cross-origin pairing denied',async()=>{
 assert.equal((await worker.fetch(rpc('tools/call',{name:'library_stats'},false),env)).status,401);
 assert.equal((await worker.fetch(new Request('https://example.test/pair',{method:'POST',headers:{Origin:'https://evil.test','oai-authenticated-user-id':'owner'}}),env)).status,403);
});

test('input validation prevents arbitrary paths and invalid limits',()=>{
 for(const args of [{query:1},{unread_only:'true'},{limit:0},{limit:101},{url:'https://evil.test'}])assert.throws(()=>argumentsFor('search_library',args));
 assert.throws(()=>argumentsFor('read_backup',{}));
 assert.throws(()=>argumentsFor('list_manga_chapters',{manga_id:'invalid'}));
 assert.throws(()=>argumentsFor('list_device_extensions',{device_id:'bad'}));
 assert.throws(()=>argumentsFor('get_manga_action_status',{command_id:'bad'}));
});

test('search metadata allowlist preserves safe opaque manga IDs',async()=>{
 const result=await execute('search_library',{query:'A & B',unread_only:true,limit:1},env,async(url,o)=>{
  assert.equal(new URL(url).origin,'https://manga.demos25.me');
  assert.equal(new URL(url).searchParams.get('query'),'A & B');
  assert.equal(o.redirect,'manual');assert.equal(o.method,'GET');
  return Response.json([{title:'Safe title',chapters:10,read:3,unread:7,id:manga_id,private_settings:'excluded',pages:['excluded']}]);
 });
 assert.equal(result.isError,false);
 assert.deepEqual(Object.keys(result.structuredContent.result[0]),['title','chapters','read','unread','id']);
});

test('empty sync is an honest success',async()=>{
 const result=await execute('library_stats',{},env,async()=>Response.json(stats));
 assert.equal(result.isError,false);assert.equal(result.structuredContent.result.has_sync_data,false);
});

test('paired device identifiers have no private key',async()=>{
 const result=await execute('list_paired_devices',{},env,async(url)=>{assert(url.endsWith('/api/assistant/devices'));return Response.json([{device_id,expires:1234567890,token:'must never return'}]);});
 assert.equal(result.isError,false);assert.deepEqual(result.structuredContent.result,[{device_id,expires:1234567890}]);
});

test('chapter listing strips nonmetadata and enforces pagination',async()=>{
 const result=await execute('list_manga_chapters',{manga_id,offset:100,limit:5},env,async(url)=>{
  assert.equal(new URL(url).searchParams.get('offset'),'100');
  return Response.json([{id:manga_id,name:'Chapter 1',read:true,pages:['excluded']}]);
 });
 assert.equal(result.isError,false);
 assert.deepEqual(result.structuredContent.result,[{id:manga_id,name:'Chapter 1',read:true}]);
});

test('extension inventory exposes only allowed fields',async()=>{
 const result=await execute('list_device_extensions',{device_id},env,async(url)=>{
  assert.equal(new URL(url).searchParams.get('device_id'),device_id);
  return Response.json({device_id,updated:null,extensions:[{package_name:'eu.kanade.tachiyomi.extension.en.sample',name:'Sample',installed:true,download_url:'excluded'}]});
 });
 assert.equal(result.isError,false);
 assert.deepEqual(Object.keys(result.structuredContent.result.extensions[0]),['package_name','name','installed']);
});

test('command queue requires explicit confirmation and does not report execution',async()=>{
 let calls=0;
 const request={device_id,action:'mark_read',args:{manga_id,read:true},confirmed:true};
 const result=await execute('request_manga_action',request,env,async(url,o)=>{
  calls++;assert(url.endsWith('/api/assistant/commands'));assert.equal(o.method,'POST');
  assert.deepEqual(JSON.parse(o.body),{device_id,action:'mark_read',args:{manga_id,read:true}});
  return Response.json({command_id,device_id,action:'mark_read',state:'pending_confirmation',expires:1234567890,secret:'excluded'});
 });
 assert.equal(calls,1);assert.equal(result.isError,false);
 assert.equal(result.structuredContent.result.state,'pending_confirmation');
 assert(!('secret' in result.structuredContent.result));
});

test('unauthorized or invalid command not queued',async()=>{
 let calls=0;
 const fetcher=async()=>{calls++;return Response.json({});};
 for(const args of [
  {device_id,action:'mark_read',args:{manga_id,read:true}},
  {device_id,action:'mark_read',args:{manga_id,read:true},confirmed:false},
  {device_id,action:'exec',args:{},confirmed:true},
  {device_id,action:'mark_read',args:{url:'https://example.test'},confirmed:true,extra:'forbidden'},
 ]){const result=await execute('request_manga_action',args,env,fetcher);assert(result.isError);}
 assert.equal(calls,0);
});

test('read pending command status validated',async()=>{
 const result=await execute('get_manga_action_status',{command_id},env,async(url)=>{
  assert(url.endsWith('/api/assistant/commands/'+command_id));
  return Response.json({command_id,device_id,action:'mark_read',state:'rejected',expires:1234567890,raw_backup:'excluded'});
 });
 assert.equal(result.isError,false);assert.equal(result.structuredContent.result.state,'rejected');
 assert(!('raw_backup' in result.structuredContent.result));
});

test('authorization and redirects fail without retries',async()=>{
 for(const status of [401,403,302]){let calls=0;const result=await execute('library_stats',{},env,async()=>{calls++;return new Response(null,{status});});assert(result.isError);assert.equal(calls,1);assert(!JSON.stringify(result).includes(token));}
});

test('page and script contain no backend credential',async()=>{
 for(const path of ['/','/app.js','/healthz']){const result=await worker.fetch(new Request('https://example.test'+path),env);assert(!(await result.text()).includes(token));}
});

test('malformed or oversized metadata rejected',()=>{
 assert.throws(()=>metadata('library_stats',{}));
 assert.throws(()=>metadata('search_library',[{title:'x',chapters:-1,read:0,unread:0}]));
 assert.throws(()=>metadata('list_device_extensions',{device_id,updated:null,extensions:[{package_name:'random',name:'bad',installed:true}]}));
});


test('extension install and uninstall enqueue pending actions without executing anything', async()=>{
 const package_name='eu.kanade.tachiyomi.extension.en.sample';
 for(const action of ['install_extension','uninstall_extension']){
  let calls=0;
  const result=await execute(action,{device_id,package_name,confirmed:true},env,async(url,options)=>{
   calls++;assert(url.endsWith('/api/assistant/commands'));
   assert.equal(options.method,'POST');
   assert.deepEqual(JSON.parse(options.body),{device_id,action,args:{package_name}});
   return Response.json({device_id,command_id,action,state:'pending_confirmation',expires:1234567890});
  });
  assert.equal(calls,1);
  assert.equal(result.isError,false);
  assert.equal(result.structuredContent.result.state,'pending_confirmation');
 }
});

test('extension actions reject unsafe package names and missing approval before network requests', async()=>{
 const inputs=[
  {device_id,package_name:'eu.kanade.tachiyomi.extension.en.sample',confirmed:false},
  {device_id,package_name:'eu.kanade.tachiyomi.extension.en.sample'},
  {device_id,package_name:'com.example.malware',confirmed:true},
  {device_id,package_name:'eu.kanade.tachiyomi.extension.en.adult',confirmed:true},
  {device_id,package_name:'eu.kanade.tachiyomi.extension.en.hentai',confirmed:true},
  {device_id,package_name:'eu.kanade.tachiyomi.extension.en.sample',confirmed:true,raw_url:'https://example.invalid/a.apk'},
 ];
 let count=0;
 for(const name of ['install_extension','uninstall_extension']){
  for(const item of inputs){
   const result=await execute(name,item,env,async()=>{count++;return Response.json({});});
   assert(result.isError,JSON.stringify({name,item}));
  }
 }
 assert.equal(count,0);
});


test('remote sync API only queues requests and returns pending status',async()=>{
 let calls=0;
 const result=await execute('request_device_sync',{device_id},env,async(url,o)=>{
  calls++;
  assert.equal(o.method,'POST');
  assert.equal(new URL(url).pathname,'/api/assistant/remote-sync');
  assert.deepEqual(JSON.parse(o.body),{device_id});
  return Response.json({request_id:command_id,device_id,state:'pending',updated:11,expires:22,token:'redacted'});
 });
 assert.equal(calls,1);
 assert.equal(result.isError,false);
 assert.deepEqual(Object.keys(result.structuredContent.result),['request_id','device_id','state','updated','expires']);
});

test('remote sync status reports completed only on phone completion',async()=>{
 const result=await execute('get_device_sync_status',{request_id:command_id},env,async(url)=>{
  assert(new URL(url).pathname.endsWith('/api/assistant/remote-sync/'+command_id));
  return Response.json({request_id:command_id,device_id,state:'completed',updated:20,expires:30,private_key:'excluded'});
 });
 assert.equal(result.isError,false);
 assert.equal(result.structuredContent.result.state,'completed');
 assert(!('private_key' in result.structuredContent.result));
});

test('remote sync tools reject invented device identifiers and unsupported fields',async()=>{
 assert.throws(()=>argumentsFor('request_device_sync',{device_id:'bad'}));
 assert.throws(()=>argumentsFor('request_device_sync',{device_id,action:'uninstall_extension'}));
 assert.throws(()=>argumentsFor('get_device_sync_status',{request_id:'bad'}));
 assert.throws(()=>metadata('request_device_sync',{request_id:command_id,device_id,state:'success',updated:1,expires:2}));
});
