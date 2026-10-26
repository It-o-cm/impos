import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest';
import { loadScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/pay-code-entry.js';

let api;

/** EXP-67: the payment screen's code-entry helper, with the register's arming endpoint stubbed. */
beforeAll(async () => {
    globalThis.fetch = vi.fn(() => Promise.resolve({ ok: true }));
    vi.resetModules();
    api = await loadScript(SOURCE);
});

/** Each case starts with a fresh record of the calls made to the register. */
beforeEach(() => {
    globalThis.fetch.mockClear();
});

describe('EXP-67 Resto Flash token entry', () => {
    it('keys a Resto Flash token on the alphanumeric code pad, in capitals', () => {
        expect(api.codeEntry('restoflash')).toEqual({ keyboardType: 'documentCode', mode: 'text', uppercase: true, maxLength: 64 });
        expect(window.PayCodeEntry.codeEntry).toBe(api.codeEntry);
    });

    it('keeps the gift card and the SOGEC code on the digit pad', () => {
        expect(api.codeEntry('giftcard')).toEqual({ keyboardType: 'numpad', mode: 'integer', uppercase: false, maxLength: 30 });
        expect(api.codeEntry('sogec').mode).toBe('integer');
    });

    it('arms the scanner when the zone opens and disarms it once when another zone takes over', async () => {
        api.armRestoFlashScan(false);
        expect(globalThis.fetch).not.toHaveBeenCalled();
        api.armRestoFlashScan(true);
        api.armRestoFlashScan(true);
        expect(globalThis.fetch).toHaveBeenCalledTimes(1);
        expect(globalThis.fetch).toHaveBeenCalledWith('/action/pay-restoflash/scan', expect.objectContaining({ method: 'POST', body: 'armed=true' }));
        api.armRestoFlashScan(false);
        expect(globalThis.fetch).toHaveBeenLastCalledWith('/action/pay-restoflash/scan', expect.objectContaining({ body: 'armed=false' }));
        await Promise.resolve();
    });

    it('forgets an arming the register never received, so the next opening posts it again', async () => {
        globalThis.fetch.mockImplementationOnce(() => Promise.reject(new Error('offline')));
        api.armRestoFlashScan(true);
        await new Promise(resolve => setTimeout(resolve, 0));
        api.armRestoFlashScan(true);
        expect(globalThis.fetch).toHaveBeenCalledTimes(2);
        api.armRestoFlashScan(false);
        await new Promise(resolve => setTimeout(resolve, 0));
    });
});
