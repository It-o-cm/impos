import { describe, it, expect, vi } from 'vitest';
import { loadScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/expense-tender.js';

/** BO-03-02-45: the real expense and tender selectors of the cash-movement screen. */
const SCREEN = '<select id="reason">'
    + '<option value="">—</option>'
    + '<option value="Timbres" data-tender="CHEQUE">Timbres</option>'
    + '<option value="Fournitures" data-tender="">Fournitures</option>'
    + '</select>'
    + '<select id="paymentMethod"><option value="">Espèces</option><option value="CHEQUE">CHEQUE</option>'
    + '<option value="TR">TR</option></select><input type="hidden" id="tenderPicked" value="">';

/** Loads the script afresh against the given document body. */
async function boot(html, bust) {
    document.body.innerHTML = html;
    vi.resetModules();
    return loadScript(SOURCE);
}

/** Chooses an option of a selector the way the cashier's tap does. */
function choose(id, value) {
    const select = document.getElementById(id);
    select.value = value;
    select.dispatchEvent(new Event('change'));
}

describe('BO-03-02-45 tender of the expense', () => {
    it('selects the administered tender, then returns to cash for an expense without one', async () => {
        await boot(SCREEN, 'a');
        choose('reason', 'Timbres');
        expect(document.getElementById('paymentMethod').value).toBe('CHEQUE');
        choose('reason', 'Fournitures');
        expect(document.getElementById('paymentMethod').value).toBe('');
        expect(document.getElementById('tenderPicked').value).toBe('');
    });

    it('records a tender picked by hand, and forgets it when another expense is chosen', async () => {
        await boot(SCREEN, 'b');
        choose('paymentMethod', 'TR');
        expect(document.getElementById('tenderPicked').value).toBe('true');
        choose('reason', 'Timbres');
        expect(document.getElementById('tenderPicked').value).toBe('');
        expect(document.getElementById('paymentMethod').value).toBe('CHEQUE');
    });

    it('treats no selected expense as cash', async () => {
        const api = await boot(SCREEN, 'c');
        choose('paymentMethod', 'TR');
        const reason = document.getElementById('reason');
        reason.selectedIndex = -1;
        api.tenderOfExpense(reason);
        expect(document.getElementById('paymentMethod').value).toBe('');
    });

    it('does nothing where the screen offers no tender selector', async () => {
        await boot('<select id="reason"><option value="Timbres" data-tender="CHEQUE">Timbres</option></select>', 'd');
        expect(() => choose('reason', 'Timbres')).not.toThrow();
    });

    it('boots on a page without the expense selector', async () => {
        await expect(boot('<main></main>', 'e')).resolves.toBeDefined();
    });
});
