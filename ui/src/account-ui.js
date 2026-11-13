import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';
import {createStatementUi} from './statement-ui.js';
import {createOperationUi} from './operation-ui.js';
import {createFundingUi} from './funding-ui.js';

export const accountFields={name:'Account name',state:'Account status',customerNumber:'Customer number',productCode:'Product code',productRevision:'Product revision',currency:'Currency',minimumOpening:'Minimum opening amount',minimumBalance:'Minimum balance',liabilityAccountCode:'Liability chart code',legalEntityName:'Legal entity'};
const statusName=s=>({AWAITING_FUNDING:'Awaiting funding',ACTIVE:'Active',FROZEN:'Frozen',DORMANT:'Dormant',CLOSED:'Closed'})[s]||s;
export function createAccountUi({api,form,field,refresh}){
  const funding=createFundingUi({api,form,field,refresh});
  const operations=createOperationUi({api,form,field,refresh});
  const statements=createStatementUi({api,form,field});
  let bankId='',bank,data,search='',state='',offset=0,generation=0,current=()=>false;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="account-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const note=text=>`<p class="field full reference-note">${e(t(text))}</p>`;
  async function render(host,isCurrent){
    current=isCurrent;generation++;const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    let selected=banks.items.find(b=>b.id===bankId);if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!isCurrent())return;if(!selected){host.innerHTML=`<h1>${e(t('Deposit accounts'))}</h1><p>${e(t('No banks registered yet.'))}</p>`;return;}
    const q=new URLSearchParams({search,limit:25,offset});if(state)q.set('state',state);
    const result=await api(`/admin/tenants/${bankId}/accounts?${q}`);if(!isCurrent())return;bank=selected;data=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounts and deposits'))}</p><h1>${e(t('Deposit accounts'))}</h1><p>${e(t('Open accounts for approved customers and preserve their agreed product terms.'))}</p></div>${button(result.storageReady?'Open deposit account':'Prepare account storage',result.storageReady?'new':'prepare','',bank.status!=='ACTIVE')}</div>
    <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="account-bank">${banks.items.map(b=>`<option value="${e(b.id)}" ${b.id===bankId?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div>
    ${!result.storageReady?note('Prepare this bank’s account tables before opening accounts.'):''}
    <div class="toolbar"><label class="field">${e(t('Search by account name, account or customer number'))}<input id="account-search" maxlength="100" value="${e(search)}"></label><label class="field">${e(t('Account status'))}<select id="account-state"><option value="">${e(t('All statuses'))}</option>${['AWAITING_FUNDING','ACTIVE','FROZEN','DORMANT','CLOSED'].map(s=>`<option value="${s}" ${s===state?'selected':''}>${e(t(statusName(s)))}</option>`).join('')}</select></label>${button('Search','search')}${button('Refresh','refresh')}</div>
    <section class="card"><div class="panel-title"><h2>${e(t('Approved account records'))}</h2><span>${result.total}</span></div><div class="table-wrap"><table class="deposit-account-table"><thead><tr>${['Account','Product / currency','Account status','Delivery',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.items.map(r=>`<tr><td><strong>${e(r.name)}</strong><small>${e(r.accountNumber)}</small><small>${e(r.customerNumber)}</small></td><td>${e(r.productCode)}<small>${e(r.currency)}</small></td><td>${e(t(statusName(r.state)))}</td><td>${e(t(r.delivery==='APPLIED'?'Applied':'Awaiting delivery'))}${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td><div class="actions">${button('Balance and funding','funding-view',r.id)}${button('Account operations','operation-view',r.id)}${button('Statement','statement-view',r.id)}${button('Details','details',r.id)}${button('Mandate','mandate',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED'||r.state==='CLOSED')}${button('Closure check','closure',r.id,!result.storageReady||r.delivery!=='APPLIED'||r.state==='CLOSED')}${button('Manage','edit',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED'||r.state==='CLOSED')}${r.delivery!=='APPLIED'?button('Retry delivery','retry',r.requestId,bank.status!=='ACTIVE'):''}</div></td></tr>`).join('')||`<tr><td colspan="5" class="empty">${e(t('No approved accounts yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=result.total)}</div></section>
    <p class="reference-note">${e(t('Changes require another administrator’s approval and successful delivery to the bank database.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>
    <p class="reference-note">${e(t('Use Balance and funding for deposits, and Account operations for holds, transfers, withdrawals and funding corrections.'))}</p>`;
  }
  async function action(name,id){
    if(!current())return;
    if(name.startsWith('statement-')){const target=bankId,epoch=generation;await statements.action(name.slice(10),id,target,()=>current()&&epoch===generation);return;}
    if(name.startsWith('operation-')){const target=bankId,epoch=generation;await operations.action(name.slice(10),id,target,()=>current()&&epoch===generation);return;}
    if(name.startsWith('funding-')){const target=bankId,epoch=generation;await funding.action(name.slice(8),id,target,()=>current()&&epoch===generation);return;}
    if(name==='refresh'){await refresh();return;}
    if(name==='search'){search=document.querySelector('#account-search').value.trim();state=document.querySelector('#account-state').value;offset=0;await refresh();return;}
    if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    const target=bankId,version=data.version,epoch=generation;
    if(name==='retry'&&bank.status==='ACTIVE'){await api(`/admin/tenants/${target}/accounts/${id}/retry`,{method:'POST'});await refresh();return;}
    if(name==='prepare'&&bank.status==='ACTIVE'){form('Prepare account storage',field('reason','Reason for this change','',{full:true,max:500}),d=>api(`/admin/tenants/${target}/accounts/prepare`,{method:'POST',body:{expectedVersion:version,reason:d.reason}}),'Prepare account storage');return;}
    if(name==='mandate'){
      const [view,people]=await Promise.all([api(`/admin/tenants/${target}/accounts/${id}/mandate`),api(`/admin/tenants/${target}/customers?limit=100&offset=0`)]);if(epoch!==generation||!current())return;
      const persons=people.items.filter(x=>x.id!==view.primaryCustomerId&&x.type!=='ORGANIZATION'&&x.delivery==='APPLIED');const choices=[{value:'',label:t('None')},...persons.map(x=>({value:x.id,label:x.customerNumber+' · '+x.fullName}))];
      const roles=view.primaryType==='ORGANIZATION'?[['SIGNATORY','Authorised signatory']]:view.primaryMinor?[['GUARDIAN','Guardian']]:[['JOINT_HOLDER','Joint holder'],['SIGNATORY','Authorised signatory']];
      const guidance=view.primaryType==='ORGANIZATION'?'Signatories must be recorded directors or representatives of the organization.':view.primaryMinor?'The account holder is a minor: guardians operate the account.':'Joint holders own the account with the primary holder; signatories may only sign.';
      const rows=[0,1,2,3].map(i=>{const c=view.parties[i]||{};return field('party'+i,t('Person')+' '+(i+1),c.customerId||'',{choices,required:false})+field('role'+i,t('Role'),c.role||roles[0][0],{choices:roles.map(([value,label])=>({value,label:t(label)})),required:false})}).join('');
      let key=crypto.randomUUID(),previous='';
      form('Account mandate',note(guidance)+(view.configured?note(t('Current rule')+': '+view.rule):note('No mandate recorded yet.'))+field('rule','Signing rule',view.rule||'ANY_ONE',{choices:[['ANY_ONE','Any one signer'],['ANY_TWO','Any two signers'],['ALL','All signers']].map(([value,label])=>({value,label:t(label)}))})+rows+field('reason','Reason and evidence','',{full:true,max:500}),d=>{
        const parties=[0,1,2,3].filter(i=>d['party'+i]).map(i=>({customerId:d['party'+i],role:d['role'+i]}));
        const body={expectedVersion:version,accountId:id,rule:d.rule,parties,reason:d.reason};const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/accounts/mandates`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');return;
    }
    if(name==='closure'){
      const check=await api(`/admin/tenants/${target}/accounts/${id}/closure`);
      const row=await api(`/admin/tenants/${target}/accounts/${id}`);if(epoch!==generation||!current())return;
      const d=row.definition,blocked=check.blockers.length>0;
      const balances=`<dl class="metadata field full">${Object.entries({Posted:check.posted,Held:check.held,'Ledger balance':check.ledgerBalance}).map(([k,v])=>`<div><dt>${e(t(k))}</dt><dd>${e(v)} ${e(d.terms.currency)}</dd></div>`).join('')}</dl>`;
      const blockers=check.blockers.map(code=>note(errorMessage(code))).join('');
      let key=crypto.randomUUID(),previous='';
      // A positive balance is paid out by a separately approved final settlement before closure.
      const settleable=['FUNDING_BALANCE_NOT_ZERO','ACCOUNT_CLOSURE_CARRY_SETTLEMENT'];
      if(blocked&&!check.settled&&Number(check.posted)>0&&check.blockers.every(code=>settleable.includes(code))){
        const [ledgers,chart,settings]=await Promise.all([api(`/admin/tenants/${target}/ledger-accounts?active=true&currency=${d.terms.currency}&limit=100`),api(`/admin/tenants/${target}/chart-of-accounts?category=ASSET&active=true&kind=POSTING&limit=100`),api(`/admin/tenants/${target}/settings`)]);
        if(epoch!==generation||!current())return;
        const assets=new Set(chart.items.filter(x=>x.normalBalance==='DEBIT').map(x=>x.id));
        const rows=ledgers.items.filter(x=>x.delivery==='APPLIED'&&x.definition.legalEntityId===d.legalEntityId&&assets.has(x.definition.chartAccountId));
        const carry=Number(check.carry||0)!==0?`<p class="field full reference-note">${e(t('Residual interest below the smallest currency unit is forfeited:'))} ${e(check.carry)}</p>`:'';
        if(!rows.length){form('Final settlement prerequisites',balances+note('Approve an asset ledger in the account currency and legal entity to pay out the final balance.'),async()=>{},'Done');return;}
        form('Final settlement before closure',balances+carry+field('assetLedgerId','Paying asset ledger','',{full:true,choices:rows.map(x=>({value:x.definition.id,label:x.definition.code+' · '+x.definition.name}))})+field('reference','Reference','',{full:true,max:80})+field('reason','Reason and evidence','',{full:true,max:500})+note('Pays out the whole posted balance, including the product minimum, and freezes the account until closure is approved. Interest is settled through the last completed daily processing run. Release holds first.'),values=>{
          const body={expectedVersion:check.version,kind:'CLOSURE_SETTLEMENT',accountId:d.id,destinationId:null,assetLedgerId:values.assetLedgerId,originalId:null,amount:check.posted,businessDate:settings.businessDate,reference:values.reference,reason:values.reason};
          const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
          return api(`/admin/tenants/${target}/account-operations`,{method:'POST',headers:{'Idempotency-Key':key},body});
        },'Submit for approval');return;
      }
      form('Account closure check',balances+blockers+note('Closure preserves history. Pay out any remaining balance through final settlement first; contracts and payment returns are not settled here.')+(blocked?'':field('reason','Reason for this change','',{full:true,max:500})),values=>{
        if(blocked)return;
        const body={expectedVersion:check.version,accountId:d.id,customerId:d.customerId,productId:d.terms.id,productRevision:d.terms.revision,legalEntityId:d.legalEntityId,name:d.name,state:'CLOSED',reason:values.reason};
        const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/accounts`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },blocked?'Done':'Submit for approval');return;
    }
    let row;
    if(name==='details'||name==='edit'){
      row=await api(`/admin/tenants/${target}/accounts/${id}`);if(epoch!==generation||!current())return;
      if(name==='details'){
        const d=row.definition,p=d.terms,values={name:d.name,state:t(statusName(d.state)),customerNumber:d.customerNumber,productCode:p.code,productRevision:p.revision,currency:p.currency,minimumOpening:p.minimumOpening,minimumBalance:p.minimumBalance,liabilityAccountCode:p.liabilityAccountCode,legalEntityName:d.legalEntityName};
        form('Deposit account details',`<p class="field full">${e(d.accountNumber)} · ${e(t(row.delivery==='APPLIED'?'Applied':'Awaiting delivery'))}</p><dl class="metadata field full">${Object.entries(values).map(([k,v])=>`<div><dt>${e(t(accountFields[k]))}</dt><dd>${e(v)}</dd></div>`).join('')}</dl>`+note('Product terms are fixed at opening. Later product changes do not alter this account.')+note('New accounts await their first approved funding. Use Account operations for holds, internal transfers and recorded withdrawals.'),async()=>{},'Done');return;
      }
    }
    if(!['new','edit'].includes(name)||bank.status!=='ACTIVE'||!data.storageReady||(row&&(row.delivery!=='APPLIED'||row.definition.state==='CLOSED')))return;
    let products=[],customers=[];
    if(!row){
      const results=await Promise.all([api(`/admin/tenants/${target}/products?active=true&limit=100`),api(`/admin/tenants/${target}/customers?state=ACTIVE&limit=100`)]);
      if(epoch!==generation||!current())return;products=results[0].items;customers=results[1].items.filter(c=>c.delivery==='APPLIED');
      if(!products.length||!customers.length){form('Opening prerequisites',note('Approve and deliver an active customer, and activate a deposit product before opening an account.'),async()=>{},'Done');return;}
    }
    const d=row?.definition;let key=crypto.randomUUID(),previous='';
    const fields=d?note('Customer, legal entity, currency and product revision cannot change after opening.')+field('state','Account status',d.state,{choices:['AWAITING_FUNDING','ACTIVE','FROZEN','DORMANT'].map(s=>({value:s,label:t(statusName(s))}))}):
      field('customerId','Customer','',{full:true,choices:customers.map(c=>({value:c.id,label:c.fullName+' · '+c.customerNumber}))})+
      field('productId','Approved product','',{full:true,choices:products.map(p=>({value:p.id,label:p.code+' · '+p.currency+' · '+t('Minimum opening amount')+' '+p.minimumOpening+' · r'+p.revision}))})+
      field('legalEntityId','Legal entity','',{full:true,choices:bank.legalEntities.map(x=>({value:x.id,label:x.legalName}))})+note('Selectors show the first 100 active customers and products.');
    form(d?'Manage deposit account':'Open deposit account',fields+field('name','Account name',d?.name||'',{full:true,max:120})+note('New accounts await their first approved funding. Use Account operations for holds, internal transfers and recorded withdrawals.')+field('reason','Reason for this change','',{full:true,max:500}),values=>{
      const product=d?.terms||products.find(p=>p.id===values.productId);
      const body={expectedVersion:version,accountId:d?.id??null,customerId:d?.customerId||values.customerId,productId:product.id,productRevision:product.revision,legalEntityId:d?.legalEntityId||values.legalEntityId,name:values.name,state:d?values.state:'AWAITING_FUNDING',reason:values.reason};
      const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
      return api(`/admin/tenants/${target}/accounts`,{method:'POST',headers:{'Idempotency-Key':key},body});
    },'Submit for approval');
  }

  return {render,action,selectBank(id){bankId=id;offset=0;search='';state='';generation++;}};
}
