import { describe, it, expect, vi, beforeEach } from 'vitest';
import { loadScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/apport-count.js';

/** One denomination row as the count fragment draws it. */
function row(id, value, label) {
    return `<div class="count-item" data-id="${id}" data-value="${value}"><span class="item-label">${label}</span>`
        + '<span class="item-qty">x 0</span><span class="item-total">0,00 €</span></div>';
}

/** LC-12-04-02: the apport screen as apport.html draws it, with a threshold of 100 €. */
function screen(rows, withManager = true) {
    return '<form id="apportForm" data-threshold="100"><input id="amount" value="0"><input id="detail" value="">'
        + '<button class="tab-btn active" id="tabBills"></button><button class="tab-btn" id="tabCoins"></button>'
        + `<div id="listContainer">${rows}</div><div id="totalDisplay">0,00 €</div>`
        + (withManager ? '<div id="managerBlock" style="display:none;"></div>' : '')
        + '<div id="numpadArea"></div></form>';
}

/** The keypad the screen arms, recording what it was set up with so a test can key a count. */
let keypad;

/** Loads the script afresh against the given document body. */
async function boot(html, bust) {
    document.body.innerHTML = html;
    keypad = { onChange: null, prefilled: null };
    window.PosInput = {
        setup: (cfg) => { keypad.onChange = cfg.onChange; keypad.config = cfg; },
        prefill: (value) => { keypad.prefilled = value; }
    };
    vi.resetModules();
    return loadScript(SOURCE);
}

/** Taps a denomination row and keys its count on the armed pad. */
function count(id, qty) {
    document.querySelector(`.count-item[data-id="${id}"] .item-label`).click();
    keypad.onChange(qty);
}

describe('LC-12-04-02 running total « À APPORTER »', () => {
    beforeEach(() => {
        globalThis.fetch = vi.fn();
    });

    it('adds every denomination to the cent, without floating-point drift', async () => {
        await boot(screen(row('c10', '0.10', '10 c') + row('b20', '20', '20 €') + row('c1', '0.01', '1 c')), 'a');
        count('c10', '3');
        expect(document.getElementById('totalDisplay').textContent).toBe('0,30 €');
        count('b20', '2');
        count('c1', '7');
        expect(document.getElementById('totalDisplay').textContent).toBe('40,37 €');
        expect(document.querySelector('[data-id="b20"] .item-qty').textContent).toBe('x 2');
        expect(document.querySelector('[data-id="b20"] .item-total').textContent).toBe('40,00 €');
        expect(keypad.config).toEqual(expect.objectContaining({ keyboardType: 'numpad', mode: 'integer', maxLength: 4 }));
    });

    it('corrects a count rather than adding to it, and reads a cleared pad as zero', async () => {
        await boot(screen(row('b5', '5', '5 €') + row('b10', '10', '10 €')), 'b');
        count('b5', '4');
        count('b10', '1');
        document.querySelector('[data-id="b5"] .item-label').click();
        expect(keypad.prefilled).toBe('4');
        expect(document.querySelector('[data-id="b5"]').classList.contains('selected')).toBe(true);
        expect(document.querySelector('[data-id="b10"]').classList.contains('selected')).toBe(false);
        keypad.onChange('');
        expect(document.getElementById('totalDisplay').textContent).toBe('10,00 €');
    });

    it('announces the manager endorsement only above the threshold', async () => {
        await boot(screen(row('b50', '50', '50 €')), 'c');
        count('b50', '2');
        expect(document.getElementById('managerBlock').style.display).toBe('none');
        count('b50', '3');
        expect(document.getElementById('managerBlock').style.display).toBe('block');
    });

    it('keeps working on a screen without the manager block', async () => {
        await boot(screen(row('b50', '50', '50 €'), false), 'd');
        count('b50', '3');
        expect(document.getElementById('totalDisplay').textContent).toBe('150,00 €');
    });

    it('posts the total and the detail, and refuses an empty count', async () => {
        const api = await boot(screen(row('b20', '20', '20 €')), 'e');
        expect(api.prepareApport()).toBe(false);
        count('b20', '3');
        expect(api.prepareApport()).toBe(true);
        expect(document.getElementById('amount').value).toBe('60.00');
        expect(JSON.parse(document.getElementById('detail').value)).toEqual({ b20: 3 });
        expect(window.ApportCount.prepareApport).toBe(api.prepareApport);
    });

    it('keeps the counts across a tab swap and draws the counted rows of the new tab', async () => {
        const api = await boot(screen(row('b20', '20', '20 €')), 'f');
        count('b20', '1');
        globalThis.fetch.mockResolvedValue({ text: () => Promise.resolve(row('c2', '2', '2 €') + row('b20', '20', '20 €')) });
        await api.switchTab('coins', document.getElementById('tabCoins'));
        expect(globalThis.fetch).toHaveBeenCalledWith('/cash-count/fragment/coins');
        expect(document.getElementById('tabCoins').classList.contains('active')).toBe(true);
        expect(document.getElementById('tabBills').classList.contains('active')).toBe(false);
        expect(document.querySelector('[data-id="b20"] .item-qty').textContent).toBe('x 1');
        expect(document.querySelector('[data-id="c2"] .item-qty').textContent).toBe('x 0');
        count('c2', '5');
        expect(document.getElementById('totalDisplay').textContent).toBe('30,00 €');
    });

    it('leaves a fragment row to its own selectItem call, which the page exposes', async () => {
        await boot(screen('<div class="count-item" data-id="c2" data-value="2" onclick="void 0">'
            + '<span class="item-label">2 €</span><span class="item-qty">x 0</span><span class="item-total"></span></div>'), 'i');
        const setups = [];
        const original = window.PosInput.setup;
        window.PosInput.setup = (cfg) => { setups.push(cfg); original(cfg); };
        const row = document.querySelector('[data-id="c2"]');
        row.querySelector('.item-label').click();
        expect(setups).toHaveLength(0);
        window.selectItem(row);
        expect(setups).toHaveLength(1);
        keypad.onChange('4');
        expect(document.getElementById('totalDisplay').textContent).toBe('8,00 €');
    });

    it('ignores a tap beside the rows', async () => {
        await boot(screen(row('b20', '20', '20 €')), 'g');
        document.getElementById('listContainer').click();
        expect(keypad.onChange).toBeNull();
    });

    it('boots on a page without the denomination list', async () => {
        await expect(boot('<main></main>', 'h')).resolves.toBeDefined();
    });
});
