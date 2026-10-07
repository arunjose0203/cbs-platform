import test from 'node:test';
import assert from 'node:assert/strict';
import {parseCounts,createCashUi} from '../src/cash-ui.js';

test('denomination parsing preserves decimals and rejects duplicate or malformed counts',()=>{
 assert.deepEqual(parseCounts('9007199254740993.17:1; 0.10:3'),[{denomination:'9007199254740993.17',quantity:1},{denomination:'0.10',quantity:3}]);
 for(const value of ['50:1;50.00:2','0:1','1:-2','1:2.5','1e3:1','1:1000000001'])assert.throws(()=>parseCounts(value));
 assert.deepEqual(parseCounts(''),[]);
});
const branch={bank_id:'bank',branch_id:'branch',bank_name:'Bank <test>',branch_name:'Main',role:'TELLER'};
const location={id:'till',code:'TILL_1',name:'Counter',currency:'KES',type:'TILL'};
const dashboard={version:8,businessDate:'2026-10-07',storageReady:true,items:[{location,balance:'100',ledgerBalance:'100',reconciled:true,sessionOpen:true,custodian:'maker'}],requests:[],deliveries:[]};
test('cash counts use scoped routes and preserve approval version',async()=>{
 let submit,posted;const content={innerHTML:''};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return path.endsWith('/exceptions')?[]:dashboard;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,content,()=>true);assert.match(content.innerHTML,/Bank &lt;test&gt;/);assert.match(content.innerHTML,/Close till/);
 await ui.action('close','till');await submit({counts:'50:2',businessDate:'2026-10-07',reference:'COUNT-1',reason:'Counted'});
 assert.equal(posted.path,'/banks/bank/branches/branch/operations/cash');assert.equal(posted.body.expectedVersion,8);assert.equal(posted.body.kind,'CLOSE');assert.equal(posted.body.amount,null);assert.deepEqual(posted.body.counts,[{denomination:'50',quantity:2}]);
});
test('auditors have no cash transaction buttons',async()=>{
 const content={innerHTML:''};const ui=createCashUi({api:async(path)=>path.endsWith('/exceptions')?[]:dashboard});await ui.render({...branch,role:'AUDITOR'},content,()=>true);
 assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-(open|close|transfer|receive|dispatch)"/);
});
test('customer cash deposit preserves amount and uses the account liability binding',async()=>{
 let submit,posted;const data={...dashboard,accounts:[{id:'customer',account_number:'A001',currency:'KES',state:'ACTIVE',liability_ledger_id:'liability'}]};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted=options;return {};}return path.endsWith('/exceptions')?[]:data;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,{innerHTML:''},()=>true);await ui.action('deposit','till');
 await submit({accountId:'customer',liabilityLedgerId:'',amount:'9007199254740993.17',businessDate:'2026-10-07',reference:'CASH-DEPOSIT',reason:'Counted'});
 assert.equal(posted.body.accountId,'customer');assert.equal(posted.body.liabilityLedgerId,'liability');assert.equal(posted.body.kind,'DEPOSIT');assert.equal(posted.body.amount,'9007199254740993.17');assert.equal(posted.body.ledgerId,null);
});

test('open exceptions freeze location actions and preserve report evidence',async()=>{
 let submit,posted;const content={innerHTML:''};let cases=[];
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return path.endsWith('/exceptions')?cases:dashboard;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,content,()=>true);await ui.action('exception','till');await submit({counts:'50:1',reason:'Short count'});
 assert.equal(posted.path,'/banks/bank/branches/branch/operations/cash/exceptions');assert.equal(posted.body.expectedVersion,8);assert.deepEqual(posted.body.counts,[{denomination:'50',quantity:1}]);assert.ok(posted.headers['Idempotency-Key']);
 cases=[{id:'case',location_id:'till',reporter:'maker',code:'TILL_1',book_amount:'100',counted_amount:'50',difference:'-50',currency:'KES',resolved_at:null}];
 await ui.render(branch,content,()=>true);assert.match(content.innerHTML,/Frozen · open exception/);assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-(deposit|withdrawal|close|transfer)"/);
 await ui.action('exception-review','case');await submit({});assert.equal(posted.body.reason,'Short count');
});

test('shortage adjustment preserves exact case amount and approved ledger selection',async()=>{
 let submit,posted;const incident={id:'case',location_id:'till',code:'TILL_1',currency:'KES',difference:'-9007199254740993.17',book_amount:'9007199254740994.17',counted_amount:'1',reporter:'teller'};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}if(path.includes('/adjustment-ledgers'))return [{id:'expense',code:'LOSS',name:'Cash loss',category:'EXPENSE'},{id:'income',code:'GAIN',name:'Cash gain',category:'INCOME'}];return path.endsWith('/exceptions')?[incident]:dashboard;},form:(_title,fields,fn)=>{assert.match(fields,/Expense ledger/);submit=fn;},field:(name,label)=>label,subject:()=> 'manager'});
 await ui.render({...branch,role:'BRANCH_MANAGER'},{innerHTML:''},()=>true);await ui.action('adjustment','case');await submit({ledgerId:'expense',counts:'1:1',reference:'LOSS-1',reason:'Investigated'});
 assert.equal(posted.body.kind,'SHORTAGE');assert.equal(posted.body.amount,'9007199254740993.17');assert.equal(posted.body.exceptionId,'case');assert.equal(posted.body.ledgerId,'expense');assert.deepEqual(posted.body.counts,[{denomination:'1',quantity:1}]);assert.equal(posted.body.expectedVersion,8);
});
test('queued adjustments remain frozen and cannot be resolved by recount in the UI',async()=>{
 const content={innerHTML:''};const incident={id:'case',location_id:'till',reporter:'teller',pending_adjustment:'request',code:'TILL_1',currency:'KES',book_amount:'100',counted_amount:'110',difference:'10'};
 const ui=createCashUi({api:async path=>path.endsWith('/exceptions')?[incident]:dashboard});
 await ui.render({...branch,role:'BRANCH_MANAGER'},content,()=>true);
 assert.match(content.innerHTML,/Adjustment awaiting posting/);assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-adjustment"/);assert.doesNotMatch(content.innerHTML,/Customer deposit/);
});
