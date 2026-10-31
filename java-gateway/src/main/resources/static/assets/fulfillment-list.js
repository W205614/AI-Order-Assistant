import { api, run } from "./api.js";
import { node, button, field, select } from "./commerce-ui.js";

const PAGE_SIZE = 50;
const MAX_PAGE = 1000000;
const ACTIVE_TASKS = new Set(["WAITING", "ASSIGNED", "PICKED_UP", "EXCEPTION"]);

export const isHistoricalTask = (task) => !ACTIVE_TASKS.has(task.status);

// Keep the existing array response contract. A full page may have one empty
// successor; no count query or unbounded client-side accumulation is needed.
export function createFulfillmentList(path, label, {
  activeLabel = "活动队列", initialScope = "active", allLabel,
} = {}) {
  let scope = initialScope;
  let page = 1;
  let revision = 0;

  async function read() {
    const request = { scope, page, revision: ++revision };
    const query = new URLSearchParams({
      scope,
      page: String(page),
      pageSize: String(PAGE_SIZE),
    });
    const rows = await api(path + "?" + query);
    if (!Array.isArray(rows)) throw Error("列表响应无效，请刷新重试。");
    return {
      ...request,
      rows,
      isCurrent: () =>
        request.revision === revision &&
        request.scope === scope &&
        request.page === page,
    };
  }

  function controls(result, reload) {
    const bar = node("div", undefined, { class: "list-pagination" });
    const filter = select(
      [
        ...(allLabel ? [["all", allLabel]] : []),
        ["active", activeLabel],
        ["history", "历史记录"],
      ],
      result.scope,
    );
    filter.setAttribute("aria-label", label + "列表范围");
    async function change(nextScope, nextPage) {
      const previous = { scope, page };
      scope = nextScope;
      page = nextPage;
      try {
        await reload();
      } catch (error) {
        if (scope === nextScope && page === nextPage)
          ({ scope, page } = previous);
        filter.value = scope;
        throw error;
      }
    }
    filter.addEventListener("change", () => {
      const nextScope = filter.value;
      run(() => change(nextScope, 1), filter);
    });
    const previous = button("上一页", () =>
      change(result.scope, Math.max(1, result.page - 1)),
    );
    const next = button("下一页", () =>
      change(result.scope, Math.min(MAX_PAGE, result.page + 1)),
    );
    previous.disabled = result.page <= 1;
    next.disabled = result.rows.length < PAGE_SIZE || result.page >= MAX_PAGE;
    bar.append(
      field("查看", filter),
      previous,
      node("span", "第 " + result.page + " 页 · 每页 " + PAGE_SIZE + " 条", {
        "aria-live": "polite",
      }),
      next,
    );
    return bar;
  }

  return { read, controls };
}
