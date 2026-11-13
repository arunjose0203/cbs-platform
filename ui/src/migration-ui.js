import {escapeHtml as e} from './model.js';

export function createMigrationUi({api, form, field, refresh}) {
  let bankId = '', banks = [], batches = [], current = () => false;
  const base = () => '/admin/tenants/' + bankId + '/migration';
  const button = (label, action, id = '', disabled = false) => `<button class="button secondary" type="button" data-action="migration-${action}" data-id="${e(id)}" ${disabled ? 'disabled' : ''}>${e(label)}</button>`;
  const post = (path, body) => api(path, {method: 'POST', body});

  async function render(host, isCurrent) {
    current = isCurrent;
    banks = (await api('/admin/tenants?limit=100')).items;
    if (!isCurrent()) return;
    if (!bankId) bankId = banks.find(x => x.status === 'ACTIVE')?.id || banks[0]?.id;
    if (!bankId) {
      host.innerHTML = '<h1>Data migration & cutover</h1><p>Register a bank first.</p>';
      return;
    }
    const bank = await api('/admin/tenants/' + bankId);
    batches = await api(base() + '/batches');
    if (!isCurrent()) return;

    host.innerHTML = `
      <div class="heading">
        <div>
          <p class="eyebrow">${e(bank.displayName)}</p>
          <h1>Data migration & cutover</h1>
          <p>Stage, validate, and commit legacy data with zero trial balance variance.</p>
        </div>
        <div class="actions">
          ${button('Choose bank', 'bank')}
          ${button('Refresh', 'refresh')}
          ${button('Reconcile trial balance', 'reconcile')}
        </div>
      </div>
      <section class="card">
        <h2>Production cutover controls</h2>
        <p>Pre-flight validation checks legacy records for duplicate national IDs, invalid account terms, and currency mismatches. Opening balance journals post atomically against the migration clearing suspense ledger.</p>
      </section>
      <section class="card section-gap">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Batch ID / type</th>
                <th>Status</th>
                <th>Records (valid / error)</th>
                <th>Created / committed</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              ${batches.map(b => `
                <tr>
                  <td><strong>${e(b.batchType)}</strong><small>${e(b.id)}</small></td>
                  <td><span class="status ${b.status === 'COMMITTED' ? 'active' : b.status === 'ROLLED_BACK' ? 'rejected' : 'pending'}">${e(b.status)}</span></td>
                  <td>${e(b.validRecords)} / ${e(b.errorRecords)} (Total: ${e(b.totalRecords)})</td>
                  <td>${e(b.createdAt?.slice(0, 19).replace('T', ' ') || '—')}<small>${b.committedAt ? 'Committed ' + e(b.committedAt.slice(0, 19).replace('T', ' ')) : 'Uncommitted'}</small></td>
                  <td>${b.status === 'STAGED' ? button('Rollback batch', 'rollback', b.id) : ''}</td>
                </tr>
              `).join('') || '<tr><td colspan="5" class="empty">No migration batches recorded.</td></tr>'}
            </tbody>
          </table>
        </div>
      </section>
    `;
  }

  async function action(name, id) {
    if (!current()) return;
    const route = base();
    if (name === 'refresh') { await refresh(); return; }
    if (name === 'bank') {
      form('Choose bank', field('bank', 'Bank', bankId, {choices: banks.map(x => ({value: x.id, label: x.displayName}))}), async v => {
        bankId = v.bank;
        await refresh();
      }, 'Open');
      return;
    }
    if (name === 'rollback') {
      form('Rollback migration batch', `<p class="field full">Are you sure you want to rollback batch ${e(id)}? Uncommitted records will be marked invalid.</p>`, async () => {
        await post(route + '/batches/' + id + '/rollback', {});
        await refresh();
      }, 'Confirm rollback');
      return;
    }
    if (name === 'reconcile') {
      form('Trial balance reconciliation', `<p class="field full">Enter legacy closing trial balance totals to verify against CBS migrated subledgers.</p>` +
        field('currency', 'Currency', 'KES', {max: 3}) +
        field('amount', 'Legacy total balance', '', {max: 25}), async v => {
        const payload = {};
        payload[v.currency] = v.amount;
        const res = await post(route + '/reconcile', payload);
        form('Reconciliation outcome', `<div class="field full"><h3>Status: ${e(res.status)}</h3><p>Legacy: ${e(res.legacyTotal)} · Platform: ${e(res.platformTotal)}</p><p>Difference: ${e(res.difference)}</p></div>`, async () => {}, 'Close');
        return {keepOpen: true};
      }, 'Run reconciliation');
    }
  }

  return {render, action};
}
