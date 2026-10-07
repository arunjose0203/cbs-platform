import test from 'node:test';import assert from 'node:assert/strict';
import {createFundingUi} from '../src/funding-ui.js';import {setLanguage,t} from '../src/i18n.js';
const account={definition:{id:'a',name:'<img src=x>',accountNumber:'A001',state:'AWAITING_FUNDING',legalEntityId:'e',terms:{currency:'KES',minimumOpening:'100',liabilityAccountId:'liab'}},delivery:'APPLIED'};
const dashboard={version:8,storageReady:true,account,balance:{currency:'KES',postedBalance:'9007199254740993.17',ledgerBalance:'9007199254740993.17',reconciled:true,liabilityLedgerId:null},items:[],total:0};
test('funding balance escapes names and preserves exact decimal strings',async()=>{let fields;const ui=createFundingUi({api:async()=>dashboard,form:(_t,f)=>fields=f});await ui.action('view','a','bank',()=>true);assert.match(fields,/&lt;img src=x&gt;/);assert.match(fields,/9007199254740993.17/);assert.match(fields,/Matched/);assert.ok(!fields.includes('<img'));});
test('funding submission pins bank version, keeps exact amounts and safely reuses idempotency keys',async()=>{
 let submit;const posts=[];const api=async(path,options)=>{if(options){posts.push({path,...options});return {};}
 if(path.includes('/funding/'))return dashboard;if(path.includes('/settings'))return {businessDate:'2026-10-06'};if(path.includes('/chart-of-accounts'))return {items:[{id:'asset',normalBalance:'DEBIT'}]};
 if(!path.includes('/ledger-accounts?'))throw new Error('Unexpected API path: '+path);
 return {items:[{delivery:'APPLIED',definition:{id:'s',code:'SOURCE',name:'Received funds',legalEntityId:'e',chartAccountId:'asset'}},{delivery:'APPLIED',definition:{id:'l',code:'LIAB',name:'Customer',legalEntityId:'e',chartAccountId:'liab'}}]};};
 const ui=createFundingUi({api,field:()=>'',form:(_t,_f,fn)=>submit=fn});await ui.action('new','a','bank',()=>true);const data={sourceLedgerId:'s',liabilityLedgerId:'l',amount:'9007199254740993.17',businessDate:'2026-10-06',reference:'R001',reason:'Verified'};
 await submit(data);await submit(data);assert.equal(posts[0].body.amount,data.amount);assert.equal(posts[0].body.expectedVersion,8);assert.equal(posts[0].body.accountId,'a');assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);await submit({...data,reference:'R002'});assert.notEqual(posts[1].headers['Idempotency-Key'],posts[2].headers['Idempotency-Key']);
});
test('frozen, pending and unreconciled accounts cannot open funding forms',async()=>{
 for(const data of [{...dashboard,account:{...account,definition:{...account.definition,state:'FROZEN'}}},{...dashboard,account:{...account,delivery:'PENDING'}},{...dashboard,balance:{...dashboard.balance,reconciled:false}}]){
 const forms=[];const ui=createFundingUi({api:async()=>data,form:(...a)=>forms.push(a)});await ui.action('new','a','bank',()=>true);assert.equal(forms.length,0);
 }
 setLanguage('fr');assert.equal(t('Record funding'),'Enregistrer un dépôt');setLanguage('sw');assert.equal(t('Record funding'),'Rekodi amana');setLanguage('en');
});
