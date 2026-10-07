// Creates disposable fixtures through the real UI; intentionally restricted to test ports.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const options=Object.fromEntries(process.argv.slice(2).reduce((pairs,item,index,args)=>{
  if(item.startsWith('--'))pairs.push([item.slice(2),args[index+1]]);return pairs;
},[]));
const base=options['base-url']||'http://127.0.0.1:19092';
const url=new URL(base);
if(!['127.0.0.1','localhost'].includes(url.hostname)||!['19090','19092'].includes(url.port))throw Error('Only disposable local test ports 19090/19092 are allowed');
const {chromium}=require(options['playwright-module']||'playwright');
const env=Object.fromEntries(fs.readFileSync(options['env-file']||'.github/compose-ci.env','utf8').replace(/^\uFEFF/,'').split(/\r?\n/).filter(l=>l&&!l.startsWith('#')&&l.includes('=')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
const output=options.report||'load/results/browser-full.json';
const checks=[],errors=[];
function check(name,condition){assert.ok(condition,name);checks.push(name);}
async function mutation(page,action,route,method='POST',status=200){
  const response=page.waitForResponse(r=>r.url().includes(route)&&r.request().method()===method&&r.status()===status);
  await action();const result=await (await response).json();if(status===200)assert.equal(result.code,1);return result.data;
}
async function fill(page,id,values){for(const [name,value]of Object.entries(values))await page.locator('#'+id+' [name='+name+']').fill(String(value));}
(async()=>{
  const browser=await chromium.launch({headless:true,...(options['browser-path']?{executablePath:options['browser-path']}:{} )});
  try{
    const newPage=async()=>{const context=await browser.newContext();const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));return page;};
    const login=async(page,route,username,password)=>{await page.goto(base+route);await fill(page,'login',{username,password});await page.locator('#login button').click();await page.locator('#workspace').waitFor({state:'visible'});};
    const suffix=Date.now().toString(36),password='Browser-fixture-'+suffix+'-2026';
    const shopName='浏览器验收店 '+suffix;
    const platform=await newPage();await login(platform,'/platform/',env.PLATFORM_ADMIN_USERNAME,env.PLATFORM_ADMIN_PASSWORD);
    await fill(platform,'provision',{name:shopName,ownerUsername:'owner_'+suffix,ownerPassword:password});
    const shop=await mutation(platform,()=>platform.locator('#provision button').click(),'/platform/merchants');
    await platform.locator('#merchants article').filter({hasText:shopName}).waitFor();check('platform_provisions_merchant_through_ui',shop.id>1);
    const owner=await newPage();await login(owner,'/admin/','owner_'+suffix,password);
    await owner.getByText('暂无符合条件的订单。新订单会自动提醒。',{exact:true}).waitFor();
    await fill(owner,'settings',{name:shopName,opensAt:'00:00',closesAt:'00:00',regions:'演示区域'});await owner.locator('#accepting').check();
    await mutation(owner,()=>owner.locator('#settings button').click(),'/admin/merchant','PUT');
    check('owner_sets_hours_and_delivery_region',await owner.locator('#settings [name=regions]').inputValue()==='演示区域');
    await fill(owner,'dishForm',{name:'浏览器验收套餐',price:20,category:'套餐',description:'本次页面验收使用',allergens:'',stock:12});await owner.locator('#dishReviewed').check();
    await mutation(owner,()=>owner.locator('#dishForm button:not([type="button"])').click(),'/admin/dishes');
    const dishRow=owner.locator('#menu article').filter({has:owner.locator('h3',{hasText:'浏览器验收套餐'})});
    await dishRow.getByText(/库存 12/).waitFor();await dishRow.locator('input[type=number]').fill('3');
    await mutation(owner,()=>dishRow.getByRole('button',{name:'调整库存'}).click(),'/inventory');await dishRow.getByText(/库存 15/).waitFor();
    await mutation(owner,()=>dishRow.getByRole('button',{name:'停售',exact:true}).click(),'/status','PUT');await dishRow.getByRole('button',{name:'上架',exact:true}).waitFor();
    await mutation(owner,()=>dishRow.getByRole('button',{name:'上架',exact:true}).click(),'/status','PUT');await dishRow.getByRole('button',{name:'停售',exact:true}).waitFor();check('owner_creates_dish_adjusts_stock_and_toggles_sale',true);
    await fill(owner,'staffForm',{username:'staff_'+suffix,password});await mutation(owner,()=>owner.locator('#staffForm button').click(),'/admin/staff');
    const staff=await newPage();await login(staff,'/admin/','staff_'+suffix,password);
    check('staff_ui_only_exposes_order_operations',await staff.locator('[data-owner]:visible').count()===0);
    const customer=await newPage();await customer.goto(base+'/chat/');await customer.locator('#auth details').evaluate(e=>e.open=true);
    await fill(customer,'register',{username:'customer_'+suffix,password,nickname:'页面验收'});await mutation(customer,()=>customer.locator('#register button').click(),'/auth/register');
    await customer.locator('#merchant option').filter({hasText:shopName}).waitFor({state:'attached'});await customer.locator('#merchant').selectOption(String(shop.id));
    await customer.locator('#menu h3').filter({hasText:'浏览器验收套餐'}).waitFor();check('customer_selects_merchant_and_sees_its_menu',await customer.locator('#menu article').count()===1);
    await customer.locator('#preferences').evaluate(e=>e.parentElement.open=true);await fill(customer,'preferences',{dislikes:'香菜',dietaryGoal:'均衡',budget:50});
    await mutation(customer,()=>customer.locator('#preferences button').click(),'/user/preferences','PUT');
    await customer.locator('#allergy input[value=鸡蛋]').check();await mutation(customer,()=>customer.locator('#allergy button').click(),'/order/safety-context','PUT');
    await customer.locator('#safetyState').filter({hasText:'当前商户约束：鸡蛋'}).waitFor();await customer.locator('#merchant').selectOption('1');
    await customer.locator('#safetyState').filter({hasText:'未设置临时约束'}).waitFor();check('switching_merchant_clears_other_shop_allergy_ui',!await customer.locator('#allergy input[value=鸡蛋]').isChecked());
    await customer.locator('#merchant').selectOption(String(shop.id));await customer.locator('#safetyState').filter({hasText:'当前商户约束：鸡蛋'}).waitFor();
    check('switching_back_restores_its_allergy_ui',await customer.locator('#allergy input[value=鸡蛋]').isChecked());
    await mutation(customer,()=>customer.locator('#clearSafety').click(),'/order/safety-context','DELETE');await customer.locator('#safetyState').filter({hasText:'未设置临时约束'}).waitFor();
    const add=()=>customer.locator('#menu').getByRole('button',{name:'加入购物车'}).click();
    await add();await add();check('checkout_link_displays_cart_count',await customer.locator('#cartCount').textContent()==='2');await customer.locator('#cart').getByRole('button',{name:'减少'}).click();
    await mutation(customer,()=>customer.locator('#cartForm button').click(),'/order/drafts');await customer.locator('#draft h3').waitFor();
    let confirmationRequests=0;customer.on('request',r=>{if(r.url().endsWith('/confirm'))confirmationRequests++;});
    await customer.getByRole('button',{name:'确认并进入模拟支付'}).click();await customer.locator('#notice').filter({hasText:'请补全收货信息'}).waitFor();
    check('incomplete_receipt_is_blocked_before_http_confirmation',confirmationRequests===0);
    await customer.getByRole('button',{name:'修改数量与备注'}).click();await add();await customer.locator('#remark').fill('打包');
    await mutation(customer,()=>customer.locator('#cartForm button').click(),'/order/drafts/','PUT');await customer.locator('#draft').getByText(/× 2/).waitFor();check('customer_modifies_draft_quantity_and_remark',true);
    await fill(customer,'receipt',{recipientName:'页面验收',recipientPhone:'13800000000',deliveryAddress:'演示楼 101'});
    await dishRow.getByRole('button',{name:'编辑',exact:true}).click();check('editing_dish_cannot_overwrite_stock_field',await owner.locator('#dishForm [name=stock]').evaluate(e=>e.readOnly));
    await owner.locator('#dishForm [name=price]').fill('22');await mutation(owner,()=>owner.locator('#dishForm button:not([type="button"])').click(),'/admin/dishes/','PUT');await dishRow.getByText(/¥22/).waitFor();
    await mutation(customer,()=>customer.getByRole('button',{name:'确认并进入模拟支付'}).click(),'/confirm','POST',409);
    await customer.locator('#notice.error').filter({hasText:'请核对并再次点击'}).waitFor();await customer.locator('#draft').getByText(/合计 ¥44/).waitFor();check('price_change_shows_requote_without_success_notice',true);
    const order=await mutation(customer,()=>customer.getByRole('button',{name:'确认并进入模拟支付'}).click(),'/confirm');
    const customerOrder=(value)=>customer.locator('#orders article').filter({has:customer.locator('h3',{hasText:new RegExp('^#'+value+' ·')})});
    let row=customerOrder(order.userSeq);await row.getByRole('button',{name:'模拟支付',exact:true}).click();await row.getByText(/已模拟支付/).waitFor();
    await staff.locator('#refresh').click();const staffOrder=staff.locator('#orders article').filter({has:staff.locator('h3',{hasText:new RegExp('^#'+order.id+' ·')})});
    for(const [label,next]of [['开始制作','制作中'],['开始配送','配送中'],['完成','完成']]){await mutation(staff,()=>staffOrder.getByRole('button',{name:label,exact:true}).click(),'/status');await staffOrder.locator('h3').filter({hasText:next}).waitFor();if(next==='制作中'){await customer.locator('#refresh').click();await row.locator('h3').filter({hasText:'制作中'}).waitFor();check('customer_cannot_cancel_after_preparation',await row.getByRole('button',{name:'取消订单'}).count()===0);}}
    await customer.locator('#refresh').click();await row.locator('h3').filter({hasText:'完成'}).waitFor();check('staff_completes_preparation_delivery_and_order',true);
    await owner.locator('#refresh').click();await owner.locator('#stats').filter({hasText:'¥44'}).waitFor();check('owner_statistics_include_completed_simulated_amount',true);
    await add();await mutation(customer,()=>customer.locator('#cartForm button').click(),'/order/drafts');const cancelled=await mutation(customer,()=>customer.getByRole('button',{name:'确认并进入模拟支付'}).click(),'/confirm');
    row=customerOrder(cancelled.userSeq);await row.getByRole('button',{name:'模拟支付',exact:true}).click();await row.getByText(/已模拟支付/).waitFor();await row.getByRole('button',{name:'取消订单',exact:true}).click();await row.getByText(/已模拟退款/).waitFor();
    check('customer_cancels_paid_order_with_readable_refund',!/SIMULATED_|CUSTOMER/.test(await customer.locator('#workspace').innerText()));
    await owner.locator('#refresh').click();await dishRow.getByText(/库存 13/).waitFor();check('paid_cancellation_restores_only_reserved_stock',true);
    check('empty_next_page_is_disabled',await customer.locator('#next').isDisabled());
    await customer.setViewportSize({width:390,height:844});await customer.locator('.checkout-link').click();
    check('mobile_page_has_no_horizontal_overflow',await customer.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    const screenshotDir=options['screenshot-dir'];if(screenshotDir){fs.mkdirSync(screenshotDir,{recursive:true});await customer.screenshot({path:path.join(screenshotDir,'customer-mobile.png'),fullPage:true});await customer.setViewportSize({width:1280,height:900});await customer.screenshot({path:path.join(screenshotDir,'customer.png'),fullPage:true});await owner.screenshot({path:path.join(screenshotDir,'owner.png'),fullPage:true});await staff.screenshot({path:path.join(screenshotDir,'staff.png'),fullPage:true});await platform.screenshot({path:path.join(screenshotDir,'platform.png'),fullPage:true});}
    const staffRow=owner.locator('#staff p').filter({hasText:'staff_'+suffix});await staffRow.getByRole('button',{name:'停用',exact:true}).click();await staffRow.getByRole('button',{name:'启用',exact:true}).waitFor();
    await staff.locator('#refresh').click();await staff.locator('#auth').waitFor({state:'visible'});check('disabled_staff_session_returns_to_login',!await staff.locator('#workspace').isVisible());
    await platform.locator('#merchants article').filter({hasText:shopName}).getByRole('button',{name:'停用商户'}).click();await platform.locator('#merchants article').filter({hasText:shopName}).getByText('已停用',{exact:true}).waitFor();
    await customer.locator('#refresh').click();await customer.locator('#notice.error').waitFor();check('disabled_merchant_rejects_customer_refresh',true);
    check('no_browser_javascript_errors',errors.length===0);
    const result={passed:true,baseUrl:base,browser:'Chromium'+(options['browser-path']?' (configured executable)':''),checks,pageErrors:errors};fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(result,null,2));console.log(JSON.stringify({passed:true,checks:checks.length,pageErrors:errors.length}));
  }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});

