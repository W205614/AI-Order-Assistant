import { execFileSync } from "node:child_process";
import {
  readFileSync,
  readdirSync,
  mkdtempSync,
  writeFileSync,
  rmSync,
} from "node:fs";
import { resolve, join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const staticRoot = join(root, "java-gateway/src/main/resources/static");
const temp = mkdtempSync(join(tmpdir(), "ai-order-modules-"));
let count = 0;
try {
  for (const file of readdirSync(join(staticRoot, "assets")).filter((f) =>
    f.endsWith(".js"),
  )) {
    const source = readFileSync(join(staticRoot, "assets", file), "utf8");
    for (const [, relative] of source.matchAll(/from\s*['"](\.[^'"]+)['"]/g)) {
      // Every added module participates in this loop; verify its local imports
      // resolve as well, so a moved controller cannot silently ship a 404.
      readFileSync(resolve(staticRoot, "assets", relative), "utf8");
    }
    const path = join(temp, file + ".mjs");
    writeFileSync(path, source);
    execFileSync(process.execPath, ["--check", path], { stdio: "pipe" });
    count++;
    if (/localStorage.*token|sessionStorage.*token|innerHTML\s*=/.test(source))
      throw new Error("Unsafe credential or HTML sink: " + file);
  }
  for (const page of ["chat", "campus", "admin", "platform", "rider"]) {
    const source = readFileSync(join(staticRoot, page, "index.html"), "utf8");
    if (/\son\w+\s*=/.test(source)) throw new Error("Inline handler: " + page);
    for (const script of source.matchAll(
      /<script([^>]*)>([\s\S]*?)<\/script>/gi,
    )) {
      if (
        (!script[1].includes('type="module"') &&
          !script[1].includes('src="/assets/vendor/qrcodegen.js"')) ||
        !script[1].includes("src=") ||
        script[2].trim()
      )
        throw new Error("Inline script: " + page);
    }
  }
  const vendor = readFileSync(join(staticRoot, "assets/vendor/qrcodegen.js"));
  const { createHash } = await import("node:crypto");
  if (createHash("sha256").update(vendor).digest("hex") !==
      "2511bc17f40a3c41d4a0578995db956b38997334d3d20113a5d4dc5c49c69480")
    throw new Error("Vendored QR distribution changed; review its source and recorded digest");
  execFileSync(process.execPath, ["--check", join(staticRoot, "assets/vendor/qrcodegen.js")], { stdio: "pipe" });
  console.log(
    count + " JavaScript modules and 5 pages passed syntax/CSP checks.",
  );
} finally {
  rmSync(temp, { recursive: true, force: true });
}
