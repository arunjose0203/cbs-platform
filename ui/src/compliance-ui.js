import {escapeHtml as e} from './model.js';

export function createComplianceUi({api,form,field,refresh}) {
  let bankId='',bank,data,banks=[],offset=0,status='ALL',current=()=>false;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="compliance-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(label)}</button>`;
  async function render(host,isCurrent) {
    current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!isCurrent())return;banks=directory.items;
    if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
    if(!bankId){host.innerHTML='<h1>Compliance cases</h1><p>Register a bank first.</p>';return;}
    const selected=await api('/admin/tenants/'+bankId);
    const result=await api(`/admin/tenants/${bankId}/compliance?status=${status}&limit=25&offset=${offset}`);if(!isCurrent())return;bank=selected;data=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">Customer verification & investigations</p><h1>Compliance cases</h1><p>${e(bank.displayName)} · ${e(status==='ALL'?'All cases':status.replaceAll('_',' '))}</p></div><div class="actions">${button('Choose bank / filter','filter')}${button('Refresh','refresh')}${button('Open case','open','',bank.status!=='ACTIVE')}</div></div>
      <section class="card compliance-summary"><h2>Evidence before clearance</h2><p>Opening a case immediately restricts customer financial activity. Record evidence references, investigate, then request an independent decision in the Approval Inbox. All cases must be cleared; expired reviews and customer profile changes require a new review.</p><p>Manual review workspace. Automated sanctions, PEP and transaction screening are not connected.</p></section>
      <section class="card section-gap"><div class="table-wrap"><table><thead><tr><th>Customer / case</th><th>Category</th><th>Decision</th><th>Risk</th><th>Review due</th><th>Assignee / due</th><th>Financial activity</th><th></th></tr></thead><tbody>${data.items.map(c=>`<tr><td><strong>${e(c.customer_number)}</strong><small class="mono">${e(c.id)}</small></td><td>${e(c.category.replaceAll('_',' '))}</td><td>${e(c.status)}</td><td>${e(c.risk||'Not assessed')}</td><td>${e(c.review_due||'—')}</td><td>${e(c.assignee||'Unassigned')}<br><small>${e(c.due_date||'—')}${c.overdue?' <span class="tag alert">OVERDUE</span>':''}</small></td><td><span class="status ${c.restricted?'rejected':'active'}">${c.restricted?'Restricted':'Case cleared'}</span></td><td>${button('Review case','detail',c.id)}</td></tr>`).join('')||'<tr><td colspan="8" class="empty">No cases match this filter. Absence of a case is not evidence of completed screening.</td></tr>'}</tbody></table></div><div class="pagination"><span>${e(data.total)} cases</span>${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=data.total)}</div></section>`;
  }
  async function action(name,id) {
    if(!current())return;const base='/admin/tenants/'+bankId+'/compliance';
    if(name==='refresh'){await refresh();return;}
    if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    if(name==='filter'){
      form('Choose bank and cases',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))})+field('status','Case filter',status,{choices:['ALL','OPEN','CLEARED','RESTRICTED','REVIEW_DUE','OVERDUE','UNASSIGNED'].map(value=>({value,label:value.replaceAll('_',' ')}))}),async v=>{bankId=v.bank;status=v.status;offset=0;},'Apply');return;
    }
    if(name==='open'){
      const customers=await api('/admin/tenants/'+bankId+'/customers?limit=100&offset=0');if(!current())return;
      if(!customers.items.length)throw Error('Create a customer profile first.');const version=data.version,key=crypto.randomUUID();
      form('Open customer review case','<p class="field full">This immediately restricts new customer financial activity and pending deliveries that have not posted. Opening a case does not declare a sanctions match or criminal finding. Use evidence references, not copied identity documents.</p>'+field('customerId','Customer',customers.items[0].id,{choices:customers.items.map(c=>({value:c.id,label:c.customerNumber+' · '+c.fullName})),help:'First 100 customer profiles in this bank.'})+field('category','Category','KYC_REVIEW',{choices:['KYC_REVIEW','SCREENING_ALERT','TRANSACTION_REVIEW'].map(value=>({value,label:value.replaceAll('_',' ')}))})+field('evidenceReference','Evidence reference','',{full:true,max:160,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})+field('reason','Reason for investigation','',{full:true,max:500}),v=>api(base,{method:'POST',headers:{'Idempotency-Key':key},body:{...v,expectedVersion:version}}),'Open case and restrict');return;
    }
    const detail=await api(base+'/'+id);if(!current())return;const c=detail.case;
    if(name==='detail'){
      const events=detail.events.map(v=>`<tr><td>${e(v.at)}</td><td>${e(v.action)}<br>${e(v.subject)}</td><td>${e(v.evidence.reason)}<br><small>${e(v.evidence.evidenceReference||v.evidence.identityEvidence||'')} ${e(v.evidence.screeningEvidence||'')}</small></td></tr>`).join('');
      form('Compliance case history',`<div class="field full compliance-detail"><p><strong>${e(c.customer_number)}</strong> · ${e(c.category)} · ${e(c.status)}</p><p>Customer version ${e(c.customer_version)} · Case version ${e(c.version)} · ${c.restricted?'Financial activity restricted':'This case is cleared; other cases may still restrict activity.'}</p><p>Assignee: ${e(c.assignee||'Unassigned')} · Due: ${e(c.due_date||'—')}${c.overdue?' <span class="tag alert">OVERDUE</span>':''}</p><div class="actions">${button('Add evidence','note',id)}${c.status==='OPEN'?button('Assign investigator','assign',id):''}${button('Export STR draft','str-draft',id)}${button('Propose clearance','clear',id)}${button('Confirm restriction','restrict',id)}</div><div class="table-wrap"><table><thead><tr><th>Recorded</th><th>Action / actor</th><th>Evidence</th></tr></thead><tbody>${events}</tbody></table></div><p>Latest 100 evidence events, newest first.</p></div>`,async()=>{},'Done');return;
    }
    const key=crypto.randomUUID();
    if(name==='assign'){
      form('Assign case investigator',field('assignee','Assignee staff username','',{max:255})+field('dueDate','SLA due date','',{type:'date',help:'Must be today or up to 366 days in future.'})+field('reason','Assignment note','',{full:true,max:500}),v=>api(base+'/'+id+'/assignment',{method:'POST',headers:{'Idempotency-Key':key},body:{...v,expectedVersion:detail.version,caseVersion:c.version}}),'Assign investigator');return;
    }
    if(name==='str-draft'){
      form('Export STR / SAR report draft',field('grounds','Grounds for suspicion','',{full:true,max:4000,help:'Neutral summary of suspicious patterns, alerts and screening hits.'})+field('reason','Export purpose','',{full:true,max:500}),async v=>{
        const res=await api(base+'/'+id+'/str-drafts',{method:'POST',body:{...v,expectedVersion:detail.version,caseVersion:c.version}});
        form('STR report draft exported',`<div class="field full"><p>Draft file: <strong>${e(res.fileName)}</strong></p><p>Document SHA-256: <code class="mono">${e(res.sha256)}</code></p><p>Recorded as an immutable STR_DRAFT event on the case history.</p><textarea class="field full mono" rows="10" readonly>${e(res.document)}</textarea></div>`,async()=>{},'Done');
      },'Generate draft');return;
    }
    if(name==='note'){
      form('Add case evidence',field('evidenceReference','Evidence reference','',{full:true,max:160,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})+field('reason','Investigation note','',{full:true,max:500}),v=>api(base+'/'+id+'/notes',{method:'POST',headers:{'Idempotency-Key':key},body:{...v,expectedVersion:detail.version,caseVersion:c.version}}),'Record evidence');return;
    }
    if(name==='clear'||name==='restrict'){
      const clearing=name==='clear';
      form(clearing?'Propose customer clearance':'Confirm customer restriction','<p class="field full">Another administrator, who did not open the case, must approve. Evidence must match the current customer profile. No other case is cleared by this decision.</p>'+field('risk','Assessed customer risk','',{choices:[{value:'',label:'Select assessed risk'},...['LOW','MEDIUM','HIGH'].map(value=>({value,label:value}))]})+field('identityEvidence','Identity verification reference','',{full:true,max:160,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})+field('screeningEvidence','Screening / investigation reference','',{full:true,max:160,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})+(clearing?field('reviewDue','Next review due','',{type:'date',help:'Required future date; bank policy determines the review interval.'}):'')+field('reason','Decision evidence','',{full:true,max:500}),v=>api(base+'/reviews',{method:'POST',headers:{'Idempotency-Key':key},body:{...v,expectedVersion:detail.version,caseId:id,caseVersion:c.version,customerVersion:c.customer_version,decision:clearing?'CLEARED':'RESTRICTED',reviewDue:clearing?v.reviewDue:null}}),'Submit for independent approval');
    }
  }
  return {render,action,selectBank:id=>{bankId=id;offset=0;}};
}
