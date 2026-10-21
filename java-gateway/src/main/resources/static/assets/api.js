export const state={management:location.pathname.startsWith('/admin')||location.pathname.startsWith('/platform')||location.pathname.startsWith('/rider'),merchant:null,me:null};
let csrf;
export class ApiError extends Error {constructor(status,body){super(body.msg||'请求失败');this.status=status;this.code=body.errorCode;this.data=body.data;}}
export async function api(path,{method='GET',body,headers={},csrfRetried=false}={}) {
  if(method!=='GET'&&!csrf){const r=await fetch('/auth/csrf',{credentials:'same-origin'});const b=await r.json();csrf=b.data.token;}
  const h={'Accept':'application/json',...headers};
  if(state.management)h['X-Session-Type']='management';
  if(state.merchant)h['X-Merchant-Id']=String(state.merchant);
  if(body!==undefined)h['Content-Type']='application/json';
  if(method!=='GET')h['X-XSRF-TOKEN']=csrf;
  const response=await fetch(path,{method,headers:h,credentials:'same-origin',body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json().catch(()=>({msg:'响应格式异常'}));
  if(response.status===403&&result.errorCode==='CSRF_REJECTED'&&!csrfRetried){csrf=undefined;return api(path,{method,body,headers,csrfRetried:true});}
  if(response.status===401){stopEvents();state.me=null;for(const [id,hidden] of [['workspace',true],['account',true],['auth',false]]){const element=document.querySelector('#'+id);if(element)element.hidden=hidden;}}
  if(!response.ok||result.code!==1)throw new ApiError(response.status,result);
  if(['/auth/login','/auth/register','/admin/login','/auth/logout','/auth/password'].includes(path))csrf=undefined;
  return result.data;
}
export function node(tag,text,attrs={}) {const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);for(const[k,v]of Object.entries(attrs))n.setAttribute(k,v);return n;}
export function button(text,action){const b=node('button',text);b.type='button';b.addEventListener('click',()=>run(action,b));return b;}
let noticeRevision=0;
export async function run(action,b){try{if(b)b.disabled=true;notice('正在处理…');const revision=noticeRevision;await action();if(revision===noticeRevision)notice('操作完成');}catch(e){notice(e.message,true);}finally{if(b)b.disabled=false;}}
export function notice(message,error=false){noticeRevision++;const el=document.querySelector('#notice');el.textContent=message;el.className=error?'error':'ok';}
export function fmt(value){if(!value)return '-';const s=String(value);const d=new Date(/[Zz]|[+-]\d\d:\d\d$/.test(s)?s:s.replace(' ','T')+'+08:00');if(Number.isNaN(d.getTime()))return '-';return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d);}
export const statusNames=['待模拟支付','商家待处理','制作中','配送中','完成','取消','支付超时'];
const paymentNames={UNPAID:'尚未模拟支付',SIMULATED_UNPAID:'尚未模拟支付',SIMULATED_PAID:'已模拟支付',PARTIALLY_REFUNDED:'部分模拟退款',SIMULATED_REFUNDED:'已模拟退款',CLOSED:'已关闭',NOT_APPLICABLE:'历史订单（无模拟支付记录）'};
export const paymentName=value=>paymentNames[value]||'未支付';
const roles={CUSTOMER:'顾客',OWNER:'商户老板',STAFF:'店员',PLATFORM_ADMIN:'平台管理员',RIDER:'骑手',SYSTEM:'系统'};
export const roleName=value=>roles[value]||value;
export function auditText(a){const actions={ORDER_CONFIRMED:'确认订单',ORDER_CANCELLED:'取消订单',MERCHANT_CANCELLED:'商家取消订单',ORDER_REMINDER:'催单',SIMULATED_PAYMENT:'模拟支付',PAYMENT_TIMEOUT:'支付超时关闭',DISH_CREATED:'新增菜品',DISH_UPDATED:'修改菜品',DISH_STATUS_CHANGED:'菜品上下架',STOCK_ADJUSTED:'调整库存',MERCHANT_CREATED:'开通商户',MERCHANT_CONFIGURED:'修改营业设置',MERCHANT_ENABLED:'启用商户',MERCHANT_DISABLED:'停用商户',STAFF_CREATED:'创建店员',STAFF_ENABLED_CHANGED:'调整店员状态',STAFF_PASSWORD_RESET:'重置店员密码',CUSTOMER_ENABLED_CHANGED:'调整顾客状态'};const actor=String(a.actor).split(':');const status=a.action?.match(/^ORDER_STATUS_(\d)$/);return fmt(a.created_at)+' · '+roleName(actor[0])+(actor[1]?' #'+actor[1]:'')+' · '+(status?'订单进入'+statusNames[Number(status[1])]:actions[a.action]||a.action)+' · '+a.resource_id;}
export function formData(form){return Object.fromEntries(new FormData(form));}
export function onForm(id,action){document.querySelector('#'+id).addEventListener('submit',e=>{e.preventDefault();run(()=>action(formData(e.target)),e.submitter);});}
let streamController;
export function stopEvents(){streamController?.abort();streamController=undefined;}
export async function watchEventStream(path,key,onChange) {
  stopEvents();const controller=new AbortController();streamController=controller;
  const {signal}=controller;
  let cursor=Number(sessionStorage.getItem(key)||0),received=cursor,refreshing=false,focusTimer;
  if(!Number.isSafeInteger(cursor)||cursor<0)cursor=received=0;
  // A cursor acknowledges an applied refresh, never merely received event bytes.
  async function flush(){
    if(refreshing||signal.aborted)return;
    refreshing=true;
    try{
      while(received>cursor&&!signal.aborted){
        const next=received;
        if(await onChange()===false||signal.aborted)break;
        cursor=next;sessionStorage.setItem(key,String(cursor));
      }
    }catch{/* Retain the pending cursor for the next heartbeat or focus change. */}
    finally{refreshing=false;}
  }
  const onFocusOut=()=>{clearTimeout(focusTimer);focusTimer=setTimeout(()=>void flush(),0);};
  document.addEventListener('focusout',onFocusOut);
  signal.addEventListener('abort',()=>{clearTimeout(focusTimer);document.removeEventListener('focusout',onFocusOut);},{once:true});
  try{
    while(!signal.aborted){
      try{
        const headers={Accept:'text/event-stream'};
        if(state.management)headers['X-Session-Type']='management';
        if(state.merchant)headers['X-Merchant-Id']=String(state.merchant);
        const response=await fetch(path+'?after='+cursor,{headers,credentials:'same-origin',signal});
        if(response.status===401||response.status===403)return;
        if(!response.ok)throw new Error('stream unavailable');
        const reader=response.body.getReader(),decoder=new TextDecoder();let pending='',more=false;
        while(!signal.aborted){
          const {value,done}=await reader.read();if(done)break;
          pending+=decoder.decode(value,{stream:true}).replace(/\r/g,'');
          let boundary;
          while((boundary=pending.indexOf('\n\n'))>=0){
            const frame=pending.slice(0,boundary);pending=pending.slice(boundary+2);
            const id=frame.match(/^id:\s*(\d+)/m),event=frame.match(/^event:\s*(.*)/m);
            if(id)received=Math.max(received,Number(id[1]));
            if(event?.[1]==='connected'){
              const raw=frame.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trim()).join('\n');
              if(raw)more=JSON.parse(raw).hasMore===true;
            }
          }
          await flush();
          // Legacy replay can contain more than one page. Do not replay a page
          // endlessly while an editor deliberately defers its refresh.
          if(more&&cursor>=received){await reader.cancel();break;}
        }
        if(more&&cursor>=received)continue;
      }catch{if(signal.aborted)break;}
      await new Promise(resolve=>{
        const finish=()=>{clearTimeout(timer);signal.removeEventListener('abort',finish);resolve();};
        const timer=setTimeout(finish,3000);signal.addEventListener('abort',finish,{once:true});
        if(signal.aborted)finish();
      });
    }
  }finally{
    controller.abort();if(streamController===controller)streamController=undefined;
  }
}
export const events=(path,scope,onChange)=>watchEventStream(path,'events:'+scope,onChange);
