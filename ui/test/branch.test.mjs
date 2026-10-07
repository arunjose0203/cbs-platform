import test from 'node:test';
import assert from 'node:assert/strict';
import {createBranchUi} from '../src/branch-ui.js';

const branch={bank_id:'bank',branch_id:'branch',bank_name:'Demo <bank>',branch_name:'Main',role:'TELLER'};
function fixture(path){
 if(path==='/banks/operations/branches')return [branch];
 if(path.includes('/accounts?'))return {version:7,businessDate:'2026-10-07',items:[{id:'account',account_number:'A001',currency:'KES',state:'ACTIVE',delivery:'APPLIED'}]};
 if(path.includes('/requests?'))return {items:[]};
 throw Error('Unexpected route '+path);
}
test('branch workspace uses scoped endpoints and escapes bank labels',async()=>{
 const calls=[];const content={innerHTML:''};const ui=createBranchUi({api:async path=>{calls.push(path);return fixture(path);}});
 await ui.render(content,()=>true);
 assert.match(content.innerHTML,/Demo &lt;bank&gt;/);assert.match(content.innerHTML,/A001/);
 assert.ok(calls.every(x=>x.startsWith('/banks/')));
});
test('branch submission pins account, bank version and decimal strings',async()=>{
 let submit;let posted;const ui=createBranchUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return fixture(path);},field:()=>'',form:(_title,_html,fn)=>submit=fn});
 await ui.render({innerHTML:''},()=>true);await ui.action('account','account');
 await submit({kind:'HOLD',amount:'123.45',destinationId:'ignored',originalId:'ignored',businessDate:'2026-10-07',reference:'REF',reason:'Review'});
 assert.equal(posted.path,'/banks/bank/branches/branch/operations/requests');assert.equal(posted.body.accountId,'account');assert.equal(posted.body.expectedVersion,7);assert.equal(posted.body.amount,'123.45');assert.equal(posted.body.destinationId,null);assert.equal(posted.body.originalId,null);
});
test('no branch membership does not load accounts or approvals',async()=>{
 let calls=0;const content={innerHTML:''};const ui=createBranchUi({api:async()=>{calls++;return [];}});await ui.render(content,()=>true);assert.equal(calls,1);assert.match(content.innerHTML,/No active branch memberships/);
});

test('branch transfer reversal swaps original accounts and preserves exact amount',async()=>{
 let submit,posted;const row={request_id:'original',kind:'TRANSFER',account_id:'source',destination_id:'target',amount:'9007199254740993.17',reference:'T1'};
 const ui=createBranchUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return path.endsWith('/reversible')?[row]:fixture(path);},field:()=>'',form:(_t,_h,fn)=>submit=fn});
 await ui.render({innerHTML:''},()=>true);await ui.action('reverse');await submit({originalId:'original',reference:'REV',reason:'Correction'});
 assert.equal(posted.body.kind,'TRANSFER_REVERSAL');assert.equal(posted.body.accountId,'target');assert.equal(posted.body.destinationId,'source');assert.equal(posted.body.amount,row.amount);assert.equal(posted.body.originalId,'original');assert.equal(posted.body.expectedVersion,7);
});
