import{api,state,node,button,onForm,auditText,run}from './api.js';import{initAuth}from './auth.js';
async function refresh(){
  const el=document.querySelector('#merchants');el.replaceChildren();
  for(const s of await api('/platform/merchants')){const row=node('article');row.append(node('h3',s.name+' #'+s.id),node('p',s.enabled?'已启用':'已停用'),
    button(s.enabled?'停用商户':'启用商户',async()=>{await api('/platform/merchants/'+s.id+'/enabled',{method:'PUT',body:{enabled:!s.enabled}});await refresh();}));el.append(row);}
  const audit=document.querySelector('#audit');audit.replaceChildren();for(const a of await api('/platform/audit'))audit.append(node('p',auditText(a)+' · 商户 '+(a.merchant_id??'平台')));
}
onForm('provision',async data=>{await api('/platform/merchants',{method:'POST',body:data});document.querySelector('#provision').reset();await refresh();});
onForm('customerEnable',async data=>{await api('/platform/users/'+Number(data.userId)+'/enabled',{method:'PUT',body:{enabled:data.enabled==='true'}});await refresh();});
document.querySelector('#refresh').addEventListener('click',()=>run(refresh));
await initAuth(async()=>{if(state.me.role!=='PLATFORM_ADMIN')throw new Error('需要平台管理员权限');await refresh();});
