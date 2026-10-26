import { describe, it, expect, vi } from 'vitest';
import { loadScript, loadTemplateScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/ticket-criteria.js';

/** LC-04-05-01: wire the real keypad and fresh criteria DOM, with no physical keyboard. */
async function screen() {
  document.body.innerHTML = '<div id="simpleDisplay"></div><input id="simpleHidden"><div id="simpleKeyboard"></div>'
    + '<details id="ticketCriteria"><form id="criteriaForm">'
    + ['number', 'loyaltyCard', 'articleCode', 'date', 'amount', 'terminalId'].map(name =>
      `<button type="button" class="criteria-field" data-field="${name}" data-mode="${name === 'amount' ? 'cents' : 'text'}" data-keyboard="${name === 'date' ? 'ticketSearch' : name === 'articleCode' || name === 'amount' ? 'numpad' : 'searchAlpha'}"><strong id="criteria-${name}-display"></strong></button><input id="criteria-${name}" name="${name}">`).join('')
    + '<select id="criteria-paymentMethod"><option value=""></option><option value="CARD">CARD</option></select>'
    + '<div id="criteriaKeyboard"></div><button type="button" id="criteriaClose"></button><button type="button" id="criteriaReset"></button></form></details>';
  loadTemplateScript('src/main/resources/templates/keyboard-lib.html');
  window.PosInput.setup({ displayId: 'simpleDisplay', hiddenId: 'simpleHidden', containerId: 'simpleKeyboard', keyboardType: 'ticketSearch', mode: 'text' });
  vi.resetModules();
  const api = await loadScript(SOURCE);
  return api;
}

/** Opens the advanced controls using the browser's ordinary toggle event. */
function open() {
  const details = document.getElementById('ticketCriteria');
  details.open = true;
  details.dispatchEvent(new Event('toggle'));
}

/** Taps an actual generated key, preserving the distinction between letter C and CLEAR. */
function key(label) {
  const button = [...document.querySelectorAll('#criteriaKeyboard button')].find(candidate => (candidate.innerText || candidate.textContent) === label);
  expect(button, `missing touch key ${label}`).toBeDefined();
  button.click();
}

describe('LC-04-05-01 cumulative touch criteria', () => {
  it('ignores screens without advanced controls', async () => {
    document.body.innerHTML = '';
    vi.resetModules();
    const api = await loadScript(SOURCE);
    expect(() => api.boot()).not.toThrow();
    expect(document.getElementById('ticketCriteria')).toBeNull();
  });

  it('keeps independent identifiers, punctuation, date and cents in the posted fields', async () => {
    await screen();
    open();
    ['C', '0', '4', '-', '4', '2', '/'].forEach(key);
    expect(document.getElementById('criteria-number').value).toBe('c04-42/');
    document.querySelector('[data-field="date"]').click();
    ['2', '9', '/', '0', '2', '/', '2', '0', '2', '4'].forEach(key);
    expect(document.getElementById('criteria-date').value).toBe('29/02/2024');
    document.querySelector('[data-field="amount"]').click();
    ['1', '2', '5', '0'].forEach(key);
    expect(document.getElementById('criteria-amount').value).toBe('12.50');
    expect(document.getElementById('criteria-amount-display').textContent).toBe('12,50');
    expect(document.getElementById('criteria-number').value).toBe('c04-42/');
    document.querySelector('[data-field="articleCode"]').click();
    [...'12345670'].forEach(key);
    expect(document.getElementById('criteria-articleCode').value).toBe('12345670');
  });

  it('restores the original search pad and its in-progress input on close', async () => {
    const api = await screen();
    api.toggle(); // A closed, never-opened dialog has nothing to restore.
    window.PosInput.handleKey('4');
    open();
    key('1');
    document.getElementById('criteriaClose').click();
    document.getElementById('ticketCriteria').dispatchEvent(new Event('toggle'));
    window.PosInput.handleKey('2');
    expect(document.getElementById('simpleHidden').value).toBe('42');
    expect(document.getElementById('criteria-number').value).toBe('1');
  });

  it('clears every posted filter and selected tender, then keeps the active field editable', async () => {
    await screen();
    open();
    key('9');
    document.querySelector('[data-field="amount"]').click();
    key('5');
    document.getElementById('criteria-paymentMethod').value = 'CARD';
    document.getElementById('criteriaReset').click();
    document.querySelectorAll('#criteriaForm input').forEach(input => expect(input.value).toBe(''));
    expect(document.getElementById('criteria-paymentMethod').value).toBe('');
    key('7');
    expect(document.getElementById('criteria-amount').value).toBe('0.07');
  });
});
