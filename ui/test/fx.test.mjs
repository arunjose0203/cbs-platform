import test from 'node:test';
import assert from 'node:assert/strict';
import {createFxUi} from '../src/fx-ui.js';
import {createFxTradeUi} from '../src/fx-trade-ui.js';
test('FX page escapes rate evidence and keeps quote inputs as exact strings',async()=>{
 let submit,html,posted;
 const rate={baseCurrency:'USD',quoteCurrency:'KES',buyRate:'129',midRate:'130',sellRate:'131',effectiveFrom:'2026-10-08',effectiveTo:'2026-10-08',expiresAt:'2026-10-09T00:00:00Z',sourceReference:'<evidence>'};
 const ui=createFxUi({api:async(path,options)=>{if(options){posted=options.body;return {sourceAmount:posted.sourceAmount,sourceCurrency:'USD',targetAmount:'100',targetCurrency:'KES',appliedRate:'129',roundingMode:'HALF_EVEN',roundingDifference:'0',expiresAt:rate.expiresAt};}if(path.endsWith('/valuation'))return {version:5,businessDate:rate.effectiveFrom,storageReady:true,books:[],closingRates:[],valuations:[]};if(path.includes('/trades?'))return {version:5,businessDate:rate.effectiveFrom,items:[],total:0,storageReady:true};if(path.includes('/fx?'))return {version:5,businessDate:rate.effectiveFrom,currencies:[{code:'USD'},{code:'KES'}],items:[{id:'rate',rate,status:'ACTIVE'}],total:1};if(path.includes('?'))return {items:[{id:'bank',status:'ACTIVE'}]};return {displayName:'Test'};},form:(_t,h,fn)=>{html=h;submit=fn;},field:()=>'',refresh:async()=>{}});
 const host={innerHTML:''};await ui.render(host,()=>true);assert.match(host.innerHTML,/&lt;evidence&gt;/);assert.match(host.innerHTML,/Convert customer funds/);
 await ui.action('quote','rate');await submit({side:'BANK_BUYS_BASE',sourceAmount:'9007199254740993.17'});assert.equal(posted.sourceAmount,'9007199254740993.17');assert.match(html,/9007199254740993.17/);
});
test('FX conversion submits only after the server preview and preserves the approved target',async()=>{
 let submit,html;const calls=[];
 const ui=createFxTradeUi({api:async(path,options)=>{calls.push({path,...options});if(path.includes('/accounts?'))return {total:2,items:[{id:'s',accountNumber:'S',customerNumber:'C',currency:'KES',delivery:'APPLIED'},{id:'t',accountNumber:'T',customerNumber:'C',currency:'USD',delivery:'APPLIED'}]};if(path.endsWith('/quote'))return {targetAmount:'1.01'};if(path.endsWith('/preview'))return {source:{accountNumber:'S'},target:{accountNumber:'T'},quote:{sourceAmount:'132.31',targetAmount:'1.01',appliedRate:'131',roundingMode:'HALF_EVEN',expiresAt:'tomorrow'}};return {};},form:(_t,h,fn)=>{html=h;submit=fn;},field:()=>'',refresh:async()=>{}});
 await ui.action('convert','rate',{route:'/admin/tenants/bank/fx',version:7,date:'2026-10-08',rate:{baseCurrency:'USD',quoteCurrency:'KES'}});
 await submit({search:'C',side:'BANK_SELLS_BASE'});
 await submit({sourceAccountId:'s',targetAccountId:'t',sourcePositionLedger:'a',targetPositionLedger:'b',sourceAmount:'132.31',reason:'Reviewed'});
 assert.match(html,/1.01 USD/);assert.equal(calls.filter(x=>x.path.endsWith('/trades')).length,0);
 await submit({expectedTargetAmount:'999'});const posted=calls.at(-1);assert.equal(posted.body.expectedTargetAmount,'1.01');assert.equal(posted.body.sourceAmount,'132.31');assert.equal(posted.body.expectedVersion,7);assert.ok(posted.headers['Idempotency-Key']);
});
