import { api, node, button, notice, run, state, accountSignal } from "./api.js";

let configured;
export async function capabilities({ refresh = false } = {}) {
  if (!configured || refresh) configured = api("/integrations/capabilities");
  try {
    return await configured;
  } catch (error) {
    configured = undefined;
    throw error;
  }
}

export function paymentLabel(config, unified = false) {
  return (unified ? "统一" : "") + (config.paymentMode === "SIMULATED" ? "模拟支付" : "微信支付");
}

function qrCanvas(value) {
  if (!globalThis.qrcodegen) throw new Error("二维码组件未加载，请刷新页面");
  const qr = globalThis.qrcodegen.QrCode.encodeText(value, globalThis.qrcodegen.QrCode.Ecc.MEDIUM);
  const canvas = node("canvas", undefined, { "aria-label": "微信支付二维码" });
  const border = 4, scale = 5;
  canvas.width = canvas.height = (qr.size + border * 2) * scale;
  const context = canvas.getContext("2d");
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "#000000";
  for (let y = 0; y < qr.size; y++)
    for (let x = 0; x < qr.size; x++)
      if (qr.getModule(x, y)) context.fillRect((x + border) * scale, (y + border) * scale, scale, scale);
  return canvas;
}

async function invokeJsapi(params) {
  if (!globalThis.WeixinJSBridge)
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        document.removeEventListener("WeixinJSBridgeReady", ready);
        reject(new Error("微信支付组件暂不可用，请在微信内重新打开页面"));
      }, 10000);
      function ready() {
        clearTimeout(timeout);
        resolve();
      }
      document.addEventListener("WeixinJSBridgeReady", ready, { once: true });
    });
  await new Promise((resolve, reject) => {
    globalThis.WeixinJSBridge.invoke("getBrandWCPayRequest", params, (result) => {
      if (result.err_msg === "get_brand_wcpay_request:ok") resolve();
      else reject(new Error(result.err_msg?.includes("cancel") ? "已取消微信支付，请查询订单确认当前状态" : "微信收银台未确认支付，请查询订单状态"));
    });
  });
}

function safeH5Url(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.hostname !== "wx.tenpay.com" || url.username || url.password)
    throw new Error("渠道返回了无效的微信支付地址");
  return url;
}

function customerPage() {
  return location.pathname.startsWith("/chat") ? "/chat/" : "/campus/";
}

let activePayment;
let resumeRefresh;
let resuming;

function storageKey() {
  return state.me?.userId ? "payment-session:" + state.me.userId : null;
}

function rememberPayment(id) {
  const key = storageKey();
  if (key) sessionStorage.setItem(key, id);
}

function forgetPayment(id) {
  const key = storageKey();
  if (key && sessionStorage.getItem(key) === id) sessionStorage.removeItem(key);
  const url = new URL(location.href);
  if (url.searchParams.get("payment_session") === id) {
    url.searchParams.delete("payment_session");
    history.replaceState(null, "", url);
  }
}

async function openPayment(session, refresh, checked = false, lifetime = accountSignal()) {
  lifetime.throwIfAborted();
  if (session.mode !== "WECHAT_PARTNER") throw new Error("该支付会话不是微信支付，请核对订单记录");
  if (activePayment?.id === session.id) {
    await activePayment.query();
    return;
  }
  activePayment?.dialog.close();
  rememberPayment(session.id);
  const dialog = node("dialog", undefined, { class: "integration-dialog", "aria-busy": "true" });
  const status = node("p", "支付结果以服务器查询为准，请勿重复付款。");
  const actions = node("div", undefined, { class: "payment-actions" });
  let confirmed = false;
  const apply = async (result) => {
    lifetime.throwIfAborted();
    if (result.mode !== "WECHAT_PARTNER") throw new Error("支付记录模式不符，请核对当前账号");
    session = result;
    status.textContent = "当前状态：" + ({ PAID: "支付成功", CLOSED: "已关闭", UNKNOWN: "渠道结果尚未确认", PENDING: "等待支付", CREATED: "收银台准备中", REVIEW: "待平台核实", REFUNDING: "退款处理中", REFUNDED: "已退款", NO_CHARGE: "免支付订单" }[result.status] || result.status);
    actions.replaceChildren();
    if (["PAID", "CLOSED", "NO_CHARGE", "REFUNDED"].includes(result.status)) {
      forgetPayment(result.id);
      if (!confirmed) {
        confirmed = true;
        try {
          await refresh();
          if (["PAID", "NO_CHARGE"].includes(result.status)) notice("支付结果已确认");
          if (result.status === "REFUNDED") notice("退款结果已确认");
        } catch (error) {
          confirmed = false;
          throw error;
        }
      }
      return;
    }
    const action = result.action;
    if (action?.type === "QR") actions.append(qrCanvas(action.payload), node("p", "请使用微信扫一扫。"));
    if (action?.type === "REDIRECT")
      actions.append(button("前往微信收银台", () => {
        const target = safeH5Url(action.payload);
        const returnTo = new URL(customerPage(), location.origin);
        returnTo.searchParams.set("payment_session", result.id);
        target.searchParams.set("redirect_url", returnTo.href);
        rememberPayment(result.id);
        location.assign(target.href);
      }));
    if (action?.type === "JSAPI")
      actions.append(button("打开微信收银台", async () => {
        await invokeJsapi(action.payload);
        await query();
      }));
    if (!action || action.type === "NONE")
      actions.append(node("p", "收银台或渠道结果尚未就绪，请稍后查询；不要重复创建支付。"));
  };
  let querying;
  const query = async () => {
    if (querying) return querying;
    dialog.setAttribute("aria-busy", "true");
    queryButton.disabled = true;
    querying = (async () => {
      const result = await api("/payments/" + encodeURIComponent(session.id), { signal: lifetime });
      await apply(result);
    })();
    try {
      await querying;
    } finally {
      querying = undefined;
      queryButton.disabled = false;
      dialog.setAttribute("aria-busy", "false");
    }
  };
  const queryButton = button("查询支付结果", query);
  dialog.append(node("h2", "确认微信支付"), status, actions,
    queryButton, button("关闭", () => dialog.close()));
  const dispose = () => {
    if (activePayment?.dialog === dialog) activePayment = undefined;
    resumeRefresh = undefined;
    dialog.close();
    dialog.remove();
  };
  lifetime.addEventListener("abort", dispose, { once: true });
  dialog.addEventListener("close", () => {
    lifetime.removeEventListener("abort", dispose);
    if (activePayment?.dialog === dialog) activePayment = undefined;
    dialog.remove();
  }, { once: true });
  document.body.append(dialog);
  activePayment = { id: session.id, dialog, query };
  dialog.showModal();
  await apply(session);
  if (!checked && !confirmed) await run(query);
  else dialog.setAttribute("aria-busy", "false");
}

export async function resumePayment(refresh) {
  const lifetime = accountSignal();
  resumeRefresh = refresh;
  if (!state.me?.userId) return;
  if (resuming) return resuming;
  const url = new URL(location.href);
  const id = url.searchParams.get("payment_session") || sessionStorage.getItem(storageKey());
  if (!id) return;
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) {
    notice("付款返回标识无效，请从订单页面核对付款状态。", true);
    return;
  }
  resuming = run(async () => {
    const session = await api("/payments/" + encodeURIComponent(id), { signal: lifetime });
    await openPayment(session, refresh, true, lifetime);
  });
  try {
    await resuming;
  } finally {
    resuming = undefined;
  }
}

window.addEventListener("pageshow", () => {
  if (resumeRefresh) void resumePayment(resumeRefresh);
});
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && resumeRefresh) void resumePayment(resumeRefresh);
});

export async function startPayment(sourceType, sourceId, legacyPath, refresh) {
  const lifetime = accountSignal();
  resumeRefresh = refresh;
  const config = await capabilities();
  lifetime.throwIfAborted();
  if (config.paymentMode === "SIMULATED") {
    await api(legacyPath, { method: "POST" });
    await refresh();
    return;
  }
  const inWechat = /MicroMessenger/i.test(navigator.userAgent);
  const mobile = /Android|iPhone|iPad|Mobile/i.test(navigator.userAgent);
  const scene = inWechat ? "JSAPI" : mobile ? "H5" : "NATIVE";
  let session;
  try {
    session = await api("/payments", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: { sourceType, sourceId: String(sourceId), scene },
      signal: lifetime,
    });
  } catch (error) {
    if (error.code === "WECHAT_OPENID_REQUIRED") {
      location.assign("/integrations/wechat/oauth/start?returnTo=" + encodeURIComponent(customerPage()));
      return;
    }
    throw error;
  }
  await openPayment(session, refresh, false, lifetime);
}
