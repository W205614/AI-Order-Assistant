import{api,state,node,button,notice,run,onForm,formData,statusNames,fmt,events,stopEvents}from './api.js';
import{initAuth}from './auth.js';
let drafts=[],cart=[],currentShop,history=[],ordersPage=1,confirmKeys=new Map();
const menuEl=document.querySelector('#menu'),draftEl=document.querySelector('#draft'),orderEl=document.querySelector('#orders');
const selection=document.querySelector('#merchant');
async function selectShop(){
  stopEvents();cart=[];drafts=[];state.merchant=Number(selection.value);confirmKeys.clear();
  currentShop=await api('/merchants/'+state.merchant);
  history=JSON.parse(sessionStorage.getItem(historyKey())||'[]');
  document.querySelector('#messages').replaceChildren();for(const h of history)addMessage(h.role,h.content,false);
  document.querySelector('#region').replaceChildren(...currentShop.deliveryRegions.map(r=>node('option',r,{value:r})));
  document.querySelector('#shopState').textContent=currentShop.name+' / '+(currentShop.acceptingOrders?'接单中':'暂停接单')+' / '+currentShop.opensAt+'–'+currentShop.closesAt;
  await refresh();events('/order/events',state.me.userId+':'+state.merchant,()=>{run(loadOrders);});
}
function historyKey(){return 'history:'+state.me.userId+':'+state.merchant;}
async function loadMenu(){
  const keyword=document.querySelector('#keyword').value;
  const result=await api('/dish/list?availableOnly=true&size=50&keyword='+encodeURIComponent(keyword));
  menuEl.replaceChildren();
  for(const d of result.items){
    const row=node('article');row.append(node('h3',d.name),node('p','¥'+d.price+' · 库存 '+d.stock),node('p',d.description||''));
    row.append(node('p',d.allergenReviewed?'已核验过敏原：'+(d.allergens||'未标注受控标签'):'过敏原尚未核验'));
    row.append(button('加入购物车',()=>{const old=cart.find(i=>i.dishId===d.id);if(old){if(old.quantity>=99)throw new Error('数量最多 99');old.quantity++;}else cart.push({dishId:d.id,dishName:d.name,quantity:1});renderCart();}));
    menuEl.append(row);
  }
}
function renderCart(){
  const el=document.querySelector('#cart');el.replaceChildren();
  for(const item of cart){const row=node('p',item.dishName+' × '+item.quantity+' ');row.append(button('减少',()=>{item.quantity--;cart=cart.filter(i=>i.quantity>0);renderCart();}));el.append(row);}
}
async function loadDraft(){drafts=await api('/order/drafts/pending');renderDraft();}
function renderDraft(){
  draftEl.replaceChildren();const draft=drafts[0];if(!draft){draftEl.append(node('p','暂无待确认草稿'));return;}
  draftEl.append(node('h3','待确认 · 版本 '+draft.version),node('p',draft.items.map(i=>i.dishName+' × '+i.quantity).join('、')),node('p','合计 ¥'+draft.totalAmount+'；五分钟内有效'));
  draftEl.append(button('确认并进入模拟支付',async()=>{
    const version=draft.version,id=draft.id,key=id+':'+version;
    if(!confirmKeys.has(key))confirmKeys.set(key,crypto.randomUUID());
    try{
      await api('/order/drafts/'+id+'/confirm',{method:'POST',headers:{'Idempotency-Key':confirmKeys.get(key)},
        body:{...formData(document.querySelector('#receipt')),expectedVersion:version}});
      cart=[];renderCart();await refresh();
    }catch(e){if(e.status===409&&e.data?.id){drafts=[e.data];renderDraft();notice(e.message+'，请核对并再次点击',true);}else throw e;}
  }),button('修改数量与备注',()=>{cart=draft.items.map(i=>({dishId:i.dishId,dishName:i.dishName,quantity:i.quantity}));document.querySelector('#remark').value=draft.remark||'';renderCart();}),
  button('放弃草稿',async()=>{await api('/order/drafts/'+draft.id,{method:'DELETE'});await refresh();}));
}
onForm('cartForm',async data=>{
  if(!cart.length)throw new Error('请先添加菜品');
  const old=drafts[0],body={items:cart,remark:data.remark};
  if(old){body.expectedVersion=old.version;await api('/order/drafts/'+old.id,{method:'PUT',body});}
  else await api('/order/drafts',{method:'POST',body});
  cart=[];renderCart();await loadDraft();
});
async function loadOrders(){
  const filter=document.querySelector('#orderStatus').value;
  const result=await api('/order/list?page='+ordersPage+'&size=20'+(filter===''?'':'&status='+filter));
  orderEl.replaceChildren();document.querySelector('#orderCount').textContent='第 '+ordersPage+' 页 / 共 '+result.total+' 笔';
  for(const order of result.items){
    const row=node('article');row.append(node('h3','#'+order.userSeq+' · '+statusNames[order.status]),node('p',order.items.map(i=>i.dishName+' × '+i.quantity).join('、')),
      node('p','¥'+order.totalAmount+' · '+order.paymentStatus+' · '+fmt(order.createTime)));
    if(order.status===0)row.append(button('模拟支付',async()=>{await api('/order/'+order.userSeq+'/pay',{method:'POST'});await refresh();}));
    if(order.status<=1)row.append(button('取消订单',async()=>{await api('/order/'+order.userSeq+'/cancel',{method:'POST'});await refresh();}));
    if(order.status>=1&&order.status<=3)row.append(button('催单',async()=>{await api('/order/'+order.userSeq+'/remind',{method:'POST'});await loadOrders();}));
    orderEl.append(row);
  }
}
async function refresh(){await Promise.all([loadMenu(),loadDraft(),loadOrders()]);}
selection.addEventListener('change',()=>run(selectShop));
document.querySelector('#search').addEventListener('click',()=>run(loadMenu));
document.querySelector('#orderStatus').addEventListener('change',()=>{ordersPage=1;run(loadOrders);});
document.querySelector('#prev').addEventListener('click',()=>{ordersPage=Math.max(1,ordersPage-1);run(loadOrders);});
document.querySelector('#next').addEventListener('click',()=>{ordersPage++;run(loadOrders);});
document.querySelector('#refresh').addEventListener('click',()=>run(refresh));
onForm('allergy',async()=>{
  const allergens=[...document.querySelectorAll('#allergy input:checked')].map(x=>x.value);
  if(!allergens.length)throw new Error('请选择明确的过敏原');
  await api('/order/safety-context',{method:'PUT',body:{allergens}});await loadMenu();
});
document.querySelector('#clearSafety').addEventListener('click',()=>run(async()=>{await api('/order/safety-context',{method:'DELETE'});await loadMenu();}));
onForm('preferences',async data=>{
  await api('/user/preferences',{method:'PUT',body:{...data,budget:data.budget?Number(data.budget):null}});await loadMenu();
});
function addMessage(role,text,save=true){
  const p=node('p', (role==='user'?'你：':'助手：')+text);document.querySelector('#messages').append(p);
  if(save){history.push({role,content:String(text).slice(0,2000)});history=history.slice(-10);sessionStorage.setItem(historyKey(),JSON.stringify(history));}
}
onForm('chatForm',async data=>{
  const prior=history.slice(-10);addMessage('user',data.message);
  document.querySelector('#message').value='';
  const result=await api('/chat',{method:'POST',headers:{'X-Request-Id':crypto.randomUUID()},body:{message:data.message,history:prior}});
  addMessage('assistant',result.reply);
  const action=result.pendingConfirmation;
  if(action?.action==='cancel_order'){
    const el=document.querySelector('#messages');el.append(button('确认取消订单 #'+action.orderSeq,async()=>{await api('/order/'+action.orderSeq+'/cancel',{method:'POST'});await refresh();}));
  }
  await refresh();
});
await initAuth(async()=>{
  const preferences=await api('/user/preferences');
  const form=document.querySelector('#preferences');
  for(const key of ['allergens','dislikes','dietaryGoal','budget'])form.elements[key].value=preferences[key]??'';
  const shops=await api('/merchants');
  selection.replaceChildren(...shops.map(s=>node('option',s.name,{value:s.id})));
  if(!shops.length){notice('暂无可用商户',true);return;}
  await selectShop();
});
