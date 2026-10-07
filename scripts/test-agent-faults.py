"""Use separate Agent/provider containers to verify real HTTP failure paths."""
import argparse,json,os,secrets,subprocess,time
from pathlib import Path
from http_contract import Session
p=argparse.ArgumentParser();p.add_argument('--project',default='ai-order-acceptance');p.add_argument('--base-url',default='http://127.0.0.1:19090');p.add_argument('--env-file',default='.github/compose-ci.env');p.add_argument('--report',default='.local/agent-faults.json');a=p.parse_args()
if a.project not in {'ai-order-acceptance','ai-order-perf','ai-order-clean'}:raise SystemExit('Use a disposable project')
env=dict(os.environ);values=dict(line.split('=',1) for line in Path(a.env_file).read_text().splitlines() if '=' in line and not line.startswith('#'))
env['AGENT_INTERNAL_API_KEY']=values['AGENT_INTERNAL_API_KEY']
image=a.project+'-agent:latest';network=a.project+'_default';suffix=secrets.token_hex(5);names=[];checks=[]
def docker(*cmd,**kw):return subprocess.run(['docker',*cmd],capture_output=True,text=True,check=True,env=env,**kw)
session=Session(a.base_url);account=session.ok('POST','/auth/register',{'username':'fault_'+suffix,'password':secrets.token_urlsafe(24),'nickname':'Fault fixture'})
token=dict(session.client.cookies)['ao_user']
before=session.ok('GET','/order/drafts/pending')
runner="""import json,sys,time,httpx
p=json.load(sys.stdin);started=time.monotonic()
headers={'X-Agent-Internal-Key':p['key'],'X-Agent-User-Id':str(p['request']['userId']),'X-Agent-Merchant-Id':'1','X-Agent-Deadline':str(p['request']['deadlineEpochMs'])}
r=httpx.post(p['url']+'/chat',json=p['request'],headers=headers,timeout=40,trust_env=False);j=r.json()
print(json.dumps({'status':r.status_code,'outcome':j.get('outcome'),'pending':bool(j.get('pendingConfirmation')),'elapsedSeconds':round(time.monotonic()-started,2)}))
"""
try:
    provider='ai-order-fault-provider-'+suffix;names.append(provider)
    docker('run','-d','--name',provider,'--label','com.ai-order.fault=true','--network',network,'-v',str(Path('scripts').resolve())+':/scripts:ro','--entrypoint','python',image,'/scripts/fault-provider.py')
    for mode in ['rate','disconnect','timeout']:
        agent='ai-order-fault-agent-'+mode+'-'+suffix;names.append(agent)
        docker('run','-d','--name',agent,'--label','com.ai-order.fault=true','--network',network,'--read-only','--tmpfs','/tmp','--tmpfs','/app/metrics:uid=10001,gid=10001','-e','AGENT_INTERNAL_API_KEY','-e','JAVA_BASE_URL=http://gateway:9090','-e','LLM_API_KEY=synthetic-fixture','-e',f'LLM_BASE_URL=http://{provider}:8810/{mode}/v1',image)
        for attempt in range(30):
            r=subprocess.run(['docker','exec',agent,'python','-c',"import urllib.request;urllib.request.urlopen('http://localhost:8800/health',timeout=1)"],capture_output=True)
            if r.returncode==0:break
            time.sleep(.5)
        else:raise AssertionError('Fault Agent did not become ready')
        request={'userId':account['userId'],'merchantId':1,'jwtToken':token,'deadlineEpochMs':int(time.time()*1000)+35000,'message':'请帮我推荐午餐并说明搭配理由','history':[],'requestId':'fault-'+mode+'-'+suffix}
        result=json.loads(docker('run','-i','--rm','--network',network,'--entrypoint','python',image,'-c',runner,input=json.dumps({'url':'http://'+agent+':8800','key':env['AGENT_INTERNAL_API_KEY'],'request':request})).stdout)
        assert result['status'] in (200,504) and not result['pending'] and result['elapsedSeconds']<36,result
        if result['status']==200:assert result['outcome']=='degraded',result
        result['case']=mode;checks.append(result);print(mode+' fault fallback passed.',flush=True)
    assert session.ok('GET','/order/drafts/pending')==before,'Provider faults created a business draft'
    report={'passed':True,'syntheticProvider':True,'checks':checks,'noBusinessDraftCreated':True}
    Path(a.report).parent.mkdir(parents=True,exist_ok=True);Path(a.report).write_text(json.dumps(report,indent=2))
finally:
    session.close()
    for name in reversed(names):
        r=subprocess.run(['docker','inspect',name,'--format','{{index .Config.Labels "com.ai-order.fault"}}'],capture_output=True,text=True)
        if r.stdout.strip()=='true':subprocess.run(['docker','rm','-f',name],capture_output=True)
