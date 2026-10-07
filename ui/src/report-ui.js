import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

export function reportTable(report){
  return `<div class="table-wrap"><table><thead><tr>${report.columns.map(c=>`<th>${e(t(c))}</th>`).join('')}</tr></thead><tbody>${report.rows.map(row=>`<tr>${row.map(v=>`<td>${e(v)}</td>`).join('')}</tr>`).join('')||`<tr><td colspan="${report.columns.length}" class="empty">${e(t('No entries yet.'))}</td></tr>`}</tbody></table></div>`;
}
export function createReportUi({api,refresh}){
  let bankId='',bank,config,ledgers=[],current=()=>false,lastQuery='',report=null,runVersion=0;
  const select=(id,label,items,value)=>`<label class="field">${e(t(label))}<select id="${id}">${items.map(a=>`<option value="${e(a.value)}" ${a.value===value?'selected':''}>${e(a.label)}</option>`).join('')}</select></label>`;
  const button=(label,action)=>`<button class="button secondary" data-action="report-${action}">${e(t(label))}</button>`;
  async function render(host,isCurrent){
    current=isCurrent;lastQuery='';report=null;runVersion++;
    const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    bankId=bankId||banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    bank=banks.items.find(b=>b.id===bankId);
    if(bankId&&!bank){bank=await api(`/admin/tenants/${bankId}`);banks.items.push(bank);}
    if(!bank){host.innerHTML=`<h1>${e(t('Accounting reports'))}</h1><p>${e(t('No banks registered yet.'))}</p>`;return;}
    config=await api(`/admin/tenants/${bankId}/settings`);if(!isCurrent())return;
    ledgers=[];
    let page;do{page=await api(`/admin/tenants/${bankId}/ledger-accounts?limit=100&offset=${ledgers.length}`);ledgers.push(...page.items);}while(ledgers.length<page.total&&page.items.length);
    if(!isCurrent())return;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Accounting reports'))}</h1><p>${e(t('Review posted entries, balances and reconciliation differences.'))}</p></div></div>
    <section class="card"><div class="toolbar report-filters">
    ${select('report-bank','Bank',banks.items.map(b=>({value:b.id,label:b.displayName})),bankId)}
    ${select('report-kind','Report',[{value:'TRIAL_BALANCE',label:t('Trial Balance')},{value:'LEDGER_STATEMENT',label:t('Ledger Statement')},{value:'RECONCILIATION',label:t('Reconciliation')}],'TRIAL_BALANCE')}
    ${select('report-entity','Legal entity',bank.legalEntities.map(a=>({value:a.id,label:a.legalName})),bank.legalEntities[0]?.id)}
    ${select('report-currency','Currency',config.currencies.map(c=>({value:c.code,label:c.code})),config.currencies[0]?.code)}
    <label class="field" id="report-from-field" hidden>${e(t('From date'))}<input id="report-from" type="date" value="${e(config.businessDate)}"></label>
    <label class="field" id="report-to-field">${e(t('Through date'))}<input id="report-to" type="date" value="${e(config.businessDate)}"></label>
    <div id="report-ledger-field" hidden></div>${button('Run report','run')}</div></section>
    <p class="reference-note">${e(t('Reconciliation checks all posted history. Other reports use inclusive business dates. Negative net debit means a credit balance.'))}</p>
    <div id="report-result"><section class="card empty">${e(t('Choose filters and run a report.'))}</section></div>`;
    changed();
  }
  function changed(){
    if(!document.querySelector('#report-kind'))return;
    const kind=document.querySelector('#report-kind').value,entity=document.querySelector('#report-entity').value,currency=document.querySelector('#report-currency').value;
    document.querySelector('#report-from-field').hidden=kind!=='LEDGER_STATEMENT';document.querySelector('#report-to-field').hidden=kind==='RECONCILIATION';
    const holder=document.querySelector('#report-ledger-field');holder.hidden=kind!=='LEDGER_STATEMENT';
    const previous=document.querySelector('#report-ledger')?.value;
    holder.innerHTML=select('report-ledger','Ledger account',ledgers.filter(r=>r.delivery==='APPLIED'&&r.definition.legalEntityId===entity&&r.definition.currency===currency).map(r=>({value:r.definition.id,label:`${r.definition.code} — ${r.definition.name}`})),previous);
  }
  async function action(name){
    if(!current())return;
    if(name==='export'&&lastQuery){
      const csv=await api(lastQuery+'&csv=true',{responseType:'text'});const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));
      const link=document.createElement('a');link.href=url;link.download='accounting-report.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return;
    }
    if(name!=='run')return;
    const kind=document.querySelector('#report-kind').value;
    const query=new URLSearchParams({kind,legalEntityId:document.querySelector('#report-entity').value,currency:document.querySelector('#report-currency').value});
    if(kind!=='RECONCILIATION')query.set('toDate',document.querySelector('#report-to').value);
    if(kind==='LEDGER_STATEMENT'){query.set('fromDate',document.querySelector('#report-from').value);query.set('ledgerId',document.querySelector('#report-ledger').value);}
    const path=`/admin/tenants/${bankId}/accounting-reports?${query}`,isCurrent=current;
    const ticket=++runVersion,result=await api(path);if(!isCurrent()||ticket!==runVersion)return;report=result;lastQuery=path;
    document.querySelector('#report-result').innerHTML=`<section class="card section-gap"><div class="panel-title"><div><h2>${e(t(({TRIAL_BALANCE:'Trial Balance',LEDGER_STATEMENT:'Ledger Statement',RECONCILIATION:'Reconciliation'})[report.kind]))}</h2><p>${e(report.bankName)} · ${e(report.legalEntityName)} · ${e(report.currency)} · ${e(report.fromDate||'')} ${e(report.toDate||t('All posted history'))}</p><small>${e(t('Generated at'))}: ${e(report.generatedAt)}</small></div>${button('Export CSV','export')}</div>
    <dl class="metadata">${Object.entries(report.summary).map(([k,v])=>`<div><dt>${e(t(k))}</dt><dd>${e(v)}</dd></div>`).join('')}</dl>
    ${report.kind==='LEDGER_STATEMENT'?'':`<p class="${report.reconciled?'approval-note':'chart-warning'}">${e(t(report.reconciled?'Checks passed':'Differences require investigation'))}</p>`}${reportTable(report)}</section><p class="reference-note">${e(t('CSV export runs the same filters again and may include newly posted entries. Reports are limited to 10,000 rows.'))}</p>`;
  }
  return {render,action,changed,selectBank(id){bankId=id;}};
}
