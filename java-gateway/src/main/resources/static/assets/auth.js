import{api,state,onForm,notice,roleName}from './api.js';
export async function initAuth(onReady){
  onForm('login',async data=>{
    const account=await api(state.management?'/admin/login':'/auth/login',{method:'POST',body:data});
    if(location.pathname.startsWith('/platform')&&account.role!=='PLATFORM_ADMIN')throw new Error('请使用平台管理员账户');
    state.me=account;await ready(onReady);
  });
  const register=document.querySelector('#register');
  if(register)onForm('register',async data=>{state.me=await api('/auth/register',{method:'POST',body:data});await ready(onReady);});
  document.querySelector('#logout').addEventListener('click',async()=>{try{await api('/auth/logout',{method:'POST'});}finally{location.reload();}});
  onForm('password',async data=>{await api('/auth/password',{method:'POST',body:data});location.reload();});
  try{state.me=await api('/auth/me');await ready(onReady);}catch(e){notice(e.status===401?'请先登录':e.message,e.status!==401);}
}
async function ready(onReady){
  const platform=location.pathname.startsWith('/platform');
  if(platform&&state.me.role!=='PLATFORM_ADMIN')throw new Error('请使用平台管理员账户');
  if(!platform&&state.management&&!['OWNER','STAFF'].includes(state.me.role))throw new Error('请使用商户账户');
  document.querySelector('#auth').hidden=true;document.querySelector('#workspace').hidden=false;document.querySelector('#account').hidden=false;
  document.querySelector('#identity').textContent=state.me.username+' / '+roleName(state.me.role);
  await onReady();
}
