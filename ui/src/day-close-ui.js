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
    const [result,periods]=await Promise.all([api(`/admin/tenants/${bankId}/business-day?limit=25&offset=${offset}`),api(`/admin/tenants/${bankId}/period-close`)]);if(!isCurrent())return;bank=selected;data=result;data.periods=periods;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Business Day Close'))}</h1><p>${e(t('Verify the day, obtain approval, and advance the bank’s business date.'))}</p></div>${button('Refresh','refresh')}</div>
    <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="day-bank">${banks.items.map(b=>`<option value="${e(b.id)}" ${b.id===bankId?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label></div>
    <section class="card"><div class="panel-title"><h2>${e(t('Close readiness'))}</h2>${result.storageReady?button('Request day close','close','',result.blockers.length>0):button('Prepare closing storage','prepare','',bank.status!=='ACTIVE')}</div>
    <dl class="metadata"><div><dt>${e(t('Business date'))}</dt><dd>${e(result.businessDate)}</dd></div><div><dt>${e(t('Next working day'))}</dt><dd>${e(result.nextBusinessDate)}</dd></div><div><dt>${e(t('Closed through'))}</dt><dd>${e(result.closedThrough||'—')}</dd></div></dl>
    ${result.blockers.length?`<ul class="chart-warning">${result.blockers.map(code=>`<li>${e(t(errorMessage(code)))}</li>`).join('')}</ul>`:`<p class="approval-note">${e(t('No pending accounting work or balance differences were found. Readiness is checked again at approval.'))}</p>`}</section>
    <p class="reference-note">${e(t('Closing requires another administrator’s approval. Journals and ledger changes pause while an approved close completes. Closed days cannot be reopened here.'))}</p>
    <section class="card"><div class="panel-title"><h2>${e(t('Month-end and year-end close'))}</h2><div class="actions">${button('Close policy','period-policy','',bank.status!=='ACTIVE'||!periods.storageReady)}${button('Close the period','period-close','',!periods.periodEnd||!periods.policy?.fiscalYearEndMonth)}</div></div>
    <p>${periods.policy?.fiscalYearEndMonth?e(t('Fiscal year ends in month'))+' '+e(periods.policy.fiscalYearEndMonth)+'. '+e(t('Each month must be closed on its last business day before that day closes. Year-end close moves income and expenses into retained earnings.')):e(t('No close policy yet: month-end close is not enforced.'))}</p>
    <div class="table-wrap"><table><thead><tr>${['Period end','Type','Status',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${(periods.closes||[]).map(r=>`<tr><td>${e(r.period_end)}</td><td>${e(t(r.period_type==='YEAR'?'Year end':'Month end'))}</td><td>${e(t(r.completed?'Closed':'Closing'))}${r.failure_code?`<small>${e(t(errorMessage(r.failure_code)))}</small>`:''}</td><td>${r.completed?'':button('Retry close','period-retry',r.request_id)}</td></tr>`).join('')||`<tr><td colspan="4" class="empty">${e(t('No period closes yet.'))}</td></tr>`}</tbody></table></div></section>
    <section class="card"><div class="panel-title"><h2>${e(t('Closing history'))}</h2></div><div class="table-wrap"><table><thead><tr>${['Business date','Next working day','Status','Approved by',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.history.map(r=>`<tr><td>${e(r.businessDate)}</td><td>${e(r.nextBusinessDate)}</td><td><span class="status ${r.status==='CLOSED'?'active':'pending'}">${e(t(r.status==='CLOSED'?'Closed':'Closing'))}</span>${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td>${e(r.checker)}</td><td>${r.status==='CLOSED'?button('Closing totals','totals',r.requestId):button('Retry close','retry',r.requestId,bank.status!=='ACTIVE')}</td></tr>`).join('')||`<tr><td colspan="5" class="empty">${e(t('No days closed yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',result.history.length<25)}</div></section>
    <p class="approval-note"><button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>`;
  }
  async function action(name,id){
    if(!current())return;
    if(name==='refresh'){await refresh();return;}
    if(name==='period-retry'){await api(`/admin/tenants/${bankId}/period-close/runs/${id}/retry`,{method:'POST'});await refresh();return;}
    if(name==='period-policy'){
      const [ledgers,chart]=await Promise.all([api(`/admin/tenants/${bankId}/ledger-accounts?active=true&limit=100`),api(`/admin/tenants/${bankId}/chart-of-accounts?category=EQUITY&active=true&kind=POSTING&limit=100`)]);if(!current())return;
      const equity=new Set(chart.items.filter(x=>x.normalBalance==='CREDIT').map(x=>x.id));const rows=ledgers.items.filter(x=>x.delivery==='APPLIED'&&equity.has(x.definition.chartAccountId));
      if(!rows.length){form('Close policy',`<p class="field full">${e(t('Approve a credit-normal equity ledger for retained earnings first.'))}</p>`,async()=>{},'Done');return;}
      const choices=[{value:'',label:t('None')},...rows.map(x=>({value:x.definition.id,label:x.definition.code+' · '+x.definition.currency+' · '+x.definition.name}))];const key=crypto.randomUUID();const version=data.version;
      form('Close policy',field('fiscalYearEndMonth','Fiscal year end month',String(data.periods.policy?.fiscalYearEndMonth||12),{choices:Array.from({length:12},(_,i)=>({value:String(i+1),label:String(i+1)}))})+[0,1,2,3].map(i=>field('ledger'+i,t('Retained earnings ledger')+' '+(i+1),'',{choices,required:false})).join('')+`<p class="field full">${e(t('Choose one retained earnings ledger for each legal entity and currency that books income or expenses.'))}</p>`+field('reason','Reason and evidence','',{full:true,max:500}),d=>{
        const picked=[0,1,2,3].map(i=>rows.find(x=>x.definition.id===d['ledger'+i])).filter(Boolean);
        return api(`/admin/tenants/${bankId}/period-close/policy`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,fiscalYearEndMonth:Number(d.fiscalYearEndMonth),retainedEarnings:picked.map(x=>({legalEntityId:x.definition.legalEntityId,currency:x.definition.currency,ledgerId:x.definition.id})),reason:d.reason}});
      },'Submit for approval');return;
    }
    if(name==='period-close'){
      const version=data.version,date=data.businessDate;const preview=await api(`/admin/tenants/${bankId}/period-close/preview`,{method:'POST',body:{expectedVersion:version,businessDate:date,reason:'Review period close'}});if(!current())return;const key=crypto.randomUUID();
      const totals=preview.totals.map(x=>`<tr><td>${e(x.currency)}</td><td>${e(x.debits)}</td><td>${e(x.credits)}</td><td>${e(x.income)}</td><td>${e(x.expense)}</td><td>${e(x.netResult)}</td></tr>`).join('');
      form(preview.type==='YEAR'?'Year-end close':'Month-end close',`<p class="field full">${e(t('Period end'))}: ${e(preview.periodEnd)}. ${e(t('Approval seals this business day; no further postings are accepted for it.'))}</p><div class="field full table-wrap"><table><thead><tr>${['Currency','Debits','Credits','Income','Expense','Result'].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${totals}</tbody></table></div>`+(preview.journals.length?`<p class="field full">${e(t('Closing journals'))}: ${e(preview.journals.map(j=>j.reference+' ('+j.lines.length+')').join(', '))}</p>`:'')+field('reason','Review evidence','',{full:true,max:500}),d=>api(`/admin/tenants/${bankId}/period-close/runs`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,businessDate:date,reason:d.reason}}),'Submit for approval');return;
    }
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
