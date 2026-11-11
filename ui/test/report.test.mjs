import test from 'node:test';
import assert from 'node:assert/strict';
import {reportTable} from '../src/report-ui.js';
test('accounting report escapes labels and values and keeps exact money strings',()=>{
  const html=reportTable({columns:['<img>','Amount'],rows:[['<script>','9007199254740993.17']]});
  assert.ok(!html.includes('<img>'));assert.ok(!html.includes('<script>'));assert.ok(html.includes('9007199254740993.17'));assert.ok(html.includes('&lt;script&gt;'));
});
test('management reports escape data, show loan buckets and parse definition lines into chart IDs',async()=>{
 const {createReportingUi}=await import('../src/reporting-ui.js');const posts=[];let submit;const host={innerHTML:''};const files=[];
 const ui=createReportingUi({field:(n)=>'['+n+']',form:(_t,_h,fn)=>{submit=fn;},refresh:async()=>{},download:(b,n)=>files.push(n),api:async(path,options)=>{
  if(options?.method==='POST'){posts.push(options);return {};}
  if(options?.responseType==='blob')return 'blob';
  if(path==='/admin/tenants?limit=100')return {items:[{id:'b',status:'ACTIVE',displayName:'Bank'}]};
  if(path==='/admin/tenants/b')return {id:'b',version:3,displayName:'Bank',legalEntities:[{id:'e',legalName:'Entity',functionalCurrency:'KES'}]};
  if(path.endsWith('/dashboard'))return {businessDate:'2026-11-11',deposits:{totals:[{currency:'KES',accounts:2,balance:'9007199254740993.17'}],byProduct:[],byBranch:[],topDepositors:[{customerNumber:'C1',name:'<b>Wanjiru</b>',currency:'KES',balance:'5',shareOfDeposits:'50'}]},loans:{portfolio:[{currency:'KES',loans:1,outstanding:'100',buckets:{CURRENT:'60','1_30':'0','31_60':'0','61_90':'0','91_180':'40','181_PLUS':'0'},nonPerformingRatio:'40'}],unreconciled:0,truncated:false},cash:[],work:{pendingApprovals:[{action:'JOURNAL_POST',count:2}],failedPostings:{journal_delivery:1},alerts:[],openComplianceCases:0,openCashExceptions:0,openPaymentCases:0}};
  if(path.endsWith('/definitions'))return [{code:'CBK_BS',name:'Balance sheet return',basis:'BALANCE',lines:3}];
  if(path.includes('/chart-of-accounts'))return {items:[{code:'2100',id:'c1'},{code:'4100',id:'c2'}]};
  return {};
 }});
 await ui.render(host,()=>true);
 assert.match(host.innerHTML,/9007199254740993\.17/);assert.match(host.innerHTML,/&lt;b&gt;Wanjiru/);assert.ok(!host.innerHTML.includes('<b>Wanjiru'));assert.match(host.innerHTML,/CBK_BS/);assert.match(host.innerHTML,/JOURNAL_POST/);
 await ui.action('define');await submit({code:'MGMT',name:'Summary',basis:'BALANCE',lines:'10 | Deposits | 2100 | +\n20 | Fees | 4100 | -',reason:'Template'});
 assert.deepEqual(posts[0].body.lines,[{code:'10',label:'Deposits',chartAccounts:['c1'],sign:1},{code:'20',label:'Fees',chartAccounts:['c2'],sign:-1}]);
 await assert.rejects(submit({code:'MGMT',name:'Summary',basis:'BALANCE',lines:'10 | Deposits | 9999 | +',reason:'x'}),/Unknown chart account/);
 await ui.action('run','CBK_BS');await submit({legalEntityId:'e',currency:'KES',to:'2026-11-11',format:'csv'});assert.equal(files[0],'report-CBK_BS-2026-11-11.csv');
});
