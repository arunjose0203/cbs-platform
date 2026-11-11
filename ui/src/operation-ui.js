import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';
export function createOperationUi({api,form,field}){
 let selected='',offset=0;
 const label=kind=>({TRANSFER:'Internal transfer',WITHDRAWAL:'Record withdrawal',HOLD:'Place hold',RELEASE:'Release hold',FUNDING_REVERSAL:'Reverse funding',WITHDRAWAL_REVERSAL:'Reverse withdrawal',TRANSFER_REVERSAL:'Reverse transfer',CLOSURE_SETTLEMENT:'Final closure settlement'})[kind]||kind;
 const note=text=>'<p class="field full reference-note">'+e(t(text))+'</p>';
 const button=(label,action,id,disabled=false)=>'<button type="button" class="button secondary" data-action="account-operation-'+action+'" data-id="'+e(id)+'" '+(disabled?'disabled':'')+'>'+e(t(label))+'</button>';
 async function action(name,id,bankId,isCurrent){
  const base='/admin/tenants/'+bankId;let original=null;
  if(name==='retry'){await api(base+'/account-operations/'+id+'/retry',{method:'POST'});if(!isCurrent())return;name='view';id=selected;}
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));name='view';id=selected;}
  if(name==='RELEASE'||name==='reverse'){const parts=id.split('/');id=parts[0];original=parts[1];}
  if(selected!==id){selected=id;offset=0;}
  const [d,row]=await Promise.all([api(base+'/account-operations/'+id+'?limit=25&offset='+offset),api(base+'/accounts/'+id)]);if(!isCurrent())return;
  const a=row.definition,p=d.position;
  if(!d.storageReady){form('Prepare transaction storage',note('Upgrade this bank’s storage before using holds or debits.')+field('reason','Reason for this change','',{full:true,max:500}),values=>api(base+'/accounts/prepare',{method:'POST',body:{expectedVersion:d.version,reason:values.reason}}),'Prepare account storage');return;}
  if(!p){form('Account operations',note('Fund this account before placing holds or making transfers.'),async()=>{},'Done');return;}
  const enabled=a.state==='ACTIVE'&&row.delivery==='APPLIED'&&p.reconciled;
  if(name==='view'){
   const values={'Posted customer balance':p.posted,'Held amount':p.held,'Available balance':p.available,'Withdrawable after minimum balance':p.withdrawable};
   form('Account operations','<p class="field full operation-heading">'+e(a.accountNumber)+' · '+e(a.terms.currency)+'</p><dl class="metadata field full">'+Object.entries(values).map(([k,v])=>'<div><dt>'+e(t(k))+'</dt><dd>'+e(v)+'</dd></div>').join('')+'</dl>'+(!p.reconciled?note('Mismatch — investigate before posting'):'')+'<div class="field full actions operation-actions">'+[['Internal transfer','TRANSFER'],['Record withdrawal','WITHDRAWAL'],['Place hold','HOLD'],['Reverse funding','FUNDING_REVERSAL']].map(([label,kind])=>button(label,kind,id,!enabled)).join('')+'</div><div class="field full table-wrap"><table><thead><tr>'+['Operation','Reference','Amount','Posting status',''].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+d.items.map(x=>'<tr><td>'+e(t(label(x.entry.change.kind)))+'</td><td>'+e(x.entry.change.reference)+'</td><td>'+e(x.entry.change.amount)+'</td><td>'+e(t(x.status==='POSTED'?'Posted':'Awaiting confirmation'))+(x.failureCode?'<small>'+e(t(errorMessage(x.failureCode)))+'</small>':'')+'</td><td>'+(x.status!=='POSTED'?button('Retry posting','retry',x.entry.id):!x.cashManaged&&['WITHDRAWAL','TRANSFER'].includes(x.entry.change.kind)&&enabled?button('Reverse transaction','reverse',id+'/'+x.entry.id):x.entry.change.kind==='HOLD'&&!d.items.some(r=>r.entry.change.kind==='RELEASE'&&r.entry.change.originalId===x.entry.id)?button('Release hold','RELEASE',id+'/'+x.entry.id):'')+'</td></tr>').join('')+'</tbody></table></div><div class="field full actions operation-actions">'+button('Previous','previous',id,offset===0)+button('Next','next',id,offset+25>=d.total)+'</div>'+note('Holds reserve funds without moving money. All changes require independent approval. Available balance excludes holds; withdrawable balance also protects the product minimum.'),async()=>{},'Done');return;
  }
  if(name==='reverse'){
   const originalItem=d.items.find(x=>x.entry.id===original);if(!originalItem||originalItem.cashManaged||originalItem.status!=='POSTED'||!['TRANSFER','WITHDRAWAL'].includes(originalItem.entry.change.kind)||!enabled)return;
   const old=originalItem.entry.change,settings=await api(base+'/settings');if(!isCurrent())return;
   const key=crypto.randomUUID();form('Reverse customer transaction',note(old.reference+' · '+old.amount+' '+a.terms.currency)+note('This reverses the full original transaction. The receiving account must still have sufficient withdrawable funds for a transfer reversal.')+field('reference','Reversal reference','',{max:80})+field('reason','Reason and evidence','',{full:true,max:500}),v=>api(base+'/account-operations',{method:'POST',headers:{'Idempotency-Key':key},body:{expectedVersion:d.version,kind:old.kind==='TRANSFER'?'TRANSFER_REVERSAL':'WITHDRAWAL_REVERSAL',accountId:old.kind==='TRANSFER'?old.destinationId:old.accountId,destinationId:old.kind==='TRANSFER'?old.accountId:null,assetLedgerId:null,originalId:original,amount:old.amount,businessDate:settings.businessDate,reference:v.reference,reason:v.reason}}),'Submit for approval');return;
  }
  if(!['TRANSFER','WITHDRAWAL','HOLD','RELEASE','FUNDING_REVERSAL'].includes(name)||(!enabled&&name!=='RELEASE'))return;
  const settings=await api(base+'/settings');let extra='',choices=[];
  if(name==='TRANSFER'){
   const list=await api(base+'/accounts?state=ACTIVE&limit=100');const targets=list.items.filter(x=>x.id!==id&&x.delivery==='APPLIED'&&x.currency===a.terms.currency);
   if(!targets.length){form('Transfer prerequisites',note('Approve and fund another active account in the same bank, currency and legal entity.'),async()=>{},'Done');return;}
   extra=field('destinationId','Destination account','',{full:true,choices:targets.map(x=>({value:x.id,label:x.accountNumber+' · '+x.name}))})+note('The server verifies that the destination is funded and belongs to the same legal entity. The selector shows the first 100 active accounts.');
  }
  if(name==='WITHDRAWAL'){
   const [ledgers,chart]=await Promise.all([api(base+'/ledger-accounts?active=true&currency='+a.terms.currency+'&limit=100'),api(base+'/chart-of-accounts?category=ASSET&active=true&kind=POSTING&limit=100')]);
   const assets=new Set(chart.items.filter(x=>x.normalBalance==='DEBIT').map(x=>x.id));const rows=ledgers.items.filter(x=>x.delivery==='APPLIED'&&x.definition.legalEntityId===a.legalEntityId&&assets.has(x.definition.chartAccountId));
   if(!rows.length){form('Withdrawal prerequisites',note('Approve an asset ledger in the account currency and legal entity.'),async()=>{},'Done');return;}
   extra=field('assetLedgerId','Paying asset ledger','',{full:true,choices:rows.map(x=>({value:x.definition.id,label:x.definition.code+' · '+x.definition.name}))})+note('Record an independently verified withdrawal. This does not send an external payment or operate a teller till.');
  }
  if(name==='FUNDING_REVERSAL'){
   const funding=await api(base+'/funding/'+id+'?limit=100');choices=funding.items.filter(x=>x.status==='POSTED');
   if(!choices.length){form('Funding reversal',note('No posted funding is available on this page.'),async()=>{},'Done');return;}
   extra=field('originalId','Original funding','',{full:true,choices:choices.map(x=>({value:x.entry.id,label:x.entry.journal.reference+' · '+x.entry.amount}))})+note('Reverses the full original deposit at the current business date. Holds and minimum balance remain protected.');
  }
  const hold=name==='RELEASE'?d.items.find(x=>x.entry.id===original):null;if(name==='RELEASE'&&!hold)return;
  const amountField=['RELEASE','FUNDING_REVERSAL'].includes(name)?'':field('amount','Amount','',{max:25,pattern:'[0-9]+(\\.[0-9]+)?'});
  if(!isCurrent())return;let key=crypto.randomUUID(),previous='';
  form(label(name),'<p class="field full operation-heading">'+e(a.accountNumber)+' · '+e(a.terms.currency)+'</p>'+extra+amountField+field('businessDate','Business date',settings.businessDate,{type:'date'})+field('reference','Reference','',{full:true,max:80})+field('reason','Reason and evidence','',{full:true,max:500}),values=>{
   const body={expectedVersion:d.version,kind:name,accountId:id,destinationId:values.destinationId||null,assetLedgerId:values.assetLedgerId||null,originalId:original||values.originalId||null,amount:hold?.entry.change.amount||choices.find(x=>x.entry.id===values.originalId)?.entry.amount||values.amount,businessDate:values.businessDate,reference:values.reference,reason:values.reason};
   const payload=JSON.stringify(body);if(previous&&previous!==payload)key=crypto.randomUUID();previous=payload;
   return api(base+'/account-operations',{method:'POST',headers:{'Idempotency-Key':key},body});
  },'Submit for approval');
 }
 return {action};
}
