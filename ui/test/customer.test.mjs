import test from 'node:test';
import assert from 'node:assert/strict';
import {createCustomerUi} from '../src/customer-ui.js';
import {setLanguage,t} from '../src/i18n.js';
const banks=[{id:'a',displayName:'Bank A',status:'ACTIVE'},{id:'b',displayName:'Bank B',status:'ACTIVE'}];
const summary={id:'c',customerNumber:'C001',fullName:'<img src=x>',state:'PROSPECT',delivery:'PENDING',requestId:'r'};
test('customer list escapes names and separates pending delivery from applied status',async()=>{
  setLanguage('en');const calls=[],forms=[],host={innerHTML:''};const ui=createCustomerUi({form:(...a)=>forms.push(a),api:async path=>{calls.push(path);if(path==='/admin/tenants?limit=100')return {items:banks};if(path.endsWith('/c'))return {definition:{id:'c',profile:{}},delivery:'PENDING'};return {version:1,storageReady:true,total:1,items:[summary]};}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/&lt;img src=x&gt;/);assert.ok(!host.innerHTML.includes('<img'));assert.match(host.innerHTML,/Awaiting delivery/);assert.match(host.innerHTML,/data-action="customer-edit"[^>]*disabled/);
  await ui.action('edit','c');assert.equal(forms.length,0);ui.selectBank('b');await ui.render(host,()=>true);assert.ok(calls.at(-1).startsWith('/admin/tenants/b/customers?'));
});
test('customer registration preserves payload, nullable expiry and idempotency',async()=>{
  let submit;const posts=[];const ui=createCustomerUi({field:()=>'',form:(title,fields,fn)=>submit=fn,api:async(path,options)=>{if(options){posts.push(options);return {};}return path==='/admin/tenants?limit=100'?{items:banks}:{version:5,storageReady:true,total:0,items:[]};}});
  await ui.render({innerHTML:''},()=>true);await ui.action('new');const fields={fullName:'Example',birthDate:'1990-01-01',nationality:'KE',residenceCountry:'KE',address:'Test',phone:'+254700000000',email:'',documentType:'NATIONAL_ID',documentCountry:'KE',documentNumber:'TEST001',documentExpiry:'',documentReference:'DOC/1',state:'PROSPECT',reviewReference:'',duplicateReview:'',reason:'Test'};
  await submit(fields);await submit(fields);assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);assert.equal(posts[0].body.profile.documentExpiry,null);assert.equal(posts[0].body.expectedVersion,5);assert.equal(posts[0].body.customerId,null);
  await submit({...fields,duplicateReview:'Different person reviewed'});assert.notEqual(posts[1].headers['Idempotency-Key'],posts[2].headers['Idempotency-Key']);
});
test('customer setup and inactive bank controls prevent mutation forms',async()=>{
  const forms=[],host={innerHTML:'old'};const ui=createCustomerUi({form:(...a)=>forms.push(a),api:async path=>path==='/admin/tenants?limit=100'?{items:[{...banks[0],status:'SUSPENDED'}]}:{version:0,storageReady:false,total:0,items:[]}});
  await ui.render(host,()=>false);assert.equal(host.innerHTML,'old');await ui.render(host,()=>true);await ui.action('new');await ui.action('prepare');assert.equal(forms.length,0);assert.match(host.innerHTML,/Prepare customer storage/);
  setLanguage('fr');assert.equal(t('Customers'),'Clients');setLanguage('sw');assert.equal(t('Customers'),'Wateja');setLanguage('en');
});
