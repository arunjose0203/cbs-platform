import test from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml,bankActions,errorMessage} from '../src/model.js';
test('directory values cannot inject markup or attributes',()=> {
  assert.equal(escapeHtml('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
  assert.equal(escapeHtml("O'Bank & Co"),'O&#39;Bank &amp; Co');
});
test('provisioning does not offer unsafe lifecycle actions',()=> {
  assert.deepEqual(bankActions('PROVISIONING'),[]);
  assert.deepEqual(bankActions('PROVISIONING_FAILED'),['provision']);
  assert.deepEqual(bankActions('ACTIVE'),['suspend']);
  assert.deepEqual(bankActions('SUSPENDED'),['resume']);
});
test('stale writes and MFA failures have actionable messages',()=> {
  assert.match(errorMessage('STALE_VERSION'),/Refresh/);
  assert.match(errorMessage('MFA_REQUIRED'),/multifactor/);
});
