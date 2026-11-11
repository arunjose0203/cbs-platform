import test from 'node:test';import assert from 'node:assert/strict';
import {createOperationUi} from '../src/operation-ui.js';import {createStatementUi} from '../src/statement-ui.js';
const account={definition:{id:'a',accountNumber:'<A001>',state:'ACTIVE',legalEntityId:'e',terms:{currency:'KES'}},delivery:'APPLIED'};
const data={version:9,storageReady:true,position:{posted:'9007199254740993.17',held:'100',available:'9007199254740893.17',withdrawable:'9007199254740883.17',reconciled:true},items:[],total:0};
function api(path){if(path.includes('/account-operations/'))return data;if(path.includes('/settings'))return {businessDate:'2026-10-07'};if(path.endsWith('/accounts/a'))return account;throw Error('Unexpected API '+path);}
test('operations preserve decimal strings, escape content and show protected balances',async()=>{let html;const ui=createOperationUi({api:async p=>api(p),form:(_t,h)=>html=h});await ui.action('view','a','bank',()=>true);assert.match(html,/9007199254740993.17/);assert.match(html,/&lt;A001&gt;/);assert.match(html,/Withdrawable after minimum balance/);assert.match(html,/account-operation-TRANSFER/);});
test('hold submissions preserve idempotency and change keys only when payload changes',async()=>{let submit;const posts=[];const ui=createOperationUi({api:async(p,o)=>{if(o){posts.push({p,...o});return {};}return api(p);},field:()=>'',form:(_t,_h,fn)=>submit=fn});await ui.action('HOLD','a','bank',()=>true);const values={amount:'9007199254740883.17',businessDate:'2026-10-07',reference:'H1',reason:'Court reference'};await submit(values);await submit(values);assert.equal(posts[0].body.kind,'HOLD');assert.equal(posts[0].body.amount,values.amount);assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);await submit({...values,amount:'1'});assert.notEqual(posts[2].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);});
test('unreconciled accounts cannot open debit forms',async()=>{let calls=0;const ui=createOperationUi({api:async p=>p.includes('/account-operations/')?{...data,position:{...data.position,reconciled:false}}:api(p),form:()=>calls++});await ui.action('WITHDRAWAL','a','bank',()=>true);assert.equal(calls,0);});
test('statement filters remain open and pagination preserves running balances',async()=>{let submit,html;const paths=[];const ui=createStatementUi({api:async p=>{paths.push(p);if(p.endsWith('/settings'))return {businessDate:'2026-10-07'};return {accountNumber:'A001',currency:'KES',from:'2026-10-01',to:'2026-10-07',openingBalance:'100',closingBalance:'9007199254740993.17',items:[{businessDate:'2026-10-07',reference:'<script>',narration:'Deposit',debit:'0',credit:'9007199254740893.17',balance:'9007199254740993.17'}],total:26};},field:()=>'',form:(_t,h,fn)=>{html=h;submit=fn;}});await ui.action('view','a','b',()=>true);const result=await submit({from:'2026-10-01',to:'2026-10-07'});assert.equal(result.keepOpen,true);assert.match(html,/&lt;script&gt;/);assert.match(html,/9007199254740993.17/);await ui.action('next','a','b',()=>true);assert.match(paths.at(-1),/offset=25/);assert.match(paths.at(-1),/from=2026-10-01/);});

test('customer withdrawal reversal preserves original amount and excludes cash-managed items',async()=>{
 let submit,posted,html;let cashManaged=false;const item={entry:{id:'original',change:{kind:'WITHDRAWAL',accountId:'a',amount:'123.45',reference:'OUT'}},status:'POSTED'};
 const ui=createOperationUi({api:async(path,options)=>{if(options){posted=options;return {};}return path.includes('/account-operations/')?{...data,items:[{...item,cashManaged}]}:api(path);},field:()=>'',form:(_t,h,fn)=>{html=h;submit=fn;}});
 await ui.action('reverse','a/original','bank',()=>true);await submit({reference:'REV',reason:'Verified correction'});
 assert.equal(posted.body.kind,'WITHDRAWAL_REVERSAL');assert.equal(posted.body.amount,'123.45');assert.equal(posted.body.originalId,'original');assert.equal(posted.body.assetLedgerId,null);
 cashManaged=true;await ui.action('view','a','bank',()=>true);assert.doesNotMatch(html,/data-action="account-operation-reverse"/);
});
test('debits on a joint account collect the required distinct signers',async()=>{
 const {createOperationUi}=await import('../src/operation-ui.js');let submit,html;const posts=[];
 const ui=createOperationUi({field:(name,label)=>'['+name+']',form:(_t,h,fn)=>{html=h;submit=fn;},api:async(path,options)=>{
  if(options){posts.push(options);return {};}
  if(path.includes('/account-operations/'))return {version:3,storageReady:true,position:{posted:'100',held:'0',available:'100',withdrawable:'90',reconciled:true},items:[],total:0};
  if(path.endsWith('/mandate'))return {required:true,configured:true,signaturesNeeded:2,signers:[{customerId:'h',customerNumber:'C1',role:'HOLDER'},{customerId:'j',customerNumber:'C2',role:'JOINT_HOLDER'}]};
  if(path.includes('/ledger-accounts?'))return {items:[{delivery:'APPLIED',definition:{id:'cash',code:'1100',name:'Vault',legalEntityId:'e',chartAccountId:'asset'}}]};
  if(path.includes('/chart-of-accounts?'))return {items:[{id:'asset',normalBalance:'DEBIT'}]};
  if(path.endsWith('/settings'))return {businessDate:'2026-11-11'};
  return {definition:{id:'acc',accountNumber:'A1',state:'ACTIVE',legalEntityId:'e',terms:{currency:'KES'}},delivery:'APPLIED'};
 }});
 await ui.action('WITHDRAWAL','acc','bank',()=>true);assert.match(html,/\[signer0\]\[signer1\]/);
 await submit({assetLedgerId:'cash',signer0:'h',signer1:'j',amount:'5',businessDate:'2026-11-11',reference:'W',reason:'Slip'});
 assert.deepEqual(posts[0].body.signatories,['h','j']);
 await submit({assetLedgerId:'cash',signer0:'h',signer1:'h',amount:'5',businessDate:'2026-11-11',reference:'W',reason:'Slip'});
 assert.deepEqual(posts[1].body.signatories,['h']);
});
test('signer step submits immediately for ordinary accounts and asks for signers otherwise',async()=>{
 const {requestSigners}=await import('../src/signers.js');let html,submitForm;const sent=[];
 const deps=m=>({api:async()=>m,form:(_t,h,fn)=>{html=h;submitForm=fn;},field:(name)=>'['+name+']'});
 await requestSigners(deps({required:false}),'/base','acc',s=>sent.push(s));assert.deepEqual(sent,[null]);
 await assert.rejects(requestSigners(deps({required:true,configured:false}),'/base','acc',()=>{}),/approved mandate/);
 const keep=await requestSigners(deps({required:true,configured:true,signaturesNeeded:2,signers:[{customerId:'a',customerNumber:'C1',role:'HOLDER'},{customerId:'b',customerNumber:'C2',role:'JOINT_HOLDER'}]}),'/base','acc',s=>sent.push(s));
 assert.deepEqual(keep,{keepOpen:true});assert.match(html,/\[signer0\]\[signer1\]/);await submitForm({signer0:'a',signer1:'b'});assert.deepEqual(sent[1],['a','b']);
});
