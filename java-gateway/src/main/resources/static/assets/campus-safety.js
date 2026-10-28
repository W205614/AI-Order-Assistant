import { api, notice } from "./api.js";
import { node, button } from "./commerce-ui.js";

export const safetyHeaders = (merchant) => ({
  "X-Merchant-Id": String(merchant),
});

export function createSafetyController({
  $,
  getShops,
  navigateToShop,
  invalidate,
  loadMenu,
  loadFavorites,
}) {
  // Keep unsaved choices by shop so refreshes and in-flight assistant responses cannot discard them.
  const safetyEdits = new Map();
  const safetyWrites = new Set();
  const safetyLocations = new Map();

  function requireSafetySaved() {
    if (safetyEdits.size || safetyWrites.size)
      throw Error(
        "有店铺的过敏选择尚未保存或正在保存，请在“待处理的过敏选择”中前往确认或放弃本页未保存选择。",
      );
  }
  function renderPendingSafety() {
    const el = $("#pendingSafetyEdits"),
      merchants = new Set([...safetyEdits.keys(), ...safetyWrites]);
    el.hidden = merchants.size === 0;
    el.replaceChildren();
    if (!merchants.size) return;
    el.append(
      node("h2", "待处理的过敏选择"),
      node(
        "p",
        "完成以下店铺的确认后才能继续点餐。放弃仅撤销本页未保存选择，不会清除已保存约束或购物车安全限制。",
      ),
    );
    for (const merchant of merchants) {
      const location = safetyLocations.get(merchant),
        saving = safetyWrites.has(merchant),
        row = node("div", undefined, { "data-merchant": merchant });
      row.append(
        node(
          "p",
          location.merchantName +
            " · " +
            location.campusName +
            " · " +
            (saving ? "正在保存，请稍候" : "选择尚未保存"),
        ),
      );
      row.append(
        button("前往确认", async () => {
          if (!(await navigateToShop(merchant, location))) return;
          $("#campusSafety").scrollIntoView({ block: "center" });
          $("#campusSafety input").focus();
        }),
      );
      const discard = button("放弃本页未保存选择", async () => {
        if (safetyWrites.has(merchant))
          throw Error("该店约束正在保存，请等待完成后再操作。");
        safetyEdits.delete(merchant);
        invalidate();
        reflectSafetyEdit();
        if ($("#shop").value === merchant) await loadMenu();
      });
      discard.disabled = saving;
      row.append(discard);
      el.append(row);
    }
  }
  function reflectSafetyEdit() {
    renderPendingSafety();
    if (
      !safetyWrites.has($("#shop").value) &&
      safetyEdits.has($("#shop").value)
    )
      $("#campusSafetyState").textContent =
        "选择尚未保存，请确认本店过敏约束后继续点餐。";
    for (const button of $("#campusMenu").querySelectorAll("button"))
      button.disabled = safetyEdits.size > 0 || safetyWrites.size > 0;
    $("#quoteButton").disabled = safetyEdits.size > 0 || safetyWrites.size > 0;
  }
  function resetSafety(message) {
    const panel = $("#campusSafety");
    panel.disabled = true;
    delete panel.dataset.merchant;
    for (const input of panel.querySelectorAll("input")) input.checked = false;
    $("#campusSafetyState").textContent = message;
  }
  function renderSafety(safety, merchant) {
    const panel = $("#campusSafety");
    panel.dataset.merchant = merchant;
    const selected = safetyEdits.get(merchant)?.allergens ?? safety.allergens;
    for (const input of panel.querySelectorAll("input"))
      input.checked = selected.includes(input.value);
    const name =
      getShops().find((s) => String(s.id) === merchant)?.name ?? "当前店铺";
    if (!safetyEdits.has(merchant) && !safetyWrites.has(merchant))
      safetyLocations.set(merchant, {
        merchantName: name,
        campusId: $("#campus").value,
        campusName: $("#campus").selectedOptions[0].textContent,
      });
    $("#campusSafetyState").textContent =
      name +
      "：" +
      (safety.needsClarification
        ? "待确认，请核对具体过敏原后确认。"
        : safety.allergens.length
          ? "已确认 " + safety.allergens.join("、")
          : "未设置临时过敏约束。");
    panel.disabled = safetyWrites.has(merchant);
    if (panel.disabled)
      $("#campusSafetyState").textContent = name + "：正在保存，请稍候。";
  }
  async function saveSafety(clear = false) {
    const panel = $("#campusSafety"),
      merchant = $("#shop").value,
      campus = $("#campus").value;
    const name =
      getShops().find((s) => String(s.id) === merchant)?.name ?? "所选店铺";
    if (
      panel.disabled ||
      panel.dataset.merchant !== merchant ||
      safetyWrites.has(merchant)
    )
      throw Error("店铺约束正在加载或保存，请稍候再试。");
    const allergens = [...panel.querySelectorAll("input:checked")].map(
      (input) => input.value,
    );
    const edit = safetyEdits.get(merchant);
    if (!clear && !allergens.length)
      throw Error("请选择具体过敏原；如需解除临时约束，请使用清除按钮。");
    safetyWrites.add(merchant);
    panel.disabled = true;
    invalidate();
    reflectSafetyEdit();
    try {
      await api("/order/safety-context", {
        method: clear ? "DELETE" : "PUT",
        headers: safetyHeaders(merchant),
        ...(clear ? {} : { body: { allergens } }),
      });
      if (safetyEdits.get(merchant) === edit) safetyEdits.delete(merchant);
    } finally {
      safetyWrites.delete(merchant);
      renderPendingSafety();
      // A delayed write belongs to its captured shop; never reload an old shop over a new one.
      if (merchant === $("#shop").value && campus === $("#campus").value)
        await loadMenu();
      else reflectSafetyEdit();
    }
    await loadFavorites();
    notice(
      clear
        ? name + "：已清除临时约束；长期偏好和已有购物车的安全限制仍然保留。"
        : name + "：已确认临时约束；跨店点餐请分别确认其他店铺。",
    );
  }

  return {
    requireSafetySaved,
    renderSafety,
    resetSafety,
    saveSafety,
    reflectSafetyEdit,
    markEdited() {
      safetyEdits.set($("#shop").value, {
        allergens: [
          ...$("#campusSafety").querySelectorAll("input:checked"),
        ].map((input) => input.value),
      });
      invalidate();
      reflectSafetyEdit();
    },
    get pending() {
      return safetyEdits.size > 0 || safetyWrites.size > 0;
    },
  };
}
