import test from 'node:test';
import assert from 'node:assert/strict';
import {createOpsConsoleUi} from '../src/ops-console-ui.js';

const consoleData={businessDate:'2026-11-13',nextBusinessDate:'2026-11-16',timezone:'Africa/Nairobi',scheduler:{enabled:true,prepareAfter:'18:00'},
 cycle:[{step:'SERVICING',status:'DONE'},{step:'PERIOD_CLOSE',status:'NOT_REQUIRED'},{step:'DAY_CLOSE',status:'WAITING_APPROVAL'}],
 dayCloseBlockers:['CASH_COUNT_REQUIRED'],approvals:[{action:'DAY_CLOSE',count:1,oldest:'2026-11-13T15:00:00Z'}],
 queues:[{queue:'account_operation',pending:2,failing:1}],exceptions:{complianceCasesOverdue:3,paymentCasesOpen:0,statementImportsOpen:0,notificationsFailed:1},
 events:[{recordedAt:'t',businessDate:'2026-11-13',step:'DAY_CLOSE',outcome:'BLOCKED',code:'CASH_COUNT_REQUIRED'},{recordedAt:'t',businessDate:'2026-11-13',step:'SERVICING',outcome:'SUBMITTED',code:null}]};
function fixture(data=consoleData){
 const calls=[];const host={innerHTML:''};let submitted;
 const ui=createOpsConsoleUi({api:async path=>{calls.push(path);if(path==='/admin/tenants?limit=100')return {items:[{id:'bank',status:'ACTIVE',displayName:'Bank <demo>'}]};return data;},
  form:(_t,_h,fn)=>{submitted=fn;},field:(n)=>'['+n+']',refresh:async()=>{}});
 return {ui,host,calls,submit:v=>submitted(v)};
}
test('console shows the cycle, blockers, approvals, failing queues and scheduler history, escaped',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);const html=f.host.innerHTML;
 assert.ok(f.calls.includes('/admin/tenants/bank/operations-console'));
 assert.match(html,/Bank &lt;demo&gt;/);assert.match(html,/Waiting for approval/);assert.match(html,/Not required today/);
 assert.match(html,/Prepared automatically after 18:00 \(Africa\/Nairobi\)/);
 assert.match(html,/account_operation/);assert.match(html,/class="warning"/);assert.match(html,/Overdue compliance cases/);
 assert.match(html,/Submitted for approval/);assert.match(html,/another person approves every step/);
});
test('an empty bank says so instead of showing empty tables',async()=>{
 const f=fixture({...consoleData,scheduler:{enabled:false},dayCloseBlockers:[],approvals:[],queues:[],events:[]});await f.ui.render(f.host,()=>true);
 assert.match(f.host.innerHTML,/Prepared by staff/);assert.match(f.host.innerHTML,/Nothing blocks the day close/);assert.match(f.host.innerHTML,/All queues are empty/);assert.match(f.host.innerHTML,/No scheduler activity yet/);
});
test('choosing a bank changes the console bank',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);await f.ui.action('choose');await f.submit({bank:'other'});
 await f.ui.render(f.host,()=>true);assert.ok(f.calls.includes('/admin/tenants/other/operations-console'));
});
