import test from 'node:test';
import assert from 'node:assert/strict';
import {escapeHtml,bankActions,errorMessage,signInErrorMessage} from '../src/model.js';
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
test('sign-in preserves the CBS failure reason without exposing raw provider errors',()=>{
  assert.match(signInErrorMessage({code:'LOCAL_MFA_EXCEPTION_EXPIRED',cbsProblem:true}),/password-only sign-in has expired/);
  assert.match(signInErrorMessage({code:'STAFF_DISABLED',cbsProblem:true}),/access is disabled/);
  assert.equal(signInErrorMessage(Object.assign(new Error('private callback details'),{code:'private-provider-code'})), 'Sign-in could not be completed. Please start sign-in again.');
});

test('missing bank limits explain the required approval',()=>{assert.match(errorMessage('TRANSACTION_LIMIT_REQUIRED'),/Approve a bank-wide limit/);});
