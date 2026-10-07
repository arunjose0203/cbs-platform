import test from 'node:test';
import assert from 'node:assert/strict';
import {createComplianceUi} from '../src/compliance-ui.js';
const bank={id:'bank',displayName:'Bank <demo>',status:'ACTIVE'};
const row={id:'case',customer_number:'C001',customer_version:7,category:'KYC_REVIEW',status:'OPEN',version:2,restricted:true};
function fixture(){let submit,html,posted,fields=[];const calls=[];
 const ui=createComplianceUi({api:async(path,options)=>{calls.push(path);if(options){posted={path,...options};return {};}if(path.includes('/customers?'))return {items:[{id:'customer',customerNumber:'C001',fullName:'Test only'}]};if(path.endsWith('/compliance/case'))return {version:9,case:row,events:[{action:'OPENED',subject:'maker',at:'2026-10-07',evidence:{reason:'<script>',evidenceReference:'REF/1'}}]};if(path.includes('/compliance?'))return {version:9,items:[row],total:26};return path.includes('?')?{items:[bank]}:bank;},form:(_t,h,fn)=>{html=h;submit=fn;},field:(name,label,value)=>{fields.push({name,value});return label;},refresh:async()=>{}});
 return {ui,calls,fields,get html(){return html;},get posted(){return posted;},submit:v=>submit(v)};
}
test('compliance opening warns about immediate restriction and pins bank version',async()=>{
 const f=fixture(),host={innerHTML:''};await f.ui.render(host,()=>true);assert.match(host.innerHTML,/Bank &lt;demo&gt;/);assert.match(host.innerHTML,/Restricted/);
 await f.ui.action('open');assert.match(f.html,/immediately restricts/);await f.submit({customerId:'customer',category:'KYC_REVIEW',evidenceReference:'REF/1',reason:'Review'});
 assert.equal(f.posted.path,'/admin/tenants/bank/compliance');assert.equal(f.posted.body.expectedVersion,9);assert.ok(f.posted.headers['Idempotency-Key']);
});
test('clearance pins evidence versions and requires explicit risk and review date',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('clear','case');
 assert.equal(f.fields.find(v=>v.name==='risk').value,'');assert.equal(f.fields.find(v=>v.name==='reviewDue').value,'');
 await f.submit({risk:'MEDIUM',identityEvidence:'ID/1',screeningEvidence:'SCREEN/1',reviewDue:'2026-11-07',reason:'Reviewed',caseVersion:900,customerVersion:900});
 assert.equal(f.posted.body.caseVersion,2);assert.equal(f.posted.body.customerVersion,7);assert.equal(f.posted.body.decision,'CLEARED');assert.equal(f.posted.body.expectedVersion,9);
 await f.ui.action('restrict','case');await f.submit({risk:'HIGH',identityEvidence:'ID/1',screeningEvidence:'SCREEN/2',reviewDue:'2099-01-01',reason:'Confirmed'});assert.equal(f.posted.body.reviewDue,null);assert.equal(f.posted.body.decision,'RESTRICTED');
});
test('case history escapes evidence and next-page requests do not truncate the case inbox',async()=>{
 const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('detail','case');assert.match(f.html,/&lt;script&gt;/);assert.doesNotMatch(f.html,/<script>/);
 await f.ui.action('next');await f.ui.render({innerHTML:''},()=>true);assert.ok(f.calls.some(p=>p.includes('offset=25')));
});
