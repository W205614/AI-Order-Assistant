import { api, state, node, button, notice, run, fmt, accountSignal, setAccount } from "./api.js";
import { capabilities } from "./payment-session.js";

let installed;

function panel(title, parent) {
  const box = node("details", undefined, { class: "integration-panel" });
  box.append(node("summary", title));
  parent.append(box);
  return box;
}

function input(form, name, label, type = "text") {
  const wrapper = node("label", label);
  const field = node("input", undefined, { name, type, required: "", "aria-label": label });
  if (type === "password") field.autocomplete = name === "currentPassword" ? "current-password" : "new-password";
  wrapper.append(field);
  form.append(wrapper);
  return field;
}

function submit(form, label, action) {
  const control = node("button", label, { type: "submit" });
  form.append(control);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    run(() => action(Object.fromEntries(new FormData(form))), control);
  });
}

function phoneForm(parent, recover, config) {
  const form = node("form");
  const description = node("p", config.sms
    ? "验证已绑定的手机号。收货电话不能作为账号验证凭据。"
    : "短信服务尚未启用，当前无法发送验证码或通过手机找回账号。");
  parent.append(description, form);
  if (recover) input(form, "username", "账号");
  const phone = input(form, "phone", "手机号", "tel");
  phone.pattern = "1[3-9][0-9]{9}";
  phone.addEventListener("input", () => delete form.dataset.challenge);
  if (recover) form.elements.username.addEventListener("input", () => delete form.dataset.challenge);
  const send = button("发送验证码", async () => {
    if (!phone.reportValidity()) return;
    const username = form.elements.username?.value;
    if (recover && !username) throw new Error("请先填写账号");
    const result = await api("/auth/phone/challenges", {
      method: "POST", signal: accountSignal(),
      body: { purpose: recover ? "RECOVER" : "BIND", phone: phone.value,
        accountType: state.management ? "MANAGEMENT" : "CUSTOMER", username },
    });
    form.dataset.challenge = result.challengeId;
    notice("请求已提交。若账号与手机号符合条件，请查收验证码；一分钟后可重新申请。");
  });
  send.disabled = !config.sms;
  form.append(send);
  input(form, "code", "短信验证码");
  input(form, recover ? "newPassword" : "currentPassword", recover ? "新密码（至少12位）" : "当前密码", "password");
  submit(form, recover ? "重置密码" : "验证并绑定手机号", async (data) => {
    if (!form.dataset.challenge) throw new Error("请先申请验证码");
    await api(recover ? "/auth/password/recovery" : "/auth/phone", {
      method: "POST", signal: accountSignal(), body: recover
        ? { challengeId: form.dataset.challenge, code: data.code, newPassword: data.newPassword }
        : { challengeId: form.dataset.challenge, code: data.code, currentPassword: data.currentPassword },
    });
    notice("操作完成，旧会话已失效，请重新登录。");
    setAccount(null);
    setTimeout(() => location.reload(), 1200);
  });
}

async function notifications(parent, controller) {
  const { signal } = controller;
  const box = panel("站内通知", parent), list = node("div");
  let before, page = [], hasMore = true;
  const render = () => {
    list.replaceChildren();
    for (const item of page) {
      const row = node("article");
      row.append(node("p", item.message), node("small", fmt(item.created_at) + (item.read_at ? " · 已读" : " · 未读")));
      if (!item.read_at)
        row.append(button("标记已读", async () => {
          await api("/notifications/" + item.id + "/read", { method: "POST", signal });
          item.read_at = new Date().toISOString();
          render();
        }));
      list.append(row);
    }
    if (!page.length) list.append(node("p", "暂无站内通知。"));
  };
  const load = async (more = false) => {
    if (more && !hasMore) return;
    const batch = await api("/notifications" + (more && before ? "?before=" + before : ""), { signal });
    if (signal.aborted) return;
    page = more ? [...page, ...batch] : batch;
    before = batch.at(-1)?.id;
    hasMore = batch.length === 50;
    older.disabled = !hasMore;
    render();
  };
  const older = button("较早通知", () => load(true));
  box.append(button("刷新通知", () => load()), list, older);
  await load();
  if (signal.aborted) return;
  let reconnectTimer;
  signal.addEventListener("abort", () => clearTimeout(reconnectTimer), { once: true });
  let after = Math.max(0, ...page.map((item) => Number(item.id)));
  async function connect() {
    try {
      const response = await fetch("/notifications/events?after=" + after, {
        credentials: "same-origin", signal,
        headers: state.management ? { "X-Session-Type": "management" } : {},
      });
      signal.throwIfAborted();
      if (response.status === 401 || response.status === 403) {
        setAccount(null);
        return;
      }
      if (!response.ok || !response.body) throw new Error("通知连接暂不可用");
      const reader = response.body.getReader(), decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
        let end;
        while ((end = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          if (!block.includes("event:notification") && !block.includes("event: notification")) continue;
          const id = block.match(/^id:\s*(\d+)/m)?.[1];
          await load();
          if (id) after = Math.max(after, Number(id));
        }
      }
    } catch (error) {
      if (signal.aborted) return;
      box.dataset.connection = "disconnected";
    }
    if (!signal.aborted) reconnectTimer = setTimeout(connect, 5000);
  }
  connect();
}

function maps(parent, config) {
  const box = panel("地址与路线参考", parent);
  if (!config.maps) {
    box.append(node("p", "地图服务尚未启用；仍可按校区规则填写和选择收货地址。"));
    return;
  }
  const form = node("form"), results = node("div");
  input(form, "query", "校区、楼宇或完整地址");
  submit(form, "查询候选地址", async (data) => {
    const candidates = await api("/maps/geocode?q=" + encodeURIComponent(data.query), { method: "POST" });
    results.replaceChildren();
    for (const candidate of candidates) {
      const row = node("p", candidate.formattedAddress + " · " + candidate.location);
      row.append(button("选用此位置", () => {
        box.querySelector("[name=destination]").value = candidate.location;
        const address = document.querySelector("#addressForm [name=detail]");
        if (address) address.value = candidate.formattedAddress;
        notice("已选用候选位置；请在收货地址中补全楼栋、楼层和房间，再保存地址。");
      }));
      results.append(row);
    }
    if (!candidates.length) results.append(node("p", "没有找到候选，请补全校区和城市。"));
  });
  const route = node("form"), routeResult = node("div");
  input(route, "origin", "出发位置（高德坐标，经度,纬度）");
  input(route, "destination", "目的位置（高德坐标，经度,纬度）");
  submit(route, "查询骑行路线", async (data) => {
    const result = await api("/maps/route?" + new URLSearchParams(data), { method: "POST" });
    routeResult.replaceChildren(node("p", "骑行参考：" + result.distance + "米，约" + Math.ceil(result.duration / 60) + "分钟。此时间不包含备餐且不保证送达。"));
    const url = new URL(result.navigationUrl);
    if (url.protocol !== "https:" || url.hostname !== "uri.amap.com") throw new Error("地图导航地址无效");
    routeResult.append(node("a", "打开高德导航", { href: url.href, target: "_blank", rel: "noopener noreferrer" }));
  });
  box.append(form, results, route, routeResult);
}

function platform(parent) {
  const box = panel("支付接入与运营处理", parent);
  const binding = node("form");
  input(binding, "merchantId", "店铺编号", "number");
  input(binding, "subMchId", "微信支付子商户号");
  const enabled = input(binding, "enabled", "启用收款绑定", "checkbox");
  enabled.required = false;
  submit(binding, "保存商户收款绑定", async (data) => {
    await api("/platform/merchant-payments/" + data.merchantId, {
      method: "PUT", body: { subMchId: data.subMchId, enabled: enabled.checked },
    });
    notice("已保存商户绑定；渠道产品权限和授权仍需在微信支付平台确认。");
  });
  const content = node("div"), date = node("input", undefined, { type: "date", "aria-label": "对账日期" });
  const list = async (path, title, action) => {
    const result = await api(path);
    const rows = path === "/platform/reconciliation"
      ? [...result.batches, ...result.differences]
      : result;
    content.replaceChildren(node("h3", title));
    const names = { id: "记录编号", merchant_id: "店铺编号", name: "名称", sub_mch_id: "微信子商户号",
      enabled: "收款启用", status: "状态", attempts: "处理次数", last_error: "异常代码", date: "日期",
      bill_date: "账单日期", difference_count: "差异数", kind: "差异类型", reason: "原因", detail: "核查信息", resolution: "处理意见" };
    for (const item of rows) {
      const row = node("article");
      row.append(node("p", Object.entries(item).filter(([key]) =>
        ["id", "merchant_id", "name", "sub_mch_id", "enabled", "status", "amount", "total_fen", "attempts", "last_error", "date", "bill_date", "difference_count", "kind", "reason", "detail", "resolution"].includes(key))
        .map(([key, value]) => ["amount", "total_fen"].includes(key)
          ? "金额 ¥" + (Number(value) / 100).toFixed(2)
          : names[key] + "：" + (key === "enabled" ? value ? "是" : "否" : value ?? "-"))
        .join(" · ")));
      if (action) row.append(button(action.label, () => action.run(item)));
      if (path === "/platform/reconciliation" && item.kind && !item.resolved_at) {
        const resolve = node("form");
        input(resolve, "reason", "核查与处理说明");
        submit(resolve, "记录差异处理意见", async (data) => {
          await api("/platform/reconciliation/differences/" + item.id + "/resolve", { method: "POST", body: data });
          await list(path, title);
        });
        row.append(resolve);
      }
      content.append(row);
    }
    if (!rows.length) content.append(node("p", "暂无记录。"));
  };
  box.append(binding,
    button("查看商户收款绑定", () => list("/platform/merchant-payments", "商户绑定")),
    button("查看支付及重新查单", () => list("/platform/payments", "支付单", {
      label: "向渠道重新查单", run: async (item) => {
        await api("/platform/payments/" + item.id + "/query", { method: "POST" });
        notice("已执行渠道查单，请刷新支付列表。");
      },
    })),
    button("查看退款", () => list("/platform/refunds", "退款单", {
      label: "重试允许重试的退款", run: async (item) => {
        await api("/platform/refunds/" + item.id + "/retry", { method: "POST" });
        notice("已提交退款处理，请查询渠道结果。");
      },
    })), date,
    button("下载并核对账单", async () => {
      if (!date.value) throw new Error("请选择对账日期");
      await api("/platform/reconciliation?date=" + date.value, { method: "POST" });
      await list("/platform/reconciliation", "对账结果");
    }),
    button("查看对账差异", () => list("/platform/reconciliation", "对账结果")),
    button("查看通知发送任务", () => list("/platform/notification-tasks", "通知任务", {
      label: "确认重复风险后重试", run: async (item) => {
        if (!confirm("渠道未知结果可能已经发送。确认需要再次发送，且接受重复通知风险？")) return;
        await api("/platform/notification-tasks/" + item.id + "/retry", {
          method: "POST", body: { acknowledgeDuplicateRisk: true },
        });
      },
    })), content);
}

async function merchantPromotions(parent) {
  if (state.me.role !== "OWNER") return;
  const box = panel("平台联合优惠参与", parent), list = node("div");
  const load = async () => {
    const rows = await api("/admin/platform-coupons");
    list.replaceChildren();
    for (const campaign of rows) {
      const row = node("p", campaign.name + " · " + (campaign.participating ? "已参加" : "未参加"));
      row.append(button(campaign.participating ? "退出活动" : "确认由本店承担优惠并参加", async () => {
        await api("/admin/platform-coupons/" + campaign.id + "/participation", {
          method: "PUT", body: { participating: !campaign.participating },
        });
        await load();
      }));
      list.append(row);
    }
    if (!rows.length) list.append(node("p", "暂无平台联合优惠活动。"));
  };
  box.append(node("p", "联合优惠按规则分摊，由参与店铺承担折扣，不包含平台现金补贴。"),
    button("刷新优惠活动", load), list);
}

export async function installIntegrationPanels() {
  const me = state.me, lifetime = accountSignal();
  const config = await capabilities();
  if (lifetime.aborted) return;
  const paymentNotice = document.querySelector(".page-footer span:last-child");
  if (paymentNotice) paymentNotice.textContent = config.paymentMode === "SIMULATED"
    ? "当前为模拟支付，订单不会产生真实扣款。"
    : "已启用微信支付，付款与退款以服务器及渠道确认结果为准。";
  const auth = document.querySelector("#auth"), account = document.querySelector("#account");
  if (auth && !auth.querySelector("[data-phone-recovery]")) {
    const recovery = panel("通过已验证手机号找回密码", auth);
    recovery.dataset.phoneRecovery = "";
    phoneForm(recovery, true, config);
  }
  if (!me || !account) return;
  const identity = [state.management ? "MANAGEMENT" : "CUSTOMER", me.adminId ?? me.userId,
    me.role, me.merchantId ?? ""].join(":");
  if (installed?.identity === identity) return;
  installed?.dispose();
  const phone = panel("绑定账号手机号", account);
  phoneForm(phone, false, config);
  const workspace = document.querySelector("#workspace");
  const section = node("section", undefined, { id: "integrationServices" });
  section.append(node("h2", "账号服务与消息"), node("p", config.paymentMode === "SIMULATED"
    ? "当前为模拟支付，不会产生真实资金扣款。" : "当前已启用微信支付，付款以渠道及服务器确认结果为准。"));
  workspace.append(section);
  const current = { identity, controller: new AbortController(), dispose: () => {
    current.controller.abort();
    phone.remove();
    section.remove();
    lifetime.removeEventListener("abort", current.dispose);
    if (installed === current) installed = undefined;
  } };
  installed = current;
  lifetime.addEventListener("abort", current.dispose, { once: true });
  if (me.role === "OWNER" && me.merchantId) {
    const pictures = panel("店铺图片", section);
    pictures.append(mediaControl("MERCHANT", me.merchantId));
  }
  maps(section, config);
  if (me.role === "PLATFORM_ADMIN") platform(section);
  try {
    if (me.role === "OWNER") await merchantPromotions(section);
    if (!current.controller.signal.aborted) await notifications(section, current.controller);
  } catch (error) {
    if (current.controller.signal.aborted) return;
    current.dispose();
    throw error;
  }
}

export async function appendProductImage(parent, dishId, title) {
  const images = await api("/media?" + new URLSearchParams({ purpose: "DISH", resourceId: String(dishId) }));
  if (images.length) parent.prepend(node("img", undefined,
    { src: mediaUrl(images[0].url), alt: title, class: "product-image", loading: "lazy" }));
}

function mediaUrl(value) {
  const url = new URL(value, location.origin);
  if (url.origin !== location.origin || !url.pathname.startsWith("/media/"))
    throw new Error("图片地址无效");
  if (state.management) url.searchParams.set("sessionType", "management");
  return url.href;
}

export function mediaControl(purpose, resourceId) {
  const form = node("form", undefined, { class: "context-media" });
  const file = input(form, "image", "上传 JPEG 或 PNG 图片（最多5MiB）", "file");
  file.accept = "image/jpeg,image/png";
  const previews = node("div", undefined, { class: "media-previews" });
  const show = async () => {
    const images = await api("/media?" + new URLSearchParams({ purpose, resourceId: String(resourceId) }));
    previews.replaceChildren(...images.map((image) => node("img", undefined,
      { src: mediaUrl(image.url), alt: purpose === "AFTERSALE" ? "售后凭证" : "已上传图片", loading: "lazy" })));
  };
  submit(form, "上传图片", async () => {
    if (!file.files[0] || file.files[0].size > 5 * 1024 * 1024) throw new Error("请选择不超过5MiB的图片");
    const body = new FormData(form);
    body.set("purpose", purpose);
    body.set("resourceId", String(resourceId));
    await api("/media", { method: "POST", body });
    file.value = "";
    await show();
  });
  form.append(button("查看图片", show), previews);
  return form;
}

export async function navigateToAddress(address) {
  const lifetime = accountSignal();
  const config = await capabilities();
  lifetime.throwIfAborted();
  if (!config.maps) throw new Error("地图服务尚未启用，请按任务中的文字地址配送");
  const candidates = await api("/maps/geocode?q=" + encodeURIComponent(address), { method: "POST", signal: lifetime });
  if (!candidates.length) throw new Error("地图未找到该地址，请核对楼宇或联系顾客确认");
  const dialog = node("dialog", undefined, { class: "integration-dialog" });
  dialog.append(node("h2", "核对配送目的地"), node("p", "地图结果需与任务地址核对，校内楼栋与入口可能需要人工确认。"));
  for (const candidate of candidates) {
    const query = new URLSearchParams({ to: candidate.location, mode: "ride", coordinate: "gaode", src: "AI-Order-Assistant" });
    dialog.append(node("p", candidate.formattedAddress), node("a", "使用高德骑行导航", {
      href: "https://uri.amap.com/navigation?" + query, target: "_blank", rel: "noopener noreferrer",
    }));
  }
  dialog.append(button("关闭", () => dialog.close()));
  const dispose = () => {
    dialog.close();
    dialog.remove();
  };
  lifetime.addEventListener("abort", dispose, { once: true });
  dialog.addEventListener("close", () => {
    lifetime.removeEventListener("abort", dispose);
    dialog.remove();
  }, { once: true });
  document.body.append(dialog);
  dialog.showModal();
}

window.addEventListener("pagehide", () => installed?.dispose());
window.addEventListener("pageshow", (event) => {
  if (event.persisted) installIntegrationPanels().catch((error) => notice("账号服务暂不可用：" + error.message, true));
});
