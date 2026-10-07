import{api,state,node,button,onForm,notice,run,statusNames,fmt,events,formData}from './api.js';
import{initAuth}from './auth.js';
let shop,menu=[],page=1;
async function orders(){
  const status=document.querySelector('#orderStatus').value;
  const result=await api('/admin/orders?page='+page+'&size=20'+(status===''?'':'&status='+status));
  const el=document.querySelector('#orders');el.replaceChildren();
  document.querySelector('#orderCount').textContent='第 '+page+' 页 / 共 '+result.total+' 笔';
  for(const o of result.items){
    const row=node('article');row.append(node('h3','#'+o.id+' · '+statusNames[o.status]),node('p',o.items.map(i=>i.dishName+' × '+i.quantity).join('、')),
      node('p','¥'+o.totalAmount+' · '+o.paymentStatus+' · '+fmt(o.createTime)),
      node('p',(o.recipientName||'')+' '+(o.recipientPhone||'')+' '+(o.deliveryAddress||'')+' · 催单 '+o.remindCount+' 次'));
    if(o.status>=1&&o.status<=3)row.append(button(['','','开始制作','开始配送','完成'][o.status+1],async()=>{await api('/admin/orders/'+o.id+'/status?status='+(o.status+1),{method:'POST'});await refresh();}));
    if(o.status<=1)row.append(button('取消并释放库存',async()=>{await api('/admin/orders/'+o.id+'/status?status=5',{method:'POST'});await refresh();}));
    el.append(row);
  }
}
async function dishes(){
  menu=await api('/admin/dishes');const el=document.querySelector('#menu');el.replaceChildren();
  for(const d of menu){
    const row=node('article');row.append(node('h3',d.name),node('p','¥'+d.price+' · 库存 '+d.stock+' / 版本 '+d.stockVersion));
    row.append(button(d.status?'停售':'上架',async()=>{await api('/admin/dishes/'+d.id+'/status?status='+(d.status?0:1),{method:'PUT'});await dishes();}));
    const form=node('form');const amount=node('input',undefined,{type:'number',placeholder:'增减库存',required:'required',min:'-1000000',max:'1000000'});
    const submit=node('button','调整库存',{type:'submit'});form.append(amount,submit);
    form.addEventListener('submit',e=>{e.preventDefault();run(async()=>{await api('/admin/dishes/'+d.id+'/inventory',{method:'POST',body:{delta:Number(amount.value),expectedVersion:d.stockVersion,requestKey:crypto.randomUUID()}});await dishes();},submit);});
    row.append(form,button('编辑',()=>{const form=document.querySelector('#dishForm');for(const name of ['id','name','price','category','description','allergens','version','stock','status'])form.elements[name].value=d[name]??'';form.elements.allergenReviewed.checked=d.allergenReviewed;}));el.append(row);
  }
}
onForm('dishForm',async data=>{
  const body={...data,price:Number(data.price),stock:Number(data.stock),status:Number(data.status),allergenReviewed:document.querySelector('#dishReviewed').checked,version:data.version?Number(data.version):null};
  await api('/admin/dishes'+(data.id?'/'+data.id:''),{method:data.id?'PUT':'POST',body});
  document.querySelector('#dishForm').reset();await refresh();
});
document.querySelector('#resetDish').addEventListener('click',()=>document.querySelector('#dishForm').reset());
onForm('settings',async data=>{
  shop=await api('/admin/merchant',{method:'PUT',body:{name:data.name,opensAt:data.opensAt,closesAt:data.closesAt,acceptingOrders:document.querySelector('#accepting').checked,deliveryRegions:data.regions.split(',').map(x=>x.trim()),expectedVersion:shop.version}});
  await settings();
});
async function settings(){
  shop=await api('/admin/merchant');const form=document.querySelector('#settings');
  form.elements.name.value=shop.name;form.elements.opensAt.value=shop.opensAt;form.elements.closesAt.value=shop.closesAt;form.elements.regions.value=shop.deliveryRegions.join(',');form.elements.acceptingOrders.checked=shop.acceptingOrders;
}
onForm('staffForm',async data=>{await api('/admin/staff',{method:'POST',body:data});document.querySelector('#staffForm').reset();await staff();});
async function staff(){
  const el=document.querySelector('#staff');el.replaceChildren();
  for(const s of await api('/admin/staff')){
    const row=node('p','#'+s.id+' '+s.username+' / '+s.role+' / '+(s.enabled?'启用':'停用')+' ');
    if(s.role==='STAFF')row.append(button(s.enabled?'停用':'启用',async()=>{await api('/admin/staff/'+s.id+'/enabled',{method:'PUT',body:{enabled:!s.enabled}});await staff();}));
    el.append(row);
  }
}
onForm('staffPassword',async data=>{await api('/admin/staff/'+Number(data.staffId)+'/password',{method:'POST',body:{password:data.password}});document.querySelector('#staffPassword').reset();});
async function audit(){const el=document.querySelector('#audit');el.replaceChildren();for(const a of await api('/admin/audit'))el.append(node('p',fmt(a.created_at)+' '+a.actor+' '+a.action+' '+a.resource_id));}
async function refresh(){await orders();if(state.me.role==='OWNER'){await Promise.all([dishes(),audit()]);const stats=await api('/admin/stats');document.querySelector('#stats').textContent='订单 '+stats.orders+' 笔 · 取消 '+stats.cancelledOrders+' 笔 · 已完成模拟金额 ¥'+stats.completedSimulatedAmount;}}
document.querySelector('#orderStatus').addEventListener('change',()=>{page=1;run(orders);});
document.querySelector('#prev').addEventListener('click',()=>{page=Math.max(1,page-1);run(orders);});
document.querySelector('#next').addEventListener('click',()=>{page++;run(orders);});
document.querySelector('#refresh').addEventListener('click',()=>run(refresh));
await initAuth(async()=>{
  if(!['OWNER','STAFF'].includes(state.me.role))throw new Error('请使用商户老板或店员账号');
  state.merchant=state.me.merchantId;
  for(const el of document.querySelectorAll('[data-owner]'))el.hidden=state.me.role!=='OWNER';
  await refresh();if(state.me.role==='OWNER')await Promise.all([settings(),staff()]);
  events('/admin/events','merchant:'+state.merchant,()=>{notice('有订单更新');run(orders);});
});
