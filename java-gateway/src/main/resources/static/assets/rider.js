import { api, run, state } from "./api.js";
import { initAuth } from "./auth.js";
import {
  node,
  button,
  card,
  form,
  labels,
  field,
  select,
  watchEvents,
} from "./commerce-ui.js";
import { createFulfillmentList, isHistoricalTask } from "./fulfillment-list.js";
import { navigateToAddress } from "./integrations.js";

const tasks = createFulfillmentList("/delivery-tasks", "骑手任务", {
  extraScopes: [["available", "可抢任务"], ["mine", "我的进行中"]],
});
async function refresh({ preserveEditing = false } = {}) {
  const el = document.querySelector("#tasks");
  const result = await tasks.read();
  if (!result.isCurrent()) return false;
  const content = node("div");
  content.append(tasks.controls(result, refresh));
  for (const t of result.rows) {
    const c = card(t.merchant_name + " · " + (labels[t.status] ?? t.status));
    c.append(
      node(
        "p",
        "配送任务 " +
          t.id +
          " · 店内订单 #" +
          t.order_id +
          (t.user_seq ? " / 顾客订单 " + t.user_seq : "") +
          " · 取餐校区 " +
          (t.origin_campus_name ?? t.origin_campus) +
          " · 送达校区 " + (t.destination_campus_name ?? t.campus_id),
      ),
    );
    c.append(node("p", "取餐点：" + (t.pickup_point || "请向店铺确认")));
    if (t.status === "EXCEPTION")
      c.append(node("p", "已报告异常，等待平台处理。"));
    else if (t.exception_reason && ["ASSIGNED", "PICKED_UP", "DELIVERED"].includes(t.status))
      c.append(node("p", "平台已更新本次配送安排。"));
    if (t.recipient) {
      c.append(
        node(
          "p",
          t.recipient.recipient_name +
            " · " +
            t.recipient.recipient_phone +
            " · " +
            t.recipient.delivery_address,
        ),
      );
      c.append(button("导航到收货地址", () => navigateToAddress(t.recipient.delivery_address)));
    } else
      c.append(
        node(
          "p",
          isHistoricalTask(t)
            ? "历史任务不再展示收货信息"
            : "接单后展示必要收货信息",
        ),
      );
    const action = async (action, code) => {
      await api("/delivery-tasks/" + t.id + "/transition", {
        method: "POST",
        body: { action, code },
      });
      await refresh();
    };
    if (t.status === "WAITING")
      c.append(
        button("抢单", async () => {
          await api("/delivery-tasks/" + t.id + "/claim", {
            method: "POST",
            body: { expectedVersion: t.version },
          });
          await refresh();
        }),
      );
    if (t.status === "ASSIGNED")
      c.append(
        button("已取餐", () => action("COLLECT")),
        button("放回任务池", () => action("RELEASE")),
      );
    if (t.status === "PICKED_UP")
      c.append(
        form([["code", "顾客提供的送达码", "text", ""]], "核验送达", (d) =>
          action("DELIVER", d.code),
        ),
      );
    if (["ASSIGNED", "PICKED_UP"].includes(t.status)) {
      const report = form([["reason", "异常原因", "text", ""]], "报告异常", (d) =>
        action("EXCEPTION", d.reason),
      );
      const reasons = select([
        ["", "选择常见原因"],
        ["进不去校门", "进不去校门"],
        ["联系不上顾客", "联系不上顾客"],
        ["店铺未出餐", "店铺未出餐"],
        ["车辆或路线问题", "车辆或路线问题"],
      ]);
      reasons.addEventListener("change", () => {
        if (reasons.value) report.elements.reason.value = reasons.value;
        report.elements.reason.focus();
      });
      report.prepend(field("常见异常", reasons));
      report.elements.reason.maxLength = 500;
      c.append(report);
    }
    if (t.exception_reason) c.append(node("p", t.exception_reason));
    content.append(c);
  }
  if (!result.rows.length) content.append(node("p", "当前页暂无配送任务。"));
  // An event may finish after the rider starts typing a code or explanation.
  if (
    !result.isCurrent() ||
    (preserveEditing && el.contains(document.activeElement))
  )
    return false;
  el.replaceChildren(...content.childNodes);
  return true;
}
document
  .querySelector("#refresh")
  .addEventListener("click", () => run(refresh));
initAuth(async () => {
  await refresh();
  watchEvents("/rider/events", "rider-events:" + state.me.adminId, async () => {
    if (document.querySelector("#tasks").contains(document.activeElement))
      return false;
    return refresh({ preserveEditing: true });
  }).catch(() => {});
});
