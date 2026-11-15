import test from 'node:test';
import assert from 'node:assert/strict';
import {createComplaintsUi} from '../src/complaints-ui.js';
import {setLanguage} from '../src/i18n.js';

const bank={id:'bank',displayName:'Bank <demo>',status:'ACTIVE'};
const open={id:'c1',reference:'CMP-2026-000001',customerNumber:'C001',accountNumber:'A001',channel:'BRANCH',category:'FEES_AND_CHARGES',summary:'Fee <b>twice</b>',
  disputedAmount:'150.5',disputedCurrency:'KES',receivedOn:'2026-11-10',acknowledgeDue:'2026-11-12',acknowledgedOn:null,acknowledgementOverdue:true,resolveDue:'2026-11-24',
  resolutionOverdue:false,status:'OPEN',escalationLevel:1,assignee:null,outcome:null,redress:null,redressPending:false,resolutionPending:false,version:4};
const resolved={...open,id:'c2',reference:'CMP-2026-000002',status:'RESOLVED',outcome:'UPHELD',redress:'REFUND',redressAmount:'150.5',redressCurrency:'KES',redressPending:true,
  acknowledgedOn:'2026-11-11',acknowledgementOverdue:false,version:7};
const frozen={...open,id:'c3',resolutionPending:true};
const summary={received:2,open:1,overdueOpen:0,acknowledgedLate:1,resolvedWithinDue:1,resolvedLate:0,redressPending:1};

function fixture(){
  let submit,html,posted,downloaded;const calls=[],fields=[];
  const ui=createComplaintsUi({
    api:async(path,options)=>{
      calls.push(path);
      if(options?.method==='POST'){posted={path,...options};return path.endsWith('/resolutions')?{status:'PENDING'}:{};}
      if(options?.responseType==='blob')return 'csv-blob';
      if(path.includes('/customers?'))return {items:[{id:'customer',customerNumber:'C001',fullName:'Test only'}]};
      if(path.includes('/complaints/summary'))return summary;
      if(path.endsWith('/complaints/c1'))return {version:9,today:'2026-11-15',complaint:open,details:'Customer says <script>alert(1)</script>',events:[{action:'RECEIVED',actorSubject:'maker',note:'Complaint <registered>',details:{},occurredAt:'2026-11-10T08:00:00Z'}]};
      if(path.endsWith('/complaints/c2'))return {version:9,today:'2026-11-15',complaint:resolved,details:'Refund agreed',events:[{action:'RESOLVED',actorSubject:'checker',note:'Refund',details:{requestId:'req-1'},occurredAt:'2026-11-14T08:00:00Z'}]};
      if(path.endsWith('/complaints/c3'))return {version:9,today:'2026-11-15',complaint:frozen,details:'Waiting',events:[]};
      if(path.includes('/complaints?'))return {version:9,today:'2026-11-15',total:27,items:[open,resolved]};
      return path.includes('?')?{items:[bank]}:bank;
    },
    form:(_title,body,fn)=>{html=body;submit=fn;},field:(name,label,value)=>{fields.push({name,value});return label;},refresh:async()=>{},download:(blob,name)=>{downloaded={blob,name};}});
  return {ui,calls,fields,get html(){return html;},get posted(){return posted;},get downloaded(){return downloaded;},submit:v=>submit(v)};
}

test('complaint list shows deadlines, pending approval and redress, and escapes customer text',async()=>{
  const f=fixture(),host={innerHTML:''};await f.ui.render(host,()=>true);
  assert.match(host.innerHTML,/Bank &lt;demo&gt;/);assert.match(host.innerHTML,/CMP-2026-000001/);assert.match(host.innerHTML,/OVERDUE/);
  assert.match(host.innerHTML,/Fee &lt;b&gt;twice&lt;\/b&gt;/);assert.doesNotMatch(host.innerHTML,/<b>twice/);
  assert.match(host.innerHTML,/Redress pending/);assert.match(host.innerHTML,/150\.5 KES/);assert.match(host.innerHTML,/27 complaints/);
  // The 30-day summary ends on the bank's own date as reported by the server.
  assert.ok(f.calls.some(p=>p.endsWith('/complaints/summary?from=2026-10-16&to=2026-11-15')));
  await f.ui.action('next');await f.ui.render({innerHTML:''},()=>true);assert.ok(f.calls.some(p=>p.includes('offset=25')));
});

test('registration sends an idempotency key and leaves out empty optional fields',async()=>{
  const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('register');
  assert.equal(f.fields.find(v=>v.name==='receivedOn').value,'2026-11-15');
  await f.submit({customerId:'customer',channel:'PHONE',category:'SERVICE',receivedOn:'2026-11-15',summary:' Long queue ',details:' Waited two hours ',transactionReference:'',disputedAmount:'',disputedCurrency:''});
  assert.equal(f.posted.path,'/admin/tenants/bank/complaints');assert.ok(f.posted.headers['Idempotency-Key']);
  assert.deepEqual(f.posted.body,{customerId:'customer',channel:'PHONE',category:'SERVICE',receivedOn:'2026-11-15',summary:'Long queue',details:'Waited two hours'});
  await f.ui.action('register');await f.submit({customerId:'customer',channel:'BRANCH',category:'CASH',receivedOn:'2026-11-15',summary:'Short cash',details:'Counted less',transactionReference:'TXN/1',disputedAmount:'200.00',disputedCurrency:'KES'});
  assert.equal(f.posted.body.disputedAmount,'200.00');assert.equal(f.posted.body.disputedCurrency,'KES');assert.equal(f.posted.body.transactionReference,'TXN/1');
});

test('handling steps pin the complaint version and the history escapes the customer account',async()=>{
  const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('detail','c1');
  assert.match(f.html,/&lt;script&gt;/);assert.doesNotMatch(f.html,/<script>/);assert.match(f.html,/Complaint &lt;registered&gt;/);
  assert.match(f.html,/complaints-acknowledge/);assert.match(f.html,/complaints-resolve/);assert.doesNotMatch(f.html,/complaints-reopen/);assert.doesNotMatch(f.html,/complaints-redress/);
  await f.ui.action('acknowledge','c1');await f.submit({note:' Called customer ',caseVersion:900});
  assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c1/acknowledgement');assert.deepEqual(f.posted.body,{caseVersion:4,note:'Called customer'});
  await f.ui.action('assign','c1');await f.submit({assignee:' handler-1 ',note:'Fee review'});
  assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c1/assignment');assert.deepEqual(f.posted.body,{caseVersion:4,assignee:'handler-1',note:'Fee review'});
  await f.ui.action('escalate','c1');await f.submit({note:'Manager'});assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c1/escalation');
  await f.ui.action('note','c1');await f.submit({note:'Checked statement'});assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c1/notes');
});

test('a resolution is submitted for independent approval with both versions and no amount unless given',async()=>{
  const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('resolve','c1');
  assert.match(f.html,/Another administrator must approve/);assert.equal(f.fields.find(v=>v.name==='redressCurrency').value,'KES');
  await f.submit({outcome:'NOT_UPHELD',redress:'EXPLANATION',redressAmount:'',redressCurrency:'KES',rootCause:'NO_ERROR',response:' The fee was charged once ',reason:' Statement checked '});
  assert.equal(f.posted.path,'/admin/tenants/bank/complaints/resolutions');assert.ok(f.posted.headers['Idempotency-Key']);
  assert.deepEqual(f.posted.body,{expectedVersion:9,complaintId:'c1',caseVersion:4,outcome:'NOT_UPHELD',redress:'EXPLANATION',rootCause:'NO_ERROR',response:'The fee was charged once',reason:'Statement checked'});
  await f.ui.action('resolve','c1');await f.submit({outcome:'UPHELD',redress:'REFUND',redressAmount:'150.50',redressCurrency:'KES',rootCause:'DUPLICATE_FEE',response:'Refund',reason:'Agreed'});
  assert.equal(f.posted.body.redressAmount,'150.50');assert.equal(f.posted.body.redressCurrency,'KES');
});

test('a complaint awaiting approval offers no handling steps',async()=>{
  const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('detail','c3');
  assert.match(f.html,/Awaiting approval/);assert.doesNotMatch(f.html,/data-action="complaints-/);
});

test('a resolved complaint offers the redress link and reopening, and the register downloads through the API',async()=>{
  const f=fixture();await f.ui.render({innerHTML:''},()=>true);await f.ui.action('detail','c2');
  assert.match(f.html,/complaints-redress/);assert.match(f.html,/complaints-reopen/);assert.doesNotMatch(f.html,/complaints-resolve/);assert.match(f.html,/req-1/);
  await f.ui.action('redress','c2');await f.submit({requestId:' 5f0c1c9e-0000-4000-8000-000000000001 ',note:'Refund credited'});
  assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c2/redress-posting');assert.deepEqual(f.posted.body,{caseVersion:7,requestId:'5f0c1c9e-0000-4000-8000-000000000001',note:'Refund credited'});
  await f.ui.action('reopen','c2');await f.submit({note:'Customer disagrees'});assert.equal(f.posted.path,'/admin/tenants/bank/complaints/c2/reopening');assert.equal(f.posted.body.caseVersion,7);
  await f.ui.action('export');await f.submit({from:'2026-10-01',to:'2026-10-31'});
  assert.ok(f.calls.includes('/admin/tenants/bank/complaints/register?from=2026-10-01&to=2026-10-31'));
  assert.deepEqual(f.downloaded,{blob:'csv-blob',name:'complaints-register-2026-10-01-2026-10-31.csv'});
});

test('complaint screen is translated into French and Kiswahili',async()=>{
  try{
    for(const [language,title,pending] of [['fr','Réclamations et litiges','Réparation en attente'],['sw','Malalamiko na migogoro','Fidia inasubiri']]){
      setLanguage(language);const f=fixture(),host={innerHTML:''};await f.ui.render(host,()=>true);
      assert.ok(host.innerHTML.includes(title),language);assert.ok(host.innerHTML.includes(pending),language);
    }
  }finally{setLanguage('en');}
});
