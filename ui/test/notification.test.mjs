import test from 'node:test';
import assert from 'node:assert/strict';
import {createNotificationUi} from '../src/notification-ui.js';
const banks=[{id:'bank',status:'ACTIVE',displayName:'Bank <demo>'}];
function fixture(page,preference={configured:false},templates=[],optOuts=[]){
 const calls=[],posts=[];let html,submit,host={innerHTML:''};
 const ui=createNotificationUi({api:async(path,options)=>{calls.push(path);if(options){posts.push({path,...options});return {};}
  if(path==='/admin/tenants?limit=100')return {items:banks};if(path.includes('/notifications?'))return page;if(path.includes('/customers?'))return {items:[{id:'c1',customerNumber:'C001',fullName:'Wanjiru <K>'}]};
  if(path.includes('/preferences/'))return preference;if(path.endsWith('/templates'))return templates;if(path.endsWith('/opt-outs'))return optOuts;if(path==='/admin/tenants/bank')return {version:9};return {};},
  form:(_t,h,fn)=>{html=h;submit=fn;},field:(name,label)=>'['+name+']'+label,refresh:async()=>{await ui.render(host,()=>true);}});
 return {ui,host,calls,posts,get html(){return html;},submit:v=>submit(v)};
}
test('alert log escapes content, warns when no provider is configured and retries only failed alerts',async()=>{
 const f=fixture({providerConfigured:false,total:2,items:[{id:'n1',createdAt:'2026-11-11T10:00:00Z',event:'CREDIT',channel:'SMS',destination:'+254******678',body:'<b>KES 10</b>',status:'SENT',attempts:1},{id:'n2',createdAt:'2026-11-11T10:01:00Z',event:'DEBIT',channel:'EMAIL',destination:'w***@example.com',body:'x',status:'FAILED',attempts:5,failureCode:'TEST_PROVIDER_REJECTED'}]});
 await f.ui.render(f.host,()=>true);
 assert.match(f.host.innerHTML,/No SMS or email provider is configured/);assert.match(f.host.innerHTML,/&lt;b&gt;KES 10&lt;\/b&gt;/);assert.ok(!f.host.innerHTML.includes('<b>KES'));
 assert.equal((f.host.innerHTML.match(/notification-retry/g)||[]).length,1);assert.match(f.host.innerHTML,/data-id="n2"/);
 await f.ui.action('retry','n2');assert.equal(f.posts[0].path,'/admin/tenants/bank/notifications/n2/retry');
 await f.ui.action('filter','FAILED');assert.ok(f.calls.some(c=>c.includes('/notifications?')&&c.includes('status=FAILED')));
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
test('receipts, notices and suppressed alerts are shown; verification codes cannot be resent',async()=>{
 const f=fixture({providerConfigured:true,total:3,items:[{id:'n1',createdAt:'t',event:'LOAN_ARREARS',channel:'SMS',destination:'+254******111',body:'overdue',status:'SENT',attempts:1,receiptStatus:'DELIVERED',receiptAt:'r'},
  {id:'n2',createdAt:'t',event:'VERIFICATION',channel:'SMS',destination:'+254******111',body:'[verification code]',status:'FAILED',attempts:5},{id:'n3',createdAt:'t',event:'CREDIT',channel:'SMS',destination:'+254******111',body:'x',status:'SUPPRESSED',attempts:0,failureCode:'NOTIFICATION_OPTED_OUT'}]});
 await f.ui.render(f.host,()=>true);
 assert.match(f.host.innerHTML,/Loan arrears/);assert.match(f.host.innerHTML,/Delivered/);assert.match(f.host.innerHTML,/Suppressed/);
 assert.equal((f.host.innerHTML.match(/notification-retry/g)||[]).length,0,'a failed verification code is never sent again from the log');
 await f.ui.action('event','TD_MATURITY');assert.ok(f.calls.some(c=>c.includes('event=TD_MATURITY')));
});
test('template change goes to approval with the wording and a stable key',async()=>{
 const f=fixture({providerConfigured:true,total:0,items:[]},{configured:false},[{event:'CREDIT',language:'sw',channel:'SMS',subject:null,body:'{bank}: <x>',approved:false},{event:'DEBIT',language:'en',channel:'EMAIL',subject:'{bank}: activity',body:'{bank}: debit',approved:true}]);
 await f.ui.render(f.host,()=>true);assert.match(f.host.innerHTML,/Built-in/);assert.match(f.host.innerHTML,/Approved by the bank/);assert.match(f.host.innerHTML,/\{bank\}: &lt;x&gt;/);
 await f.ui.action('template','1');assert.match(f.html,/\[subject\]/);
 const values={subject:'{bank}: account debit',body:'{bank}: {currency} {amount} debited',reason:'Approved wording'};
 await f.submit(values);await f.submit(values);
 assert.equal(f.posts[0].path,'/admin/tenants/bank/notifications/templates');assert.deepEqual({...f.posts[0].body},{expectedVersion:9,event:'DEBIT',language:'en',channel:'EMAIL',subject:'{bank}: account debit',body:'{bank}: {currency} {amount} debited',reason:'Approved wording'});
 assert.equal(f.posts[0].headers['Idempotency-Key'],f.posts[1].headers['Idempotency-Key']);
});
test('destination verification, opt-out and resuming messages post to their endpoints',async()=>{
 const f=fixture({providerConfigured:true,total:0,items:[]},{configured:true,verified:false,preference:{channel:'SMS',destination:'+254711000111'}},[],[{channel:'SMS',destination:'+254******111',source:'PROVIDER',reference:'RECEIPT/r-2',recordedAt:'t'}]);
 await f.ui.render(f.host,()=>true);assert.match(f.host.innerHTML,/Customer reply to the provider/);
 await f.ui.action('verify');await f.submit({customerId:'c1'});assert.match(f.html,/\[code\]/);
 await f.submit({code:'123456'});assert.equal(f.posts[0].path,'/admin/tenants/bank/notifications/preferences/c1/verify');assert.deepEqual(f.posts[0].body,{code:'123456'});
 await f.ui.action('resend','c1');assert.equal(f.posts[1].path,'/admin/tenants/bank/notifications/preferences/c1/resend');
 await f.ui.action('resume','SMS');await f.submit({destination:'+254711000111',reference:'CONSENT/2'});
 assert.equal(f.posts[2].path,'/admin/tenants/bank/notifications/opt-outs/remove');assert.deepEqual(f.posts[2].body,{channel:'SMS',destination:'+254711000111',reference:'CONSENT/2'});
 await f.ui.action('opt-out');await f.submit({channel:'EMAIL',destination:'a@example.com',reference:'BRANCH/1'});assert.equal(f.posts[3].path,'/admin/tenants/bank/notifications/opt-outs');
});
