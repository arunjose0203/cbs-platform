// Isolated UI fixture server. Never connects to CBS, an identity provider, or a database.
// Run: node ui/test/approval-preview.mjs; open http://127.0.0.1:18082.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
const root=fileURLToPath(new URL('../../',import.meta.url));
const source=await readFile(new URL('../src/app.js',import.meta.url),'utf8');
const fixtureView=process.argv.includes('--lending-access')?'lendingaccess':process.argv.includes('--lending-approvals')?'lendingapprovals':process.argv.includes('--collections')?'collections':process.argv.includes('--credit')?'credit':process.argv.includes('--fx')?'fx':process.argv.includes('--term')?'term':process.argv.includes('--loan')?'loan':process.argv.includes('--payment-cases')?'paymentcases':process.argv.includes('--statements')?'statements':process.argv.includes('--payments')?'payments':process.argv.includes('--compliance')?'compliance':process.argv.includes('--servicing')?'servicing':process.argv.includes('--branch')?'branchwork':process.argv.includes('--funding')?'accounts':process.argv.includes('--accounts')?'accounts':process.argv.includes('--customers')?'customers':process.argv.includes('--products')?'products':process.argv.includes('--dayclose')?'dayclose':process.argv.includes('--reports')?'reports':process.argv.includes('--journals')?'journals':process.argv.includes('--ledgers')?'ledgers':process.argv.includes('--chart')?'chart':process.argv.includes('--settings')?'settings':'approvals';
const result=await build({stdin:{contents:source.replace(/boot\(\);\s*$/,`configuration={configured:true};user={access_token:'fixture-only'};me={platformAdmin:true,staff:{subject:'checker.demo'}};view='${fixtureView}';document.querySelector('#connection').textContent='UI test fixtures';render();`),resolveDir:root+'ui/src',sourcefile:'approval-preview.js'},bundle:true,write:false,format:'esm'});
const bankId='10000000-0000-0000-0000-000000000001';
const settings={bankId,version:1,currencies:[{code:'KES',roundingMode:'HALF_EVEN',enabled:true}],languages:['en','sw'],defaultLanguage:'en',businessTimezone:'Africa/Nairobi',businessDate:'2026-09-23',weekendDays:[6,7],holidays:[{date:'2026-12-25',name:'Christmas Day'}]};
const catalogue={currencies:[{code:'KES',name:'Kenyan Shilling',decimalPlaces:2},{code:'USD',name:'US Dollar',decimalPlaces:2},{code:'JPY',name:'Japanese Yen',decimalPlaces:0}],languages:[{code:'en',name:'English'},{code:'fr',name:'French'},{code:'sw',name:'Swahili'}],timezones:['Africa/Nairobi','Africa/Lagos','Africa/Johannesburg'],roundingModes:['HALF_EVEN','HALF_UP','DOWN']};
let preference='en';
const adjustmentPreview=process.argv.includes('--adjustment');
const inventoryPreview=process.argv.includes('--inventory');
const transitPreview=process.argv.includes('--transit');
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
const collectionFixture={version:12,definition:{id:'95000000-0000-0000-0000-000000000001',terms:{agreementReference:'FICTIONAL/LOAN-001'},account:{accountNumber:'A000001',terms:{currency:'KES'}}},contractStatus:'ACTIVE',report:{asOf:'2026-10-08',outstanding:{principal:'90000',interest:'620.5',fees:'250',total:'90870.5'},overdue:{principal:'10000',interest:'410.5',fees:'250',total:'10660.5'},daysPastDue:15,accrualComplete:true,reconciled:true,buckets:{CURRENT:{principal:'80000',interest:'210',fees:'0',total:'80210'},'1_30':{principal:'10000',interest:'410.5',fees:'250',total:'10660.5'},'31_60':{principal:'0',interest:'0',fees:'0',total:'0'},'61_90':{principal:'0',interest:'0',fees:'0',total:'0'},'91_180':{principal:'0',interest:'0',fees:'0',total:'0'},'181_PLUS':{principal:'0',interest:'0',fees:'0',total:'0'}},instalments:[{dueDate:'2026-09-23',principal:'10000',interest:'410.5',fees:'250',total:'10660.5',daysPastDue:15},{dueDate:'2026-10-23',principal:'10000',interest:'210',fees:'0',total:'10210',daysPastDue:0}]},case:{status:'OPEN',owner:'collector.demo',followUpOn:'2026-10-07',escalation:'TEAM_LEAD',promise:{amount:'5000',dueDate:'2026-10-06'}},promiseStatus:{status:'BROKEN',paid:'1000',paidByDue:'1000',remaining:'4000'},followUpOverdue:true,pendingDelivery:false,fees:[{feeId:'fee-demo',dueDate:'2026-09-23',after:'250',receivableLedger:'fee-asset-demo',incomeLedger:'fee-income-demo',change:{evidenceReference:'FICTIONAL/FEE-001'}}],history:[],historyTotal:0};
createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://127.0.0.1:18082');
    const send=(body,type='application/json')=>{res.setHeader('Content-Type',type);res.end(type==='application/json'?JSON.stringify(body):body);};
    const grant={staffId:'30000000-0000-0000-0000-000000000001',active:true,bankWide:false,branches:['50000000-0000-0000-0000-000000000001'],roles:['CREDIT_APPROVER'],limits:[{operation:'LOAN_DISBURSE',currency:'KES',perTransaction:'100000',dailyTotal:'500000'}],reason:'Fictional branch credit authority'};
    if(url.pathname==='/api/v1/banks/lending-operations/banks')return send({items:[{id:bankId,displayName:'Demo Bank Kenya',status:'ACTIVE'}]});
    if(url.pathname===`/api/v1/banks/${bankId}/lending-operations`)return send({id:bankId,displayName:'Demo Bank Kenya',status:'ACTIVE',version:1,grant});
    if(url.pathname===`/api/v1/banks/${bankId}/lending-operations/approvals`)return send({items:[{...requests[0],action:'CONTRACT_OPEN',proposedValues:{type:'LOAN',amount:'75000',currency:'KES',account:'FICTIONAL/A000001'}}]});
    if(url.pathname.startsWith(`/api/v1/banks/${bankId}/lending-operations/approvals/`)){if(req.method==='POST')return send({status:'APPROVED'});return send({request:{...requests[0],action:'CONTRACT_OPEN',proposedValues:{type:'LOAN',amount:'75000',currency:'KES',account:'FICTIONAL/A000001'}},events:events.get(requests[0].id)});}
    if(url.pathname.includes('/lending-operations/'))url.pathname=url.pathname.replace(`/banks/${bankId}/lending-operations/`,`/admin/tenants/${bankId}/`);
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/lending-access/branches`)return send([{id:grant.branches[0],code:'NAIROBI',name:'Nairobi demo branch',active:true}]);
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/lending-access`){if(req.method==='POST')return send({status:'PENDING'});return send({version:1,items:[{grant,displayName:'Demo credit approver',subject:'checker.demo',staffActive:true}],total:1,historyTotal:1,history:[{grant,maker:'admin.demo',checker:'reviewer.demo',at:'2026-10-08T09:00:00Z'}]});}
    if(url.pathname==='/api/v1/admin/staff')return send({items:[{id:grant.staffId,displayName:'Demo credit approver',subject:'checker.demo'}]});

    if(url.pathname.includes('/loan-collections')){if(req.method==='POST')return send({});return send(url.pathname.endsWith('/loan-collections')?{version:12,businessDate:'2026-10-08',items:[collectionFixture],total:1}:collectionFixture);}
    if(url.pathname.endsWith('/contracts/fees/preview')){let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);return send({kind:body.operation,feeServicing:{before:'0',after:body.amount,dueDate:body.dueDate},customerMovement:'0',after:{closed:false}});}
    if(url.pathname.endsWith('/contracts/fees'))return send({});
    if(url.pathname.endsWith('/fx/valuation'))return send({version:1,businessDate:settings.businessDate,storageReady:true,books:[{id:'book-demo',functionalCurrency:'KES',policy:{legalEntityId:entityId,foreignCurrency:'USD',longLimit:'5000',shortLimit:'1000',policyReference:'DEMO/WEIGHTED-AVERAGE'}}],closingRates:[{id:'closing-demo',rate:{bookId:'book-demo',businessDate:settings.businessDate,rate:'130'}}],valuations:[{id:'valuation-demo',completed:true,posting:{book:{functionalCurrency:'KES',policy:{foreignCurrency:'USD'}},closingRate:{businessDate:settings.businessDate,rate:'130',sourceReference:'DEMO/CLOSING-EVIDENCE'},snapshot:{totals:{quantity:'100',cost:'12900',cash:'-12900',marketValue:'13000',realized:'0',unrealized:'100',totalGainLoss:'100'},previousRealized:'0',previousUnrealized:'0',tradeCount:1},journal:{lines:[{side:'DEBIT',amount:'100',ledgerId:'Demo functional position'},{side:'CREDIT',amount:'100',ledgerId:'Demo unrealized gain'}]}}}]});
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/fx`)return send({version:1,businessDate:settings.businessDate,currencies:[{code:'KES'},{code:'USD'}],total:1,items:[{id:'94000000-0000-0000-0000-000000000001',status:'ACTIVE',rate:{baseCurrency:'USD',quoteCurrency:'KES',buyRate:'129',midRate:'130',sellRate:'131',effectiveFrom:settings.businessDate,effectiveTo:settings.businessDate,expiresAt:'2026-10-09T18:00:00Z',sourceReference:'FICTIONAL/QUOTE'}}]});
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/fx/trades`)return send({version:1,businessDate:settings.businessDate,storageReady:true,total:0,items:[]});
    if(url.pathname.endsWith('/contracts/maturity-alerts'))return send({businessDate:settings.businessDate,leadDays:7,total:1,channel:'IN_APP_OPERATIONS',items:[{contractId:'95000000-0000-0000-0000-000000000001',agreementReference:'FICTIONAL/TERM-001',accountNumber:'A000001',maturityDate:settings.businessDate,daysToMaturity:0,status:'MATURED_ACTION_REQUIRED'}]});
    if(url.pathname.endsWith('/history')&&url.pathname.includes('/contracts/'))return send({version:1,businessDate:settings.businessDate,total:1,items:[{completed:true,reversed:false,reversible:true,maker:'maker.demo',checker:'checker.demo',posting:{id:'98000000-0000-0000-0000-000000000001',kind:'OPEN',businessDate:settings.businessDate,customerMovement:'-100000',reason:'Fictional approved deposit opening',contract:{id:'95000000-0000-0000-0000-000000000001',terms:{agreementReference:'FICTIONAL/TERM-001'},account:{accountNumber:'A000001',terms:{currency:'KES'}}}}}]});
    if(url.pathname===`/api/v1/admin/tenants/${bankId}/contracts`){const loan=url.searchParams.get('type')==='LOAN';return send({version:1,businessDate:settings.businessDate,storageReady:true,total:1,items:[{definition:{id:'95000000-0000-0000-0000-000000000001',bankId,account:{id:'96000000-0000-0000-0000-000000000001',accountNumber:'A000001',customerNumber:'C000001',terms:{currency:'KES'}},terms:{agreementReference:loan?'FICTIONAL/LOAN-001':'FICTIONAL/TERM-001',principal:'100000',annualRatePercent:'5',dayBasis:365,withholdingPercent:'0',startDate:'2026-09-23',maturityDate:loan?'2027-09-23':settings.businessDate,principalLedger:'97000000-0000-0000-0000-000000000001',accruedInterestLedger:'97000000-0000-0000-0000-000000000002'}},position:{principal:'100000',interest:'13.70',carry:'-0.001369863013698630136986',accruedThrough:settings.businessDate},status:'ACTIVE',pending:[],overdue:false,principalArrears:'0',schedule:loan?[{dueDate:'2026-10-23',principal:'8333.33',projectedInterest:'410.96',total:'8744.29'},{dueDate:'2026-11-23',principal:'8333.33',projectedInterest:'389.27',total:'8722.60'}]:[]}]});}
    if(url.pathname==='/api/v1/banks/payment-operations/banks')return send({items:[{id:bankId,displayName:'Demo Bank Kenya',status:'ACTIVE'}]});
    if(url.pathname.endsWith('/payment-operations'))return send({id:bankId,displayName:'Demo Bank Kenya',role:'SUPERVISOR',canMake:true,canApprove:true});
    if(url.pathname.includes('/payment-operations/')){
     if(url.pathname.endsWith('/ledgers'))return send({items:ledgerRows});
     const c={id:'case-demo',payment_id:'payment-demo',kind:'PROVIDER_REVERSAL',state:'OPEN',version:0,due_date:'2026-10-06',owner_name:'Reconciliation team',reference:'DEMO-PAYOUT-014',amount:'1500',statement_id:'statement-demo',line_id:'return-demo'};
     if(url.pathname.endsWith('/cases'))return send({version:12,businessDate:'2026-10-07',overdue:1,total:1,items:[{...c,overdue:true}],uninvestigated:[]});
     if(url.pathname.endsWith('/cases/case-demo'))return send({version:12,businessDate:'2026-10-07',case:c,events:[{action:'OPENED',subject:'reconciler.demo',at:'2026-10-06T10:00:00Z',payload:{evidenceReference:'DEMO/PROVIDER-RETURN',reason:'Fictional provider statement credit awaiting independent customer return approval'}}]});
     if(url.pathname.endsWith('/approvals'))return send({items:[]});
     url.pathname=url.pathname.replace('/banks/'+bankId+'/payment-operations/','/admin/tenants/'+bankId+'/');
    }

    if(url.pathname==='/admin/app.js')return send(result.outputFiles[0].text,'text/javascript');
    if(url.pathname==='/admin/styles.css')return send(await readFile(root+'src/main/resources/static/admin/styles.css','utf8'),'text/css');
    if(url.pathname==='/admin/chart.css')return send(await readFile(root+'src/main/resources/static/admin/chart.css','utf8'),'text/css');
    if(url.pathname==='/'||url.pathname==='/admin/index.html')return send(await readFile(root+'src/main/resources/static/admin/index.html','utf8'),'text/html');
    if(url.pathname.endsWith('/cash/cash-receipt-demo/receipt'))return send({bank:'Demo Bank Kenya · FICTIONAL',bankCode:'DEMO_KE',number:17,requestId:'cash-receipt-demo',businessDate:settings.businessDate,postedAt:'2026-09-23T10:00:00Z',operation:'DEPOSIT',location:'TILL_01',amount:'1000',currency:'KES',reference:'DEMO-RECEIPT-001',customerAccount:'DEMO001',copiesGenerated:1,counts:[{denomination:'1000',quantity:1}]});
    if(url.pathname==='/api/v1/admin/tenants/'+bankId)return send({id:bankId,version:1,displayName:'Demo Bank Kenya',status:'ACTIVE',legalEntities:[{id:entityId,legalName:'Demo Bank Kenya Limited',functionalCurrency:'KES'}]});
    if(url.pathname.includes('/credit')){
      const application={accountId:'account-demo',reference:'DEMO/CREDIT-001',purpose:'Fictional equipment purchase',principal:'100000',annualRatePercent:'12',dayBasis:365,instalments:12,monthlyIncome:'50000',monthlyExpenses:'15000',monthlyDebtPayments:'2000',existingDebt:'10000',documents:[{reference:'DOC/DEMO-001',sha256:'a'.repeat(64),expiresOn:'2027-09-23'}],securities:[{type:'COLLATERAL',reference:'ASSET/DEMO-001',owner:'Fictional borrower',value:'150000',valuedOn:'2026-09-23',evidence:{reference:'TITLE/DEMO',sha256:'b'.repeat(64),expiresOn:'2027-09-23'}}],reason:'Fictional verified evidence'};
      const item={id:'credit-demo',status:'APPROVED',currency:'KES',application,reference:application.reference,createdBy:'maker.demo'};
      if(url.pathname.endsWith('/credit'))return send({version:1,businessDate:settings.businessDate,total:1,items:[item],policies:[{currency:'KES',maximumPrincipal:'500000',maximumDebtServicePercent:'40',minimumMonthlySurplus:'10000',minimumCollateralPercent:'100',maximumRiskGrade:5,approverSubject:'checker.demo'}]});
      return send({...item,version:1,businessDate:settings.businessDate,review:{conditions:['Verify signed lending agreement']},assessment:{peakMonthlyPayment:'9352.51',debtServicePercent:'22.70502',monthlySurplus:'23647.49',collateralPercent:'150',guaranteeValue:'0'},events:[{kind:'APPLICATION_CREATED',subject:'maker.demo',at:'2026-09-23T09:00:00Z',evidence:{reason:'Fictional equipment application'}}]});
    }
    if(url.pathname.includes('/statement-imports')){
      const source={statementReference:'MPESA-2026-10-07',walletReference:'Demo wallet 600000',ledgerId:ledgerRows[0].definition.id,fromDate:'2026-10-07',toDate:'2026-10-07',openingBalance:'10000',closingBalance:'7465',evidenceReference:'DEMO/MPESA/OCT07',fileHash:'a'.repeat(64)};
      if(url.pathname.endsWith('/statement-imports'))return send({version:12,total:1,items:[{id:'statement-demo',source,status:'OPEN'}]});
      if(url.pathname.endsWith('/statement-demo'))return send({id:'statement-demo',version:12,source,status:'OPEN',reportHash:'b'.repeat(64),balances:{providerOpening:'10000',providerClosing:'7465',cbsOpening:'10000',cbsClosing:'7500',openingDifference:'0',closingDifference:'35',fees:'35',pendingAccounting:'0'},rows:[{id:'payout-row',source:{receipt:'DEMOMPESA001',date:'2026-10-07',type:'PAYOUT',credit:'0',debit:'2500',balance:'7500',paymentReference:'DEMO-PAY-001',beneficiary:'254700000001'},status:'RESOLVED',decisionId:'decision-1',decisionKind:'MATCH',paymentId:'payment-demo-1'},{id:'fee-row',source:{receipt:'DEMOMPESA001',date:'2026-10-07',type:'FEE',credit:'0',debit:'35',balance:'7465',paymentReference:'',beneficiary:''},status:'NEEDS_LEDGER'}],total:2,blockers:['UNRESOLVED_ROWS','CLOSING_DIFFERENCE'],unmatchedCbs:[],missingPayments:[],conflicts:[],history:[{kind:'MATCH',subject:'checker.demo',at:'2026-10-07T11:00:00Z'}]});
    }
    if(url.pathname.endsWith('/payments'))return send({version:12,businessDate:'2026-10-07',configured:true,total:3,items:[{id:'payment-demo-1',reference:'DEMO-MPESA-001',amount:'2500',beneficiary:'••••••000001',state:'SUBMITTED',posted:true},{id:'payment-demo-2',reference:'DEMO-MPESA-002',amount:'750',beneficiary:'••••••000002',state:'UNKNOWN',posted:true},{id:'payment-demo-3',reference:'DEMO-MPESA-003',amount:'1200',beneficiary:'••••••000003',state:'SETTLED',posted:true}]});
    if(url.pathname.endsWith('/payments/payment-demo-1'))return send({id:'payment-demo-1',version:12,state:'SUBMITTED',originator_id:'fictional-originator',payload:{reference:'DEMO-MPESA-001',amount:'2500',beneficiary:'254700000001',beneficiaryName:'Fictional sandbox beneficiary'},events:[{action:'PROVIDER_ACCEPTED',subject:'operator.demo',at:'2026-10-07T10:00:00Z'}],observations:[{code:'0',receipt:'DEMORECEIPT1',amount:'2500'}],statements:[{id:'statement-demo-1',subject:'maker.demo',payload:{providerReference:'DEMORECEIPT1',amount:'2500',currency:'KES',outcome:'PAID',evidenceReference:'DEMO/STATEMENT/001'}}]});
    if(url.pathname.endsWith('/compliance'))return send({version:9,today:'2026-10-07',total:2,items:[{id:'case-demo-1',customer_number:'DEMO001',category:'KYC_REVIEW',status:'OPEN',version:0,risk:null,review_due:null,restricted:true},{id:'case-demo-2',customer_number:'DEMO002',category:'SCREENING_ALERT',status:'CLEARED',version:2,risk:'MEDIUM',review_due:'2026-11-07',restricted:false}]});
    if(url.pathname.endsWith('/compliance/case-demo-1'))return send({version:9,case:{id:'case-demo-1',customer_number:'DEMO001',category:'KYC_REVIEW',status:'OPEN',version:0,customer_version:3,restricted:true},events:[{action:'OPENED',subject:'maker.demo',at:'2026-10-07T09:00:00Z',evidence:{reason:'Fictional periodic verification review; no real customer data.',evidenceReference:'DEMO/KYC/001'}}]});
    if(url.pathname.endsWith('/servicing/preview'))return send({nextBusinessDate:'2026-09-24',items:[{account:{accountNumber:'DEMO001',terms:{currency:'KES'}},openingBalance:'1000',calculation:{grossInterest:'1',interestTax:'0.1',fee:'0.5',feeTax:'0.1',netChange:'0.3',carryAfter:'0'}}]});
    if(url.pathname.endsWith('/servicing'))return send({version:1,businessDate:settings.businessDate,storageReady:true,policies:[{id:'Fictional policy',product_id:'SAVE_KES',product_revision:1,effective_from:settings.businessDate,currency:'KES',annual_rate:'36.5',daily_fee:'0.5'}],runs:[]});
    if(url.pathname==='/api/v1/banks/operations/branches')return send([{bank_id:bankId,branch_id:'b0000000-0000-0000-0000-000000000001',bank_name:'Demo Bank Kenya',branch_name:'Nairobi Main',role:adjustmentPreview||transitPreview||inventoryPreview?'BRANCH_MANAGER':'TELLER'}]);
    if(url.pathname.endsWith('/operations/cash/transit-destinations'))return send([{id:'c0000000-0000-0000-0000-000000000003',code:'MOMBASA_VAULT',branch_name:'Mombasa',currency:'KES',legal_entity_id:entityId}]);
    if(url.pathname.endsWith('/operations/cash/transit'))return send(transitPreview?[{dispatch_id:'d0000000-0000-0000-0000-000000000001',source_branch:'b0000000-0000-0000-0000-000000000002',destination_branch:'b0000000-0000-0000-0000-000000000001',source_id:'c0000000-0000-0000-0000-000000000003',destination_id:'c0000000-0000-0000-0000-000000000001',ledger_id:'e0000000-0000-0000-0000-000000000001',source_code:'MOMBASA_VAULT',destination_code:'MAIN_VAULT',reference:'DEMO-SHIP-001',currency:'KES',amount:'50000',received:'20000',returned:'0',outstanding:'30000',receivable:'30000',expected_date:settings.businessDate,dispatched:true,overdue:false}]:[]);
    if(url.pathname.endsWith('/operations/cash/reversible'))return send([{request_id:'f0000000-0000-0000-0000-000000000001',reference:'DEMO-CASH-DEPOSIT-001',kind:'DEPOSIT',amount:'250.50',currency:'KES',location_id:'c0000000-0000-0000-0000-000000000002',destination_id:null}]);
    if(url.pathname.endsWith('/operations/reversible'))return send([]);
    if(url.pathname.endsWith('/operations/cash/adjustment-ledgers'))return send([{id:'e0000000-0000-0000-0000-000000000001',code:'CASH_LOSS_KES',name:'Cash shortage expense',category:'EXPENSE'}]);
    if(url.pathname.endsWith('/operations/cash/exceptions')){return send(adjustmentPreview?[{id:'c0000000-0000-0000-0000-000000000001',location_id:'c0000000-0000-0000-0000-000000000002',code:'TILL_01',currency:'KES',book_amount:'10000',counted_amount:'9500',difference:'-500',reporter:'teller.demo',resolved_at:null,pending_adjustment:null}]:[]);}
    if(url.pathname.startsWith('/api/v1/banks/')&&url.pathname.endsWith('/operations/cash')){
      if(req.method==='POST'){let body='';for await(const chunk of req)body+=chunk;res.statusCode=202;return send({id:crypto.randomUUID(),status:'PENDING',proposedValues:JSON.parse(body)});}
      return send({version:1,businessDate:settings.businessDate,storageReady:true,items:[
       {location:{id:'c0000000-0000-0000-0000-000000000001',code:'MAIN_VAULT',name:'Nairobi main vault',type:'VAULT',currency:'KES',legalEntityId:entityId},inventoryEnabled:inventoryPreview,inventory:[{denomination:'1000',quantity:90}],balance:'90000',ledgerBalance:'90000',reconciled:true,sessionOpen:false},
       {location:{id:'c0000000-0000-0000-0000-000000000002',code:'TILL_01',name:'Counter one',type:'TILL',currency:'KES'},balance:'10000',ledgerBalance:'10000',reconciled:true,sessionOpen:true,custodian:'checker.demo'}
      ],accounts:[{id:'94000000-0000-0000-0000-000000000001',account_number:'DEMO001',currency:'KES',state:'ACTIVE',liability_ledger_id:ledgerRows[1].definition.id}],requests:[],deliveries:inventoryPreview?[{request_id:"cash-receipt-demo",completed:true,printable:true}]:[],limit:25,offset:0});
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
}).listen(Number(process.env.CBS_UI_PREVIEW_PORT||18082),'127.0.0.1',()=>console.log('Isolated approval UI fixtures: http://127.0.0.1:18082/admin/index.html'));
