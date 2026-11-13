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
test('customer documents and kyc reviews render and post correctly',async()=>{
  const posts=[];let submitForm;
  const docs=[{id:'d1',fileName:'id.pdf',documentType:'NATIONAL_ID',fileSize:2048,retentionUntil:'2033-01-01'}];
  const reviews=[{id:'r1',riskRating:'HIGH',reviewedAt:'2026-01-01',nextReviewDue:'2027-01-01',overdue:false,reviewReference:'REV-01'}];
  const ui=createCustomerUi({
    refresh:async()=>{},
    field:(name,label,val,opts)=>`<input name="${name}" value="${val||''}">`,
    form:(title,fields,fn)=>{submitForm=fn;},
    api:async(path,opts)=>{
      if(opts){posts.push({path,...opts});return {};}
      if(path==='/admin/tenants?limit=100')return {items:banks};
      if(path.endsWith('/documents'))return docs;
      if(path.endsWith('/kyc-reviews'))return reviews;
      return {version:1,storageReady:true,total:1,items:[{id:'c1',customerNumber:'C001',fullName:'Deceased Person',state:'DECEASED',delivery:'APPLIED'}]};
    }
  });
  const host={innerHTML:''};
  await ui.render(host,()=>true);
  assert.match(host.innerHTML,/Deceased/);
  await ui.action('docs','c1');
  assert.ok(submitForm);
  await submitForm({documentType:'PASSPORT',fileName:'passport.pdf',contentType:'application/pdf',retentionUntil:'2033-11-01',base64Content:'AQID'});
  assert.equal(posts[0].path,'/admin/tenants/a/customers/c1/documents');
  assert.equal(posts[0].body.fileName,'passport.pdf');

  await ui.action('kyc-review','c1');
  assert.ok(submitForm);
  await submitForm({riskRating:'HIGH',reviewedAt:'2026-11-13',reviewReference:'REV-99',notes:'Urgent'});
  assert.equal(posts[1].path,'/admin/tenants/a/customers/c1/kyc-reviews');
  assert.equal(posts[1].body.riskRating,'HIGH');
});
