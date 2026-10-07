// Run independently from ordinary business load; a real provider is opt-in.
import http from 'k6/http';
import {check,sleep} from 'k6';
import exec from 'k6/execution';
import {Counter,Trend} from 'k6/metrics';
const fixture=JSON.parse(open(__ENV.USERS_FILE||'/fixtures/bench-users.json'));
const base=__ENV.BASE_URL||'http://host.docker.internal:19090';
const real=__ENV.REAL_MODEL==='true';
const completed=new Counter('ai_completed'),limited=new Counter('ai_limited'),unavailable=new Counter('ai_unavailable'),degraded=new Counter('ai_degraded');
const successLatency=new Trend('ai_success_ms',true);
export const options={vus:Number(__ENV.VUS||2),duration:__ENV.DURATION||'30s',
 thresholds:{checks:['rate==1'],'http_req_duration':['p(95)<40000']},summaryTrendStats:['avg','med','p(95)','max']};
export default function(){
 const session=fixture.sessions[exec.vu.idInTest-1],jar=http.cookieJar();
 if(!session)throw new Error('Each VU must have a distinct prepared customer');
 for(const[name,value]of Object.entries(session.cookies))jar.set(base,name,value);
 const headers={'Content-Type':'application/json','X-XSRF-TOKEN':session.csrf,'X-Merchant-Id':'1'};
 if(!real&&exec.vu.iterationInScenario===0){
   // The disposable customer's first request creates a draft; later requests
   // update its quantity instead of accidentally asking the unconfigured model.
   const pending=http.get(base+'/order/drafts/pending',{headers});
   const drafts=pending.status===200?pending.json('data'):[];
   for(const draft of drafts||[])http.del(base+'/order/drafts/'+draft.id,null,{headers});
 }
 const message=real?'请推荐一份清淡午餐':exec.vu.iterationInScenario===0?'我要一份鱼香肉丝饭':'改成一份';
 const response=http.post(base+'/chat',JSON.stringify({message,history:[]}),
 {headers:{...headers,'X-Request-Id':'ai-'+exec.vu.idInTest+'-'+exec.vu.iterationInScenario+'-'+Date.now()},timeout:'40s'});
 let body;try{body=response.json();}catch(e){body={};}
 const success=response.status===200&&body.code===1&&body.data?.outcome==='completed';
 if(success){completed.add(1);successLatency.add(response.timings.duration);}
 else if(response.status===429)limited.add(1);
 else if(response.status===503||response.status===504)unavailable.add(1);
 else degraded.add(1);
 check(response,{'router succeeds; real model declares failure or limit':()=>real?
   response.status===429||response.status===503||response.status===504||response.status===200&&body.code===1:success});
 sleep(3);
}
export function handleSummary(data){return{[__ENV.K6_SUMMARY_PATH||'/results/k6-ai.json']:JSON.stringify(data,null,2)};}
