// Focused stream lifecycle checks using the platform's real ReadableStream and AbortController.
// No server, database, model, or npm dependencies are needed.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
global.location={pathname:'/rider/'};
global.document=new EventTarget();
const storage=new Map();
global.sessionStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)};
const streams=[];
global.fetch=async(url,{signal,headers})=>{
  const entry={url,signal,headers};streams.push(entry);
  return {status:200,ok:true,body:new ReadableStream({
    start(controller){entry.controller=controller;signal.addEventListener('abort',()=>controller.error(new Error('aborted')),{once:true});},
    cancel(){entry.cancelled=true;}
  })};
};
const waitFor=async predicate=>{
  const end=Date.now()+2000;
  while(!predicate()){if(Date.now()>end)throw Error('stream condition timed out');await new Promise(r=>setTimeout(r,5));}
};
const emit=(stream,text)=>stream.controller.enqueue(new TextEncoder().encode(text));
(async()=>{
  const source=fs.readFileSync(path.resolve(__dirname,'../java-gateway/src/main/resources/static/assets/api.js'),'utf8');
  const {state,watchEventStream,stopEvents}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
  state.me={adminId:7};
  let editing=true,refreshes=0;
  const first=watchEventStream('/rider/events','rider-events:7',async()=>{if(editing)return false;refreshes++;});
  await waitFor(()=>streams.length===1);
  assert.equal(streams[0].headers['X-Session-Type'],'management');
  emit(streams[0],'id: 10\nevent: commerce\ndata: {}\n\n');
  await new Promise(r=>setTimeout(r,10));assert.equal(storage.has('rider-events:7'),false);
  editing=false;document.dispatchEvent(new Event('focusout'));
  await waitFor(()=>storage.get('rider-events:7')==='10');
  assert.equal(refreshes,1,'focusout applies the pending event without another server heartbeat');
  let release;
  const second=watchEventStream('/rider/events','rider-events:8',()=>new Promise(resolve=>{release=resolve;}));
  await waitFor(()=>streams.length===2);assert.equal(streams[0].signal.aborted,true);
  assert.ok(streams[1].url.endsWith('after=0'),'a different rider starts with its own cursor');
  emit(streams[1],'id: 2\nevent: commerce\ndata: {}\n');
  emit(streams[1],'\n');await waitFor(()=>release);
  assert.equal(storage.has('rider-events:8'),false,'receiving bytes does not acknowledge an unfinished refresh');
  release();await waitFor(()=>storage.get('rider-events:8')==='2');
  stopEvents();await Promise.all([first,second]);
  assert.equal(streams[1].signal.aborted,true,'stop cancels the active reader');
  const count=streams.length;document.dispatchEvent(new Event('focusout'));
  await new Promise(r=>setTimeout(r,10));assert.equal(streams.length,count,'stopped subscription does not reconnect');
  console.log(JSON.stringify({passed:true,checks:['deferred focusout refresh','single cancellable subscription','account-scoped cursor','acknowledge only after refresh','stop without reconnect']}));
})().catch(error=>{console.error(error);process.exitCode=1;});
