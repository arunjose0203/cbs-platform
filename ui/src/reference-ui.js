import {escapeHtml as e} from './model.js';
import {t} from './i18n.js';
export function createReferenceUi({api,form,field,refresh}){
  let bankId='',section='currencies',catalogue,settings;
  const days=['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
  const button=(label,action,id='',secondary=false)=>`<button class="button ${secondary?'secondary':''}" data-action="reference-${action}" data-id="${e(id)}">${e(t(label))}</button>`;
  const label=(text,value)=>`<div><dt>${e(t(text))}</dt><dd>${e(value)}</dd></div>`;
  const table=(heads,body)=>`<div class="table-wrap"><table><thead><tr>${heads.map(h=>`<th scope="col">${e(t(h))}</th>`).join('')}</tr></thead><tbody>${body}</tbody></table></div>`;
  const options=(items,value)=>items.map(item=>`<option value="${e(item.value)}" ${item.value===value?'selected':''}>${e(item.label)}</option>`).join('');
  const currencyChoices=()=>catalogue.currencies.map(c=>({value:c.code,label:`${c.code} — ${c.name}`}));
  const currencyName=code=>catalogue.currencies.find(c=>c.code===code);
  const reason=()=>field('reason','Reason for this change','',{full:true,max:500});
  function edit(title,fields,makeChange){
    const saved=structuredClone(settings),key=crypto.randomUUID();
    form(title,`<p class="field full approval-note">${e(t('Changes take effect after approval by another administrator.'))}</p>`+fields+reason(),d=>{
      const {bankId:target,version,...body}=saved;
      return api(`/admin/tenants/${target}/settings`,{method:'PUT',headers:{'Idempotency-Key':key},body:{...body,...makeChange(d,saved),expectedVersion:version,reason:d.reason}});
    },'Submit for approval');
  }
  async function render(host,isCurrent){
    const [banks,meta]=await Promise.all([api('/admin/tenants?limit=100'),catalogue?Promise.resolve(catalogue):api('/admin/reference-data/catalogue')]);
    if(!isCurrent())return;
    catalogue=meta;
    if(!bankId&&banks.items.length)bankId=banks.items[0].id;
    let bank=banks.items.find(b=>b.id===bankId);
    if(bankId&&!bank){bank=await api(`/admin/tenants/${bankId}`);banks.items.push(bank);}
    if(!bank){host.innerHTML=`<div class="heading"><h1>${e(t('Bank settings'))}</h1></div><section class="card empty"><h2>${e(t('No banks registered yet.'))}</h2><p>${e(t('Register a bank first to configure its currencies, languages and calendar.'))}</p></section>`;return;}
    const latest=await api(`/admin/tenants/${bankId}/settings`);if(!isCurrent())return;settings=latest;
    const tabs=[['currencies','Currencies'],['languages','Languages'],['calendar','Business calendar']];
    let body='';
    if(section==='currencies'){
      body=`<section class="card"><div class="panel-title"><div><h2>${e(t('Currencies'))}</h2><p>${e(t('Precision follows the currency catalogue. Functional currencies must stay enabled.'))}</p></div>${button('Add currency','currency')}</div>`+
        table(['Currency','Decimal places','Rounding rule','Status',''],settings.currencies.map(c=>`<tr><td><strong>${e(c.code)}</strong><small>${e(currencyName(c.code)?.name??c.code)}</small></td><td>${e(currencyName(c.code)?.decimalPlaces??'—')}</td><td>${e(c.roundingMode)}</td><td>${e(t(c.enabled?'Enabled':'Disabled'))}</td><td>${button('Edit','currency',c.code,true)}</td></tr>`).join(''))+'</section>'+
        `<section class="card section-gap"><div class="panel-title"><h2>${e(t('Rounding preview'))}</h2></div><div class="toolbar reference-tools"><label class="field">${e(t('Currency'))}<select id="round-currency">${options(settings.currencies.filter(c=>c.enabled).map(c=>({value:c.code,label:c.code})),settings.currencies.find(c=>c.enabled)?.code)}</select></label><label class="field">${e(t('Amount'))}<input id="round-amount" value="123.455" maxlength="38" inputmode="decimal"></label>${button('Preview','round')}<output id="round-result" aria-live="polite"></output></div></section>`;
    }
    if(section==='languages')body=`<section class="card"><div class="panel-title"><h2>${e(t('Languages'))}</h2>${button('Edit languages','languages')}</div><dl class="metadata">${label('Supported languages',settings.languages.join(', '))}${label('Default language',settings.defaultLanguage)}</dl><p class="reference-note">${e(t('English, French and Swahili are available for the settings screens. Other interface text falls back to English.'))}</p></section>`;
    if(section==='calendar')body=`<section class="card"><div class="panel-title"><h2>${e(t('Business calendar'))}</h2>${button('Edit calendar','calendar')}</div><dl class="metadata">${label('Business timezone',settings.businessTimezone)}${label('Business date',settings.businessDate)}${label('Weekend days',settings.weekendDays.map(d=>t(days[d-1])).join(', ')||'—')}</dl><p class="reference-note">${e(t('After ledgers are created, advance the date through Business Day Close. Calendar settings still require approval.'))}</p></section>
      <section class="card section-gap"><div class="panel-title"><h2>${e(t('Holidays'))}</h2>${button('Add holiday','holiday')}</div>${table(['Date','Holiday name',''],settings.holidays.length?settings.holidays.map(h=>`<tr><td>${e(h.date)}</td><td>${e(h.name)}</td><td><div class="actions">${button('Edit','holiday',h.date,true)}${button('Remove','remove-holiday',h.date,true)}</div></td></tr>`).join(''):`<tr><td colspan="3">${e(t('No holidays configured.'))}</td></tr>`)}</section>
      <section class="card section-gap"><div class="panel-title"><h2>${e(t('Check working day'))}</h2></div><div class="toolbar reference-tools"><label class="field">${e(t('Date'))}<input id="calendar-date" type="date" value="${e(settings.businessDate)}"></label>${button('Check date','day')}<output id="calendar-result" aria-live="polite"></output></div></section>`;
    host.innerHTML=`<div class="heading"><div><p class="eyebrow">${e(t('Configure bank settings'))}</p><h1>${e(t('Bank settings'))}</h1><p class="intro">${e(bank.displayName)} · ${e(t('Changes take effect after approval by another administrator.'))}</p></div></div><div class="toolbar reference-bank"><label class="field">${e(t('Bank'))}<select id="reference-bank">${options(banks.items.map(b=>({value:b.id,label:b.displayName})),bankId)}</select></label><small>${e(t('Bank selector shows the first 100 banks.'))}</small></div><div class="actions approval-tabs">${tabs.map(([key,name])=>`<button class="button ${key===section?'':'secondary'}" data-action="reference-tab" data-id="${key}" aria-pressed="${key===section}">${e(t(name))}</button>`).join('')}</div>${body}`;
  }
  async function action(name,id){
    if(name==='tab'){section=id;await refresh();return;}
    if(!settings)return;
    if(name==='currency'){
      const current=settings.currencies.find(c=>c.code===id);
      const available=currencyChoices().filter(c=>c.value===id||!settings.currencies.some(r=>r.code===c.value));
      edit(current?'Edit':'Add currency',field('code','Currency',id||available[0]?.value,{choices:available})+field('roundingMode','Rounding rule',current?.roundingMode||'HALF_EVEN',{choices:catalogue.roundingModes.map(value=>({value,label:value}))})+field('enabled','Status',String(current?.enabled??true),{choices:[{value:'true',label:t('Enabled')},{value:'false',label:t('Disabled')}]}),
        (d,saved)=>({currencies:[...saved.currencies.filter(c=>c.code!==id),{code:d.code,roundingMode:d.roundingMode,enabled:d.enabled==='true'}].sort((a,b)=>a.code.localeCompare(b.code))}));return;
    }
    if(name==='languages'){
      edit('Edit languages',field('languages','Supported languages',settings.languages.join(', '),{full:true,max:1000,help:t('Use comma-separated language tags, for example en, fr, sw.')})+field('defaultLanguage','Default language',settings.defaultLanguage,{max:35}),d=>({languages:d.languages.split(',').map(tag=>tag.trim()).filter(Boolean),defaultLanguage:d.defaultLanguage.trim()}));return;
    }
    if(name==='calendar'){
      edit('Edit calendar',field('businessTimezone','Business timezone',settings.businessTimezone,{full:true,choices:catalogue.timezones.map(value=>({value,label:value}))})+field('businessDate','Business date',settings.businessDate,{type:'date'})+`<fieldset class="field full weekend-options"><legend>${e(t('Weekend days'))}</legend>${days.map((day,index)=>`<label><input type="checkbox" name="weekend${index+1}" ${settings.weekendDays.includes(index+1)?'checked':''}> ${e(t(day))}</label>`).join('')}</fieldset>`,d=>({businessTimezone:d.businessTimezone,businessDate:d.businessDate,weekendDays:days.map((_,i)=>i+1).filter(day=>d[`weekend${day}`]==='on')}));return;
    }
    if(name==='holiday'){
      const current=settings.holidays.find(h=>h.date===id);
      edit(current?'Edit':'Add holiday',field('date','Date',current?.date||'',{type:'date'})+field('name','Holiday name',current?.name||'',{max:120}), (d,saved)=>({holidays:[...saved.holidays.filter(h=>h.date!==id),{date:d.date,name:d.name}].sort((a,b)=>a.date.localeCompare(b.date))}));return;
    }
    if(name==='remove-holiday'){edit('Remove',`<p class="field full">${e(id)} — ${e(settings.holidays.find(h=>h.date===id)?.name)}</p>`,(d,saved)=>({holidays:saved.holidays.filter(h=>h.date!==id)}));return;}
    if(name==='day'){
      const date=document.querySelector('#calendar-date').value,result=await api(`/admin/tenants/${bankId}/calendar-day?date=${encodeURIComponent(date)}`);
      const output=document.querySelector('#calendar-result');if(output)output.textContent=`${t(result.workingDay?'Working day':'Non-working day')}${result.holiday?` · ${result.holiday}`:''} · ${t('Next working day')}: ${result.nextWorkingDay}`;return;
    }
    if(name==='round'){
      const currency=document.querySelector('#round-currency').value,amount=document.querySelector('#round-amount').value;
      const result=await api(`/admin/tenants/${bankId}/rounding-preview?${new URLSearchParams({currency,amount})}`);
      const output=document.querySelector('#round-result');if(output)output.textContent=`${result.currency} ${result.roundedAmount} · ${result.roundingMode}`;
    }
  }
  return {render,action,selectBank(id){bankId=id;}};
}
