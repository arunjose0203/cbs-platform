import test from 'node:test';
import assert from 'node:assert/strict';
import {createLedgerUi} from '../src/ledger-ui.js';
import {setLanguage} from '../src/i18n.js';
const bank={id:'a',bankCode:'A',displayName:'Bank A',status:'ACTIVE',legalEntities:[]};
test('ledger page distinguishes pending from ready and escapes all account text',async()=>{
  setLanguage('en');const host={innerHTML:''};
  const ui=createLedgerUi({api:async path=>{
    if(path==='/admin/tenants?limit=100')return {items:[bank]};
    if(path.endsWith('/settings'))return {currencies:[{code:'INR',enabled:true}]};
    return {version:1,storageReady:true,total:1,items:[{definition:{id:'l',code:'CASH',name:'<script>alert(1)</script>',active:true,currency:'INR'},chartCode:'1100',legalEntityName:'<img>',delivery:'PENDING',requestId:'req',failureCode:null}]};
  }});
  await ui.render(host,()=>true);
  assert.match(host.innerHTML,/Awaiting delivery/);assert.match(host.innerHTML,/Retry delivery/);
  assert.ok(host.innerHTML.includes('&lt;script&gt;'));assert.ok(!host.innerHTML.includes('<script>'));assert.ok(host.innerHTML.includes('&lt;img&gt;'));
});
test('unprepared bank presents a preparation action and blocks creation',async()=>{
  const host={innerHTML:''},forms=[];
  const ui=createLedgerUi({form:(...args)=>forms.push(args),api:async path=>path==='/admin/tenants?limit=100'?{items:[bank]}:path.endsWith('/settings')?{currencies:[]}:{version:0,storageReady:false,items:[],total:0}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/Prepare ledger storage/);
  await ui.action('new');assert.equal(forms.length,0);
});
