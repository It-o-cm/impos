import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { loadTemplateScript } from './harness.js';

/** Executes both the actual shared keyboard and the actual NIF screen initialization. */
function open(value) {
    document.body.innerHTML = '<form id="taxIdForm"><div id="taxIdDisplay"></div><input id="taxIdHidden"></form><div id="taxIdKeyboard"></div>';
    document.getElementById('taxIdHidden').value = value;
    loadTemplateScript('src/main/resources/templates/keyboard-lib.html');
    loadTemplateScript('src/main/resources/templates/sale-tax-id.html');
}

/** Each case starts with a fresh page, actual keyboard state, a stopped clock and no network. */
beforeEach(() => {
    document.body.innerHTML = '';
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ json: () => ({ scans: Number.NaN, value: '' }) }));
});

/** Gives the real clock and network back. */
afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

describe('sale NIF tactile input LC-08-06-02/-03', () => {
    it('shows the anonymous default and submits it unchanged if accepted', () => {
        open('999999990');
        expect(document.getElementById('taxIdDisplay').textContent).toBe('999999990');
        expect(document.getElementById('taxIdHidden').value).toBe('999999990');
    });
    it('replaces the offered value on the first actual numeric key press', () => {
        open('999999990');
        [...document.querySelectorAll('button')].find(button => button.innerText === '1').click();
        expect(document.getElementById('taxIdHidden').value).toBe('1');
    });
    it('clears a previous choice and permits a fresh choice in the mask', () => {
        open('123456789');
        window.PosInput.handleKey('C');
        expect(document.getElementById('taxIdHidden').value).toBe('');
        window.PosInput.handleKey('F'); window.PosInput.handleKey('R'); window.PosInput.handleKey('1');
        expect(document.getElementById('taxIdHidden').value).toBe('FR1');
    });
});

describe('LC-08-06-05 scanned NIF on the open screen', () => {
    it('shows a NIF scanned while the screen is open, as a proposal', async () => {
        open('999999990');
        fetch.mockResolvedValue({ json: () => ({ scans: 1, value: 'PT123456789' }) });
        await vi.advanceTimersByTimeAsync(1000);
        expect(fetch).toHaveBeenCalledWith('/sale-tax-id/scanned');
        expect(document.getElementById('taxIdHidden').value).toBe('PT123456789');
        expect(document.getElementById('taxIdDisplay').textContent).toBe('PT123456789');
        window.PosInput.handleKey('1');
        expect(document.getElementById('taxIdHidden').value).toBe('1');
    });
    it('keeps what the cashier typed while no new scan arrives, and survives a failed poll', async () => {
        open('999999990');
        fetch.mockResolvedValue({ json: () => ({ scans: 1, value: '123456789' }) });
        await vi.advanceTimersByTimeAsync(1000);
        window.PosInput.handleKey('5');
        await vi.advanceTimersByTimeAsync(1000);
        expect(document.getElementById('taxIdHidden').value).toBe('5');
        fetch.mockRejectedValue(new Error('offline'));
        await vi.advanceTimersByTimeAsync(1000);
        expect(document.getElementById('taxIdHidden').value).toBe('5');
    });
});
