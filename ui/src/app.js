import {createLendingAccessUi} from './lending-access-ui.js';
import {createBankDutiesUi} from './bank-duties-ui.js';
import {createCollectionsUi} from './collections-ui.js';
import {createCreditUi} from './credit-ui.js';
import {createContractsUi} from './contracts-ui.js';
import {createFxUi} from './fx-ui.js';
import {createPaymentCasesUi} from './payment-cases-ui.js';
import {createStatementImportUi} from './statement-import-ui.js';
import {createPaymentsUi} from './payments-ui.js';
import {createComplianceUi} from './compliance-ui.js';
import {createServicingUi} from './servicing-ui.js';
import {createNotificationUi} from './notification-ui.js';
import {createReportingUi} from './reporting-ui.js';
import {createScreeningUi} from './screening-ui.js';
import {createBranchUi} from './branch-ui.js';
import {UserManager,WebStorageStateStore,InMemoryWebStorage} from 'oidc-client-ts';
import {escapeHtml as e,bankActions,errorMessage,signInErrorMessage} from './model.js';
import {t,setLanguage,getLanguage,displayValue} from './i18n.js';
import {createReferenceUi} from './reference-ui.js';
import {createChartUi} from './chart-ui.js';
import {createAccountUi,accountFields} from './account-ui.js';
import {createCustomerUi,customerFields} from './customer-ui.js';
import {createProductUi} from './product-ui.js';
import {createDayCloseUi} from './day-close-ui.js';
import {createReportUi} from './report-ui.js';
import {createJournalUi} from './journal-ui.js';
import {createLedgerUi} from './ledger-ui.js';

const content=document.querySelector('#content'), dialog=document.querySelector('#dialog');
let manager, user, me, configuration, view='banks', selectedBank, submitForm, lastFocus;
let rows=[], offset=0, busy=false, noticeTimer, renderVersion=0;
let approvalScope='INBOX', approvalAction='', approvalBank='', selectedApproval;
const pageSize=25;
const labels={bankduties:"Bank duties & access review",lendingaccess:"Lending permissions",lendingapprovals:"Lending approval inbox",collections:"Loan arrears & collections",credit:'Loan applications & credit review',term:'Term deposits',loan:'Lending',fx:'FX & exchange rates',paymentcases:'Payment exceptions',statements:'M-Pesa statements',payments:'Payments & reconciliation',compliance:'Compliance cases',servicing:'Deposit servicing',notifications:'Customer alerts',reporting:'Management reports',screening:'Screening & monitoring',branchwork:'Branch workspace',banks:'Bank directory',staff:'Staff & access',approvals:'Approval Inbox',settings:'Bank settings',chart:'Chart of Accounts',ledgers:'Ledger Accounts',journals:'Journals & balances',reports:'Accounting reports',dayclose:'Business Day Close',products:'Product Factory',accounts:'Deposit accounts',customers:'Customers',sessions:'My sessions',audit:'Audit trail'};
const lendingAccessUi=createLendingAccessUi({api,form,field,refresh:render,subject:()=>me?.staff.subject});
const bankDutiesUi=createBankDutiesUi({api,form,field,refresh:render});
const lendingApi=(path,options)=>{if(path==='/admin/tenants?limit=100')return api('/banks/lending-operations/banks',options);return api(path.replace(/^\/admin\/tenants\/([^/?]+)/,'/banks/$1/lending-operations'),options);};
const statementImportUi=createStatementImportUi({api,form,field,refresh:render});
const collectionsUi=createCollectionsUi({api:lendingApi,form,field,refresh:render,isAdmin:()=>me?.platformAdmin});
const creditUi=createCreditUi({api:lendingApi,form,field,refresh:render,isAdmin:()=>me?.platformAdmin});
const termUi=createContractsUi({api,form,field,refresh:render,type:'TERM_DEPOSIT'});
const loanUi=createContractsUi({api:lendingApi,form,field,refresh:render,type:'LOAN',isAdmin:()=>me?.platformAdmin});
const fxUi=createFxUi({api,form,field,refresh:render});
const paymentCasesUi=createPaymentCasesUi({api,form,field,refresh:render});
const paymentsUi=createPaymentsUi({api,form,field,refresh:render});
const complianceUi=createComplianceUi({api,form,field,refresh:render});
const screeningUi=createScreeningUi({api,form,field,refresh:render});
const servicingUi=createServicingUi({api,form,field,refresh:render});
const notificationUi=createNotificationUi({api,form,field,refresh:render});
const reportingUi=createReportingUi({api,form,field,refresh:render,download:(blob,name)=>{const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}});
const branchUi=createBranchUi({api,form,field,refresh:render,subject:()=>me?.staff.subject});
const referenceUi=createReferenceUi({api,form,field,refresh:render});
const accountUi=createAccountUi({api,form,field,refresh:render});
const customerUi=createCustomerUi({api,form,field,refresh:render});
const productUi=createProductUi({api,form,field,refresh:render});
const chartUi=createChartUi({api,form,field,refresh:render});
const dayCloseUi=createDayCloseUi({api,form,field,refresh:render});
const reportUi=createReportUi({api,refresh:render});
const journalUi=createJournalUi({api,form,field,refresh:render});
const ledgerUi=createLedgerUi({api,form,field,refresh:render});
function localizeChrome(){
  document.documentElement.lang=getLanguage();
  document.querySelector('#ui-language').value=getLanguage();
  document.querySelectorAll('[data-view]').forEach(node=>{node.lastChild.textContent=' '+t(labels[node.dataset.view]);});
  document.querySelector('#auth-button').textContent=t(me?'End CBS session':'Sign in');
}
const titleCase=s=>String(s).toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase());
const date=s=>s?new Date(s).toLocaleString(getLanguage()): '—';
const badge=s=>`<span class="status ${e(String(s).toLowerCase())}">${e(titleCase(s))}</span>`;
const button=(label,action,extra='',secondary=false)=>`<button class="button ${secondary?'secondary':''}" data-action="${action}" ${extra}>${e(t(label))}</button>`;
function notify(message,error=false){const n=document.querySelector('#notice');n.textContent=t(message);n.className=`notice ${error?'error':''}`;n.hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>n.hidden=true,9000);}
function clearSession(){renderVersion++;user=null;me=null;rows=[];selectedBank=null;dialog.close();manager?.removeUser();document.querySelector('#auth-button').textContent='Sign in';}
async function api(path,{method='GET',body,headers={},responseType='json'}={}){
  if(!user)throw new Error('Sign in to continue.');
  const response=await fetch(`/api/v1${path}`,{method,credentials:'omit',headers:{Authorization:`Bearer ${user.access_token}`,
    'X-Correlation-ID':crypto.randomUUID(),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined});
  if(!response.ok){const problem=await response.json().catch(()=>({}));
    if(response.status===401||['STAFF_DISABLED','MFA_REQUIRED','LOCAL_MFA_EXCEPTION_EXPIRED'].includes(problem.code)){clearSession();signedOut();}
    // Screening list validation names the CSV line, which the operator needs to fix the file.
    throw Object.assign(new Error(problem.code==='SCREENING_LIST_INVALID'&&problem.detail?problem.detail:errorMessage(problem.code)),{code:problem.code,cbsProblem:true});}
  return response.status===204?null:responseType==='text'?response.text():responseType==='blob'?response.blob():response.json();
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
  localizeChrome();
  document.querySelector('#breadcrumb').textContent=t(labels[view]);
  document.querySelectorAll('[data-view]').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.setAttribute('aria-current',b.dataset.view===view?'page':'false');});
  if(!me){signedOut();return;}
  if(!me.platformAdmin&&['bankduties','lendingaccess','term','fx','banks','staff','audit','approvals','settings','chart','ledgers','journals','reports','dayclose','products','customers','accounts','servicing','notifications','reporting','compliance','screening'].includes(view)){content.innerHTML=heading(labels[view],'Platform administrator access is required for this section.')+`<div class="card empty"><h3>You are signed in as ${e(me.staff.displayName)}</h3><p>Your current identity can manage its own sessions.</p>${button('View my sessions','my-sessions')}</div>`;return;}
  content.innerHTML='<div class="loading">Loading workspace…</div>';
  try{
    if(view==='bankduties'){await bankDutiesUi.render(content,()=>revision===renderVersion);return;}
    if(view==='lendingaccess'||view==='lendingapprovals'){await lendingAccessUi.render(content,()=>revision===renderVersion,view==='lendingaccess'?'grants':'approvals');return;}
    if(view==='collections'){await collectionsUi.render(content,()=>revision===renderVersion);return;}
    if(view==='credit'){await creditUi.render(content,()=>revision===renderVersion);return;}
    if(view==='term'){await termUi.render(content,()=>revision===renderVersion);return;}
    if(view==='loan'){await loanUi.render(content,()=>revision===renderVersion);return;}
    if(view==='fx'){await fxUi.render(content,()=>revision===renderVersion);return;}
    if(view==='paymentcases'){await paymentCasesUi.render(content,()=>revision===renderVersion);return;}
    if(view==='statements'){await statementImportUi.render(content,()=>revision===renderVersion);return;}
    if(view==='payments'){await paymentsUi.render(content,()=>revision===renderVersion);return;}
    if(view==='compliance'){await complianceUi.render(content,()=>revision===renderVersion);return;}
    if(view==='screening'){await screeningUi.render(content,()=>revision===renderVersion);return;}
    if(view==='servicing'){await servicingUi.render(content,()=>revision===renderVersion);return;}
    if(view==='notifications'){await notificationUi.render(content,()=>revision===renderVersion);return;}
    if(view==='reporting'){await reportingUi.render(content,()=>revision===renderVersion);return;}
    if(view==='branchwork'){await branchUi.render(content,()=>revision===renderVersion);return;}
    if(view==='accounts'){await accountUi.render(content,()=>revision===renderVersion);return;}
    if(view==='customers'){await customerUi.render(content,()=>revision===renderVersion);return;}
    if(view==='products'){await productUi.render(content,()=>revision===renderVersion);return;}
    if(view==='dayclose'){await dayCloseUi.render(content,()=>revision===renderVersion);return;}
    if(view==='reports'){await reportUi.render(content,()=>revision===renderVersion);return;}
    if(view==='journals'){await journalUi.render(content,()=>revision===renderVersion);return;}
    if(view==='ledgers'){await ledgerUi.render(content,()=>revision===renderVersion);return;}
    if(view==='chart'){await chartUi.render(content,()=>revision===renderVersion);return;}
    if(view==='settings'){await referenceUi.render(content,()=>revision===renderVersion);return;}
    if(view==='approvals'){if(selectedApproval)await approvalDetail(revision);else await approvalDirectory(revision);return;}
    if(selectedBank&&view==='banks'){await bankDetail(selectedBank,revision);return;}
    if(view==='banks'||view==='staff'){
      const result=await api(`/admin/${view==='banks'?'tenants':'staff'}?limit=${pageSize}&offset=${offset}`);if(revision!==renderVersion)return;rows=result.items;
      content.innerHTML=heading(labels[view],view==='banks'?'Manage bank workspaces and their operational status.':'Manage the people who can access your banking platform.',button(view==='banks'?'+ Register bank':'+ Register staff',view==='banks'?'new-bank':'new-staff')+(view==='staff'?button('Bank duties & access review','bank-duties')+button('Lending permissions','lending-permissions'):''))+
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
    `<div class="detail-grid"><div class="stack"><section class="card"><div class="panel-title"><h2>Bank profile</h2><div class="actions">${bankActions(bank.status).map(a=>button(titleCase(a),`bank-${a}`,'',true)).join('')}${button('Deposit accounts','bank-accounts','',true)}${button('Customers','bank-customers','',true)}${button('Product Factory','bank-products','',true)}${button('Journals & balances','bank-journals','',true)}${button('Ledger Accounts','bank-ledgers','',true)}${button('Chart of Accounts','bank-chart','',true)}${button('Cash locations','bank-cash-locations','',true)}${button('Cash denominations','bank-denominations','',true)}${button('Branch controls','bank-branch-controls','',true)}${button('Transaction limits','bank-limits','',true)}${button('Bank settings','bank-settings','',true)}${button('Edit','edit-bank','',true)}</div></div><dl class="metadata"><div><dt>Bank code</dt><dd>${e(bank.bankCode)}</dd></div><div><dt>Default language</dt><dd>${e(bank.defaultLanguage)}</dd></div><div><dt>Database reference</dt><dd>${e(bank.databaseBinding.databaseReference)}</dd></div><div><dt>Configuration version</dt><dd>${bank.version}</dd></div>${bank.databaseBinding.failureCode?`<div><dt>Provisioning issue</dt><dd>${e(bank.databaseBinding.failureCode)}</dd></div>`:''}</dl></section>
    <section class="card"><div class="panel-title"><div><h2>Branches</h2><p>Showing up to 100 branches. Assign access within this bank.</p></div>${button('+ Add branch','new-branch','',true)}</div>${table(['Branch','Status',''],branches.items.map(b=>`<tr><td><strong>${e(b.displayName)}</strong><small>${e(b.code)}</small></td><td>${badge(b.active?'ACTIVE':'DISABLED')}</td><td><button class="link-button" data-action="branch-grant" data-id="${b.id}">Assign access</button><br><button class="link-button" data-action="edit-branch" data-id="${b.id}">Edit branch</button><br><button class="link-button" data-action="branch-members" data-id="${b.id}">View access</button></td></tr>`).join(''),'No branches registered.')}</section></div>
    <div class="stack"><section class="card"><div class="panel-title"><h2>Legal entities</h2>${button('+ Add','new-entity','',true)}</div>${table(['Entity','Currency'],bank.legalEntities.map(l=>`<tr><td><strong>${e(l.legalName)}</strong><small>${e(l.country)} · ${e(l.businessTimezone)}</small></td><td>${e(l.functionalCurrency)}</td></tr>`).join(''))}</section>
    <section class="card"><div class="panel-title"><div><h2>Bank memberships</h2><p>Showing up to 100 identities</p></div>${button('Manage','bank-membership','',true)}</div>${table(['Identity','Role','Status'],members.items.map(m=>`<tr><td class="mono">${e(m.subject)}</td><td>${e(titleCase(m.role))}</td><td>${badge(m.active?'ACTIVE':'DISABLED')}</td></tr>`).join(''),'No bank memberships.')}</section></div></div>
    <section class="card section-gap"><div class="panel-title"><div><h2>Bank audit history</h2><p>First 100 records, oldest first</p></div></div>${table(['Action','Actor','Reason','Recorded'],audit.items.map(a=>`<tr><td>${e(titleCase(a.action))}</td><td>${e(a.actorSubject)}</td><td>${e(a.reason)}</td><td>${e(date(a.occurredAt))}</td></tr>`).join(''))}</section>`;
}
async function approvalDirectory(revision){
  const query=new URLSearchParams({scope:approvalScope,limit:pageSize,offset});
  if(approvalAction)query.set('action',approvalAction);
  if(approvalBank)query.set('bankId',approvalBank);
  const [result,banks]=await Promise.all([api(`/admin/approvals?${query}`),api('/admin/tenants?limit=100')]);
  if(revision!==renderVersion)return;
  rows=result.items;
  const tabs=[['INBOX','Pending approvals'],['MINE','My requests'],['HISTORY','Approval history']].map(([value,label])=>
    `<button class="button ${approvalScope===value?'':'secondary'}" data-action="approval-scope" data-id="${value}" aria-pressed="${approvalScope===value}">${label}</button>`).join('');
  content.innerHTML=heading('Approval Inbox','Review proposed changes before they take effect. Every decision is recorded.')+
    `<div class="approval-tabs actions">${tabs}</div><section class="card"><div class="toolbar approval-filters">
    <label class="field">Request type<select id="approval-type"><option value="">All types</option>${['BANK_SUSPEND','BANK_RESUME','STAFF_UPDATE','BANK_SETTINGS','CHART_ACCOUNT','LEDGER_ACCOUNT','JOURNAL_POST','DAY_CLOSE','DEPOSIT_PRODUCT','CUSTOMER_CHANGE','DEPOSIT_ACCOUNT','ACCOUNT_FUNDING','ACCOUNT_OPERATION','TRANSACTION_LIMIT','ACCOUNT_BRANCH','BRANCH_LIMIT','CASH_OPERATION','CASH_DENOMINATIONS','SERVICING_POLICY','SERVICING_RUN','COMPLIANCE_REVIEW','PAYMENT_ORDER','PAYMENT_RECONCILE','STATEMENT_ACTION','PAYMENT_CASE','PAYMENT_ACCESS','FX_RATE','FX_RATE_WITHDRAW','FX_TRADE','FX_REVERSAL','FX_BOOK','FX_CLOSING_RATE','FX_VALUATION','CONTRACT_OPEN','CONTRACT_EVENT','CONTRACT_CORRECTION','TERM_SERVICING','CREDIT_POLICY','CREDIT_REVIEW','CREDIT_RELEASE','LOAN_SERVICING','LOAN_FEE','LENDING_ACCESS','NOTIFICATION_PREFERENCE'].map(a=>`<option value="${a}" ${a===approvalAction?'selected':''}>${e(titleCase(a))}</option>`).join('')}</select></label>
    <label class="field">Bank<select id="approval-bank"><option value="">All banks and staff</option>${banks.items.map(b=>`<option value="${b.id}" ${b.id===approvalBank?'selected':''}>${e(b.displayName)}</option>`).join('')}</select></label>
    <span class="tag">${rows.length} on this page</span></div>`+
    table(['Request / target','Status','Submitted by','Submitted',''],rows.map(r=>`<tr><td><strong>${e(titleCase(r.action))}</strong><small>${e(r.targetLabel)}</small></td><td>${badge(r.status)}</td><td>${e(r.makerSubject)}${r.makerSubject===me.staff.subject?'<small>Your request</small>':''}</td><td>${e(date(r.createdAt))}</td><td><button class="link-button" data-action="open-approval" data-id="${r.id}">View request →</button></td></tr>`).join(''),approvalScope==='INBOX'?'No pending approvals. Changes submitted for review will appear here.':'No requests match these filters.')+pagination()+
    `</section><p class="approval-note">Bank configuration, products, staff updates, ledger changes, journals and day closing require a different platform administrator to approve. Approved journals affect balances only after posting. Emergency session revocation remains immediate.</p>`;
}
async function approvalDetail(revision){
  const details=await api(`/admin/approvals/${selectedApproval}`);
  if(revision!==renderVersion)return;
  const r=details.request, own=r.makerSubject===me.staff.subject;
  const actions=r.status==='PENDING'?(own?button('Cancel request','approval-cancel',`data-id="${r.id}"`,true):button('Reject','approval-reject',`data-id="${r.id}"`,true)+button('Approve change','approval-approve',`data-id="${r.id}"`)):'';
  const productFields={code:'Product code',name:'Product name',description:'Description',productType:'Product type',currency:'Currency',minimumOpening:'Minimum opening',minimumBalance:'Minimum balance',liabilityAccountCode:'Liability account',active:'Active'};
  const values=Object.keys(r.proposedValues).filter(key=>r.action!=='DEPOSIT_PRODUCT'||key!=='liabilityAccountId').map(key=>`<tr><th scope="row">${e(t((r.action==='ACCOUNT_FUNDING'?{...accountFields,accountNumber:'Account number',reference:'Receipt reference',narration:'Receipt evidence and reason',businessDate:'Business date',legalEntity:'Legal entity',lines:'Accounting entries',amount:'Amount',operation:'Operation'}:r.action==='DEPOSIT_ACCOUNT'?accountFields:r.action==='CUSTOMER_CHANGE'?customerFields:r.action==='DEPOSIT_PRODUCT'?productFields:{status:'Status',displayName:'Display name',email:'Email',active:'Access enabled'})[key]||key))}</th><td><pre class="approval-value">${e(displayValue(r.beforeValues[key]))}</pre></td><td><pre class="approval-value">${e(displayValue(r.proposedValues[key]))}</pre></td></tr>`).join('');
  content.innerHTML=`<button class="link-button back" data-action="back-approvals">← Approval Inbox</button>`+heading(titleCase(r.action),r.targetLabel,badge(r.status))+
    `<section class="card"><div class="panel-title"><div><h2>Review the proposed change</h2><p>${r.status==='PENDING'?'The live record remains unchanged until approval.':'This request is closed.'}</p></div><div class="actions">${actions}</div></div>`+
    table(['Field','Current at submission','Proposed'],values)+`<dl class="metadata"><div><dt>Submitted by</dt><dd>${e(r.makerSubject)}</dd></div><div><dt>Submitted</dt><dd>${e(date(r.createdAt))}</dd></div><div><dt>Reason</dt><dd>${e(r.reason)}</dd></div><div><dt>Request ID</dt><dd>${e(r.id)}</dd></div>${r.reviewerSubject?`<div><dt>Reviewed by</dt><dd>${e(r.reviewerSubject)}</dd></div><div><dt>Decision reason</dt><dd>${e(r.decisionReason)}</dd></div>`:''}</dl></section>
    ${own&&r.status==='PENDING'?'<p class="approval-note">You submitted this request. Another platform administrator must review it.</p>':''}
    ${r.status==='STALE'?'<p class="approval-note">The record changed after submission. No change was applied. Open the current record and submit a new request.</p>':''}
    <section class="card section-gap"><div class="panel-title"><h2>Request history</h2></div>${table(['Event','Actor','Reason','Time'],details.events.map(item=>`<tr><td>${e(titleCase(item.action))}</td><td>${e(item.actorSubject)}</td><td>${e(item.reason)}</td><td>${e(date(item.occurredAt))}</td></tr>`).join(''))}</section>`;
}
function field(name,label,value='',options={}){
  const {type='text',required=true,full=false,help='',choices,max=200,pattern='',format=''}=options;
  return `<label class="field ${full?'full':''}">${e(t(label))}${choices?`<select name="${name}" ${required?'required':''}>${choices.map(c=>`<option value="${e(c.value)}" ${String(c.value)===String(value)?'selected':''}>${e(c.label)}</option>`).join('')}</select>`:`<input name="${name}" type="${type}" value="${e(value)}" maxlength="${max}" ${pattern?`pattern="${e(pattern)}" title="${e(format)}"`:''} ${required?'required':''}>`}${help?`<small>${e(help)}</small>`:''}</label>`;
}
const reason=()=>field('reason','Reason for this change','',{full:true,max:500});
const activeChoices=[{value:'true',label:'Active'},{value:'false',label:'Disabled / revoked'}];
const roleChoices=roles=>roles.map(r=>({value:r,label:titleCase(r)}));
const entityFields=()=>field('legalName','Legal entity name','',{full:true})+field('country','Country code','IN',{max:2})+field('functionalCurrency','Functional currency','INR',{max:3})+field('businessTimezone','Business timezone','Asia/Kolkata',{full:true,max:64});
const entity=d=>({legalName:d.legalName,country:d.country,functionalCurrency:d.functionalCurrency,businessTimezone:d.businessTimezone});
function form(title,fields,submit,label='Save changes'){
  lastFocus=document.activeElement;submitForm=submit;document.querySelector('#dialog-title').textContent=t(title);document.querySelector('#fields').innerHTML=fields;document.querySelector('#form-error').textContent='';document.querySelector('#save').textContent=t(label);dialog.showModal();dialog.querySelector('input,select')?.focus();
}
function closeDialog(){if(busy)return;dialog.close();lastFocus?.focus();}
async function action(name,id){
  if(name.startsWith('statement-import-')){await statementImportUi.action(name.slice(17),id);return;}
  if(name==='bank-duties'){view='bankduties';await render();return;}
  if(name.startsWith('bankduties-')){await bankDutiesUi.action(name.slice(11),id);return;}
  if(name==='lending-permissions'){view='lendingaccess';await render();return;}
  if(name.startsWith('lendingaccess-')){await lendingAccessUi.action(name.slice(14),id);return;}
  if(name.startsWith('collections-')){await collectionsUi.action(name.slice(12),id);return;}
  if(name.startsWith('credit-')){await creditUi.action(name.slice(7),id);return;}
  if(name.startsWith('term-')){await termUi.action(name.slice(5),id);return;}
  if(name.startsWith('loan-')){await loanUi.action(name.slice(5),id);return;}
  if(name.startsWith('fx-')){await fxUi.action(name.slice(3),id);return;}
  if(name.startsWith('payment-case-')){await paymentCasesUi.action(name.slice(13),id);return;}
  if(name.startsWith('payments-')){await paymentsUi.action(name.slice(9),id);return;}
  if(name.startsWith('compliance-')){await complianceUi.action(name.slice(11),id);return;}
  if(name.startsWith('screening-')){await screeningUi.action(name.slice(10),id);return;}
  if(name.startsWith('servicing-')){await servicingUi.action(name.slice(10),id);return;}
  if(name.startsWith('notification-')){await notificationUi.action(name.slice(13),id);return;}
  if(name.startsWith('reporting-')){await reportingUi.action(name.slice(10),id);return;}
  if(name.startsWith('account-')){await accountUi.action(name.slice(8),id);return;}
  if(name==='bank-accounts'){accountUi.selectBank(selectedBank);view='accounts';await render();return;}
  if(name.startsWith('customer-')){await customerUi.action(name.slice(9),id);return;}
  if(name==='bank-customers'){customerUi.selectBank(selectedBank);view='customers';await render();return;}
  if(name.startsWith('product-')){await productUi.action(name.slice(8),id);return;}
  if(name==='bank-products'){productUi.selectBank(selectedBank);view='products';await render();return;}
  if(name.startsWith('day-')){await dayCloseUi.action(name.slice(4),id);return;}
  if(name.startsWith('report-')){await reportUi.action(name.slice(7));return;}
  if(name.startsWith('branch-work-')){await branchUi.action(name.slice(12),id);return;}
  if(name.startsWith('journal-')){await journalUi.action(name.slice(8),id);return;}
  if(name==='bank-journals'){journalUi.selectBank(selectedBank);view='journals';await render();return;}
  if(name.startsWith('ledger-')){await ledgerUi.action(name.slice(7),id);return;}
  if(name==='bank-ledgers'){ledgerUi.selectBank(selectedBank);view='ledgers';await render();return;}
  if(name.startsWith('chart-')){await chartUi.action(name.slice(6),id);return;}
  if(name==='bank-chart'){chartUi.selectBank(selectedBank);view='chart';await render();return;}
  if(name.startsWith('reference-')){await referenceUi.action(name.slice(10),id);return;}
  if(name==='bank-settings'){referenceUi.selectBank(selectedBank);view='settings';await render();return;}
  if(name==='approval-scope'){approvalScope=id;offset=0;selectedApproval=null;await render();return;}
  if(name==='open-approval'){selectedApproval=id;await render();return;}
  if(name==='back-approvals'){selectedApproval=null;await render();return;}
  if(['approval-approve','approval-reject','approval-cancel'].includes(name)){
    const decision=name.slice(9);
    form(`${titleCase(decision)} request`,reason(),d=>api(`/admin/approvals/${id}/${decision}`,{method:'POST',body:d}),decision==='approve'?'Approve change':`${titleCase(decision)} request`);return;
  }
  const bank=selectedBank?rows.find(r=>r.id===selectedBank):null;
  const base=bank?`/admin/tenants/${bank.id}`:'';
  if(name==='bank-branch-controls'){
    const branches=await api(base+'/branches?limit=100&offset=0');
    if(!branches.items.length)throw new Error('Create a branch before configuring operational controls.');
    form('Branch controls',field('branchId','Branch',branches.items[0].id,{choices:branches.items.map(x=>({value:x.id,label:x.displayName}))})+field('mode','Action','assignment',{choices:[{value:'assignment',label:'Assign account to branch'},{value:'limit',label:'Configure branch or staff limit'}]}),async choice=>{
      const [current,accounts,grants]=await Promise.all([api(base),api(base+'/accounts?limit=100&offset=0'),api(base+'/branches/'+choice.branchId+'/memberships')]);const key=crypto.randomUUID();
      if(choice.mode==='assignment'){
        if(!accounts.items.length)throw new Error('Open an account before assigning it to a branch.');
        form('Assign account to branch',field('accountId','Account',accounts.items[0].id,{choices:accounts.items.map(x=>({value:x.id,label:x.accountNumber+' · '+x.name}))})+reason(),d=>api(base+'/account-branches',{method:'POST',headers:{'Idempotency-Key':key},body:{...d,branchId:choice.branchId,expectedVersion:current.version}}),'Submit for approval');
      }else{
        const configured=await api(base+'/branch-limits');
        const list=configured.items.filter(x=>x.branch_id===choice.branchId).map(x=>'<li>'+e(x.scope_id===choice.branchId?'Branch':grants.find(g=>g.staffId===x.scope_id)?.displayName||'Staff')+' · '+e(x.currency)+' · '+e(x.operation)+' · '+e(x.per_transaction)+' / '+e(x.daily_total)+'</li>').join('');
        form('Branch and staff limits','<ul class="field full">'+list+'</ul>'+field('staffId','Scope','',{choices:[{value:'',label:'Entire branch'},...grants.filter(x=>x.active&&x.role!=='AUDITOR').map(x=>({value:x.staffId,label:x.displayName}))]})+field('currency','Currency','KES',{max:3,pattern:'[A-Z]{3}'})+field('operation','Operation','HOLD',{choices:['HOLD','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL'].map(value=>({value,label:value}))})+field('perTransaction','Per transaction')+field('dailyTotal','Daily total')+reason(),d=>api(base+'/branch-limits',{method:'POST',headers:{'Idempotency-Key':key},body:{...d,staffId:d.staffId||null,branchId:choice.branchId,expectedVersion:configured.version}}),'Submit for approval');
      }
      return {keepOpen:true};
    },'Continue');return;
  }
  if(name==='bank-denominations'){
    const bank=await api('/admin/tenants/'+selectedBank),base='/admin/tenants/'+selectedBank+'/cash-denominations',existing=await api(base),key=crypto.randomUUID();
    form('Cash denomination catalogue','<p class="field full">Existing denominations are retained. Enter accepted notes and coins as NOTE:1000; COIN:10. Each face value has one type. Values and types require independent approval.</p><pre class="field full">'+e(existing.map(d=>d.currency+' '+d.type+':'+d.value).join('; '))+'</pre>'+field('currency','Currency','',{pattern:'[A-Z]{3}',max:3})+field('denominations','Complete catalogue for this currency','',{full:true,max:2000})+reason(),v=>{const denominations=v.denominations.split(';').map(x=>{const [type,value,...extra]=x.trim().split(':');if(extra.length||!['NOTE','COIN'].includes(type)||!/^\d+(\.\d+)?$/.test(value))throw Error('Use NOTE:value or COIN:value separated by semicolons.');return {type,value};});return api(base,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:bank.version,currency:v.currency,denominations,reason:v.reason}});},'Submit for approval');return;
  }
  if(name==='bank-cash-locations'){
    const [bank,branches,ledgers,settings]=await Promise.all([api(base),api(base+'/branches?limit=100&offset=0'),api(base+'/ledger-accounts?limit=100&offset=0'),api(base+'/settings')]);
    if(Number(bank.databaseBinding?.schemaVersion||0)<14){
      form('Prepare cash storage','<p class="field full">Prepare the cash custody tables in this bank’s dedicated database. Then open Cash locations again to register its vaults and tills.</p>'+reason(),d=>api(base+'/ledger-accounts/prepare',{method:'POST',body:{expectedVersion:bank.version,reason:d.reason}}),'Prepare cash storage');return;
    }
    if(!branches.items.some(b=>b.active))throw new Error('Create an active branch before registering cash locations.');
    if(!ledgers.items.some(l=>l.delivery==='APPLIED'&&l.definition.active))throw new Error('Create and approve a dedicated asset ledger before registering cash locations.');
    const key=crypto.randomUUID();
    form('Register vault or till','<p class="field full">Use a dedicated, unused debit-normal asset ledger. Registration starts at zero; record external cash receipts through the branch cash workspace after approval.</p>'+field('branchId','Branch','',{choices:branches.items.filter(b=>b.active).map(b=>({value:b.id,label:b.displayName}))})+field('ledgerId','Dedicated cash ledger','',{choices:ledgers.items.filter(l=>l.delivery==='APPLIED'&&l.definition.active).map(l=>({value:l.definition.id,label:l.definition.code+' · '+l.definition.currency}))})+field('code','Location code','',{pattern:'[A-Z0-9][A-Z0-9_-]{0,31}',max:32})+field('name','Location name','',{max:160})+field('type','Location type','VAULT',{choices:[{value:'VAULT',label:'Vault'},{value:'TILL',label:'Teller till'}]})+reason(),d=>{const ledger=ledgers.items.find(l=>l.definition.id===d.ledgerId).definition;return api(base+'/cash-locations',{method:'POST',headers:{'Idempotency-Key':key},body:{...d,kind:'REGISTER',expectedVersion:bank.version,currency:ledger.currency,businessDate:settings.businessDate,reference:'REGISTER-'+d.code}});},'Submit for approval');return;
  }
  if(name==='bank-limits'){
    const d=await api(base+'/transaction-limits');const key=crypto.randomUUID();
    const table='<div class="field full table-wrap"><table><thead><tr><th>Currency</th><th>Operation</th><th>Per transaction</th><th>Daily total</th></tr></thead><tbody>'+d.items.map(x=>'<tr><td>'+e(x.currency)+'</td><td>'+e(x.operation)+'</td><td>'+e(x.perTransaction)+'</td><td>'+e(x.dailyTotal)+'</td></tr>').join('')+'</tbody></table></div>';
    form('Transaction limits',table+(d.missing?.length?'<p class="field full">Missing policies: '+d.missing.map(x=>e(x.currency+' '+x.operation)).join(', ')+'.</p>':'')+'<p class="field full">Set bank-wide limits for one currency and operation. Another administrator must approve the change. Approved transactions awaiting posting count toward the daily total. Transactions are blocked when their currency and operation have no approved bank-wide policy.</p>'+field('currency','Currency','KES',{max:3,pattern:'[A-Z]{3}'})+field('operation','Operation','WITHDRAWAL',{choices:['FUNDING','TRANSFER','WITHDRAWAL','FUNDING_REVERSAL','HOLD','CASH_RECEIVE','CASH_DISPATCH','CASH_TRANSFER','CASH_DEPOSIT','CASH_WITHDRAWAL','CASH_SHORTAGE','CASH_OVERAGE','CASH_REVERSAL','WITHDRAWAL_REVERSAL','TRANSFER_REVERSAL','FX_CONVERSION','FX_REVERSAL','TD_OPEN','TD_REDEEM','LOAN_DISBURSE','LOAN_REPAY','TD_OPEN_REVERSAL','LOAN_DISBURSE_REVERSAL','LOAN_REPAY_REVERSAL','TD_EARLY_CLOSE','TD_RENEW','LOAN_IMPAIR','LOAN_RESTRUCTURE','LOAN_WRITE_OFF','LOAN_FEE_ASSESS','LOAN_FEE_PAY','LOAN_FEE_WAIVE','CLOSURE_SETTLEMENT','CLOSURE_SETTLEMENT_REVERSAL'].map(value=>({value,label:value}))})+field('perTransaction','Per transaction','',{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('dailyTotal','Daily total','',{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+reason(),values=>api(base+'/transaction-limits',{method:'POST',headers:{'Idempotency-Key':key},body:{...values,expectedVersion:d.version}}),'Submit for approval');return;
  }
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
    form('Register a bank',field('bankCode','Bank code','',{max:32,pattern:'[A-Z][A-Z0-9_]{2,31}',format:'Bank code: use 3–32 uppercase letters, digits or underscores, starting with a letter (for example SBI_01).',help:'Example: SBI_01. Use 3–32 uppercase letters, digits or underscores; start with a letter.'})+field('displayName','Display name')+field('defaultLanguage','Default language','en-IN',{max:35})+field('databaseReference','Approved database reference','',{max:64,pattern:'[a-z][a-z0-9\\-]{2,63}',format:'Database reference: use 3–64 lowercase letters, digits or hyphens, starting with a letter (for example sbi-01-db).',help:'Example format: sbi-01-db. An administrator must configure and approve this database first; entering a name here does not create it.'})+entityFields(),async d=>{
      const body={bankCode:d.bankCode,displayName:d.displayName,defaultLanguage:d.defaultLanguage,databaseReference:d.databaseReference,legalEntity:entity(d)};
      const next=JSON.stringify(body);if(serialized&&serialized!==next)throw new Error('This registration was already attempted. Close and reopen the form before changing its details.');serialized=next;
      await api('/admin/tenants',{method:'POST',body,headers:{'Idempotency-Key':key}});
    },'Register bank');return;
  }
  if(name==='new-staff'){form('Register a staff identity',field('subject','Identity provider subject','',{full:true,help:'Use the stable subject identifier supplied by your identity provider.'})+field('displayName','Display name')+field('email','Email','',{type:'email',required:false,max:254})+reason(),d=>api('/admin/staff',{method:'POST',body:d}),'Register staff');return;}
  if(name==='edit-staff'){
    const r=rows.find(x=>x.id===id), key=crypto.randomUUID();form(`Manage ${r.displayName}`,`<div class="field full">CBS staff ID<span class="mono">${e(r.id)}</span><small>Profile and access updates need approval by another administrator. Session revocation takes effect immediately.</small></div>`+field('displayName','Display name',r.displayName)+field('email','Email',r.email||'',{type:'email',required:false,max:254})+field('active','Access',String(r.active),{choices:activeChoices})+field('operation','Action','update',{choices:[{value:'update',label:'Submit identity update for approval'},{value:'revoke',label:'Revoke all sessions immediately'}]})+reason(),d=>api(`/admin/staff/${id}${d.operation==='revoke'?'/revoke-sessions':''}`,{method:d.operation==='revoke'?'POST':'PATCH',headers:{'Idempotency-Key':key},body:d.operation==='revoke'?{expectedVersion:r.version,reason:d.reason}:{expectedVersion:r.version,displayName:d.displayName,email:d.email,active:d.active==='true',reason:d.reason}}),'Submit');return;
  }
  if(name==='end-session'){form('End this session?',`<p class="intro field full">The selected session will lose access on its next request.</p>`,async()=>{await api(`/access/sessions/${id}`,{method:'DELETE'});if(id===me.sessionId)clearSession();},'End session');return;}
  if(name.startsWith('bank-')&&['provision','suspend','resume'].includes(name.slice(5))){const operation=name.slice(5), key=crypto.randomUUID();form(`${titleCase(operation)} ${bank.displayName}`,(operation==='provision'?'':'<p class="field full approval-note">This change will take effect only after another platform administrator approves it.</p>')+reason(),async d=>{const result=await api(`${base}/${operation}`,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:bank.version,reason:d.reason}});if(result.status==='PROVISIONING_FAILED'){dialog.close();await render();throw new Error(`Provisioning needs attention: ${result.databaseBinding.failureCode}. Refresh the bank before retrying.`);}return result;},operation==='provision'?'Provision':'Submit for approval');return;}
  if(name==='edit-bank'){form('Edit bank profile',field('displayName','Display name',bank.displayName)+`<p class="field full">Default language: ${e(bank.defaultLanguage)}. Change this in Bank settings, with approval.</p>`+reason(),d=>api(base,{method:'PATCH',body:{...d,defaultLanguage:bank.defaultLanguage,expectedVersion:bank.version}}));return;}
  if(name==='new-entity'){form('Add legal entity',entityFields()+reason(),d=>api(`${base}/legal-entities`,{method:'POST',body:{legalEntity:entity(d),expectedVersion:bank.version,reason:d.reason}}));return;}
  if(name==='bank-membership'){form('Manage bank membership',field('subject','Identity subject','',{full:true,help:'Register the staff identity first.'})+field('role','Bank role','BANK_OPERATOR',{choices:roleChoices(['BANK_OPERATOR','BANK_AUDITOR'])})+field('active','Access','true',{choices:activeChoices})+reason(),d=>api(`${base}/memberships`,{method:'PUT',body:{...d,active:d.active==='true',expectedVersion:bank.version}}));return;}
  if(name==='new-branch'){form('Add a branch',field('code','Branch code','',{max:32})+field('displayName','Branch name')+field('legalEntityId','Legal entity',bank.legalEntities[0]?.id,{full:true,choices:bank.legalEntities.map(l=>({value:l.id,label:l.legalName}))})+reason(),d=>api(`${base}/branches`,{method:'POST',body:d}));return;}
  if(name==='edit-branch'){const b=rows.find(r=>r.id===id);form('Edit branch',field('displayName','Branch name',b.displayName)+field('active','Status',String(b.active),{choices:activeChoices})+reason(),d=>api(`${base}/branches/${id}`,{method:'PATCH',body:{...d,active:d.active==='true',expectedVersion:b.version}}));return;}
  if(name==='branch-grant'){const b=rows.find(r=>r.id===id);form('Manage branch access',field('staffId','CBS staff ID','',{full:true,help:'Use the staff record UUID from the staff directory. Bank membership is required.'})+field('role','Branch role','TELLER',{choices:roleChoices(['TELLER','BRANCH_MANAGER','AUDITOR'])})+field('active','Access','true',{choices:activeChoices})+reason(),d=>api(`${base}/branches/${id}/memberships`,{method:'PUT',body:{...d,active:d.active==='true',expectedVersion:b.version}}));return;}
  if(name==='branch-members'){const grants=await api(`${base}/branches/${id}/memberships`);form('Branch access',`<div class="field full">${table(['Staff','Role','Access'],grants.map(g=>`<tr><td>${e(g.displayName)}</td><td>${e(titleCase(g.role))}</td><td>${badge(g.active?'ACTIVE':'DISABLED')}</td></tr>`).join(''),'No branch access assigned.')}</div>`,async()=>{},'Done');}
}
document.addEventListener('click',async event=>{
  const nav=event.target.closest('[data-view]');if(nav){view=nav.dataset.view;offset=0;selectedBank=null;selectedApproval=null;await render();return;}
  const target=event.target.closest('[data-action]');if(target&&!target.disabled){try{await action(target.dataset.action,target.dataset.id);}catch(error){if(dialog.open)document.querySelector('#form-error').textContent=error.message;else notify(error.message,true);}}
});
document.addEventListener('input',event=>{if(event.target.id==='search')directory(event.target.value);});
document.addEventListener('keydown',async event=>{if(event.target.id==='product-search'&&event.key==='Enter'){event.preventDefault();try{await productUi.action('search');}catch(error){notify(error.message,true);}}});
document.addEventListener('keydown',async event=>{if(event.target.id==='chart-search'&&event.key==='Enter'){event.preventDefault();try{await chartUi.action('search');}catch(error){notify(error.message,true);}}});
document.addEventListener('change',async event=>{
  if(event.target.id==='account-bank'){accountUi.selectBank(event.target.value);await render();}
  if(event.target.id==='account-state'){try{await accountUi.action('search');}catch(error){notify(error.message,true);}}
  if(event.target.id==='customer-bank'){customerUi.selectBank(event.target.value);await render();}
  if(event.target.id==='customer-state'){try{await customerUi.action('search');}catch(error){notify(error.message,true);}}
  if(event.target.id==='product-bank'){productUi.selectBank(event.target.value);await render();}
  if(event.target.id==='product-type'||event.target.id==='product-active'){try{await productUi.action('search');}catch(error){notify(error.message,true);}}
  if(event.target.id==='day-bank'){dayCloseUi.selectBank(event.target.value);await render();}
  if(event.target.id==='report-bank'){reportUi.selectBank(event.target.value);await render();}
  if(['report-kind','report-entity','report-currency'].includes(event.target.id))reportUi.changed();
  if(event.target.id==='journal-bank'){journalUi.selectBank(event.target.value);await render();}
  if(event.target.id==='ledger-bank'){ledgerUi.selectBank(event.target.value);await render();}
  if(event.target.id==='ledger-currency'||event.target.id==='ledger-active'){try{await ledgerUi.action('search');}catch(error){notify(error.message,true);}}
  if(event.target.id==='chart-bank'){chartUi.selectBank(event.target.value);await render();}
  if(event.target.id==='chart-category'||event.target.id==='chart-active'){try{await chartUi.action('search');}catch(error){notify(error.message,true);}}
  if(view==='chart'&&event.target.name==='category')chartUi.categoryChanged(event.target.value);
  if(event.target.id==='reference-bank'){referenceUi.selectBank(event.target.value);await render();}
  if(event.target.id==='ui-language'){
    try{if(me)await api('/access/preferences',{method:'PUT',body:{language:event.target.value}});setLanguage(event.target.value);await render();if(me)notify(t('Language preference saved.'));}
    catch(error){event.target.value=getLanguage();notify(error.message,true);}
  }
  if(event.target.id==='approval-type'){approvalAction=event.target.value;offset=0;await render();}
  if(event.target.id==='approval-bank'){approvalBank=event.target.value;offset=0;await render();}
});
document.querySelector('#editor').addEventListener('invalid',()=>{
  const messages=Array.from(document.querySelectorAll('#editor :invalid'),input=>input.validity.patternMismatch&&input.title?input.title:`${input.closest('label')?.firstChild.textContent.trim()||'Field'}: ${input.validationMessage}`);
  document.querySelector('#form-error').textContent=messages.join(' ');
},true);
dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
document.querySelector('#editor').addEventListener('submit',async event=>{
  event.preventDefault();if(busy)return;busy=true;document.querySelector('#save').disabled=true;document.querySelector('#form-error').textContent='';
  try{const result=await submitForm(Object.fromEntries(new FormData(event.target)));if(result?.keepOpen)return;dialog.close();await render();notify(result?.status==='PENDING'?'Request submitted. An independent reviewer must approve it.':result?.status==='STALE'?'The record changed. No change was applied; submit a new request.':'Changes saved.');}
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
        try{user=await manager.signinRedirectCallback();me=await api('/access/me');setLanguage((await api('/access/preferences')).language);document.querySelector('#auth-button').textContent='End CBS session';}
        catch(error){clearSession();notify(signInErrorMessage(error),true);}
        finally{history.replaceState({},'',location.pathname);}
      }
    }
    await render();
  }catch(error){configuration={configured:false};signedOut();notify(error.message,true);document.querySelector('#connection').textContent='Connection unavailable';}
}
boot();
