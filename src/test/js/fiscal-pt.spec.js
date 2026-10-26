import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest';
import { loadTemplateScript } from './harness.js';

/**
 * Opens the Portuguese fiscal documents screen on the conversion mask: a day, a register
 * and a number on the numeric pad, then the customer on the full keyboard, exactly as the
 * server renders the hidden inputs carrying them.
 */
function open() {
    document.body.innerHTML = '<span id="clock"></span>'
        + '<form id="entryForm"><div id="fieldList"></div><div id="entryTitle"></div>'
        + '<div id="kbDisplay"></div><div id="kbArea"></div>'
        + '<input type="hidden" class="pt-field" name="day" id="f_day" value="01102026"'
        + ' data-label="Date (JJMMAAAA) *" data-kb="numpad" data-up="false" data-max="8">'
        + '<input type="hidden" class="pt-field" name="terminal" id="f_terminal" value=""'
        + ' data-label="N° de caisse *" data-kb="email" data-up="true" data-max="20">'
        + '</form>';
    loadTemplateScript('src/main/resources/templates/keyboard-lib.html');
    loadTemplateScript('src/main/resources/templates/fiscal-pt.html');
}

/** Each case starts with a fresh page and a stopped clock. */
beforeEach(() => {
    document.body.innerHTML = '';
    vi.useFakeTimers();
});

/** Gives the real clock back. */
afterEach(() => {
    vi.useRealTimers();
});

describe('Portuguese fiscal documents screen LC-08-04-21/-22', () => {
    it('lists the fields, the first one selected on the numeric pad with its proposed day', () => {
        open();
        const rows = document.querySelectorAll('#fieldList .field');
        expect(rows.length).toBe(2);
        expect(rows[0].className).toBe('field on');
        expect(rows[1].querySelector('.val').className).toBe('val empty');
        expect(document.getElementById('entryTitle').innerText).toBe('Date (JJMMAAAA) *');
        expect(document.getElementById('kbDisplay').textContent).toBe('01102026');
    });
    it('keeps the significant leading zero of a typed day', () => {
        open();
        window.PosInput.handleKey('0');
        window.PosInput.handleKey('2');
        expect(document.getElementById('f_day').value).toBe('02');
        expect(document.querySelector('#fieldList .val').innerText).toBe('02');
    });
    it('moves the keyboard to the field touched, upper-casing what is typed there', () => {
        open();
        document.querySelectorAll('#fieldList .field')[1].click();
        expect(document.getElementById('entryTitle').innerText).toBe('N° de caisse *');
        expect(document.querySelectorAll('#fieldList .field')[1].className).toBe('field on');
        window.PosInput.handleKey('c');
        window.PosInput.handleKey('0');
        expect(document.getElementById('f_terminal').value).toBe('C0');
        expect(document.getElementById('f_day').value).toBe('01102026');
    });
    it('redraws the list when the form is submitted, and ticks the clock', () => {
        open();
        document.getElementById('f_terminal').value = 'C04';
        document.getElementById('entryForm').dispatchEvent(new Event('submit'));
        expect(document.querySelectorAll('#fieldList .val')[1].innerText).toBe('C04');
        vi.advanceTimersByTime(1000);
        expect(document.getElementById('clock').textContent).not.toBe('');
    });
});
