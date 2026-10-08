import {escapeHtml as e} from './model.js';
export function createFxTradeUi({api,form,field,refresh}){
 const post=(path,body,key=crypto.randomUUID())=>api(path,{method:'POST',headers:{'Idempotency-Key':key},body});
 const button=(text,action,id='',disabled=false)=>`<button type="button" class="button secondary" data-action="fx-${action}" data-id="${e(id)}" ${disabled?'disabled':''}>${e(text)}</button>`;
 function section(data){return `<section class="card section-gap"><h2>Customer currency conversions</h2><p>Move funds between active accounts belonging to the same customer and legal entity. Each conversion requires independent approval, explicit bank-wide FX limits and dedicated currency position ledgers. Both currency journals post together.</p>${!data.storageReady?button('Prepare FX storage','prepare'):''}<div class="table-wrap"><table><thead><tr><th>Business date / reference</th><th>Customer gives</th><th>Customer receives</th><th>Status</th><th></th></tr></thead><tbody>${data.items.map(x=>`<tr><td>${e(x.batch.quote.businessDate)}<small>${e(x.id)}</small></td><td>${e(x.batch.source.accountNumber)}<small>${e(x.batch.quote.sourceAmount)} ${e(x.batch.quote.sourceCurrency)}</small></td><td>${e(x.batch.target.accountNumber)}<small>${e(x.batch.quote.targetAmount)} ${e(x.batch.quote.targetCurrency)}</small></td><td>${e(x.completed?'Posted':x.failureCode||'Awaiting posting')}${x.reversed?' · reversal approved':''}${x.batch.originalId?' · reversal':''}</td><td>${!x.completed?button('Retry both legs','trade-retry',x.id):!x.reversed&&!x.batch.originalId?button('Reverse conversion','reverse',x.id):''}</td></tr>`).join('')||'<tr><td colspan="5">No approved conversions.</td></tr>'}</tbody></table></div><div class="pagination"><span>${e(data.total)} conversions</span>${button('Previous conversions','trades-previous','',data.offset===0)}${button('Next conversions','trades-next','',data.offset+25>=data.total)}</div></section>`;}
 async function action(name,id,{route,version,date,rate,trades}){
  if(name==='prepare'){await post(route+'/prepare');await refresh();return;}
  if(name==='trade-retry'){await post(route+'/trades/'+id+'/retry');await refresh();return;}
  if(name==='reverse'){
   const item=trades.items.find(x=>x.id===id);if(!item)return;const q=item.batch.quote,key=crypto.randomUUID();
   form('Reverse complete conversion',`<p class="field full">Return ${e(q.targetAmount)} ${e(q.targetCurrency)} from ${e(item.batch.target.accountNumber)} and restore ${e(q.sourceAmount)} ${e(q.sourceCurrency)} to ${e(item.batch.source.accountNumber)}. This uses the original amounts; sufficient available funds and an approved reversal limit are required.</p>`+field('reason','Reason and evidence','',{full:true,max:500}),v=>post(route+'/reversals',{expectedVersion:version,originalId:id,businessDate:date,reason:v.reason},key),'Submit for independent approval');return;
  }
  if(name!=='convert'||!rate)return;
  const options=[{value:'BANK_BUYS_BASE',label:`Give ${rate.baseCurrency}, receive ${rate.quoteCurrency}`},{value:'BANK_SELLS_BASE',label:`Give ${rate.quoteCurrency}, receive ${rate.baseCurrency}`}];
  form('Find customer accounts',field('search','Customer number or account search','',{full:true,max:100})+field('side','Conversion direction','BANK_BUYS_BASE',{choices:options}),async selection=>{
   const parent=route.replace(/\/fx$/,''),sourceCurrency=selection.side==='BANK_BUYS_BASE'?rate.baseCurrency:rate.quoteCurrency,targetCurrency=selection.side==='BANK_BUYS_BASE'?rate.quoteCurrency:rate.baseCurrency;
   const accounts=await api(parent+'/accounts?state=ACTIVE&limit=100&search='+encodeURIComponent(selection.search));
   if(accounts.total>100)throw Error('Narrow the search to one customer; more than 100 accounts matched.');
   const source=accounts.items.filter(x=>x.currency===sourceCurrency&&x.delivery==='APPLIED'),target=accounts.items.filter(x=>x.currency===targetCurrency&&x.delivery==='APPLIED');
   if(!source.length||!target.length)throw Error('This search needs funded active accounts in both currencies. Search using the customer number.');
   const choices=items=>items.map(x=>({value:x.id,label:`${x.accountNumber} · ${x.customerNumber} · ${x.currency}`}));
   form('Prepare customer conversion',`<p class="field full">Use dedicated approved EQUITY position ledgers in each account’s legal entity and currency. The server checks the customer, ledgers, balance and limits.</p>`+field('sourceAccountId','Account to debit',source[0].id,{choices:choices(source)})+field('targetAccountId','Account to credit',target[0].id,{choices:choices(target)})+field('sourcePositionLedger',sourceCurrency+' position ledger ID','',{full:true})+field('targetPositionLedger',targetCurrency+' position ledger ID','',{full:true})+field('sourceAmount','Amount to debit in '+sourceCurrency,'',{max:25})+field('reason','Reason and evidence','',{full:true,max:500}),async input=>{
    const quote=await post(route+'/quote',{rateId:id,side:selection.side,sourceAmount:input.sourceAmount});
    const body={...input,expectedVersion:version,businessDate:date,rateId:id,side:selection.side,expectedTargetAmount:quote.targetAmount};
    const preview=await post(route+'/trades/preview',body),key=crypto.randomUUID();
    form('Review currency conversion',`<div class="field full"><h3>${e(preview.quote.sourceAmount)} ${e(sourceCurrency)} → ${e(preview.quote.targetAmount)} ${e(targetCurrency)}</h3><p>Debit ${e(preview.source.accountNumber)}; credit ${e(preview.target.accountNumber)}.</p><p>Rate ${e(preview.quote.appliedRate)} · ${e(preview.quote.roundingMode)} · expires ${e(preview.quote.expiresAt)}</p><p>Reason: ${e(input.reason)}</p><p>Approval revalidates this exact rate and amount. Funds move only after another administrator approves.</p></div>`,()=>post(route+'/trades',body,key),'Submit for independent approval');return {keepOpen:true};
   },'Review conversion');return {keepOpen:true};
  },'Find accounts');
 }
 return {section,action};
}
