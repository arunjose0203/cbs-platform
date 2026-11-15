import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

const CHANNELS=['BRANCH','PHONE','EMAIL','LETTER','MOBILE','SOCIAL_MEDIA','REGULATOR'];
const CATEGORIES=['SERVICE','FEES_AND_CHARGES','TRANSACTION_DISPUTE','MOBILE_MONEY','CASH','ACCOUNT','LOAN','FRAUD','DATA_PRIVACY','STAFF_CONDUCT','OTHER'];
const FILTERS=['OPEN','OVERDUE','UNASSIGNED','ESCALATED','REDRESS_PENDING','RESOLVED','ALL'];
const OUTCOMES=['UPHELD','PARTLY_UPHELD','NOT_UPHELD','WITHDRAWN'];
const REDRESS=['NONE','APOLOGY','EXPLANATION','CORRECTION','REFUND','COMPENSATION'];
const AMOUNT='[0-9]{1,18}(\\.[0-9]{1,6})?';

/** Complaints and disputes (Round 20): register, handle, propose a resolution for independent approval, export the register. */
export function createComplaintsUi({api,form,field,refresh,download}) {
  let bankId='',bank,data,summary,banks=[],offset=0,status='OPEN',current=()=>false;
  const word=value=>t(String(value).replaceAll('_',' '));
  const choices=values=>values.map(value=>({value,label:word(value)}));
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="complaints-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const tag=label=>` <span class="tag alert">${e(t(label))}</span>`;
  const money=(amount,currency)=>amount?`${e(amount)} ${e(currency)}`:'—';
  const base=()=>'/admin/tenants/'+bankId+'/complaints';
  const monthBefore=day=>{const d=new Date(day+'T00:00:00Z');d.setUTCDate(d.getUTCDate()-30);return d.toISOString().slice(0,10);};

  async function render(host,isCurrent) {
    current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!isCurrent())return;banks=directory.items;
    if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
    if(!bankId){host.innerHTML=`<h1>${e(t('Complaints & disputes'))}</h1><p>${e(t('Register a bank first.'))}</p>`;return;}
    const selected=await api('/admin/tenants/'+bankId);
    const page=await api(`${base()}?status=${status}&limit=25&offset=${offset}`);
    // The summary covers the last 30 days up to the bank's own date, which the server reports.
    const counts=await api(`${base()}/summary?from=${monthBefore(page.today)}&to=${page.today}`);if(!isCurrent())return;
    bank=selected;data=page;summary=counts;
    const stat=(label,value)=>`<div><strong>${e(value)}</strong><small>${e(t(label))}</small></div>`;
    const rows=data.items.map(c=>`<tr>
      <td><strong>${e(c.reference)}</strong><small>${e(c.customerNumber)}${c.accountNumber?' · '+e(c.accountNumber):''}</small></td>
      <td>${e(word(c.category))}<br><small>${e(word(c.channel))}</small></td>
      <td>${e(c.summary)}<br><small>${e(t('Disputed'))}: ${money(c.disputedAmount,c.disputedCurrency)}</small></td>
      <td>${e(c.receivedOn)}</td>
      <td>${e(t('Acknowledge by'))} ${e(c.acknowledgeDue)}${c.acknowledgementOverdue?tag('OVERDUE'):''}<br><small>${e(t('Resolve by'))} ${e(c.resolveDue)}${c.resolutionOverdue?tag('OVERDUE'):''}</small></td>
      <td>${e(t('Level'))} ${e(c.escalationLevel)}<br><small>${e(c.assignee||t('Unassigned'))}</small></td>
      <td><span class="status ${c.status==='OPEN'?'pending':'active'}">${e(word(c.outcome||c.status))}</span>${c.resolutionPending?tag('Awaiting approval'):''}${c.redressPending?tag('Redress pending'):''}</td>
      <td>${button('Open','detail',c.id)}</td></tr>`).join('');
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Customer care & redress'))}</p><h1>${e(t('Complaints & disputes'))}</h1><p>${e(bank.displayName)} · ${e(word(status))}</p></div>
      <div class="actions">${button('Choose bank / filter','filter')}${button('Refresh','refresh')}${button('Export register','export')}${button('Register complaint','register','',bank.status!=='ACTIVE')}</div></div>
      <section class="card compliance-summary"><h2>${e(t('Last 30 days'))}</h2><div class="actions">${stat('Received',summary.received)}${stat('Open',summary.open)}${stat('Past resolution date',summary.overdueOpen)}${stat('Acknowledged late',summary.acknowledgedLate)}${stat('Resolved in time',summary.resolvedWithinDue)}${stat('Resolved late',summary.resolvedLate)}${stat('Redress pending',summary.redressPending)}</div>
      <p>${e(t('A complaint is closed only when another administrator approves the proposed resolution. Refunds and compensation are paid through the normal account or journal requests and then linked here.'))}</p></section>
      <section class="card section-gap"><div class="table-wrap"><table><thead><tr><th>${e(t('Complaint'))}</th><th>${e(t('Category'))}</th><th>${e(t('Summary'))}</th><th>${e(t('Received'))}</th><th>${e(t('Deadlines'))}</th><th>${e(t('Handling'))}</th><th>${e(t('Status'))}</th><th></th></tr></thead>
      <tbody>${rows||`<tr><td colspan="8" class="empty">${e(t('No complaints match this filter.'))}</td></tr>`}</tbody></table></div>
      <div class="pagination"><span>${e(data.total)} ${e(t('complaints'))}</span>${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=data.total)}</div></section>`;
  }

  async function action(name,id) {
    if(!current())return;
    if(name==='refresh'){await refresh();return;}
    if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    if(name==='filter'){
      form('Choose bank and complaints',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))})+field('status','Complaint filter',status,{choices:choices(FILTERS)}),async v=>{bankId=v.bank;status=v.status;offset=0;},'Apply');return;
    }
    if(name==='export'){
      form('Export complaints register',`<p class="field full">${e(t('The register lists every complaint received in the period, without the customer’s account of events or handling notes. At most 366 days.'))}</p>`+field('from','From',monthBefore(data.today),{type:'date'})+field('to','To',data.today,{type:'date'}),async v=>{
        const blob=await api(`${base()}/register?from=${v.from}&to=${v.to}`,{responseType:'blob'});download(blob,`complaints-register-${v.from}-${v.to}.csv`);
      },'Download CSV');return;
    }
    if(name==='register'){
      const customers=await api('/admin/tenants/'+bankId+'/customers?limit=100&offset=0');if(!current())return;
      if(!customers.items.length)throw Error(t('Create a customer profile first.'));const key=crypto.randomUUID();
      form('Register complaint',`<p class="field full">${e(t('Keep the summary free of personal data: it appears in lists and the register. The customer’s account is stored encrypted and every reading is recorded.'))}</p>`
        +field('customerId','Customer',customers.items[0].id,{choices:customers.items.map(c=>({value:c.id,label:c.customerNumber+' · '+c.fullName})),help:t('First 100 customer profiles in this bank.')})
        +field('channel','Received through','BRANCH',{choices:choices(CHANNELS)})+field('category','Category','SERVICE',{choices:choices(CATEGORIES)})
        +field('receivedOn','Date received',data.today,{type:'date'})+field('summary','Summary','',{full:true,max:200})
        +field('details','Customer’s account of what happened','',{full:true,max:2000})
        +field('transactionReference','Transaction reference','',{required:false,max:120,pattern:'[A-Za-z0-9][A-Za-z0-9._/-]*'})
        +field('disputedAmount','Disputed amount','',{required:false,max:25,pattern:AMOUNT})+field('disputedCurrency','Currency','',{required:false,max:3,pattern:'[A-Z]{3}'}),
        v=>api(base(),{method:'POST',headers:{'Idempotency-Key':key},body:{customerId:v.customerId,channel:v.channel,category:v.category,receivedOn:v.receivedOn,summary:v.summary.trim(),details:v.details.trim(),
          ...(v.transactionReference?{transactionReference:v.transactionReference.trim()}:{}),...(v.disputedAmount?{disputedAmount:v.disputedAmount,disputedCurrency:v.disputedCurrency}:{})}}),'Register complaint');return;
    }
    const detail=await api(base()+'/'+id);if(!current())return;const c=detail.complaint,open=c.status==='OPEN',free=open&&!c.resolutionPending;
    if(name==='detail'){
      const events=detail.events.map(v=>`<tr><td>${e(v.occurredAt)}</td><td>${e(word(v.action))}<br>${e(v.actorSubject)}</td><td>${e(v.note)}${v.details.requestId?`<br><small class="mono">${e(v.details.requestId)}</small>`:''}</td></tr>`).join('');
      form('Complaint history',`<div class="field full compliance-detail"><p><strong>${e(c.reference)}</strong> · ${e(c.customerNumber)} · ${e(word(c.category))} · ${e(word(c.outcome||c.status))}</p>
        <p>${e(c.summary)}</p><p>${e(detail.details)}</p>
        <p>${e(t('Disputed'))}: ${money(c.disputedAmount,c.disputedCurrency)} · ${e(t('Redress'))}: ${e(c.redress?word(c.redress):'—')} ${c.redressAmount?money(c.redressAmount,c.redressCurrency):''}${c.redressPending?tag('Redress pending'):''}</p>
        <p>${e(t('Acknowledge by'))} ${e(c.acknowledgeDue)}${c.acknowledgedOn?' · '+e(t('Acknowledged'))+' '+e(c.acknowledgedOn):''}${c.acknowledgementOverdue?tag('OVERDUE'):''} · ${e(t('Resolve by'))} ${e(c.resolveDue)}${c.resolutionOverdue?tag('OVERDUE'):''}</p>
        <p>${e(t('Level'))} ${e(c.escalationLevel)} · ${e(c.assignee||t('Unassigned'))}${c.resolutionPending?tag('Awaiting approval'):''}</p>
        <div class="actions">${free&&!c.acknowledgedOn?button('Acknowledge','acknowledge',id):''}${free?button('Assign','assign',id):''}${free||!open?button('Add note','note',id):''}${free&&c.escalationLevel<3?button('Escalate','escalate',id):''}${free?button('Propose resolution','resolve',id,bank.status!=='ACTIVE'):''}${c.redressPending?button('Link redress payment','redress',id):''}${!open?button('Reopen','reopen',id,bank.status!=='ACTIVE'):''}</div>
        <div class="table-wrap"><table><thead><tr><th>${e(t('Recorded'))}</th><th>${e(t('Action / actor'))}</th><th>${e(t('Note'))}</th></tr></thead><tbody>${events}</tbody></table></div></div>`,async()=>{},'Done');return;
    }
    const step=(title,path,label,help)=>form(title,(help?`<p class="field full">${e(t(help))}</p>`:'')+field('note','Note','',{full:true,max:1000}),v=>api(`${base()}/${id}/${path}`,{method:'POST',body:{caseVersion:c.version,note:v.note.trim()}}),label);
    if(name==='acknowledge'){step('Acknowledge complaint','acknowledgement','Record acknowledgement','Record how and when the customer was told the complaint was received.');return;}
    if(name==='note'){step('Add handling note','notes','Add note');return;}
    if(name==='escalate'){step('Escalate complaint','escalation','Escalate','Escalation moves the complaint one level up and cannot be undone.');return;}
    if(name==='reopen'){step('Reopen complaint','reopening','Reopen','The earlier resolution stays in the history. The complaint moves one level up and gets a new resolution date.');return;}
    if(name==='assign'){
      form('Assign complaint',field('assignee','Assignee staff username',c.assignee||'',{max:200})+field('note','Note','',{full:true,max:1000}),v=>api(`${base()}/${id}/assignment`,{method:'POST',body:{caseVersion:c.version,assignee:v.assignee.trim(),note:v.note.trim()}}),'Assign');return;
    }
    if(name==='redress'){
      form('Link redress payment',`<p class="field full">${e(t('Enter the approved request that paid the customer (account operation, funding, journal, cash, fee or payment). One request can settle one complaint.'))}</p>`+field('requestId','Approved request ID','',{full:true,max:36,pattern:'[0-9a-fA-F-]{36}'})+field('note','Note','',{full:true,max:1000}),
        v=>api(`${base()}/${id}/redress-posting`,{method:'POST',body:{caseVersion:c.version,requestId:v.requestId.trim(),note:v.note.trim()}}),'Link payment');return;
    }
    if(name==='resolve'){
      const key=crypto.randomUUID();
      form('Propose resolution',`<p class="field full">${e(t('Another administrator must approve this resolution before the complaint closes. Refunds and compensation need an amount; they are paid separately and linked afterwards.'))}</p>`
        +field('outcome','Outcome','UPHELD',{choices:choices(OUTCOMES)})+field('redress','Redress','APOLOGY',{choices:choices(REDRESS)})
        +field('redressAmount','Redress amount','',{required:false,max:25,pattern:AMOUNT})+field('redressCurrency','Currency',c.disputedCurrency||'',{required:false,max:3,pattern:'[A-Z]{3}'})
        +field('rootCause','Root cause code','',{max:40,pattern:'[A-Z][A-Z_]{1,39}',help:t('Capital letters and underscores, for example DUPLICATE_FEE.')})
        +field('response','Response to the customer','',{full:true,max:1000})+field('reason','Reason for this decision','',{full:true,max:500}),
        v=>api(`${base()}/resolutions`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:detail.version,complaintId:id,caseVersion:c.version,outcome:v.outcome,redress:v.redress,
          ...(v.redressAmount?{redressAmount:v.redressAmount,redressCurrency:v.redressCurrency}:{}),rootCause:v.rootCause.trim(),response:v.response.trim(),reason:v.reason.trim()}}),'Submit for independent approval');
    }
  }
  return {render,action,selectBank:id=>{bankId=id;offset=0;}};
}
