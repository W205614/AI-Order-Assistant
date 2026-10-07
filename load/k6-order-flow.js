import http from 'k6/http';
import {check,sleep,fail} from 'k6';
import exec from 'k6/execution';
import {Trend,Rate} from 'k6/metrics';
const base=__ENV.BASE_URL||'http://host.docker.internal:19090';
const fixture=JSON.parse(open(__ENV.USERS_FILE||'/fixtures/bench-users.json'));
const query=new Trend('query_ms',true),write=new Trend('write_ms',true),unexpected=new Rate('unexpected_errors');
export const options={
 scenarios:{business:{executor:'constant-vus',vus:Number(__ENV.VUS||50),duration:__ENV.DURATION||'10m',gracefulStop:'30s'}},
 thresholds:{checks:['rate==1'],query_ms:['p(95)<500'],write_ms:['p(95)<1000'],unexpected_errors:['rate<0.01']},
 summaryTrendStats:['avg','med','p(95)','max']
};
function request(method,path,body,extra={}){
 const session=fixture.sessions[exec.vu.idInTest-1];
 if(!session)fail('VU must use a distinct prepared customer');
 const jar=http.cookieJar();for(const[name,value]of Object.entries(session.cookies))jar.set(base,name,value);
 const r=http.request(method,base+path,body===undefined?null:JSON.stringify(body),{headers:{'Content-Type':'application/json',
  'X-XSRF-TOKEN':session.csrf,'X-Merchant-Id':'1',...extra},timeout:'10s',tags:{kind:method==='GET'?'query':'write',name:method+' '+path.split('?')[0].replace(/\/drafts\/[^/]+/,'/drafts/:id').replace(/\/order\/\d+/,'/order/:seq')}});
 (method==='GET'?query:write).add(r.timings.duration);
 let result;try{result=r.json();}catch(e){result={};}
 const valid=r.status===200&&result.code===1;unexpected.add(!valid);
 if(!check(r,{'business response succeeds':()=>valid}))fail('Unexpected business response: '+method+' HTTP '+r.status+' / '+(result.errorCode||r.error_code||'UNKNOWN'));
 return r.json('data');
}
export default function(){
 const menu=request('GET','/dish/list?availableOnly=true&size=50');
 if(!menu.items.length)fail('Benchmark menu empty');
 const dish=menu.items[(exec.vu.idInTest-1)%menu.items.length];
 const d=request('POST','/order/drafts',{items:[{dishId:dish.id,quantity:1}],remark:'Disposable benchmark'});
 const key='k6-'+exec.vu.idInTest+'-'+exec.vu.iterationInScenario+'-'+Date.now();
 const body={expectedVersion:d.version,recipientName:'Benchmark',recipientPhone:'13800000000',deliveryAddress:'测试楼',deliveryRegion:'校园'};
 const o=request('POST','/order/drafts/'+d.id+'/confirm',body,{'Idempotency-Key':key});
 const again=request('POST','/order/drafts/'+d.id+'/confirm',body,{'Idempotency-Key':key});
 check(again,{'confirmation retry creates same order':()=>o.id===again.id});
 const paid=request('POST','/order/'+o.userSeq+'/pay');
 check(paid,{'simulated payment':()=>paid.paymentStatus==='SIMULATED_PAID'});
 const cancelled=request('POST','/order/'+o.userSeq+'/cancel');
 const repeat=request('POST','/order/'+o.userSeq+'/cancel');
 check(repeat,{'cancel/refund/release exactly once':()=>cancelled.id===repeat.id&&repeat.status===5&&repeat.inventoryReleased&&repeat.paymentStatus==='SIMULATED_REFUNDED'});
 request('GET','/order/list?page=1&size=20');
 sleep(1);
}
export function handleSummary(data){return{[__ENV.K6_SUMMARY_PATH||'/results/k6-summary.json']:JSON.stringify(data,null,2)};}
