import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

/** Screening lists, possible matches, transaction monitoring rules and alerts. */
export function createScreeningUi({api,form,field,refresh}){
 let bankId='',banks=[],bank,lists=[],hits={items:[],total:0},policies=[],alerts={items:[],total:0},current=()=>false;
 const keys=new Map();
 // The same request body always reuses its idempotency key, so a retried submit cannot create a second request.
 const key=body=>{const k=JSON.stringify(body);if(!keys.has(k))keys.set(k,crypto.randomUUID());return keys.get(k);};
 const submit=(path,body)=>api(path,{method:'POST',headers:{'Idempotency-Key':key({path,body})},body});
 const button=(label,action,id='')=>'<button type="button" class="button secondary" data-action="screening-'+action+'" data-id="'+e(id)+'">'+e(t(label))+'</button>';
 const table=(heads,rows)=>'<div class="table-wrap"><table><thead><tr>'+heads.map(h=>'<th>'+e(t(h))+'</th>').join('')+'</tr></thead><tbody>'+(rows.length?rows.join(''):'<tr><td colspan="'+heads.length+'" class="empty">'+e(t('Nothing to show.'))+'</td></tr>')+'</tbody></table></div>';
 const cells=(values,actions='')=>'<tr>'+values.map(v=>'<td>'+e(v??'')+'</td>').join('')+(actions===null?'':'<td class="actions">'+actions+'</td>')+'</tr>';
 const base=()=>'/admin/tenants/'+bankId+'/compliance';
 async function render(host,isCurrent){
  current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!current())return;banks=directory.items;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>'+e(t('Screening & monitoring'))+'</h1><p>'+e(t('Register a bank first.'))+'</p>';return;}
  [bank,lists,hits,policies,alerts]=await Promise.all([api('/admin/tenants/'+bankId),api(base()+'/screening/lists'),api(base()+'/screening/hits?status=OPEN&limit=50'),api(base()+'/monitoring/policies'),api(base()+'/monitoring/alerts?status=OPEN&limit=50')]);
  if(!current())return;
  host.innerHTML='<div class="heading"><div><h1>'+e(t('Screening & monitoring'))+'</h1><p>'+e(bank.displayName)+'</p></div><div class="actions">'+button('Choose bank','choose')+button('Refresh','refresh')+'</div></div>'+
   '<section class="card"><div class="panel-title"><h2>'+e(t('Possible matches'))+' ('+e(hits.total)+')</h2></div>'+
   '<p>'+e(t('An open match blocks the customer, and any organization they act for, until it is escalated to a case or its dismissal is approved.'))+'</p>'+
   table(['Customer','Customer name','Listed name','List / entry','Score','Found by',''],hits.items.map(h=>cells([h.customer_number,h.customer_name,h.matched_name,h.list_name+' / '+h.entry_ref,Number(h.score).toFixed(3),t(h.trigger==='LIST'?'List update':'Customer change')],
    button('Evidence','hit',h.id)+button('Escalate','escalate-hit',h.id+':'+h.version)+button('Request dismissal','dismiss-hit',h.id+':'+h.version))))+'</section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Monitoring alerts'))+' ('+e(alerts.total)+')</h2></div>'+
   '<p>'+e(t('Alerts are for investigation and never block the customer, so the customer is not alerted. Escalating opens a transaction review case, which does restrict.'))+'</p>'+
   table(['Rule','Account','Customer','Business date','Amount',''],alerts.items.map(a=>cells([t(a.rule),a.account_number,a.customer_number,a.business_date,a.currency+' '+a.amount],
    button('Evidence','alert',a.id)+button('Escalate','escalate-alert',a.id+':'+a.version)+button('Request dismissal','dismiss-alert',a.id+':'+a.version))))+'</section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Screening lists'))+'</h2>'+button('Upload list version','upload')+'</div>'+
   table(['Name','Category','Status','Entries','Threshold','Uploaded','Re-screen',''],lists.map(l=>cells([l.name,t(l.category),t(l.status),l.entry_count,l.threshold,String(l.created_at||'').slice(0,10),l.run_status?t(l.run_status)+' · '+l.run_screened+' '+t('screened')+' · '+l.run_hits+' '+t('matches'):''],
    l.status==='DRAFT'?button('Request activation','activate',l.id):'')))+
   '<p>'+e(t('CSV columns: entry_ref, entity_type (INDIVIDUAL or ENTITY), name, aliases separated by semicolons, birth_date (YYYY-MM-DD), country, program. A draft has no effect until an independent reviewer approves its activation.'))+'</p></section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Monitoring rules'))+'</h2>'+button('Change rules','policy')+'</div>'+
   table(['Currency','Rules','Updated'],policies.map(p=>cells([p.currency,Object.entries(p.summary).filter(([k])=>!['currency','reason'].includes(k)).map(([,v])=>v).join(' · '),String(p.updatedAt||'').slice(0,10)],null)))+'</section>';
 }
 const split=id=>{const [item,version]=String(id).split(':');return {item,version:Number(version)};};
 const decision=()=>field('evidenceReference','Evidence reference','',{max:160,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})+field('reason','Reason','',{full:true,max:500});
 const evidence=pairs=>'<dl class="field full">'+pairs.map(([k,v])=>'<dt>'+e(t(k))+'</dt><dd>'+e(v??'—')+'</dd>').join('')+'</dl>';
 async function action(name,id){
  if(!current())return;
  if(name==='refresh'){await refresh();return;}
  if(name==='choose'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;},'Open');return;}
  if(name==='upload'){
   form('Upload list version',field('name','List name','',{max:40,pattern:'[A-Z0-9][A-Z0-9_-]{0,39}',help:t('For example UN_SANCTIONS. A new version of the same name replaces the active one once approved.')})+
    field('category','Category','SANCTIONS',{choices:['SANCTIONS','PEP','INTERNAL'].map(c=>({value:c,label:t(c)}))})+field('source','Source and date of the list','',{full:true,max:200})+
    field('threshold','Match threshold (0.70–1.00)','0.88',{max:4,pattern:'(0\\.[7-9][0-9]?|1(\\.0{1,2})?)'})+'<label class="field full">'+e(t('List CSV'))+'<input type="file" name="file" accept=".csv,text/csv" required></label>',
    async v=>{if(!v.file?.size||v.file.size>6000000)throw Error(t('Choose a UTF-8 CSV file no larger than 6 MB.'));const csv=await v.file.text();
     return api(base()+'/screening/lists',{method:'POST',body:{name:v.name,category:v.category,source:v.source,threshold:v.threshold,csv}});},'Upload draft');return;
  }
  if(name==='activate'){
   const l=lists.find(x=>x.id===id);if(!l)return;
   form('Request activation',evidence([['List',l.name],['Entries',l.entry_count],['Threshold',l.threshold],['SHA-256',l.sha256]])+field('reason','Reason','',{full:true,max:500}),
    async v=>submit(base()+'/screening/activations',{expectedVersion:bank.version,listId:id,sha256:l.sha256,reason:v.reason}),'Submit for approval');return;
  }
  if(name==='hit'||name==='alert'){
   const path=name==='hit'?'/screening/hits/':'/monitoring/alerts/';const detail=await api(base()+path+id);if(!current())return;
   const pairs=name==='hit'?(h=>[['Customer',h.customer_number+' · '+h.customer_name],['Listed names',(h.entry_names||[]).join('; ')],['Entry type',h.entity_type],['Listed birth date',h.entry_birth_date],['Listed country',h.entry_country],['Program',h.entry_program],['Score',h.score],['Status',h.status]])(detail.hit)
    :(a=>[['Rule',t(a.rule)],['Account',a.account_number],['Customer',a.customer_number],['Amount',a.currency+' '+a.amount],['Details',Object.entries(a.details||{}).map(([k,v])=>k+': '+v).join(', ')],['Recent movements',(detail.movements||[]).slice(0,10).map(m=>m.business_date+' '+m.direction+' '+m.channel+' '+m.amount).join('\n')]])(detail.alert);
   form(name==='hit'?'Match evidence':'Alert evidence',evidence(pairs),async()=>{},'Close');return;
  }
  if(name==='escalate-hit'||name==='escalate-alert'){
   const {item,version}=split(id);const path=name==='escalate-hit'?'/screening/hits/':'/monitoring/alerts/';
   form('Escalate to a compliance case',decision(),async v=>api(base()+path+item+'/escalate',{method:'POST',body:{expectedVersion:bank.version,itemVersion:version,evidenceReference:v.evidenceReference,reason:v.reason}}),'Escalate');return;
  }
  if(name==='dismiss-hit'||name==='dismiss-alert'){
   const {item,version}=split(id);const type=name==='dismiss-hit'?'SCREENING_HIT':'MONITORING_ALERT';
   form('Request dismissal as a false positive',decision(),async v=>submit(base()+'/dismissals',{expectedVersion:bank.version,type,itemId:item,itemVersion:version,evidenceReference:v.evidenceReference,reason:v.reason}),'Submit for approval');return;
  }
  if(name==='policy'){
   const opt={required:false,max:25};
   form('Monitoring rules',field('currency','Currency','',{max:3,pattern:'[A-Z]{3}'})+
    field('largeCash','Large cash: single cash movement at or above','',{...opt,pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+
    field('structuringTotal','Structuring: deposits below and totalling at least','',{...opt,pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('structuringDays','Structuring: within days','',{...opt,type:'number'})+field('structuringCount','Structuring: at least this many deposits','',{...opt,type:'number'})+
    field('velocityDebits','Velocity: debits on one account in a day','',{...opt,type:'number'})+
    field('unusualMultiplier','Unusual amount: times the account average','',{...opt,pattern:'[0-9]{1,3}(\\.[0-9]{1,2})?'})+field('unusualLookbackDays','Unusual amount: average over days','',{...opt,type:'number'})+field('unusualMinHistory','Unusual amount: minimum earlier movements','',{...opt,type:'number'})+
    field('reason','Reason','',{full:true,max:500}),
    async v=>{const n=x=>x===''||x==null?null:Number(x),s=x=>x===''||x==null?null:String(x);
     return submit(base()+'/monitoring/policies',{expectedVersion:bank.version,currency:v.currency,largeCash:s(v.largeCash),structuringTotal:s(v.structuringTotal),structuringDays:n(v.structuringDays),structuringCount:n(v.structuringCount),velocityDebits:n(v.velocityDebits),unusualMultiplier:s(v.unusualMultiplier),unusualLookbackDays:n(v.unusualLookbackDays),unusualMinHistory:n(v.unusualMinHistory),reason:v.reason});},'Submit for approval');
  }
 }
 return {render,action,selectBank:x=>{bankId=x;}};
}
