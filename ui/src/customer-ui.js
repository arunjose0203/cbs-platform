import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

export const customerFields={fullName:'Full name',birthDate:'Date of birth',nationality:'Nationality',residenceCountry:'Country of residence',address:'Residential address',phone:'Phone',email:'Email',documentType:'Document type',documentCountry:'Document issuing country',documentNumber:'Document number',documentExpiry:'Document expiry',documentReference:'Document reference',state:'Customer status',reviewReference:'Manual review reference',duplicateReview:'Duplicate review explanation'};
const statusName=s=>({PROSPECT:'Prospect',ACTIVE:'Active',BLOCKED:'Blocked'})[s]||s;
export function createCustomerUi({api,form,field,refresh}){
  let bankId='',bank,data,search='',state='',offset=0,generation=0,current=()=>false;
  const button=(label,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="customer-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(t(label))}</button>`;
  const note=text=>`<p class="field full reference-note">${e(t(text))}</p>`;
  async function render(host,isCurrent){
    current=isCurrent;generation++;const banks=await api('/admin/tenants?limit=100');if(!isCurrent())return;
    if(!bankId)bankId=banks.items.find(b=>b.status==='ACTIVE')?.id||banks.items[0]?.id;
    let selected=banks.items.find(b=>b.id===bankId);if(bankId&&!selected){selected=await api(`/admin/tenants/${bankId}`);banks.items.push(selected);}
    if(!isCurrent())return;if(!selected){host.innerHTML=`<h1>${e(t('Customers'))}</h1><p>${e(t('No banks registered yet.'))}</p>`;return;}
    const q=new URLSearchParams({search,limit:25,offset});if(state)q.set('state',state);
    const result=await api(`/admin/tenants/${bankId}/customers?${q}`);if(!isCurrent())return;bank=selected;data=result;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Individual onboarding'))}</p><h1>${e(t('Customers'))}</h1><p>${e(t('Register individuals, review identity references and approve customer changes.'))}</p></div>${button(result.storageReady?'Register customer':'Prepare customer storage',result.storageReady?'new':'prepare','',bank.status!=='ACTIVE')}</div>
    <div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="customer-bank">${banks.items.map(b=>`<option value="${e(b.id)}" ${b.id===bankId?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div>
    ${!result.storageReady?note('Prepare this bank’s customer tables before onboarding.'):''}
    <div class="toolbar"><label class="field">${e(t('Search by name or customer number'))}<input id="customer-search" maxlength="100" value="${e(search)}"></label><label class="field">${e(t('Customer status'))}<select id="customer-state"><option value="">${e(t('All statuses'))}</option>${['PROSPECT','ACTIVE','BLOCKED'].map(s=>`<option value="${s}" ${s===state?'selected':''}>${e(t(statusName(s)))}</option>`).join('')}</select></label>${button('Search','search')}${button('Refresh','refresh')}</div>
    <section class="card"><div class="panel-title"><h2>${e(t('Approved customer records'))}</h2><span>${result.total}</span></div><div class="table-wrap"><table><thead><tr>${['Customer','Customer status','Delivery',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.items.map(r=>`<tr><td><strong>${e(r.fullName)}</strong><small>${e(r.customerNumber)}</small></td><td>${e(t(statusName(r.state)))}</td><td>${e(t(r.delivery==='APPLIED'?'Applied':'Awaiting delivery'))}${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td><div class="actions">${button('Details','details',r.id)+(r.type==='ORGANIZATION'?button('Directors & owners','parties',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED'):'')}${button('Manage','edit',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED')}${r.delivery!=='APPLIED'?button('Retry delivery','retry',r.requestId,bank.status!=='ACTIVE'):''}</div></td></tr>`).join('')||`<tr><td colspan="4" class="empty">${e(t('No approved customers yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=result.total)}</div></section>
    <p class="reference-note">${e(t('Changes require another administrator’s approval and successful delivery to the bank database.'))} <button class="link-button" data-view="approvals">${e(t('Approval Inbox'))} →</button></p>
    <p class="reference-note">${e(t('Activation records a manual review, not automated identity or sanctions verification. Use Deposit accounts to open an account for an approved active customer.'))}</p>`;
  }
  async function action(name,id){
    if(!current())return;
    if(name==='refresh'){await refresh();return;}
    if(name==='search'){search=document.querySelector('#customer-search').value.trim();state=document.querySelector('#customer-state').value;offset=0;await refresh();return;}
    if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
    const target=bankId,version=data.version,epoch=generation;
    if(name==='retry'&&bank.status==='ACTIVE'){await api(`/admin/tenants/${target}/customers/${id}/retry`,{method:'POST'});await refresh();return;}
    if(name==='prepare'&&bank.status==='ACTIVE'){form('Prepare customer storage',field('reason','Reason for this change','',{full:true,max:500}),d=>api(`/admin/tenants/${target}/customers/prepare`,{method:'POST',body:{expectedVersion:version,reason:d.reason}}),'Prepare customer storage');return;}
    if(name==='parties'){
      const [current0,people]=await Promise.all([api(`/admin/tenants/${target}/customers/${id}/parties`),api(`/admin/tenants/${target}/customers?limit=100&offset=0`)]);if(epoch!==generation||!current())return;
      const persons=people.items.filter(x=>x.id!==id&&x.type!=='ORGANIZATION'&&x.delivery==='APPLIED');const choices=[{value:'',label:t('None')},...persons.map(x=>({value:x.id,label:x.customerNumber+' · '+x.fullName}))];
      const rows=[0,1,2,3,4].map(i=>{const c=current0[i]||{};return field('person'+i,t('Person')+' '+(i+1),c.personId||'',{choices,required:false})+field('role'+i,t('Role'),c.role||'DIRECTOR',{choices:[['DIRECTOR','Director'],['BENEFICIAL_OWNER','Beneficial owner'],['REPRESENTATIVE','Representative']].map(([value,label])=>({value,label:t(label)})),required:false})+field('share'+i,t('Ownership %'),c.ownershipPercent||'',{required:false,max:6,pattern:'[0-9]{1,3}(\\.[0-9]{1,2})?'})}).join('');
      let key=crypto.randomUUID(),previous='';
      form('Directors & owners',note('Record every director and each beneficial owner with their ownership percentage. At least one director is required before the organization can transact. Ownership applies to beneficial owners only.')+rows+field('reason','Reason and evidence','',{full:true,max:500}),d=>{
        const parties=[0,1,2,3,4].filter(i=>d['person'+i]).map(i=>({personId:d['person'+i],role:d['role'+i],ownershipPercent:d['role'+i]==='BENEFICIAL_OWNER'?d['share'+i]:null}));
        const body={expectedVersion:version,organizationId:id,parties,reason:d.reason};const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/customers/parties`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');return;
    }
    let row,p={};
    if(name==='details'||name==='edit'){
      row=await api(`/admin/tenants/${target}/customers/${id}`);if(epoch!==generation||!current())return;p=row.definition.profile;
      if(name==='details'){
        form('Customer details',`<p class="field full">${e(row.definition.customerNumber)} · ${e(t(row.delivery==='APPLIED'?'Applied':'Awaiting delivery'))}</p><dl class="metadata field full">${Object.entries(customerFields).filter(([key])=>key in p).map(([key,label])=>`<div><dt>${e(t(label))}</dt><dd>${e(key==='state'?t(statusName(p[key])):p[key]||'—')}</dd></div>`).join('')}</dl>`,async()=>{},'Done');return;
      }
    }
    if(!['new','edit'].includes(name)||bank.status!=='ACTIVE'||!data.storageReady||(row&&row.delivery!=='APPLIED'))return;
    const f=(key,opts={})=>field(key,customerFields[key],p[key]??'',opts);
    let key=crypto.randomUUID(),previous='';
    form(row?'Manage customer':'Register customer',note('Use document references from your approved document repository. Do not enter passwords or upload documents here.')+
      (row?note(t(p.type==='ORGANIZATION'?'Organization':'Individual')):field('type','Customer type','INDIVIDUAL',{choices:[{value:'INDIVIDUAL',label:t('Individual')},{value:'ORGANIZATION',label:t('Organization')}],help:t('For an organization, enter the registered name, the incorporation date as date of birth, the country of registration as nationality, and the registration certificate as the document.')}))+
      f('fullName',{full:true,max:160})+f('birthDate',{type:'date'})+f('nationality',{max:2,pattern:'[A-Z]{2}'})+f('residenceCountry',{max:2,pattern:'[A-Z]{2}'})+
      f('address',{full:true,max:300})+f('phone',{max:16,pattern:'\\+[1-9][0-9]{6,14}',help:t('Use international format, for example +254700000000.')})+f('email',{type:'email',required:false,max:200})+
      f('documentType',{choices:[{value:'NATIONAL_ID',label:t('National ID')},{value:'PASSPORT',label:t('Passport')},{value:'REGISTRATION_CERTIFICATE',label:t('Registration certificate')}]})+f('documentCountry',{max:2,pattern:'[A-Z]{2}'})+f('documentNumber',{max:40,pattern:'[A-Za-z0-9 \\-]{3,40}'})+f('documentExpiry',{type:'date',required:false})+f('documentReference',{full:true,max:160})+
      f('state',{choices:['PROSPECT','ACTIVE','BLOCKED'].map(s=>({value:s,label:t(statusName(s))}))})+f('reviewReference',{max:160,required:false})+
      note('Active status requires a manual review reference and an unexpired document, where an expiry date applies.')+
      field('duplicateReview','Duplicate review explanation','',{full:true,max:500,required:false,help:t('If the name and date of birth match another record, investigate and explain why this is a different person. Matching identity documents cannot be duplicated.')})+
      field('reason','Reason for this change','',{full:true,max:500}),d=>{
        const profile={};for(const k of Object.keys(customerFields))if(k!=='duplicateReview')profile[k]=d[k];profile.documentExpiry=d.documentExpiry||null;const type=row?(p.type||'INDIVIDUAL'):d.type;if(type==='ORGANIZATION')profile.type='ORGANIZATION';
        const body={expectedVersion:version,customerId:row?.definition.id??null,profile,duplicateReview:d.duplicateReview,reason:d.reason};const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
        return api(`/admin/tenants/${target}/customers`,{method:'POST',headers:{'Idempotency-Key':key},body});
      },'Submit for approval');
  }
  return {render,action,selectBank(id){bankId=id;offset=0;search='';state='';generation++;}};
}
