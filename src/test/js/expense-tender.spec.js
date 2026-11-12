import { describe, it, expect, vi } from 'vitest';
import { loadScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/expense-tender.js';

/** BO-03-02-45: the real expense and tender tiles of the cash-movement screen. */
const SCREEN = '<input type="hidden" id="reason" value="">'
    + '<input type="radio" name="reasonTile" id="r1" value="Timbres" data-tender="CHEQUE">'
    + '<input type="radio" name="reasonTile" id="r2" value="Fournitures" data-tender="">'
    + '<input type="hidden" id="paymentMethod" value="">'
    + '<input type="radio" name="tenderTile" id="t0" value="" checked>'
    + '<input type="radio" name="tenderTile" id="t1" value="CHEQUE">'
    + '<input type="radio" name="tenderTile" id="t2" value="TR">'
    + '<input type="hidden" id="tenderPicked" value="">';

/** Loads the script afresh against the given document body. */
async function boot(html, bust) {
    document.body.innerHTML = html;
    vi.resetModules();
    return loadScript(SOURCE, { bust });
}

/** Taps a tile the way the cashier does. */
function tap(id) {
    const tile = document.getElementById(id);
    tile.checked = true;
    tile.dispatchEvent(new Event('change'));
}

/** The value of the tender tile currently shown as chosen. */
function shownTender() {
    const checked = document.querySelector('input[name=tenderTile]:checked');
    return checked ? checked.value : null;
}

describe('BO-03-02-45 tender of the expense', () => {
    it('selects the administered tender, then returns to cash for an expense without one', async () => {
        await boot(SCREEN, 'a');
        tap('r1');
        expect(document.getElementById('reason').value).toBe('Timbres');
        expect(document.getElementById('paymentMethod').value).toBe('CHEQUE');
        expect(shownTender()).toBe('CHEQUE');
        tap('r2');
        expect(document.getElementById('reason').value).toBe('Fournitures');
        expect(document.getElementById('paymentMethod').value).toBe('');
        expect(shownTender()).toBe('');
        expect(document.getElementById('tenderPicked').value).toBe('');
    });

    it('records a tender picked by hand, and forgets it when another expense is chosen', async () => {
        await boot(SCREEN, 'b');
        tap('t2');
        expect(document.getElementById('paymentMethod').value).toBe('TR');
        expect(document.getElementById('tenderPicked').value).toBe('true');
        tap('r1');
        expect(document.getElementById('tenderPicked').value).toBe('');
        expect(document.getElementById('paymentMethod').value).toBe('CHEQUE');
    });

    it('treats no expense tile as cash', async () => {
        const api = await boot(SCREEN, 'c');
        tap('t2');
        api.tenderOfExpense(null);
        expect(document.getElementById('paymentMethod').value).toBe('');
        expect(shownTender()).toBe('');
    });

    it('does nothing to the tender where the screen offers no tender choice', async () => {
        await boot('<input type="hidden" id="reason" value="">'
            + '<input type="radio" name="reasonTile" id="r1" value="Timbres" data-tender="CHEQUE">', 'd');
        expect(() => tap('r1')).not.toThrow();
        expect(document.getElementById('reason').value).toBe('Timbres');
    });

    it('boots on a page without the expense choice', async () => {
        await expect(boot('<main></main>', 'e')).resolves.toBeDefined();
    });
});
