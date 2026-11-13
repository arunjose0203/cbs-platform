import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

const EVENTS={CREDIT:'Credit',DEBIT:'Debit',TD_MATURITY:'Term-deposit maturity',LOAN_DUE:'Loan instalment due',LOAN_ARREARS:'Loan arrears',VERIFICATION:'Verification code'};
const LANGUAGES={en:'English',fr:'Français',sw:'Kiswahili'};
const title=s=>s.charAt(0)+s.slice(1).toLowerCase();

/**
 * Customer alerts: delivery history with provider receipts, retry of failed alerts, approved contact
 * preferences and their one-time-code verification, bank message templates (maker-checker) and opt-outs.
 */
export function createNotificationUi({api,form,field,refresh}){
 let bankId='',banks=[],data,templates=[],optOuts=[],status='',event='',offset=0,current=()=>false;
 const button=(label,action,id='',disabled=false)=>'<button type="button" class="button secondary" data-action="notification-'+action+'" data-id="'+e(id)+'" '+(disabled?'disabled':'')+'>'+e(t(label))+'</button>';
 const yesNo=(name,label,on)=>field(name,label,on?'yes':'no',{choices:[{value:'yes',label:t('Yes')},{value:'no',label:t('No')}]});
 async function render(host,isCurrent){
  current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!current())return;banks=directory.items;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>'+e(t('Customer alerts'))+'</h1><p>'+e(t('Register a bank first.'))+'</p>';return;}
  const bank=banks.find(b=>b.id===bankId)||{};const base='/admin/tenants/'+bankId+'/notifications';
  const list=x=>Array.isArray(x)?x:[];
  [data,templates,optOuts]=await Promise.all([api(base+'?'+new URLSearchParams({...(status?{status}:{}),...(event?{event}:{}),limit:25,offset})),api(base+'/templates').then(list,()=>[]),api(base+'/opt-outs').then(list,()=>[])]);if(!current())return;
  host.innerHTML='<div class="heading"><div><h1>'+e(t('Customer alerts'))+'</h1><p>'+e(bank.displayName||'')+'</p></div><div class="actions">'+button('Choose bank','choose')+button('Alert preference','preference')+button('Verify destination','verify')+button('Record opt-out','opt-out')+button('Refresh','refresh')+'</div></div>'+
   (data.providerConfigured?'':'<section class="card"><p><strong>'+e(t('No SMS or email provider is configured.'))+'</strong> '+e(t('Alerts are queued and will be sent once a provider is connected.'))+'</p></section>')+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Alert log'))+'</h2><span>'+e(data.total)+'</span></div><div class="actions">'+['','PENDING','SENT','FAILED','SUPPRESSED'].map(s=>button(s?title(s):'All','filter',s,s===status)).join('')+'</div>'+
   '<div class="actions">'+['',...Object.keys(EVENTS)].map(v=>button(v?EVENTS[v]:'All events','event',v,v===event)).join('')+'</div>'+
   '<div class="table-wrap"><table><thead><tr>'+['Created','Event','Channel','Destination','Message','Status','Delivery receipt',''].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+
   data.items.map(n=>'<tr><td>'+e(n.createdAt)+'</td><td>'+e(t(EVENTS[n.event]||n.event))+'</td><td>'+e(n.channel)+'</td><td>'+e(n.destination)+'</td><td>'+e(n.body)+'</td><td>'+e(t(title(n.status)))+(n.failureCode?'<small>'+e(t(errorMessage(n.failureCode)))+'</small>':'')+'<small>'+e(n.attempts)+' '+e(t('attempts'))+'</small></td><td>'+(n.receiptStatus?e(t(title(n.receiptStatus)))+'<small>'+e(n.receiptAt||'')+'</small>':'—')+'</td><td>'+(n.status==='FAILED'&&n.event!=='VERIFICATION'?button('Send again','retry',n.id):'')+'</td></tr>').join('')+
   (data.items.length?'':'<tr><td colspan="8" class="empty">'+e(t('No alerts yet.'))+'</td></tr>')+'</tbody></table></div><div class="pagination">'+button('Previous','previous','',offset===0)+button('Next','next','',offset+25>=data.total)+'</div>'+
   '<p>'+e(t('Alerts never include balances or full account numbers. Destinations are masked here; changes to where alerts go require independent approval.'))+'</p></section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Message templates'))+'</h2></div><div class="table-wrap"><table><thead><tr>'+['Event','Language','Channel','Wording','Source',''].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+
   templates.map((x,i)=>'<tr><td>'+e(t(EVENTS[x.event]||x.event))+'</td><td>'+e(LANGUAGES[x.language]||x.language)+'</td><td>'+e(x.channel)+'</td><td>'+(x.subject?'<strong>'+e(x.subject)+'</strong><br>':'')+e(x.body)+'</td><td>'+e(t(x.approved?'Approved by the bank':'Built-in'))+'</td><td>'+button('Change wording','template',String(i))+'</td></tr>').join('')+
   '</tbody></table></div><p>'+e(t('Templates may use only the placeholders of their event, must name the bank, and SMS wording must fit two messages. Another administrator approves each change.'))+'</p></section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Opt-outs'))+'</h2><span>'+e(optOuts.length)+'</span></div><div class="table-wrap"><table><thead><tr>'+['Channel','Destination','Source','Reference','Recorded',''].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+
   optOuts.map(o=>'<tr><td>'+e(o.channel)+'</td><td>'+e(o.destination)+'</td><td>'+e(t(o.source==='PROVIDER'?'Customer reply to the provider':'Recorded at the bank'))+'</td><td>'+e(o.reference)+'</td><td>'+e(o.recordedAt)+'</td><td>'+button('Resume messages','resume',o.channel)+'</td></tr>').join('')+
   (optOuts.length?'':'<tr><td colspan="6" class="empty">'+e(t('No opt-outs.'))+'</td></tr>')+'</tbody></table></div></section>';
 }
 async function chooseCustomer(base,then){
  const customers=await api(base+'/customers?limit=100&offset=0');if(!current())return;
  if(!customers.items.length){form('Alert preference','<p class="field full">'+e(t('Onboard a customer first.'))+'</p>',async()=>{},'Done');return;}
  form('Choose customer',field('customerId','Customer',customers.items[0].id,{full:true,choices:customers.items.map(c=>({value:c.id,label:(c.customerNumber||'')+' · '+(c.fullName||c.name||'')}))}),async v=>{await then(v.customerId);return {keepOpen:true};},'Continue');
 }
 async function action(name,id){
  if(!current())return;const base='/admin/tenants/'+bankId;
  if(name==='refresh'){await refresh();return;}
  if(name==='filter'){status=id;offset=0;await refresh();return;}
  if(name==='event'){event=id;offset=0;await refresh();return;}
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
  if(name==='retry'){await api(base+'/notifications/'+id+'/retry',{method:'POST'});await refresh();return;}
  if(name==='choose'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;offset=0;},'Open');return;}
  if(name==='preference'){const bank=await api(base);if(!current())return;await chooseCustomer(base,customerId=>edit(customerId,bank.version));return;}
  if(name==='verify'){await chooseCustomer(base,verify);return;}
  if(name==='template'){
   const x=templates[Number(id)];if(!x)return;const bank=await api(base);if(!current())return;let key=crypto.randomUUID(),previous='';
   form('Change wording','<p class="field full">'+e(t(EVENTS[x.event]||x.event))+' · '+e(LANGUAGES[x.language]||x.language)+' · '+e(x.channel)+'</p>'+
    (x.channel==='EMAIL'?field('subject','Subject',x.subject||'',{full:true,max:200}):'')+field('body','Wording',x.body,{full:true,max:x.channel==='SMS'?306:1000})+field('reason','Reason and evidence','',{full:true,max:500}),
    v=>{const body={expectedVersion:bank.version,event:x.event,language:x.language,channel:x.channel,subject:x.channel==='EMAIL'?v.subject:null,body:v.body,reason:v.reason};
     const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
     return api(base+'/notifications/templates',{method:'POST',headers:{'Idempotency-Key':key},body});},'Submit for approval');
   return;
  }
  if(name==='opt-out'){
   form('Record opt-out',field('channel','Channel','SMS',{choices:[{value:'SMS',label:'SMS'},{value:'EMAIL',label:'Email'}]})+field('destination','Phone (+254…) or email','',{full:true,max:254})+field('reference','Reference (form or call record)','',{full:true,max:120}),
    v=>api(base+'/notifications/opt-outs',{method:'POST',body:{channel:v.channel,destination:v.destination,reference:v.reference}}),'Record opt-out');
   return;
  }
  if(name==='resume'){
   form('Resume messages','<p class="field full">'+e(t('Re-enter the full destination and the reference of the customer’s renewed consent.'))+'</p>'+field('destination','Phone (+254…) or email','',{full:true,max:254})+field('reference','Renewed consent reference','',{full:true,max:120}),
    v=>api(base+'/notifications/opt-outs/remove',{method:'POST',body:{channel:id,destination:v.destination,reference:v.reference}}),'Resume messages');
   return;
  }
  async function verify(customerId){
   const existing=await api(base+'/notifications/preferences/'+customerId);if(!current())return;
   if(!existing.configured||existing.verified){form('Verify destination','<p class="field full">'+e(t(existing.configured?'This destination is already verified.':'No alert preference yet.'))+'</p>',async()=>{},'Done');return;}
   form('Verify destination','<p class="field full">'+e(t('Ask the customer for the six-digit code sent to'))+' '+e(existing.preference.destination||'')+'.</p>'+field('code','Verification code','',{max:6,pattern:'[0-9]{6}'})+
    '<p class="field full">'+button('Send code again','resend',customerId)+'</p>',
    v=>api(base+'/notifications/preferences/'+customerId+'/verify',{method:'POST',body:{code:v.code}}),'Verify');
  }
  if(name==='resend'){await api(base+'/notifications/preferences/'+id+'/resend',{method:'POST'});return;}
  async function edit(customerId,version){
   const existing=await api(base+'/notifications/preferences/'+customerId);if(!current())return;const p=existing.preference||{};const events=new Set(p.events||['CREDIT','DEBIT','TD_MATURITY','LOAN_DUE','LOAN_ARREARS']);
   let key=crypto.randomUUID(),previous='';
   form('Alert preference',(existing.configured?'<p class="field full">'+e(t('Current'))+': '+e(p.channel)+' '+e(p.destination||'')+' · '+e(t(existing.verified?'Verified':'Not verified'))+'</p>':'<p class="field full">'+e(t('No alert preference yet.'))+'</p>')+
    field('channel','Channel',p.channel||'SMS',{choices:[{value:'SMS',label:'SMS'},{value:'EMAIL',label:'Email'},{value:'NONE',label:t('No alerts')}]})+
    field('destination','Phone (+254…) or email','',{full:true,max:254})+
    field('language','Language',p.language||'en',{choices:Object.entries(LANGUAGES).map(([value,label])=>({value,label}))})+
    yesNo('credit','Credit alerts',events.has('CREDIT'))+yesNo('debit','Debit alerts',events.has('DEBIT'))+
    yesNo('maturity','Term-deposit maturity notices',events.has('TD_MATURITY'))+yesNo('loanDue','Loan instalment reminders',events.has('LOAN_DUE'))+yesNo('arrears','Loan arrears notices',events.has('LOAN_ARREARS'))+
    field('consentReference','Customer consent reference',p.consentReference||'',{full:true,max:120})+field('reason','Reason and evidence','',{full:true,max:500})+
    '<p class="field full">'+e(t('Re-enter the full destination; it is verified by the approver. Another administrator must approve before alerts change. A new destination receives a one-time code; alerts start once the customer confirms it.'))+'</p>',
    v=>{const chosen=[['credit','CREDIT'],['debit','DEBIT'],['maturity','TD_MATURITY'],['loanDue','LOAN_DUE'],['arrears','LOAN_ARREARS']].filter(([k])=>v[k]==='yes').map(([,x])=>x);
     const body={expectedVersion:version,customerId,channel:v.channel,destination:v.channel==='NONE'?null:v.destination,language:v.language,events:chosen,consentReference:v.consentReference,reason:v.reason};
     const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
     return api(base+'/notifications/preferences',{method:'POST',headers:{'Idempotency-Key':key},body});},'Submit for approval');
  }
 }
 return {render,action,selectBank:id=>{bankId=id;offset=0;}};
}
