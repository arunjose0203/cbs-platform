import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

const typeLabel=value=>t(value==='SAVINGS'?'Savings':'Current account');
export function createProductUi({api,form,field,refresh}){
  let bankId='',bank,data,search='',type='',active='',offset=0,generation=0,isCurrent=()=>false,historyId='',historyOffset=0;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="product-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const note=text=>`<p class="field full reference-note">${e(t(text))}</p>`;
  async function render(host,current){
    isCurrent=current;generation++;
    const banks=await api('/admin/tenants?limit=100');if(!current())return;
    if(!bankId)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    let selected=banks.items.find(b=>b.id===bankId);
    if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!current())return;
    if(!selected){host.innerHTML=`<h1>${e(t('Product Factory'))}</h1><p>${e(t('No banks registered yet.'))}</p>`;return;}
    const query=new URLSearchParams({limit:25,offset,search});if(type)query.set('type',type);if(active)query.set('active',active);
    const result=await api(`/admin/tenants/${bankId}/products?${query}`);if(!current())return;bank=selected;data=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Deposit products'))}</p><h1>${e(t('Product Factory'))}</h1><p>${e(t('Define the products your bank will offer to customers.'))}</p></div>${button('Create product','new','',bank.status!=='ACTIVE')}</div>
    <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="product-bank">${banks.items.map(b=>`<option value="${e(b.id)}" ${b.id===bankId?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div>
    ${bank.status!=='ACTIVE'?`<p class="chart-warning">${e(t('The bank must be active to configure products.'))}</p>`:''}
    <div class="toolbar"><label class="field">${e(t('Search'))}<input id="product-search" maxlength="100" value="${e(search)}"></label><label class="field">${e(t('Product type'))}<select id="product-type"><option value="">${e(t('All types'))}</option>${['SAVINGS','CURRENT'].map(v=>`<option value="${v}" ${type===v?'selected':''}>${e(typeLabel(v))}</option>`).join('')}</select></label><label class="field">${e(t('Status'))}<select id="product-active">${[['','All statuses'],['true','Active'],['false','Inactive']].map(([v,l])=>`<option value="${v}" ${active===v?'selected':''}>${e(t(l))}</option>`).join('')}</select></label>${button('Search','search')}${button('Refresh','refresh')}</div>
    <section class="card"><div class="panel-title"><h2>${e(t('Approved products'))}</h2><span>${result.total}</span></div><div class="table-wrap"><table><thead><tr>${['Product','Currency','Minimum opening','Minimum balance','Status','Revision',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.items.map(p=>`<tr><td><strong>${e(p.code)} · ${e(p.name)}</strong><small>${e(typeLabel(p.productType))} · ${e(t('Liability account'))}: ${e(p.liabilityAccountCode)}</small></td><td>${e(p.currency)}</td><td>${e(p.minimumOpening)}</td><td>${e(p.minimumBalance)}</td><td><span class="status ${p.active?'active':'pending'}">${e(t(p.active?'Active':'Inactive'))}</span></td><td>${e(p.revision)}</td><td><div class="actions">${button('Manage','edit',p.id,bank.status!=='ACTIVE')}${button('Revisions','history',p.id)}</div></td></tr>`).join('')||`<tr><td colspan="7" class="empty">${e(t('No approved products yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=result.total)}</div></section>
    <p class="reference-note">${e(t('Changes take effect after approval by another administrator.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>
    <p class="reference-note">${e(t('Product definitions prepare for account opening. Customer accounts, interest, fees and overdrafts are not available in this module.'))}</p>`;
  }
  async function action(name,id){
    if(!isCurrent())return;
    if(name==='refresh'){await refresh();return;}
    if(name==='search'){search=document.querySelector('#product-search').value.trim();type=document.querySelector('#product-type').value;active=document.querySelector('#product-active').value;offset=0;await refresh();return;}
    if(name==='next'||name==='previous'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    const target=bankId,version=data.version,epoch=generation;
    if(['history','history-next','history-previous'].includes(name)){
      if(name==='history'){historyId=id;historyOffset=0;}else historyOffset=Math.max(0,historyOffset+(name==='history-next'?25:-25));
      const rows=await api(`/admin/tenants/${target}/products/${historyId}/revisions?limit=25&offset=${historyOffset}`);if(epoch!==generation||!isCurrent())return;
      form('Product revisions',`<div class="field full table-wrap"><table><thead><tr>${['Revision','Product','Minimum opening','Minimum balance','Status','Approved by'].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${rows.items.map(r=>`<tr><td>${e(r.revision)}<small>${e(r.createdAt)}</small></td><td>${e(r.definition.name)}<small>${e(r.definition.currency)} · ${e(r.definition.liabilityAccountCode)}</small></td><td>${e(r.definition.minimumOpening)}</td><td>${e(r.definition.minimumBalance)}</td><td>${e(t(r.definition.active?'Active':'Inactive'))}</td><td>${e(r.approvedBy)}<small>${e(r.reason)}</small></td></tr>`).join('')}</tbody></table></div><div class="field full actions">${button('Previous','history-previous','',historyOffset===0)}${button('Next','history-next','',rows.items.length<25)}</div>`,async()=>{},'Done');return;
    }
    if(bank.status!=='ACTIVE'||!['new','edit'].includes(name))return;
    const p=name==='edit'?data.items.find(r=>r.id===id):null;if(name==='edit'&&!p)return;
    let structure;
    if(p)structure=note('Code, type, currency and liability mapping are fixed after approval.')+`<dl class="field full metadata"><div><dt>${e(t('Product'))}</dt><dd>${e(p.code)} · ${e(typeLabel(p.productType))}</dd></div><div><dt>${e(t('Currency'))}</dt><dd>${e(p.currency)}</dd></div><div><dt>${e(t('Liability account'))}</dt><dd>${e(p.liabilityAccountCode)}</dd></div></dl>`;
    else{
      const settings=await api(`/admin/tenants/${target}/settings`);let accounts=[],page;
      do{page=await api(`/admin/tenants/${target}/chart-of-accounts?category=LIABILITY&kind=POSTING&active=true&limit=100&offset=${accounts.length}`);accounts.push(...page.items);}while(accounts.length<page.total&&page.items.length);
      if(epoch!==generation||!isCurrent())return;
      const mappings=accounts.filter(a=>a.normalBalance==='CREDIT'),currencies=settings.currencies.filter(c=>c.enabled);
      if(!mappings.length||!currencies.length)throw Error(t('Enable a currency and approve an active liability posting account with a credit normal balance first.'));
      structure=field('code','Product code','',{max:32,pattern:'[A-Z0-9][A-Z0-9_\\-]{0,31}'})+field('productType','Product type','SAVINGS',{choices:[{value:'SAVINGS',label:typeLabel('SAVINGS')},{value:'CURRENT',label:typeLabel('CURRENT')}]})+field('currency','Currency',currencies[0].code,{choices:currencies.map(c=>({value:c.code,label:c.code}))})+field('liabilityAccountId','Liability account',mappings[0].id,{choices:mappings.map(a=>({value:a.id,label:`${a.code} — ${a.name}`}))});
    }
    let key=crypto.randomUUID(),previous='';
    form(p?'Manage product':'Create product',note('Changes take effect after approval by another administrator.')+structure+
      field('name','Product name',p?.name||'',{full:true,max:160})+field('description','Description',p?.description||'',{full:true,max:500,required:false})+
      field('minimumOpening','Minimum opening',p?.minimumOpening||'0',{max:25,pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('minimumBalance','Minimum balance',p?.minimumBalance||'0',{max:25,pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+
      note('Use exact amounts in the product currency. Minimum opening must be at least the minimum balance.')+
      field('active','Status',String(p?.active??false),{choices:[{value:'false',label:t('Inactive')},{value:'true',label:t('Active')}]})+field('reason','Reason for this change','',{full:true,max:500}),d=>{
        const body={expectedVersion:version,productId:p?.id??null,code:p?.code??d.code,productType:p?.productType??d.productType,currency:p?.currency??d.currency,liabilityAccountId:p?.liabilityAccountId??d.liabilityAccountId,name:d.name,description:d.description,minimumOpening:d.minimumOpening,minimumBalance:d.minimumBalance,active:d.active==='true',reason:d.reason};
        const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/products`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');
  }
  return {render,action,selectBank(id){bankId=id;offset=0;search='';type='';active='';generation++;}};
}
