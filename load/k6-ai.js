// Run independently from ordinary business load; a real provider is opt-in.
import http from 'k6/http';
import {check,sleep} from 'k6';
import exec from 'k6/execution';
const fixture=JSON.parse(open(__ENV.USERS_FILE||'/fixtures/bench-users.json'));
const base=__ENV.BASE_URL||'http://host.docker.internal:19090';
const real=__ENV.REAL_MODEL==='true';
export const options={vus:Number(__ENV.VUS||2),duration:__ENV.DURATION||'30s',
 thresholds:{checks:['rate==1'],'http_req_duration':['p(95)<40000']},summaryTrendStats:['avg','med','p(95)','max']};
export default function(){
 const session=fixture.sessions[(exec.vu.idInTest-1)%fixture.count],jar=http.cookieJar();
 for(const[name,value]of Object.entries(session.cookies))jar.set(base,name,value);
 const response=http.post(base+'/chat',JSON.stringify({message:real?'请推荐一份清淡午餐':'我要一份鱼香肉丝饭',history:[]}),
 {headers:{'Content-Type':'application/json','X-XSRF-TOKEN':session.csrf,'X-Merchant-Id':'1','X-Request-Id':'ai-'+exec.vu.idInTest+'-'+exec.vu.iterationInScenario+'-'+Date.now()},timeout:'40s'});
 check(response,{'AI returns a declared result or admission limit':r=>r.status===429||r.status===503||r.status===504||r.status===200&&r.json('code')===1});
 sleep(3);
}
export function handleSummary(data){return{[__ENV.K6_SUMMARY_PATH||'/results/k6-ai.json']:JSON.stringify(data,null,2)};}
