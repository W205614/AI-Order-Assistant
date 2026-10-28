// Focused browser checks; all HTTP traffic is intercepted, with no database or model.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.argv[2]||'playwright');
const root=path.resolve(__dirname,'../java-gateway/src/main/resources/static');
const products=[1,2].map(id=>({type:'SKU',id,merchantId:1,name:'商品'+id,price:10,packagingFee:0}));
const candidate={total:20,shops:[{merchantName:'测试店',payable:20,lines:products.map(product=>({product,quantity:1}))}]};
const checks=[];
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.argv[3]?{executablePath:process.argv[3]}:{})});
  try{
    const page=await browser.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    let cart=[],mode='success',chatMode='timeout',batchCalls=0,cartReads=0,failCart=false,releaseBatch,enteredBatch;
    function row(item){return{...item,id:item.productId,product_id:item.productId,product_type:item.productType,merchant_id:1,merchant_name:'测试店',product_name:'商品'+item.productId,version:1};}
    await page.route('**/*',async route=>{
      const req=route.request(),url=new URL(req.url()),p=url.pathname;
      const json=(data,status=200,msg='ok')=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?1:0,data,msg})});
      if(p==='/campus/'||p.startsWith('/assets/')){
        const file=path.join(root,p==='/campus/'?'campus/index.html':p);
        return route.fulfill({body:fs.readFileSync(file),contentType:p.endsWith('.js')?'text/javascript':p.endsWith('.css')?'text/css':p.endsWith('.svg')?'image/svg+xml':'text/html'});
      }
      if(p==='/auth/me')return json({userId:1,username:'fixture',role:'CUSTOMER'});
      if(p==='/auth/csrf')return json({token:'fixture'});
      if(p==='/campuses')return json([{id:1,name:'测试校区'}]);
      if(p==='/campuses/1/merchants')return json([{id:1,name:'测试店',fee:0}]);
      if(p==='/campus-menu/1')return json(products.map(product=>({product,stock:10})));
      if(p==='/order/safety-context')return json({allergens:[],needsClarification:false});
      if(p==='/cart'){
        assert.equal(req.method(),'GET','combination must use the batch endpoint');cartReads++;
        return failCart?json(null,503,'fixture unavailable'):json(cart);
      }
      if(p==='/cart/batch'){
        batchCalls++;const batch=req.postDataJSON();assert.equal(batch.items.length,2);
        if(mode==='held'){enteredBatch();await new Promise(resolve=>{releaseBatch=resolve;});}
        if(mode==='conflict')return json(null,400,'购物车版本已变化');
        cart=batch.items.map(row);
        if(mode==='lost')return route.abort('connectionreset');
        return json([1,2]);
      }
      if(p==='/campus-assistant/recommend')return json({candidates:[candidate],message:'fixture'});
      if(p==='/campus-assistant/chat'){
        if(chatMode==='quote'){
          cart=products.map(product=>row({productId:product.id,productType:'SKU',quantity:1,remark:''}));
          const quote={id:'fixture-quote',version:1,total:20,expiresAt:new Date(Date.now()+60000).toISOString(),unavailable:{},
            request:{campusId:1,addressId:null,fulfillmentMode:'PICKUP',merchantIds:[1],shopCoupons:{1:13},platformCoupon:14,reservationSlots:{}},
            shops:[{merchantId:1,merchantName:'测试店',goods:20,packaging:0,delivery:0,shopDiscount:0,platformDiscount:0,payable:20,etaMin:10,etaMax:20,
              lines:products.map(product=>({cartId:product.id,cartVersion:1,product,quantity:1}))}]};
          return json({reply:'已准备报价，请核对。',quote});
        }
        cart=[row({productId:1,productType:'SKU',quantity:4,remark:'工具已完成'})];
        return json(null,504,'AI 时间预算已用完');
      }
      if(p==='/coupons/mine')return json(chatMode==='quote'?[{id:13,merchant_id:1,status:'AVAILABLE',name:'店铺券',threshold_amount:0,discount_amount:0},{id:14,merchant_id:null,status:'AVAILABLE',name:'平台券',threshold_amount:0,discount_amount:0}]:[]);
      if(p==='/me/events')return route.fulfill({status:403,body:''});
      if(['/addresses','/coupons','/coupons/mine','/checkouts','/favorites','/feedback','/reservations/1'].includes(p))return json([]);
      throw Error('Unexpected fixture request: '+p);
    });
    await page.goto('http://127.0.0.1:19876/campus/');
    await page.locator('#workspace').waitFor({state:'visible'});
    await page.locator('#recommendForm input[name=budget]').fill('100');
    await page.locator('#recommendForm input[name=keywords]').fill('商品');
    await page.locator('#recommendForm button').click();
    const add=page.getByRole('button',{name:'将本组合加入购物车',exact:true});
    await add.waitFor();
    mode='held';const entered=new Promise(resolve=>{enteredBatch=resolve;});
    await add.click();await entered;
    assert.equal(await add.isDisabled(),true);
    // A second click on a disabled native button cannot dispatch another request.
    await add.evaluate(button=>button.click());assert.equal(batchCalls,1);releaseBatch();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('整组加入'));
    assert.equal(cart.length,2);assert.equal(batchCalls,1);checks.push('one batch and duplicate-click suppression');
    mode='conflict';const before=JSON.stringify(cart),readsBefore=cartReads;
    await add.click();await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('已刷新购物车'));
    assert.equal(JSON.stringify(cart),before);assert.ok(cartReads>readsBefore);checks.push('conflict refresh without automatic retry');
    mode='lost';await add.click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('结果可能已提交'));
    assert.equal(batchCalls,3);assert.ok((await page.locator('#campusCart').innerText()).includes('× 2'));
    checks.push('lost response refreshes committed cart without retry');
    await page.locator('#campusPrompt').fill('加入商品后超时');
    await page.locator('#campusChat button').click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('已同步购物车与安全状态'));
    const recoveryNotice=await page.locator('#notice').innerText();
    assert.ok(['部分操作可能已完成','逐店确认'].every(text=>recoveryNotice.includes(text)));
    assert.ok((await page.locator('#campusCart').innerText()).includes('× 4'));checks.push('chat timeout refreshes partial completion');
    failCart=true;
    await page.locator('#campusChat button').click();
    await page.waitForFunction(()=>document.querySelector('#notice').textContent.includes('状态尚未确认'));
    assert.ok((await page.locator('#quote').innerText()).includes('重新计算报价'));checks.push('failed refresh marks state unknown and invalidates quote');
    failCart=false;chatMode='quote';
    await page.locator('#mode').selectOption('PICKUP');
    await page.locator('#campusChat button').click();
    await page.getByRole('button',{name:'确认本次报价并创建订单',exact:true}).waitFor();
    assert.equal(await page.locator('#platformCoupon').inputValue(),'14');
    assert.equal(await page.locator('#campusCart select').first().inputValue(),'13');
    checks.push('successful assistant quote applies coupon selections through module contracts');
    assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,checks}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
