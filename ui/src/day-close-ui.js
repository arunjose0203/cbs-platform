import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

export function createDayCloseUi({api,form,field,refresh}){
  let bankId='',bank,data,current=()=>false,offset=0;
  const button=(label,action,id='',disabled=false)=>`<button class="button secondary" data-action="day-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  async function render(host,isCurrent){
    current=isCurrent;
    const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    let selected=banks.items.find(b=>b.id===bankId);if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!selected){host.innerHTML=`<h1>${e(t('Business Day Close'))}</h1><p>${e(t('No banks registered yet.'))}</p>`;return;}
    const result=await api(`/admin/tenants/${bankId}/business-day?limit=25&offset=${offset}`);if(!isCurrent())return;bank=selected;data=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Business Day Close'))}</h1><p>${e(t('Verify the day, obtain approval, and advance the bank’s business date.'))}</p></div>${button('Refresh','refresh')}</div>
    <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="day-bank">${banks.items.map(b=>`<option value="${e(b.id)}" ${b.id===bankId?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label></div>
    <section class="card"><div class="panel-title"><h2>${e(t('Close readiness'))}</h2>${result.storageReady?button('Request day close','close','',result.blockers.length>0):button('Prepare closing storage','prepare','',bank.status!=='ACTIVE')}</div>
    <dl class="metadata"><div><dt>${e(t('Business date'))}</dt><dd>${e(result.businessDate)}</dd></div><div><dt>${e(t('Next working day'))}</dt><dd>${e(result.nextBusinessDate)}</dd></div><div><dt>${e(t('Closed through'))}</dt><dd>${e(result.closedThrough||'—')}</dd></div></dl>
    ${result.blockers.length?`<ul class="chart-warning">${result.blockers.map(code=>`<li>${e(t(errorMessage(code)))}</li>`).join('')}</ul>`:`<p class="approval-note">${e(t('No pending accounting work or balance differences were found. Readiness is checked again at approval.'))}</p>`}</section>
    <p class="reference-note">${e(t('Closing requires another administrator’s approval. Journals and ledger changes pause while an approved close completes. Closed days cannot be reopened here.'))}</p>
    <section class="card"><div class="panel-title"><h2>${e(t('Closing history'))}</h2></div><div class="table-wrap"><table><thead><tr>${['Business date','Next working day','Status','Approved by',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.history.map(r=>`<tr><td>${e(r.businessDate)}</td><td>${e(r.nextBusinessDate)}</td><td><span class="status ${r.status==='CLOSED'?'active':'pending'}">${e(t(r.status==='CLOSED'?'Closed':'Closing'))}</span>${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td>${e(r.checker)}</td><td>${r.status==='CLOSED'?button('Closing totals','totals',r.requestId):button('Retry close','retry',r.requestId,bank.status!=='ACTIVE')}</td></tr>`).join('')||`<tr><td colspan="5" class="empty">${e(t('No days closed yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',result.history.length<25)}</div></section>
    <p class="approval-note"><button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>`;
  }
  async function action(name,id){
    if(!current())return;
    if(name==='refresh'){await refresh();return;}
    if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    const target=bankId,saved=structuredClone(data);
    if(name==='totals'){
      const row=data.history.find(r=>r.requestId===id);if(!row)return;
      form('Closing totals',`<p class="field full">${e(row.businessDate)} · ${e(row.completedAt||'')}</p><div class="field full table-wrap"><table><thead><tr>${['Legal entity','Currency','Debit balance','Credit balance'].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${row.totals.map(r=>`<tr><td>${e(bank.legalEntities.find(a=>a.id===r.legalEntityId)?.legalName||r.legalEntityId)}</td><td>${e(r.currency)}</td><td>${e(r.debitBalance)}</td><td>${e(r.creditBalance)}</td></tr>`).join('')}</tbody></table></div>`,async()=>{},'Done');return;
    }
    if(bank.status!=='ACTIVE')return;
    if(name==='retry'){await api(`/admin/tenants/${target}/business-day/${id}/retry`,{method:'POST'});await refresh();return;}
    if(name==='prepare'){form('Prepare closing storage',field('reason','Reason for this change','',{full:true,max:500}),d=>api(`/admin/tenants/${target}/ledger-accounts/prepare`,{method:'POST',body:{expectedVersion:saved.version,reason:d.reason}}),'Prepare closing storage');return;}
    if(name==='close'&&!saved.blockers.length){
      let key=crypto.randomUUID(),previous='';
      form('Request day close',`<p class="field full">${e(t('Close this business day and advance to the next working day after approval.'))}<br><strong>${e(saved.businessDate)} → ${e(saved.nextBusinessDate)}</strong></p>`+field('reason','Reason for this change','',{full:true,max:500}),d=>{
        const body={expectedVersion:saved.version,businessDate:saved.businessDate,nextBusinessDate:saved.nextBusinessDate,reason:d.reason};const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/business-day/close`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');
    }
  }
  return {render,action,selectBank(id){bankId=id;offset=0;}};
}
