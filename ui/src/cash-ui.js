import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';
import {requestSigners} from './signers.js';

export function parseCounts(text){
 if(!text.trim())return [];
 const entries=text.split(';');if(entries.length>50)throw new Error('Use at most 50 denomination counts.');
 const seen=new Set();
 return entries.map(entry=>{
  const parts=entry.trim().split(':');
  if(parts.length!==2||!/^\d{1,18}(\.\d{1,6})?$/.test(parts[0].trim())||!/^\d{1,10}$/.test(parts[1].trim()))throw new Error('Enter counts as denomination:quantity, separated by semicolons.');
  const denomination=parts[0].trim(),quantity=Number(parts[1].trim());
  const canonical=denomination.replace(/^0+(?=\d)/,'').replace(/(\.\d*?)0+$/,'$1').replace(/\.$/,'');
  if(!/[1-9]/.test(canonical)||quantity>1000000000||seen.has(canonical))throw new Error('Use unique positive denominations and whole quantities up to 1,000,000,000.');
  seen.add(canonical);return {denomination,quantity};
 });
}

export function createCashUi({api,form,field,refresh,subject=()=>null}){
 let branch,base,data,exceptions=[],transfers=[],offset=0;
 const button=(label,action,id='')=>'<button type="button" class="button secondary" data-action="branch-work-cash-'+action+'" data-id="'+e(id)+'">'+e(t(label))+'</button>';
 async function render(selected,content,isCurrent){
  if(branch?.branch_id!==selected.branch_id)offset=0;branch=selected;base='/banks/'+branch.bank_id+'/branches/'+branch.branch_id+'/operations/cash';
  const [result,cases,movements]=await Promise.all([api(base+'?limit=25&offset='+offset),api(base+'/exceptions'),api(base+'/transit')]);if(!isCurrent())return;data=result;exceptions=cases;transfers=movements;
  const rows=data.items.map(p=>{
   const l=p.location,readOnly=branch.role==='AUDITOR';let actions='';
   const frozen=exceptions.some(x=>x.location_id===l.id&&!x.resolved_at);
   if(!readOnly&&data.storageReady&&!frozen){
    if(!p.inventoryEnabled&&!p.sessionOpen&&branch.role==='BRANCH_MANAGER')actions+=button('Activate inventory','inventory',l.id);
    actions+=button('Report cash difference','exception',l.id);
    if(!p.sessionOpen&&branch.role==='BRANCH_MANAGER')actions+=button('Record daily count','count',l.id);
    if(l.type==='TILL'&&p.sessionOpen&&p.custodian===subject())actions+=button('Customer deposit','deposit',l.id)+button('Customer withdrawal','withdrawal',l.id);
    if(l.type==='TILL')actions+=button(p.sessionOpen?'Close till':'Open till',p.sessionOpen?'close':'open',l.id);
    actions+=button('Transfer cash','transfer',l.id);
    if(l.type==='VAULT'&&branch.role==='BRANCH_MANAGER')actions+=button('Receive external cash','receive',l.id)+button('Dispatch external cash','dispatch',l.id)+button('Send to another branch','transit-dispatch',l.id);
   }
   return '<tr><td>'+e(l.code)+'<br><small>'+e(l.name)+'</small></td><td>'+e(l.type)+' · '+e(l.currency)+'</td><td>'+e(p.balance)+'<br><small>'+e(p.inventoryEnabled?(p.inventory||[]).map(n=>n.denomination+' × '+n.quantity).join('; '):'Inventory not activated')+'</small></td><td>'+e(frozen?'Frozen · open exception':p.reconciled?t('Matched'):t('Investigate'))+'<br><small>'+e(p.countedDate===data.businessDate?'Count approved today':'Daily count due')+'</small>'+'</td><td>'+e(l.type==='VAULT'?'—':p.sessionOpen?'Open · '+p.custodian:'Closed')+'</td><td><div class="actions">'+actions+'</div></td></tr>';
  }).join('');
  content.innerHTML='<div class="heading"><div><h1>'+e(t('Teller & cash custody'))+'</h1><p>'+e(branch.bank_name)+' · '+e(branch.branch_name)+' · '+e(data.businessDate)+'</p></div>'+(branch.role==='AUDITOR'?'':button('Reverse posted cash','reverse'))+button('Back to branch','back')+'</div><p>'+e(t('Count physical cash before submitting. An independent branch manager must review every movement and till opening or closing. Customer cash transactions also update the customer balance and accounting journal together.'))+'</p>'+
   (!data.storageReady?'<section class="card"><p>'+e(t('Ask an administrator to prepare bank storage before using cash custody.'))+'</p></section>':'')+
   '<section class="card"><h2>'+e(t('Vaults and tills'))+'</h2><div class="table-wrap"><table><thead><tr><th>Location</th><th>Type / currency</th><th>Cash balance</th><th>GL reconciliation</th><th>Session</th><th>Actions</th></tr></thead><tbody>'+rows+'</tbody></table></div>'+(!rows?'<p>No cash locations on this page. An administrator can register a vault or till from the bank profile.</p>':'')+button('Previous','previous')+button('Next','next')+'</section>'+
   '<section class="card section-gap"><h2>Inter-branch cash in transit</h2><p>Amounts remain in transit until independently approved physical receipts or returns post. Partial receipts leave the balance outstanding. Investigate discrepancies before recording further movements. Showing the latest 100 transfers, outstanding first.</p><div class="table-wrap"><table><thead><tr><th>Reference / route</th><th>Sent</th><th>Received / returned</th><th>Outstanding</th><th>Expected / status</th><th>Actions</th></tr></thead><tbody>'+transfers.map(x=>'<tr><td>'+e(x.reference)+'<br><small>'+e(x.source_code)+' → '+e(x.destination_code)+'</small></td><td>'+e(x.amount)+' '+e(x.currency)+'</td><td>'+e(x.received)+' / '+e(x.returned)+'</td><td>'+e(x.outstanding)+'</td><td>'+e(x.expected_date)+'<br>'+e(!x.dispatched?'Awaiting dispatch posting':!/[1-9]/.test(x.outstanding)?'Settled':x.overdue?'Overdue · investigate':/[1-9]/.test(x.received)||/[1-9]/.test(x.returned)?'Partially settled':'In transit')+'</td><td>'+((branch.role==='BRANCH_MANAGER'&&x.dispatched&&/[1-9]/.test(x.receivable))?button(x.source_branch===branch.branch_id?'Receive returned cash':'Receive shipment','transit-receive',x.dispatch_id):'')+'</td></tr>').join('')+'</tbody></table></div></section>'+
   '<section class="card section-gap"><h2>Cash exceptions</h2><p>Reporting a difference freezes this location. An independent manager can resolve a counting error after verifying the physical cash matches the book. Managers can request an exact shortage or overage adjustment using an approved expense or income ledger. The location stays frozen until the independently approved journal posts.</p><div class="table-wrap"><table><thead><tr><th>Location</th><th>Book / counted</th><th>Difference</th><th>Status</th><th></th></tr></thead><tbody>'+exceptions.map(x=>'<tr><td>'+e(x.code)+'</td><td>'+e(x.book_amount)+' / '+e(x.counted_amount)+' '+e(x.currency)+'</td><td>'+e(x.difference)+'</td><td>'+e(x.resolved_at?(x.adjustment_request_id?'Adjustment posted':'Recount verified'):x.pending_adjustment?'Adjustment awaiting posting':'Open · frozen')+'</td><td>'+button('View exception','exception-review',x.id)+(!x.resolved_at&&!x.pending_adjustment&&branch.role==='BRANCH_MANAGER'?button('Request adjustment','adjustment',x.id):'')+'</td></tr>').join('')+'</tbody></table></div></section>'+
   '<section class="card section-gap"><h2>Recent cash requests</h2><div class="table-wrap"><table><thead><tr><th>Reason</th><th>Maker</th><th>Status</th><th></th></tr></thead><tbody>'+data.requests.map(r=>'<tr><td>'+e(r.reason)+'</td><td>'+e(r.maker_subject)+'</td><td>'+e(r.status)+'</td><td>'+button('Review','review',r.id)+'</td></tr>').join('')+'</tbody></table></div></section>'+
   '<section class="card section-gap"><h2>Posting status</h2><ul>'+data.deliveries.map(d=>'<li>'+e(d.request_id)+' · '+e(d.completed?'Posted':d.failure_code?'Needs attention: '+d.failure_code:'Awaiting posting')+(d.completed&&d.printable?button('Receipt / reprint','receipt',d.request_id):'')+'</li>').join('')+'</ul><p>Pending deliveries retry automatically. An approved request is not a posted cash movement until its status is Posted.</p></section>';
 }
 async function action(name,id){
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await refresh();return;}
  const savedBase=base,savedBranch=branch;
  if(name==='receipt'){
   const r=await api(savedBase+'/'+id+'/receipt',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()}});
   const details=[['Receipt',r.bankCode+'-'+r.number],['Bank',r.bank],['Transaction',r.requestId],['Business date',r.businessDate],['Posted at',r.postedAt],['Operation',r.operation],['Cash location',r.location],['Amount',r.amount+' '+r.currency],['Reference',r.reference],['Customer account',r.customerAccount||'—'],['Copies generated',r.copiesGenerated]];
   form('Cash receipt','<section class="cash-printable field full"><h2>'+e(r.bank)+'</h2><p>'+e(r.copiesGenerated>1?'REPRINT':'ORIGINAL COPY')+'</p><dl>'+details.map(([k,v])=>'<dt>'+e(k)+'</dt><dd>'+e(v)+'</dd>').join('')+'</dl><p>'+e((r.counts||[]).map(c=>c.denomination+' × '+c.quantity).join('; '))+'</p></section>',async()=>{window.print();},'Print / Save PDF');return;
  }
  if(name==='transit-dispatch'){
   if(savedBranch.role!=='BRANCH_MANAGER')return;
   const p=data.items.find(x=>x.location.id===id);if(!p||p.location.type!=='VAULT')return;
   const version=data.version,date=data.businessDate,key=crypto.randomUUID();
   const choices=(await api(savedBase+'/transit-destinations')).filter(x=>x.currency===p.location.currency&&x.legal_entity_id===p.location.legalEntityId).map(x=>({value:x.id,label:x.branch_name+' · '+x.code}));
   if(!choices.length){form('Destination vault required','<p class="field full">Register an approved vault at another active branch in the same legal entity and currency.</p>',async()=>{},'Done');return;}
   form('Send cash to another branch','<p class="field full">'+e(p.location.code)+' · '+e(p.location.currency)+'. Dispatch debits cash in transit and credits this vault. Destination cash is credited only after receipt approval.</p>'+field('destinationId','Destination vault',choices[0].value,{full:true,choices})+field('ledgerId','Dedicated transit asset ledger ID','',{full:true,help:'Use an approved unused asset ledger on first dispatch. Once bound, only linked transit movements can use it.'})+(p.inventoryEnabled?field('counts','Dispatched denominations','',{full:true,max:2000}):'')+field('amount','Amount','',{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('expectedDate','Expected receipt date',date,{type:'date'})+field('reference','Shipment reference','',{max:80})+field('reason','Dispatch evidence','',{full:true,max:500}),v=>api(savedBase,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,kind:'DISPATCH',branchId:savedBranch.branch_id,locationId:id,ledgerId:v.ledgerId,amount:v.amount,counts:p.inventoryEnabled?parseCounts(v.counts):null,businessDate:date,reference:v.reference,reason:v.reason,transit:{destinationId:v.destinationId,expectedDate:v.expectedDate,returned:false}}}),'Submit for approval');return;
  }
  if(name==='transit-receive'){
   if(savedBranch.role!=='BRANCH_MANAGER')return;const x=transfers.find(x=>x.dispatch_id===id);if(!x||!x.dispatched)return;
   const returned=x.source_branch===savedBranch.branch_id,version=data.version,date=data.businessDate,key=crypto.randomUUID();
   form(returned?'Receive returned shipment':'Receive inter-branch shipment','<p class="field full">'+e(x.reference)+' · Outstanding '+e(x.outstanding)+' '+e(x.currency)+'. Record only physically received cash. A short receipt leaves the difference in transit for investigation; excess receipts are blocked.</p>'+field('amount','Received amount',x.receivable,{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('counts','Received denominations','',{full:true,max:2000,help:'Count this receipt only, e.g. 100:3; 50:2. The count must equal the received amount.'})+field('reference','Receipt reference','',{max:80})+field('reason','Receipt evidence / discrepancy findings','',{full:true,max:500}),v=>api(savedBase,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,kind:'RECEIVE',branchId:savedBranch.branch_id,locationId:returned?x.source_id:x.destination_id,ledgerId:x.ledger_id,amount:v.amount,counts:parseCounts(v.counts),businessDate:date,reference:v.reference,reason:v.reason,transit:{dispatchId:id,returned}}}),'Submit for approval');return;
  }
  if(name==='reverse'){
   if(savedBranch.role==='AUDITOR')return;const version=data.version,date=data.businessDate,key=crypto.randomUUID();
   const rows=(await api(savedBase+'/reversible')).filter(x=>savedBranch.role==='BRANCH_MANAGER'||!['RECEIVE','DISPATCH'].includes(x.kind));
   if(!rows.length){form('Cash reversals','<p class="field full">No eligible posted cash transactions among the latest 100. Reversed transactions and shortage/overage adjustments cannot be reversed here.</p>',async()=>{},'Done');return;}
   form('Reverse posted cash',field('originalId','Original transaction',rows[0].request_id,{full:true,choices:rows.map(x=>({value:x.request_id,label:x.reference+' · '+x.kind+' · '+x.amount+' '+x.currency}))})+'<p class="field full">The full original amount is reversed on the current business date. Cash custody, customer funds and holds are checked again. An independent manager must approve.</p>'+field('reference','Reversal reference','',{max:80})+field('reason','Reason and evidence','',{full:true,max:500}),v=>{const original=rows.find(x=>x.request_id===v.originalId);return api(savedBase,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,kind:'REVERSAL',branchId:savedBranch.branch_id,locationId:original.location_id,destinationId:original.destination_id||null,originalId:original.request_id,amount:original.amount,businessDate:date,reference:v.reference,reason:v.reason}});},'Submit for approval');return;
  }
  if(name==='adjustment'){

   const x=exceptions.find(x=>x.id===id);if(!x||x.resolved_at||x.pending_adjustment||savedBranch.role!=='BRANCH_MANAGER')return;
   const version=data.version,date=data.businessDate,key=crypto.randomUUID(),kind=x.difference.startsWith('-')?'SHORTAGE':'OVERAGE',amount=x.difference.replace(/^-/,'');
   const ledgers=await api(savedBase+'/adjustment-ledgers?locationId='+encodeURIComponent(x.location_id));
   const choices=ledgers.filter(l=>l.category===(kind==='SHORTAGE'?'EXPENSE':'INCOME')).map(l=>({value:l.id,label:l.code+' · '+l.name}));
   if(!choices.length){form('Configure an adjustment ledger','<p class="field full">Ask an administrator to create and approve an '+(kind==='SHORTAGE'?'expense':'income')+' ledger for this location’s legal entity and currency.</p>',async()=>{},'Done');return;}
   form('Request cash '+kind.toLowerCase()+' adjustment','<p class="field full">'+e(x.code)+' · '+e(amount)+' '+e(x.currency)+' · Book '+e(x.book_amount)+' / physical '+e(x.counted_amount)+'. The case remains frozen until posting succeeds.</p>'+field('ledgerId',kind==='SHORTAGE'?'Expense ledger':'Income ledger',choices[0].value,{choices})+field('counts','Confirmed physical denominations','',{required:false,full:true,max:2000,help:'Re-enter the counted cash. Its total must equal the original discrepancy report.'})+field('reference','Reference','',{max:80})+field('reason','Adjustment justification','',{full:true,max:500}),v=>api(savedBase,{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,kind,branchId:savedBranch.branch_id,locationId:x.location_id,exceptionId:x.id,ledgerId:v.ledgerId,amount,businessDate:date,reference:v.reference,counts:parseCounts(v.counts),reason:v.reason}}),'Submit for approval');return;
  }
  if(name==='exception-review'){

   const x=exceptions.find(x=>x.id===id);if(!x)return;
   const evidence='<pre class="field full approval-value">'+e(JSON.stringify(x,null,2))+'</pre>';
   if(x.resolved_at||x.pending_adjustment||branch.role!=='BRANCH_MANAGER'||x.reporter===subject()){form('Cash exception',evidence,async()=>{},'Done');return;}
   const version=data.version,date=data.businessDate;
   form('Verify corrected cash count',evidence+field('counts','Verified denominations','',{required:false,full:true,max:2000,help:'Only resolve a counting error when physical cash matches the recorded balance. This does not post a cash adjustment.'})+field('reason','Investigation findings','',{full:true,max:500}),v=>api(savedBase+'/exceptions/'+id+'/resolve',{method:'POST',body:{expectedVersion:version,locationId:x.location_id,businessDate:date,counts:parseCounts(v.counts),reason:v.reason}}),'Verify recount');return;
  }
  if(name==='review'){

   const d=await api(savedBase+'/'+id),r=d.request;const own=r.makerSubject===subject();
   const choices=own?[{value:'cancel',label:'Cancel my request'}]:branch.role==='BRANCH_MANAGER'?[{value:'approve',label:'Approve'},{value:'reject',label:'Reject'}]:[];
   const values='<p class="field full">'+e(r.targetLabel)+' · '+e(r.status)+'</p><pre class="field full approval-value">'+e(JSON.stringify({current:r.beforeValues,proposed:r.proposedValues},null,2))+'</pre>';
   if(r.status!=='PENDING'||!choices.length){form('Cash request',values,async()=>{},'Done');return;}
   form('Review cash request',values+field('decision','Decision',choices[0].value,{choices})+field('reason','Reason','',{full:true,max:500}),v=>api(savedBase+'/'+id+'/'+v.decision,{method:'POST',body:{reason:v.reason}}),'Submit decision');return;
  }
  const p=data.items.find(p=>p.location.id===id);if(!p)return;const l=p.location,kind=name.toUpperCase(),key=crypto.randomUUID(),version=data.version;
  if(name==='exception'){
   const date=data.businessDate;
   form('Report cash difference',field('counts','Physical denominations','',{required:false,full:true,max:2000,help:'Enter denomination:quantity separated by semicolons; blank means zero cash.'})+field('reason','Discrepancy evidence','',{full:true,max:500}),v=>api(savedBase+'/exceptions',{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:version,locationId:id,businessDate:date,counts:parseCounts(v.counts),reason:v.reason}}),'Report and freeze');return;
  }
  let fields='<p class="field full">'+e(l.code)+' · '+e(l.currency)+' · '+e(p.balance)+'</p>';
  if(kind==='INVENTORY'||kind==='OPEN'||kind==='CLOSE'||kind==='COUNT')fields+=field('counts','Counted denominations','',{required:false,full:true,max:2000,help:'Example: 100:3; 50:2 means three notes of 100 and two of 50. Leave blank only for zero cash. A difference blocks opening or closing.'});
  else{
   if(p.inventoryEnabled)fields+=field('counts','Movement denominations','',{full:true,max:2000,help:'Enter notes/coins moved, not the full location balance.'});
   fields+=field('amount','Amount','',{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'});
   if(kind==='DEPOSIT'||kind==='WITHDRAWAL'){
    fields+=field('accountId','Customer account','',{choices:(data.accounts||[]).filter(a=>a.currency===l.currency&&(kind==='DEPOSIT'||a.state==='ACTIVE')).map(a=>({value:a.id,label:a.account_number}))});
    if(kind==='DEPOSIT')fields+=field('liabilityLedgerId','Customer liability ledger ID','',{required:false,help:'Leave blank for an already funded account. For the first deposit, enter its dedicated approved liability ledger ID.'});
   }else if(kind==='TRANSFER')fields+=field('destinationId','Destination','',{choices:data.items.filter(x=>x.location.id!==id&&x.location.currency===l.currency&&!(x.location.type==='TILL'&&l.type==='TILL')).map(x=>({value:x.location.id,label:x.location.code}))});
   else fields+=field('ledgerId','External settlement asset ledger ID','',{help:'Use the approved asset ledger for the external cash source or destination.'});
  }
  fields+=field('businessDate','Business date',data.businessDate,{type:'date'})+field('reference','Reference','',{max:80})+field('reason','Cash evidence / reason','',{full:true,max:500});
  form(t(kind==='INVENTORY'?'Activate denomination inventory':kind==='OPEN'?'Open till':kind==='CLOSE'?'Close till':kind==='COUNT'?'Record daily cash count':kind==='TRANSFER'?'Transfer cash':kind==='RECEIVE'?'Receive external cash':kind==='DEPOSIT'?'Customer cash deposit':kind==='WITHDRAWAL'?'Customer cash withdrawal':'Dispatch external cash'),fields,d=>{const body={expectedVersion:version,kind,branchId:savedBranch.branch_id,locationId:id,destinationId:d.destinationId||null,ledgerId:d.ledgerId||null,code:null,name:null,currency:null,type:null,amount:d.amount||null,businessDate:d.businessDate,reference:d.reference,accountId:d.accountId||null,liabilityLedgerId:kind==='DEPOSIT'?(d.liabilityLedgerId||(data.accounts||[]).find(a=>a.id===d.accountId)?.liability_ledger_id||null):null,counts:p.inventoryEnabled||kind==='INVENTORY'||kind==='OPEN'||kind==='CLOSE'||kind==='COUNT'?parseCounts(d.counts):null,reason:d.reason};const send=signatories=>api(savedBase,{method:'POST',headers:{'Idempotency-Key':key},body:signatories?{...body,signatories}:body});return kind==='WITHDRAWAL'&&d.accountId?requestSigners({api,form,field},'/banks/'+savedBranch.bank_id+'/branches/'+savedBranch.branch_id+'/operations',d.accountId,send):send(null);},'Submit for approval');
 }
 return {render,action};
}
