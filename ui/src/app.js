import {UserManager,WebStorageStateStore,InMemoryWebStorage} from 'oidc-client-ts';
import {escapeHtml as e,bankActions,errorMessage} from './model.js';

const content=document.querySelector('#content'), dialog=document.querySelector('#dialog');
let manager, user, me, configuration, view='banks', selectedBank, submitForm, lastFocus;
let rows=[], offset=0, busy=false, noticeTimer, renderVersion=0;
const pageSize=25;
const labels={banks:'Bank directory',staff:'Staff & access',sessions:'My sessions',audit:'Audit trail'};
const titleCase=s=>String(s).toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase());
const date=s=>s?new Date(s).toLocaleString(): '—';
const badge=s=>`<span class="status ${e(String(s).toLowerCase())}">${e(titleCase(s))}</span>`;
const button=(label,action,extra='',secondary=false)=>`<button class="button ${secondary?'secondary':''}" data-action="${action}" ${extra}>${e(label)}</button>`;
function notify(message,error=false){const n=document.querySelector('#notice');n.textContent=message;n.className=`notice ${error?'error':''}`;n.hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>n.hidden=true,9000);}
function clearSession(){renderVersion++;user=null;me=null;rows=[];selectedBank=null;dialog.close();manager?.removeUser();document.querySelector('#auth-button').textContent='Sign in';}
async function api(path,{method='GET',body,headers={}}={}){
  if(!user)throw new Error('Sign in to continue.');
  const response=await fetch(`/api/v1${path}`,{method,credentials:'omit',headers:{Authorization:`Bearer ${user.access_token}`,
    'X-Correlation-ID':crypto.randomUUID(),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined});
  if(!response.ok){const problem=await response.json().catch(()=>({}));
    if(response.status===401||['STAFF_DISABLED','MFA_REQUIRED'].includes(problem.code)){clearSession();signedOut();}
    throw new Error(errorMessage(problem.code));}
  return response.status===204?null:response.json();
}
function heading(title,description,action=''){return `<div class="heading"><div><p class="eyebrow">Administration workspace</p><h1>${e(title)}</h1><p class="intro">${e(description)}</p></div>${action}</div>`;}
function signedOut(){
  const ready=configuration?.configured;
  content.innerHTML=heading(labels[view],'A clear view of your banks, people and permissions.')+`
    <section class="welcome"><div class="welcome-copy"><p class="eyebrow">Your banking administration, together</p><h2>A secure foundation.<br>A simpler working day.</h2><p>Manage independent banks, give your teams the right access, and keep every administrative change accountable.</p>${button(ready?'Sign in to your workspace':'View setup checklist',ready?'login':'setup')}</div><div class="welcome-art" aria-hidden="true"><div class="orbit"></div><div class="orbit second"></div><div class="bank-mark"><div class="bank-roof"></div><div class="pillars"><i></i><i></i><i></i><i></i></div><div class="bank-base"></div></div><span class="art-tag">✓ &nbsp; Independent banks. Connected oversight.</span></div></section>
    <div class="features"><article class="card feature"><div class="feature-icon">▦</div><h3>Independent bank workspaces</h3><p>Separate bank data, clear lifecycle controls, and a shared administration experience.</p></article><article class="card feature"><div class="feature-icon">◎</div><h3>The right access for every role</h3><p>Register staff identities, assign branch permissions, and revoke access when responsibilities change.</p></article><article class="card feature"><div class="feature-icon">≡</div><h3>A record behind every change</h3><p>Trace administrative decisions to their actor, reason, and time in the audit history.</p></article></div>
    <section class="card"><div class="panel-title"><div><h2>${ready?'Your workspace is protected':'Getting your workspace ready'}</h2><p>${ready?'Sign in with your organization’s identity provider to continue.':'Complete the connection setup to start administering your banks.'}</p></div><span class="tag">${ready?'Sign-in required':'Setup required'}</span></div><div class="setup-list"><div class="setup-row"><span class="step">1</span><div><strong>Connect your identity provider</strong><p>Use your organization’s sign-in and multifactor authentication.</p></div><span class="tag">${ready?'Configured':'Awaiting configuration'}</span></div><div class="setup-row"><span class="step">2</span><div><strong>Register your administration team</strong><p>Link staff identities and assign bank and branch responsibilities.</p></div><span class="tag">After sign-in</span></div><div class="setup-row"><span class="step">3</span><div><strong>Bring your first bank online</strong><p>Register a bank, select its approved database, and provision its workspace.</p></div><span class="tag">After sign-in</span></div></div><div id="setup-help" class="setup-help" hidden><strong>Operator setup</strong><p>Configure the JWT issuer, signing-key endpoint, portal client ID and redirect address. Register the initial administrator using <code>CBS_BOOTSTRAP_ADMIN_SUBJECT</code>. Your identity provider must issue the configured CBS audience, session claims, and MFA evidence.</p><p>The complete environment-variable guide is in <code>docs/module-02-access.md</code> in this project. No bank or staff data is shown before sign-in.</p></div></section>`;
}
function pagination(){return `<div class="pagination"><span>Records ${rows.length?offset+1:0}–${offset+rows.length}</span><div><button class="button secondary small" data-action="previous" ${offset===0?'disabled':''}>Previous</button><button class="button secondary small" data-action="next" ${rows.length<pageSize?'disabled':''}>Next</button></div></div>`;}
function table(headers,body,empty='No records yet.'){return `<div class="table-wrap"><table><thead><tr>${headers.map(h=>`<th scope="col">${e(h)}</th>`).join('')}</tr></thead><tbody>${body||`<tr><td colspan="${headers.length}" class="empty">${e(empty)}</td></tr>`}</tbody></table></div>`;}
function directory(filter=''){
  const shown=rows.filter(r=>`${r.displayName} ${r.bankCode??r.subject}`.toLowerCase().includes(filter.toLowerCase()));
  let body='';
  if(view==='banks') body=shown.map(r=>`<tr><td><strong>${e(r.displayName)}</strong><small>${e(r.bankCode)}</small></td><td>${badge(r.status)}</td><td>${e(r.defaultLanguage)}</td><td>${e(r.legalEntities.length)}</td><td><button class="link-button" data-action="open-bank" data-id="${r.id}">Manage bank →</button></td></tr>`).join('');
  if(view==='staff') body=shown.map(r=>`<tr><td><strong>${e(r.displayName)}</strong><small>${e(r.email||r.subject)}</small></td><td>${badge(r.active?'ACTIVE':'DISABLED')}</td><td class="mono">${e(r.subject)}</td><td><button class="link-button" data-action="edit-staff" data-id="${r.id}">Manage</button></td></tr>`).join('');
  document.querySelector('#directory-table').innerHTML=table(view==='banks'?['Bank','Status','Language','Legal entities','']:['Staff member','Access','Identity subject',''],body,filter?'No matching records on this page.':`No ${view==='banks'?'banks registered':'staff identities registered'} yet.`);
}
async function render(){
  const revision=++renderVersion;
  document.querySelector('#breadcrumb').textContent=labels[view];
  document.querySelectorAll('[data-view]').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.setAttribute('aria-current',b.dataset.view===view?'page':'false');});
  if(!me){signedOut();return;}
  if(!me.platformAdmin&&['banks','staff','audit'].includes(view)){content.innerHTML=heading(labels[view],'Platform administrator access is required for this section.')+`<div class="card empty"><h3>You are signed in as ${e(me.staff.displayName)}</h3><p>Your current identity can manage its own sessions.</p>${button('View my sessions','my-sessions')}</div>`;return;}
  content.innerHTML='<div class="loading">Loading workspace…</div>';
  try{
    if(selectedBank&&view==='banks'){await bankDetail(selectedBank,revision);return;}
    if(view==='banks'||view==='staff'){
      const result=await api(`/admin/${view==='banks'?'tenants':'staff'}?limit=${pageSize}&offset=${offset}`);if(revision!==renderVersion)return;rows=result.items;
      content.innerHTML=heading(labels[view],view==='banks'?'Manage bank workspaces and their operational status.':'Manage the people who can access your banking platform.',button(view==='banks'?'+ Register bank':'+ Register staff',view==='banks'?'new-bank':'new-staff'))+
        `<section class="card"><div class="toolbar"><input id="search" class="search" type="search" placeholder="Search this page…" aria-label="Search this page"><span class="tag">${rows.length} on this page</span></div><div id="directory-table"></div>${pagination()}</section>`;
      directory();return;
    }
    if(view==='sessions'){
      const sessions=await api('/access/sessions');if(revision!==renderVersion)return;rows=sessions;content.innerHTML=heading('My sessions','Review recent sessions and end access you no longer need. Idle limits also apply to sessions not revoked.')+
        `<section class="card">${table(['Session','Signed in','Last used','Status',''],rows.map(r=>`<tr><td><strong>${r.id===me.sessionId?'This session':'Other session'}</strong><small class="mono">${e(r.id.slice(0,8))}</small></td><td>${e(date(r.authenticatedAt))}</td><td>${e(date(r.lastSeenAt))}</td><td>${badge(r.revokedAt?'DISABLED':new Date(r.expiresAt)<=new Date()?'EXPIRED':'NOT_REVOKED')}</td><td>${!r.revokedAt?`<button class="link-button" data-action="end-session" data-id="${r.id}">End session</button>`:''}</td></tr>`).join(''))}</section>`;return;
    }
    const result=await api(`/admin/identity-audit?limit=${pageSize}&offset=${offset}`);if(revision!==renderVersion)return;rows=result.items;
    content.innerHTML=heading('Identity audit trail','A chronological record of staff and session administration.')+`<section class="card">${table(['Action','Actor','Reason','Recorded'],rows.map(r=>`<tr><td><strong>${e(titleCase(r.action))}</strong><small class="mono">${e(r.staffId.slice(0,8))}</small></td><td>${e(r.actorSubject)}</td><td>${e(r.reason)}</td><td>${e(date(r.occurredAt))}</td></tr>`).join(''))}${pagination()}</section>`;
  }catch(error){if(revision!==renderVersion)return;if(me)content.innerHTML=heading(labels[view],'The workspace could not be loaded.')+`<div class="card empty"><p>${e(error.message)}</p>${button('Try again','refresh')}</div>`;notify(error.message,true);}
}
async function bankDetail(id,revision){
  const bank=await api(`/admin/tenants/${id}`);
  const [branches,members,audit]=await Promise.all([api(`/admin/tenants/${id}/branches?limit=100`),api(`/admin/tenants/${id}/memberships?limit=100`),api(`/admin/tenants/${id}/audit?limit=100`)]);
  if(revision!==renderVersion)return;
  selectedBank=bank.id;rows=[bank,...branches.items];
  content.innerHTML=`<button class="link-button back" data-action="back-banks">← Bank directory</button>`+heading(bank.displayName,`${bank.bankCode} · Independent bank workspace`,badge(bank.status))+
    `<div class="detail-grid"><div class="stack"><section class="card"><div class="panel-title"><h2>Bank profile</h2><div class="actions">${bankActions(bank.status).map(a=>button(titleCase(a),`bank-${a}`,'',true)).join('')}${button('Edit','edit-bank','',true)}</div></div><dl class="metadata"><div><dt>Bank code</dt><dd>${e(bank.bankCode)}</dd></div><div><dt>Default language</dt><dd>${e(bank.defaultLanguage)}</dd></div><div><dt>Database reference</dt><dd>${e(bank.databaseBinding.databaseReference)}</dd></div><div><dt>Configuration version</dt><dd>${bank.version}</dd></div>${bank.databaseBinding.failureCode?`<div><dt>Provisioning issue</dt><dd>${e(bank.databaseBinding.failureCode)}</dd></div>`:''}</dl></section>
    <section class="card"><div class="panel-title"><div><h2>Branches</h2><p>Showing up to 100 branches. Assign access within this bank.</p></div>${button('+ Add branch','new-branch','',true)}</div>${table(['Branch','Status',''],branches.items.map(b=>`<tr><td><strong>${e(b.displayName)}</strong><small>${e(b.code)}</small></td><td>${badge(b.active?'ACTIVE':'DISABLED')}</td><td><button class="link-button" data-action="branch-grant" data-id="${b.id}">Assign access</button><br><button class="link-button" data-action="edit-branch" data-id="${b.id}">Edit branch</button><br><button class="link-button" data-action="branch-members" data-id="${b.id}">View access</button></td></tr>`).join(''),'No branches registered.')}</section></div>
    <div class="stack"><section class="card"><div class="panel-title"><h2>Legal entities</h2>${button('+ Add','new-entity','',true)}</div>${table(['Entity','Currency'],bank.legalEntities.map(l=>`<tr><td><strong>${e(l.legalName)}</strong><small>${e(l.country)} · ${e(l.businessTimezone)}</small></td><td>${e(l.functionalCurrency)}</td></tr>`).join(''))}</section>
    <section class="card"><div class="panel-title"><div><h2>Bank memberships</h2><p>Showing up to 100 identities</p></div>${button('Manage','bank-membership','',true)}</div>${table(['Identity','Role','Status'],members.items.map(m=>`<tr><td class="mono">${e(m.subject)}</td><td>${e(titleCase(m.role))}</td><td>${badge(m.active?'ACTIVE':'DISABLED')}</td></tr>`).join(''),'No bank memberships.')}</section></div></div>
    <section class="card section-gap"><div class="panel-title"><div><h2>Bank audit history</h2><p>First 100 records, oldest first</p></div></div>${table(['Action','Actor','Reason','Recorded'],audit.items.map(a=>`<tr><td>${e(titleCase(a.action))}</td><td>${e(a.actorSubject)}</td><td>${e(a.reason)}</td><td>${e(date(a.occurredAt))}</td></tr>`).join(''))}</section>`;
}
function field(name,label,value='',options={}){
  const {type='text',required=true,full=false,help='',choices,max=200}=options;
  return `<label class="field ${full?'full':''}">${e(label)}${choices?`<select name="${name}" ${required?'required':''}>${choices.map(c=>`<option value="${e(c.value)}" ${String(c.value)===String(value)?'selected':''}>${e(c.label)}</option>`).join('')}</select>`:`<input name="${name}" type="${type}" value="${e(value)}" maxlength="${max}" ${required?'required':''}>`}${help?`<small>${e(help)}</small>`:''}</label>`;
}
const reason=()=>field('reason','Reason for this change','',{full:true,max:500});
const activeChoices=[{value:'true',label:'Active'},{value:'false',label:'Disabled / revoked'}];
const roleChoices=roles=>roles.map(r=>({value:r,label:titleCase(r)}));
const entityFields=()=>field('legalName','Legal entity name','',{full:true})+field('country','Country code','IN',{max:2})+field('functionalCurrency','Functional currency','INR',{max:3})+field('businessTimezone','Business timezone','Asia/Kolkata',{full:true,max:64});
const entity=d=>({legalName:d.legalName,country:d.country,functionalCurrency:d.functionalCurrency,businessTimezone:d.businessTimezone});
function form(title,fields,submit,label='Save changes'){
  lastFocus=document.activeElement;submitForm=submit;document.querySelector('#dialog-title').textContent=title;document.querySelector('#fields').innerHTML=fields;document.querySelector('#form-error').textContent='';document.querySelector('#save').textContent=label;dialog.showModal();dialog.querySelector('input,select')?.focus();
}
function closeDialog(){if(busy)return;dialog.close();lastFocus?.focus();}
async function action(name,id){
  const bank=selectedBank?rows.find(r=>r.id===selectedBank):null;
  const base=bank?`/admin/tenants/${bank.id}`:'';
  if(name==='login'){if(manager)await manager.signinRedirect({prompt:'login',max_age:0});return;}
  if(name==='setup'){const p=document.querySelector('#setup-help');p.hidden=!p.hidden;p.scrollIntoView({behavior:'smooth',block:'nearest'});return;}
  if(name==='close-dialog'){closeDialog();return;}
  if(name==='refresh'){await render();return;}
  if(name==='my-sessions'){view='sessions';await render();return;}
  if(name==='next'||name==='previous'){offset=Math.max(0,offset+(name==='next'?pageSize:-pageSize));await render();return;}
  if(name==='open-bank'){selectedBank=id;await render();return;}
  if(name==='back-banks'){selectedBank=null;await render();return;}
  if(name==='new-bank'){
    const key=crypto.randomUUID();let serialized;
    form('Register a bank',field('bankCode','Bank code','',{max:32})+field('displayName','Display name')+field('defaultLanguage','Default language','en-IN',{max:35})+field('databaseReference','Approved database reference','',{max:64})+entityFields(),async d=>{
      const body={bankCode:d.bankCode,displayName:d.displayName,defaultLanguage:d.defaultLanguage,databaseReference:d.databaseReference,legalEntity:entity(d)};
      const next=JSON.stringify(body);if(serialized&&serialized!==next)throw new Error('This registration was already attempted. Close and reopen the form before changing its details.');serialized=next;
      await api('/admin/tenants',{method:'POST',body,headers:{'Idempotency-Key':key}});
    },'Register bank');return;
  }
  if(name==='new-staff'){form('Register a staff identity',field('subject','Identity provider subject','',{full:true,help:'Use the stable subject identifier supplied by your identity provider.'})+field('displayName','Display name')+field('email','Email','',{type:'email',required:false,max:254})+reason(),d=>api('/admin/staff',{method:'POST',body:d}),'Register staff');return;}
  if(name==='edit-staff'){
    const r=rows.find(x=>x.id===id);form(`Manage ${r.displayName}`,`<div class="field full">CBS staff ID<span class="mono">${e(r.id)}</span></div>`+field('displayName','Display name',r.displayName)+field('email','Email',r.email||'',{type:'email',required:false,max:254})+field('active','Access',String(r.active),{choices:activeChoices})+field('operation','Action','update',{choices:[{value:'update',label:'Update identity'},{value:'revoke',label:'Revoke all sessions'}]})+reason(),d=>api(`/admin/staff/${id}${d.operation==='revoke'?'/revoke-sessions':''}`,{method:d.operation==='revoke'?'POST':'PATCH',body:d.operation==='revoke'?{expectedVersion:r.version,reason:d.reason}:{expectedVersion:r.version,displayName:d.displayName,email:d.email,active:d.active==='true',reason:d.reason}}));return;
  }
  if(name==='end-session'){form('End this session?',`<p class="intro field full">The selected session will lose access on its next request.</p>`,async()=>{await api(`/access/sessions/${id}`,{method:'DELETE'});if(id===me.sessionId)clearSession();},'End session');return;}
  if(name.startsWith('bank-')&&['provision','suspend','resume'].includes(name.slice(5))){const operation=name.slice(5);form(`${titleCase(operation)} ${bank.displayName}`,reason(),async d=>{const result=await api(`${base}/${operation}`,{method:'POST',body:{expectedVersion:bank.version,reason:d.reason}});if(result.status==='PROVISIONING_FAILED'){dialog.close();await render();throw new Error(`Provisioning needs attention: ${result.databaseBinding.failureCode}. Refresh the bank before retrying.`);}},titleCase(operation));return;}
  if(name==='edit-bank'){form('Edit bank profile',field('displayName','Display name',bank.displayName)+field('defaultLanguage','Default language',bank.defaultLanguage,{max:35})+reason(),d=>api(base,{method:'PATCH',body:{...d,expectedVersion:bank.version}}));return;}
  if(name==='new-entity'){form('Add legal entity',entityFields()+reason(),d=>api(`${base}/legal-entities`,{method:'POST',body:{legalEntity:entity(d),expectedVersion:bank.version,reason:d.reason}}));return;}
  if(name==='bank-membership'){form('Manage bank membership',field('subject','Identity subject','',{full:true,help:'Register the staff identity first.'})+field('role','Bank role','BANK_OPERATOR',{choices:roleChoices(['BANK_OPERATOR','BANK_AUDITOR'])})+field('active','Access','true',{choices:activeChoices})+reason(),d=>api(`${base}/memberships`,{method:'PUT',body:{...d,active:d.active==='true',expectedVersion:bank.version}}));return;}
  if(name==='new-branch'){form('Add a branch',field('code','Branch code','',{max:32})+field('displayName','Branch name')+field('legalEntityId','Legal entity',bank.legalEntities[0]?.id,{full:true,choices:bank.legalEntities.map(l=>({value:l.id,label:l.legalName}))})+reason(),d=>api(`${base}/branches`,{method:'POST',body:d}));return;}
  if(name==='edit-branch'){const b=rows.find(r=>r.id===id);form('Edit branch',field('displayName','Branch name',b.displayName)+field('active','Status',String(b.active),{choices:activeChoices})+reason(),d=>api(`${base}/branches/${id}`,{method:'PATCH',body:{...d,active:d.active==='true',expectedVersion:b.version}}));return;}
  if(name==='branch-grant'){const b=rows.find(r=>r.id===id);form('Manage branch access',field('staffId','CBS staff ID','',{full:true,help:'Use the staff record UUID from the staff directory. Bank membership is required.'})+field('role','Branch role','TELLER',{choices:roleChoices(['TELLER','BRANCH_MANAGER','AUDITOR'])})+field('active','Access','true',{choices:activeChoices})+reason(),d=>api(`${base}/branches/${id}/memberships`,{method:'PUT',body:{...d,active:d.active==='true',expectedVersion:b.version}}));return;}
  if(name==='branch-members'){const grants=await api(`${base}/branches/${id}/memberships`);form('Branch access',`<div class="field full">${table(['Staff','Role','Access'],grants.map(g=>`<tr><td>${e(g.displayName)}</td><td>${e(titleCase(g.role))}</td><td>${badge(g.active?'ACTIVE':'DISABLED')}</td></tr>`).join(''),'No branch access assigned.')}</div>`,async()=>{},'Done');}
}
document.addEventListener('click',async event=>{
  const nav=event.target.closest('[data-view]');if(nav){view=nav.dataset.view;offset=0;selectedBank=null;await render();return;}
  const target=event.target.closest('[data-action]');if(target&&!target.disabled){try{await action(target.dataset.action,target.dataset.id);}catch(error){notify(error.message,true);}}
});
document.addEventListener('input',event=>{if(event.target.id==='search')directory(event.target.value);});
dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
document.querySelector('#editor').addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;busy=true;document.querySelector('#save').disabled=true;document.querySelector('#form-error').textContent='';
  try{await submitForm(Object.fromEntries(new FormData(event.target)));dialog.close();await render();notify('Changes saved.');}
  catch(error){document.querySelector('#form-error').textContent=error.message;notify(error.message,true);}
  finally{busy=false;document.querySelector('#save').disabled=false;}
});
document.querySelector('#auth-button').addEventListener('click',async()=>{
  if(!me){try{await action('login');}catch{notify('Sign-in could not be started. Check your identity provider connection.',true);}return;}
  let revoked=true;try{await api(`/access/sessions/${me.sessionId}`,{method:'DELETE'});}catch{revoked=false;}
  clearSession();signedOut();notify(revoked?'Your CBS session has ended.':'Local sign-out completed. Server revocation could not be confirmed.',!revoked);
});
async function boot(){
  try{
    const response=await fetch('/portal/config',{credentials:'omit'});if(!response.ok)throw new Error('Unable to load sign-in configuration.');configuration=await response.json();
    const connection=document.querySelector('#connection');connection.textContent=configuration.configured?'Identity connected':'Identity not connected';connection.classList.toggle('ready',configuration.configured);
    document.querySelector('#auth-button').disabled=!configuration.configured;
    if(configuration.configured){
      if(new URL(configuration.redirectUri).origin!==location.origin)throw new Error('Open the portal at its configured redirect origin.');
      manager=new UserManager({authority:configuration.authority,client_id:configuration.clientId,redirect_uri:configuration.redirectUri,
        response_type:'code',scope:configuration.scope,automaticSilentRenew:false,loadUserInfo:false,
        userStore:new WebStorageStateStore({store:new InMemoryWebStorage()}),stateStore:new WebStorageStateStore({store:sessionStorage,prefix:'cbs.oidc.'})});
      manager.events.addAccessTokenExpired(()=>{clearSession();signedOut();notify('Your access token expired. Sign in again.');});
      const query=new URLSearchParams(location.search);
      if(query.has('code')||query.has('error')){
        try{user=await manager.signinRedirectCallback();me=await api('/access/me');document.querySelector('#auth-button').textContent='End CBS session';}
        catch{clearSession();notify('Sign-in could not be completed. Check your CBS registration and complete MFA, then start again.',true);}
        finally{history.replaceState({},'',location.pathname);}
      }
    }
    await render();
  }catch(error){configuration={configured:false};signedOut();notify(error.message,true);document.querySelector('#connection').textContent='Connection unavailable';}
}
boot();
