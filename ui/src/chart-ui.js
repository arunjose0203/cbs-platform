import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';
export const chartCategories=['ASSET','LIABILITY','EQUITY','INCOME','EXPENSE'];
export const chartLabel=value=>({ASSET:'Assets',LIABILITY:'Liabilities',EQUITY:'Equity',INCOME:'Income',EXPENSE:'Expenses',DEBIT:'Debit',CREDIT:'Credit',GROUP:'Group',POSTING:'Posting'})[value]??value;
export const defaultBalance=category=>['ASSET','EXPENSE'].includes(category)?'DEBIT':'CREDIT';
export function createChartUi({api,form,field,refresh}){
  let bankId='',search='',category='',active='',offset=0,chart,bank,generation=0,currentView=()=>false;
  const size=25;
  const options=(items,value)=>items.map(item=>`<option value="${e(item.value)}" ${item.value===value?'selected':''}>${e(item.label)}</option>`).join('');
  const choices=values=>values.map(value=>({value,label:t(chartLabel(value))}));
  const button=(label,action,id='',secondary=false,disabled=false)=>`<button class="button ${secondary?'secondary':''}" data-action="chart-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const fieldNote=text=>`<p class="field full approval-note">${e(t(text))}</p>`;
  async function render(host,isCurrent){
    currentView=isCurrent;
    generation++;
    const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId&&banks.items.length)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id??banks.items[0].id;
    let selected=banks.items.find(b=>b.id===bankId);
    if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!isCurrent())return;
    if(!selected){host.innerHTML=`<div class="heading"><h1>${e(t('Chart of Accounts'))}</h1></div><section class="card empty"><h2>${e(t('No banks registered yet.'))}</h2><p>${e(t('Register and provision a bank to set up its chart of accounts.'))}</p></section>`;return;}
    const query=new URLSearchParams({limit:size,offset,search});if(category)query.set('category',category);if(active)query.set('active',active);
    const result=await api(`/admin/tenants/${bankId}/chart-of-accounts?${query}`);if(!isCurrent())return;
    bank=selected;chart=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Chart of Accounts'))}</h1><p class="intro">${e(t('Organize the accounts behind every banking transaction.'))}</p></div>${button('Add account','new','',false,bank.status!=='ACTIVE')}</div>
      <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="chart-bank">${options(banks.items.map(b=>({value:b.id,label:`${b.displayName} · ${b.bankCode}`})),bankId)}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div>
      ${bank.status!=='ACTIVE'?`<p class="chart-warning" role="status">${e(t('This bank must be ACTIVE before account changes can be submitted.'))}</p>`:''}
      <div class="chart-summary"><article class="card"><span>${e(t('Total definitions'))}</span><strong>${chart.accountCount}</strong></article><article class="card"><span>${e(t('Groups'))}</span><strong>${chart.groupCount}</strong></article><article class="card"><span>${e(t('Posting definitions'))}</span><strong>${chart.accountCount-chart.groupCount}</strong></article><article class="card"><span>${e(t('Active'))}</span><strong>${chart.activeCount}</strong></article></div>
      <section class="card"><div class="toolbar chart-filters"><label class="field chart-search">${e(t('Search code or name'))}<input id="chart-search" value="${e(search)}" maxlength="100" type="search"></label><label class="field">${e(t('Category'))}<select id="chart-category">${options([{value:'',label:t('All categories')},...choices(chartCategories)],category)}</select></label><label class="field">${e(t('Status'))}<select id="chart-active">${options([{value:'',label:t('All statuses')},{value:'true',label:t('Active')},{value:'false',label:t('Inactive')}],active)}</select></label>${button('Search','search','',true)}</div>
      <div class="table-wrap chart-table"><table><thead><tr>${['Code / Account name','Category','Normal balance','Kind / Parent','Status',''].map(h=>`<th scope="col">${e(t(h))}</th>`).join('')}</tr></thead><tbody>${chart.items.map(a=>`<tr><td><strong class="mono">${e(a.code)}</strong><span>${e(a.name)}</span></td><td>${e(t(chartLabel(a.category)))}</td><td>${e(t(chartLabel(a.normalBalance)))}</td><td>${e(t(chartLabel(a.kind)))}<small>${e(a.parentCode?`${t('Parent')}: ${a.parentCode}`:t('Root account'))}</small></td><td><span class="status ${a.active?'active':'disabled'}">${e(t(a.active?'Active':'Inactive'))}</span></td><td>${button('Manage','edit',a.id,true,bank.status!=='ACTIVE')}</td></tr>`).join('')||`<tr><td colspan="6" class="empty"><h3>${e(t(chart.accountCount?'No matching accounts.':'Build your bank’s chart of accounts.'))}</h3><p>${e(t(chart.accountCount?'Change the filters to find an account.':'Start with a group such as Assets, then add posting definitions beneath it.'))}</p>${chart.accountCount?'':button('Add account','new','',false,bank.status!=='ACTIVE')}</td></tr>`}</tbody></table></div>
      <div class="pagination"><span>${e(t('Matching accounts'))}: ${chart.total} · ${chart.items.length?offset+1:0}–${offset+chart.items.length}</span><div>${button('Previous','previous','',true,offset===0)}${button('Next','next','',true,offset+size>=chart.total)}</div></div></section>
      <p class="approval-note">${e(t('Changes take effect after approval by another administrator.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p><p class="reference-note">${e(t('Definitions apply across the bank. Currency-specific ledgers, balances and journal posting come next.'))}</p>`;
  }
  async function action(name,id){
    if(name==='search'){search=document.querySelector('#chart-search').value.trim();category=document.querySelector('#chart-category').value;active=document.querySelector('#chart-active').value;offset=0;await refresh();return;}
    if(name==='next'||name==='previous'){offset=Math.max(0,offset+(name==='next'?size:-size));await refresh();return;}
    if(!chart||bank.status!=='ACTIVE')return;
    const current=name==='edit'?chart.items.find(a=>a.id===id):null;
    if(!['new','edit'].includes(name)||(name==='edit'&&!current))return;
    const saved=structuredClone(chart),target=bankId,revision=generation;
    let groups=[];
    if(!current){
      let page;
      do{page=await api(`/admin/tenants/${target}/chart-of-accounts?kind=GROUP&active=true&limit=100&offset=${groups.length}`);groups.push(...page.items);}while(groups.length<page.total&&page.items.length);
      if(revision!==generation||!currentView())return;
    }
    const startingCategory=current?.category||category||'ASSET';
    const structure=current?fieldNote('Code, category, kind, normal balance and parent are fixed after approval.')+`<dl class="metadata field full"><div><dt>${e(t('Code'))}</dt><dd>${e(current.code)}</dd></div><div><dt>${e(t('Category'))}</dt><dd>${e(t(chartLabel(current.category)))}</dd></div><div><dt>${e(t('Normal balance'))}</dt><dd>${e(t(chartLabel(current.normalBalance)))}</dd></div><div><dt>${e(t('Kind / Parent'))}</dt><dd>${e(t(chartLabel(current.kind)))} / ${e(current.parentCode||t('Root account'))}</dd></div></dl>`:
      field('code','Account code','',{max:32,pattern:'[A-Z0-9][A-Z0-9_\\-]{0,31}',format:t('Use 1–32 uppercase letters, digits, hyphens or underscores.'),help:t('Examples: 1000 for Assets, 1100 for Cash.')})+
      field('category','Category',startingCategory,{choices:choices(chartCategories)})+
      field('kind','Account kind','GROUP',{choices:choices(['GROUP','POSTING']),help:t('Groups organize the chart; posting definitions will be used for ledger accounts.')})+
      field('normalBalance','Normal balance',defaultBalance(startingCategory),{choices:choices(['DEBIT','CREDIT']),help:t('Choose the opposite balance only for a contra account.')})+
      `<label class="field full">${e(t('Parent group'))}<select name="parentId"><option value="">${e(t('No parent (root account)'))}</option>${groups.map(g=>`<option value="${e(g.id)}" data-category="${g.category}" ${g.category!==startingCategory?'disabled':''}>${e(g.code)} — ${e(g.name)} (${e(t(chartLabel(g.category)))})</option>`).join('')}</select><small>${e(t('Choose an active group in the same category.'))}</small></label>`;
    // A retry of an unchanged form reuses its key; corrected input gets a new key.
    let previousPayload='',key=crypto.randomUUID();
    form(current?'Manage account':'Add account',fieldNote('Changes take effect after approval by another administrator.')+structure+
      field('name','Account name',current?.name||'',{full:true,max:160})+field('description','Description',current?.description||'',{full:true,max:500,required:false})+
      field('active','Status',String(current?.active??true),{choices:[{value:'true',label:t('Active')},{value:'false',label:t('Inactive')}]})+
      field('reason','Reason for this change','',{full:true,max:500}),d=>{
        const body={expectedVersion:saved.version,accountId:current?.id??null,code:current?.code??d.code,name:d.name,description:d.description,
          category:current?.category??d.category,normalBalance:current?.normalBalance??d.normalBalance,kind:current?.kind??d.kind,parentId:current?current.parentId:(d.parentId||null),active:d.active==='true',reason:d.reason};
        const payload=JSON.stringify(body);if(previousPayload&&previousPayload!==payload)key=crypto.randomUUID();previousPayload=payload;
        return api(`/admin/tenants/${target}/chart-of-accounts`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');
  }
  function categoryChanged(value){
    const parent=document.querySelector('#editor select[name="parentId"]');if(!parent)return;
    for(const option of parent.options)option.disabled=!!option.value&&option.dataset.category!==value;
    if(parent.selectedOptions[0]?.disabled)parent.value='';
    document.querySelector('#editor select[name="normalBalance"]').value=defaultBalance(value);
  }
  return {render,action,categoryChanged,selectBank(id){bankId=id;offset=0;search='';category='';active='';generation++;}};
}
