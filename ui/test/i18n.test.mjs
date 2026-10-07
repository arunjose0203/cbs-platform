import test from 'node:test';
import assert from 'node:assert/strict';
import {setLanguage,t,displayValue} from '../src/i18n.js';
test('settings labels switch languages and unsupported text falls back safely',()=>{
  setLanguage('fr');assert.equal(t('Bank settings'),'Paramètres bancaires');
  setLanguage('sw');assert.equal(t('Business calendar'),'Kalenda ya benki');
  assert.equal(t('Unknown server message'),'Unknown server message');
  setLanguage('unsupported');assert.equal(t('Bank settings'),'Bank settings');
});
test('structured approval values remain readable instead of object placeholders',()=>{
  assert.equal(displayValue([{code:'KES',enabled:true}]),'{"code":"KES","enabled":true}');
  assert.equal(displayValue([6,7]),'6\n7');
});
