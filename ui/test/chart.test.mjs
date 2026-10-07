import test from 'node:test';
import assert from 'node:assert/strict';
import {createChartUi} from '../src/chart-ui.js';
import {setLanguage} from '../src/i18n.js';

test('chart escapes account data and switches the API bank context',async()=>{
  setLanguage('en');
  const calls=[],host={innerHTML:''};
  const banks=[{id:'a',bankCode:'A',displayName:'Bank A',status:'ACTIVE'},{id:'b',bankCode:'B',displayName:'Bank B',status:'ACTIVE'}];
  const ui=createChartUi({api:async path=>{
    calls.push(path);
    if(path==='/admin/tenants?limit=100')return {items:banks};
    return {version:1,total:1,accountCount:1,groupCount:1,activeCount:1,items:[{id:'one',code:'1000',name:'<img src=x onerror=alert(1)>',category:'ASSET',normalBalance:'DEBIT',kind:'GROUP',active:true}]};
  }});
  await ui.render(host,()=>true);
  assert.ok(host.innerHTML.includes('&lt;img src=x onerror=alert(1)&gt;'));
  assert.ok(!host.innerHTML.includes('<img'));
  assert.ok(calls[1].startsWith('/admin/tenants/a/chart-of-accounts?'));
  ui.selectBank('b');await ui.render(host,()=>true);
  assert.ok(calls.at(-1).startsWith('/admin/tenants/b/chart-of-accounts?'));
});
test('inactive banks cannot open account mutation forms and stale renders do not update the page',async()=>{
  const host={innerHTML:'previous'},forms=[];
  const ui=createChartUi({form:(...args)=>forms.push(args),api:async path=>path==='/admin/tenants?limit=100'?{items:[{id:'a',bankCode:'A',displayName:'Bank A',status:'REQUESTED'}]}:{version:0,total:0,accountCount:0,groupCount:0,activeCount:0,items:[]}});
  await ui.render(host,()=>false);assert.equal(host.innerHTML,'previous');
  await ui.render(host,()=>true);assert.match(host.innerHTML,/must be ACTIVE/);
  await ui.action('new');assert.equal(forms.length,0);
});
