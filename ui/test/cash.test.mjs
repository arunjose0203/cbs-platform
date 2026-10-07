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
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return (path.endsWith('/exceptions')||path.endsWith('/transit'))?[]:dashboard;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,content,()=>true);assert.match(content.innerHTML,/Bank &lt;test&gt;/);assert.match(content.innerHTML,/Close till/);
 await ui.action('close','till');await submit({counts:'50:2',businessDate:'2026-10-07',reference:'COUNT-1',reason:'Counted'});
 assert.equal(posted.path,'/banks/bank/branches/branch/operations/cash');assert.equal(posted.body.expectedVersion,8);assert.equal(posted.body.kind,'CLOSE');assert.equal(posted.body.amount,null);assert.deepEqual(posted.body.counts,[{denomination:'50',quantity:2}]);
});
test('auditors have no cash transaction buttons',async()=>{
 const content={innerHTML:''};const ui=createCashUi({api:async(path)=>(path.endsWith('/exceptions')||path.endsWith('/transit'))?[]:dashboard});await ui.render({...branch,role:'AUDITOR'},content,()=>true);
 assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-(open|close|transfer|receive|dispatch)"/);
});
test('customer cash deposit preserves amount and uses the account liability binding',async()=>{
 let submit,posted;const data={...dashboard,accounts:[{id:'customer',account_number:'A001',currency:'KES',state:'ACTIVE',liability_ledger_id:'liability'}]};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted=options;return {};}return (path.endsWith('/exceptions')||path.endsWith('/transit'))?[]:data;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,{innerHTML:''},()=>true);await ui.action('deposit','till');
 await submit({accountId:'customer',liabilityLedgerId:'',amount:'9007199254740993.17',businessDate:'2026-10-07',reference:'CASH-DEPOSIT',reason:'Counted'});
 assert.equal(posted.body.accountId,'customer');assert.equal(posted.body.liabilityLedgerId,'liability');assert.equal(posted.body.kind,'DEPOSIT');assert.equal(posted.body.amount,'9007199254740993.17');assert.equal(posted.body.ledgerId,null);
});

test('open exceptions freeze location actions and preserve report evidence',async()=>{
 let submit,posted;const content={innerHTML:''};let cases=[];
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}if(path.endsWith('/transit'))return [];return path.endsWith('/exceptions')?cases:dashboard;},form:(_title,_fields,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,content,()=>true);await ui.action('exception','till');await submit({counts:'50:1',reason:'Short count'});
 assert.equal(posted.path,'/banks/bank/branches/branch/operations/cash/exceptions');assert.equal(posted.body.expectedVersion,8);assert.deepEqual(posted.body.counts,[{denomination:'50',quantity:1}]);assert.ok(posted.headers['Idempotency-Key']);
 cases=[{id:'case',location_id:'till',reporter:'maker',code:'TILL_1',book_amount:'100',counted_amount:'50',difference:'-50',currency:'KES',resolved_at:null}];
 await ui.render(branch,content,()=>true);assert.match(content.innerHTML,/Frozen · open exception/);assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-(deposit|withdrawal|close|transfer)"/);
 await ui.action('exception-review','case');await submit({});assert.equal(posted.body.reason,'Short count');
});

test('shortage adjustment preserves exact case amount and approved ledger selection',async()=>{
 let submit,posted;const incident={id:'case',location_id:'till',code:'TILL_1',currency:'KES',difference:'-9007199254740993.17',book_amount:'9007199254740994.17',counted_amount:'1',reporter:'teller'};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}if(path.includes('/adjustment-ledgers'))return [{id:'expense',code:'LOSS',name:'Cash loss',category:'EXPENSE'},{id:'income',code:'GAIN',name:'Cash gain',category:'INCOME'}];if(path.endsWith('/transit'))return [];return path.endsWith('/exceptions')?[incident]:dashboard;},form:(_title,fields,fn)=>{assert.match(fields,/Expense ledger/);submit=fn;},field:(name,label)=>label,subject:()=> 'manager'});
 await ui.render({...branch,role:'BRANCH_MANAGER'},{innerHTML:''},()=>true);await ui.action('adjustment','case');await submit({ledgerId:'expense',counts:'1:1',reference:'LOSS-1',reason:'Investigated'});
 assert.equal(posted.body.kind,'SHORTAGE');assert.equal(posted.body.amount,'9007199254740993.17');assert.equal(posted.body.exceptionId,'case');assert.equal(posted.body.ledgerId,'expense');assert.deepEqual(posted.body.counts,[{denomination:'1',quantity:1}]);assert.equal(posted.body.expectedVersion,8);
});
test('queued adjustments remain frozen and cannot be resolved by recount in the UI',async()=>{
 const content={innerHTML:''};const incident={id:'case',location_id:'till',reporter:'teller',pending_adjustment:'request',code:'TILL_1',currency:'KES',book_amount:'100',counted_amount:'110',difference:'10'};
 const ui=createCashUi({api:async path=>path.endsWith('/transit')?[]:path.endsWith('/exceptions')?[incident]:dashboard});
 await ui.render({...branch,role:'BRANCH_MANAGER'},content,()=>true);
 assert.match(content.innerHTML,/Adjustment awaiting posting/);assert.doesNotMatch(content.innerHTML,/data-action="branch-work-cash-adjustment"/);assert.doesNotMatch(content.innerHTML,/Customer deposit/);
});

test('cash reversal pins original locations and exact amount without accepting editable money',async()=>{
 let submit,posted;const original={request_id:'posted',reference:'DEP-1',kind:'DEPOSIT',amount:'9007199254740993.17',currency:'KES',location_id:'till',destination_id:null};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {};}return path.endsWith('/reversible')?[original]:(path.endsWith('/exceptions')||path.endsWith('/transit'))?[]:dashboard;},form:(_t,_h,fn)=>submit=fn,field:()=>''});
 await ui.render(branch,{innerHTML:''},()=>true);await ui.action('reverse');await submit({originalId:'posted',amount:'1',reference:'REV-1',reason:'Correction'});
 assert.equal(posted.body.kind,'REVERSAL');assert.equal(posted.body.originalId,'posted');assert.equal(posted.body.amount,original.amount);assert.equal(posted.body.locationId,'till');assert.equal(posted.body.expectedVersion,8);assert.equal(posted.body.businessDate,'2026-10-07');assert.ok(posted.headers['Idempotency-Key']);
});

test('interbranch dispatch pins branch and business date while preserving exact money',async()=>{
 let submit,posted;const vault={...location,id:'vault',type:'VAULT',legalEntityId:'entity'};
 const data={...dashboard,items:[{location:vault,balance:'10000',reconciled:true}]};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted=options;return {};}if(path.endsWith('/transit-destinations'))return [{id:'destination',branch_name:'Other branch',code:'VAULT',currency:'KES',legal_entity_id:'entity'},{id:'wrong',currency:'UGX',legal_entity_id:'entity'}];return path.endsWith('/exceptions')||path.endsWith('/transit')?[]:data;},field:(n,l,v,o)=>{if(n==='destinationId')assert.equal(o.choices.length,1);return l;},form:(_t,_h,fn)=>submit=fn});
 await ui.render({...branch,role:'BRANCH_MANAGER'},{innerHTML:''},()=>true);await ui.action('transit-dispatch','vault');
 await submit({destinationId:'destination',ledgerId:'transit',amount:'9007199254740993.17',expectedDate:'2026-10-08',reference:'SHIP-1',reason:'Sealed shipment'});
 assert.equal(posted.body.amount,'9007199254740993.17');assert.equal(posted.body.kind,'DISPATCH');assert.equal(posted.body.branchId,'branch');assert.equal(posted.body.businessDate,'2026-10-07');assert.deepEqual(posted.body.transit,{destinationId:'destination',expectedDate:'2026-10-08',returned:false});
});
test('partial transit receipts bind original route and require physical denomination evidence',async()=>{
 let submit,posted;const shipment={dispatch_id:'dispatch',source_branch:'other',source_id:'source',destination_id:'vault',ledger_id:'transit',reference:'<shipment>',amount:'100',received:'0',returned:'0',outstanding:'100',receivable:'80',currency:'KES',dispatched:true};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted=options;return {};}return path.endsWith('/transit')?[shipment]:path.endsWith('/exceptions')?[]:dashboard;},form:(_t,_h,fn)=>submit=fn,field:()=>''});
 const content={innerHTML:''};await ui.render({...branch,role:'BRANCH_MANAGER'},content,()=>true);assert.match(content.innerHTML,/&lt;shipment&gt;/);await ui.action('transit-receive','dispatch');
 await submit({amount:'20',counts:'10:2',reference:'RECEIPT',reason:'Partial delivery'});
 assert.equal(posted.body.locationId,'vault');assert.equal(posted.body.ledgerId,'transit');assert.equal(posted.body.amount,'20');assert.deepEqual(posted.body.counts,[{denomination:'10',quantity:2}]);assert.deepEqual(posted.body.transit,{dispatchId:'dispatch',returned:false});
 shipment.source_branch='branch';await ui.render({...branch,role:'BRANCH_MANAGER'},content,()=>true);await ui.action('transit-receive','dispatch');await submit({amount:'10',counts:'10:1',reference:'RETURN',reason:'Physically returned'});assert.equal(posted.body.locationId,'source');assert.equal(posted.body.transit.returned,true);
});

test('inventory movements submit denominations while rendering held quantities',async()=>{
 let submit,posted;const content={innerHTML:''};const data={...dashboard,accounts:[{id:'customer',account_number:'A001',currency:'KES',state:'ACTIVE',liability_ledger_id:'liability'}],items:[{...dashboard.items[0],inventoryEnabled:true,inventory:[{denomination:'50',quantity:2}]}]};
 const ui=createCashUi({api:async(path,options)=>{if(options){posted=options;return {};}return path.endsWith('/exceptions')||path.endsWith('/transit')?[]:data;},form:(_t,_h,fn)=>submit=fn,field:()=>'',subject:()=> 'maker'});
 await ui.render(branch,content,()=>true);assert.match(content.innerHTML,/50 × 2/);await ui.action('deposit','till');await submit({accountId:'customer',amount:'50',counts:'50:1',businessDate:'2026-10-07',reference:'INV-DEP',reason:'Counted'});assert.deepEqual(posted.body.counts,[{denomination:'50',quantity:1}]);
});
test('receipt generation is scoped, idempotency-keyed and escapes printable evidence',async()=>{
 let html,posted;const ui=createCashUi({api:async(path,options)=>{if(options){posted={path,...options};return {bank:'<Bank>',number:17,copiesGenerated:2,amount:'9007199254740993.17',currency:'KES',reference:'<script>',counts:[{denomination:'100',quantity:1}]};}return path.endsWith('/exceptions')||path.endsWith('/transit')?[]:dashboard;},form:(_t,h)=>html=h});
 await ui.render(branch,{innerHTML:''},()=>true);await ui.action('receipt','receipt-id');assert.equal(posted.path,'/banks/bank/branches/branch/operations/cash/receipt-id/receipt');assert.ok(posted.headers['Idempotency-Key']);assert.match(html,/REPRINT/);assert.match(html,/&lt;Bank&gt;/);assert.match(html,/&lt;script&gt;/);assert.match(html,/9007199254740993.17/);assert.match(html,/100 × 1/);
});
