import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

export const customerFields={fullName:'Full name',birthDate:'Date of birth',nationality:'Nationality',residenceCountry:'Country of residence',address:'Residential address',phone:'Phone',email:'Email',documentType:'Document type',documentCountry:'Document issuing country',documentNumber:'Document number',documentExpiry:'Document expiry',documentReference:'Document reference',state:'Customer status',reviewReference:'Manual review reference',duplicateReview:'Duplicate review explanation'};
const statusName=s=>({PROSPECT:'Prospect',ACTIVE:'Active',BLOCKED:'Blocked',DECEASED:'Deceased'})[s]||s;
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
    <div class="toolbar"><label class="field">${e(t('Search by name or customer number'))}<input id="customer-search" maxlength="100" value="${e(search)}"></label><label class="field">${e(t('Customer status'))}<select id="customer-state"><option value="">${e(t('All statuses'))}</option>${['PROSPECT','ACTIVE','BLOCKED','DECEASED'].map(s=>`<option value="${s}" ${s===state?'selected':''}>${e(t(statusName(s)))}</option>`).join('')}</select></label>${button('Search','search')}${button('Refresh','refresh')}</div>
    <section class="card"><div class="panel-title"><h2>${e(t('Approved customer records'))}</h2><span>${result.total}</span></div><div class="table-wrap"><table><thead><tr>${['Customer','Customer status','Delivery',''].map(h=>`<th>${e(t(h))}</th>`).join('')}</tr></thead><tbody>${result.items.map(r=>`<tr><td><strong>${e(r.fullName)}</strong><small>${e(r.customerNumber)}</small></td><td>${e(t(statusName(r.state)))}</td><td>${e(t(r.delivery==='APPLIED'?'Applied':'Awaiting delivery'))}${r.failureCode?`<small>${e(t(errorMessage(r.failureCode)))}</small>`:''}</td><td><div class="actions">${button('Details','details',r.id)+(r.type==='ORGANIZATION'?button('Directors & owners','parties',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED'):'')}${button('Manage','edit',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED')}${button('KYC docs','docs',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED')}${button('KYC review','kyc-review',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED')}${button('Privacy','privacy',r.id,bank.status!=='ACTIVE'||r.delivery!=='APPLIED')}${r.delivery!=='APPLIED'?button('Retry delivery','retry',r.requestId,bank.status!=='ACTIVE'):''}</div></td></tr>`).join('')||`<tr><td colspan="4" class="empty">${e(t('No approved customers yet.'))}</td></tr>`}</tbody></table></div><div class="pagination">${button('Previous','previous','',offset===0)}${button('Next','next','',offset+25>=result.total)}</div></section>
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
    if(name==='docs'){
      const docs=await api(`/admin/tenants/${target}/customers/${id}/documents`);if(epoch!==generation||!current())return;
      const docRows=docs.map(d=>`<tr><td><strong>${e(d.fileName)}</strong><small>${e(d.documentType)}</small></td><td>${e(d.fileSize)} B</td><td>${e(d.retentionUntil)}</td><td><a href="/api/v1/admin/tenants/${target}/customers/${id}/documents/${d.id}/download" target="_blank" download="${e(d.fileName)}">${e(t('Download'))}</a></td></tr>`).join('');
      const docTable=`<div class="table-wrap"><table><thead><tr><th>${e(t('Document'))}</th><th>${e(t('Size'))}</th><th>${e(t('Retention'))}</th><th></th></tr></thead><tbody>${docRows||`<tr><td colspan="4" class="empty">${e(t('No encrypted KYC documents stored.'))}</td></tr>`}</tbody></table></div>`;
      form('KYC documents',note('Encrypted document storage with tamper-resistant retention.')+docTable+
        field('documentType','Document type','NATIONAL_ID',{choices:[['NATIONAL_ID','National ID'],['PASSPORT','Passport'],['REGISTRATION_CERTIFICATE','Registration certificate'],['PROOF_OF_ADDRESS','Proof of address'],['UTILITY_BILL','Utility bill'],['OTHER','Other']].map(([value,label])=>({value,label:t(label)}))})+
        field('fileName','File name','',{max:255})+
        field('contentType','Content type','application/pdf',{max:100})+
        field('retentionUntil','Retention until (min 5 years)','',{type:'date',required:false})+
        field('base64Content','Base64 document content','',{full:true,max:1000000}),async d=>{
          await api(`/admin/tenants/${target}/customers/${id}/documents`,{method:'POST',body:{documentType:d.documentType,fileName:d.fileName,contentType:d.contentType,retentionUntil:d.retentionUntil||null,base64Content:d.base64Content.trim()}});
          await refresh();
        },'Upload document');return;
    }
    if(name==='kyc-review'){
      const reviews=await api(`/admin/tenants/${target}/customers/${id}/kyc-reviews`);if(epoch!==generation||!current())return;
      const reviewRows=reviews.map(r=>`<tr><td><strong>${e(r.riskRating)}</strong></td><td>${e(r.reviewedAt)}</td><td>${e(r.nextReviewDue)} ${r.overdue?`<small class="danger">${e(t('OVERDUE'))}</small>`:''}</td><td>${e(r.reviewReference)}</td></tr>`).join('');
      const reviewTable=`<div class="table-wrap"><table><thead><tr><th>${e(t('Risk rating'))}</th><th>${e(t('Reviewed'))}</th><th>${e(t('Next due'))}</th><th>${e(t('Reference'))}</th></tr></thead><tbody>${reviewRows||`<tr><td colspan="4" class="empty">${e(t('No KYC reviews recorded.'))}</td></tr>`}</tbody></table></div>`;
      form('Periodic KYC reviews',note('Schedule periodic reviews driven by customer risk rating: High (1 year), Medium (2 years), Low (3 years).')+reviewTable+
        field('riskRating','Risk rating','MEDIUM',{choices:[['LOW','Low (3 years)'],['MEDIUM','Medium (2 years)'],['HIGH','High (1 year)']].map(([value,label])=>({value,label:t(label)}))})+
        field('reviewedAt','Review date',new Date().toISOString().slice(0,10),{type:'date'})+
        field('reviewReference','Review reference','',{max:160})+
        field('notes','Review notes','',{full:true,max:1000,required:false}),async d=>{
          await api(`/admin/tenants/${target}/customers/${id}/kyc-reviews`,{method:'POST',body:{riskRating:d.riskRating,reviewedAt:d.reviewedAt,reviewReference:d.reviewReference,notes:d.notes||null}});
          await refresh();
        },'Record review');return;
    }
    if(name==='privacy'){
      const [erasure,views]=await Promise.all([
        api(`/admin/tenants/${target}/privacy/customers/${id}/erasure`),
        api(`/admin/tenants/${target}/privacy/customers/${id}/views?limit=10`)
      ]);if(epoch!==generation||!current())return;
      const viewRows=views.map(v=>`<tr><td><strong>${e(v.actorSubject)}</strong></td><td>${e(v.action)}</td><td>${e(v.at.slice(0,19).replace('T',' '))}</td></tr>`).join('');
      const viewTable=`<div class="table-wrap"><table><thead><tr><th>${e(t('Actor'))}</th><th>${e(t('Action'))}</th><th>${e(t('Timestamp'))}</th></tr></thead><tbody>${viewRows||`<tr><td colspan="3" class="empty">${e(t('No accesses recorded.'))}</td></tr>`}</tbody></table></div>`;
      const blockersText=erasure.blockers.length?erasure.blockers.join(', '):'None (eligible)';
      form('Customer privacy & data protection',
        note(`Erasure eligibility: ${blockersText}. Retention until: ${erasure.retainUntil||'N/A'}`)+
        `<div class="panel-title"><h3>${e(t('Recent personal data access log'))}</h3></div>`+
        viewTable+
        field('type','Register data-subject request','ACCESS',{choices:[['ACCESS','Access'],['RECTIFICATION','Rectification'],['ERASURE','Erasure'],['OBJECTION','Objection'],['PORTABILITY','Portability']].map(([v,l])=>({value:v,label:t(l)}))})+
        field('reference','Request reference','',{max:120})+
        field('details','Details','',{full:true,max:500,required:false}),async d=>{
          const key=crypto.randomUUID();
          await api(`/admin/tenants/${target}/privacy/requests`,{method:'POST',headers:{'Idempotency-Key':key},body:{customerId:id,type:d.type,receivedOn:new Date().toISOString().slice(0,10),reference:d.reference.trim(),details:d.details.trim()}});
          await refresh();
        },'Register request');return;
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
      f('state',{choices:['PROSPECT','ACTIVE','BLOCKED','DECEASED'].map(s=>({value:s,label:t(statusName(s))}))})+f('reviewReference',{max:160,required:false})+
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
