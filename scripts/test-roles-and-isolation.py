"""Provision two disposable shops and exercise their HTTP permission boundaries."""
import argparse
import json
import secrets
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event

import httpx

from http_contract import Session


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:19092")
    parser.add_argument("--env-file", default=".github/compose-ci.env")
    parser.add_argument("--report", default="load/results/roles-and-isolation.json")
    args = parser.parse_args()
    values = dict(line.split("=", 1) for line in Path(args.env_file).read_text(encoding="utf-8-sig").splitlines()
                  if "=" in line and not line.startswith("#"))
    checks, sessions = [], []

    def session(merchant=None, management=False):
        result = Session(args.base_url, merchant, management)
        sessions.append(result)
        return result

    def check(name, condition):
        checks.append({"check": name, "passed": bool(condition)})
        assert condition, name

    def denied(name, response):
        check(name, response.status_code in (400, 401, 403, 404))

    def receive_events(owner, ready):
        events, event_name = [], ""
        try:
            with owner.client.stream("GET", "/admin/events", headers=owner.headers, timeout=3) as response:
                assert response.status_code == 200, "SSE authentication failed"
                for line in response.iter_lines():
                    if line.startswith("event:"):
                        event_name = line[6:].strip()
                    elif line.startswith("data:"):
                        if event_name == "connected":
                            ready.set()
                        elif event_name == "order-status":
                            events.append(json.loads(line[5:]))
        except httpx.ReadTimeout:
            pass  # Observe the quiet foreign stream for three seconds.
        return events

    try:
        platform = session(management=True)
        platform.login(values["PLATFORM_ADMIN_USERNAME"], values["PLATFORM_ADMIN_PASSWORD"], True)
        suffix, password = secrets.token_hex(5), secrets.token_urlsafe(24)
        owners, shops, dishes = [], [], []
        for index in range(2):
            name = f"isolation_{suffix}_{index}"
            shop = platform.ok("POST", "/platform/merchants", {
                "name": f"验收商户 {index}", "ownerUsername": name, "ownerPassword": password})
            owner = session(management=True)
            account = owner.login(name, password, True)
            check(f"owner_{index}_bound_to_provisioned_shop", account["merchantId"] == shop["id"])
            shop = owner.ok("PUT", "/admin/merchant", {
                "name": shop["name"], "acceptingOrders": True, "opensAt": "00:00", "closesAt": "00:00",
                "deliveryRegions": ["验收区域"], "expectedVersion": shop["version"]})
            dish = owner.ok("POST", "/admin/dishes", {
                "name": "验收套餐", "price": 10 + index, "category": "套餐", "stock": 4,
                "status": 1, "allergens": "", "allergenReviewed": True})
            owners.append(owner); shops.append(shop); dishes.append(dish)
        first, second = owners
        denied("owner_cannot_switch_shop_parameter", first.response("GET", "/admin/orders", extra={"X-Merchant-Id": str(shops[1]["id"])}))
        denied("foreign_menu_update_rejected", first.response("PUT", f"/admin/dishes/{dishes[1]['id']}", dishes[1]))
        denied("foreign_menu_delete_rejected", first.response("DELETE", f"/admin/dishes/{dishes[1]['id']}"))
        check("owner_menu_scoped", {d["id"] for d in first.ok("GET", "/admin/dishes")} == {dishes[0]["id"]})
        staff_name = "staff_" + suffix
        first.ok("POST", "/admin/staff", {"username": staff_name, "password": password})
        staff = session(management=True); staff.login(staff_name, password, True)
        denied("staff_cannot_manage_people", staff.response("GET", "/admin/staff"))
        denied("staff_cannot_manage_menu", staff.response("POST", "/admin/dishes", dishes[0]))
        customer = session(shops[0]["id"])
        username = "customer_" + suffix
        customer.ok("POST", "/auth/register", {"username": username, "password": password})
        denied("customer_cannot_use_merchant_interface", customer.response("GET", "/admin/orders"))
        menu = customer.ok("GET", "/dish/list")["items"]
        check("customer_menu_cache_scoped", {d["id"] for d in menu} == {dishes[0]["id"]})
        denied("foreign_dish_cannot_enter_draft", customer.response("POST", "/order/drafts", {"items": [{"dishId": dishes[1]["id"], "quantity": 1}]}))
        draft = customer.ok("POST", "/order/drafts", {"items": [{"dishId": dishes[0]["id"], "quantity": 1}]})
        receipt = {"expectedVersion": draft["version"], "recipientName": "验收", "recipientPhone": "13800000000", "deliveryAddress": "验收楼", "deliveryRegion": "验收区域"}
        with ThreadPoolExecutor(max_workers=2) as pool:
            ready_a, ready_b = Event(), Event()
            stream_a = pool.submit(receive_events, first, ready_a)
            stream_b = pool.submit(receive_events, second, ready_b)
            check("both_tenant_sse_streams_connected", ready_a.wait(5) and ready_b.wait(5))
            order = customer.ok("POST", f"/order/drafts/{draft['id']}/confirm", receipt, {"Idempotency-Key": "isolation-" + suffix})
            customer.ok("POST", f"/order/{order['userSeq']}/pay")
            own_events, foreign_events = stream_a.result(timeout=5), stream_b.result(timeout=5)
            check("live_sse_delivers_own_order", any(e["orderId"] == order["id"] for e in own_events))
            check("live_sse_hides_foreign_order", not any(e["orderId"] == order["id"] for e in foreign_events))
        denied("foreign_order_detail_rejected", second.response("GET", f"/admin/orders/{order['id']}"))
        denied("foreign_order_transition_rejected", second.response("POST", f"/admin/orders/{order['id']}/status?status=2"))
        check("foreign_orders_and_events_hidden", second.ok("GET", "/admin/orders")["total"] == 0 and not second.ok("GET", "/admin/events/replay"))
        for status in (2, 3, 4):
            current = staff.ok("POST", f"/admin/orders/{order['id']}/status?status={status}")
            check(f"staff_manual_fulfilment_{status}", current["status"] == status)
        check("completed_order_visible_to_customer", customer.ok("GET", f"/order/{order['userSeq']}")["status"] == 4)
        staff_id = next(s["id"] for s in first.ok("GET", "/admin/staff") if s["username"] == staff_name)
        first.ok("PUT", f"/admin/staff/{staff_id}/enabled", {"enabled": False})
        check("disabled_staff_cookie_revoked", staff.response("GET", "/auth/me").status_code == 401)
        platform.ok("PUT", f"/platform/merchants/{shops[1]['id']}/enabled", {"enabled": False})
        check("disabled_merchant_owner_cookie_revoked", second.response("GET", "/auth/me").status_code == 401)
        for _ in range(10):
            check("wrong_password_rejected", customer.response("POST", "/auth/login", {"username": username, "password": "invalid-password"}).status_code == 401)
        check("login_account_limit_returns_429", customer.response("POST", "/auth/login", {"username": username, "password": "invalid-password"}).status_code == 429)
        report = {"passed": True, "disposableMerchants": 2, "checks": checks}
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps({"passed": True, "checks": len(checks)}))
    finally:
        for item in sessions:
            item.close()


if __name__ == "__main__":
    main()
