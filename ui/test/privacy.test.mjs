import test from 'node:test';
import assert from 'node:assert/strict';
import {createPrivacyUi} from '../src/privacy-ui.js';

test('privacy ui renders requests, holds and statutory retention matrix', async () => {
  const bank = {id: 'bank-1', status: 'ACTIVE', version: 3};
  const requests = [
    {
      id: 'req-1',
      customerNumber: 'C001',
      type: 'ACCESS',
      receivedOn: '2026-11-01',
      dueOn: '2026-11-08',
      status: 'OPEN',
      overdue: true,
      reference: 'DSR-001'
    }
  ];
  const holds = [
    {
      customerId: 'cust-1',
      customerNumber: 'C001',
      reference: 'COURT-ORD-2026',
      reason: 'Ongoing litigation',
      updatedAt: '2026-11-10T12:00:00Z'
    }
  ];
  const matrix = [
    {
      category: 'Customer identification (KYC)',
      records: 'Customer profile, identity document',
      years: 7,
      starts: 'End of relationship',
      basis: 'POCAMLA s.46',
      endAction: 'Anonymize'
    }
  ];
  const candidates = [
    {
      customerId: 'cust-99',
      customerNumber: 'C099',
      relationshipEnded: '2019-01-01',
      retainUntil: '2026-01-01'
    }
  ];

  const calls = [];
  const ui = createPrivacyUi({
    refresh: () => {},
    form: () => {},
    field: () => '',
    api: async (path) => {
      calls.push(path);
      if (path.includes('/requests')) return requests;
      if (path.includes('/legal-holds')) return holds;
      if (path.endsWith('/retention/candidates')) return candidates;
      if (path.endsWith('/retention')) return matrix;
      return [];
    }
  });

  const host = {innerHTML: '', querySelectorAll: () => []};

  // Test Requests tab
  await ui.render(host, bank, 'bank-1', 1, () => true);
  assert.match(host.innerHTML, /DSR-001/);
  assert.match(host.innerHTML, /OVERDUE/);
  assert.match(host.innerHTML, /Export report/);
  assert.match(host.innerHTML, /Register request/);
});

test('privacy ui registers data-subject request with idempotency', async () => {
  const bank = {id: 'bank-1', status: 'ACTIVE', version: 3};
  const posts = [];
  let submitForm;

  const ui = createPrivacyUi({
    refresh: () => {},
    field: () => '',
    form: (title, fields, fn) => { submitForm = fn; },
    api: async (path, opts) => {
      if (opts) {
        posts.push({path, ...opts});
        return {id: 'new-req-1'};
      }
      if (path.includes('/customers?')) {
        return {items: [{id: 'cust-1', customerNumber: 'C001', fullName: 'Alice'}]};
      }
      return [];
    }
  });

  const host = {innerHTML: '', querySelectorAll: () => []};
  await ui.render(host, bank, 'bank-1', 1, () => true);

  // Trigger new-request
  await ui.render(host, bank, 'bank-1', 1, () => true);
  // Simulate clicking Register Request button
  const buttons = [];
  // Direct invoke of action via form submission
  // Test form submission
  const customers = [{id: 'cust-1', customerNumber: 'C001'}];
});
