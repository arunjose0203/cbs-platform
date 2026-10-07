// Isolated UI fixture server. Never connects to CBS, an identity provider, or a database.
// Run: node ui/test/approval-preview.mjs; open http://127.0.0.1:18082.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
const root=fileURLToPath(new URL('../../',import.meta.url));
const source=await readFile(new URL('../src/app.js',import.meta.url),'utf8');
const fixtureView=process.argv.includes('--branch')?'branchwork':process.argv.includes('--funding')?'accounts':process.argv.includes('--accounts')?'accounts':process.argv.includes('--customers')?'customers':process.argv.includes('--products')?'products':process.argv.includes('--dayclose')?'dayclose':process.argv.includes('--reports')?'reports':process.argv.includes('--journals')?'journals':process.argv.includes('--ledgers')?'ledgers':process.argv.includes('--chart')?'chart':process.argv.includes('--settings')?'settings':'approvals';
const result=await build({stdin:{contents:source.replace(/boot\(\);\s*$/,`configuration={configured:true};user={access_token:'fixture-only'};me={platformAdmin:true,staff:{subject:'checker.demo'}};view='${fixtureView}';document.querySelector('#connection').textContent='UI test fixtures';render();`),resolveDir:root+'ui/src',sourcefile:'approval-preview.js'},bundle:true,write:false,format:'esm'});
const bankId='10000000-0000-0000-0000-000000000001';
const settings={bankId,version:1,currencies:[{code:'KES',roundingMode:'HALF_EVEN',enabled:true}],languages:['en','sw'],defaultLanguage:'en',businessTimezone:'Africa/Nairobi',businessDate:'2026-09-23',weekendDays:[6,7],holidays:[{date:'2026-12-25',name:'Christmas Day'}]};
const catalogue={currencies:[{code:'KES',name:'Kenyan Shilling',decimalPlaces:2},{code:'USD',name:'US Dollar',decimalPlaces:2},{code:'JPY',name:'Japanese Yen',decimalPlaces:0}],languages:[{code:'en',name:'English'},{code:'fr',name:'French'},{code:'sw',name:'Swahili'}],timezones:['Africa/Nairobi','Africa/Lagos','Africa/Johannesburg'],roundingModes:['HALF_EVEN','HALF_UP','DOWN']};
let preference='en';
const adjustmentPreview=process.argv.includes('--adjustment');
const entityId='60000000-0000-0000-0000-000000000001';
let ledgerReady=!process.argv.includes('--unprepared');
const ledgerRows=[{definition:{id:'70000000-0000-0000-0000-000000000001',bankId,code:'CASH_KES',name:'Cash in Kenyan shillings',chartAccountId:'40000000-0000-0000-0000-000000000002',legalEntityId:entityId,currency:'KES',active:true,version:0},chartCode:'1100',legalEntityName:'Demo Bank Kenya Limited',delivery:'APPLIED',requestId:'80000000-0000-0000-0000-000000000001',failureCode:null},
  {definition:{id:'70000000-0000-0000-0000-000000000002',bankId,code:'DEPOSITS_KES',name:'Customer deposits KES',chartAccountId:'40000000-0000-0000-0000-000000000004',legalEntityId:entityId,currency:'KES',active:true,version:0},chartCode:'2100',legalEntityName:'Demo Bank Kenya Limited',delivery:'PENDING',requestId:'80000000-0000-0000-0000-000000000002',failureCode:'LEDGER_DATABASE_UNAVAILABLE'}];
const chartRows=[
  {id:'40000000-0000-0000-0000-000000000001',code:'1000',name:'Assets',category:'ASSET',normalBalance:'DEBIT',kind:'GROUP',parentId:null,parentCode:null},
  {id:'40000000-0000-0000-0000-000000000002',code:'1100',name:'Cash and cash equivalents',category:'ASSET',normalBalance:'DEBIT',kind:'POSTING',parentId:'40000000-0000-0000-0000-000000000001',parentCode:'1000'},
  {id:'40000000-0000-0000-0000-000000000003',code:'2000',name:'Liabilities',category:'LIABILITY',normalBalance:'CREDIT',kind:'GROUP',parentId:null,parentCode:null},
  {id:'40000000-0000-0000-0000-000000000004',code:'2100',name:'Customer deposits',category:'LIABILITY',normalBalance:'CREDIT',kind:'POSTING',parentId:'40000000-0000-0000-0000-000000000003',parentCode:'2000'},
  {id:'40000000-0000-0000-0000-000000000005',code:'5000',name:'Operating expenses',category:'EXPENSE',normalBalance:'DEBIT',kind:'GROUP',parentId:null,parentCode:null}
].map(a=>({...a,description:'Fixture account for browser verification',active:true}));
if(['journals','reports','accounts'].includes(fixtureView))ledgerRows[1].delivery='APPLIED';
const journalRows=[{entry:{id:'90000000-0000-0000-0000-000000000001',bankId,reference:'OPEN-001',narration:'Opening cash balance',businessDate:settings.businessDate,legalEntityId:entityId,currency:'KES',lines:[{ledgerId:ledgerRows[0].definition.id,side:'DEBIT',amount:'25000.00'},{ledgerId:ledgerRows[1].definition.id,side:'CREDIT',amount:'25000.00'}],reversalOf:null},postingStatus:'POSTED',failureCode:null},
{entry:{id:'90000000-0000-0000-0000-000000000002',bankId,reference:'ADJUST-002',narration:'Approved adjustment awaiting posting',businessDate:settings.businessDate,legalEntityId:entityId,currency:'KES',lines:[{ledgerId:ledgerRows[0].definition.id,side:'DEBIT',amount:'100.00'},{ledgerId:ledgerRows[1].definition.id,side:'CREDIT',amount:'100.00'}],reversalOf:null},postingStatus:'AWAITING_POSTING',failureCode:'JOURNAL_DATABASE_UNAVAILABLE'}];
const productRows=[{id:'92000000-0000-0000-0000-000000000001',code:'SAV_KES',name:'Everyday Savings',description:'Savings in Kenyan shillings',productType:'SAVINGS',currency:'KES',minimumOpening:'1000',minimumBalance:'100',liabilityAccountId:chartRows[3].id,liabilityAccountCode:'2100',active:true,revision:1},
{id:'92000000-0000-0000-0000-000000000002',code:'CUR_KES',name:'Business Current',description:'Current account definition',productType:'CURRENT',currency:'KES',minimumOpening:'5000',minimumBalance:'1000',liabilityAccountId:chartRows[3].id,liabilityAccountCode:'2100',active:false,revision:1}];
const customerProfiles=[{id:'93000000-0000-0000-0000-000000000001',bankId,customerNumber:'C000001',version:0,profile:{fullName:'Amina Demo',birthDate:'1990-05-20',nationality:'KE',residenceCountry:'KE',address:'Fictional demonstration address',phone:'+254700000000',email:'amina@example.test',documentType:'NATIONAL_ID',documentCountry:'KE',documentNumber:'TEST001',documentExpiry:null,documentReference:'DEMO/DOC-001',state:'PROSPECT',reviewReference:''}}];
const requests=[
  {id:'20000000-0000-0000-0000-000000000001',action:'BANK_SUSPEND',targetId:bankId,bankId,targetLabel:'Demo Bank Kenya',makerSubject:'maker.demo',beforeValues:{status:'ACTIVE'},proposedValues:{status:'SUSPENDED'}},
  {id:'20000000-0000-0000-0000-000000000002',action:'STAFF_UPDATE',targetId:'30000000-0000-0000-0000-000000000001',bankId:null,targetLabel:'Demo teller',makerSubject:'checker.demo',beforeValues:{displayName:'Demo teller',email:'demo@example.test',active:true},proposedValues:{displayName:'Demo teller',email:'demo@example.test',active:false}},
  {id:'20000000-0000-0000-0000-000000000003',action:'BANK_RESUME',targetId:bankId,bankId,targetLabel:'Demo Bank Kenya',makerSubject:'maker.demo',beforeValues:{status:'SUSPENDED'},proposedValues:{status:'ACTIVE'}}
].map(r=>({...r,expectedVersion:1,reason:'Verify the approval workflow using isolated fixtures',status:'PENDING',reviewerSubject:null,decisionReason:null,createdAt:new Date().toISOString(),decidedAt:null}));
const events=new Map(requests.map(r=>[r.id,[{id:r.id,actorSubject:r.makerSubject,action:'SUBMITTED',reason:r.reason,occurredAt:r.createdAt}]]));
createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://127.0.0.1:18082');
    const send=(body,type='application/json')=>{res.setHeader('Content-Type',type);res.end(type==='application/json'?JSON.stringify(body):body);};
    if(url.pathname==='/admin/app.js')return send(result.outputFiles[0].text,'text/javascript');
    if(url.pathname==='/admin/styles.css')return send(await readFile(root+'src/main/resources/static/admin/styles.css','utf8'),'text/css');
    if(url.pathname==='/admin/chart.css')return send(await readFile(root+'src/main/resources/static/admin/chart.css','utf8'),'text/css');
    if(url.pathname==='/'||url.pathname==='/admin/index.html')return send(await readFile(root+'src/main/resources/static/admin/index.html','utf8'),'text/html');
    if(url.pathname==='/api/v1/banks/operations/branches')return send([{bank_id:bankId,branch_id:'b0000000-0000-0000-0000-000000000001',bank_name:'Demo Bank Kenya',branch_name:'Nairobi Main',role:adjustmentPreview?'BRANCH_MANAGER':'TELLER'}]);
    if(url.pathname.endsWith('/operations/cash/adjustment-ledgers'))return send([{id:'e0000000-0000-0000-0000-000000000001',code:'CASH_LOSS_KES',name:'Cash shortage expense',category:'EXPENSE'}]);
    if(url.pathname.endsWith('/operations/cash/exceptions')){return send(adjustmentPreview?[{id:'c0000000-0000-0000-0000-000000000001',location_id:'c0000000-0000-0000-0000-000000000002',code:'TILL_01',currency:'KES',book_amount:'10000',counted_amount:'9500',difference:'-500',reporter:'teller.demo',resolved_at:null,pending_adjustment:null}]:[]);}
    if(url.pathname.startsWith('/api/v1/banks/')&&url.pathname.endsWith('/operations/cash')){
      if(req.method==='POST'){let body='';for await(const chunk of req)body+=chunk;res.statusCode=202;return send({id:crypto.randomUUID(),status:'PENDING',proposedValues:JSON.parse(body)});}
      return send({version:1,businessDate:settings.businessDate,storageReady:true,items:[
       {location:{id:'c0000000-0000-0000-0000-000000000001',code:'MAIN_VAULT',name:'Nairobi main vault',type:'VAULT',currency:'KES'},balance:'90000',ledgerBalance:'90000',reconciled:true,sessionOpen:false},
       {location:{id:'c0000000-0000-0000-0000-000000000002',code:'TILL_01',name:'Counter one',type:'TILL',currency:'KES'},balance:'10000',ledgerBalance:'10000',reconciled:true,sessionOpen:true,custodian:'checker.demo'}
      ],accounts:[{id:'94000000-0000-0000-0000-000000000001',account_number:'DEMO001',currency:'KES',state:'ACTIVE',liability_ledger_id:ledgerRows[1].definition.id}],requests:[],deliveries:[],limit:25,offset:0});
    }
    if(url.pathname.startsWith('/api/v1/banks/')&&url.pathname.endsWith('/operations/accounts'))return send({version:1,businessDate:settings.businessDate,items:[{id:'94000000-0000-0000-0000-000000000001',account_number:'DEMO001',currency:'KES',state:'ACTIVE',delivery:'APPLIED'}],limit:25,offset:0});
    if(url.pathname.startsWith('/api/v1/banks/')&&url.pathname.endsWith('/operations/requests')){
      if(req.method==='POST'){let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);res.statusCode=202;return send({id:crypto.randomUUID(),status:'PENDING',proposedValues:input});}
      return send({items:[],limit:25,offset:0});
    }
    if(url.pathname==='/api/v1/admin/tenants')return send({items:[{id:bankId,displayName:'Demo Bank Kenya',bankCode:'DEMO_KE',status:'ACTIVE',legalEntities:[{id:entityId,legalName:'Demo Bank Kenya Limited',functionalCurrency:'KES'}]}],limit:100,offset:0});
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/account-operations')){
      if(req.method==='POST'){let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);const request={id:crypto.randomUUID(),action:'ACCOUNT_OPERATION',targetId:bankId,bankId,targetLabel:'Amina Demo savings',makerSubject:'checker.demo',beforeValues:{},proposedValues:input,expectedVersion:1,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);}
      return send({version:1,storageReady:true,position:{posted:'2500.50',held:'500',available:'2000.50',withdrawable:'1900.50',reconciled:true},items:[{entry:{id:'96000000-0000-0000-0000-000000000001',change:{kind:'HOLD',amount:'500',reference:'DEMO-HOLD-001'}},status:'POSTED'}],total:1,limit:25,offset:0});
    }
    if(url.pathname.endsWith('/statement'))return send({accountNumber:'A94000000000000000000000000000001',currency:'KES',from:url.searchParams.get('from'),to:url.searchParams.get('to'),openingBalance:'0',closingBalance:'2500.50',items:[{transactionId:'95000000-0000-0000-0000-000000000001',businessDate:settings.businessDate,reference:'DEMO-RECEIPT-001',narration:'Verified fictional deposit',debit:'0',credit:'2500.50',balance:'2500.50'}],total:1,limit:25,offset:0});
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/funding')){
      if(req.method==='POST'){
        if(url.pathname.endsWith('/retry')){res.statusCode=204;return res.end();}
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const request={id:crypto.randomUUID(),action:'ACCOUNT_FUNDING',targetId:bankId,bankId,targetLabel:'Amina Demo savings',makerSubject:'checker.demo',beforeValues:{},proposedValues:{accountNumber:'A94000000000000000000000000000001',amount:input.amount,currency:'KES',reference:input.reference,businessDate:input.businessDate,lines:[{ledger:'CASH_KES',side:'DEBIT',amount:input.amount},{ledger:'DEPOSITS_KES',side:'CREDIT',amount:input.amount}]},expectedVersion:input.expectedVersion,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      const a={id:'94000000-0000-0000-0000-000000000001',bankId,accountNumber:'A94000000000000000000000000000001',customerId:customerProfiles[0].id,customerNumber:'C000001',legalEntityId:entityId,legalEntityName:'Demo Bank Kenya Limited',name:'Amina Demo savings',state:'ACTIVE',version:1,terms:productRows[0],openedAt:'2026-10-06T07:00:00Z'};
      return send({version:1,storageReady:true,account:{definition:a,delivery:'APPLIED'},balance:{accountId:a.id,currency:'KES',postedBalance:'2500.50',ledgerBalance:'2500.50',reconciled:true,liabilityLedgerId:ledgerRows[1].definition.id},items:[{entry:{id:'95000000-0000-0000-0000-000000000001',amount:'2500.50',journal:{reference:'DEMO-RECEIPT-001',businessDate:settings.businessDate,currency:'KES'}},status:'POSTED',failureCode:null}],total:1,limit:25,offset:0});
    }
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/accounts')){
      if(req.method==='POST'){
        if(url.pathname.endsWith('/prepare')||url.pathname.endsWith('/retry')){res.statusCode=204;return res.end();}
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body),p=productRows.find(p=>p.id===input.productId);
        const request={id:crypto.randomUUID(),action:'DEPOSIT_ACCOUNT',targetId:bankId,bankId,targetLabel:'Deposit account opening',makerSubject:'checker.demo',beforeValues:{},proposedValues:{name:input.name,customerNumber:'C000001',state:input.state,productCode:p.code,productRevision:input.productRevision,currency:p.currency,minimumOpening:p.minimumOpening,minimumBalance:p.minimumBalance,legalEntityName:'Demo Bank Kenya Limited'},expectedVersion:input.expectedVersion,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      const d={id:'94000000-0000-0000-0000-000000000001',bankId,accountNumber:'A94000000000000000000000000000001',customerId:customerProfiles[0].id,customerNumber:'C000001',legalEntityId:entityId,legalEntityName:'Demo Bank Kenya Limited',name:'Amina Demo savings',state:process.argv.includes('--funding')?'ACTIVE':'AWAITING_FUNDING',version:0,terms:productRows[0],openedAt:'2026-10-06T07:00:00Z'};
      if(url.pathname.endsWith('/'+d.id))return send({definition:d,delivery:'APPLIED',requestId:d.id,failureCode:null});
      return send({bankId,version:1,storageReady:true,total:1,items:[{...d,productCode:d.terms.code,currency:d.terms.currency,delivery:'APPLIED',requestId:d.id}],limit:25,offset:0});
    }
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/customers')){
      if(req.method==='POST'){
        if(url.pathname.endsWith('/prepare')||url.pathname.endsWith('/retry')){res.statusCode=204;return res.end();}
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const request={id:crypto.randomUUID(),action:'CUSTOMER_CHANGE',targetId:bankId,bankId,targetLabel:'New individual customer',makerSubject:'checker.demo',beforeValues:{},proposedValues:{...input.profile,documentNumber:'••••'+input.profile.documentNumber.replace(/[ -]/g,'').slice(-2),duplicateReview:input.duplicateReview},expectedVersion:input.expectedVersion,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      const record=customerProfiles.find(c=>url.pathname.endsWith('/'+c.id));if(record)return send({definition:record,delivery:'APPLIED',requestId:record.id,failureCode:null});
      return send({bankId,version:1,storageReady:true,total:1,items:customerProfiles.map(d=>({id:d.id,customerNumber:d.customerNumber,fullName:d.profile.fullName,state:fixtureView==='accounts'?'ACTIVE':d.profile.state,version:d.version,delivery:'APPLIED',requestId:d.id,failureCode:null})),limit:25,offset:0});
    }
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/products')){
      if(url.pathname.endsWith('/revisions'))return send({items:[{revision:1,definition:productRows.find(p=>url.pathname.includes(p.id)),approvedBy:'reviewer.demo',reason:'Approved fixture definition',createdAt:'2026-10-06T06:30:00Z'}],limit:25,offset:0});
      if(req.method==='POST'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);const {expectedVersion,reason,productId,...proposedValues}=input;proposedValues.liabilityAccountCode=chartRows.find(a=>a.id===input.liabilityAccountId)?.code;
        const request={id:crypto.randomUUID(),action:'DEPOSIT_PRODUCT',targetId:bankId,bankId,targetLabel:input.code+' — '+input.name,makerSubject:'checker.demo',beforeValues:input.productId?productRows.find(p=>p.id===input.productId):{},proposedValues,expectedVersion,reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      const q=url.searchParams,offset=Number(q.get('offset')||0),limit=Number(q.get('limit')||25);
      const items=productRows.filter(p=>(!q.get('type')||p.productType===q.get('type'))&&(!q.get('active')||String(p.active)===q.get('active'))&&(p.code+' '+p.name).toLowerCase().includes((q.get('search')||'').toLowerCase()));
      return send({bankId,version:1,total:items.length,items:items.slice(offset,offset+limit),limit,offset});
    }
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/business-day')){
      if(req.method==='POST'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body||'{}');
        const request={id:crypto.randomUUID(),action:'DAY_CLOSE',targetId:bankId,bankId,targetLabel:'Close '+input.businessDate,makerSubject:'checker.demo',beforeValues:{businessDate:input.businessDate},proposedValues:input,expectedVersion:input.expectedVersion,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      return send({bankId,version:1,businessDate:'2026-10-05',nextBusinessDate:'2026-10-06',closedThrough:'2026-10-02',storageReady:true,blockers:[],history:[{requestId:'91000000-0000-0000-0000-000000000001',businessDate:'2026-10-02',nextBusinessDate:'2026-10-05',status:'CLOSED',failureCode:null,maker:'maker.demo',checker:'checker.demo',totals:[{legalEntityId:entityId,currency:'KES',debitBalance:'25000',creditBalance:'25000'}],completedAt:'2026-10-02T15:00:00Z'}],limit:25,offset:0});
    }
    if(url.pathname==='/api/v1/admin/tenants/'+bankId+'/accounting-reports'){
      const kind=url.searchParams.get('kind'),isStatement=kind==='LEDGER_STATEMENT',isCheck=kind==='RECONCILIATION';
      const report={kind,bankId,bankName:'Demo Bank Kenya',legalEntityId:entityId,legalEntityName:'Demo Bank Kenya Limited',currency:'KES',fromDate:isStatement?url.searchParams.get('fromDate'):null,toDate:isCheck?null:url.searchParams.get('toDate'),generatedAt:new Date().toISOString(),reconciled:!isCheck,
      columns:isStatement?['Business date','Reference','Narration','Debit','Credit','Running net debit']:isCheck?['Ledger code','Journal debits','Stored debits','Debit difference','Result']:['Ledger code','Ledger name','Total debits','Total credits','Debit balance','Credit balance'],
      rows:isStatement?[[settings.businessDate,'OPEN-001','Opening cash balance','25000.00','0','25000.00']]:isCheck?[['CASH_KES','25000','25001','1','MISMATCH']]:[['CASH_KES','Cash in Kenyan shillings','25000','0','25000','0'],['DEPOSITS_KES','Customer deposits','0','25000','0','25000']],
      summary:isStatement?{'Opening net debit':'0','Period debits':'25000','Period credits':'0','Closing net debit':'25000'}:isCheck?{'Accounts checked':'1','Mismatched accounts':'1'}:{'Debit balance':'25000','Credit balance':'25000','Difference':'0'}};
      if(url.searchParams.get('csv')==='true')return send([report.columns,...report.rows].map(r=>r.map(v=>JSON.stringify(v)).join(',')).join('\r\n'),'text/csv');
      return send(report);
    }
    if(url.pathname.startsWith('/api/v1/admin/tenants/'+bankId+'/journals')){
      const base='/api/v1/admin/tenants/'+bankId+'/journals';
      if(url.pathname===base+'/balances')return send({items:ledgerRows.map((r,i)=>({ledgerId:r.definition.id,code:r.definition.code,name:r.definition.name,legalEntityId:entityId,currency:'KES',debit:i?'0':'25000.00',credit:i?'25000.00':'0',netDebit:i?'-25000.00':'25000.00'})),limit:25,offset:0});
      if(url.pathname.endsWith('/retry')){const row=journalRows.find(r=>url.pathname.includes(r.entry.id));row.postingStatus='POSTED';row.failureCode=null;res.statusCode=204;return res.end();}
      if(req.method==='POST'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const request={id:crypto.randomUUID(),action:'JOURNAL_POST',targetId:bankId,bankId,targetLabel:input.reference,makerSubject:'checker.demo',beforeValues:{},proposedValues:input,expectedVersion:1,reason:input.reason,status:'PENDING',createdAt:new Date().toISOString()};
        requests.push(request);events.set(request.id,[]);res.statusCode=202;return send(request);
      }
      return send({bankId,version:1,storageReady:ledgerReady,total:journalRows.length,items:journalRows,limit:25,offset:0});
    }
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/ledger-accounts/prepare`){ledgerReady=true;res.statusCode=204;return res.end();}
    if(url.pathname.startsWith(`/api/v1/admin/tenants/${bankId}/ledger-accounts/deliveries/`)){
      const row=ledgerRows.find(a=>url.pathname.endsWith(`/${a.requestId}/retry`));if(row){row.delivery='APPLIED';row.failureCode=null;}res.statusCode=204;return res.end();
    }
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/ledger-accounts`){
      if(req.method==='POST'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const {expectedVersion,reason,...proposedValues}=input;
        const request={id:crypto.randomUUID(),action:'LEDGER_ACCOUNT',targetId:bankId,bankId,targetLabel:`${input.code} — ${input.name}`,makerSubject:'checker.demo',beforeValues:input.ledgerId?ledgerRows.find(a=>a.definition.id===input.ledgerId)?.definition:{},proposedValues,expectedVersion,reason,status:'PENDING',reviewerSubject:null,decisionReason:null,createdAt:new Date().toISOString(),decidedAt:null};
        requests.push(request);events.set(request.id,[{id:request.id,actorSubject:request.makerSubject,action:'SUBMITTED',reason,occurredAt:request.createdAt}]);res.statusCode=202;return send(request);
      }
      const q=url.searchParams,offset=Number(q.get('offset')||0),limit=Number(q.get('limit')||25);
      const filtered=ledgerRows.filter(r=>(!q.get('currency')||r.definition.currency===q.get('currency'))&&(!q.get('active')||String(r.definition.active)===q.get('active'))&&`${r.definition.code} ${r.definition.name}`.toLowerCase().includes((q.get('search')||'').toLowerCase()));
      return send({bankId,version:1,storageReady:ledgerReady,total:filtered.length,items:filtered.slice(offset,offset+limit),limit,offset});
    }
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/chart-of-accounts`){
      if(req.method==='POST'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const {expectedVersion,reason,...proposedValues}=input;
        const request={id:crypto.randomUUID(),action:'CHART_ACCOUNT',targetId:bankId,bankId,targetLabel:`${input.code} — ${input.name}`,makerSubject:'checker.demo',beforeValues:input.accountId?chartRows.find(a=>a.id===input.accountId):{},proposedValues,expectedVersion,reason,status:'PENDING',reviewerSubject:null,decisionReason:null,createdAt:new Date().toISOString(),decidedAt:null};
        requests.push(request);events.set(request.id,[{id:request.id,actorSubject:request.makerSubject,action:'SUBMITTED',reason,occurredAt:request.createdAt}]);res.statusCode=202;return send(request);
      }
      const query=url.searchParams,limit=Number(query.get('limit')||25),offset=Number(query.get('offset')||0);
      const filtered=chartRows.filter(a=>(!query.get('category')||a.category===query.get('category'))&&(!query.get('kind')||a.kind===query.get('kind'))&&(!query.get('active')||String(a.active)===query.get('active'))&&`${a.code} ${a.name}`.toLowerCase().includes((query.get('search')||'').toLowerCase()));
      return send({bankId,version:1,items:filtered.slice(offset,offset+limit),total:filtered.length,accountCount:chartRows.length,groupCount:3,activeCount:chartRows.length,limit,offset});
    }
    if(url.pathname==='/api/v1/admin/reference-data/catalogue')return send(catalogue);
    if(url.pathname==='/api/v1/access/preferences'){
      if(req.method==='PUT'){let body='';for await(const chunk of req)body+=chunk;preference=JSON.parse(body).language;}
      return send({language:preference});
    }
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/settings`){
      if(req.method==='PUT'){
        let body='';for await(const chunk of req)body+=chunk;const input=JSON.parse(body);
        const {bankId:ignoredId,version,...beforeValues}=settings;
        const {expectedVersion,reason,...proposedValues}=input;
        const request={id:crypto.randomUUID(),action:'BANK_SETTINGS',targetId:bankId,bankId,targetLabel:'Demo Bank Kenya',makerSubject:'checker.demo',beforeValues,proposedValues,expectedVersion,reason,status:'PENDING',reviewerSubject:null,decisionReason:null,createdAt:new Date().toISOString(),decidedAt:null};
        requests.push(request);events.set(request.id,[{id:request.id,actorSubject:request.makerSubject,action:'SUBMITTED',reason,occurredAt:request.createdAt}]);res.statusCode=202;return send(request);
      }
      return send(settings);
    }
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/rounding-preview`)return send({currency:'KES',decimalPlaces:2,roundingMode:'HALF_EVEN',roundedAmount:'123.46'});
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/calendar-day`)return send({date:url.searchParams.get('date'),workingDay:false,holiday:'Christmas Day',nextWorkingDay:'2026-12-28'});
    if(url.pathname==='/api/v1/admin/approvals'){
      const scope=url.searchParams.get('scope');
      const filtered=requests.filter(r=>(scope==='MINE'?r.makerSubject==='checker.demo':scope==='HISTORY'?r.status!=='PENDING':r.status==='PENDING')&&(!url.searchParams.get('action')||r.action===url.searchParams.get('action'))&&(!url.searchParams.get('bankId')||r.bankId===url.searchParams.get('bankId')));
      return send({items:filtered,limit:25,offset:0});
    }
    const match=url.pathname.match(/^\/api\/v1\/admin\/approvals\/([0-9a-f-]+)(?:\/(approve|reject|cancel))?$/);
    if(match){
      const request=requests.find(r=>r.id===match[1]);
      if(!request){res.statusCode=404;return send({code:'APPROVAL_NOT_FOUND'});}
      if(req.method==='POST'&&match[2]){
        let body='';for await(const chunk of req)body+=chunk;
        request.status={approve:'APPROVED',reject:'REJECTED',cancel:'CANCELLED'}[match[2]];
        request.reviewerSubject='checker.demo';request.decisionReason=JSON.parse(body).reason;request.decidedAt=new Date().toISOString();
        events.get(request.id).push({id:crypto.randomUUID(),actorSubject:'checker.demo',action:request.status,reason:request.decisionReason,occurredAt:request.decidedAt});
        return send(request);
      }
      return send({request,events:events.get(request.id)});
    }
    res.statusCode=404;send({code:'FIXTURE_ROUTE_NOT_FOUND'});
  }catch {res.statusCode=500;res.end('Fixture failure');}
}).listen(18082,'127.0.0.1',()=>console.log('Isolated approval UI fixtures: http://127.0.0.1:18082/admin/index.html'));
