import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';
export function createStatementUi({api,form,field}){
 let selected='',from='',to='',offset=0;
 const button=(label,action,disabled=false)=>'<button type="button" class="button secondary" data-action="account-statement-'+action+'" data-id="'+e(selected)+'" '+(disabled?'disabled':'')+'>'+e(t(label))+'</button>';
 async function action(name,id,bankId,isCurrent){
  const base='/admin/tenants/'+bankId;
  if(selected!==id){selected=id;offset=0;from='';to='';}
  if(name==='export'){
   const csv=await api(base+'/accounts/'+id+'/statement/export?'+new URLSearchParams({from,to}),{responseType:'text'});if(!isCurrent())return;
   const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='statement-'+id+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return;
  }
  if(name==='view'){
   const settings=await api(base+'/settings');if(!isCurrent())return;
   form('Customer statement',field('from','From date',from||settings.businessDate,{type:'date'})+field('to','To date',to||settings.businessDate,{type:'date'}),async values=>{from=values.from;to=values.to;offset=0;await show();return {keepOpen:true};},'View statement');return;
  }
  if(name==='previous'||name==='next'){offset=Math.max(0,offset+(name==='next'?25:-25));await show();}
  async function show(){
   const d=await api(base+'/accounts/'+id+'/statement?'+new URLSearchParams({from,to,limit:25,offset}));if(!isCurrent())return;
   form('Customer statement','<p class="field full operation-heading">'+e(d.accountNumber)+' · '+e(d.currency)+' · '+e(d.from)+' – '+e(d.to)+'</p><dl class="metadata field full"><div><dt>'+e(t('Opening balance'))+'</dt><dd>'+e(d.openingBalance)+'</dd></div><div><dt>'+e(t('Closing balance'))+'</dt><dd>'+e(d.closingBalance)+'</dd></div></dl><div class="field full table-wrap"><table><thead><tr>'+['Business date','Reference','Debit','Credit','Balance'].map(x=>'<th>'+e(t(x))+'</th>').join('')+'</tr></thead><tbody>'+d.items.map(x=>'<tr><td>'+e(x.businessDate)+'</td><td>'+e(x.reference)+'<small>'+e(x.narration)+'</small></td><td>'+e(x.debit)+'</td><td>'+e(x.credit)+'</td><td>'+e(x.balance)+'</td></tr>').join('')+'</tbody></table></div><div class="field full actions operation-actions">'+button('Export CSV','export')+button('Previous','previous',offset===0)+button('Next','next',offset+25>=d.total)+'</div><p class="field full reference-note">'+e(t('Posted transactions only. Holds do not change statement balances.'))+'</p>',async()=>{},'Done');
  }
 }
 return {action};
}
