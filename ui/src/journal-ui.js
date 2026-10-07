import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

// Integer minor units keep preview arithmetic exact, including amounts above Number.MAX_SAFE_INTEGER.
export function journalTotals(lines,scale){
  let debit=0n,credit=0n;
  for(const line of lines){
    if(!/^\d{1,18}(\.\d{1,6})?$/.test(line.amount)||!['DEBIT','CREDIT'].includes(line.side))return null;
    const [whole,fraction='']=line.amount.split('.');
    if(fraction.slice(scale).replace(/0/g,''))return null;
    const amount=BigInt(whole+fraction.slice(0,scale).padEnd(scale,'0'));
    if(amount<=0n)return null;
    if(line.side==='DEBIT')debit+=amount;else credit+=amount;
  }
  const format=n=>{const v=n.toString().padStart(scale+1,'0');return scale?`${v.slice(0,-scale)}.${v.slice(-scale)}`:v;};
  return {debit:format(debit),credit:format(credit),balanced:debit===credit&&debit>0n};
}

export function createJournalUi({api,form,field,refresh}){
  let bankId='',bank,directory,settings,mode='journals',offset=0,currentView=()=>false,choices=[],lineIndex=0,scale=2;
  const size=25;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="journal-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const options=(items,value)=>items.map(i=>`<option value="${e(i.value)}" ${i.value===value?'selected':''}>${e(i.label)}</option>`).join('');
  const lineTable=entry=>`<div class="table-wrap field full"><table><thead><tr><th>${e(t('Ledger account'))}</th><th>${e(t('Side'))}</th><th>${e(t('Amount'))}</th></tr></thead><tbody>${entry.lines.map(l=>`<tr><td>${e(choices.find(a=>a.id===l.ledgerId)?.code||l.ledgerId)}</td><td>${e(t(l.side==='DEBIT'?'Debit':'Credit'))}</td><td>${e(l.amount)}</td></tr>`).join('')}</tbody></table></div>`;
  async function render(host,isCurrent){
    currentView=isCurrent;
    const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    let selected=banks.items.find(b=>b.id===bankId);
    if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!selected){host.innerHTML=`<h1>${e(t('Journals & balances'))}</h1><section class="card empty">${e(t('No banks registered yet.'))}</section>`;return;}
    const [result,config]=await Promise.all([api(`/admin/tenants/${bankId}/journals?limit=${size}&offset=${mode==='journals'?offset:0}`),api(`/admin/tenants/${bankId}/settings`)]);
    const balances=mode==='balances'&&result.storageReady?await api(`/admin/tenants/${bankId}/journals/balances?limit=${size}&offset=${offset}`):null;
    if(!isCurrent())return;bank=selected;directory=result;settings=config;
    const canWrite=bank.status==='ACTIVE'&&result.storageReady;
    const rows=mode==='journals'?result.items:balances?.items||[];
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Accounting foundation'))}</p><h1>${e(t('Journals & balances'))}</h1><p class="intro">${e(t('Balanced entries. Independent approval. Traceable balances.'))}</p></div>${button('New journal','new','',!canWrite)}</div>
      <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="journal-bank">${options(banks.items.map(b=>({value:b.id,label:`${b.displayName} · ${b.bankCode}`})),bankId)}</select></label><span>${e(t('Business date'))}: ${e(config.businessDate)}</span></div>
      <div class="toolbar">${button('Journal history','history')}${button('Ledger balances','balances')}${button('Refresh','refresh')}</div>
      ${!result.storageReady?`<section class="card empty"><h2>${e(t('Prepare journal storage'))}</h2><p>${e(t('Upgrade this bank’s accounting tables before posting.'))}</p>${button('Prepare journal storage','prepare','',bank.status!=='ACTIVE')}</section>`:
      `<section class="card"><div class="panel-title"><h2>${e(t(mode==='journals'?'Journal history':'Ledger balances'))}</h2></div><div class="table-wrap chart-table"><table><thead><tr>${(mode==='journals'?['Reference / Narration','Business date','Currency','Posting status','']:['Ledger account','Legal entity','Currency','Total debits','Total credits','Net debit']).map(h=>`<th scope="col">${e(t(h))}</th>`).join('')}</tr></thead><tbody>${rows.map(row=>mode==='journals'?`<tr><td><strong>${e(row.entry.reference)}</strong><span>${e(row.entry.narration)}</span>${row.entry.reversalOf?`<small>${e(t('Reversal of'))} ${e(row.entry.reversalOf)}</small>`:''}</td><td>${e(row.entry.businessDate)}</td><td>${e(row.entry.currency)}</td><td><span class="status ${row.postingStatus==='POSTED'?'active':'pending'}">${e(t(row.postingStatus==='POSTED'?'Posted':'Awaiting posting'))}</span>${row.failureCode?`<small>${e(errorMessage(row.failureCode))}</small>`:''}</td><td>${button('Details','details',row.entry.id)}${row.postingStatus==='POSTED'?(row.entry.reversalOf||row.funding?'':button('Reverse','reverse',row.entry.id,!canWrite)):button('Retry posting','retry',row.entry.id,!canWrite)}</td></tr>`:
      `<tr><td><strong>${e(row.code)}</strong><span>${e(row.name)}</span></td><td>${e(bank.legalEntities.find(x=>x.id===row.legalEntityId)?.legalName||row.legalEntityId)}</td><td>${e(row.currency)}</td><td>${e(row.debit)}</td><td>${e(row.credit)}</td><td>${e(row.netDebit)}</td></tr>`).join('')||`<tr><td colspan="6" class="empty">${e(t('No entries yet.'))}</td></tr>`}</tbody></table></div>
      <div class="pagination"><span>${rows.length?offset+1:0}–${offset+rows.length}</span><div>${button('Previous','previous','',offset===0)}${button('Next','next','',mode==='journals'?offset+size>=result.total:rows.length<size)}</div></div></section>`}
      <p class="approval-note">${e(t('Approval does not mean posted. Only posted journals affect balances.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>
      <p class="reference-note">${e(t('Each journal uses one legal entity and currency. Net debit is total debits minus total credits; a negative value is a credit balance.'))}</p>`;
  }
  function readLines(){return [...document.querySelectorAll('.journal-line')].map(row=>({ledgerId:row.querySelector('select[data-ledger]').value,side:row.querySelector('select[data-side]').value,amount:row.querySelector('input[data-amount]').value}));}
  function updateTotals(){const totals=journalTotals(readLines(),scale);document.querySelector('#journal-totals').textContent=totals?`${t('Debit')}: ${totals.debit} · ${t('Credit')}: ${totals.credit} · ${t(totals.balanced?'Balanced':'Not balanced')}`:t('Enter positive amounts with the currency’s decimal precision.');}
  function addLine(side='DEBIT'){
    const i=++lineIndex;
    document.querySelector('#journal-lines').insertAdjacentHTML('beforeend',`<div class="journal-line" data-line="${i}"><label class="field">${e(t('Ledger account'))} ${i}<select data-ledger required>${options(choices.map(a=>({value:a.id,label:`${a.code} — ${a.name}`})))}</select></label><label class="field">${e(t('Side'))} ${i}<select data-side>${options([{value:'DEBIT',label:t('Debit')},{value:'CREDIT',label:t('Credit')}],side)}</select></label><label class="field">${e(t('Amount'))} ${i}<input data-amount inputmode="decimal" required maxlength="25" pattern="[0-9]{1,18}(\\.[0-9]{1,6})?"></label>${button('Remove','remove-line',String(i))}</div>`);
    updateTotals();
  }
  async function loadAccounts(target){
    let rows=[],page;do{page=await api(`/admin/tenants/${target}/ledger-accounts?limit=100&offset=${rows.length}`);rows.push(...page.items);}while(rows.length<page.total&&page.items.length);
    return rows.filter(r=>r.delivery==='APPLIED'&&r.definition.active).map(r=>r.definition);
  }
  async function action(name,id){
    if(name==='add-line'){if(readLines().length<100)addLine();return;}
    if(name==='remove-line'){if(readLines().length>2)document.querySelector(`[data-line="${id}"]`)?.remove();updateTotals();return;}
    if(name==='history'||name==='balances'){mode=name==='history'?'journals':'balances';offset=0;await refresh();return;}
    if(name==='refresh'){await refresh();return;}
    if(name==='next'||name==='previous'){offset=Math.max(0,offset+(name==='next'?size:-size));await refresh();return;}
    if(!bank||!currentView())return;
    const target=bankId,version=directory.version,viewIsCurrent=currentView;
    const row=directory.items.find(r=>r.entry.id===id);
    if(name==='details'&&row){choices=await loadAccounts(target);if(!viewIsCurrent())return;form('Journal details',`<p class="field full">${e(row.entry.reference)} · ${e(row.entry.narration)}<br>${e(row.entry.businessDate)} · ${e(row.entry.currency)} · ${e(t(row.postingStatus==='POSTED'?'Posted':'Awaiting posting'))}</p>`+lineTable(row.entry),async()=>{},'Done');return;}
    if(bank.status!=='ACTIVE')return;
    if(name==='prepare'){form('Prepare journal storage',field('reason','Reason for this change','',{full:true,max:500}),d=>api(`/admin/tenants/${target}/ledger-accounts/prepare`,{method:'POST',body:{expectedVersion:version,reason:d.reason}}),'Prepare journal storage');return;}
    if(!directory.storageReady)return;
    if(name==='retry'){await api(`/admin/tenants/${target}/journals/${id}/retry`,{method:'POST'});await refresh();return;}
    let key=crypto.randomUUID(),previous='';
    const submit=(path,body)=>{const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;return api(path,{method:'POST',headers:{'Idempotency-Key':key},body});};
    if(name==='reverse'&&!row?.funding&&row?.postingStatus==='POSTED'&&!row.entry.reversalOf){
      form('Reverse journal',`<p class="field full">${e(t('Reverse all original lines on the current business date. Another administrator must approve.'))}<br>${e(row.entry.reference)} · ${e(settings.businessDate)}</p>`+field('reference','Reference','',{max:80})+field('reason','Reason for this change','',{full:true,max:500}),d=>submit(`/admin/tenants/${target}/journals/${id}/reverse`,{expectedVersion:version,reference:d.reference,reason:d.reason}),'Submit for approval');return;
    }
    if(name!=='new')return;
    const accounts=await loadAccounts(target);if(!viewIsCurrent())return;
    const groups=[...new Set(accounts.map(a=>`${a.legalEntityId}|${a.currency}`))].filter(k=>accounts.filter(a=>`${a.legalEntityId}|${a.currency}`===k).length>=2);
    if(!groups.length)throw new Error(t('Prepare two active, delivered ledger accounts in the same legal entity and currency first.'));
    const config=structuredClone(settings),entities=structuredClone(bank.legalEntities);
    form('New journal',field('scope','Legal entity / Currency',groups[0],{full:true,choices:groups.map(k=>({value:k,label:`${entities.find(e=>e.id===k.split('|')[0])?.legalName} · ${k.split('|')[1]}`}))})+field('reference','Reference','',{max:80})+`<div class="field">${e(t('Business date'))}<strong>${e(config.businessDate)}</strong></div>`+field('narration','Narration','',{full:true,max:500})+`<div class="field full" id="journal-lines"></div><div class="field full">${button('Add line','add-line')}<p id="journal-totals" aria-live="polite"></p></div>`+field('reason','Reason for this change','',{full:true,max:500}),d=>{
      const lines=readLines(),totals=journalTotals(lines,scale);
      if(!totals?.balanced)throw new Error(t('Debits and credits must balance.'));
      const [legalEntityId,currency]=d.scope.split('|');
      return submit(`/admin/tenants/${target}/journals`,{expectedVersion:version,reference:d.reference,narration:d.narration,businessDate:config.businessDate,legalEntityId,currency,lines,reversalOf:null,reason:d.reason});
    },'Submit for approval');
    const select=document.querySelector('[name="scope"]');
    const reset=()=>{const [entity,currency]=select.value.split('|');choices=accounts.filter(a=>a.legalEntityId===entity&&a.currency===currency);scale=new Intl.NumberFormat('en',{style:'currency',currency}).resolvedOptions().maximumFractionDigits;lineIndex=0;document.querySelector('#journal-lines').innerHTML='';addLine();addLine('CREDIT');document.querySelectorAll('[data-ledger]')[1].value=choices[1].id;};
    select.addEventListener('change',reset);document.querySelector('#journal-lines').addEventListener('input',updateTotals);reset();
  }
  return {render,action,selectBank(id){bankId=id;offset=0;}};
}
