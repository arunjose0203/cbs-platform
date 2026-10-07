import test from 'node:test';
import assert from 'node:assert/strict';
import {reportTable} from '../src/report-ui.js';
test('accounting report escapes labels and values and keeps exact money strings',()=>{
  const html=reportTable({columns:['<img>','Amount'],rows:[['<script>','9007199254740993.17']]});
  assert.ok(!html.includes('<img>'));assert.ok(!html.includes('<script>'));assert.ok(html.includes('9007199254740993.17'));assert.ok(html.includes('&lt;script&gt;'));
});
