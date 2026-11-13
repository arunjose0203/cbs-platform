import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

/** Management dashboard, financial statements and approved report definitions. */
export function createReportingUi({api,form,field,refresh,download}){
 let bankId='',banks=[],bank,board,definitions=[],current=()=>false;
 const button=(label,action,id='')=>'<button type="button" class="button secondary" data-action="reporting-'+action+'" data-id="'+e(id)+'">'+e(t(label))+'</button>';
 const table=(heads,rows)=>'<div class="table-wrap"><table><thead><tr>'+heads.map(h=>'<th>'+e(t(h))+'</th>').join('')+'</tr></thead><tbody>'+(rows.length?rows.join(''):'<tr><td colspan="'+heads.length+'" class="empty">'+e(t('Nothing to show.'))+'</td></tr>')+'</tbody></table></div>';
 const tr=cells=>'<tr>'+cells.map(c=>'<td>'+e(c)+'</td>').join('')+'</tr>';
 async function render(host,isCurrent){
  current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!current())return;banks=directory.items;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>'+e(t('Management reports'))+'</h1><p>'+e(t('Register a bank first.'))+'</p>';return;}
  [bank,board,definitions]=await Promise.all([api('/admin/tenants/'+bankId),api('/admin/tenants/'+bankId+'/reporting/dashboard'),api('/admin/tenants/'+bankId+'/reporting/definitions')]);if(!current())return;
  const d=board.deposits,l=board.loans,w=board.work;
  host.innerHTML='<div class="heading"><div><h1>'+e(t('Management reports'))+'</h1><p>'+e(bank.displayName)+' · '+e(board.businessDate)+'</p></div><div class="actions">'+button('Choose bank','choose')+button('Financial statements','statements')+button('Operational reports','operational')+button('Refresh','refresh')+'</div></div>'+
   '<section class="card"><h2>'+e(t('Deposits'))+'</h2>'+table(['Currency','Accounts','Balance'],d.totals.map(x=>tr([x.currency,x.accounts,x.balance])))+
   '<h3>'+e(t('By product'))+'</h3>'+table(['Product','Currency','Accounts','Balance'],d.byProduct.map(x=>tr([x.product,x.currency,x.accounts,x.balance])))+
   '<h3>'+e(t('By branch'))+'</h3>'+table(['Branch','Currency','Accounts','Balance'],d.byBranch.map(x=>tr([x.branch,x.currency,x.accounts,x.balance])))+
   '<h3>'+e(t('Largest depositors'))+'</h3>'+table(['Customer','Name','Currency','Balance','Share of deposits %'],d.topDepositors.map(x=>tr([x.customerNumber,x.name,x.currency,x.balance,x.shareOfDeposits])))+'</section>'+
   '<section class="card section-gap"><h2>'+e(t('Loan quality'))+'</h2>'+table(['Currency','Loans','Outstanding','Current','1–30','31–60','61–90','91–180','181+','Non-performing %'],l.portfolio.map(x=>tr([x.currency,x.loans,x.outstanding,x.buckets.CURRENT,x.buckets['1_30'],x.buckets['31_60'],x.buckets['61_90'],x.buckets['91_180'],x.buckets['181_PLUS'],x.nonPerformingRatio])))+
   '<p>'+e(t('Days past due use posted repayments and accruals. Non-performing means more than 90 days past due.'))+(l.unreconciled?' <strong>'+e(l.unreconciled)+' '+e(t('loans could not be reconciled and are excluded.'))+'</strong>':'')+(l.truncated?' '+e(t('Only the first 5,000 active loans are included.')):'')+'</p></section>'+
   '<section class="card section-gap"><h2>'+e(t('Cash holdings'))+'</h2>'+table(['Branch','Location','Type','Currency','Balance'],board.cash.map(x=>tr([x.branch,x.code,x.kind,x.currency,x.balance])))+'</section>'+
   '<section class="card section-gap"><h2>'+e(t('Work waiting'))+'</h2>'+table(['Item','Count'],[...w.pendingApprovals.map(x=>tr([t('Pending approval')+': '+x.action,x.count])),...Object.entries(w.failedPostings).filter(([,n])=>n>0).map(([k,n])=>tr([t('Failed postings')+': '+k,n])),...w.alerts.map(x=>tr([t('Alerts')+': '+x.status,x.count])),tr([t('Open compliance cases'),w.openComplianceCases]),tr([t('Open cash exceptions'),w.openCashExceptions]),tr([t('Open payment cases'),w.openPaymentCases])])+'</section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Report definitions'))+'</h2>'+button('New or change definition','define')+'</div>'+table(['Code','Name','Basis','Lines',''],definitions.map(x=>'<tr><td>'+e(x.code)+'</td><td>'+e(x.name)+'</td><td>'+e(x.basis)+'</td><td>'+e(x.lines)+'</td><td>'+button('Run','run',x.code)+'</td></tr>'))+
   '<p>'+e(t('Each line is the signed sum of chart accounts; group accounts include everything beneath them. Configure official returns from their published templates.'))+'</p></section>';
 }
 const entityChoices=()=>bank.legalEntities.map(x=>({value:x.id,label:x.legalName}));
 const fetchFile=async(path,name)=>{const blob=await api(path,{responseType:'blob'});download(blob,name);};
 async function action(name,id){
  if(!current())return;const base='/admin/tenants/'+bankId+'/reporting';
  if(name==='refresh'){await refresh();return;}
  if(name==='choose'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;},'Open');return;}
  if(name==='statements'){
   const date=board.businessDate;
   form('Financial statements',field('kind','Statement','balance-sheet',{choices:[{value:'balance-sheet',label:t('Balance sheet')},{value:'income-statement',label:t('Income statement')}]})+field('legalEntityId','Legal entity',bank.legalEntities[0].id,{choices:entityChoices()})+field('currency','Currency',bank.legalEntities[0].functionalCurrency||'',{max:3,pattern:'[A-Z]{3}'})+field('from','From (income statement)',date.slice(0,4)+'-01-01',{type:'date',required:false})+field('to','As at / to',date,{type:'date'})+field('format','Format','pdf',{choices:[{value:'pdf',label:'PDF'},{value:'csv',label:'CSV'}]}),
    async v=>{const q=new URLSearchParams({legalEntityId:v.legalEntityId,currency:v.currency,format:v.format,...(v.kind==='balance-sheet'?{asOf:v.to}:{from:v.from,to:v.to})});await fetchFile(base+'/'+v.kind+'?'+q,v.kind+'-'+v.to+'.'+v.format);},'Download');return;
  }
  if(name==='operational'){
   const date=board.businessDate;
   form('Operational and regulatory reports',
    field('report','Report','deposit-register',{choices:[
     {value:'deposit-register',label:t('Deposit register')},
     {value:'loan-register',label:t('Loan register')},
     {value:'cash-report',label:t('Cash custody report')},
     {value:'tax-remittance',label:t('Tax remittance (KRA WHT & Excise)')},
     {value:'exception-ageing',label:t('Exception ageing')},
     {value:'loan-provisions',label:t('Loan provisions (IFRS 9 / CBK)')}
    ]})+
    field('currency','Currency','',{max:3,pattern:'[A-Z]{3}',required:false})+
    field('from','From (tax remittance)',date.slice(0,4)+'-01-01',{type:'date',required:false})+
    field('to','To (tax remittance)',date,{type:'date',required:false})+
    field('format','Format','pdf',{choices:[{value:'pdf',label:'PDF'},{value:'csv',label:'CSV'},{value:'json',label:'JSON'}]}),
    async v=>{
     const params={format:v.format};
     if(v.currency)params.currency=v.currency;
     if(v.report==='tax-remittance'){if(v.from)params.from=v.from;if(v.to)params.to=v.to;}
     const q=new URLSearchParams(params);
     if(v.format==='json'){
      const data=await api(base+'/'+v.report+'?'+q);
      download(data,v.report+'-'+date+'.json');
     }else{
      await fetchFile(base+'/'+v.report+'?'+q,v.report+'-'+date+'.'+v.format);
     }
    },'Download');return;
  }
  if(name==='run'){
   form('Run '+id,field('legalEntityId','Legal entity',bank.legalEntities[0].id,{choices:entityChoices()})+field('currency','Currency',bank.legalEntities[0].functionalCurrency||'',{max:3,pattern:'[A-Z]{3}'})+field('from','From (movement reports)','',{type:'date',required:false})+field('to','As at / to',board.businessDate,{type:'date'})+field('format','Format','csv',{choices:[{value:'csv',label:'CSV'},{value:'pdf',label:'PDF'}]}),
    async v=>{const q=new URLSearchParams({legalEntityId:v.legalEntityId,currency:v.currency,to:v.to,format:v.format,...(v.from?{from:v.from}:{})});await fetchFile(base+'/definitions/'+id+'/run?'+q,'report-'+id+'-'+v.to+'.'+v.format);},'Download');return;
  }
  if(name==='define'){
   const chart=await api('/admin/tenants/'+bankId+'/chart-of-accounts?limit=100');if(!current())return;const byCode=new Map(chart.items.map(c=>[c.code,c.id]));const key=crypto.randomUUID();
   form('Report definition',field('code','Code','',{max:32,pattern:'[A-Z][A-Z0-9_]{1,31}'})+field('name','Name','',{full:true,max:200})+field('basis','Basis','BALANCE',{choices:[{value:'BALANCE',label:t('Balances as at a date')},{value:'MOVEMENT',label:t('Movements over a period')}]})+
    field('lines','Lines','',{full:true,max:20000,help:t('One line per row: code | label | chart account codes separated by commas | + or −. Example: 10 | Customer deposits | 2100,2200 | +')})+field('reason','Reason and evidence','',{full:true,max:500}),
    async v=>{const lines=v.lines.split(/\r?\n/).map(x=>x.trim()).filter(Boolean).map(row=>{const [code,label,accounts,sign]=row.split('|').map(p=>(p||'').trim());const ids=accounts.split(',').map(c=>c.trim()).filter(Boolean).map(c=>{const found=byCode.get(c);if(!found)throw Error(t('Unknown chart account')+': '+c);return found;});return {code,label,chartAccounts:ids,sign:sign==='-'||sign==='−'?-1:1};});
     return api(base+'/definitions',{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:bank.version,code:v.code,name:v.name,basis:v.basis,lines,reason:v.reason}});},'Submit for approval');
  }
 }
 return {render,action,selectBank:x=>{bankId=x;}};
}
