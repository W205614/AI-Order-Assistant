"""Verify stock/ledger/tenant invariants after the disposable load has stopped."""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--project',default='ai-order-perf');p.add_argument('--env-file',default='.github/compose-ci.env');p.add_argument('--output',default='load/results/stock-invariants.json');a=p.parse_args()
if a.project not in {'ai-order-perf','ai-order-acceptance','ai-order-clean'}:raise SystemExit('Only disposable projects are allowed')
sql="""SELECT JSON_OBJECT(
'negativeStock',(SELECT COUNT(*) FROM dish WHERE stock<0),
'unreleasedClosedOrders',(SELECT COUNT(*) FROM orders WHERE status IN(5,6) AND payment_status<>'NOT_APPLICABLE' AND inventory_released=FALSE),
'missingReserve',(SELECT COUNT(*) FROM order_item i JOIN orders o ON o.id=i.order_id WHERE o.draft_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM inventory_ledger l WHERE l.order_id=o.id AND l.dish_id=i.dish_id AND l.kind='RESERVE' AND l.delta=-i.quantity)),
'missingRelease',(SELECT COUNT(*) FROM order_item i JOIN orders o ON o.id=i.order_id WHERE o.inventory_released=TRUE AND NOT EXISTS(SELECT 1 FROM inventory_ledger l WHERE l.order_id=o.id AND l.dish_id=i.dish_id AND l.kind='RELEASE' AND l.delta=i.quantity)),
'badRefund',(SELECT COUNT(*) FROM orders o WHERE payment_status='SIMULATED_REFUNDED' AND ((SELECT COUNT(*) FROM payment_record p WHERE p.order_id=o.id AND p.kind='REFUND')<>1)),
'orphanItems',(SELECT COUNT(*) FROM order_item i LEFT JOIN orders o ON o.id=i.order_id AND o.merchant_id=i.merchant_id WHERE o.id IS NULL));"""
cmd=['docker','compose','--env-file',a.env_file,'-p',a.project,'-f','docker-compose.yml','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N ai_order_assistant']
r=subprocess.run(cmd,input=sql,text=True,capture_output=True,check=True);checks=json.loads(r.stdout)
report={'passed':all(x==0 for x in checks.values()),'checks':checks}
Path(a.output).parent.mkdir(parents=True,exist_ok=True);Path(a.output).write_text(json.dumps(report,indent=2))
print(json.dumps(report));raise SystemExit(0 if report['passed'] else 1)
