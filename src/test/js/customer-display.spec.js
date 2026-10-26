import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const page = readFileSync('src/main/resources/templates/customer.html', 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
let display;

/** Load the real customer page script without starting its polling clock. */
beforeEach(() => {
  document.body.innerHTML = page.match(/<body\b[^>]*>([\s\S]*?)<\/body>/)[1];
  display = new Function('document', 'fetch', 'setInterval', script + '\nreturn { apply };')(
    document, vi.fn().mockResolvedValue({ json: () => ({ changed: false }) }), vi.fn());
});

/** Build a running sale payload, preserving totals already valued by the engine. */
function payload(items, extra = {}) {
  return { training: false, message: '', locked: false, empty: false, complete: false,
    paying: false, total: '7,60', mealEligible: '5,20', payments: [], labels: { mealEligible: 'Articles éligibles aux titres-restaurant', paying: 'Betaling / PAIEMENT', remaining: 'Nog / Reste', cash: 'Contant / Espèces', change: 'Wisselgeld / Rendu', digital: 'Bon / Ticket' }, items, ...extra };
}

describe('LC-10-01-03 customer advantages', () => {
  it('keeps ordered payments visible through completion and removes cancelled payments', () => {
    const payments = [{ label: 'Cash <admin>', amount: '2,50' }, { label: 'Card', amount: '5,10' }];
    const items = [{ qty: 'x1', label: 'Milk', amount: '7,60' }];
    display.apply(payload(items, { payments, paying: true, remaining: '0,00' }));
    expect(document.querySelectorAll('#cartPayments .payment-line')).toHaveLength(2);
    expect(document.getElementById('cartPayments').textContent).toContain('Cash <admin>2,50 €');
    expect(document.getElementById('cartPayments').querySelector('admin')).toBeNull();
    display.apply(payload(items, { payments, complete: true, change: '1,00', digitalPath: '/t/1/key' }));
    expect(document.getElementById('thanksScreen').classList.contains('active')).toBe(true);
    expect(document.querySelectorAll('#thanksPayments .payment-line')).toHaveLength(2);
    expect(document.getElementById('changeZone').textContent).toBe('Wisselgeld / Rendu : 1,00 €');
    expect(document.getElementById('digitalZone').textContent).toContain('Bon / Ticket');
    display.apply(payload(items, { payments: payments.slice(1), paying: true, remaining: '2,50' }));
    expect(document.querySelectorAll('#cartPayments .payment-line')).toHaveLength(1);
    expect(document.getElementById('cartPayments').textContent).not.toContain('Cash');
    expect(document.getElementById('payingHint').textContent).toBe('Betaling / PAIEMENT');
    expect(document.querySelector('.cart-line').textContent).toContain('Milk');
  });
  it('shows engine and manual labels under the right article as text', () => {
    display.apply(payload([
      { qty: 'x1', label: 'Milk', amount: '8,00', promotion: 'Bundle <img src=x>', modifier: 'Discount 10 %' },
      { qty: 'x1', label: 'Bread', amount: '2,00', promotion: null, modifier: '' },
    ]));
    const rows = document.querySelectorAll('.cart-line');
    expect(rows[0].querySelectorAll('.customer-advantage')).toHaveLength(2);
    expect(rows[0].textContent).toContain('Bundle <img src=x>');
    expect(rows[0].querySelector('img')).toBeNull();
    expect(rows[1].querySelector('.customer-advantage')).toBeNull();
    expect(document.getElementById('totalZone').textContent).toBe('7,60 €');
  });

  it('shows the allocated ticket discount once and clears obsolete advantages on refresh', () => {
    display.apply(payload([{ qty: 'x1', label: 'Milk', amount: '7,60', promotion: 'Offer', modifier: null }],
      { discountLabel: 'Ticket discount <5 %>', discountAmount: '-0,40' }));
    const discount = document.querySelector('.customer-discount');
    expect(discount.textContent).toContain('Ticket discount <5 %>');
    expect(discount.querySelector('.amt').textContent).toBe('-0,40 €');
    expect(document.querySelectorAll('.customer-discount')).toHaveLength(1);
    expect(document.getElementById('totalZone').textContent).toBe('7,60 €');
    display.apply(payload([{ qty: 'x1', label: 'Milk', amount: '8,00', promotion: '', modifier: null }],
      { total: '8,00', discountLabel: null, discountAmount: null }));
    expect(document.querySelector('.customer-discount')).toBeNull();
    expect(document.querySelector('.customer-advantage')).toBeNull();
    expect(document.getElementById('totalZone').textContent).toBe('8,00 €');
  });

  it('preserves advantages while the sale moves into partial payment', () => {
    display.apply(payload([{ qty: 'x2', label: 'Apples', amount: '1,19', promotion: '2 for 1' }],
      { paying: true, remaining: '0,19', total: '1,19' }));
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(true);
    expect(document.querySelector('.customer-advantage').textContent).toBe('2 for 1');
    expect(document.getElementById('remainingZone').textContent).toContain('0,19');
  });
});


describe('LC-10-01-07 customer meal eligibility', () => {
  it('refreshes the eligible base and keeps it visible throughout payment', () => {
    const items = [{ qty: 'x1', label: 'Food', amount: '7,60' }];
    display.apply(payload(items));
    expect(document.getElementById('mealEligibleLabel').textContent).toBe('Articles éligibles aux titres-restaurant');
    expect(document.getElementById('mealEligibleAmount').textContent).toBe('5,20 €');
    display.apply(payload(items, { mealEligible: '4,50', paying: true, remaining: '2,00', payments: [{ label: 'TR', amount: '2,50' }] }));
    expect(document.getElementById('mealEligibleAmount').textContent).toBe('4,50 €');
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(true);
    display.apply(payload(items, { mealEligible: '0,00' }));
    expect(document.getElementById('mealEligibleAmount').textContent).toBe('0,00 €');
    display.apply(payload(items, { complete: true, change: '0,00' }));
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(false);
    display.apply(payload(items, { empty: true }));
    expect(document.getElementById('welcomeScreen').classList.contains('active')).toBe(true);
  });
});

describe('LC-10-01-09 / LC-10-01-11 customer restricted bases', () => {
  it('shows each administered base under the meal one and keeps it until payment ends', () => {
    const items = [{ qty: 'x1', label: 'Food', amount: '7,60' }];
    const bases = [{ label: 'Éco-chèques <b>', amount: '3,20' }, { label: 'Carte achat sociale', amount: '0,00' }];
    display.apply(payload(items, { restrictedEligible: bases }));
    let rows = document.querySelectorAll('#restrictedEligible .meal-eligible');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('span').textContent).toBe('Éco-chèques <b>');
    expect(rows[0].querySelector('b')).toBeNull();
    expect(rows[0].querySelector('strong').textContent).toBe('3,20 €');
    expect(rows[1].querySelector('strong').textContent).toBe('0,00 €');
    display.apply(payload(items, { paying: true, remaining: '2,00', restrictedEligible: [{ label: 'Éco-chèques', amount: '4,10' }] }));
    rows = document.querySelectorAll('#restrictedEligible .meal-eligible');
    expect(rows).toHaveLength(1);
    expect(rows[0].querySelector('strong').textContent).toBe('4,10 €');
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(true);
    display.apply(payload(items, { complete: true, change: '0,00' }));
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(false);
  });

  it('leaves no row when the shop shows no restricted base or the field is absent', () => {
    const items = [{ qty: 'x1', label: 'Food', amount: '7,60' }];
    display.apply(payload(items, { restrictedEligible: [{ label: 'Éco-chèques', amount: '1,00' }] }));
    display.apply(payload(items, { restrictedEligible: [] }));
    expect(document.getElementById('restrictedEligible').children).toHaveLength(0);
    display.apply(payload(items, { restrictedEligible: [{ label: 'Éco-chèques', amount: '1,00' }] }));
    display.apply(payload(items));
    expect(document.getElementById('restrictedEligible').children).toHaveLength(0);
    expect(document.getElementById('mealEligibleAmount').textContent).toBe('5,20 €');
  });
});

describe('LC-10-01-04 customer advantage highlight', () => {
  it('tags each advantage under its article and marks only the lowered line', () => {
    display.apply(payload([
      { qty: 'x2', label: 'Milk', amount: '4,14', promotion: '2 for 1', modifier: 'Discount 10 %' },
      { qty: 'x1', label: 'Bread', amount: '0,96', promotion: '', modifier: null },
    ]));
    const rows = document.querySelectorAll('.cart-line');
    const tags = rows[0].querySelectorAll('.customer-advantage');
    expect(tags).toHaveLength(2);
    expect(tags[0].textContent).toBe('2 for 1');
    expect(tags[0].getAttribute('style')).toBeNull();
    expect(rows[0].classList.contains('has-advantage')).toBe(true);
    expect(rows[1].classList.contains('has-advantage')).toBe(false);
    expect(page).toContain('.cart-line.has-advantage .amt { color: var(--success); }');
  });
});

describe('LC-14-02-07 customer applied tare', () => {
  it('states the tare as text under a weighed line and nothing under the others', () => {
    display.apply(payload([
      { qty: '0,750 kg', label: 'Apples', amount: '1,88', detail: '0,750 kg x 2,50 €/kg', tare: '0,050 kg' },
      { qty: 'x1', label: 'Bread', amount: '0,96' },
    ], { labels: { ...payload([]).labels, tare: 'Tare déduite <b>' } }));
    const rows = document.querySelectorAll('.cart-line');
    expect(rows[0].querySelector('.customer-tare').textContent).toBe('Tare déduite <b> : 0,050 kg');
    expect(rows[0].querySelector('b')).toBeNull();
    expect(rows[1].querySelector('.customer-tare')).toBeNull();
  });
});

describe('LC-03-04-13 customer fidelity balance', () => {
  it('shows the administered balance and clears it when the payload carries none', () => {
    const items = [{ qty: 'x1', label: 'Food', amount: '7,60' }];
    const labels = { ...payload([]).labels, fidelityBalance: 'Cagnotte disponible' };
    display.apply(payload(items, { labels, fidelityHolder: 'DUPONT Jean', fidelityBalance: '12,35' }));
    expect(document.getElementById('fidelityBalance').textContent).toBe('Cagnotte disponible : 12,35 €');
    expect(document.getElementById('fidelityHolder').textContent).toBe('DUPONT Jean');
    display.apply(payload(items, { labels, fidelityBalance: '' }));
    expect(document.getElementById('fidelityBalance').textContent).toBe('');
    display.apply(payload(items, { labels }));
    expect(document.getElementById('fidelityBalance').textContent).toBe('');
  });
});

describe('LC-13-06-08 / -13 customer language choice', () => {
  /**
   * Loads the page script with its own fetch and location doubles, the polling clock
   * stopped, so the language keys and the redraw can be observed.
   *
   * @param {string} lang The language the page was drawn in.
   * @returns {object} The apply function and the two doubles.
   */
  function load(lang) {
    document.body.innerHTML = page.match(/<body\b[^>]*>([\s\S]*?)<\/body>/)[1];
    document.documentElement.lang = lang;
    const fetchMock = vi.fn().mockResolvedValue({ json: () => ({ changed: false }) });
    const location = { reload: vi.fn() };
    const loaded = new Function('document', 'fetch', 'setInterval', 'location', script + '\nreturn { apply };')(
      document, fetchMock, vi.fn(), location);
    fetchMock.mockClear();
    return { apply: loaded.apply, fetchMock, location };
  }

  /** The keys offered by the payload, one per language. */
  const languages = [{ tag: 'fr', name: 'Français' }, { tag: 'nl', name: 'Nederlands' }, { tag: 'pt', name: 'Português <b>' }];

  it('takes no room where the store offers no choice', () => {
    const { apply } = load('fr');
    apply(payload([{ qty: 'x1', label: 'Milk', amount: '1,00' }], { displayLang: 'fr', languages: [] }));
    expect(document.getElementById('languageBar').children).toHaveLength(0);
    expect(document.getElementById('languageBar').classList.contains('active')).toBe(false);
    apply(payload([{ qty: 'x1', label: 'Milk', amount: '1,00' }], { displayLang: 'fr' }));
    expect(document.getElementById('languageBar').classList.contains('active')).toBe(false);
  });

  it('offers one key per language, names it as text and marks the language in force', () => {
    const { apply } = load('nl');
    apply(payload([], { empty: true, displayLang: 'nl', languages }));
    const keys = document.querySelectorAll('#languageBar button');
    expect(Array.from(keys).map(key => key.textContent)).toEqual(['Français', 'Nederlands', 'Português <b>']);
    expect(document.querySelector('#languageBar b')).toBeNull();
    expect(Array.from(keys).map(key => key.className)).toEqual(['', 'current', '']);
    expect(keys[2].lang).toBe('pt');
    expect(document.getElementById('languageBar').classList.contains('active')).toBe(true);
    expect(document.getElementById('welcomeScreen').classList.contains('active')).toBe(true);
    apply(payload([], { empty: true, displayLang: 'nl', languages: [] }));
    expect(document.getElementById('languageBar').children).toHaveLength(0);
  });

  it('posts the tapped language and polls at once', async () => {
    const { apply, fetchMock } = load('fr');
    apply(payload([{ qty: 'x1', label: 'Milk', amount: '1,00' }], { displayLang: 'fr', languages }));
    document.querySelectorAll('#languageBar button')[1].click();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls[0][0]).toBe('/customer/language');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
    expect(fetchMock.mock.calls[0][1].body).toBe('tag=nl');
    expect(fetchMock.mock.calls[1][0]).toMatch(/^\/customer-data/);
  });

  it('keeps the screen when the language could not be posted', async () => {
    const { apply, fetchMock } = load('fr');
    apply(payload([{ qty: 'x1', label: 'Milk', amount: '1,00' }], { displayLang: 'fr', languages }));
    fetchMock.mockRejectedValueOnce(new Error('offline'));
    document.querySelectorAll('#languageBar button')[2].click();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await Promise.resolve();
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(true);
  });

  it('draws the page again when the language it is drawn in moves, and only then', () => {
    const { apply, location } = load('fr');
    apply(payload([{ qty: 'x1', label: 'Milk', amount: '1,00' }], { displayLang: 'fr', languages }));
    expect(location.reload).not.toHaveBeenCalled();
    expect(document.getElementById('cartScreen').classList.contains('active')).toBe(true);
    apply(payload([{ qty: 'x1', label: 'Bread', amount: '2,00' }], { displayLang: 'nl', languages }));
    expect(location.reload).toHaveBeenCalledTimes(1);
    expect(document.querySelector('.cart-line').textContent).toContain('Milk');
  });

  it('declares the language it is drawn in on the page itself', () => {
    expect(page).toContain('<html lang="{inject:i18n.lang}">');
  });
});
