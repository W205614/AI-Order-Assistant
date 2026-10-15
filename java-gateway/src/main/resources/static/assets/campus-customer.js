import{api,state,onForm,notice,run,fmt}from './api.js';
import{initAuth}from './auth.js';
import{node,button,money,labels,select,field,card,form,watchEvents}from './commerce-ui.js';
const $=s=>document.querySelector(s);
let addresses=[],cart=[],coupons=[],quote,quoteKey,shops=[],menuRevision=0,shopRevision=0,quoteRevision=0,ordersRevision=0,orderPages=1,shopCampus=null;
const selections=new Map(),shopCoupons=new Map(),slots=new Map();
function invalidate(){quoteRevision++;quote=undefined;$('#quote').replaceChildren(node('p','购物车或结算条件变化后，请重新计算报价。'));}
function showMode(){const pickup=$('#mode').value==='PICKUP';$('#address').disabled=pickup;$('#modeHint').textContent=pickup?'自提无需收货地址，仅支持店铺所属校区；当前规则仍需店铺开放该校区服务，并满足该店起送门槛。':'一次结算使用同一收货地址和履约方式，配送费用按店铺分别计算。';}
function context(){return{campusId:Number($('#campus').value),addressId:$('#mode').value==='DELIVERY'&&$('#address').value?Number($('#address').value):null,fulfillmentMode:$('#mode').value};}
function couponChoices(merchant){return[['','不使用优惠券'],...coupons.filter(c=>c.status==='AVAILABLE'&&(merchant===null?c.merchant_id===null:Number(c.merchant_id)===Number(merchant))).map(c=>[c.id,c.name+' · 满'+c.threshold_amount+'减'+c.discount_amount])];}
function couponSelection(){return{shopCoupons:Object.fromEntries([...shopCoupons].filter(([m,id])=>selections.get(m)&&id)),platformCoupon:$('#platformCoupon').value?Number($('#platformCoupon').value):null};}
async function loadAddresses(){addresses=await api('/addresses');const old=$('#address').value;$('#address').replaceChildren(...addresses.filter(a=>a.campus_id===Number($('#campus').value)).map(a=>node('option',a.recipient_name+' · '+a.detail,{value:a.id})));if([...$('#address').options].some(o=>o.value===old))$('#address').value=old;$('#addresses').replaceChildren(...addresses.map(a=>{const p=node('p',a.recipient_name+' · '+a.detail);p.append(button('删除地址',async()=>{await api('/addresses/'+a.id,{method:'DELETE'});invalidate();await loadAddresses();}));return p;}));}
async function loadShops(){
  const revision=++shopRevision,campus=$('#campus').value,el=$('#campusMenu');
  menuRevision++;
  el.replaceChildren(node('p','正在加载该校区店铺与菜单…',{class:'muted'}));
  el.setAttribute('aria-busy','true');
  let result;
  try{result=await api('/campuses/'+campus+'/merchants');}
  catch(error){if(revision===shopRevision&&campus===$('#campus').value){el.replaceChildren(node('p','店铺加载失败，请点击刷新重试。',{class:'error'}));el.setAttribute('aria-busy','false');}throw error;}
  if(revision!==shopRevision||campus!==$('#campus').value)return;
  const selected=$('#shop').value;
  shops=result;
  shopCampus=campus;
  $('#shop').replaceChildren(...shops.map(s=>node('option',s.name+' · 配送'+money(s.fee),{value:s.id})));
  if(shops.some(s=>String(s.id)===selected))$('#shop').value=selected;
  await loadMenu();
}
async function loadMenu(){
  const revision=++menuRevision,campus=$('#campus').value,merchant=$('#shop').value,el=$('#campusMenu');
  el.replaceChildren(node('p','正在加载菜单…',{class:'muted'}));
  el.setAttribute('aria-busy','true');
  if(shopCampus!==campus)return;
  if(!merchant){el.replaceChildren(node('p','该校区暂无可服务店铺。'));el.setAttribute('aria-busy','false');return;}
  const current=()=>revision===menuRevision&&campus===$('#campus').value&&merchant===$('#shop').value;
  let result;
  try{result=await api('/campus-menu/'+merchant);}
  catch(error){if(current()){el.replaceChildren(node('p','菜单加载失败，请点击刷新重试。',{class:'error'}));el.setAttribute('aria-busy','false');}throw error;}
  if(!current())return;
  el.replaceChildren();
  el.setAttribute('aria-busy','false');
  for(const r of result){const p=r.product,c=node('article',undefined,{class:'menu-product'}),copy=node('div',undefined,{class:'product-copy'}),actions=node('div',undefined,{class:'product-actions'});copy.append(node('h3',p.name),node('p',money(p.price),{class:'product-price'}),node('p','包装 '+money(p.packagingFee)+' · 剩余 '+r.stock,{class:'product-meta'}));const save=button('收藏',async()=>{await api('/favorites/'+p.type+'/'+p.id,{method:'POST'});await loadFavorites();});save.className='save-button';actions.append(button('加入购物车',()=>add(p)),save);c.append(copy,actions);el.append(c);}
  if(!el.children.length)el.append(node('p','暂无安全可售商品。存在过敏约束时，请先在单店页面明确约束。',{class:'empty-state'}));
}
async function add(p){const old=cart.find(c=>c.product_type===p.type&&c.product_id===p.id);await api('/cart',{method:'PUT',body:{productType:p.type,productId:p.id,quantity:(old?.quantity??0)+1,remark:old?.remark??'',expectedVersion:old?.version??null}});invalidate();await loadCart();}
async function loadCart(){cart=await api('/cart');$('#campusCartCount').textContent=cart.reduce((n,c)=>n+c.quantity,0)+' 件';const el=$('#campusCart');el.replaceChildren();const groups=Map.groupBy(cart,c=>c.merchant_id);for(const [m,items]of groups){if(!selections.has(m))selections.set(m,true);const c=card(items[0].merchant_name),checked=node('input',undefined,{type:'checkbox'});checked.checked=selections.get(m);checked.dataset.merchant=String(m);checked.addEventListener('change',()=>{selections.set(m,checked.checked);invalidate();});c.append(field('本次结算',checked));for(const item of items){const p=node('div',undefined,{class:'cart-item'}),actions=node('span',undefined,{class:'quantity-actions'});p.append(node('span',item.product_name+' × '+item.quantity,{class:'cart-item-name'}));actions.append(button('减少',async()=>{if(item.quantity===1)await api('/cart/'+item.id+'?expectedVersion='+item.version,{method:'DELETE'});else await api('/cart',{method:'PUT',body:{productType:item.product_type,productId:item.product_id,quantity:item.quantity-1,remark:item.remark,expectedVersion:item.version}});invalidate();await loadCart();}),button('增加',()=>add({type:item.product_type,id:item.product_id})),button('移除',async()=>{await api('/cart/'+item.id+'?expectedVersion='+item.version,{method:'DELETE'});invalidate();await loadCart();}));p.append(actions);c.append(p);}
const coupon=select(couponChoices(m),shopCoupons.get(m)??'');coupon.addEventListener('change',()=>{shopCoupons.set(m,coupon.value?Number(coupon.value):null);invalidate();});c.append(field('店铺券',coupon));const times=await api('/reservations/'+m),slot=select([['','尽快送达 / 尽快自提'],...times.map(s=>[s.id,s.starts_at+' 至 '+s.ends_at+' · 余'+(s.capacity-s.reserved_count)])],slots.get(m)??'');slot.addEventListener('change',()=>{slots.set(m,slot.value?Number(slot.value):null);invalidate();});c.append(field('预约',slot));el.append(c);}if(!cart.length)el.append(node('p','购物车还是空的，先从左侧菜单选几样喜欢的。',{class:'empty-state'}));}
async function loadCoupons(){coupons=await api('/coupons/mine');const old=$('#platformCoupon').value;$('#platformCoupon').replaceChildren(...couponChoices(null).map(([v,t])=>node('option',t,{value:v})));if([...$('#platformCoupon').options].some(o=>o.value===old))$('#platformCoupon').value=old;const campaigns=await api('/coupons'),el=$('#couponList');el.replaceChildren();for(const c of campaigns){const owned=coupons.find(x=>x.campaign_id===c.id),box=card(c.name);box.append(node('p',(c.merchant_id?'店铺券':'平台券')+' · 满'+c.threshold_amount+'减'+c.discount_amount));if(owned)box.append(node('p',labels[owned.status]??owned.status));else box.append(button('领取',async()=>{await api('/coupons/'+c.id+'/claim',{method:'POST'});invalidate();await loadCoupons();await loadCart();}));el.append(box);}if(!campaigns.length)el.append(node('p','当前暂无可领取活动。新的优惠开放后会显示在这里。',{class:'empty-state'}));}
async function price(){const revision=quoteRevision;const merchantIds=[...new Set(cart.map(c=>c.merchant_id))].filter(m=>selections.get(m));if(!merchantIds.length)throw Error('请勾选需要结算的店铺');quote=await api('/checkouts/quotes',{method:'POST',body:{...context(),...couponSelection(),merchantIds,reservationSlots:Object.fromEntries([...slots].filter(([m,id])=>merchantIds.includes(m)&&id))}});if(revision!==quoteRevision){quote=undefined;throw Error('结算条件已变化，请重新报价');}renderQuote(quote);}
function renderQuote(current){quote=current;quoteKey=crypto.randomUUID();const el=$('#quote');el.replaceChildren(node('h3','请核对本次报价'),node('p','报价有效至 '+fmt(quote.expiresAt)+'，提交时会重新核验。',{class:'muted'}));for(const s of quote.shops){const box=node('div',undefined,{class:'quote-shop'});box.append(node('strong',s.merchantName),node('p',s.lines.map(l=>l.product.name+' × '+l.quantity).join('、'),{class:'muted'}));for(const [label,amount]of [['商品',s.goods],['包装费',s.packaging],['配送费',s.delivery],['店铺优惠',-s.shopDiscount],['平台优惠',-s.platformDiscount]]){const row=node('div',undefined,{class:'price-row'});row.append(node('span',label),node('span',money(amount)));box.append(row);}const total=node('div',undefined,{class:'price-row total-row'});total.append(node('span','本店实付'),node('span',money(s.payable)));box.append(total,node('p','预计履约区间 '+s.etaMin+'–'+s.etaMax+' 分钟',{class:'muted'}));el.append(box);}el.append(node('strong','本次合计 '+money(quote.total)));const failed=Object.entries(quote.unavailable);if(failed.length){for(const [m,reason]of failed)el.append(node('p',(shops.find(s=>s.id===Number(m))?.name??'所选店铺')+'：'+reason,{class:'error'}));el.append(button('仅选择可提交店铺，重新计算报价',async()=>{for(const [m]of failed){if(Number(m)===0){$('#platformCoupon').value='';continue;}selections.set(Number(m),false);shopCoupons.delete(Number(m));slots.delete(Number(m));}await loadCart();await price();}));}else if(quote.shops.length)el.append(button('确认本次报价并创建订单',async()=>{const current=quote;if(!current)throw Error('请重新计算报价');await api('/checkouts/confirm',{method:'POST',headers:{'Idempotency-Key':quoteKey},body:{quoteId:current.id,expectedVersion:current.version}});invalidate();await refresh();notice('订单已创建，请在十分钟内模拟支付。');}));}
function compatibleAssistantQuote(candidate) {
  if(!candidate?.request||!Array.isArray(candidate.shops))return false;
  const current=context(),requested=candidate.request;
  if(Number(requested.campusId)!==current.campusId||requested.fulfillmentMode!==current.fulfillmentMode||Number(requested.addressId??0)!==Number(current.addressId??0))return false;
  const expiry=String(candidate.expiresAt??'');
  const expiresAt=new Date(/[Zz]|[+-]\d\d:\d\d$/.test(expiry)?expiry:expiry.replace(' ','T')+'+08:00');
  if(!Number.isFinite(expiresAt.getTime())||expiresAt.getTime()<=Date.now())return false;
  for(const shop of candidate.shops){
    const items=cart.filter(item=>Number(item.merchant_id)===Number(shop.merchantId));
    if(items.length!==shop.lines.length)return false;
    for(const line of shop.lines){
      const item=items.find(item=>Number(item.id)===Number(line.cartId));
      if(!item||Number(item.version)!==Number(line.cartVersion)||Number(item.quantity)!==Number(line.quantity)||item.product_type!==line.product.type||Number(item.product_id)!==Number(line.product.id))return false;
    }
  }
  return true;
}
async function applyAssistantQuote(candidate,expectedRevision) {
  if(expectedRevision!==quoteRevision||!compatibleAssistantQuote(candidate))return false;
  const requested=candidate.request,selected=new Set(requested.merchantIds.map(Number));
  if(requested.platformCoupon&&!coupons.some(c=>Number(c.id)===Number(requested.platformCoupon)&&c.status==='AVAILABLE'&&c.merchant_id===null))return false;
  for(const [merchant,id]of Object.entries(requested.shopCoupons??{}))if(!coupons.some(c=>Number(c.id)===Number(id)&&Number(c.merchant_id)===Number(merchant)&&c.status==='AVAILABLE'))return false;
  for(const item of cart)selections.set(Number(item.merchant_id),selected.has(Number(item.merchant_id)));
  shopCoupons.clear();slots.clear();
  for(const [merchant,id]of Object.entries(requested.shopCoupons??{}))shopCoupons.set(Number(merchant),Number(id));
  for(const [merchant,id]of Object.entries(requested.reservationSlots??{}))slots.set(Number(merchant),Number(id));
  $('#platformCoupon').value=requested.platformCoupon?String(requested.platformCoupon):'';
  await loadCart();
  if(expectedRevision!==quoteRevision||!compatibleAssistantQuote(candidate))return false;
  renderQuote(candidate);
  return true;
}

async function loadOrders(){const revision=++ordersRevision,el=node('div');const checkouts=new Map();let more=false;for(let page=1;page<=orderPages;page++){const batch=await api('/checkouts?page='+page+'&pageSize=20');for(const c of batch)checkouts.set(c.id,c);more=batch.length===20;if(!more)break;}for(const c of checkouts.values()){const checkout=await api('/checkouts/'+c.id),box=card('结算 · '+(labels[c.status]??c.status)+' · '+money(c.total_amount)+' · 已退'+money(c.refunded_amount));box.dataset.checkoutId=c.id;if(c.status==='UNPAID')box.append(button('统一模拟支付',async()=>{await api('/checkouts/'+c.id+'/pay',{method:'POST'});await refresh();}),button('关闭未支付结算',async()=>{await api('/checkouts/'+c.id+'/close',{method:'POST'});await refresh();}));for(const o of checkout.orders){const order=card(o.merchant_name+' · '+(labels[o.fulfillment_status]??o.fulfillment_status));order.append(node('p',o.items.map(i=>i.dish_name+' × '+i.quantity).join('、')),node('p','实付'+money(o.total_amount)+' · 已退'+money(o.refunded_amount)));if(o.status===1&&!o.production_started)order.append(button('取消本店订单',async()=>{await api('/checkouts/orders/'+o.id+'/cancel',{method:'POST'});await refresh();}));if(o.status>=1&&o.status<=3)order.append(button('查看本人取餐码',async()=>{const code=await api('/checkouts/orders/'+o.id+'/code');notice('取餐码：'+code+'，请仅在取餐或确认送达时提供。');}));order.append(button('再来一单',async()=>{await api('/cart/reorder/'+o.id,{method:'POST'});invalidate();await loadCart();}));
if(o.status>0&&o.status<5){const refund=form([...o.items.filter(i=>i.refundable_quantity>0).map(i=>['item'+i.id,'退款数量：'+i.dish_name+'（最多'+i.refundable_quantity+'）','number','0']),['reason','退款原因','text','']],'提交按数量退款',async d=>{const items=o.items.map(i=>({orderItemId:i.id,quantity:Number(d['item'+i.id]??0)})).filter(i=>i.quantity>0);await api('/aftersales',{method:'POST',body:{orderId:o.id,items,reason:d.reason,requestKey:crypto.randomUUID()}});await refresh();});order.append(refund);}
for(const a of o.aftersales){order.append(node('p','售后 '+(labels[a.status]??a.status)+' · '+money(a.amount)+' · '+(a.decision_reason??a.reason)));if(a.status==='REJECTED')order.append(form([['reason','申诉原因','text','']],'提交平台申诉',async d=>{await api('/aftersales/'+a.id+'/dispute',{method:'POST',body:d});await loadOrders();}));}order.append(form([['message','意见反馈','text','']],'提交反馈',async d=>{await api('/feedback',{method:'POST',body:{orderId:o.id,message:d.message}});await loadFeedback();}));box.append(order);}el.append(box);}if(!el.children.length)el.append(node('p','还没有结算订单。选好商品、核对报价后，你的订单会显示在这里。',{class:'empty-state'}));if(more)el.append(button('加载更多结算',async()=>{orderPages++;try{await loadOrders();}catch(e){orderPages--;throw e;}}));if(revision===ordersRevision)$('#checkoutList').replaceChildren(...el.childNodes);}
async function loadFavorites(){const el=$('#favoriteList');el.replaceChildren();const favorites=await api('/favorites');if(!favorites.length)el.append(node('p','在菜单中收藏喜欢的商品，下次更快找到。',{class:'empty-state'}));for(const f of favorites){const products=await api('/campus-assistant/search?campusId='+$('#campus').value+'&keyword=');const p=products.find(p=>p.type===f.product_type&&p.id===f.product_id),c=node('p',p?.name??'收藏商品当前不可售或不在本校区服务范围');if(p)c.append(button('加入购物车',()=>add(p)));c.append(button('取消收藏',async()=>{await api('/favorites/'+f.product_type+'/'+f.product_id,{method:'DELETE'});await loadFavorites();}));el.append(c);}}
async function loadFeedback(){const el=$('#feedbackList'),rows=await api('/feedback');el.replaceChildren(...rows.map(f=>node('p',f.message+' · '+(f.reply??'等待回复'))));if(!rows.length)el.append(node('p','暂无反馈记录。订单内可以提交反馈并查看回复。',{class:'empty-state'}));}
async function refresh(){await loadAddresses();await loadCoupons();await loadCart();if(shopCampus!==$('#campus').value)await loadShops();else await loadMenu();await loadOrders();await loadFavorites();await loadFeedback();}
$('#quoteButton').addEventListener('click',()=>run(price,$('#quoteButton')));$('#refresh').addEventListener('click',()=>run(refresh));$('#shop').addEventListener('change',()=>run(loadMenu));$('#mode').addEventListener('change',showMode);for(const id of ['address','mode','platformCoupon'])$('#'+id).addEventListener('change',invalidate);$('#campus').addEventListener('change',()=>run(async()=>{invalidate();shopRevision++;menuRevision++;shopCampus=null;$('#campusMenu').replaceChildren(node('p','正在切换校区…',{class:'muted'}));$('#campusMenu').setAttribute('aria-busy','true');await loadAddresses();await loadShops();await loadFavorites();}));
onForm('addressForm',async d=>{await api('/addresses',{method:'POST',body:{...d,campusId:context().campusId}});invalidate();await loadAddresses();});
onForm('campusChat',async d=>{
  const el=$('#campusMessages'),revision=quoteRevision;
  el.append(node('p',d.message,{class:'message message-user'}));
  const pending=node('p','正在查询菜单与点餐信息…',{class:'message message-pending'});
  el.append(pending);el.setAttribute('aria-busy','true');el.scrollTop=el.scrollHeight;
  try{
    const r=await api('/campus-assistant/chat',{method:'POST',body:{...context(),message:d.message}});
    el.append(node('p',r.reply,{class:'message message-assistant'}));
    if(r.contextApplied)el.append(node('p','已参考同一校区、地址与履约方式的近期对话。',{class:'conversation-context'}));
    const changed=revision!==quoteRevision;
    invalidate();const responseRevision=quoteRevision;await loadCoupons();await loadCart();
    if(r.quote&&!changed&&responseRevision===quoteRevision&&await applyAssistantQuote(r.quote,responseRevision))notice('助手已准备实际报价，已同步所选店铺与优惠。请核对账单后自行确认。');
    else if(r.quote)notice('购物车或收货条件已变化，助手报价未应用。请重新计算当前报价。');
  }finally{pending.remove();el.removeAttribute('aria-busy');el.scrollTop=el.scrollHeight;}
});
onForm('recommendForm',async d=>{const r=await api('/campus-assistant/recommend',{method:'POST',body:{...context(),...couponSelection(),budget:Number(d.budget),keywords:d.keywords.split(/[,，]/).map(s=>s.trim()).filter(Boolean)}});const el=$('#recommendations');el.replaceChildren(node('p',r.message));for(const q of r.candidates){const c=card('组合合计 '+money(q.total));for(const s of q.shops)c.append(node('p',s.merchantName+'：'+s.lines.map(l=>l.product.name).join('、')+' · '+money(s.payable)));c.append(button('将本组合加入购物车',async()=>{for(const s of q.shops)for(const l of s.lines)await add(l.product);invalidate();notice('组合已加入，请核对购物车并重新计算报价。');}));el.append(c);}});
initAuth(async()=>{const campuses=await api('/campuses');$('#campus').replaceChildren(...campuses.map(c=>node('option',c.name,{value:c.id})));await loadShops();await refresh();watchEvents('/me/events','campus-events:'+state.me.userId,async()=>{if($('#checkoutList').contains(document.activeElement))return false;await loadOrders();return true;}).catch(()=>{});});
