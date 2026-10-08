import test from 'node:test';
import assert from 'node:assert/strict';
import {createFxValuationUi} from '../src/fx-valuation-ui.js';

const book={id:'book',functionalCurrency:'KES',policy:{foreignCurrency:'USD',legalEntityId:'entity',longLimit:'100',shortLimit:'50',policyReference:'<policy>'}};
const posting={book,closingRate:{rate:'130',sourceReference:'<rate>',businessDate:'2026-10-08'},snapshot:{totals:{quantity:'1',cost:'129',cash:'-129',marketValue:'130',realized:'0',unrealized:'1',totalGainLoss:'1'},previousRealized:'0',previousUnrealized:'0',tradeCount:1},journal:{lines:[{side:'DEBIT',amount:'1',ledgerId:'bridge'},{side:'CREDIT',amount:'1',ledgerId:'gain'}]}};
const data={version:8,businessDate:'2026-10-08',storageReady:true,books:[book],closingRates:[{id:'rate',rate:{bookId:'book',businessDate:'2026-10-08'}}],valuations:[{id:'v',posting,completed:true}]};
test('valuation report escapes evidence and labels cumulative functional gain/loss',()=>{
 const ui=createFxValuationUi({});const html=ui.section(data);
 assert.match(html,/&lt;policy&gt;/);assert.match(html,/&lt;rate&gt;/);assert.match(html,/130 KES/);assert.match(html,/cumulative/);assert.doesNotMatch(html,/<rate>/);
 assert.match(ui.section({...data,storageReady:false}),/Prepare valuation storage/);
});
test('valuation requires preview before approval and pins the chosen closing-rate ID',async()=>{
 let submit,html;const calls=[];
 const ui=createFxValuationUi({api:async(path,options)=>{calls.push({path,...options});return posting;},form:(_title,h,fn)=>{html=h;submit=fn;},field:()=>'',refresh:async()=>{}});
 await ui.action('run','book',{route:'/fx',data});await submit({reason:'Close verified book'});
 assert.equal(calls.length,1);assert.match(calls[0].path,/preview$/);assert.match(html,/seal this book/);
 await submit({closingRateId:'tampered'});assert.equal(calls[1].body.closingRateId,'rate');assert.equal(calls[1].body.expectedVersion,8);assert.match(calls[1].path,/runs$/);assert.ok(calls[1].headers['Idempotency-Key']);
});
test('closing rate keeps exact decimal strings and missing rate blocks preview',async()=>{
 let submit,body;const ui=createFxValuationUi({api:async(_p,o)=>{body=o.body;},form:(_t,_h,fn)=>{submit=fn;},field:()=>'',refresh:async()=>{}});
 await ui.action('rate','book',{route:'/fx',data});await submit({rate:'123456789012.123456789012',sourceReference:'TEST/RATE',reason:'Verified'});
 assert.equal(body.rate,'123456789012.123456789012');assert.equal(body.bookId,'book');assert.equal(body.businessDate,'2026-10-08');
 await assert.rejects(()=>ui.action('run','book',{route:'/fx',data:{...data,closingRates:[]}}),e=>e.code==='FX_CLOSING_RATE_REQUIRED');
});
test('unchanged valuation presents a receipt without a zero-amount journal',async()=>{
 let html;const ui=createFxValuationUi({form:(_t,h)=>{html=h;}});
 await ui.action('details','v',{route:'/fx',data:{...data,valuations:[{id:'v',posting:{...posting,journal:null},completed:true}]}});
 assert.match(html,/No adjustment is needed/);assert.match(html,/closing receipt/);
});
