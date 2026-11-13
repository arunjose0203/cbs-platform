import {escapeHtml as e,errorMessage} from './model.js';
import {t} from './i18n.js';

const STEPS={SERVICING:'Deposit servicing',PERIOD_CLOSE:'Month-end and year-end close',DAY_CLOSE:'Business Day Close'};
const STATUS={DONE:'Done',NOT_REQUIRED:'Not required today',WAITING_APPROVAL:'Waiting for approval',WAITING_DELIVERY:'Posting',OPEN:'Not started',SUBMITTED:'Submitted for approval',BLOCKED:'Blocked'};

/** P-07 operations console: the daily cycle, pending approvals, delivery queues and exceptions for one bank. Read-only. */
export function createOpsConsoleUi({api,form,field,refresh}){
 let bankId='',banks=[],current=()=>false;
 const button=(label,action)=>'<button type="button" class="button secondary" data-action="ops-'+action+'">'+e(t(label))+'</button>';
 const table=(headers,rows,empty)=>'<div class="table-wrap"><table><thead><tr>'+headers.map(h=>'<th>'+e(t(h))+'</th>').join('')+'</tr></thead><tbody>'+(rows.length?rows.join(''):'<tr><td colspan="'+headers.length+'" class="empty">'+e(t(empty))+'</td></tr>')+'</tbody></table></div>';
 async function render(host,isCurrent){
  current=isCurrent;const directory=await api('/admin/tenants?limit=100');if(!current())return;banks=directory.items;
  if(!bankId)bankId=banks.find(b=>b.status==='ACTIVE')?.id||banks[0]?.id;
  if(!bankId){host.innerHTML='<h1>'+e(t('Operations console'))+'</h1><p>'+e(t('Register a bank first.'))+'</p>';return;}
  const bank=banks.find(b=>b.id===bankId)||{};const c=await api('/admin/tenants/'+bankId+'/operations-console');if(!current())return;
  const scheduler=c.scheduler||{};
  host.innerHTML='<div class="heading"><div><h1>'+e(t('Operations console'))+'</h1><p>'+e(bank.displayName||'')+' · '+e(t('Business date'))+' '+e(c.businessDate)+' → '+e(c.nextBusinessDate)+'</p></div><div class="actions">'+button('Choose bank','choose')+button('Refresh','refresh')+'</div></div>'+
   '<section class="card" aria-live="polite"><div class="panel-title"><h2>'+e(t('Daily cycle'))+'</h2><span>'+e(scheduler.enabled?t('Prepared automatically after')+' '+scheduler.prepareAfter+' ('+c.timezone+')':t('Prepared by staff'))+'</span></div>'+
   '<ol class="cycle">'+(c.cycle||[]).map(s=>'<li data-status="'+e(s.status)+'"><strong>'+e(t(STEPS[s.step]||s.step))+'</strong> — '+e(t(STATUS[s.status]||s.status))+'</li>').join('')+'</ol>'+
   ((c.dayCloseBlockers||[]).length?'<h3>'+e(t('Before the day can close'))+'</h3><ul>'+c.dayCloseBlockers.map(b=>'<li>'+e(t(errorMessage(b)))+'</li>').join('')+'</ul>':'<p>'+e(t('Nothing blocks the day close.'))+'</p>')+
   '<p>'+e(t('The scheduler only submits requests; another person approves every step.'))+'</p></section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Pending approvals'))+'</h2></div>'+table(['Action','Count','Oldest'],(c.approvals||[]).map(a=>'<tr><td>'+e(a.action)+'</td><td>'+e(a.count)+'</td><td>'+e(a.oldest)+'</td></tr>'),'No pending approvals.')+'</section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Delivery queues'))+'</h2></div>'+table(['Queue','Pending','Failing'],(c.queues||[]).map(q=>'<tr'+(q.failing>0?' class="warning"':'')+'><td>'+e(q.queue)+'</td><td>'+e(q.pending)+'</td><td>'+e(q.failing)+'</td></tr>'),'All queues are empty.')+'</section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Exceptions'))+'</h2></div><dl class="facts">'+Object.entries(c.exceptions||{}).map(([k,v])=>'<dt>'+e(t({complianceCasesOverdue:'Overdue compliance cases',paymentCasesOpen:'Open payment cases',statementImportsOpen:'Open statement imports',notificationsFailed:'Failed customer alerts'}[k]||k))+'</dt><dd>'+e(v)+'</dd>').join('')+'</dl></section>'+
   '<section class="card section-gap"><div class="panel-title"><h2>'+e(t('Scheduler history'))+'</h2></div>'+table(['Recorded','Business date','Step','Outcome','Reason'],(c.events||[]).map(x=>'<tr><td>'+e(x.recordedAt)+'</td><td>'+e(x.businessDate)+'</td><td>'+e(t(STEPS[x.step]||x.step))+'</td><td>'+e(t(STATUS[x.outcome]||x.outcome))+'</td><td>'+(x.code?e(t(errorMessage(x.code))):'—')+'</td></tr>'),'No scheduler activity yet.')+'</section>';
 }
 async function action(name){
  if(!current())return;
  if(name==='refresh'){await refresh();return;}
  if(name==='choose'){form('Choose bank',field('bank','Bank',bankId,{choices:banks.map(b=>({value:b.id,label:b.displayName}))}),async v=>{bankId=v.bank;},'Open');}
 }
 return {render,action,selectBank:id=>{bankId=id;}};
}
