import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import { loadScript } from './harness.js';

let guard;
let show;
let close;

/** Boots the actual capture guard with administered rules (LC-13-05-04). */
beforeAll(async () => {
    history.replaceState({}, '', '/');
    document.body.innerHTML = '<script id="training-policy" type="application/json"></script>';
    document.getElementById('training-policy').textContent = JSON.stringify({
        rules: ['key:configured', 'key:/:local', 'path:/pay', 'path:/action/discount/*'],
        message: 'FONCTION INDISPONIBLE EN FORMATION', close: 'OK'
    });
    // jsdom has no native dialog implementation; emulate its open state only in this fixture.
    show = vi.fn(function () { this.open = true; });
    close = vi.fn(function () { this.open = false; });
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: show });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: close });
    guard = await loadScript('src/main/resources/META-INF/resources/pos/training-policy.js');
});

/** Removes actual global listeners so other operator tests keep their own page. */
afterAll(() => {
    document.removeEventListener('click', guard.checkClick, true);
    document.removeEventListener('submit', guard.checkSubmit, true);
    delete HTMLDialogElement.prototype.showModal;
    delete HTMLDialogElement.prototype.close;
});

/** Each case uses a clean operator document and capture-event fixture. */
beforeEach(() => {
    history.replaceState({}, '', '/');
    document.body.innerHTML = '<main class="pos-terminal"></main>';
    show.mockClear(); close.mockClear();
});

/** Installs a key and returns its visible control without changing its configured identity. */
function control(html) {
    document.querySelector('main').innerHTML = html;
    return document.querySelector('button,a,input');
}

/** Captures cancellation without allowing a test link to navigate the jsdom document. */
function event(target, submitter) {
    return { target, submitter, preventDefault: vi.fn(), stopImmediatePropagation: vi.fn() };
}

describe('training policy LC-13-05-04', () => {
    it('retains all usage-report identities and handler arguments', () => {
        expect(guard.keyOf(control('<button data-pos-key="configured">key</button>'))).toBe('configured');
        expect(guard.keyOf(control('<button id="local">key</button>'))).toBe('/:local');
        expect(guard.keyOf(control('<button onclick="pick(7);">key</button>'))).toBe('/:pick(7);');
        control('<form action="/action/test"><button name="choice" value="ONE">key</button></form>');
        expect(guard.keyOf(document.querySelector('button'))).toBe('/:/action/test:choice:ONE');
        expect(guard.keyOf(control('<a href="/pay">pay</a>'))).toBe('/:/pay');
    });
    it('resolves exact local routes and submit overrides without entered data', () => {
        expect(guard.pathOf(control('<a href="/pay?value=private">pay</a>'))).toBe('/pay');
        expect(guard.pathOf(control('<a href="https://example.org/pay">external</a>'))).toBeNull();
        expect(guard.pathOf(control('<button>local</button>'))).toBeNull();
        control('<form><button>local</button></form>');
        expect(guard.pathOf(document.querySelector('button'))).toBeNull();
        control('<form action="/pay"><button formaction="/action/discount/ten">key</button></form>');
        expect(guard.pathOf(document.querySelector('button'))).toBe('/action/discount/ten');
    });
    it('blocks configured local keys and exact paths', () => {
        expect(guard.blocked(control('<button data-pos-key="configured">key</button>'))).toBe(true);
        expect(guard.blocked(control('<button id="local">key</button>'))).toBe(true);
        expect(guard.blocked(control('<a href="/pay">pay</a>'))).toBe(true);
        expect(guard.blocked(control('<a href="/payment">other</a>'))).toBe(false);
        expect(guard.blocked(control('<button id="other">other</button>'))).toBe(false);
    });
    it('uses a slash boundary on explicit subtrees', () => {
        expect(guard.blocked(control('<a href="/action/discount">root</a>'))).toBe(true);
        expect(guard.blocked(control('<a href="/action/discount/ten">child</a>'))).toBe(true);
        expect(guard.blocked(control('<a href="/action/discounted">sibling</a>'))).toBe(false);
    });
    it('keeps the training exit, home and acknowledgement usable even with a blocked key id', () => {
        expect(guard.blocked(control('<a data-pos-key="configured" href="/action/training">exit</a>'))).toBe(false);
        expect(guard.blocked(control('<a data-pos-key="configured" href="/">home</a>'))).toBe(false);
        expect(guard.blocked(control('<button data-training-ack data-pos-key="configured">ok</button>'))).toBe(false);
    });
    it('cancels a blocked click before its handler, leaves the key visible and explains the refusal', () => {
        const button = control('<button data-pos-key="configured"><span>key</span></button>');
        const click = event(button.firstElementChild);
        guard.checkClick(click);
        expect(click.preventDefault).toHaveBeenCalledOnce();
        expect(click.stopImmediatePropagation).toHaveBeenCalledOnce();
        expect(button.isConnected).toBe(true);
        expect(button.hidden).toBe(false);
        expect(document.querySelector('dialog').textContent).toContain('FONCTION INDISPONIBLE EN FORMATION');
        const ack = document.querySelector('dialog button');
        expect(ack.textContent).toBe('OK');
        ack.click();
        expect(close).toHaveBeenCalledOnce();
        guard.checkClick(event(button));
        expect(document.querySelectorAll('dialog')).toHaveLength(1);
        expect(show).toHaveBeenCalledTimes(2);
    });
    it('does not intercept a non-control, disabled key or allowed key', () => {
        for (const target of [document.querySelector('main'), control('<button disabled data-pos-key="configured">no</button>'), control('<button id="other">yes</button>')]) {
            const click = event(target); guard.checkClick(click);
            expect(click.preventDefault).not.toHaveBeenCalled();
        }
    });
    it('guards keyboard submit, with and without an explicit submitter', () => {
        control('<form action="/pay"><button>pay</button></form>');
        const form = document.querySelector('form');
        for (const submitter of [document.querySelector('button'), null]) {
            const submit = event(form, submitter); guard.checkSubmit(submit);
            expect(submit.preventDefault).toHaveBeenCalledOnce();
            expect(submit.stopImmediatePropagation).toHaveBeenCalledOnce();
        }
        form.setAttribute('action', '/other');
        const submit = event(form); guard.checkSubmit(submit);
        expect(submit.preventDefault).not.toHaveBeenCalled();
    });
});
