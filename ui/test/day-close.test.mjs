import test from 'node:test';
import assert from 'node:assert/strict';
import {createDayCloseUi} from '../src/day-close-ui.js';
test('close readiness blocks submission and escapes history fields',async()=>{
  const host={innerHTML:''},forms=[];
  const ui=createDayCloseUi({form:(...args)=>forms.push(args),api:async path=>path==='/admin/tenants?limit=100'?{items:[{id:'b',displayName:'<img>',status:'ACTIVE',legalEntities:[]}]}:{version:1,businessDate:'2026-10-05',nextBusinessDate:'2026-10-06',storageReady:true,blockers:['DAY_CLOSE_IN_PROGRESS'],history:[{requestId:'req',businessDate:'2026-10-05',nextBusinessDate:'2026-10-06',status:'CLOSING',checker:'<script>',totals:[]}]}});
  await ui.render(host,()=>true);assert.match(host.innerHTML,/Retry close/);assert.match(host.innerHTML,/data-action="day-close"[^>]*disabled/);assert.ok(host.innerHTML.includes('&lt;script&gt;'));assert.ok(!host.innerHTML.includes('<img>'));
  await ui.action('close');assert.equal(forms.length,0);
});
