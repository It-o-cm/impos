import { readFileSync } from 'node:fs';
import { describe, it, expect, vi } from 'vitest';

/** Loads the real selection function with the server-rendered cancellation mode. */
function selection(cancelMode, prefill) {
  const source = readFileSync('src/main/resources/templates/manual.html', 'utf8');
  const start = source.indexOf('    function selectProduct(');
  const end = source.indexOf('    function submitForm(', start);
  return new Function('cancelMode', 'document', 'PosPrefill', source.slice(start, end) + '\nreturn selectProduct;')(
    cancelMode, document, prefill);
}

describe('LC-02-12-05 group cancellation selection', () => {
  it('posts only the selected EAN, without consuming a prefilled sale quantity', () => {
    document.body.innerHTML = '<form id="formKnown" action="/action/cancel-group-article"><input id="inputEan"><input id="inputQuantity" value="999"></form>';
    const submit = vi.fn();
    document.getElementById('formKnown').submit = submit;
    const prefill = { isEmpty: vi.fn(), consume: vi.fn() };
    selection(true, prefill)(null, 'Milk', '0012345678905');
    expect(submit).toHaveBeenCalledOnce();
    expect(document.getElementById('inputEan').value).toBe('0012345678905');
    expect(document.getElementById('inputQuantity').value).toBe('999');
    expect(document.getElementById('formKnown').getAttribute('action')).toBe('/action/cancel-group-article');
    expect(prefill.isEmpty).not.toHaveBeenCalled();
    expect(prefill.consume).not.toHaveBeenCalled();
  });

  it('keeps normal sale prefill behavior outside the cancellation view', () => {
    document.body.innerHTML = '<form id="formKnown" action="/action/manual-add-known"><input id="inputEan"><input id="inputQuantity"></form>';
    const submit = vi.fn();
    document.getElementById('formKnown').submit = submit;
    const prefill = { isEmpty: () => false, consume: () => '2' };
    selection(false, prefill)(null, 'Milk', '300');
    expect(submit).toHaveBeenCalledOnce();
    expect(document.getElementById('inputQuantity').value).toBe('2');
    expect(document.getElementById('formKnown').getAttribute('action')).toBe('/action/manual-add-known');
  });
});
