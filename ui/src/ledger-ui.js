import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

export function createLedgerUi({api,form,field,refresh}){
  let bankId='',search='',currency='',active='',offset=0,bank,directory,settings,currentView=()=>false;
  const size=25;
  const options=(items,value)=>items.map(i=>`<option value="${e(i.value)}" ${i.value===value?'selected':''}>${e(i.label)}</option>`).join('');
  const button=(label,action,id='',disabled=false)=>`<button class="button secondary" data-action="ledger-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const reason=()=>field('reason','Reason for this change','',{full:true,max:500});
  async function render(host,isCurrent){
    currentView=isCurrent;
    const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId&&banks.items.length)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id??banks.items[0].id;
    let selected=banks.items.find(b=>b.id===bankId);
    if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!isCurrent())return;
    if(!selected){host.innerHTML=`<div class="heading"><h1>${e(t('Ledger Accounts'))}</h1></div><section class="card empty"><h2>${e(t('No banks registered yet.'))}</h2><p>${e(t('Register and provision a bank before creating ledger accounts.'))}</p></section>`;return;}
    const query=new URLSearchParams({search,offset,limit:size});if(currency)query.set('currency',currency);if(active)query.set('active',active);
    const [result,config]=await Promise.all([api(`/admin/tenants/${bankId}/ledger-accounts?${query}`),api(`/admin/tenants/${bankId}/settings`)]);
    if(!isCurrent())return;bank=selected;directory=result;settings=config;
    const canWrite=bank.status==='ACTIVE'&&directory.storageReady;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Ledger Accounts'))}</h1><p class="intro">${e(t('Create operational accounts for each legal entity and currency.'))}</p></div>${button('Add ledger account','new','',!canWrite)}</div>
      <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="ledger-bank">${options(banks.items.map(b=>({value:b.id,label:`${b.displayName} · ${b.bankCode}`})),bankId)}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div>
      ${bank.status!=='ACTIVE'?`<p class="chart-warning">${e(t('This bank must be ACTIVE before account changes can be submitted.'))}</p>`:''}
      ${!directory.storageReady?`<section class="card empty"><h2>${e(t('Prepare ledger storage'))}</h2><p>${e(t('Set up the ledger tables in this bank’s dedicated database.'))}</p>${button('Prepare ledger storage','prepare','',bank.status!=='ACTIVE')}</section>`:
        `<section class="card"><div class="toolbar chart-filters"><label class="field chart-search">${e(t('Search code or name'))}<input type="search" id="ledger-search" maxlength="100" value="${e(search)}"></label><label class="field">${e(t('Currency'))}<select id="ledger-currency">${options([{value:'',label:t('All currencies')},...settings.currencies.map(c=>({value:c.code,label:c.code}))],currency)}</select></label><label class="field">${e(t('Status'))}<select id="ledger-active">${options([{value:'',label:t('All statuses')},{value:'true',label:t('Active')},{value:'false',label:t('Inactive')}],active)}</select></label>${button('Search','search')}<button class="button secondary" data-action="refresh">${e(t('Refresh'))}</button></div>
        <div class="table-wrap chart-table"><table><thead><tr>${['Ledger account','Chart / Legal entity','Currency','Approved status','Delivery',''].map(h=>`<th scope="col">${e(t(h))}</th>`).join('')}</tr></thead><tbody>${directory.items.map(row=>{
          const a=row.definition;return `<tr><td><strong>${e(a.code)}</strong><span>${e(a.name)}</span></td><td><strong>${e(row.chartCode)}</strong><small>${e(row.legalEntityName)}</small></td><td>${e(a.currency)}</td><td><span class="status ${a.active?'active':'disabled'}">${e(t(a.active?'Active':'Inactive'))}</span></td><td><span class="status ${row.delivery==='APPLIED'?'active':'pending'}">${e(t(row.delivery==='APPLIED'?'Ready':'Awaiting delivery'))}</span>${row.failureCode?`<small>${e(errorMessage(row.failureCode))}</small>`:''}</td><td>${row.delivery==='APPLIED'?button('Manage','edit',a.id,!canWrite):button('Retry delivery','retry',row.requestId,bank.status!=='ACTIVE')}</td></tr>`;
        }).join('')||`<tr><td colspan="6" class="empty"><h3>${e(t('No ledger accounts match.'))}</h3><p>${e(t('Choose an approved posting definition, legal entity and enabled currency to add a ledger.'))}</p>${button('Add ledger account','new','',!canWrite)}</td></tr>`}</tbody></table></div>
        <div class="pagination"><span>${e(t('Matching accounts'))}: ${directory.total} · ${directory.items.length?offset+1:0}–${offset+directory.items.length}</span><div>${button('Previous','previous','',offset===0)}${button('Next','next','',offset+size>=directory.total)}</div></div></section>`}
      <p class="approval-note">${e(t('Changes require another administrator’s approval. Approved changes are then delivered to the bank database.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p><p class="reference-note">${e(t('Awaiting delivery is not ready for use. Refresh to check delivery; failed deliveries can be retried safely.'))}<br>${e(t('Open Journals & balances to post approved entries and review balances.'))}</p>`;
  }
  async function action(name,id){
    if(name==='search'){search=document.querySelector('#ledger-search').value.trim();currency=document.querySelector('#ledger-currency').value;active=document.querySelector('#ledger-active').value;offset=0;await refresh();return;}
    if(name==='next'||name==='previous'){offset=Math.max(0,offset+(name==='next'?size:-size));await refresh();return;}
    if(!bank||bank.status!=='ACTIVE'||!currentView())return;
    const target=bankId,saved=structuredClone(directory),selectedBank=structuredClone(bank),viewIsCurrent=currentView;
    if(name==='prepare'){
      form('Prepare ledger storage',`<p class="field full">${e(t('Set up the ledger tables in this bank’s dedicated database.'))}</p>`+reason(),d=>api(`/admin/tenants/${target}/ledger-accounts/prepare`,{method:'POST',body:{expectedVersion:saved.version,reason:d.reason}}),'Prepare ledger storage');return;
    }
    if(name==='retry'){await api(`/admin/tenants/${target}/ledger-accounts/deliveries/${id}/retry`,{method:'POST'});await refresh();return;}
    if(!directory.storageReady||!['new','edit'].includes(name))return;
    const row=name==='edit'?saved.items.find(r=>r.definition.id===id):null;
    if(name==='edit'&&(!row||row.delivery!=='APPLIED'))return;
    const current=row?.definition;
    let definitions=[];
    if(!current){
      let page;
      do{page=await api(`/admin/tenants/${target}/chart-of-accounts?kind=POSTING&active=true&limit=100&offset=${definitions.length}`);definitions.push(...page.items);}while(definitions.length<page.total&&page.items.length);
      if(!viewIsCurrent())return;
      if(!definitions.length)throw new Error(t('Create and approve an active posting definition in Chart of Accounts first.'));
    }
    const currencies=settings.currencies.filter(c=>c.enabled).map(c=>({value:c.code,label:c.code}));
    const structure=current?`<dl class="metadata field full"><div><dt>${e(t('Account code'))}</dt><dd>${e(current.code)}</dd></div><div><dt>${e(t('Chart / Legal entity'))}</dt><dd>${e(row.chartCode)} / ${e(row.legalEntityName)}</dd></div><div><dt>${e(t('Currency'))}</dt><dd>${e(current.currency)}</dd></div></dl><p class="field full approval-note">${e(t('Code, chart definition, legal entity and currency are fixed after approval.'))}</p>`:
      field('code','Account code','',{max:32,pattern:'[A-Z0-9][A-Z0-9_\\-]{0,31}',format:t('Use 1–32 uppercase letters, digits, hyphens or underscores.')})+
      field('currency','Currency',currencies[0]?.value,{choices:currencies})+
      field('chartAccountId','Posting definition',definitions[0]?.id,{full:true,choices:definitions.map(a=>({value:a.id,label:`${a.code} — ${a.name}`}))})+
      field('legalEntityId','Legal entity',selectedBank.legalEntities[0]?.id,{full:true,choices:selectedBank.legalEntities.map(a=>({value:a.id,label:a.legalName}))});
    let key=crypto.randomUUID(),previousPayload='';
    form(current?'Manage ledger account':'Add ledger account',`<p class="field full approval-note">${e(t('Changes take effect after approval by another administrator.'))}</p>`+structure+
      field('name','Account name',current?.name||'',{full:true,max:160})+field('active','Status',String(current?.active??true),{choices:[{value:'true',label:t('Active')},{value:'false',label:t('Inactive')}]})+reason(),d=>{
        const body={expectedVersion:saved.version,ledgerId:current?.id??null,code:current?.code??d.code,name:d.name,chartAccountId:current?.chartAccountId??d.chartAccountId,legalEntityId:current?.legalEntityId??d.legalEntityId,currency:current?.currency??d.currency,active:d.active==='true',reason:d.reason};
        const payload=JSON.stringify(body);if(previousPayload&&payload!==previousPayload)key=crypto.randomUUID();previousPayload=payload;
        return api(`/admin/tenants/${target}/ledger-accounts`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');
  }
  return {render,action,selectBank(id){bankId=id;offset=0;search='';currency='';active='';}};
}
