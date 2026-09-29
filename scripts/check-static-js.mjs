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
    const formatter = scripts.join('\n').match(/function fmt\(s\)\{[\s\S]*?\n\}/);
    if (!formatter) throw new Error('Order time formatter is missing.');
    const format = Function(`${formatter[0]}; return fmt;`)();
    if (format('2026-09-29T00:00:00') !== '09-29 00:00'
        || format('2026-09-29T14:08:00Z') !== '09-29 22:08'
        || format('invalid') !== '-') {
      throw new Error('Order times must render restaurant local timestamps and explicit offsets in Asia/Shanghai.');
    }
    if (client.includes('/chat/')) {
      const script = scripts.join('\n');
      const expression = script.match(/const previousHistory = ([\s\S]*?);/);
      if (!expression) throw new Error('Chat request history selection is missing.');
      const prior = JSON.parse(JSON.stringify([
        { role: 'user', content: '我要一份鱼香肉丝饭' },
        { role: 'assistant', content: '请确认草稿' },
        { role: 'notification', content: '订单状态更新' },
      ]));
      const selected = Function('history', `return ${expression[1]};`)(prior);
      if (selected.length !== 2 || selected[0].content !== '我要一份鱼香肉丝饭'
          || selected[1].content !== '请确认草稿') {
        throw new Error('Refreshed chat history must contain only prior user/assistant messages.');
      }
      if (script.indexOf('const previousHistory =') > script.indexOf("history.push({role:'user',content:text.trim()")) {
        throw new Error('Current message was appended before request history was captured.');
      }
    }
  }
  console.log(`Validated ${checkedScripts} inline browser script(s).`);
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
