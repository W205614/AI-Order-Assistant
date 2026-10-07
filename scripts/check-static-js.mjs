import {execFileSync} from 'node:child_process';
import {readFileSync,readdirSync,mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {resolve,join,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {tmpdir} from 'node:os';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const staticRoot=join(root,'java-gateway/src/main/resources/static');
const temp=mkdtempSync(join(tmpdir(),'ai-order-modules-'));
let count=0;
try {
  for(const file of readdirSync(join(staticRoot,'assets')).filter(f=>f.endsWith('.js'))){
    const source=readFileSync(join(staticRoot,'assets',file),'utf8');
    const path=join(temp,file+'.mjs');writeFileSync(path,source);
    execFileSync(process.execPath,['--check',path],{stdio:'pipe'});count++;
    if(/localStorage.*token|sessionStorage.*token|innerHTML\s*=/.test(source))throw new Error('Unsafe credential or HTML sink: '+file);
  }
  for(const page of ['chat','admin','platform']){
    const source=readFileSync(join(staticRoot,page,'index.html'),'utf8');
    if(/\son\w+\s*=/.test(source))throw new Error('Inline handler: '+page);
    for(const script of source.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)){
      if(!script[1].includes('type="module"')||!script[1].includes('src=')||script[2].trim())throw new Error('Inline script: '+page);
    }
  }
  console.log(count+' JavaScript modules and 3 pages passed syntax/CSP checks.');
}finally{rmSync(temp,{recursive:true,force:true});}
