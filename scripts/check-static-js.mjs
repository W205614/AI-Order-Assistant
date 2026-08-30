import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const clients = [
  'java-gateway/src/main/resources/static/chat/index.html',
  'java-gateway/src/main/resources/static/admin/index.html',
];
const inlineScript = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
const temporaryDirectory = mkdtempSync(join(tmpdir(), 'ai-order-static-js-'));
let checkedScripts = 0;

try {
  for (const client of clients) {
    const source = readFileSync(join(repositoryRoot, client), 'utf8');
    const scripts = [];
    let match;
    while ((match = inlineScript.exec(source)) !== null) {
      if (!/\bsrc\s*=/i.test(match[1])) scripts.push(match[2]);
    }
    if (scripts.length === 0) throw new Error(`${client} does not contain an inline script to validate.`);

    scripts.forEach((script, index) => {
      const temporaryScript = join(temporaryDirectory, `${basename(client)}-${index}.js`);
      writeFileSync(temporaryScript, script, 'utf8');
      execFileSync(process.execPath, ['--check', temporaryScript], { stdio: 'pipe' });
      checkedScripts += 1;
    });
  }
  console.log(`Validated ${checkedScripts} inline browser script(s).`);
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
