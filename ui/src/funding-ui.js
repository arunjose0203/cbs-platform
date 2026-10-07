import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';
export function createFundingUi({api,form,field,refresh}){
 let selected='',offset=0;
 const note=text=>`<p class="field full reference-note">${e(t(text))}</p>`;
 const button=(label,action,id,disabled=false)=>`<button type="button" class="button secondary" data-action="account-funding-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
 async function action(name,id,bankId,isCurrent){
  const base=`/admin/tenants/${bankId}`;
  if(name==='retry'){await api(base+'/funding/'+id+'/retry',{method:'POST'});if(!isCurrent())return;name='view';id=selected;}
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));name='view';id=selected;}else if(selected!==id){selected=id;offset=0;}
  const result=await api(base+'/funding/'+id+'?limit=25&offset='+offset);if(!isCurrent())return;
  const a=result.account.definition,b=result.balance;const ready=result.storageReady;
  if(!ready){form('Prepare funding storage',note('Upgrade this bank’s storage before funding accounts.')+field('reason','Reason for this change','',{full:true,max:500}),d=>api(base+'/accounts/prepare',{method:'POST',body:{expectedVersion:result.version,reason:d.reason}}),'Prepare funding storage');return;}
  if(name==='view'){
   const canFund=result.account.delivery==='APPLIED'&&['AWAITING_FUNDING','ACTIVE'].includes(a.state)&&b.reconciled;
   form('Balance and funding',`<p class="field full funding-heading">${e(a.name)} · ${e(a.accountNumber)}</p><dl class="metadata field full funding-balances"><div><dt>${e(t('Posted customer balance'))}</dt><dd>${e(b.postedBalance)} ${e(b.currency)}</dd></div><div><dt>${e(t('Liability ledger balance'))}</dt><dd>${e(b.ledgerBalance)} ${e(b.currency)}</dd></div><div><dt>${e(t('Reconciliation'))}</dt><dd>${e(t(b.reconciled?'Matched':'Mismatch — investigate before posting'))}</dd></div></dl><div class="field full">${button('Record funding','new',a.id,!canFund)}</div><div class="table-wrap field full"><table><thead><tr>${['Reference','Business date','Amount','Posting status',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.items.map(r=>`<tr><td>${e(r.entry.journal.reference)}</td><td>${e(r.entry.journal.businessDate)}</td><td>${e(r.entry.amount)} ${e(r.entry.journal.currency)}</td><td>${e(t(r.status==='POSTED'?'Posted':'Awaiting confirmation'))}${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td>${r.status==='POSTED'?'':button('Retry posting','retry',r.entry.id)}</td></tr>`).join('')||`<tr><td colspan="5">${e(t('No funding posted yet.'))}</td></tr>`}</tbody></table></div><div class="field full actions funding-pagination">${button('Previous','previous',a.id,offset===0)}${button('Next','next',a.id,offset+25>=result.total)}</div>`+note('The posted balance includes confirmed deposits and debits. Use Account operations to inspect holds and withdrawable funds.'),async()=>{},'Done');return;
  }
  if(name!=='new'||result.account.delivery!=='APPLIED'||!['AWAITING_FUNDING','ACTIVE'].includes(a.state)||!b.reconciled)return;
  const [ledgers,chart,settings]=await Promise.all([api(base+'/ledger-accounts?active=true&currency='+a.terms.currency+'&limit=100'),api(base+'/chart-of-accounts?category=ASSET&active=true&kind=POSTING&limit=100'),api(base+'/settings')]);if(!isCurrent())return;
  const live=ledgers.items.filter(r=>r.delivery==='APPLIED'&&r.definition.legalEntityId===a.legalEntityId).map(r=>r.definition);
  const assets=new Set(chart.items.filter(c=>c.normalBalance==='DEBIT').map(c=>c.id));
  const source=live.filter(l=>assets.has(l.chartAccountId)),liability=live.filter(l=>l.chartAccountId===a.terms.liabilityAccountId&&(!b.liabilityLedgerId||l.id===b.liabilityLedgerId));
  if(!source.length||!liability.length){form('Funding prerequisites',note('Approve and deliver an asset ledger and a dedicated liability ledger matching this account’s currency, legal entity and product chart mapping.'),async()=>{},'Done');return;}
  let key=crypto.randomUUID(),previous='';const choices=items=>items.map(l=>({value:l.id,label:l.code+' · '+l.name}));
  form('Record funding',`<p class="field full">${e(a.accountNumber)} · ${e(a.terms.currency)} · ${e(t('Minimum opening amount'))}: ${e(a.terms.minimumOpening)}</p>`+note('Record only funds already received and verified. This entry does not initiate a bank transfer.')+
    field('sourceLedgerId','Receiving asset ledger','',{full:true,choices:choices(source)})+field('liabilityLedgerId','Dedicated customer liability ledger',b.liabilityLedgerId||'',{full:true,choices:choices(liability)})+
    note('The first deposit binds an unused liability ledger to this customer account. Later deposits use the same ledger. Selectors show the first 100 matching ledgers.')+
    field('amount','Amount','',{max:25,pattern:'[0-9]+(\\.[0-9]+)?'})+field('businessDate','Business date',settings.businessDate,{type:'date'})+
    field('reference','Receipt reference','',{full:true,max:80})+field('reason','Receipt evidence and reason','',{full:true,max:500}),d=>{
      const body={expectedVersion:result.version,accountId:a.id,sourceLedgerId:d.sourceLedgerId,liabilityLedgerId:d.liabilityLedgerId,amount:d.amount,businessDate:d.businessDate,reference:d.reference,reason:d.reason};
      const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
      return api(base+'/funding',{method:'POST',headers:{'Idempotency-Key':key},body});
    },'Submit for approval');
 }
 return {action};
}
