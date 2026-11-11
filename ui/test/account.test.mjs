import test from 'node:test';
import assert from 'node:assert/strict';
import {createAccountUi} from '../src/account-ui.js';
import {setLanguage,t} from '../src/i18n.js';
const banks=[{id:'a',displayName:'Bank A',status:'ACTIVE',legalEntities:[{id:'entity',legalName:'Entity'}]},{id:'b',displayName:'Bank B',status:'ACTIVE'}];
const summary={id:'d',accountNumber:'A001',customerNumber:'C001',name:'<img src=x>',productCode:'SAV',currency:'KES',state:'AWAITING_FUNDING',delivery:'PENDING',requestId:'r'};
test('closure shows exact balances and blockers without submitting an ineligible account',async()=>{
 const forms=[],posts=[];const ui=createAccountUi({field:()=>'',form:(...v)=>forms.push(v),api:async(path,options)=>{
  if(options){posts.push(options);return {};}
  if(path==='/admin/tenants?limit=100')return {items:banks};
  if(path.endsWith('/closure'))return {version:7,posted:'9007199254740993.17',held:'1.00',ledgerBalance:'9007199254740993.17',blockers:['FUNDING_BALANCE_NOT_ZERO','ACCOUNT_CLOSURE_HOLDS']};
  if(path.endsWith('/d'))return {definition:{id:'d',terms:{currency:'KES'}}};
  return {version:1,storageReady:true,total:0,items:[]};
 }});
 await ui.render({innerHTML:''},()=>true);await ui.action('closure','d');
 assert.match(forms[0][1],/9007199254740993.17/);assert.match(forms[0][1],/Release all account holds/);assert.equal(forms[0][3],'Done');await forms[0][2]({reason:'Close'});assert.equal(posts.length,0);
});
test('eligible closure pins fresh version, uses independent approval and preserves retry key',async()=>{
 let submit;const posts=[];const ui=createAccountUi({field:()=>'',form:(title,fields,fn)=>submit=fn,api:async(path,options)=>{
  if(options){posts.push(options);return {};}
  if(path==='/admin/tenants?limit=100')return {items:banks};
  if(path.endsWith('/closure'))return {version:9,posted:'0',held:'0',ledgerBalance:'0',blockers:[]};
  if(path.endsWith('/d'))return {definition:{id:'d',name:'Savings',customerId:'c',legalEntityId:'entity',terms:{id:'p',revision:3,currency:'KES'}}};
  return {version:1,storageReady:true,total:0,items:[]};
 }});
 await ui.render({innerHTML:''},()=>true);await ui.action('closure','d');await submit({reason:'Customer request'});await submit({reason:'Customer request'});
 assert.equal(posts[0].body.expectedVersion,9);assert.equal(posts[0].body.state,'CLOSED');assert.equal(posts[0].body.productRevision,3);assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);
 await submit({reason:'Corrected reason'});assert.notEqual(posts[1].headers['Idempotency-Key'],posts[2].headers['Idempotency-Key']);
});
test('account directory escapes data, distinguishes delivery and scopes bank requests',async()=>{
 const calls=[],forms=[],host={innerHTML:''};const ui=createAccountUi({form:(...a)=>forms.push(a),api:async path=>{calls.push(path);if(path==='/admin/tenants?limit=100')return {items:banks};if(path.endsWith('/d'))return {definition:{id:'d'},delivery:'PENDING'};return {version:1,storageReady:true,total:1,items:[summary]};}});
 await ui.render(host,()=>true);assert.match(host.innerHTML,/&lt;img src=x&gt;/);assert.ok(!host.innerHTML.includes('<img'));assert.match(host.innerHTML,/Awaiting delivery/);assert.match(host.innerHTML,/data-action="account-edit"[^>]*disabled/);
 await ui.action('edit','d');assert.equal(forms.length,0);ui.selectBank('b');await ui.render(host,()=>true);assert.ok(calls.at(-1).startsWith('/admin/tenants/b/accounts?'));
});
test('opening pins selected product revision and preserves idempotency until payload changes',async()=>{
 let submit;const posts=[];const ui=createAccountUi({field:()=>'',form:(title,fields,fn)=>submit=fn,api:async(path,options)=>{if(options){posts.push(options);return {};}if(path==='/admin/tenants?limit=100')return {items:banks};if(path.includes('/products?'))return {items:[{id:'p',code:'SAV',currency:'KES',minimumOpening:'100',revision:3}]};if(path.includes('/customers?'))return {items:[{id:'c',fullName:'Example',customerNumber:'C001',delivery:'APPLIED'}]};return {version:5,storageReady:true,total:0,items:[]};}});
 await ui.render({innerHTML:''},()=>true);await ui.action('new');const data={customerId:'c',productId:'p',legalEntityId:'entity',name:'Savings',reason:'Open'};await submit(data);await submit(data);
 assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);assert.equal(posts[0].body.productRevision,3);assert.equal(posts[0].body.state,'AWAITING_FUNDING');assert.equal(posts[0].body.accountId,null);assert.equal(posts[0].body.expectedVersion,5);
 await submit({...data,name:'Renamed'});assert.notEqual(posts[1].headers['Idempotency-Key'],posts[2].headers['Idempotency-Key']);
});
test('closed and pending accounts cannot be managed; opening prerequisites and translations are visible',async()=>{
 const forms=[];const ui=createAccountUi({field:()=>'',form:(...a)=>forms.push(a),api:async path=>path==='/admin/tenants?limit=100'?{items:banks}:path.endsWith('/d')?{definition:{id:'d',state:'CLOSED'},delivery:'APPLIED'}:{version:1,storageReady:true,total:0,items:[]}});
 await ui.render({innerHTML:''},()=>true);await ui.action('edit','d');assert.equal(forms.length,0);await ui.action('new');assert.equal(forms[0][0],'Opening prerequisites');
 setLanguage('fr');assert.equal(t('Deposit accounts'),'Comptes de dépôt');setLanguage('sw');assert.equal(t('Deposit accounts'),'Akaunti za amana');setLanguage('en');
});
test('positive balance offers final settlement of the exact posted balance, then nothing else',async()=>{
 let submit;const forms=[],posts=[];const ui=createAccountUi({field:(name)=>'['+name+']',form:(...a)=>{forms.push(a);submit=a[2];},api:async(path,options)=>{
  if(options){posts.push({path,...options});return {};}
  if(path==='/admin/tenants?limit=100')return {items:banks};
  if(path.endsWith('/closure'))return {version:11,posted:'9007199254740993.17',held:'0',ledgerBalance:'9007199254740993.17',carry:'0.004',settled:false,blockers:['FUNDING_BALANCE_NOT_ZERO','ACCOUNT_CLOSURE_CARRY_SETTLEMENT']};
  if(path.endsWith('/d'))return {definition:{id:'d',legalEntityId:'entity',terms:{currency:'KES'}}};
  if(path.includes('/ledger-accounts?'))return {items:[{delivery:'APPLIED',definition:{id:'cash',code:'1100',name:'Vault <b>',legalEntityId:'entity',chartAccountId:'asset'}},{delivery:'APPLIED',definition:{id:'other',code:'9',name:'Other entity',legalEntityId:'x',chartAccountId:'asset'}}]};
  if(path.includes('/chart-of-accounts?'))return {items:[{id:'asset',normalBalance:'DEBIT'}]};
  if(path.endsWith('/settings'))return {businessDate:'2026-11-11'};
  return {version:1,storageReady:true,total:0,items:[]};
 }});
 await ui.render({innerHTML:''},()=>true);await ui.action('closure','d');
 assert.equal(forms[0][0],'Final settlement before closure');assert.match(forms[0][1],/0\.004/);assert.equal(forms[0][3],'Submit for approval');
 await submit({assetLedgerId:'cash',reference:'CLOSE-1',reason:'Customer request'});await submit({assetLedgerId:'cash',reference:'CLOSE-1',reason:'Customer request'});
 assert.equal(posts[0].path,'/admin/tenants/a/account-operations');assert.equal(posts[0].body.kind,'CLOSURE_SETTLEMENT');assert.equal(posts[0].body.amount,'9007199254740993.17');
 assert.equal(posts[0].body.expectedVersion,11);assert.equal(posts[0].body.businessDate,'2026-11-11');assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);
});
test('settled account no longer offers another settlement',async()=>{
 const forms=[];const ui=createAccountUi({field:()=>'',form:(...a)=>forms.push(a),api:async(path)=>{
  if(path==='/admin/tenants?limit=100')return {items:banks};
  if(path.endsWith('/closure'))return {version:12,posted:'0',held:'0',ledgerBalance:'0',carry:'0.004',settled:true,blockers:['ACCOUNT_OPERATION_PENDING']};
  if(path.endsWith('/d'))return {definition:{id:'d',terms:{currency:'KES'}}};
  return {version:1,storageReady:true,total:0,items:[]};
 }});
 await ui.render({innerHTML:''},()=>true);await ui.action('closure','d');assert.equal(forms[0][0],'Account closure check');assert.equal(forms[0][3],'Done');
});
