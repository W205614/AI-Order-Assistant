// Shared capabilities for isolated list/recovery browser fixtures. Live tests use the real endpoints.
module.exports = async function integrations(route, path) {
  let data;
  if (path === "/integrations/capabilities")
    data = { paymentMode: "SIMULATED", sms: false, maps: false, media: true };
  else if (["/notifications", "/media", "/admin/platform-coupons"].includes(path)) data = [];
  else if (path === "/notifications/events") {
    await route.fulfill({ contentType: "text/event-stream", body: ": heartbeat\n\n" });
    return true;
  } else return false;
  await route.fulfill({ contentType: "application/json", body: JSON.stringify({ code: 1, data }) });
  return true;
};
