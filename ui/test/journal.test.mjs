import test from 'node:test';
import assert from 'node:assert/strict';
import {journalTotals,createJournalUi} from '../src/journal-ui.js';
import {setLanguage} from '../src/i18n.js';
test('journal arithmetic stays exact above the JavaScript integer range',()=>{
  assert.deepEqual(journalTotals([{side:'DEBIT',amount:'9007199254740993.17'},{side:'CREDIT',amount:'9007199254740993.10'},{side:'CREDIT',amount:'0.07'}],2),{debit:'9007199254740993.17',credit:'9007199254740993.17',balanced:true});
  assert.equal(journalTotals([{side:'DEBIT',amount:'0.10'},{side:'CREDIT',amount:'0.20'}],2).balanced,false);
  for(const amount of ['0','-1','1.001','1e2','abc'])assert.equal(journalTotals([{side:'DEBIT',amount}],2),null);
  assert.equal(journalTotals([{side:'DEBIT',amount:'1.1'}],0),null);
  assert.equal(journalTotals([{side:'DEBIT',amount:'1.001'},{side:'CREDIT',amount:'1.001'}],3).balanced,true);
});
test('journal status is distinct from approval and user text is escaped',async()=>{
  setLanguage('en');const host={innerHTML:''};
  const ui=createJournalUi({api:async p=>p==='/admin/tenants?limit=100'?{items:[{id:'b',displayName:'Bank',bankCode:'B',status:'ACTIVE',legalEntities:[]}]}:p.endsWith('/settings')?{businessDate:'2026-09-29'}:{version:1,storageReady:true,total:1,items:[{entry:{id:'j',reference:'<script>',narration:'<img>',businessDate:'2026-09-29',currency:'KES'},postingStatus:'AWAITING_POSTING'}]}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/Awaiting posting/);assert.match(host.innerHTML,/Retry posting/);assert.match(host.innerHTML,/&lt;script&gt;/);assert.ok(!host.innerHTML.includes('<img>'));assert.match(host.innerHTML,/Only posted journals affect balances/);
});
