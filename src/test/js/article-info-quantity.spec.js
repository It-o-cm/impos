import { readFileSync } from 'node:fs';
import { describe, it, expect, vi } from 'vitest';

/** Runs the actual contextual keypad with the existing keyboard renderers. */
function keypad(withQuantity = true) {
  document.body.innerHTML = '<input id="codeHidden" value="3300000000001"><button id="codeDisplay" data-label="Code article">3300000000001</button><span id="keypadLabel"></span><div id="consultationKbArea"></div>'
    + (withQuantity ? '<input id="quantityHidden" value="1"><button id="quantityDisplay" data-label="Poids net (kg)">1</button>' : '');
  const source = readFileSync('src/main/resources/templates/article-info.html', 'utf8');
  const start = source.indexOf('    function setupConsultationPad()');
  const end = source.indexOf('    setupConsultationPad();', start);
  let press;
  const renderer = vi.fn((element, callback) => { press = callback; });
  const keyboards = { renderPriceNumpad: renderer, renderNumpad: renderer };
  new Function('document', 'PosKeyboards', source.slice(start, end) + '\nsetupConsultationPad();')(document, keyboards);
  return { press: key => press(key), renderer };
}

describe('INFO ARTICLE single contextual keypad', () => {
  it('keeps gram precision and visibly edits quantity without changing the barcode', () => {
    const { press } = keypad();
    for (const key of ['0', ',', '8', '7', '5', ',']) press(key);
    expect(document.getElementById('quantityHidden').value).toBe('0.875');
    expect(document.getElementById('quantityDisplay').textContent).toBe('0,875');
    expect(document.getElementById('codeHidden').value).toBe('3300000000001');
    expect(document.getElementById('quantityDisplay').classList.contains('active')).toBe(true);
    expect(document.getElementById('keypadLabel').textContent).toBe('Poids net (kg)');
  });

  it('switches to code entry and back without mixing either field', () => {
    const { press } = keypad(); press('2');
    document.getElementById('codeDisplay').click();
    for (const key of ['0', '0', '4', ',', '⌫', '2']) press(key);
    expect(document.getElementById('codeHidden').value).toBe('002');
    expect(document.getElementById('quantityHidden').value).toBe('2');
    expect(document.getElementById('keypadLabel').textContent).toBe('Code article');
    expect(document.getElementById('quantityDisplay').classList.contains('active')).toBe(false);
    document.getElementById('quantityDisplay').click(); press('3');
    expect(document.getElementById('quantityHidden').value).toBe('3');
    expect(document.getElementById('codeHidden').value).toBe('002');
  });

  it('bounds decimal entry, ignores unsupported keys and clears the active field', () => {
    const { press } = keypad();
    for (const key of ['x', 'OK', '-', '1','2','3','4','5','6','7','8','9','0',',','1']) press(key);
    expect(document.getElementById('quantityHidden').value).toBe('1234567890');
    press('C'); expect(document.getElementById('quantityHidden').value).toBe('');
    expect(document.getElementById('quantityDisplay').textContent).toBe('—');
  });

  it('uses only the code field when no editable quantity exists', () => {
    const { press, renderer } = keypad(false);
    press('4'); press('2');
    expect(document.getElementById('codeHidden').value).toBe('42');
    expect(document.getElementById('codeDisplay').classList.contains('active')).toBe(true);
    expect(renderer).toHaveBeenCalledOnce();
  });
});
