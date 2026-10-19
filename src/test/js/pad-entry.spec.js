import { describe, it, expect, beforeEach } from 'vitest';
import { loadTemplateScript } from './harness.js';

/*
 * The two entry doctrines of the register's touch pads, both carried by PosInput in
 * templates/keyboard-lib.html and therefore true on every screen at once:
 *
 *  1. A PREFILLED VALUE IS A PROPOSAL. It counts only if the cashier validates it
 *     straight away; the first key of the pad takes the whole of it away, with nothing
 *     to erase first. A till has no caret and no selection, so appending to a default
 *     is how a 20 keyed over an amount due of 45,30 used to post 45,3020.
 *  2. EVERY AMOUNT IS KEYED IN CENTS. The cashier keys 1 2 3 4, the display reads
 *     12,34, and the hidden input posts 12.34 — the comma is placed by the application
 *     and never keyed. 'decimal' stays for what is NOT an amount: a weight, a decimal
 *     quantity, a percentage.
 *
 * The bench drives the library exactly as a screen does — a display element, a hidden
 * input, and key presses — and asserts BOTH what the cashier sees and what is posted.
 */

/**
 * Project-relative path of the template carrying the pad library.
 */
const TEMPLATE = 'src/main/resources/templates/keyboard-lib.html';

/**
 * The display element of the field under test, re-created for every test.
 */
let display;

/**
 * The hidden input the field posts through, re-created for every test.
 */
let hidden;

/**
 * The PosInput singleton, re-installed for every test.
 */
let pad;

/**
 * Wires the library onto a fresh display and hidden input.
 *
 * @param {object} config The setup options of the field (mode, maxLength, initialValue).
 * @returns {object} The PosInput singleton, ready to receive keys.
 */
function field(config) {
  document.body.innerHTML = '<div id="d"></div><input id="h"><div id="kb"></div>';
  display = document.getElementById('d');
  hidden = document.getElementById('h');
  const window_ = loadTemplateScript(TEMPLATE);
  window_.PosInput.setup({ displayId: 'd', hiddenId: 'h', containerId: 'kb', ...config });
  return window_.PosInput;
}

/**
 * Presses a sequence of keys on the pad under test.
 *
 * @param {...string} keys The keys, as the pad labels them.
 */
function press(...keys) {
  keys.forEach((k) => pad.handleKey(k));
}

/**
 * What the cashier sees.
 *
 * @returns {string} The display's text.
 */
function shown() {
  return display.textContent;
}

/**
 * What the form posts.
 *
 * @returns {string} The hidden input's value.
 */
function posted() {
  return hidden.value;
}

describe('a prefilled value is a proposal', () => {
  /**
   * Arms an amount field on the amount due, the case that named the rule.
   */
  beforeEach(() => {
    pad = field({ mode: 'cents', maxLength: 10, initialValue: '45.30' });
  });

  it('shows the proposal and marks it untouched', () => {
    expect(shown()).toBe('45,30');
    expect(pad.pristine).toBe(true);
  });

  it('is accepted whole when validated straight away', () => {
    press('OK');
    expect(posted()).toBe('45.30');
    expect(pad.pristine).toBe(true);
  });

  it('is replaced ENTIRELY by the first keys, with nothing to erase', () => {
    press('2', '0');
    expect(shown()).toBe('0,20');
    expect(posted()).toBe('0.20');
  });

  it('is emptied whole by the correction key, as one selected block', () => {
    press('⌫');
    expect(shown()).toBe('0,00');
    expect(posted()).toBe('');
  });

  it('is emptied by the clear key', () => {
    press('C');
    expect(shown()).toBe('0,00');
    expect(posted()).toBe('');
    expect(pad.pristine).toBe(false);
  });

  it('stops being a proposal once typed over, so the next keys append', () => {
    press('2', '0', '5');
    expect(shown()).toBe('2,05');
  });
});

describe('a prefill that proposes nothing', () => {
  it('leaves the field at zero and untouched', () => {
    pad = field({ mode: 'cents', maxLength: 10, initialValue: '' });
    expect(pad.pristine).toBe(false);
    expect(shown()).toBe('0,00');
    press('7');
    expect(shown()).toBe('0,07');
  });

  it('keeps a proposed zero visible, since a zero is a figure', () => {
    pad = field({ mode: 'cents', maxLength: 10, initialValue: '0' });
    expect(shown()).toBe('0,00');
    expect(pad.pristine).toBe(true);
  });
});

describe('an entry carried across a reload', () => {
  /**
   * The live-search exception: the pattern coming back from the server is what the
   * cashier typed, not a proposal, so the next key must extend it.
   */
  it('is resumed and extended, not replaced', () => {
    pad = field({ mode: 'text', maxLength: 20 });
    pad.resume('123');
    expect(pad.pristine).toBe(false);
    press('4');
    expect(shown()).toBe('1234');
  });
});

describe('every amount is keyed in cents', () => {
  beforeEach(() => {
    pad = field({ mode: 'cents', maxLength: 10 });
  });

  it('places the comma two digits from the right', () => {
    press('1', '2', '3', '4');
    expect(shown()).toBe('12,34');
    expect(posted()).toBe('12.34');
  });

  it('reads one digit as a centime', () => {
    press('5');
    expect(shown()).toBe('0,05');
    expect(posted()).toBe('0.05');
  });

  it('reads two digits as centimes', () => {
    press('5', '0');
    expect(shown()).toBe('0,50');
    expect(posted()).toBe('0.50');
  });

  it('keys a round amount with the double zero', () => {
    press('1', '2', '00');
    expect(shown()).toBe('12,00');
    expect(posted()).toBe('12.00');
  });

  it('refuses a separator key, which has nothing left to do', () => {
    press('1', '2', ',', '.', '3');
    expect(shown()).toBe('1,23');
  });

  it('carries its comma before the first key, and posts nothing', () => {
    expect(shown()).toBe('0,00');
    expect(posted()).toBe('');
  });

  it('moves the figures in under a comma that never moves', () => {
    press('1');
    expect(shown()).toBe('0,01');
    press('0');
    expect(shown()).toBe('0,10');
    press('00');
    expect(shown()).toBe('10,00');
    expect(posted()).toBe('10.00');
  });

  it('drops the leading zeros of a long entry', () => {
    press('0', '0', '1', '2', '3', '4');
    expect(shown()).toBe('12,34');
  });

  it('stops at its maximum length instead of growing', () => {
    press('1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1');
    expect(posted()).toBe('12345678.90');
  });

  it('refuses a double zero that would not fit whole', () => {
    pad = field({ mode: 'cents', maxLength: 4 });
    press('1', '2', '3', '00');
    expect(shown()).toBe('1,23');
  });
});

describe('the pre-entry buffer, the other door into an amount', () => {
  /**
   * Mounts the pre-entry pad the way the payment screen does: it owns the pad until
   * a tender key claims it, and what was keyed on it IS the amount settled.
   *
   * @returns {object} The PosPrefill singleton, ready to receive keys.
   */
  function buffer() {
    document.body.innerHTML = '<div id="d"></div><div id="kb"></div>';
    display = document.getElementById('d');
    const window_ = loadTemplateScript(TEMPLATE);
    window_.PosPrefill.mount({ displayId: 'd', containerId: 'kb', maxLength: 10, amount: true });
    return window_.PosPrefill;
  }

  it('shows the comma while it is keyed, when mounted as an amount', () => {
    const pre = buffer();
    expect(display.textContent).toBe('0,00');
    pre.handleKey('1');
    expect(display.textContent).toBe('0,01');
    pre.handleKey('0');
    expect(display.textContent).toBe('0,10');
    pre.handleKey('00');
    expect(display.textContent).toBe('10,00');
  });

  it('stays raw when mounted for a code or a quantity', () => {
    document.body.innerHTML = '<div id="d"></div><div id="kb"></div>';
    display = document.getElementById('d');
    const pre = loadTemplateScript(TEMPLATE).PosPrefill;
    pre.mount({ displayId: 'd', containerId: 'kb', maxLength: 13 });
    ['1', '0', '00'].forEach((k) => pre.handleKey(k));
    expect(display.textContent).toBe('1000');
  });

  it('reads a figure keyed before the ESPÈCES key as cents', () => {
    const pre = buffer();
    ['1', '0', '0', '0'].forEach((k) => pre.handleKey(k));
    expect(pre.consumeAmount()).toBe('10.00');
  });

  it('reads one digit as a centime', () => {
    const pre = buffer();
    pre.handleKey('5');
    expect(pre.consumeAmount()).toBe('0.05');
  });

  it('drops a comma the cashier spelled out, for the same amount', () => {
    const pre = buffer();
    ['1', '0', ',', '0', '0'].forEach((k) => pre.handleKey(k));
    expect(pre.consumeAmount()).toBe('10.00');
  });

  it('gives nothing when nothing was keyed', () => {
    expect(buffer().consumeAmount()).toBe('');
  });

  it('leaves a quantity alone, since a quantity is not an amount', () => {
    const pre = buffer();
    pre.handleKey('3');
    expect(pre.consume()).toBe('3');
  });
});

describe('what is not an amount keeps its comma', () => {
  /**
   * A weighed quantity is the counter-example the cents rule must not touch: 0,850 kg
   * is a measurement the cashier spells out, not 85 centimes.
   */
  it('keys a decimal quantity with the separator', () => {
    pad = field({ mode: 'decimal', maxLength: 9 });
    press('0', ',', '8', '5');
    expect(shown()).toBe('0,85');
    expect(posted()).toBe('0.85');
  });

  it('counts whole units in integer mode', () => {
    pad = field({ mode: 'integer', maxLength: 4 });
    press('1', '2');
    expect(shown()).toBe('12');
    expect(posted()).toBe('12');
  });
});
