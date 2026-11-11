import test from 'node:test';
import assert from 'node:assert/strict';
import {createNotificationUi} from '../src/notification-ui.js';
const banks=[{id:'bank',status:'ACTIVE',displayName:'Bank <demo>'}];
function fixture(page,preference={configured:false}){
 const calls=[],posts=[];let html,submit,host={innerHTML:''};
 const ui=createNotificationUi({api:async(path,options)=>{calls.push(path);if(options){posts.push({path,...options});return {};}
  if(path==='/admin/tenants?limit=100')return {items:banks};if(path.includes('/notifications?'))return page;if(path.includes('/customers?'))return {items:[{id:'c1',customerNumber:'C001',fullName:'Wanjiru <K>'}]};
  if(path.includes('/preferences/'))return preference;if(path==='/admin/tenants/bank')return {version:9};return {};},
  form:(_t,h,fn)=>{html=h;submit=fn;},field:(name,label)=>'['+name+']'+label,refresh:async()=>{await ui.render(host,()=>true);}});
 return {ui,host,calls,posts,get html(){return html;},submit:v=>submit(v)};
}
test('alert log escapes content, warns when no provider is configured and retries only failed alerts',async()=>{
 const f=fixture({providerConfigured:false,total:2,items:[{id:'n1',createdAt:'2026-11-11T10:00:00Z',event:'CREDIT',channel:'SMS',destination:'+254******678',body:'<b>KES 10</b>',status:'SENT',attempts:1},{id:'n2',createdAt:'2026-11-11T10:01:00Z',event:'DEBIT',channel:'EMAIL',destination:'w***@example.com',body:'x',status:'FAILED',attempts:5,failureCode:'TEST_PROVIDER_REJECTED'}]});
 await f.ui.render(f.host,()=>true);
 assert.match(f.host.innerHTML,/No SMS or email provider is configured/);assert.match(f.host.innerHTML,/&lt;b&gt;KES 10&lt;\/b&gt;/);assert.ok(!f.host.innerHTML.includes('<b>KES'));
 assert.equal((f.host.innerHTML.match(/notification-retry/g)||[]).length,1);assert.match(f.host.innerHTML,/data-id="n2"/);
 await f.ui.action('retry','n2');assert.equal(f.posts[0].path,'/admin/tenants/bank/notifications/n2/retry');
 await f.ui.action('filter','FAILED');assert.ok(f.calls.at(-1).includes('status=FAILED'));
});
test('preference change goes to approval with a pinned version and stable retry key',async()=>{
 const f=fixture({providerConfigured:true,total:0,items:[]},{configured:true,preference:{channel:'SMS',destination:'+254712345678',language:'sw',events:['CREDIT'],consentReference:'CONSENT/1'}});
 await f.ui.render(f.host,()=>true);await f.ui.action('preference');await f.submit({customerId:'c1'});
 assert.match(f.html,/Current/);
 const values={channel:'EMAIL',destination:'wanjiru@example.com',language:'fr',credit:'yes',debit:'no',consentReference:'CONSENT/2',reason:'Customer request'};
 await f.submit(values);await f.submit(values);
 assert.equal(f.posts[0].path,'/admin/tenants/bank/notifications/preferences');assert.deepEqual(f.posts[0].body.events,['CREDIT']);assert.equal(f.posts[0].body.expectedVersion,9);
 assert.equal(f.posts[0].headers['Idempotency-Key'],f.posts[1].headers['Idempotency-Key']);
 await f.submit({...values,channel:'NONE'});assert.equal(f.posts[2].body.destination,null);assert.notEqual(f.posts[2].headers['Idempotency-Key'],f.posts[1].headers['Idempotency-Key']);
});
