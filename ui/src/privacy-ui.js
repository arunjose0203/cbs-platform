import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

export function createPrivacyUi({api,form,field,refresh}){
  let tab='requests',statusFilter='',overdueFilter=false;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="privacy-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;

  async function render(host,bank,bankId,generation,current){
    const target=bankId;
    let content='';

    if(tab==='requests'){
      const query=`/admin/tenants/${target}/privacy/requests?overdue=${overdueFilter}${statusFilter?`&status=${statusFilter}`:''}`;
      const requests=await api(query);if(!current())return;
      const rows=requests.map(r=>`<tr>
        <td><strong>${e(r.reference)}</strong><small>${e(r.customerNumber)}</small></td>
        <td><span class="badge">${e(r.type)}</span></td>
        <td>${e(r.receivedOn)}</td>
        <td>${e(r.dueOn)} ${r.overdue?`<small class="danger">${e(t('OVERDUE'))}</small>`:''}</td>
        <td><span class="badge ${r.status==='OPEN'?'warning':(r.status==='COMPLETED'?'success':'danger')}">${e(r.status)}</span></td>
        <td>
          <div class="actions">
            ${(r.type==='ACCESS'||r.type==='PORTABILITY')&&r.status==='OPEN'?button('Export report','report',r.id):''}
            ${r.status==='OPEN'?button('Close','close',r.id):''}
          </div>
        </td>
      </tr>`).join('');

      content=`<div class="toolbar">
        <label class="field">${e(t('Status'))}
          <select id="privacy-status-filter">
            <option value="">${e(t('All'))}</option>
            <option value="OPEN" ${statusFilter==='OPEN'?'selected':''}>${e(t('Open'))}</option>
            <option value="COMPLETED" ${statusFilter==='COMPLETED'?'selected':''}>${e(t('Completed'))}</option>
            <option value="REJECTED" ${statusFilter==='REJECTED'?'selected':''}>${e(t('Rejected'))}</option>
          </select>
        </label>
        <label class="checkbox-field"><input type="checkbox" id="privacy-overdue-filter" ${overdueFilter?'checked':''}> ${e(t('Overdue only'))}</label>
        ${button('Filter','filter')}
        ${button('Register request','new-request','',bank.status!=='ACTIVE')}
      </div>
      <section class="card"><div class="table-wrap"><table>
        <thead><tr><th>${e(t('Request'))}</th><th>${e(t('Type'))}</th><th>${e(t('Received'))}</th><th>${e(t('Due'))}</th><th>${e(t('Status'))}</th><th></th></tr></thead>
        <tbody>${rows||`<tr><td colspan="6" class="empty">${e(t('No data-subject requests recorded.'))}</td></tr>`}</tbody>
      </table></div></section>`;
    } else if(tab==='holds'){
      const holds=await api(`/admin/tenants/${target}/privacy/legal-holds`);if(!current())return;
      const rows=holds.map(h=>`<tr>
        <td><strong>${e(h.customerNumber)}</strong></td>
        <td>${e(h.reference)}</td>
        <td>${e(h.reason)}</td>
        <td>${e(h.updatedAt.slice(0,10))}</td>
        <td><div class="actions">${button('Release hold','release-hold',h.customerId,bank.status!=='ACTIVE')}</div></td>
      </tr>`).join('');

      content=`<div class="toolbar">
        ${button('Place legal hold','new-hold','',bank.status!=='ACTIVE')}
      </div>
      <section class="card"><div class="table-wrap"><table>
        <thead><tr><th>${e(t('Customer'))}</th><th>${e(t('Reference'))}</th><th>${e(t('Reason'))}</th><th>${e(t('Updated'))}</th><th></th></tr></thead>
        <tbody>${rows||`<tr><td colspan="5" class="empty">${e(t('No active legal holds.'))}</td></tr>`}</tbody>
      </table></div></section>`;
    } else if(tab==='retention'){
      const [matrix,candidates]=await Promise.all([
        api(`/admin/tenants/${target}/privacy/retention`),
        api(`/admin/tenants/${target}/privacy/retention/candidates`)
      ]);if(!current())return;

      const matrixRows=matrix.map(m=>`<tr>
        <td><strong>${e(m.category)}</strong></td>
        <td>${e(m.records)}</td>
        <td>${e(m.years)} ${e(t('years'))}</td>
        <td>${e(m.starts)}</td>
        <td>${e(m.basis)}</td>
        <td>${e(m.endAction)}</td>
      </tr>`).join('');

      const candidateRows=candidates.map(c=>`<tr>
        <td><strong>${e(c.customerNumber)}</strong></td>
        <td>${e(c.relationshipEnded)}</td>
        <td>${e(c.retainUntil)}</td>
      </tr>`).join('');

      content=`<section class="card">
        <div class="panel-title"><h2>${e(t('Statutory Retention Matrix'))}</h2></div>
        <div class="table-wrap"><table>
          <thead><tr><th>${e(t('Category'))}</th><th>${e(t('Records'))}</th><th>${e(t('Period'))}</th><th>${e(t('Trigger'))}</th><th>${e(t('Legal basis'))}</th><th>${e(t('End action'))}</th></tr></thead>
          <tbody>${matrixRows}</tbody>
        </table></div>
      </section>
      <section class="card">
        <div class="panel-title"><h2>${e(t('Archival Review Candidates'))}</h2><span>${candidates.length}</span></div>
        <div class="table-wrap"><table>
          <thead><tr><th>${e(t('Customer'))}</th><th>${e(t('Ended'))}</th><th>${e(t('Retention expired'))}</th></tr></thead>
          <tbody>${candidateRows||`<tr><td colspan="3" class="empty">${e(t('No customers due for archival review.'))}</td></tr>`}</tbody>
        </table></div>
      </section>`;
    }

    host.innerHTML=`<div class="heading">
      <div>
        <p class="eyebrow">${e(t('Data Protection & Governance'))}</p>
        <h1>${e(t('Privacy & Data Protection'))}</h1>
        <p>${e(t('Manage legal holds, data-subject requests, statutory retention and audit view logs.'))}</p>
      </div>
    </div>
    <div class="tabs">
      <button type="button" class="tab ${tab==='requests'?'active':''}" data-privacy-tab="requests">${e(t('Subject requests'))}</button>
      <button type="button" class="tab ${tab==='holds'?'active':''}" data-privacy-tab="holds">${e(t('Legal holds'))}</button>
      <button type="button" class="tab ${tab==='retention'?'active':''}" data-privacy-tab="retention">${e(t('Retention matrix'))}</button>
    </div>
    ${content}`;

    host.querySelectorAll('[data-privacy-tab]').forEach(b=>{
      b.onclick=()=>{tab=b.dataset.privacyTab;refresh();};
    });

    host.querySelectorAll('[data-action^="privacy-"]').forEach(btn=>{
      const actionName=btn.dataset.action.replace('privacy-','');
      const actionId=btn.dataset.id;
      btn.onclick=()=>handleAction(actionName,actionId,bank,target);
    });
  }

  async function handleAction(name,id,bank,target){
    if(name==='filter'){
      statusFilter=document.querySelector('#privacy-status-filter')?.value||'';
      overdueFilter=document.querySelector('#privacy-overdue-filter')?.checked||false;
      refresh();
      return;
    }
    if(name==='report'){
      window.open(`/api/v1/admin/tenants/${target}/privacy/requests/${id}/report`,'_blank');
      return;
    }
    if(name==='close'){
      form('Close data-subject request',
        field('outcome','Outcome','COMPLETED',{choices:[['COMPLETED','Completed'],['REJECTED','Rejected']].map(([v,l])=>({value:v,label:t(l)}))})+
        field('code','Outcome code','FULFILLED',{max:40,pattern:'[A-Z][A-Z_]{1,39}'})+
        field('note','Closing note and justification','',{full:true,max:500}),async d=>{
          await api(`/admin/tenants/${target}/privacy/requests/${id}/close`,{method:'POST',body:{outcome:d.outcome,code:d.code.trim(),note:d.note.trim()}});
          await refresh();
        },'Close request');
      return;
    }
    if(name==='new-request'){
      const customers=await api(`/admin/tenants/${target}/customers?limit=100&offset=0`);
      const choices=customers.items.map(c=>({value:c.id,label:c.customerNumber+' · '+c.fullName}));
      const key=crypto.randomUUID();
      form('Register data-subject request',
        field('customerId','Customer',choices[0]?.value||'',{choices})+
        field('type','Request type','ACCESS',{choices:[['ACCESS','Access (Article 15)'],['RECTIFICATION','Rectification (Article 16)'],['ERASURE','Erasure (Article 17)'],['OBJECTION','Objection (Article 21)'],['PORTABILITY','Portability (Article 20)']].map(([v,l])=>({value:v,label:t(l)}))})+
        field('receivedOn','Received date',new Date().toISOString().slice(0,10),{type:'date'})+
        field('reference','Request reference','',{max:120})+
        field('details','Details of request','',{full:true,max:500,required:false}),async d=>{
          await api(`/admin/tenants/${target}/privacy/requests`,{method:'POST',headers:{'Idempotency-Key':key},body:{customerId:d.customerId,type:d.type,receivedOn:d.receivedOn,reference:d.reference.trim(),details:d.details.trim()}});
          await refresh();
        },'Register request');
      return;
    }
    if(name==='new-hold'){
      const customers=await api(`/admin/tenants/${target}/customers?limit=100&offset=0`);
      const choices=customers.items.map(c=>({value:c.id,label:c.customerNumber+' · '+c.fullName}));
      const key=crypto.randomUUID();
      form('Place legal hold (Maker-Checker)',
        field('customerId','Customer',choices[0]?.value||'',{choices})+
        field('reference','Legal reference (court order / summons)','',{max:120})+
        field('reason','Substantive justification','',{full:true,max:500}),async d=>{
          await api(`/admin/tenants/${target}/privacy/legal-holds`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:bank.version,customerId:d.customerId,active:true,reference:d.reference.trim(),reason:d.reason.trim()}});
          await refresh();
        },'Submit for approval');
      return;
    }
    if(name==='release-hold'){
      const key=crypto.randomUUID();
      form('Release legal hold (Maker-Checker)',
        field('reference','Release order reference','',{max:120})+
        field('reason','Justification for release','',{full:true,max:500}),async d=>{
          await api(`/admin/tenants/${target}/privacy/legal-holds`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:bank.version,customerId:id,active:false,reference:d.reference.trim(),reason:d.reason.trim()}});
          await refresh();
        },'Submit for approval');
      return;
    }
  }

  return {render};
}
