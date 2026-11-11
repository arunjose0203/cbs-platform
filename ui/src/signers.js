import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';

/**
 * Second step for debits on joint, organization and minors' accounts: asks which authorised
 * signers approved the instruction, then calls submit(signatories). Accounts that need no
 * signers submit immediately with null.
 */
export async function requestSigners({api,form,field},base,accountId,submit){
 const m=await api(base+'/accounts/'+accountId+'/mandate');
 if(!m?.required)return submit(null);
 if(!m.configured)throw Error(t('This account needs an approved mandate before it can be debited.'));
 const count=Math.min(m.signers.length,Math.max(m.signaturesNeeded,1));
 const label=x=>x.customerNumber+' · '+t(x.role==='HOLDER'?'Holder':x.role==='JOINT_HOLDER'?'Joint holder':x.role==='GUARDIAN'?'Guardian':'Authorised signatory');
 const choices=m.signers.map(x=>({value:x.customerId,label:label(x)}));
 form('Authorising signers','<p class="field full">'+e(t('Signatures required'))+': '+e(m.signaturesNeeded)+'</p>'+Array.from({length:count},(_,i)=>field('signer'+i,t('Signer')+' '+(i+1),choices[i]?.value||'',{choices})).join(''),
  v=>submit([...new Set(Array.from({length:count},(_,i)=>v['signer'+i]).filter(Boolean))]),'Submit for approval');
 return {keepOpen:true};
}
