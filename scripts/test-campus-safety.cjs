// Allergy controls and stale-response protection against a disposable deployment.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const options=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>{if(v.startsWith('--'))a.push([v.slice(2),all[i+1]]);return a;},[]));
const base=options['base-url']||'http://127.0.0.1:19090',target=new URL(base);
assert.ok(['127.0.0.1','localhost'].includes(target.hostname)&&['19090','19092'].includes(target.port),'Disposable deployment only');
const {chromium}=require(options['playwright-module']||'playwright');
const env=Object.fromEntries(fs.readFileSync(options['env-file']||'.github/compose-ci.env','utf8').split(/\r?\n/).filter(l=>l&&!l.startsWith('#')&&l.includes('=')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
const output=options.report||'load/results/campus-safety.json',checks=[],errors=[];
function check(name,condition){assert.ok(condition,name);checks.push(name);console.log('Passed: '+name);}
async function api(context,route,method='GET',data,merchant,management=false){
  const headers={...(merchant?{'X-Merchant-Id':String(merchant)}:{}),...(management?{'X-Session-Type':'management'}:{})};
  if(method!=='GET'){const r=await context.request.get(base+'/auth/csrf');headers['X-XSRF-TOKEN']=(await r.json()).data.token;}
  const r=await context.request.fetch(base+route,{method,data,headers}),body=await r.json();
  assert.equal(r.status(),200,route+': '+body.msg);assert.equal(body.code,1,route+': '+body.msg);return body.data;
}
async function mutate(page,button,method){
  const [r]=await Promise.all([page.waitForResponse(r=>r.url().endsWith('/order/safety-context')&&r.request().method()===method),button.click()]);
  assert.equal(r.status(),200);assert.equal((await r.json()).code,1);
  await page.locator('#campusSafety:not([disabled])').waitFor();
}
(async()=>{
  let browser,passed=false;
  try{
    browser=await chromium.launch({headless:true,...(options['browser-path']?{executablePath:options['browser-path']}:{})});
    const stamp=Date.now().toString(36),password='Campus-safety-'+stamp,platform=await browser.newContext();
    await api(platform,'/admin/login','POST',{username:env.PLATFORM_ADMIN_USERNAME,password:env.PLATFORM_ADMIN_PASSWORD},null,true);
    const campus=await api(platform,'/platform/campuses','POST',{name:'安全验收校区'+stamp},null,true),shops=[];
    for(let i=0;i<2;i++){
      const m=await api(platform,'/platform/merchants','POST',{name:'安全店'+i+stamp,ownerUsername:'safety_owner'+i+stamp,ownerPassword:password},null,true);shops.push(m);
      const owner=await browser.newContext();await api(owner,'/admin/login','POST',{username:'safety_owner'+i+stamp,password},null,true);
      await api(owner,'/admin/delivery-rules','PUT',{campusId:campus,enabled:true,fee:0,minimumAmount:0,etaMin:20,etaMax:40,expectedVersion:0},null,true);
      for(const [name,allergens,reviewed]of [['清爽米饭','',true],['花生饭','花生',true],['鸡蛋饭','鸡蛋',true],['面条','麸质',true],['未核验菜','',false]])
        await api(owner,'/admin/dishes','POST',{name:name+stamp,category:'安全测试',price:10,stock:20,status:1,allergens,allergenReviewed:reviewed},null,true);
    }
    const customer=await browser.newContext();await api(customer,'/auth/register','POST',{username:'safety_customer'+stamp,password});
    await api(customer,'/addresses','POST',{campusId:campus,recipientName:'验收同学',phone:'13800000000',detail:'测试楼'});
    const page=await customer.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(base+'/campus/');await page.locator('#workspace').waitFor({state:'visible'});
    await page.locator('#campus').selectOption(String(campus));await page.locator('#shop option[value="'+shops[0].id+'"]').waitFor({state:'attached'});
    await page.locator('#shop').selectOption(String(shops[0].id));
    await page.locator('#campusSafety:not([disabled])').waitFor({timeout:5000});
    check('campus_has_accessible_per_shop_allergy_controls',await page.getByRole('group',{name:'当前店铺临时过敏约束'}).isVisible()&&await page.locator('#campusSafety input[type=checkbox]').count()===3);
    await page.getByLabel('花生',{exact:true}).check();await mutate(page,page.locator('#saveCampusSafety'),'PUT');
    await page.locator('#campusMenu h3').filter({hasText:'清爽米饭'+stamp}).waitFor();
    check('confirmed_restrictions_filter_conflicting_and_unreviewed_products',!(await page.locator('#campusMenu').innerText()).includes('花生饭')&&!(await page.locator('#campusMenu').innerText()).includes('未核验菜'));
    const first=await api(customer,'/order/safety-context','GET',undefined,shops[0].id);check('confirmation_persists_for_current_shop',!first.needsClarification&&first.allergens.join()==='花生');
    await page.locator('#shop').selectOption(String(shops[1].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    check('switching_shops_does_not_copy_restrictions',!await page.getByLabel('花生',{exact:true}).isChecked());
    await page.getByLabel('鸡蛋',{exact:true}).check();await mutate(page,page.locator('#saveCampusSafety'),'PUT');
    await page.locator('#shop').selectOption(String(shops[0].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    check('returning_to_shop_restores_its_own_restrictions',await page.getByLabel('花生',{exact:true}).isChecked()&&!await page.getByLabel('鸡蛋',{exact:true}).isChecked());
    await page.locator('#campusMenu article').filter({hasText:'清爽米饭'+stamp}).getByRole('button',{name:'加入购物车',exact:true}).click();await page.locator('#campusCart article').waitFor();
    await page.locator('#quoteButton').click();await page.getByRole('button',{name:'确认本次报价并创建订单'}).waitFor();
    await page.getByLabel('鸡蛋',{exact:true}).check();await page.getByLabel('麸质',{exact:true}).check();
    check('unsaved_selection_blocks_add_and_checkout',await page.locator('#quoteButton').isDisabled()&&await page.locator('#campusMenu button:enabled').count()===0&&await page.getByRole('button',{name:'确认本次报价并创建订单'}).count()===0);
    await page.locator('#refresh').click();await page.locator('#campusSafety:not([disabled])').waitFor();
    check('refresh_preserves_unsaved_allergens',await page.getByLabel('花生',{exact:true}).isChecked()&&await page.getByLabel('鸡蛋',{exact:true}).isChecked()&&await page.getByLabel('麸质',{exact:true}).isChecked()&&await page.locator('#quoteButton').isDisabled());
    const safeProduct=(await api(customer,'/campus-menu/'+shops[0].id)).find(x=>x.product.name.startsWith('清爽米饭')).product;
    await api(customer,'/favorites/'+safeProduct.type+'/'+safeProduct.id,'POST');await page.locator('#refresh').click();await page.locator('#favoriteList').getByRole('button',{name:'加入购物车',exact:true}).waitFor();
    const originalCart=await api(customer,'/cart');await page.locator('#favoriteList').getByRole('button',{name:'加入购物车',exact:true}).click();await page.locator('#notice').filter({hasText:'尚未保存'}).waitFor();
    check('favorites_cannot_bypass_unsaved_safety_selection',JSON.stringify(await api(customer,'/cart'))===JSON.stringify(originalCart));
    const failWrite=async route=>{if(route.request().method()==='PUT')await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:0,msg:'测试保存失败'})});else await route.continue();};
    await page.route('**/order/safety-context',failWrite);await page.locator('#saveCampusSafety').click();await page.locator('#notice').filter({hasText:'测试保存失败'}).waitFor();
    check('failed_save_keeps_selection_for_retry',await page.getByLabel('鸡蛋',{exact:true}).isChecked()&&await page.getByLabel('麸质',{exact:true}).isChecked()&&await page.locator('#quoteButton').isDisabled());await page.unroute('**/order/safety-context',failWrite);
    await mutate(page,page.locator('#saveCampusSafety'),'PUT');
    check('safety_change_invalidates_existing_quote',await page.getByRole('button',{name:'确认本次报价并创建订单'}).count()===0);
    await page.locator('#campusMenu h3').filter({hasText:'清爽米饭'+stamp}).waitFor();check('all_supported_restrictions_are_applied',await page.locator('#campusMenu article').count()===1);

    let release,seen;const gate=new Promise(r=>release=r),started=new Promise(r=>seen=r);
    await page.locator('#shop').selectOption(String(shops[1].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    const delayed=async route=>{const req=route.request();if(req.method()==='GET'&&req.headers()['x-merchant-id']===String(shops[0].id)){const response=await route.fetch();seen();await gate;await route.fulfill({response});}else await route.continue();};
    await page.route('**/order/safety-context',delayed);await page.locator('#shop').selectOption(String(shops[0].id));await started;
    check('controls_are_disabled_while_loading',await page.locator('#campusSafety').evaluate(el=>el.disabled)&&await page.locator('#saveCampusSafety').isDisabled());
    await page.locator('#shop').selectOption(String(shops[1].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    const oldResponse=page.waitForResponse(r=>r.url().endsWith('/order/safety-context')&&r.request().headers()['x-merchant-id']===String(shops[0].id));release();await (await oldResponse).finished();
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    await page.waitForFunction(()=>document.querySelector('#campusSafety').dataset.merchant===document.querySelector('#shop').value);
    check('late_shop_response_cannot_overwrite_current_controls',await page.getByLabel('鸡蛋',{exact:true}).isChecked()&&!await page.getByLabel('花生',{exact:true}).isChecked());await page.unroute('**/order/safety-context',delayed);

    let releaseWrite,seenWrite;const writeGate=new Promise(r=>releaseWrite=r),writeStarted=new Promise(r=>seenWrite=r);
    const delayedWrite=async route=>{if(route.request().method()==='PUT'){seenWrite();await writeGate;}await route.continue();};
    await page.route('**/order/safety-context',delayedWrite);await page.getByLabel('鸡蛋',{exact:true}).uncheck();await page.getByLabel('麸质',{exact:true}).check();
    const writeResponse=page.waitForResponse(r=>r.url().endsWith('/order/safety-context')&&r.request().method()==='PUT');await page.locator('#saveCampusSafety').click();await writeStarted;
    await page.locator('#shop').selectOption(String(shops[0].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    await page.locator('#shop').selectOption(String(shops[1].id));await page.locator('#campusSafetyState').filter({hasText:'正在保存'}).waitFor();
    check('switching_back_cannot_start_a_second_write_to_same_shop',await page.locator('#saveCampusSafety').isDisabled()&&await page.locator('#clearCampusSafety').isDisabled()&&await page.getByLabel('花生',{exact:true}).isDisabled());
    await page.locator('#shop').selectOption(String(shops[0].id));await page.locator('#campusSafety:not([disabled])').waitFor();releaseWrite();await (await writeResponse).finished();await page.locator('#notice').filter({hasText:shops[1].name+'：已确认'}).waitFor();
    check('delayed_write_stays_bound_to_original_shop',await page.getByLabel('花生',{exact:true}).isChecked()&&(await api(customer,'/order/safety-context','GET',undefined,shops[1].id)).allergens.join()==='麸质');await page.unroute('**/order/safety-context',delayedWrite);

    const failRead=async route=>{if(route.request().method()==='GET')await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:0,msg:'测试约束读取失败'})});else await route.continue();};
    await page.route('**/order/safety-context',failRead);await page.locator('#refresh').click();await page.locator('#campusSafetyState').filter({hasText:'加载失败'}).waitFor();
    check('failed_safety_read_keeps_controls_disabled',await page.locator('#saveCampusSafety').isDisabled()&&await page.locator('#campusMenu button').count()===0);await page.unroute('**/order/safety-context',failRead);
    await page.locator('#refresh').click();await page.locator('#campusSafety:not([disabled])').waitFor();check('refresh_recovers_safety_controls',await page.getByLabel('花生',{exact:true}).isChecked());

    let releaseChat,seenChat;const chatGate=new Promise(r=>releaseChat=r),chatStarted=new Promise(r=>seenChat=r);
    const delayedChat=async route=>{seenChat();await chatGate;await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:1,data:{reply:'只读查询已完成',toolCalls:[],outcome:'completed'}})});};
    await page.route('**/campus-assistant/chat',delayedChat);await page.locator('#campusPrompt').fill('查询优惠券');await page.locator('#campusChat button').click();await chatStarted;
    await page.getByLabel('麸质',{exact:true}).uncheck();releaseChat();await page.locator('#campusMessages .message-assistant').filter({hasText:'只读查询已完成'}).waitFor();await page.locator('#campusMessages').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#campusMessages').hasAttribute('aria-busy'));
    check('late_chat_response_preserves_unsaved_allergens',!await page.getByLabel('麸质',{exact:true}).isChecked()&&await page.locator('#quoteButton').isDisabled());await page.unroute('**/campus-assistant/chat',delayedChat);await mutate(page,page.locator('#saveCampusSafety'),'PUT');

    await page.locator('#shop').selectOption(String(shops[1].id));await page.locator('#campusSafety:not([disabled])').waitFor();
    await api(customer,'/campus-assistant/chat','POST',{campusId:campus,addressId:null,fulfillmentMode:'PICKUP',message:'我花生过敏'});
    await page.locator('#refresh').click();await page.locator('#campusSafetyState').filter({hasText:'待确认'}).waitFor();
    check('allergy_message_exposes_pending_confirmation_without_leaving_campus',await page.locator('#campusMenu').getByRole('button',{name:'加入购物车'}).count()===0);
    await mutate(page,page.locator('#saveCampusSafety'),'PUT');await page.locator('#campusMenu h3').filter({hasText:'清爽米饭'+stamp}).waitFor();
    const pending=await api(customer,'/order/safety-context','GET',undefined,shops[0].id);check('confirming_one_shop_does_not_confirm_other_shops',pending.needsClarification);
    await mutate(page,page.locator('#clearCampusSafety'),'DELETE');check('explicit_clear_removes_only_current_temporary_restrictions',(await api(customer,'/order/safety-context','GET',undefined,shops[1].id)).allergens.length===0);
    const stranger=await browser.newContext();await api(stranger,'/auth/register','POST',{username:'safety_other'+stamp,password});check('safety_context_is_customer_scoped',(await api(stranger,'/order/safety-context','GET',undefined,shops[0].id)).allergens.length===0);
    await page.reload();await page.locator('#workspace').waitFor({state:'visible'});await page.locator('#campus').selectOption(String(campus));await page.locator('#shop option[value="'+shops[0].id+'"]').waitFor({state:'attached'});await page.locator('#shop').selectOption(String(shops[0].id));await page.locator('#campusSafetyState').filter({hasText:'待确认'}).waitFor();
    check('pending_state_survives_reload',await page.getByLabel('花生',{exact:true}).isChecked());
    await page.setViewportSize({width:390,height:844});check('allergy_controls_fit_mobile_viewport',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    fs.mkdirSync(path.dirname(output),{recursive:true});await page.locator('#browse').screenshot({path:output.replace(/\.json$/,'.png')});
    check('no_page_script_errors',errors.length===0);passed=true;
  }catch(error){errors.push(error.message);process.exitCode=1;console.error(error.message);}
  finally{if(browser)await browser.close();fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify({passed,checks,errors},null,2));}
})();
