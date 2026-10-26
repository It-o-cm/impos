import { describe, it, expect, beforeEach } from 'vitest';
import { loadTemplateScript } from './harness.js';

/*
 * LC-09-01-09: the letter layout of the alphabetic and alphanumeric virtual keyboards
 * follows the country or region the shop administers (keyboard.layout). The library is
 * the inline script of templates/keyboard-lib.html; the bench runs it RAW, so the Qute
 * expression of the layout stays unrendered — exactly the "not rendered" case the
 * library must answer with AZERTY — and each test then sets the layout a rendered
 * screen would carry.
 *
 * jsdom lays nothing out, so the grid placement is recomputed here the way CSS grid
 * auto-placement does it on the ten-column alphabetic grid: an item that does not fit
 * the rest of its row starts the next one. That is what proves a nine-letter row no
 * longer pulls the next row up.
 */

/**
 * Project-relative path of the template carrying the keyboard library.
 */
const TEMPLATE = 'src/main/resources/templates/keyboard-lib.html';

/**
 * The PosKeyboards library, re-installed for every test.
 */
let keyboards;

/**
 * The keys pressed during the current test, in order.
 */
let pressed;

/** Installs a fresh library and an empty host for each test. */
beforeEach(() => {
  document.head.innerHTML = '';
  document.body.innerHTML = '<div id="kb"></div><div id="d"></div><input id="h">';
  keyboards = loadTemplateScript(TEMPLATE).PosKeyboards;
  pressed = [];
});

/**
 * Renders one of the letter keyboards into the host.
 *
 * @param {string} renderer The renderer name, e.g. "renderAlphaKeyboard".
 * @param {string} [layout] The layout a rendered screen would carry; omitted, the raw
 *   unrendered Qute expression is kept.
 * @returns {HTMLElement} The keyboard grid.
 */
function render(renderer, layout) {
  if (layout !== undefined) {
    keyboards.layout = layout;
  }
  keyboards[renderer](document.getElementById('kb'), (key) => pressed.push(key));
  return document.querySelector('#kb .pos-keyboard');
}

/**
 * Lays the grid's children out on ten columns as CSS grid auto-placement does.
 *
 * @param {HTMLElement} kb The keyboard grid.
 * @returns {string[][]} The rows, each cell holding the key label, '' for a spacer;
 *   a key spanning several columns is repeated once per column it covers.
 */
function layOut(kb) {
  const rows = [[]];
  for (const child of kb.children) {
    const classes = child.className;
    const span = classes.includes('pos-key--wide5') ? 5
      : (classes.includes('pos-key--wide2') ? 2 : 1);
    if (rows[rows.length - 1].length + span > 10) {
      rows.push([]);
    }
    const label = child.tagName === 'BUTTON' ? child.innerText : '';
    for (let i = 0; i < span; i++) {
      rows[rows.length - 1].push(label);
    }
    if (rows[rows.length - 1].length === 10) {
      rows.push([]);
    }
  }
  return rows.filter((row) => row.length > 0);
}

/**
 * Joins a laid-out row into a compact string: letters as they are, '.' for a spacer,
 * a spanning key once per column it covers.
 *
 * @param {string[]} row One row from layOut.
 * @returns {string} The row as one string.
 */
function line(row) {
  return row.map((cell) => (cell === '' ? '.' : (cell.length > 1 ? '#' : cell))).join('');
}

/**
 * Clicks the key showing the given label.
 *
 * @param {string} label The visible label of the key.
 */
function tap(label) {
  const key = Array.from(document.querySelectorAll('#kb button')).find((b) => b.innerText === label);
  expect(key).toBeDefined();
  key.click();
}

describe('LC-09-01-09: alphabetic keyboard layouts', () => {
  /** Unrendered, the layout is the raw Qute expression and the keyboard stays AZERTY. */
  it('keeps AZERTY when the layout was not rendered', () => {
    expect(keyboards.layout).toBe('{posKeyboardLayout}');
    const rows = layOut(render('renderAlphaKeyboard')).map(line);
    expect(rows).toEqual(['AZERTYUIOP', 'QSDFGHJKLM', 'WXCVBN....', '##########']);
  });

  /** Each administered layout draws its own letter rows, staggered on the ten columns. */
  it.each([
    ['AZERTY', ['AZERTYUIOP', 'QSDFGHJKLM', 'WXCVBN....']],
    ['QWERTY', ['QWERTYUIOP', 'ASDFGHJKL.', 'ZXCVBNM...']],
    ['QWERTZ', ['QWERTZUIOP', 'ASDFGHJKL.', 'YXCVBNM...']],
    ['ABCD', ['ABCDEFGHIJ', 'KLMNOPQRST', 'UVWXYZ....']],
  ])('draws the %s rows', (layout, letters) => {
    const kb = render('renderAlphaKeyboard', layout);
    const rows = layOut(kb);
    expect(rows.map(line)).toEqual([...letters, '##########']);
    expect(rows[3]).toEqual(['ESPACE', 'ESPACE', 'ESPACE', 'ESPACE', 'ESPACE',
      'EFFACER', 'EFFACER', 'EFFACER', 'EFFACER', 'EFFACER']);
    expect(kb.querySelectorAll('button.pos-key--alpha')).toHaveLength(26);
    for (const spacer of kb.querySelectorAll('.pos-key-spacer')) {
      expect(spacer.getAttribute('aria-hidden')).toBe('true');
    }
  });

  /** An unknown name, or one only the object prototype knows, falls back to AZERTY. */
  it.each(['DVORAK', 'constructor', ''])('falls back to AZERTY for %j', (layout) => {
    const rows = layOut(render('renderAlphaKeyboard', layout)).map(line);
    expect(rows.slice(0, 3)).toEqual(['AZERTYUIOP', 'QSDFGHJKLM', 'WXCVBN....']);
  });

  /** A QWERTY key types its own letter, lower-cased like every alphabetic key. */
  it('types the letters of the layout through the screen binding', () => {
    const input = loadTemplateScript(TEMPLATE).PosInput;
    window.PosKeyboards.layout = 'QWERTY';
    input.setup({ displayId: 'd', hiddenId: 'h', containerId: 'kb', keyboardType: 'alpha', mode: 'text', maxLength: 20 });
    ['Q', 'W', 'Z', 'ESPACE', 'M'].forEach(tap);
    expect(document.getElementById('h').value).toBe('qwz m');
  });
});

describe('LC-09-01-09: search keyboard layouts', () => {
  /** AZERTY keeps the historical last row: six letters, space and erase two slots each. */
  it('keeps the AZERTY search rows', () => {
    const rows = layOut(render('renderSearchKeyboard', 'AZERTY'));
    expect(rows.map(line)).toEqual(['1234567890', 'AZERTYUIOP', 'QSDFGHJKLM', 'WXCVBN####']);
    expect(rows[3].slice(6)).toEqual(['ESPACE', 'ESPACE', 'EFFACER', 'EFFACER']);
  });

  /** ABCD also ends on six letters and gets the same wide space key. */
  it('keeps the wide space key on the six-letter ABCD row', () => {
    const rows = layOut(render('renderSearchKeyboard', 'ABCD'));
    expect(rows.map(line)).toEqual(['1234567890', 'ABCDEFGHIJ', 'KLMNOPQRST', 'UVWXYZ####']);
  });

  /** Seven letters leave three slots: a one-slot space glyph and the two-slot erase. */
  it.each(['QWERTY', 'QWERTZ'])('fits the seven-letter %s row on one line', (layout) => {
    const rows = layOut(render('renderSearchKeyboard', layout));
    expect(rows).toHaveLength(4);
    expect(rows[2].join('')).toBe('ASDFGHJKL');
    expect(line(rows[2])).toBe('ASDFGHJKL.');
    expect(rows[3].slice(7)).toEqual(['␣', 'EFFACER', 'EFFACER']);
    tap('␣');
    tap('EFFACER');
    expect(pressed).toEqual([' ', 'DEL']);
  });
});

describe('LC-06-02-01 / -09: document-code keyboard layouts', () => {
  /** AZERTY: the two-slot space key becomes a two-slot dash key. */
  it('puts the dash where the AZERTY space key was', () => {
    keyboards.layout = 'AZERTY';
    keyboards.renderSearchKeyboard(document.getElementById('kb'), (key) => pressed.push(key), '-');
    const rows = layOut(document.querySelector('#kb .pos-keyboard'));
    expect(rows[3].slice(6)).toEqual(['-', '-', 'EFFACER', 'EFFACER']);
    tap('-');
    expect(pressed).toEqual(['-']);
  });

  /** QWERTY: the one-slot space glyph becomes a one-slot dash key. */
  it('puts the dash where the QWERTY space glyph was', () => {
    keyboards.layout = 'QWERTY';
    keyboards.renderSearchKeyboard(document.getElementById('kb'), (key) => pressed.push(key), '-');
    const rows = layOut(document.querySelector('#kb .pos-keyboard'));
    expect(rows[3].slice(7)).toEqual(['-', 'EFFACER', 'EFFACER']);
    tap('-');
    expect(pressed).toEqual(['-']);
  });
});

describe('LC-09-01-09: e-mail keyboard layouts', () => {
  /** AZERTY keeps the historical e-mail rows, '@._-' closing the last letter row. */
  it('keeps the AZERTY e-mail rows', () => {
    const rows = layOut(render('renderEmailKeyboard', 'AZERTY')).map(line);
    expect(rows.slice(0, 5)).toEqual(['1234567890', 'AZERTYUIOP', 'QSDFGHJKLM', 'WXCVBN@._-', "!#$%&'*+/="]);
  });

  /** QWERTZ letters keep their rows; the symbol keys that follow still all exist. */
  it('draws the QWERTZ letters on their own rows', () => {
    const rows = layOut(render('renderEmailKeyboard', 'QWERTZ')).map(line);
    expect(rows.slice(0, 4)).toEqual(['1234567890', 'QWERTZUIOP', 'ASDFGHJKL.', 'YXCVBNM@._']);
    expect(rows[4].startsWith("-!#$%&'*+/")).toBe(true);
    tap('Y');
    tap('-');
    expect(pressed).toEqual(['y', '-']);
  });
});
