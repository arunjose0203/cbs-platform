import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';
import {createCashUi} from './cash-ui.js';

export function createBranchUi({api,form,field,refresh,subject=()=>null}){
 const cash=createCashUi({api,form,field,refresh,subject});let cashMode=false;
 let branches=[],selected=null,accounts=[],requests=[],version=0,businessDate='',accountOffset=0,requestOffset=0;
 const base=()=>'/banks/'+selected.bank_id+'/branches/'+selected.branch_id+'/operations';
 const button=(label,action,id='')=>'<button type="button" class="button secondary" data-action="branch-work-'+action+'" data-id="'+e(id)+'">'+e(t(label))+'</button>';
 async function render(content,isCurrent){
  branches=await api('/banks/operations/branches');if(!isCurrent())return;
  selected=branches.find(x=>x.branch_id===selected?.branch_id)||branches[0];
  if(!selected){content.innerHTML='<h1>'+e(t('Branch workspace'))+'</h1><p>'+e(t('No active branch memberships are available. Ask an administrator to assign your bank and branch roles.'))+'</p>';return;}
  if(cashMode){await cash.render(selected,content,isCurrent);return;}
  const [a,r]=await Promise.all([api(base()+'/accounts?limit=25&offset='+accountOffset),api(base()+'/requests?limit=25&offset='+requestOffset)]);if(!isCurrent())return;
  accounts=a.items;requests=r.items;version=a.version;businessDate=a.businessDate;
  content.innerHTML='<div class="heading"><div><h1>'+e(t('Branch workspace'))+'</h1><p>'+e(selected.bank_name)+' · '+e(selected.branch_name)+'</p></div>'+button('Choose branch','choose')+button('Teller & cash custody','cash')+'</div><p>'+e(t('Transactions require recent MFA, assigned accounts, approved branch and staff limits, and an independent manager’s approval.'))+'</p><section class="card"><h2>'+e(t('Assigned accounts'))+'</h2><div class="table-wrap"><table><thead><tr><th>'+e(t('Account'))+'</th><th>'+e(t('Currency'))+'</th><th>'+e(t('Status'))+'</th><th></th></tr></thead><tbody>'+accounts.map(x=>'<tr><td>'+e(x.account_number)+'</td><td>'+e(x.currency)+'</td><td>'+e(x.state)+' · '+e(x.delivery)+'</td><td>'+(selected.role==='AUDITOR'?'':button('Submit request','account',x.id))+'</td></tr>').join('')+'</tbody></table></div>'+button('Previous accounts','accounts-previous')+button('Next accounts','accounts-next')+'</section><section class="card"><h2>'+e(t('Branch requests'))+'</h2><div class="table-wrap"><table><thead><tr><th>'+e(t('Request'))+'</th><th>'+e(t('Status'))+'</th><th></th></tr></thead><tbody>'+requests.map(x=>'<tr><td>'+e(x.target_label)+'</td><td>'+e(x.status)+'</td><td>'+button('Review','review',x.id)+'</td></tr>').join('')+'</tbody></table></div>'+button('Previous requests','requests-previous')+button('Next requests','requests-next')+'</section>';
 }
 async function action(name,id){
  if(name==='cash'||name==='cash-back'){cashMode=name==='cash';await refresh();return;}
  if(name.startsWith('cash-')){await cash.action(name.slice(5),id);return;}
  if(name==='choose'){form('Choose branch',field('branchId','Branch',selected?.branch_id,{choices:branches.map(x=>({value:x.branch_id,label:x.bank_name+' · '+x.branch_name}))}),async d=>{selected=branches.find(x=>x.branch_id===d.branchId);accountOffset=0;requestOffset=0;await refresh();});return;}
  if(!selected)return;
  if(name.startsWith('accounts-')||name.startsWith('requests-')){const delta=name.endsWith('next')?25:-25;if(name.startsWith('accounts-'))accountOffset=Math.max(0,accountOffset+delta);else requestOffset=Math.max(0,requestOffset+delta);await refresh();return;}
  if(name==='account'){
   const account=accounts.find(x=>x.id===id);if(!account)return;const savedBase=base(),savedVersion=version,key=crypto.randomUUID();
   form('Submit branch request','<p class="field full">'+e(account.account_number)+' · '+e(account.currency)+'</p>'+field('kind','Operation','HOLD',{choices:[{value:'HOLD',label:t('Hold funds')},{value:'RELEASE',label:t('Release hold')},{value:'TRANSFER',label:t('Transfer within branch')}]})+field('amount','Amount','',{pattern:'[0-9]{1,18}(\\.[0-9]{1,6})?'})+field('destinationId','Destination account','',{required:false,choices:[{value:'',label:t('Not applicable')},...accounts.filter(x=>x.id!==id&&x.currency===account.currency).map(x=>({value:x.id,label:x.account_number}))]})+field('originalId','Original hold request ID','',{required:false,help:t('Required only when releasing a hold.')})+field('businessDate','Business date',businessDate,{type:'date'})+field('reference','Reference','',{max:80})+field('reason','Reason','',{full:true,max:500}),d=>api(savedBase+'/requests',{method:'POST',headers:{'Idempotency-Key':key},body:{...d,expectedVersion:savedVersion,accountId:id,destinationId:d.kind==='TRANSFER'?d.destinationId:null,originalId:d.kind==='RELEASE'?d.originalId:null,assetLedgerId:null}}),'Submit for approval');return;
  }
  if(name==='review'){
   const savedBase=base();const d=await api(savedBase+'/requests/'+id);const r=d.request;
   const own=r.makerSubject===subject();const choices=own?[{value:'cancel',label:t('Cancel my request')}]:selected.role==='BRANCH_MANAGER'?[{value:'approve',label:t('Approve')},{value:'reject',label:t('Reject')}]:[];
   const fields='<p class="field full">'+e(r.targetLabel)+' · '+e(r.status)+'</p><pre class="field full approval-value">'+e(JSON.stringify(r.proposedValues,null,2))+'</pre>';
   if(r.status!=='PENDING'||!choices.length){form('Branch request',fields,async()=>{},'Done');return;}
   form('Review branch request',fields+field('decision','Decision',choices[0].value,{choices})+field('reason','Reason','',{full:true,max:500}),d=>api(savedBase+'/requests/'+id+'/'+d.decision,{method:'POST',body:{reason:d.reason}}),'Submit decision');
  }
 }
 return {render,action};
}
