import test from 'node:test';
import assert from 'node:assert/strict';
import {createDayCloseUi} from '../src/day-close-ui.js';
test('close readiness blocks submission and escapes history fields',async()=>{
  const host={innerHTML:''},forms=[];
  const ui=createDayCloseUi({form:(...args)=>forms.push(args),api:async path=>path==='/admin/tenants?limit=100'?{items:[{id:'b',displayName:'<img>',status:'ACTIVE',legalEntities:[]}]}:{version:1,businessDate:'2026-10-05',nextBusinessDate:'2026-10-06',storageReady:true,blockers:['DAY_CLOSE_IN_PROGRESS'],history:[{requestId:'req',businessDate:'2026-10-05',nextBusinessDate:'2026-10-06',status:'CLOSING',checker:'<script>',totals:[]}]}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/Retry close/);assert.match(host.innerHTML,/data-action="day-close"[^>]*disabled/);assert.ok(host.innerHTML.includes('&lt;script&gt;'));assert.ok(!host.innerHTML.includes('<img>'));
  await ui.action('close');assert.equal(forms.length,0);
});
test('period close section shows policy and history and previews before submitting for approval',async()=>{
 const {createDayCloseUi}=await import('../src/day-close-ui.js');let html,submit;const posts=[];const host={innerHTML:''};
 const ui=createDayCloseUi({field:(name)=>'['+name+']',form:(_t,h,fn)=>{html=h;submit=fn;},refresh:async()=>{},api:async(path,options)=>{
  if(options&&path.endsWith('/preview'))return {type:'YEAR',periodEnd:'2026-12-31',totals:[{currency:'KES',debits:'9007199254740993.17',credits:'9007199254740993.17',income:'350',expense:'130',netResult:'220'}],journals:[{reference:'YEC-2026-12-31-AB',lines:[1,2,3]}]};
  if(options){posts.push({path,...options});return {};}
  if(path==='/admin/tenants?limit=100')return {items:[{id:'b',displayName:'Bank',status:'ACTIVE'}]};
  if(path.endsWith('/period-close'))return {storageReady:true,periodEnd:true,policy:{fiscalYearEndMonth:12},closes:[{request_id:'r1',period_end:'2026-11-30',period_type:'MONTH',completed:true}]};
  return {version:7,businessDate:'2026-12-31',nextBusinessDate:'2027-01-04',storageReady:true,blockers:['PERIOD_CLOSE_REQUIRED'],history:[],total:0};
 }});
 await ui.render(host,()=>true);
 assert.match(host.innerHTML,/Fiscal year ends in month 12/);assert.match(host.innerHTML,/2026-11-30/);assert.match(host.innerHTML,/Close the month/);
 await ui.action('period-close');assert.match(html,/9007199254740993\.17/);assert.match(html,/YEC-2026-12-31-AB \(3\)/);
 await submit({reason:'Reviewed trial balance'});assert.equal(posts[0].path,'/admin/tenants/b/period-close/runs');assert.equal(posts[0].body.businessDate,'2026-12-31');assert.equal(posts[0].body.expectedVersion,7);
});
