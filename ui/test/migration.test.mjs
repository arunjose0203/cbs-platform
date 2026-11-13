import test from 'node:test';
import assert from 'node:assert/strict';
import {createMigrationUi} from '../src/migration-ui.js';

function fixture() {
  let submit, html, fields = [], calls = [];
  const bank = {id: 'bank', displayName: 'Demo Bank', status: 'ACTIVE'};
  const batches = [
    {
      id: 'batch-1',
      tenantId: 'bank',
      batchType: 'CUSTOMERS',
      status: 'STAGED',
      totalRecords: 100,
      validRecords: 98,
      errorRecords: 2,
      createdAt: '2026-10-08T10:00:00Z',
      committedAt: null
    }
  ];

  const ui = createMigrationUi({
    api: async (path, options) => {
      calls.push({path, ...options});
      if (options) {
        if (path.endsWith('/reconcile')) {
          return {status: 'MATCHED', legacyTotal: '1000000.00', platformTotal: '1000000.00', difference: '0.00'};
        }
        return {};
      }
      if (path.includes('/batches')) return batches;
      if (path.includes('?')) return {items: [bank]};
      return bank;
    },
    form: (_t, h, fn) => { html = h; submit = fn; },
    field: (name, label, value) => { fields.push({name, value}); return label; },
    refresh: async () => {}
  });

  return {ui, calls, fields, get html() { return html; }, submit: v => submit(v)};
}

test('migration UI renders batches and allows batch rollback and reconciliation', async () => {
  const f = fixture();
  const host = {innerHTML: ''};
  await f.ui.render(host, () => true);

  assert.match(host.innerHTML, /Demo Bank/);
  assert.match(host.innerHTML, /CUSTOMERS/);
  assert.match(host.innerHTML, /STAGED/);
  assert.match(host.innerHTML, /Rollback batch/);

  await f.ui.action('rollback', 'batch-1');
  await f.submit({});
  const rollbackCall = f.calls.at(-1);
  assert.equal(rollbackCall.path, '/admin/tenants/bank/migration/batches/batch-1/rollback');

  await f.ui.action('reconcile');
  assert.equal(f.fields.find(x => x.name === 'currency').value, 'KES');
  await f.submit({currency: 'KES', amount: '1000000.00'});
  const reconCall = f.calls.find(c => c.path.endsWith('/reconcile'));
  assert.ok(reconCall);
  assert.match(f.html, /MATCHED/);
});
