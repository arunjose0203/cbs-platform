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
  return ({"FUNDING_STORAGE_NOT_READY":"Prepare funding storage for this bank first.","FUNDING_ACCOUNT_NOT_READY":"Funding requires an applied account that is awaiting funding or active.","FUNDING_UNRECONCILED":"Customer and ledger balances differ. Investigate before posting.","FUNDING_SOURCE_INVALID":"Select an approved debit-normal asset ledger for the received funds.","FUNDING_LIABILITY_INVALID":"Select a liability ledger matching the account’s pinned product chart mapping.","FUNDING_LIABILITY_IMMUTABLE":"This account must use its existing dedicated liability ledger.","FUNDING_OPENING_MINIMUM":"The first deposit must meet the account’s agreed minimum opening amount.","FUNDING_LEDGER_USED":"The first deposit needs an unused liability ledger with no postings, pending journals or other customer binding.","FUNDING_DATABASE_UNAVAILABLE":"Funding could not be confirmed. Retry safely after the bank database is available.","FUNDING_BALANCE_NOT_ZERO":"A funded account cannot be closed or returned to awaiting funding here.","FUNDING_REQUIRED":"An account becomes active only after its first approved deposit posts.","FUNDING_MANAGED":"Use Balance and funding for customer ledger transactions. Manual edits and reversals are blocked.","ACCOUNT_NOT_FOUND":"This deposit account was not found in the selected bank.","ACCOUNT_CUSTOMER_NOT_READY":"Choose an active customer with delivered approval and an unexpired identity review.","ACCOUNT_PRODUCT_NOT_READY":"The product changed or is inactive. Refresh and choose an approved active product.","ACCOUNT_INITIAL_STATE":"New accounts must start awaiting funding.","ACCOUNT_ALREADY_CLOSED":"Closed accounts cannot be changed or reopened.","ACCOUNT_STRUCTURE_IMMUTABLE":"Customer, legal entity and agreed product revision cannot be changed.","ACCOUNT_DELIVERY_PENDING":"Complete the approved account delivery before submitting another change.","ACCOUNT_STORAGE_NOT_READY":"Prepare account storage for this bank first.","ACCOUNT_BANK_NOT_ACTIVE":"This bank must be active to manage deposit accounts.","ACCOUNT_INVALID_ENTITY":"Choose a legal entity belonging to this bank.","ACCOUNT_RECONCILIATION_REQUIRED":"The account copies do not match. Ask an administrator to investigate.","ACCOUNT_DATABASE_UNAVAILABLE":"The bank database could not confirm account delivery. Retry safely.","CURRENCY_USED_BY_ACCOUNT":"An unclosed deposit account uses this currency.","CHART_USED_BY_ACCOUNT":"An unclosed deposit account uses this chart mapping.",CUSTOMER_NOT_FOUND:'This customer does not belong to the selected bank.',CUSTOMER_BANK_NOT_ACTIVE:'The bank must be active to onboard or change customers.',CUSTOMER_STORAGE_NOT_READY:'Prepare customer storage for this bank first.',CUSTOMER_DATABASE_UNAVAILABLE:'Customer delivery could not be confirmed. Retry safely.',CUSTOMER_RECONCILIATION_REQUIRED:'The bank customer record differs from its approved definition. Ask an operator to investigate.',CUSTOMER_INVALID_PROFILE:'Check the date of birth, country codes, identity number and expiry date.',CUSTOMER_REVIEW_REQUIRED:'Activation requires a manual review reference and an unexpired identity document.',CUSTOMER_DOCUMENT_DUPLICATE:'This identity document already belongs to a customer in this bank. Use the existing customer record.',CUSTOMER_DUPLICATE_REVIEW_REQUIRED:'A customer with the same name and date of birth already exists. Investigate it, then provide a duplicate review explanation if these are different people.',CUSTOMER_DELIVERY_PENDING:'Complete the approved customer delivery before submitting another change.',PRODUCT_NOT_FOUND:'This product does not belong to the selected bank.',PRODUCT_BANK_NOT_ACTIVE:'The bank must be active to configure products.',PRODUCT_CODE_IN_USE:'This product code already exists in this bank.',PRODUCT_STRUCTURE_IMMUTABLE:'Code, type, currency and liability mapping are fixed after approval.',PRODUCT_CURRENCY_DISABLED:'Enable the product currency in Bank settings first.',PRODUCT_INVALID_MAPPING:'Choose an active liability posting account with a credit normal balance from this bank.',PRODUCT_INVALID_AMOUNT:'Use nonnegative amounts with up to 18 whole digits and the currency’s decimal precision.',PRODUCT_MINIMUM_ORDER:'Minimum opening must be at least the minimum balance.',CHART_USED_BY_PRODUCT:'An active product uses this chart account. Deactivate the product through approval first.',CURRENCY_USED_BY_PRODUCT:'An active product uses this currency. Deactivate the product through approval first.',DAY_CLOSE_IN_PROGRESS:'An approved day close is in progress. Complete or retry it before changing accounting data.',DAY_CLOSE_PENDING_APPROVALS:'Resolve pending journal, ledger or day-close approvals first.',DAY_CLOSE_PENDING_DELIVERIES:'Complete pending journal and ledger deliveries first.',DAY_CLOSE_UNRECONCILED:'Ledger balances do not match journal totals. Investigate reconciliation differences before closing.',DAY_CLOSE_STORAGE_NOT_READY:'Prepare closing storage for this bank first.',DAY_CLOSE_DATABASE_UNAVAILABLE:'The close could not be confirmed. Retry safely and check its status.',DAY_CLOSE_DATE_INVALID:'Refresh the page and use the current business date and next working day.',DAY_CLOSE_FUTURE_POSTINGS:'Later-dated postings exist. Ask an operator to investigate before closing.',BUSINESS_DAY_CLOSED:'This business day is already closed.',BUSINESS_DATE_REQUIRES_CLOSE:'Use Business Day Close to advance the date after ledgers have been created.',REPORT_FILTER_INVALID:'Choose a valid currency and date range (1900–9999), with a ledger for statements.',REPORT_TOO_LARGE:'This report exceeds 10,000 rows. Narrow the statement date range; larger account reports need an operator export.',REPORT_DATABASE_UNAVAILABLE:'The report database is unavailable. Please retry.',CASH_ADJUSTMENT_UNAVAILABLE:'Use an unresolved exception from this branch and business date without an approved adjustment already queued.',CASH_ADJUSTMENT_MISMATCH:'The adjustment must exactly match the reported shortage or overage and confirmed physical count.',CASH_ADJUSTMENT_LEDGER_INVALID:'Choose an approved expense ledger for a shortage or income ledger for an overage, in the same bank, legal entity and currency.',CASH_EXCEPTION_OPEN:'This cash location has an open discrepancy. Resolve it through a verified recount or posted approved adjustment before further cash operations or day close.',CASH_EXCEPTION_NOT_REQUIRED:'The count already matches the book. Use the normal cash count workflow.',CASH_EXCEPTION_NOT_FOUND:'This exception was not found in the selected branch.',CASH_EXCEPTION_RESOLVED:'This exception has already been resolved. Refresh the workspace.',CASH_STORAGE_NOT_READY:'Prepare bank storage before registering cash locations.',CASH_FIELDS_INVALID:'Check the fields required for this cash operation.',CASH_SCOPE_MISMATCH:'Use a cash location and customer account in the same branch, legal entity and currency. Customer cash transactions require a till.',CASH_LEDGER_USED:'Choose a dedicated unused asset ledger for a cash location; use an external asset ledger for settlement.',CASH_LOCATION_NOT_READY:'Wait for cash location approval and delivery.',CASH_MANAGED:'Use the cash workspace for this dedicated cash ledger.',CASH_DELIVERY_PENDING:'Complete the pending cash delivery before submitting another cash operation.',CASH_SESSION_REQUIRED:'Open and approve the till session for the current business date first.',CASH_SESSION_OPEN:'Close and approve all open till sessions before closing the business day.',CASH_CUSTODIAN_REQUIRED:'Only the staff member who opened this till may submit its cash movements and closing count.',CASH_COUNT_REQUIRED:'Approve a balanced physical count for every vault and till after its last movement before closing the day.',CASH_COUNT_INVALID:'Use unique positive denominations, whole quantities and the currency precision.',CASH_COUNT_MISMATCH:'The physical count differs from the recorded cash balance. Recount and investigate; no automatic adjustment has been posted.',CASH_INSUFFICIENT:'This location does not have enough recorded cash.',CASH_UNRECONCILED:'The cash record and general ledger differ. Investigate before proceeding.',CASH_DATABASE_UNAVAILABLE:'The cash posting could not be confirmed. Check delivery status; retries are safe.',BRANCH_LIMIT_REQUIRED:'Approve both branch and staff limits for this currency and operation first.',TRANSFER_BRANCH_MISMATCH:'Choose a destination account assigned to the same branch.',ACCOUNT_BRANCH_INVALID:'Choose an active branch in the account’s legal entity and wait for account delivery.',BRANCH_ACCESS_DENIED:'Your current bank or branch membership does not allow this request.',BRANCH_PERMISSION_DENIED:'Your branch role does not allow this action.',TRANSACTION_LIMIT_REQUIRED:'Approve a bank-wide limit for this currency and operation in Transaction limits before submitting this transaction.',TRANSACTION_LIMIT_EXCEEDED:'This amount exceeds the approved per-transaction limit.',DAILY_LIMIT_EXCEEDED:'This transaction would exceed the daily limit, including approved transactions awaiting posting.',LIMIT_INVALID:'Enter positive limits using the currency precision. Daily total must be at least the per-transaction limit.',STATEMENT_EXPORT_TOO_LARGE:'Select a shorter date range. A CSV export supports at most 10,000 entries.',JOURNAL_STORAGE_NOT_READY:'Prepare journal storage for this bank first.',
    JOURNAL_DATABASE_UNAVAILABLE:'Posting could not be confirmed. Retry safely and check the posting status.',
    JOURNAL_NOT_POSTED:'This journal has not posted in this bank.',
    JOURNAL_AMOUNT_INVALID:'Use positive amounts within the currency’s decimal precision.',
    JOURNAL_LINES_INVALID:'Use 2–100 lines and at least two distinct ledger accounts.',
    JOURNAL_UNBALANCED:'Total debits must equal total credits.',
    JOURNAL_BUSINESS_DATE_INVALID:'Use the bank’s current business date. Refresh and submit again.',
    JOURNAL_LEDGER_NOT_READY:'Every ledger must be active, fully delivered, and in the selected bank, legal entity and currency.',
    JOURNAL_REFERENCE_IN_USE:'An approved journal already uses this reference. Enter a unique reference.',
    JOURNAL_REVERSAL_INVALID:'Only original posted journals can be reversed, with all lines reversed exactly.',
    JOURNAL_ALREADY_REVERSED:'A reversal is already approved for this journal.',
    LEDGER_JOURNAL_PENDING:'A journal is awaiting posting to this ledger. Complete posting before changing it.',
    LEDGER_BALANCE_NOT_ZERO:'This ledger has a nonzero balance and cannot be deactivated.',
    STALE_VERSION:'This record changed. Refresh it before saving again.',
    LEDGER_BANK_NOT_ACTIVE:'The bank must be active to create, change or deliver ledger accounts.',
    LEDGER_STORAGE_NOT_READY:'Prepare ledger storage for this bank, then try again.',
    LEDGER_POSTING_REQUIRED:'Choose an active, approved posting definition from this bank’s Chart of Accounts.',
    LEDGER_CODE_IN_USE:'This ledger code is already reserved in this bank. Choose another code.',
    LEDGER_NOT_FOUND:'This ledger account does not belong to the selected bank.',
    LEDGER_STRUCTURE_IMMUTABLE:'Ledger code, chart definition, legal entity and currency cannot be changed.',
    LEDGER_DELIVERY_PENDING:'Wait for the approved change to be delivered before submitting another edit.',
    LEDGER_DATABASE_UNAVAILABLE:'The bank database could not apply the change. Delivery remains pending and can be retried safely.',
    LEDGER_RECONCILIATION_REQUIRED:'Bank ledger data differs from the approved record. Ask an operator to reconcile it before continuing.',
    CHART_USED_BY_LEDGER:'This definition is used by an active ledger or a pending delivery. Deactivate and deliver the ledger changes first.',
    CURRENCY_USED_BY_LEDGER:'A ledger already uses this currency. Keep it enabled.',
    LEGAL_ENTITY_NOT_IN_BANK:'Select a legal entity belonging to this bank.',
    CHART_BANK_NOT_ACTIVE:'Provision or resume this bank before changing its chart of accounts.',
    CHART_CODE_IN_USE:'This account code already exists in this bank. Choose another code.',
    CHART_ACCOUNT_NOT_FOUND:'The account or parent group is not available in this bank.',
    CHART_INVALID_PARENT:'Choose an active parent group in the same category.',
    CHART_ACTIVE_CHILDREN:'Deactivate the active child accounts before deactivating this group.',
    CHART_STRUCTURE_IMMUTABLE:'Approved account structure cannot be changed. Edit its name, description or status instead.',
    OPERATION_STORAGE_NOT_READY:'Prepare account storage before using account operations.',
    OPERATION_ACCOUNT_NOT_FUNDED:'This account needs a posted opening deposit first.',
    ACCOUNT_OPERATION_PENDING:'An approved account operation is still awaiting delivery. Retry it before submitting another change.',
    INSUFFICIENT_AVAILABLE_BALANCE:'The amount exceeds available funds after holds and the product minimum.',
    HOLD_NOT_ACTIVE:'This hold has already been released, or the amount does not match.',
    TRANSFER_SCOPE_MISMATCH:'Both accounts must belong to the same bank, legal entity and currency.',
    OPERATION_FIELDS_INVALID:'Select the required accounts and original reference for this operation.',
    FUNDING_REVERSAL_INVALID:'Select a posted funding receipt and reverse its full amount.',
    OPERATION_DATABASE_UNAVAILABLE:'Posting could not be confirmed. Retry the same approved operation safely.',
    MFA_REQUIRED:'Sign in again and complete multifactor authentication.',
    LOCAL_MFA_EXCEPTION_EXPIRED:'Temporary password-only sign-in has expired. Ask your local administrator to renew the development exception or restore MFA, then sign in again.',
    STAFF_NOT_REGISTERED:'Your identity is not registered in CBS. Contact a platform administrator.',
    STAFF_DISABLED:'Your CBS access is disabled. Contact a platform administrator.',
    SESSION_REVOKED:'This session has been revoked. Please sign in again.',
    SESSION_EXPIRED:'Your session expired. Please sign in again.',
    BANK_CODE_IN_USE:'That bank code is already in use.',
    INVALID_CURRENCY_SETTINGS:'Choose distinct currency codes and valid rounding rules from the catalogue.',
    FUNCTIONAL_CURRENCY_REQUIRED:'Every legal entity’s functional currency must remain enabled.',
    CURRENCY_NOT_ENABLED:'Enable this currency through Bank settings and have it approved first.',
    INVALID_LANGUAGE_SETTINGS:'Use distinct, correctly formatted language tags, such as en, en-KE, fr or sw.',
    DEFAULT_LANGUAGE_REQUIRED:'The default language must be included in supported languages.',
    INVALID_CALENDAR:'Check the timezone, weekend days and unique holiday dates (1900–2200). At least one weekday must remain a working day.',
    BUSINESS_DATE_BACKWARDS:'The business date cannot move backwards and must be between 1900 and 2200.',
    BUSINESS_DATE_NOT_WORKING:'Choose a business date that is neither a weekend nor a holiday.',
    INVALID_AMOUNT:'Enter a decimal amount with up to 24 integer digits and 12 fractional digits, without separators.',
    UI_LANGUAGE_NOT_SUPPORTED:'Choose English, French or Swahili for the interface.',
    SETTINGS_APPROVAL_REQUIRED:'Change the bank’s default language through Bank settings and the approval workflow.',
    SELF_APPROVAL_FORBIDDEN:'Another platform administrator must review your request.',
    ONLY_MAKER_CAN_CANCEL:'Only the person who submitted this request can cancel it.',
    APPROVAL_ALREADY_DECIDED:'This request has already been decided. Refresh to see its current status.',
    APPROVAL_NOT_FOUND:'This approval request is not available to your identity.',
    MAKER_DISABLED:'The submitter is no longer active. Reject this request and ask an active administrator to submit a new one.',
    IDEMPOTENCY_CONFLICT:'This submission was already attempted with different details. Reopen the form to submit a new request.',
    STAFF_ALREADY_EXISTS:'This identity is already registered.',
    DATABASE_REFERENCE_NOT_APPROVED:'This database reference is not configured. Ask an administrator to configure and approve a dedicated database for this bank, then use that exact reference.',
    DATABASE_REFERENCE_IN_USE:'That database reference belongs to another bank.',
    INVALID_REQUEST:'Check the required fields and try again.',
    INVALID_LEGAL_ENTITY:'Check the country, currency and business timezone.',
    ACCESS_DENIED:'Your role does not permit this action.',
    SELF_DEACTIVATION_FORBIDDEN:'Another administrator must deactivate your identity.'})[code]
    || `The request could not be completed${code ? ` (${code})` : ''}.`;
}
export function signInErrorMessage(error) {
  // Provider errors can contain callback details; display only mapped CBS errors.
  return error?.cbsProblem && error.code ? errorMessage(error.code) : 'Sign-in could not be completed. Please start sign-in again.';
}
