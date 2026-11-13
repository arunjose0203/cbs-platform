import test from 'node:test';
import assert from 'node:assert/strict';
import {createBankDutiesUi,dutyLists,DUTIES} from '../src/bank-duties-ui.js';
import {errorMessage} from '../src/model.js';

function fixture(overrides={}){
 let submit,html;const calls=[];
 const data={version:9,enforced:false,snapshotHash:'a'.repeat(64),findingCount:2,bankFindings:['DUTIES_NOT_ENFORCED'],
  items:[{staffId:'s1',subject:'<subject>',displayName:'<name>',staffActive:true,lastSeen:null,duties:{submit:['FINANCE'],approve:[],audit:false,expiresAt:''},lendingRoles:['AUDITOR'],paymentRoles:[],branchRoles:['MAIN:TELLER'],findings:['DORMANT']}],
  history:[{kind:'GRANT',change:{staffId:'s1',active:true,reason:'<reason>'},displayName:'<name>',maker:'m',checker:'c',at:'2026-11-13'}],
  reviews:[],...overrides};
 const ui=createBankDutiesUi({api:async(path,options)=>{calls.push({path,...options});if(options)return {};if(path==='/admin/tenants?limit=100')return {items:[{id:'bank',displayName:'Demo',status:'ACTIVE'}]};if(path.startsWith('/admin/staff'))return {items:[{id:'s1',displayName:'Staff',subject:'person'}]};return data;},
  form:(_t,h,fn)=>{html=h;submit=fn;},field:(name)=>`[${name}]`,refresh:async()=>{}});
 return {ui,calls,data,get html(){return html;},submit:v=>submit(v)};
}

test('report escapes identities, reasons and shows other roles and findings',async()=>{
 const f=fixture(),host={innerHTML:''};await f.ui.render(host,()=>true);
 assert.match(host.innerHTML,/&lt;name&gt;/);assert.match(host.innerHTML,/&lt;subject&gt;/);assert.match(host.innerHTML,/&lt;reason&gt;/);
 assert.doesNotMatch(host.innerHTML,/<name>|<reason>/);
 assert.match(host.innerHTML,/Duties are not enforced/);assert.match(host.innerHTML,/Branch MAIN:TELLER/);assert.match(host.innerHTML,/dormant/);
 assert.match(host.innerHTML,/data-action="bankduties-policy"/);
});

test('duty choices map to separate submit and approve lists',()=>{
 assert.deepEqual(dutyLists({FINANCE:'both',COMPLIANCE:'approve',TREASURY:'submit',PAYMENTS:'none'}),{submit:['FINANCE','TREASURY'],approve:['FINANCE','COMPLIANCE']});
 assert.equal(DUTIES.length,7);
});

test('grant form pins version and reuses its idempotency key across retries',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('edit','s1');
 const values={staffId:'s1',FINANCE:'submit',ACCESS_ADMINISTRATION:'none',audit:'no',expiresAt:'',reason:'Pilot finance maker'};
 await f.submit(values);const first=f.calls.at(-1);await f.submit(values);const second=f.calls.at(-1);
 assert.equal(first.path,'/admin/tenants/bank/bank-duties');assert.equal(first.body.expectedVersion,9);
 assert.deepEqual(first.body.submit,['FINANCE']);assert.deepEqual(first.body.approve,[]);assert.equal(first.body.active,true);assert.equal(first.body.expiresAt,null);
 assert.equal(first.headers['Idempotency-Key'],second.headers['Idempotency-Key']);
});

test('grant form rejects empty grants and past expiry, and sends future expiry as ISO time',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('grant');
 assert.throws(()=>f.submit({staffId:'s1',audit:'no',expiresAt:'',reason:'x'}),/at least one duty/);
 assert.throws(()=>f.submit({staffId:'s1',FINANCE:'both',audit:'no',expiresAt:'2001-01-01T00:00',reason:'x'}),/future expiry/);
 const future=new Date(Date.now()+86400000).toISOString();
 await f.submit({staffId:'s1',FINANCE:'both',audit:'no',expiresAt:future,reason:'Break-glass cover'});
 assert.equal(f.calls.at(-1).body.expiresAt,future);
});

test('revocation keeps the target and clears expiry',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('revoke','s1');
 await f.submit({reason:'Moved role',staffId:'other'});const call=f.calls.at(-1);
 assert.equal(call.body.staffId,'s1');assert.equal(call.body.active,false);assert.equal(call.body.expiresAt,null);assert.equal(call.body.expectedVersion,9);
});

test('policy toggles enforcement and the review signs the hash that was shown',async()=>{
 const f=fixture({enforced:true});await f.ui.render({innerHTML:''},()=>true);
 await f.ui.action('policy');await f.submit({reason:'Pilot finished'});
 assert.equal(f.calls.at(-1).path,'/admin/tenants/bank/bank-duties/policy');assert.equal(f.calls.at(-1).body.enforced,false);
 await f.ui.action('review');assert.match(f.html,/a{64}/);await f.submit({statement:'Quarterly review'});
 assert.deepEqual(f.calls.at(-1).body,{snapshotHash:'a'.repeat(64),statement:'Quarterly review'});
});

test('duty errors explain the next step',()=>{
 assert.match(errorMessage('BANK_DUTY_REQUIRED'),/duty/);assert.match(errorMessage('BANK_DUTY_LOCKOUT'),/two/);
 assert.match(errorMessage('ACCESS_REVIEW_STALE'),/Refresh/);assert.match(errorMessage('BANK_DUTY_MAKER_REVOKED'),/maker/);
});
