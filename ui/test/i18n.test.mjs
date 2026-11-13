import test from 'node:test';
import assert from 'node:assert/strict';
import {setLanguage, getLanguage, t, displayValue, translations} from '../src/i18n.js';

test('settings labels switch languages and unsupported text falls back safely', () => {
  setLanguage('fr');
  assert.equal(t('Bank settings'), 'Paramètres bancaires');
  setLanguage('sw');
  assert.equal(t('Business calendar'), 'Kalenda ya benki');
  assert.equal(t('Unknown server message'), 'Unknown server message');
  setLanguage('unsupported');
  assert.equal(t('Bank settings'), 'Bank settings');
  setLanguage('en');
  assert.equal(getLanguage(), 'en');
});

test('structured approval values remain readable instead of object placeholders', () => {
  assert.equal(displayValue([{code: 'KES', enabled: true}]), '{"code":"KES","enabled":true}');
  assert.equal(displayValue([6, 7]), '6\n7');
  assert.equal(displayValue(null), '—');
});

test('complete key parity exists between French and Kiswahili translations without gaps', () => {
  const frKeys = Object.keys(translations.fr);
  const swKeys = Object.keys(translations.sw);
  assert.ok(frKeys.length >= 700, `Expected at least 700 translated strings, got ${frKeys.length}`);
  assert.equal(frKeys.length, swKeys.length, 'French and Swahili must have identical translation key count');

  for (const key of frKeys) {
    assert.ok(key in translations.sw, `Missing Swahili translation for key: "${key}"`);
    assert.ok(translations.fr[key] && translations.fr[key].trim().length > 0, `Empty French translation for key: "${key}"`);
    assert.ok(translations.sw[key] && translations.sw[key].trim().length > 0, `Empty Swahili translation for key: "${key}"`);
  }
});

test('all application navigation labels have non-empty translations in English, French and Kiswahili', () => {
  const navigationLabels = [
    'Privacy & data protection',
    'Bank duties & access review',
    'Lending permissions',
    'Lending approval inbox',
    'Loan arrears & collections',
    'Loan applications & credit review',
    'Term deposits',
    'Lending',
    'FX & exchange rates',
    'Payment exceptions',
    'M-Pesa statements',
    'Payments & reconciliation',
    'Compliance cases',
    'Deposit servicing',
    'Customer alerts',
    'Management reports',
    'Screening & monitoring',
    'Branch workspace',
    'Bank directory',
    'Staff & access',
    'Approval Inbox',
    'Bank settings',
    'Chart of Accounts',
    'Ledger Accounts',
    'Journals & balances',
    'Accounting reports',
    'Business Day Close',
    'Product Factory',
    'Deposit accounts',
    'Customers',
    'My sessions',
    'Audit trail'
  ];

  for (const label of navigationLabels) {
    setLanguage('en');
    assert.equal(t(label), label);

    setLanguage('fr');
    const fr = t(label);
    assert.notEqual(fr, label, `Navigation label "${label}" not translated to French`);
    assert.ok(fr.length > 0);

    setLanguage('sw');
    const sw = t(label);
    assert.notEqual(sw, label, `Navigation label "${label}" not translated to Swahili`);
    assert.ok(sw.length > 0);
  }
  setLanguage('en');
});

test('critical banking and operational journeys have valid Swahili and French translations', () => {
  const operationalTerms = [
    {en: 'Teller & cash custody', fr: 'Caisse et garde des espèces', sw: 'Ulinzi wa fedha taslimu na keshia'},
    {en: 'Branch operations', fr: 'Opérations d’agence', sw: 'Shughuli za tawi'},
    {en: 'Subject requests', fr: 'Demandes des personnes', sw: 'Maombi ya mhusika wa data'},
    {en: 'Legal holds', fr: 'Suspensions légales', sw: 'Vizuizi vya kisheria'},
    {en: 'Retention matrix', fr: 'Grille de conservation', sw: 'Jedwali la uhifadhi wa kumbukumbu'},
    {en: 'Shortage / overage', fr: 'Déficit / Excédent', sw: 'Upungufu / Ziada'},
    {en: 'Days past due', fr: 'Jours de retard', sw: 'Siku za kuchelewa'},
    {en: 'Promise to pay', fr: 'Promesse de paiement', sw: 'Ahadi ya kulipa'},
    {en: 'Payment orders', fr: 'Ordres de paiement', sw: 'Maagizo ya malipo'}
  ];

  for (const item of operationalTerms) {
    setLanguage('fr');
    assert.equal(t(item.en), item.fr);
    setLanguage('sw');
    assert.equal(t(item.en), item.sw);
  }
  setLanguage('en');
});

test('error messages for key statutory and governance flows translate to Kiswahili and French', () => {
  const errorCases = [
    {
      code: 'This account is dormant. Reactivate the account with approved KYC review before transacting.',
      fr: 'Ce compte est dormant. Réactivez le compte avec un examen KYC approuvé avant toute opération.',
      sw: 'Akaunti hii imelala. Amsha akaunti tena kwa ukaguzi wa KYC ulioidhinishwa kabla ya kufanya muamala.'
    },
    {
      code: 'The customer is recorded as deceased. Ordinary account operations are blocked.',
      fr: 'Le client est enregistré comme décédé. Les opérations courantes sont bloquées.',
      sw: 'Mteja amerekodiwa kuwa amefariki. Shughuli za kawaida za akaunti zimezuiwa.'
    },
    {
      code: 'This bank enforces duty segregation. Ask an access administrator to assign you the duty for this work.',
      fr: 'Cette banque applique la séparation des tâches. Demandez à un administrateur d’accès de vous assigner la fonction requise.',
      sw: 'Benki hii inatekeleza mgawanyo wa wajibu. Mwombe msimamizi wa ufikiaji akupe wajibu wa kazi hii.'
    }
  ];

  for (const item of errorCases) {
    setLanguage('fr');
    assert.equal(t(item.code), item.fr);
    setLanguage('sw');
    assert.equal(t(item.code), item.sw);
  }
  setLanguage('en');
});
