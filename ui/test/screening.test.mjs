import test from 'node:test';
import assert from 'node:assert/strict';
import {createScreeningUi} from '../src/screening-ui.js';
const banks=[{id:'bank',status:'ACTIVE',displayName:'Bank <demo>'}];
function fixture(){
 const posts=[];let html,submit,title,host={innerHTML:''};
 const ui=createScreeningUi({api:async(path,options)=>{if(options){posts.push({path,...options});return {status:'PENDING'};}
  if(path==='/admin/tenants?limit=100')return {items:banks};if(path==='/admin/tenants/bank')return {version:7,displayName:'Bank <demo>'};
  if(path.endsWith('/screening/lists'))return [{id:'l1',name:'UN_SANCTIONS',category:'SANCTIONS',status:'DRAFT',entry_count:2,threshold:0.88,sha256:'a'.repeat(64),created_at:'2026-11-11T10:00:00Z'},{id:'l0',name:'UN_SANCTIONS',category:'SANCTIONS',status:'ACTIVE',entry_count:1,threshold:0.88,sha256:'b'.repeat(64),run_status:'DONE',run_screened:40,run_hits:1}];
  if(path.includes('/screening/hits?'))return {total:1,items:[{id:'h1',version:0,customer_number:'C1',customer_name:'<script>Victor</script>',matched_name:'Viktor Petrov',list_name:'UN_SANCTIONS',entry_ref:'UN-1',score:0.95,trigger:'LIST'}]};
  if(path.endsWith('/screening/hits/h1'))return {hit:{customer_number:'C1',customer_name:'Victor',entry_names:['Viktor Petrov','V. Petrov'],entity_type:'INDIVIDUAL',score:0.95,status:'OPEN'}};
  if(path.endsWith('/monitoring/policies'))return [{currency:'KES',summary:{currency:'KES',largeCash:'single cash movement ≥ 1000000',reason:'x'},updatedAt:'2026-11-11T10:00:00Z'}];
  if(path.includes('/monitoring/alerts?'))return {total:1,items:[{id:'a1',version:2,rule:'STRUCTURING',account_number:'ACC1',customer_number:'C2',business_date:'2026-11-11',currency:'KES',amount:'1050'}]};
  return {};},
  form:(t,h,fn)=>{title=t;html=h;submit=fn;},field:(name,label)=>'['+name+']'+label,refresh:async()=>{}});
 return {ui,host,posts,get html(){return html;},get title(){return title;},submit:v=>submit(v)};
}
test('queues escape customer data and offer activation only for drafts',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);
 assert.ok(!f.host.innerHTML.includes('<script>'));assert.match(f.host.innerHTML,/&lt;script&gt;Victor/);
 assert.equal((f.host.innerHTML.match(/screening-activate/g)||[]).length,1);assert.match(f.host.innerHTML,/data-id="l1"/);
 assert.match(f.host.innerHTML,/DONE · 40 screened · 1 matches/);assert.match(f.host.innerHTML,/single cash movement/);
 assert.match(f.host.innerHTML,/data-action="screening-escalate-alert" data-id="a1:2"/);
});
test('activation and dismissal go to approval with pinned versions and stable retry keys',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);
 await f.ui.action('activate','l1');assert.match(f.html,/a{64}/);
 await f.submit({reason:'Adopt list'});await f.submit({reason:'Adopt list'});
 assert.equal(f.posts[0].path,'/admin/tenants/bank/compliance/screening/activations');assert.deepEqual(f.posts[0].body,{expectedVersion:7,listId:'l1',sha256:'a'.repeat(64),reason:'Adopt list'});
 assert.equal(f.posts[0].headers['Idempotency-Key'],f.posts[1].headers['Idempotency-Key']);
 await f.ui.action('dismiss-hit','h1:0');await f.submit({evidenceReference:'EV/1',reason:'Different person'});
 assert.equal(f.posts[2].path,'/admin/tenants/bank/compliance/dismissals');assert.equal(f.posts[2].body.type,'SCREENING_HIT');assert.equal(f.posts[2].body.itemVersion,0);
 await f.submit({evidenceReference:'EV/2',reason:'Different person'});assert.notEqual(f.posts[3].headers['Idempotency-Key'],f.posts[2].headers['Idempotency-Key']);
});
test('escalation is immediate and monitoring rules send only the rules that were filled in',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);
 await f.ui.action('escalate-alert','a1:2');await f.submit({evidenceReference:'EV/3',reason:'Source of funds missing'});
 assert.equal(f.posts[0].path,'/admin/tenants/bank/compliance/monitoring/alerts/a1/escalate');assert.equal(f.posts[0].body.itemVersion,2);assert.equal(f.posts[0].headers,undefined);
 await f.ui.action('policy');await f.submit({currency:'KES',largeCash:'1000000',structuringTotal:'',structuringDays:'',structuringCount:'',velocityDebits:'20',unusualMultiplier:'',unusualLookbackDays:'',unusualMinHistory:'',reason:'Board-approved thresholds'});
 const body=f.posts[1].body;assert.equal(body.largeCash,'1000000');assert.equal(body.velocityDebits,20);assert.equal(body.structuringTotal,null);assert.equal(body.unusualMultiplier,null);
 await f.ui.action('hit','h1');assert.equal(f.title,'Match evidence');assert.match(f.html,/Viktor Petrov; V\. Petrov/);
});
test('the upload reads the chosen CSV and rejects oversized files',async()=>{
 const f=fixture();await f.ui.render(f.host,()=>true);await f.ui.action('upload');
 const csv='entry_ref,entity_type,name,aliases,birth_date,country,program\nUN-1,INDIVIDUAL,Viktor Petrov,,,,\n';
 await f.submit({name:'UN_SANCTIONS',category:'SANCTIONS',source:'UN 2026-11-01',threshold:'0.88',file:{size:csv.length,text:async()=>csv}});
 assert.equal(f.posts[0].path,'/admin/tenants/bank/compliance/screening/lists');assert.equal(f.posts[0].body.csv,csv);
 await assert.rejects(f.submit({name:'X',category:'PEP',source:'s',threshold:'0.9',file:{size:7000000,text:async()=>''}}),/6 MB/);
});
