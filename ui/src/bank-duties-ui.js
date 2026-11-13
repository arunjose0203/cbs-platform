import {escapeHtml as e} from './model.js';

// Bank duties (segregation of duties for platform administrators) and signed access reviews.
export const DUTIES=['ACCESS_ADMINISTRATION','BANK_CONFIGURATION','FINANCE','CUSTOMER_OPERATIONS','PAYMENTS','TREASURY','COMPLIANCE'];
const LEVELS=[{value:'none',label:'None'},{value:'submit',label:'Submit (maker)'},{value:'approve',label:'Approve (checker)'},{value:'both',label:'Submit and approve'}];
const words=value=>String(value).replaceAll('_',' ').toLowerCase();

/** Converts the per-duty choices of the grant form into the API's submit/approve lists. */
export function dutyLists(values){
 const submit=DUTIES.filter(d=>['submit','both'].includes(values[d]));
 const approve=DUTIES.filter(d=>['approve','both'].includes(values[d]));
 return {submit,approve};
}

export function createBankDutiesUi({api,form,field,refresh}) {
 let bankId='',banks=[],data,current=()=>false;
 const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="bankduties-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(label)}</button>`;
 const route=()=>'/admin/tenants/'+bankId+'/bank-duties';
 const post=(path,body,key)=>api(path,{method:'POST',headers:key?{'Idempotency-Key':key}:{},body});
 const dutyText=d=>d?[d.submit.length?'Submit: '+d.submit.map(words).join(', '):'',d.approve.length?'Approve: '+d.approve.map(words).join(', '):'',d.audit?'Audit (read-only, signs reviews)':'',d.expiresAt?'Expires '+d.expiresAt:''].filter(Boolean).map(t=>`<small>${e(t)}</small>`).join(''):'<small>No bank duties</small>';
 const otherText=i=>[...i.lendingRoles.map(r=>'Lending '+words(r)),...i.paymentRoles.map(r=>'Payments '+words(r)),...i.branchRoles.map(r=>'Branch '+r)].map(t=>`<small>${e(t)}</small>`).join('')||'<small>None</small>';

 async function render(host,isCurrent){
  current=isCurrent;
  banks=(await api('/admin/tenants?limit=100')).items;if(!isCurrent())return;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>Bank duties & access review</h1><p>No banks are registered.</p>';return;}
  data=await api(route());if(!isCurrent())return;
  const bankName=banks.find(b=>b.id===bankId)?.displayName||'Bank';
  host.innerHTML=`<div class="heading"><div><p class="eyebrow">Staff & access · ${e(bankName)}</p><h1>Bank duties & access review</h1></div><div class="actions">${button('Choose bank','bank')}${button('Refresh','refresh')}${button('Propose duties','grant')}${button(data.enforced?'Stop enforcing duties':'Enforce duties','policy')}${button('Sign access review','review')}</div></div>`
   +`<section class="card"><h2>Separate who configures, who moves money and who grants access</h2><p>When duties are enforced, a platform administrator needs an approved duty for this bank to submit or approve its requests, and only sees requests for the duties they hold. Submit and approve are separate duties, so the same person cannot be both maker and checker of a change. Lending, payment-operator and branch roles keep their own grants.</p><p>Every grant, revocation and enforcement change needs another administrator's approval, and nobody can approve their own duties. At least two access administrators must remain while duties are enforced. Time-limited duties end automatically at their expiry.</p></section>`
   +`<section class="card section-gap"><h2>Status</h2><p><strong>${data.enforced?'Duties are enforced':'Duties are not enforced'}</strong> · ${e(data.findingCount)} findings</p>${data.bankFindings.map(f=>`<small>${e(words(f))}</small>`).join('')}</section>`
   +`<section class="card section-gap"><h2>Who can do what in this bank</h2><div class="table-wrap"><table><thead><tr><th>Staff</th><th>Bank duties</th><th>Other roles</th><th>Findings</th><th></th></tr></thead><tbody>${data.items.map(i=>`<tr><td>${e(i.displayName)}<small>${e(i.subject)} · ${i.staffActive?'Active':'Disabled'}</small><small>Last seen ${e(i.lastSeen||'never')}</small></td><td>${dutyText(i.duties)}</td><td>${otherText(i)}</td><td>${i.findings.map(f=>`<small>${e(words(f))}</small>`).join('')||'<small>None</small>'}</td><td>${i.duties?button('Replace duties','edit',i.staffId)+button('Revoke','revoke',i.staffId):button('Assign duties','edit',i.staffId)}</td></tr>`).join('')||'<tr><td colspan="5">Nobody holds access to this bank yet.</td></tr>'}</tbody></table></div></section>`
   +`<section class="card section-gap"><h2>Signed access reviews</h2>${data.reviews.map(r=>`<p>${e(r.reviewer)} · ${e(r.findings)} findings<small>${e(r.at)} · ${e(r.statement)}</small><small>Snapshot ${e(r.snapshotHash)}</small></p>`).join('')||'<p>No access review has been signed for this bank.</p>'}</section>`
   +`<section class="card section-gap"><h2>Duty and enforcement history</h2>${data.history.map(h=>`<p>${h.kind==='POLICY'?(h.change.enforced?'Enforcement switched on':'Enforcement switched off'):e(h.displayName||h.change.staffId)+' · '+(h.change.active?'Duties granted / replaced':'Duties revoked')}<small>${e(h.maker)} → ${e(h.checker)} · ${e(h.at)}</small><small>${e(h.change.reason)}</small></p>`).join('')||'<p>No decisions yet.</p>'}</section>`;
 }

 async function action(name,id){
  if(!current())return;
  if(name==='refresh'){await refresh();return;}
  if(name==='bank'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;},'Open');return;}
  const version=data.version,key=crypto.randomUUID();
  if(name==='policy'){
   const on=!data.enforced;
   form(on?'Enforce bank duties':'Stop enforcing bank duties',`<p class="field full">${on?'After approval, platform administrators without a duty for this bank can no longer submit, approve or see its requests. At least two staff must already hold access administration.':'After approval, any platform administrator can again submit and approve this bank\'s requests.'} Another administrator must approve.</p>`+field('reason','Reason','',{full:true,max:500}),v=>post(route()+'/policy',{expectedVersion:version,enforced:on,reason:v.reason},key),'Submit for approval');
   return;
  }
  if(name==='revoke'){
   const item=data.items.find(i=>i.staffId===id);if(!item?.duties)return;
   form('Revoke bank duties',`<p class="field full">Remove all bank duties from ${e(item.displayName)}. Another administrator must approve.</p>`+field('reason','Reason','',{full:true,max:500}),v=>post(route(),{expectedVersion:version,staffId:id,active:false,submit:item.duties.submit,approve:item.duties.approve,audit:item.duties.audit,expiresAt:null,reason:v.reason},key),'Submit revocation');
   return;
  }
  if(name==='grant'||name==='edit'){
   const original=data.items.find(i=>i.staffId===id)?.duties;
   const staff=await api('/admin/staff?limit=100');if(!current())return;
   const level=d=>original?(original.submit.includes(d)&&original.approve.includes(d)?'both':original.submit.includes(d)?'submit':original.approve.includes(d)?'approve':'none'):'none';
   form('Bank duties',`<p class="field full">Replace the complete set of duties. Omitted duties are removed. Leave the expiry empty for a standing duty, or set one for time-limited (break-glass) access.</p>`
    +field('staffId','Staff identity',id||'',{full:true,choices:[{value:'',label:'Select staff'},...staff.items.map(s=>({value:s.id,label:s.displayName+' · '+s.subject}))]})
    +DUTIES.map(d=>field(d,words(d).replace(/^./,c=>c.toUpperCase()),level(d),{choices:LEVELS})).join('')
    +field('audit','Audit (read-only, signs access reviews)',original?.audit?'yes':'no',{choices:[{value:'no',label:'No'},{value:'yes',label:'Yes'}]})
    +field('expiresAt','Expires at (optional, e.g. 2026-12-31T17:00)',original?.expiresAt||'',{required:false})
    +field('reason','Access justification','',{full:true,max:500}),
    v=>{
     const {submit,approve}=dutyLists(v),audit=v.audit==='yes';
     if(!v.staffId)throw Error('Select a staff identity.');
     if(!submit.length&&!approve.length&&!audit)throw Error('Choose at least one duty or audit. Use Revoke to remove all duties.');
     let expiresAt=null;
     if(v.expiresAt){const when=new Date(v.expiresAt);if(Number.isNaN(when.getTime())||when<=new Date())throw Error('Enter a future expiry date and time.');expiresAt=when.toISOString();}
     return post(route(),{expectedVersion:version,staffId:v.staffId,active:true,submit,approve,audit,expiresAt,reason:v.reason},key);
    },'Submit for approval');
   return;
  }
  if(name==='review'){
   const hash=data.snapshotHash,count=data.findingCount;
   form('Sign access review',`<p class="field full">You attest that you reviewed every person and finding shown (${e(count)} findings). The signature is bound to this exact view; if access changes before you sign, refresh and review again. Only staff with the audit duty can sign when duties are enforced.</p><p class="field full"><small>Snapshot ${e(hash)}</small></p>`+field('statement','Review statement','',{full:true,max:500}),v=>post(route()+'/reviews',{snapshotHash:hash,statement:v.statement}),'Sign review');
  }
 }
 return {render,action};
}
