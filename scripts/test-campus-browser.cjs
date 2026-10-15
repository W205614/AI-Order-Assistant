// Four-role campus acceptance. Fixtures are restricted to disposable local test ports.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const options=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>{if(v.startsWith('--'))a.push([v.slice(2),all[i+1]]);return a;},[]));
const base=options['base-url']||'http://127.0.0.1:19090';
const url=new URL(base);
if(!['127.0.0.1','localhost'].includes(url.hostname)||!['19090','19092'].includes(url.port))throw Error('Disposable local test ports only');
const {chromium}=require(options['playwright-module']||'playwright');
const env=Object.fromEntries(fs.readFileSync(options['env-file']||'.github/compose-ci.env','utf8').split(/\r?\n/).filter(l=>l&&!l.startsWith('#')&&l.includes('=')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
const output=options.report||'load/results/campus-browser.json',checks=[],errors=[];
function check(name,test){assert.ok(test,name);checks.push(name);console.log('Passed: '+name);}
async function request(context,route,method='GET',data,management=false,expected=200){
  const headers=management?{'X-Session-Type':'management'}:{};
  if(route==='/checkouts/confirm')headers['Idempotency-Key']='browser-'+require('node:crypto').randomUUID();
  if(method!=='GET'){const r=await context.request.get(base+'/auth/csrf');headers['X-XSRF-TOKEN']=(await r.json()).data.token;}
  const response=await context.request.fetch(base+route,{method,data,headers});const body=await response.json();assert.equal(response.status(),expected,route+': '+body.msg);if(expected===200){assert.equal(body.code,1,route+': '+body.msg);return body.data;}return body;
}
async function clickMutation(page,locator,route,method='POST'){
  const [r]=await Promise.all([page.waitForResponse(r=>r.url().includes(route)&&r.request().method()===method),locator.click()]);const body=await r.json();assert.equal(r.status(),200,route+': '+body.msg);assert.equal(body.code,1);return body.data;
}
async function open(context,route){const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(base+route);await page.locator('#workspace').waitFor({state:'visible'});return page;}
const local=minutes=>new Date(Date.now()+(minutes+480)*60000).toISOString().slice(0,19);
(async()=>{const browser=await chromium.launch({headless:true,...(options['browser-path']?{executablePath:options['browser-path']}:{})});try{
  const suffix=Date.now().toString(36),password='Campus-browser-'+suffix;
  const platform=await browser.newContext();await request(platform,'/admin/login','POST',{username:env.PLATFORM_ADMIN_USERNAME,password:env.PLATFORM_ADMIN_PASSWORD},true);
  const east=await request(platform,'/platform/campuses','POST',{name:'验收东校区'+suffix},true);
  const stores=[],owners=[],products=[];
  for(let i=0;i<3;i++){
    const m=await request(platform,'/platform/merchants','POST',{name:'校园店'+i+' '+suffix,ownerUsername:'campus_owner'+i+'_'+suffix,ownerPassword:password},true);stores.push(m);
    const owner=await browser.newContext();await request(owner,'/admin/login','POST',{username:'campus_owner'+i+'_'+suffix,password},true);owners.push(owner);
    await request(owner,'/admin/delivery-rules','PUT',{campusId:east,enabled:i!==2,fee:i===0?3:2,minimumAmount:0,etaMin:20,etaMax:60,expectedVersion:0},true);
    await request(owner,'/admin/dishes','POST',{name:['米饭','奶茶','面条'][i]+suffix,category:'验收',price:[10,15,8][i],stock:20,status:1,allergens:'',allergenReviewed:true},true);
  }
  const customer=await browser.newContext();await request(customer,'/auth/register','POST',{username:'campus_customer_'+suffix,password,nickname:'校园验收'});
  const address=await request(customer,'/addresses','POST',{campusId:east,recipientName:'验收同学',phone:'13800000000',detail:'测试楼'});
  for(const m of stores)products.push((await request(customer,'/campus-menu/'+m.id))[0].product);
  const campaign=(name)=>({name,thresholdAmount:20,discountAmount:3,totalCount:10,claimStart:local(-60),claimEnd:local(60),validStart:local(-60),validEnd:local(120)});
  const shopCampaign=await request(owners[0],'/admin/coupons','POST',campaign('店铺满减'+suffix),true);
  const platformCampaign=await request(platform,'/platform/coupons','POST',{...campaign('平台满减'+suffix),discountAmount:5},true);
  for(let i=0;i<2;i++)await request(platform,'/platform/riders','POST',{username:'campus_rider'+i+'_'+suffix,password,campusIds:[east]},true);
  check('two_campuses_three_shops_two_riders_configured',east>1&&stores.length===3);
  const page=await open(customer,'/campus/');await page.locator('#campus').selectOption(String(east));await page.locator('#shop option').filter({hasText:stores[0].name}).waitFor({state:'attached'});
  for(let i=0;i<2;i++){await page.locator('#shop').selectOption(String(stores[i].id));await page.locator('#campusMenu h3').filter({hasText:products[i].name}).waitFor();await clickMutation(page,page.locator('#campusMenu').getByRole('button',{name:'加入购物车'}),'/cart','PUT');await page.locator('#campusCart').getByRole('heading',{name:stores[i].name,exact:true}).waitFor();}
  let cart=await request(customer,'/cart');await request(customer,'/cart','PUT',{productType:products[0].type,productId:products[0].id,quantity:3,remark:'',expectedVersion:cart.find(c=>c.merchant_id===stores[0].id).version});
  await request(customer,'/cart','PUT',{productType:products[2].type,productId:products[2].id,quantity:1,remark:''});
  await page.reload();await page.locator('#workspace').waitFor({state:'visible'});await page.locator('#campus').selectOption(String(east));await page.locator('#address').selectOption(String(address));
  check('cart_survives_switching_and_reload',(await request(customer,'/cart')).length===3);
  await clickMutation(page,page.locator('#couponList article').filter({hasText:'店铺满减'+suffix}).getByRole('button',{name:'领取'}),'/coupons/'+shopCampaign+'/claim');
  await clickMutation(page,page.locator('#couponList article').filter({hasText:'平台满减'+suffix}).getByRole('button',{name:'领取'}),'/coupons/'+platformCampaign+'/claim');
  const owned=await request(customer,'/coupons/mine'),shopCoupon=owned.find(c=>c.campaign_id===shopCampaign),platformCoupon=owned.find(c=>c.campaign_id===platformCampaign);
  await page.locator('#campusCart article').filter({has:page.getByRole('heading',{name:stores[0].name,exact:true})}).locator('select').first().selectOption(String(shopCoupon.id));await page.locator('#platformCoupon').selectOption(String(platformCoupon.id));
  let quote=await clickMutation(page,page.locator('#quoteButton'),'/checkouts/quotes');check('unavailable_shop_is_reported',Object.keys(quote.unavailable).includes(String(stores[2].id)));
  quote=await clickMutation(page,page.getByRole('button',{name:'仅选择可提交店铺，重新计算报价'}),'/checkouts/quotes');check('selected_subset_is_requoted_with_stacked_coupons',quote.shops.length===2&&Number(quote.total)===42);
  const checkout=await clickMutation(page,page.getByRole('button',{name:'确认本次报价并创建订单'}),'/checkouts/confirm');await page.getByRole('button',{name:'统一模拟支付'}).waitFor();await clickMutation(page,page.getByRole('button',{name:'统一模拟支付'}),'/checkouts/'+checkout.id+'/pay');
  check('unavailable_shop_remains_in_cart',(await request(customer,'/cart')).some(c=>c.merchant_id===stores[2].id));
  const detail=await request(customer,'/checkouts/'+checkout.id),order=detail.orders.find(o=>o.merchant_id===stores[0].id),order2=detail.orders.find(o=>o.merchant_id===stores[1].id);
  const ownerPage=await open(owners[0],'/admin/');
  for(const [action,title]of [['ACCEPT','确认接单'],['START','开始制作'],['READY','备餐完成']])await clickMutation(ownerPage,ownerPage.locator('#commerceManagement').getByRole('button',{name:title,exact:true}),'/admin/commerce-orders/'+order.id+'/transition');
  await request(customer,'/aftersales','POST',{orderId:order.id,items:[{orderItemId:order.items[0].id,quantity:1}],reason:'质量反馈',requestKey:'browser-refund-'+suffix});
  await ownerPage.locator('#commerceManagement').getByRole('button',{name:'刷新校园业务'}).click();const approval=ownerPage.locator('#commerceManagement form').filter({has:ownerPage.getByRole('button',{name:'同意退款',exact:true})});await approval.locator('input').fill('审核同意');await clickMutation(ownerPage,approval.getByRole('button',{name:'同意退款',exact:true}),'/decision');check('quantity_refund_is_approved',(await request(customer,'/aftersales'))[0].status==='APPROVED');
  await page.locator('#checkoutList input[name=reason]').first().focus();
  const rider=await browser.newContext();await request(rider,'/admin/login','POST',{username:'campus_rider0_'+suffix,password},true);const riderPage=await open(rider,'/rider/');await clickMutation(riderPage,riderPage.getByRole('button',{name:'抢单',exact:true}),'/claim');await clickMutation(riderPage,riderPage.getByRole('button',{name:'已取餐',exact:true}),'/transition');const code=await request(customer,'/checkouts/orders/'+order.id+'/code');await riderPage.locator('input[name=code]').fill(code);await clickMutation(riderPage,riderPage.getByRole('button',{name:'核验送达',exact:true}),'/transition');check('rider_delivery_completes_with_customer_code',(await request(customer,'/checkouts/'+checkout.id)).orders.find(o=>o.id===order.id).fulfillment_status==='COMPLETED');
  await request(customer,'/checkouts/orders/'+order2.id+'/cancel','POST');check('another_shop_can_cancel_independently',(await request(customer,'/checkouts/'+checkout.id)).orders.find(o=>o.id===order2.id).status===5);
  const favorite=products[0];await request(customer,'/favorites/'+favorite.type+'/'+favorite.id,'POST');await request(customer,'/cart/reorder/'+order.id,'POST');await request(customer,'/feedback','POST',{orderId:order.id,message:'配送体验反馈'});const replay=await request(customer,'/me/events/replay');check('favorites_reorder_feedback_and_event_replay',replay.length>0&&(await request(customer,'/favorites')).length===1);
  const now=Date.now(),start=new Date(now+20*60000+8*3600000).toISOString().slice(0,19),end=new Date(now+40*60000+8*3600000).toISOString().slice(0,19),cutoff=new Date(now+10*60000+8*3600000).toISOString().slice(0,19);
  const slot=await request(owners[0],'/admin/reservations','POST',{startsAt:start,endsAt:end,cutoffAt:cutoff,prepareMinutes:30,capacity:1},true);
  const pickup=await request(customer,'/checkouts/quotes','POST',{merchantIds:[stores[0].id],campusId:1,fulfillmentMode:'PICKUP',reservationSlots:{[stores[0].id]:slot}});assert.equal(pickup.shops[0].delivery,0);const p=await request(customer,'/checkouts/confirm','POST',{quoteId:pickup.id,expectedVersion:1});await request(customer,'/checkouts/'+p.id+'/pay','POST');
  const po=p.orders[0];for(const action of ['ACCEPT','START','READY'])await request(owners[0],'/admin/commerce-orders/'+po.id+'/transition','POST',{action},true);const pickupCode=await request(customer,'/checkouts/orders/'+po.id+'/code');await request(owners[0],'/admin/commerce-orders/'+po.id+'/transition','POST',{action:'PICKUP',code:pickupCode},true);
  check('reservation_and_pickup_quote_has_no_delivery_fee',pickup.shops[0].slotId===slot);
  check('reserved_pickup_completes_with_one_time_code',(await request(customer,'/checkouts/'+p.id)).orders[0].fulfillment_status==='COMPLETED');
  const platformPage=await open(platform,'/platform/');await platformPage.locator('#commerceManagement').getByRole('heading',{name:'平台骑手',exact:true}).waitFor();check('all_four_role_pages_render_without_script_errors',errors.length===0);
  await page.locator('#refresh').focus();
  const completed=page.locator('#checkoutList h3').filter({hasText:stores[0].name+' · 已完成'});await completed.nth(1).waitFor();check('customer_receives_committed_delivery_event',await completed.count()===2);
  fs.mkdirSync(path.dirname(output),{recursive:true});await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(path.dirname(output),'campus-customer-mobile.png'),fullPage:true});await platformPage.screenshot({path:path.join(path.dirname(output),'campus-platform.png'),fullPage:true});await riderPage.screenshot({path:path.join(path.dirname(output),'campus-rider.png'),fullPage:true});
  fs.writeFileSync(output,JSON.stringify({checks,errors,passed:true},null,2));
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
