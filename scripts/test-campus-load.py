"""Bounded mixed campus acceptance, not a production capacity claim."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import secrets
import time
from urllib.parse import urlparse
from http_contract import Session


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--base-url',default='http://127.0.0.1:19090')
    parser.add_argument('--workers',type=int,default=6)
    parser.add_argument('--iterations',type=int,default=3)
    parser.add_argument('--report',default='load/results/campus-load.json')
    parser.add_argument('--expect-agent-down',action='store_true')
    args=parser.parse_args()
    parsed=urlparse(args.base_url)
    if parsed.hostname not in {'localhost','127.0.0.1'} or parsed.port not in {19090,19092}:
        raise SystemExit('Only disposable local ports are allowed')
    reads,writes=[],[]
    def sample(session,method,path,body=None,extra=None):
        start=time.perf_counter();value=session.ok(method,path,body,extra)
        (reads if method=='GET' else writes).append((time.perf_counter()-start)*1000)
        return value
    # Provision accounts sequentially, outside the measured transaction workload.
    sessions=[]
    try:
        for _ in range(args.workers):
            s=Session(args.base_url,merchant=None)
            s.ok('POST','/auth/register',{'username':'campus_load_'+secrets.token_hex(6),'password':secrets.token_urlsafe(24),'nickname':'负载验收'})
            sessions.append(s)
        campuses=sessions[0].ok('GET','/campuses')
        campus=None;selected=None
        for c in reversed(campuses):
            shops=sessions[0].ok('GET',f"/campuses/{c['id']}/merchants")
            found=[]
            for shop in shops:
                if not shop['accepting_orders']:continue
                products=sessions[0].ok('GET',f"/campus-menu/{shop['id']}")
                product=next((p for p in products if p['stock']>=args.workers),None)
                if product:found.append((shop['id'],product['product']))
                if len(found)==2:break
            if len(found)==2:campus=c['id'];selected=found;break
        assert selected,'Two serviceable stores with stock are required'
        addresses=[s.ok('POST','/addresses',{'campusId':campus,'recipientName':'验收','phone':'13800000000','detail':'测试楼'}) for s in sessions]
        if args.expect_agent_down:
            before=sessions[0].ok('GET','/checkouts')
            response=sessions[0].response('POST','/campus-assistant/chat',{'campusId':campus,'addressId':addresses[0],'fulfillmentMode':'DELIVERY','message':'推荐一份午饭'})
            assert response.status_code==503 and response.json()['errorCode']=='AI_UNAVAILABLE'
            assert before==sessions[0].ok('GET','/checkouts')
        def work(pair):
            s,address=pair
            for _ in range(args.iterations):
                for merchant,product in selected:
                    sample(s,'GET',f'/campus-menu/{merchant}')
                    sample(s,'PUT','/cart',{'productType':product['type'],'productId':product['id'],'quantity':1,'remark':''})
                q=sample(s,'POST','/checkouts/quotes',{'merchantIds':[m for m,_ in selected],'campusId':campus,'addressId':address,'fulfillmentMode':'DELIVERY'})
                assert not q['unavailable']
                key={'Idempotency-Key':'load-'+secrets.token_hex(16)}
                c=sample(s,'POST','/checkouts/confirm',{'quoteId':q['id'],'expectedVersion':q['version']},key)
                again=sample(s,'POST','/checkouts/confirm',{'quoteId':q['id'],'expectedVersion':q['version']},key)
                assert c['id']==again['id']
                sample(s,'POST',f"/checkouts/{c['id']}/pay")
                for order in c['orders']:
                    sample(s,'POST',f"/checkouts/orders/{order['id']}/cancel")
                detail=sample(s,'GET',f"/checkouts/{c['id']}")
                assert detail['total_amount']==detail['refunded_amount']
            return True
        with ThreadPoolExecutor(max_workers=args.workers) as pool:assert all(pool.map(work,zip(sessions,addresses)))
        def p95(values):return round(sorted(values)[min(len(values)-1,int(len(values)*.95))],2)
        report={'workers':args.workers,'iterations':args.iterations,'readSamples':len(reads),'writeSamples':len(writes),'readP95Ms':p95(reads),'writeP95Ms':p95(writes),'unexpectedErrors':0,'consistencyPassed':True,'agentOfflineVerified':args.expect_agent_down}
        report['passed']=report['readP95Ms']<500 and report['writeP95Ms']<1000
        Path(args.report).parent.mkdir(parents=True,exist_ok=True)
        Path(args.report).write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps(report));assert report['passed'],'Measured latency exceeded the existing local acceptance gates'
    finally:
        for s in sessions:s.close()


if __name__=='__main__':main()
