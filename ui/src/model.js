export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
export function bankActions(status) {
  if (['REQUESTED','PROVISIONING_FAILED'].includes(status)) return ['provision'];
  if (status === 'ACTIVE') return ['suspend'];
  if (status === 'SUSPENDED') return ['resume'];
  return [];
}
export function errorMessage(code) {
  return ({STALE_VERSION:'This record changed. Refresh it before saving again.',
    MFA_REQUIRED:'Sign in again and complete multifactor authentication.',
    STAFF_NOT_REGISTERED:'Your identity is not registered in CBS. Contact a platform administrator.',
    STAFF_DISABLED:'Your CBS access is disabled. Contact a platform administrator.',
    SESSION_REVOKED:'This session has been revoked. Please sign in again.',
    SESSION_EXPIRED:'Your session expired. Please sign in again.',
    BANK_CODE_IN_USE:'That bank code is already in use.',
    STAFF_ALREADY_EXISTS:'This identity is already registered.',
    DATABASE_REFERENCE_NOT_APPROVED:'Choose an operator-approved database reference.',
    DATABASE_REFERENCE_IN_USE:'That database reference belongs to another bank.',
    INVALID_REQUEST:'Check the required fields and try again.',
    INVALID_LEGAL_ENTITY:'Check the country, currency and business timezone.',
    ACCESS_DENIED:'Your role does not permit this action.',
    SELF_DEACTIVATION_FORBIDDEN:'Another administrator must deactivate your identity.'})[code]
    || `The request could not be completed${code ? ` (${code})` : ''}.`;
}
