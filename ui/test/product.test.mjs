import test from 'node:test';
import assert from 'node:assert/strict';
import {createProductUi} from '../src/product-ui.js';
import {setLanguage,t} from '../src/i18n.js';

const product={id:'p',code:'SAV_KES',name:'<img src=x>',description:'Savings',productType:'SAVINGS',currency:'KES',minimumOpening:'9007199254740993.25',minimumBalance:'10',liabilityAccountId:'mapping',liabilityAccountCode:'2100',active:true,revision:1};
const banks=[{id:'a',displayName:'Bank A',status:'ACTIVE'},{id:'b',displayName:'Bank B',status:'ACTIVE'}];
test('products escape data, preserve exact decimals and switch bank routes',async()=>{
  const calls=[],host={innerHTML:''};setLanguage('en');
  const ui=createProductUi({api:async path=>{calls.push(path);return path==='/admin/tenants?limit=100'?{items:banks}:{version:2,total:1,items:[product]};}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/&lt;img src=x&gt;/);assert.ok(!host.innerHTML.includes('<img'));assert.match(host.innerHTML,/9007199254740993.25/);
  ui.selectBank('b');await ui.render(host,()=>true);assert.ok(calls.at(-1).startsWith('/admin/tenants/b/products?'));
});
test('product edits keep structure fixed and safely reuse submission keys',async()=>{
  let submit;const posts=[];const ui=createProductUi({field:()=>'',form:(title,fields,fn)=>{submit=fn;assert.match(fields,/fixed after approval/);},api:async(path,options)=>{
    if(options){posts.push({path,...options});return {};}
    return path==='/admin/tenants?limit=100'?{items:banks}:{version:2,total:1,items:[product]};
  }});
  await ui.render({innerHTML:''},()=>true);await ui.action('edit','p');
  const input={code:'TAMPER',currency:'USD',productType:'CURRENT',liabilityAccountId:'other',name:'Updated',description:'',minimumOpening:'9007199254740993.25',minimumBalance:'10',active:'true',reason:'Review'};
  await submit(input);await submit(input);assert.equal(posts[0].headers['Idempotency-Key'],posts[1].headers['Idempotency-Key']);
  assert.equal(posts[0].body.code,'SAV_KES');assert.equal(posts[0].body.currency,'KES');assert.equal(posts[0].body.liabilityAccountId,'mapping');assert.equal(posts[0].body.minimumOpening,input.minimumOpening);
  await submit({...input,minimumBalance:'11'});assert.notEqual(posts[1].headers['Idempotency-Key'],posts[2].headers['Idempotency-Key']);
});
test('inactive banks block editing and stale product views do not render',async()=>{
  const forms=[],host={innerHTML:'unchanged'};const ui=createProductUi({form:(...args)=>forms.push(args),api:async path=>path==='/admin/tenants?limit=100'?{items:[{...banks[0],status:'SUSPENDED'}]}:{version:2,total:1,items:[product]}});
  await ui.render(host,()=>false);assert.equal(host.innerHTML,'unchanged');await ui.render(host,()=>true);await ui.action('edit','p');assert.equal(forms.length,0);assert.match(host.innerHTML,/must be active/);
  setLanguage('fr');assert.equal(t('Product Factory'),'Catalogue de produits');setLanguage('sw');assert.equal(t('Product Factory'),'Katalogi ya bidhaa');setLanguage('en');
});
