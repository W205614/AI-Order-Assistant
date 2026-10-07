"""Exercise the deployed Cookie/CSRF contract with one disposable account."""
import argparse,json,secrets,time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from http_contract import Session

def main():
    p=argparse.ArgumentParser();p.add_argument("--base-url",default="http://127.0.0.1:9090");p.add_argument("--workers",type=int,default=16)
    p.add_argument("--report",default="load/results/functional-concurrency.json");args=p.parse_args()
    checks=[];started=time.monotonic();s=Session(args.base_url)
    def check(name,condition):
        checks.append({"check":name,"passed":bool(condition)})
        assert condition,name
    def burst(action):
        barrier=Barrier(args.workers)
        def run(i):
            barrier.wait(timeout=20);return action(i)
        with ThreadPoolExecutor(max_workers=args.workers)as pool:return list(pool.map(run,range(args.workers)))
    try:
        info=s.ok("POST","/auth/register",{"username":"verify_"+secrets.token_hex(5),"password":secrets.token_urlsafe(24),"nickname":"Verification"})
        check("browser_token_not_exposed","token" not in info)
        menu=s.ok("GET","/dish/list?size=50")["items"];dish=menu[0]
        check("merchant_scoped_menu",all(d["merchantId"]==1 for d in menu))
        for message in ["花生过敏","过敏但不知道是什么"]:
            s.ok("POST","/chat",{"message":message,"history":[]})
            check("allergy_requires_selection",s.ok("GET","/order/safety-context")["needsClarification"])
            check("unresolved_allergy_blocks_draft",s.response("POST","/order/drafts",{"items":[{"dishId":dish["id"],"quantity":1}]}).status_code==400)
            s.ok("DELETE","/order/safety-context")
        drafts=burst(lambda _:s.ok("POST","/order/drafts",{"items":[{"dishId":dish["id"],"quantity":1}]}))
        pending=s.ok("GET","/order/drafts/pending")
        check("one_pending_draft_under_concurrency",len(pending)==1)
        draft=pending[0]
        updated=s.ok("PUT","/order/drafts/"+draft["id"],{"items":[{"dishId":dish["id"],"quantity":1}],"expectedVersion":draft["version"],"remark":"Edited"})
        receipt={"expectedVersion":draft["version"],"recipientName":"Verification","recipientPhone":"13800000000","deliveryAddress":"测试楼","deliveryRegion":"校园"}
        path="/order/drafts/"+draft["id"]+"/confirm"
        old=s.response("POST",path,receipt,{"Idempotency-Key":"stale-"+secrets.token_hex(8)})
        check("stale_confirmation_returns_409_and_latest",old.status_code==409 and old.json()["data"]["version"]==updated["version"])
        receipt["expectedVersion"]=updated["version"];key="verify-"+secrets.token_hex(12)
        confirmed=burst(lambda _:s.ok("POST",path,receipt,{"Idempotency-Key":key}))
        check("one_order_under_concurrent_retries",len({o["id"] for o in confirmed})==1)
        o=confirmed[0];seq=o["userSeq"]
        check("order_awaits_simulated_payment",o["status"]==0 and o["paymentStatus"]=="UNPAID")
        different=s.response("POST",path,receipt,{"Idempotency-Key":"different-"+secrets.token_hex(8)})
        check("new_key_cannot_reconfirm_finished_draft",different.status_code==409)
        paid=burst(lambda _:s.ok("POST","/order/"+str(seq)+"/pay"))
        check("payment_retries_stay_paid",all(p["paymentStatus"]=="SIMULATED_PAID" for p in paid))
        cancelled=burst(lambda _:s.ok("POST","/order/"+str(seq)+"/cancel"))
        check("cancel_refund_release_are_idempotent",all(c["status"]==5 and c["inventoryReleased"] and c["paymentStatus"]=="SIMULATED_REFUNDED" for c in cancelled))
        check("events_can_be_replayed",len(s.ok("GET","/order/events/replay?after=0"))==3)
        cookie=dict(s.client.cookies).get("ao_user")
        s.ok("POST","/auth/logout")
        s.client.cookies.set("ao_user",cookie,domain="127.0.0.1")
        check("logout_invalidates_server_credential",s.response("GET","/order/list").status_code==401)
        report={"passed":True,"workers":args.workers,"checks":checks,"elapsedMs":round((time.monotonic()-started)*1000,1)}
    except Exception as error:
        report={"passed":False,"checks":checks,"errorType":type(error).__name__}
        raise
    finally:
        Path(args.report).parent.mkdir(parents=True,exist_ok=True);Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8");s.close()
    print(json.dumps({"passed":report["passed"],"checks":len(checks),"workers":args.workers}))
if __name__=="__main__":main()
