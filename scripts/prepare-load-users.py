"""Prepare separate customer cookie sessions in a disposable benchmark stack."""
import argparse,json,time,secrets,subprocess
from pathlib import Path
from http_contract import Session
def main():
    p=argparse.ArgumentParser();p.add_argument("--base-url",default="http://127.0.0.1:19090");p.add_argument("--project",default="ai-order-perf")
    p.add_argument("--env-file",default=".github/compose-ci.env");p.add_argument("--count",type=int,default=50);p.add_argument("--output",default=".local/bench-users.json")
    args=p.parse_args()
    if args.project not in {"ai-order-perf","ai-order-acceptance","ai-order-clean"}:raise SystemExit("Load fixtures require an explicitly disposable project")
    if not 1<=args.count<=50:raise SystemExit("User count must be 1–50")
    prefix="bench_"+secrets.token_hex(4);password=secrets.token_urlsafe(24)
    first=Session(args.base_url)
    first.ok("POST","/auth/register",{"username":prefix+"_1","password":password,"nickname":"Benchmark"})
    sql=[]
    for i in range(2,args.count+1):
        sql.append(f"INSERT INTO user(username,password,nickname,created_at) SELECT '{prefix}_{i}',password,'Benchmark',NOW() FROM user WHERE username='{prefix}_1';")
    sql.append("UPDATE dish SET stock=100000,stock_version=stock_version+1 WHERE merchant_id=1;")
    command=["docker","compose","--env-file",args.env_file,"-p",args.project,"-f","docker-compose.yml","-f","docker-compose.perf.yml","exec","-T","mysql","sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot ai_order_assistant']
    subprocess.run(command,input="\n".join(sql),text=True,check=True,capture_output=True)
    sessions=[]
    for i in range(1,args.count+1):
        if i==1:session=first
        else:
            if i==26:
                print("Login rate limit respected; preparing the second batch after one minute.",flush=True);time.sleep(65)
            session=Session(args.base_url);session.login(prefix+"_"+str(i),password)
        sessions.append({"cookies":dict(session.client.cookies),"csrf":session.csrf})
        session.close()
    Path(args.output).parent.mkdir(parents=True,exist_ok=True)
    Path(args.output).write_text(json.dumps({"sessions":sessions,"count":args.count,"createdAt":time.time()}),encoding="utf-8")
    print(f"{args.count} independent customer sessions prepared.",flush=True)
if __name__=="__main__":main()
