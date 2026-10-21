// Reproduce a real UI race: an event refresh starts before a customer focuses
// a feedback field, then finishes after the customer has already typed a draft.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.argv[2]||'playwright');
const root=path.resolve(__dirname,'../java-gateway/src/main/resources/static');
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.argv[3]?{executablePath:process.argv[3]}:{})});
  try{
    const page=await browser.newPage();
    let releaseEvent,releaseReplay,enteredReplay;
    const eventReady=new Promise(resolve=>{releaseEvent=resolve;});
    const replayEntered=new Promise(resolve=>{enteredReplay=resolve;});
    const replayReady=new Promise(resolve=>{releaseReplay=resolve;});
    let replayReads=0;
    await page.route('**/*',async route=>{
      const p=new URL(route.request().url()).pathname;
      const json=data=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:1,msg:'ok',data})});
      if(p==='/campus/'||p.startsWith('/assets/'))return route.fulfill({
        body:fs.readFileSync(path.join(root,p==='/campus/'?'campus/index.html':p)),
        contentType:p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':p.endsWith('.svg')?'image/svg+xml':'text/html'
      });
      if(p==='/auth/me')return json({userId:1,username:'fixture',role:'CUSTOMER'});
      if(p==='/auth/csrf')return json({token:'fixture'});
      if(p==='/campuses')return json([{id:1,name:'测试校区'}]);
      if(p==='/campuses/1/merchants')return json([{id:1,name:'测试店',fee:0}]);
      if(p==='/order/safety-context')return json({allergens:[],needsClarification:false});
      if(p==='/checkouts'){
        replayReads++;
        if(replayReads===2){enteredReplay();await replayReady;}
        return json([{id:'fixture',status:'PAID',total_amount:20,refunded_amount:0}]);
      }
      if(p==='/checkouts/fixture')return json({orders:[{id:1,merchant_name:'测试店',status:4,
        fulfillment_status:'COMPLETED',total_amount:20,refunded_amount:0,production_started:true,items:[],aftersales:[]}]});
      if(p==='/me/events'){
        await eventReady;
        return route.fulfill({contentType:'text/event-stream',body:'id: 1\nevent: commerce\ndata: {}\n\n'});
      }
      if(['/campus-menu/1','/cart','/addresses','/coupons','/coupons/mine','/favorites','/feedback'].includes(p))return json([]);
      throw Error('Unexpected request: '+p);
    });
    await page.goto('http://127.0.0.1:19876/campus/');
    await page.locator('#workspace').waitFor({state:'visible'});
    releaseEvent();await replayEntered;
    const feedback=page.locator('#checkoutList [name=message]');
    await feedback.fill('尚未提交的反馈草稿');
    releaseReplay();await page.waitForLoadState('networkidle');
    assert.equal(await feedback.inputValue(),'尚未提交的反馈草稿','an in-flight event refresh must not replace the focused editor');
    assert.equal(await page.evaluate(()=>sessionStorage.getItem('campus-events:1')),null,'a deferred event must remain unacknowledged');
    await page.locator('#campusPrompt').focus();
    await page.waitForFunction(()=>sessionStorage.getItem('campus-events:1')==='1');
    assert.ok(replayReads>=3,'leaving the editor retries the deferred refresh');
    console.log(JSON.stringify({passed:true,checks:['in-flight event preserves active feedback draft','deferred refresh keeps cursor pending','focusout applies pending update']}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
