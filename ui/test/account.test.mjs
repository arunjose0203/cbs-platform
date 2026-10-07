import test from 'node:test';
import assert from 'node:assert/strict';
import {createAccountUi} from '../src/account-ui.js';
import {setLanguage,t} from '../src/i18n.js';
const banks=[{id:'a',displayName:'Bank A',status:'ACTIVE',legalEntities:[{id:'entity',legalName:'Entity'}]},{id:'b',displayName:'Bank B',status:'ACTIVE'}];
const summary={id:'d',accountNumber:'A001',customerNumber:'C001',name:'<img src=x>',productCode:'SAV',currency:'KES',state:'AWAITING_FUNDING',delivery:'PENDING',requestId:'r'};
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
