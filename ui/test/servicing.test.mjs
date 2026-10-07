import test from 'node:test';
import assert from 'node:assert/strict';
import {createServicingUi} from '../src/servicing-ui.js';
const bank={id:'bank',status:'ACTIVE',displayName:'Bank <demo>',legalEntities:[{id:'entity',legalName:'Demo'}]};
const data={version:4,businessDate:'2026-10-07',storageReady:true,policies:[],runs:[]};
function fixture(extra={}){let submit,html,posted,fields=[];const ui=createServicingUi({api:async(path,options)=>{if(options){posted={path,...options};if(path.endsWith('/preview'))return {nextBusinessDate:'2026-10-08',items:[{account:{accountNumber:'<account>',terms:{currency:'KES'}},openingBalance:'9007199254740993.17',calculation:{grossInterest:'1.01',interestTax:'0.1',fee:'0.2',feeTax:'0',netChange:'0.71',carryAfter:'0.00001'}}]};return {}; }if(path.endsWith('/servicing'))return data;if(path.includes('/products?'))return {items:[{id:'product',code:'SAVE',revision:1}]};return path.includes('?')?{items:[bank]}:bank;},form:(_t,h,fn)=>{html=h;submit=fn;},field:(name,label,value)=>{fields.push({name,value});return label;},refresh:async()=>{},...extra});return {ui,get html(){return html;},get posted(){return posted;},fields,submit:v=>submit(v)};}
test('servicing preview preserves exact money and pins server business date',async()=>{
 const f=fixture(),host={innerHTML:''};await f.ui.render(host,()=>true);assert.match(host.innerHTML,/Bank &lt;demo&gt;/);
 await f.ui.action('preview');assert.match(f.html,/9007199254740993.17/);assert.match(f.html,/&lt;account&gt;/);assert.match(f.html,/2026-10-08/);
 await f.submit({reason:'Reviewed',businessDate:'1999-01-01'});assert.equal(f.posted.body.businessDate,'2026-10-07');assert.equal(f.posted.body.expectedVersion,4);assert.ok(f.posted.headers['Idempotency-Key']);assert.equal(f.posted.path,'/admin/tenants/bank/servicing/runs');
});
test('servicing policy leaves financial rates blank and preserves entered decimal strings',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('policy');
 for(const name of ['annualRatePercent','interestTaxPercent','feeTaxPercent','feePerBusinessDay'])assert.equal(f.fields.find(x=>x.name===name).value,'');
 await f.submit({productId:'product',productRevision:'2',dayBasis:'360',annualRatePercent:'0.12345678',feePerBusinessDay:'9007199254740993.17',reason:'Bank approved'});
 assert.equal(f.posted.body.annualRatePercent,'0.12345678');assert.equal(f.posted.body.feePerBusinessDay,'9007199254740993.17');assert.equal(f.posted.body.productRevision,2);assert.equal(f.posted.body.dayBasis,360);assert.equal(f.posted.body.expectedVersion,4);
});
