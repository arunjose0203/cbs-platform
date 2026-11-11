import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

/** Customer alerts: delivery log, retry of failed alerts, and approved contact preferences. */
export function createNotificationUi({api,form,field,refresh}){
 let bankId='',banks=[],data,status='',offset=0,current=()=>false;
 const button=(label,action,id='',disabled=false)=>'<button type="button" class="button secondary" data-action="notification-'+action+'" data-id="'+e(id)+'" '+(disabled?'disabled':'')+'>'+e(t(label))+'</button>';
 async function render(host,isCurrent){
  current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!current())return;banks=directory.items;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>'+e(t('Customer alerts'))+'</h1><p>'+e(t('Register a bank first.'))+'</p>';return;}
  const bank=banks.find(b=>b.id===bankId)||{};
  data=await api('/admin/tenants/'+bankId+'/notifications?'+new URLSearchParams({...(status?{status}:{}),limit:25,offset}));if(!current())return;
  host.innerHTML='<div class="heading"><div><h1>'+e(t('Customer alerts'))+'</h1><p>'+e(bank.displayName||'')+'</p></div><div class="actions">'+button('Choose bank','choose')+button('Alert preference','preference')+button('Refresh','refresh')+'</div></div>'+
   (data.providerConfigured?'':'<section class="card"><p><strong>'+e(t('No SMS or email provider is configured.'))+'</strong> '+e(t('Alerts are queued and will be sent once a provider is connected.'))+'</p></section>')+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Alert log'))+'</h2><span>'+e(data.total)+'</span></div><div class="actions">'+['','PENDING','SENT','FAILED'].map(s=>button(s?t(s.charAt(0)+s.slice(1).toLowerCase()):t('All'),'filter',s,s===status)).join('')+'</div>'+
   '<div class="table-wrap"><table><thead><tr>'+['Created','Event','Channel','Destination','Message','Status',''].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+
   data.items.map(n=>'<tr><td>'+e(n.createdAt)+'</td><td>'+e(t(n.event==='CREDIT'?'Credit':'Debit'))+'</td><td>'+e(n.channel)+'</td><td>'+e(n.destination)+'</td><td>'+e(n.body)+'</td><td>'+e(t(n.status.charAt(0)+n.status.slice(1).toLowerCase()))+(n.failureCode?'<small>'+e(t(errorMessage(n.failureCode)))+'</small>':'')+'<small>'+e(n.attempts)+' '+e(t('attempts'))+'</small></td><td>'+(n.status==='FAILED'?button('Send again','retry',n.id):'')+'</td></tr>').join('')+
   (data.items.length?'':'<tr><td colspan="7" class="empty">'+e(t('No alerts yet.'))+'</td></tr>')+'</tbody></table></div><div class="pagination">'+button('Previous','previous','',offset===0)+button('Next','next','',offset+25>=data.total)+'</div>'+
   '<p>'+e(t('Alerts never include balances or full account numbers. Destinations are masked here; changes to where alerts go require independent approval.'))+'</p></section>';
 }
 async function action(name,id){
  if(!current())return;const base='/admin/tenants/'+bankId;
  if(name==='refresh'){await refresh();return;}
  if(name==='filter'){status=id;offset=0;await refresh();return;}
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
  if(name==='retry'){await api(base+'/notifications/'+id+'/retry',{method:'POST'});await refresh();return;}
  if(name==='choose'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;offset=0;},'Open');return;}
  if(name==='preference'){
   const [customers,bank]=await Promise.all([api(base+'/customers?limit=100&offset=0'),api(base)]);if(!current())return;
   if(!customers.items.length){form('Alert preference','<p class="field full">'+e(t('Onboard a customer first.'))+'</p>',async()=>{},'Done');return;}
   form('Choose customer',field('customerId','Customer',customers.items[0].id,{full:true,choices:customers.items.map(c=>({value:c.id,label:(c.customerNumber||'')+' · '+(c.fullName||c.name||'')}))}),async v=>{await edit(v.customerId,bank.version);return {keepOpen:true};},'Continue');
  }
  async function edit(customerId,version){
   const existing=await api(base+'/notifications/preferences/'+customerId);if(!current())return;const p=existing.preference||{};const events=new Set(p.events||['CREDIT','DEBIT']);
   let key=crypto.randomUUID(),previous='';
   form('Alert preference',(existing.configured?'<p class="field full">'+e(t('Current'))+': '+e(p.channel)+' '+e(p.destination||'')+'</p>':'<p class="field full">'+e(t('No alert preference yet.'))+'</p>')+
    field('channel','Channel',p.channel||'SMS',{choices:[{value:'SMS',label:'SMS'},{value:'EMAIL',label:'Email'},{value:'NONE',label:t('No alerts')}]})+
    field('destination','Phone (+254…) or email','',{full:true,max:254})+
    field('language','Language',p.language||'en',{choices:[{value:'en',label:'English'},{value:'fr',label:'Français'},{value:'sw',label:'Kiswahili'}]})+
    field('credit','Credit alerts',events.has('CREDIT')?'yes':'no',{choices:[{value:'yes',label:t('Yes')},{value:'no',label:t('No')}]})+
    field('debit','Debit alerts',events.has('DEBIT')?'yes':'no',{choices:[{value:'yes',label:t('Yes')},{value:'no',label:t('No')}]})+
    field('consentReference','Customer consent reference',p.consentReference||'',{full:true,max:120})+field('reason','Reason and evidence','',{full:true,max:500})+
    '<p class="field full">'+e(t('Re-enter the full destination; it is verified by the approver. Another administrator must approve before alerts change.'))+'</p>',
    v=>{const body={expectedVersion:version,customerId,channel:v.channel,destination:v.channel==='NONE'?null:v.destination,language:v.language,events:[v.credit==='yes'?'CREDIT':null,v.debit==='yes'?'DEBIT':null].filter(Boolean),consentReference:v.consentReference,reason:v.reason};
     const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
     return api(base+'/notifications/preferences',{method:'POST',headers:{'Idempotency-Key':key},body});},'Submit for approval');
  }
 }
 return {render,action,selectBank:id=>{bankId=id;offset=0;}};
}
