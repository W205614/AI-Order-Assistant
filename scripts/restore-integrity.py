"""Stream full-table hashes and domain invariants from an independent restored MySQL database."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import threading

from integration_settings import write_private_json
from media_manifest import canonical_bytes, manifest_digest

LEGACY_TABLES = "merchant user admin_user dish orders order_item user_food_preference order_draft order_draft_item user_order_sequence order_safety_context inventory_ledger order_event audit_log payment_record revoked_token auth_rate_window".split()
CAMPUS_TABLES = "order_event_cursor campus delivery_rule customer_address product_sku product_bundle bundle_component cart_item favorite coupon_campaign customer_coupon coupon_ledger reservation_slot checkout_quote checkout order_unit customer_order_code order_stock_component sku_inventory_ledger commerce_payment aftersale aftersale_unit rider_campus delivery_task feedback commerce_event_cursor commerce_event".split()
DOMAINS = {
    "payments": "merchant_payment_binding platform_coupon_participation provider_payment provider_payment_part provider_refund provider_callback payment_oauth_state payment_wechat_identity payment_reconciliation payment_reconciliation_difference payment_provider_fee".split(),
    "phone": ["verified_phone", "phone_challenge"],
    "media": ["media_asset"],
    "notifications": ["notification", "notification_cursor", "notification_task"],
}

# These names are the release gate contract. Every query returns a violation count, never data.
INTEGRATION_INVARIANTS = {
    "provider_payment_ownership": "SELECT COUNT(*) FROM provider_payment p LEFT JOIN user u ON u.id=p.user_id LEFT JOIN checkout c ON p.source_type='CHECKOUT' AND c.id=p.source_id LEFT JOIN orders o ON p.source_type='ORDER' AND CAST(o.id AS CHAR)=p.source_id WHERE u.id IS NULL OR p.mode NOT IN ('SIMULATED','WECHAT_PARTNER') OR p.source_type NOT IN ('ORDER','CHECKOUT') OR (p.source_type='CHECKOUT' AND (c.id IS NULL OR c.user_id<>p.user_id OR c.payment_mode<>p.mode)) OR (p.source_type='ORDER' AND (o.id IS NULL OR o.user_id<>p.user_id OR o.payment_mode<>p.mode))",
    "provider_part_totals": "SELECT COUNT(*) FROM provider_payment p LEFT JOIN (SELECT payment_id,COUNT(*) n,SUM(amount) amount FROM provider_payment_part GROUP BY payment_id) t ON t.payment_id=p.id WHERE p.amount<0 OR t.n IS NULL OR t.amount<>p.amount",
    "provider_refund_links": "SELECT COUNT(*) FROM provider_payment_part p LEFT JOIN provider_payment h ON h.id=p.payment_id LEFT JOIN orders o ON o.id=p.order_id WHERE h.id IS NULL OR o.id IS NULL OR p.merchant_id<>o.merchant_id OR h.user_id<>o.user_id OR p.amount<>o.total_amount*100 OR (h.source_type='CHECKOUT' AND NOT(h.source_id<=>o.checkout_id)) OR (h.source_type='ORDER' AND h.source_id<>CAST(o.id AS CHAR)) OR p.amount<0 UNION ALL SELECT COUNT(*) FROM provider_refund r LEFT JOIN provider_payment_part p ON p.order_id=r.order_id AND p.payment_id=r.payment_id WHERE p.order_id IS NULL OR r.amount<0 OR r.amount>p.amount",
    "provider_refund_bounds": "SELECT COUNT(*) FROM orders WHERE refunded_amount<0 OR refund_pending_amount<0 OR refunded_amount+refund_pending_amount>total_amount UNION ALL SELECT COUNT(*) FROM (SELECT p.order_id,p.amount,SUM(r.amount) requested FROM provider_payment_part p JOIN provider_refund r ON r.order_id=p.order_id GROUP BY p.order_id,p.amount HAVING requested>p.amount) invalid",
    "provider_payment_success": "SELECT COUNT(*) FROM provider_payment_part p JOIN provider_payment h ON h.id=p.payment_id WHERE (h.status='PAID' AND p.status<>'PAID') OR (h.mode='WECHAT_PARTNER' AND p.amount>0 AND p.status='PAID' AND (p.transaction_id IS NULL OR p.paid_at IS NULL))",
    "merchant_payment_ownership": "SELECT COUNT(*) FROM merchant_payment_binding b LEFT JOIN merchant m ON m.id=b.merchant_id WHERE m.id IS NULL OR CHAR_LENGTH(b.sub_mch_id)=0",
    "platform_coupon_ownership": "SELECT COUNT(*) FROM platform_coupon_participation p LEFT JOIN coupon_campaign c ON c.id=p.campaign_id LEFT JOIN merchant m ON m.id=p.merchant_id WHERE c.id IS NULL OR c.merchant_id IS NOT NULL OR m.id IS NULL",
    "oauth_account_ownership": "SELECT COUNT(*) FROM payment_oauth_state s LEFT JOIN user u ON u.id=s.user_id WHERE u.id IS NULL UNION ALL SELECT COUNT(*) FROM payment_wechat_identity i LEFT JOIN user u ON u.id=i.user_id WHERE u.id IS NULL OR CHAR_LENGTH(i.app_id)=0 OR CHAR_LENGTH(i.open_id)=0",
    "phone_account_ownership": "SELECT COUNT(*) FROM verified_phone p LEFT JOIN user u ON p.account_type='CUSTOMER' AND u.id=p.account_id LEFT JOIN admin_user a ON p.account_type='MANAGEMENT' AND a.id=p.account_id WHERE p.account_type NOT IN ('CUSTOMER','MANAGEMENT') OR (p.account_type='CUSTOMER' AND u.id IS NULL) OR (p.account_type='MANAGEMENT' AND a.id IS NULL)",
    "phone_challenge_bounds": "SELECT COUNT(*) FROM phone_challenge p LEFT JOIN user u ON p.account_type='CUSTOMER' AND u.id=p.account_id LEFT JOIN admin_user a ON p.account_type='MANAGEMENT' AND a.id=p.account_id WHERE p.account_type NOT IN ('CUSTOMER','MANAGEMENT') OR p.purpose NOT IN ('BIND','RECOVER') OR p.attempts<0 OR p.attempts>5 OR p.expires_at<p.created_at OR (p.account_id IS NULL AND p.purpose<>'RECOVER') OR (p.account_id IS NOT NULL AND ((p.account_type='CUSTOMER' AND u.id IS NULL) OR (p.account_type='MANAGEMENT' AND a.id IS NULL)))",
    "media_resource_ownership": "SELECT COUNT(*) FROM media_asset m LEFT JOIN user u ON m.owner_type='CUSTOMER' AND u.id=m.owner_id LEFT JOIN admin_user au ON m.owner_type='MANAGEMENT' AND au.id=m.owner_id LEFT JOIN dish d ON m.purpose='DISH' AND CAST(d.id AS CHAR)=m.resource_id LEFT JOIN merchant me ON m.purpose='MERCHANT' AND CAST(me.id AS CHAR)=m.resource_id LEFT JOIN aftersale a ON m.purpose='AFTERSALE' AND a.id=m.resource_id LEFT JOIN orders o ON o.id=a.order_id WHERE m.purpose NOT IN ('DISH','MERCHANT','AFTERSALE') OR m.owner_type NOT IN ('CUSTOMER','MANAGEMENT') OR (m.owner_type='CUSTOMER' AND (u.id IS NULL OR m.purpose<>'AFTERSALE' OR a.user_id<>u.id)) OR (m.owner_type='MANAGEMENT' AND au.id IS NULL) OR (m.purpose='DISH' AND d.id IS NULL) OR (m.purpose='MERCHANT' AND me.id IS NULL) OR (m.purpose='AFTERSALE' AND (a.id IS NULL OR o.id IS NULL)) OR (m.owner_type='MANAGEMENT' AND au.role<>'PLATFORM_ADMIN' AND (au.role NOT IN ('OWNER','STAFF') OR (m.purpose<>'AFTERSALE' AND au.role<>'OWNER') OR NOT(au.merchant_id<=>CASE m.purpose WHEN 'DISH' THEN d.merchant_id WHEN 'MERCHANT' THEN me.id ELSE o.merchant_id END)))",
    "media_metadata": "SELECT COUNT(*) FROM media_asset WHERE provider NOT IN ('local','oss') OR byte_length<=0 OR byte_length>5242880 OR sha256 NOT REGEXP '^[0-9a-f]{64}$' OR content_type NOT IN ('image/jpeg','image/png') OR object_key NOT REGEXP '^[a-f0-9-]{36}\\.(jpg|png)$' OR object_key NOT LIKE CONCAT(id,'.%')",
    "notification_account_ownership": "SELECT COUNT(*) FROM notification n LEFT JOIN user u ON n.account_type='CUSTOMER' AND u.id=n.account_id LEFT JOIN admin_user a ON n.account_type='MANAGEMENT' AND a.id=n.account_id WHERE n.account_type NOT IN ('CUSTOMER','MANAGEMENT') OR (n.account_type='CUSTOMER' AND u.id IS NULL) OR (n.account_type='MANAGEMENT' AND a.id IS NULL)",
    "notification_task_ownership": "SELECT COUNT(*) FROM notification_task t LEFT JOIN notification n ON n.id=t.notification_id WHERE n.id IS NULL OR t.attempts<0 OR t.status NOT IN ('PENDING','SENDING','ACCEPTED','FAILED','UNKNOWN','SKIPPED')",
    "notification_cursor_bounds": "SELECT COUNT(*) FROM notification_cursor c WHERE c.source NOT IN ('commerce','order') OR c.last_id<0 OR (c.source='commerce' AND c.last_id>(SELECT COALESCE(MAX(id),0) FROM commerce_event)) OR (c.source='order' AND c.last_id>(SELECT COALESCE(MAX(id),0) FROM order_event)) UNION ALL SELECT IF(COUNT(*)=2,0,1) FROM notification_cursor",
    "reconciliation_ownership": "SELECT COUNT(*) FROM payment_reconciliation_difference d LEFT JOIN payment_reconciliation b ON b.id=d.batch_id WHERE b.id IS NULL UNION ALL SELECT COUNT(*) FROM payment_provider_fee f LEFT JOIN payment_reconciliation b ON b.id=f.batch_id WHERE b.id IS NULL OR f.bill_date<>b.bill_date OR f.sub_mch_id<>b.sub_mch_id UNION ALL SELECT COUNT(*) FROM provider_callback WHERE payload_hash NOT REGEXP '^[0-9a-f]{64}$'",
}
BASE_INVARIANTS = {
    "orphan_items": "SELECT COUNT(*) FROM order_item i LEFT JOIN orders o ON o.id=i.order_id AND o.merchant_id=i.merchant_id WHERE o.id IS NULL",
    "orphan_draft_items": "SELECT COUNT(*) FROM order_draft_item i LEFT JOIN order_draft d ON d.id=i.draft_id AND d.merchant_id=i.merchant_id WHERE d.id IS NULL",
    "negative_inventory": "SELECT COUNT(*) FROM dish WHERE stock<0",
}
CAMPUS_INVARIANTS = {
    "campus_orphan_components": "SELECT COUNT(*) FROM order_stock_component c JOIN order_item i ON i.id=c.order_item_id LEFT JOIN product_sku s ON s.id=c.sku_id AND s.merchant_id=i.merchant_id WHERE s.id IS NULL",
    "campus_refund_bounds": "SELECT COUNT(*) FROM orders WHERE commerce_version=2 AND (refunded_amount<0 OR refunded_amount>total_amount)",
    "campus_capacity_bounds": "SELECT COUNT(*) FROM reservation_slot WHERE reserved_count<0 OR reserved_count>capacity",
}


def mysql_lines(container, sql, timeout=600):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", container):
        raise ValueError("Invalid database container identifier")
    command = ["docker", "exec", "-i", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names --default-character-set=utf8mb4 ai_order_assistant']
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    timer = threading.Timer(timeout, process.kill)
    timer.daemon = True
    timer.start()
    write_failure = []
    def feed_query():
        try:
            process.stdin.write(sql.encode("utf-8"))
        except OSError as error:
            write_failure.append(error)
        finally:
            process.stdin.close()
    writer = threading.Thread(target=feed_query, daemon=True)
    try:
        # Read output while feeding the SQL batch: Windows pipes can fill before a full batch is sent.
        writer.start()
        for raw in process.stdout:
            yield raw.rstrip(b"\r\n")
        error = process.stderr.read()
        if process.wait() != 0:
            raise RuntimeError("Database fingerprint query failed; data was not logged")
        writer.join(timeout=5)
        if writer.is_alive() or write_failure:
            raise RuntimeError("Database fingerprint input did not complete")
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
            process.wait()


def quoted(name):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
        raise ValueError("Invalid schema identifier")
    return "`" + name + "`"


def capture(container, mode):
    tables = LEGACY_TABLES + (CAMPUS_TABLES if mode != "legacy" else [])
    if mode == "integration":
        tables += [table for group in DOMAINS.values() for table in group]
    names = ",".join("'" + table + "'" for table in tables)
    sql = "SELECT JSON_ARRAY('COLUMN',TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,EXTRA,COLLATION_NAME) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN (" + names + ") ORDER BY TABLE_NAME,ORDINAL_POSITION;\n"
    sql += "SELECT JSON_ARRAY('PRIMARY',TABLE_NAME,COLUMN_NAME) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name='PRIMARY' AND table_name IN (" + names + ") ORDER BY TABLE_NAME,SEQ_IN_INDEX;\n"
    sql += "SELECT JSON_ARRAY('FLYWAY',COUNT(*)) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history';\n"
    columns, primary, metadata, flyway = {}, {}, {}, False
    for raw in mysql_lines(container, sql):
        row = json.loads(raw)
        if row[0] == "COLUMN":
            columns.setdefault(row[1], []).append(row[2])
            metadata.setdefault(row[1], []).append(row[2:])
        elif row[0] == "PRIMARY":
            primary.setdefault(row[1], []).append(row[2])
        elif row[0] == "FLYWAY":
            flyway = row[1] == 1
    if set(columns) != set(tables) or any(not primary.get(table) for table in tables):
        raise ValueError("Selected recovery schema is incomplete or lacks primary keys")
    fingerprints = {table: {"rows": 0, "sha256": None, "schemaSHA256": hashlib.sha256(canonical_bytes(metadata[table])).hexdigest()} for table in sorted(tables)}
    hashes = {table: hashlib.sha256() for table in tables}
    invariants = dict(BASE_INVARIANTS)
    if mode != "legacy":
        invariants.update(CAMPUS_INVARIANTS)
    if mode == "integration":
        invariants.update(INTEGRATION_INVARIANTS)
    sql = "SET SESSION time_zone='+08:00'; SET SESSION MAX_EXECUTION_TIME=30000; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n"
    for table in sorted(tables):
        sql += "SELECT JSON_ARRAY('ROW','" + table + "',JSON_ARRAY(" + ",".join(quoted(column) for column in columns[table]) + ")) FROM " + quoted(table) + " ORDER BY " + ",".join(quoted(key) for key in primary[table]) + ";\n"
    for name, query in invariants.items():
        sql += "SELECT JSON_ARRAY('INVARIANT','" + name + "',SUM(violations)) FROM (" + query.replace("SELECT COUNT(*)", "SELECT COUNT(*) violations").replace("SELECT IF(COUNT(*)=2,0,1)", "SELECT IF(COUNT(*)=2,0,1) violations") + ") counts;\n"
    if flyway:
        sql += "SELECT JSON_ARRAY('VERSION',COALESCE(MAX(CAST(version AS UNSIGNED)),0)) FROM flyway_schema_history WHERE success=1;\n"
    if mode == "integration":
        sql += "SELECT JSON_ARRAY('MEDIA',id,purpose,resource_id,owner_type,owner_id,provider,object_key,content_type,byte_length,sha256) FROM media_asset ORDER BY id;\n"
    sql += "COMMIT;\n"
    observed, assets, version = {}, [], 0
    for raw in mysql_lines(container, sql):
        row = json.loads(raw)
        if row[0] == "ROW":
            hashes[row[1]].update(raw + b"\n")
            fingerprints[row[1]]["rows"] += 1
        elif row[0] == "INVARIANT":
            observed[row[1]] = int(row[2])
        elif row[0] == "VERSION":
            version = int(row[1])
        elif row[0] == "MEDIA":
            assets.append(dict(zip(("id", "purpose", "resourceId", "ownerType", "ownerId", "provider", "objectKey", "contentType", "byteLength", "sha256"), row[1:])))
    if set(observed) != set(invariants):
        raise ValueError("Recovery invariant checks did not complete")
    for table in fingerprints:
        fingerprints[table]["sha256"] = hashes[table].hexdigest()
    manifest = {"schemaVersion": 1, "assets": assets}
    result = {
        "schemaVersion": 2, "schemaMode": mode, "restoredSchemaVersion": version,
        "fingerprintPolicy": "mysql-json-sha256-v1", "tableFingerprints": fingerprints,
        "databaseFingerprintSHA256": hashlib.sha256(canonical_bytes(fingerprints)).hexdigest(),
        "invariants": observed,
        "integrationChecks": {"passed": mode == "integration" and all(value == 0 for value in observed.values()) and version >= 9, "domains": list(DOMAINS) if mode == "integration" else [], "invariants": {key: observed[key] for key in INTEGRATION_INVARIANTS} if mode == "integration" else {}},
        "mediaAssetCount": len(assets), "mediaManifestSHA256": manifest_digest(manifest),
        "testedAt": datetime.now(timezone.utc).isoformat(),
        "passed": all(value == 0 for value in observed.values()) and (mode != "integration" or version >= 9),
    }
    return result, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--schema-mode", choices=("legacy", "campus", "integration"), default="integration")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--media-manifest", type=Path)
    args = parser.parse_args()
    try:
        report, manifest = capture(args.container, args.schema_mode)
        if args.media_manifest:
            write_private_json(args.media_manifest, manifest)
        write_private_json(args.report, report)
        print(json.dumps({"passed": report["passed"], "tables": len(report["tableFingerprints"]), "mediaAssets": report["mediaAssetCount"]}))
        return int(not report["passed"])
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        write_private_json(args.report, {"schemaVersion": 2, "schemaMode": args.schema_mode, "passed": False, "error": "Recovery fingerprint or invariant verification failed", "testedAt": datetime.now(timezone.utc).isoformat()})
        print(json.dumps({"passed": False, "error": "Recovery integrity verification failed; no row data was logged"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
