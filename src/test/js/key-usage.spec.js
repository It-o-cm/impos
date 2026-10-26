import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import { loadScript } from './harness.js';

let observer;
let originalBeacon;

/** Boots the real observer with a fake beacon transport (LC-13-05-01). */
beforeAll(async () => {
  originalBeacon = navigator.sendBeacon;
  Object.defineProperty(navigator, 'sendBeacon', { configurable: true, writable: true, value: vi.fn() });
  observer = await loadScript('src/main/resources/META-INF/resources/supervisor/key-usage.js');
});

/** Restores the browser listener and transport after the isolated tests. */
afterAll(() => {
  document.removeEventListener('click', observer.recordClick, true);
  navigator.sendBeacon = originalBeacon;
});

/** Each case starts on the operator page with a clean beacon mock. */
beforeEach(() => {
  history.replaceState({}, '', '/');
  document.body.innerHTML = '<main class="pos-terminal"></main>';
  navigator.sendBeacon = vi.fn();
});

/** Installs one control on the operator surface. */
function control(html) {
  document.querySelector('main').innerHTML = html;
  return document.querySelector('button,a,input');
}

describe('dynamic key usage, LC-13-05-01', () => {
  it('preserves a configured identity', () => {
    const button = control('<button data-pos-key="configured-17">key</button>');
    observer.recordClick({ target: button });
    expect(navigator.sendBeacon).toHaveBeenCalledWith('/api/pos/key-use', 'configured-17');
  });
  it('identifies stable DOM ids', () => {
    expect(observer.keyOf(control('<button id="cash">key</button>'))).toBe('/:cash');
  });
  it('identifies configured handlers and their arguments', () => {
    expect(observer.keyOf(control(`<button onclick="payWith('cash')">key</button>`))).toBe(`/:payWith('cash')`);
  });
  it('identifies submitted action keys without reading form inputs', () => {
    const button = control('<form action="/action/pay"><input name="password" value="secret"><button name="mode" value="CARD">key</button></form>');
    const key = observer.keyOf(document.querySelector('button'));
    expect(key).toBe('/:/action/pay:mode:CARD');
    expect(key).not.toContain('secret');
  });
  it('identifies links', () => {
    expect(observer.keyOf(control('<a href="/pay">pay</a>'))).toBe('/:/pay');
  });
  it('ignores non-controls', () => {
    observer.recordClick({ target: document.querySelector('main') });
    expect(navigator.sendBeacon).not.toHaveBeenCalled();
  });
  it('never records typing-pad digits', () => {
    observer.recordClick({ target: control('<button class="pos-key" data-key="7">7</button>') });
    expect(navigator.sendBeacon).not.toHaveBeenCalled();
  });
  it('does not count disabled controls', () => {
    observer.recordClick({ target: control('<button disabled>key</button>') });
    expect(navigator.sendBeacon).not.toHaveBeenCalled();
  });
  it('does not count back-office or customer-page controls', () => {
    document.querySelector('main').className = '';
    observer.recordClick({ target: control('<button>key</button>') });
    expect(navigator.sendBeacon).not.toHaveBeenCalled();
  });
  it('includes the maintenance operator screen', () => {
    history.replaceState({}, '', '/maintenance');
    document.querySelector('main').className = '';
    observer.recordClick({ target: control('<button id="reset">key</button>') });
    expect(navigator.sendBeacon).toHaveBeenCalledWith('/api/pos/key-use', '/maintenance:reset');
  });
  it('bounds dynamically configured identifiers to the journal contract', () => {
    observer.recordClick({ target: control('<button data-pos-key="' + 'x'.repeat(210) + '">key</button>') });
    expect(navigator.sendBeacon.mock.calls[0][1]).toHaveLength(200);
  });
  it('keeps operator actions usable when the beacon is unavailable', () => {
    navigator.sendBeacon.mockImplementation(() => { throw new Error('unavailable'); });
    expect(() => observer.recordClick({ target: control('<button id="reset">key</button>') })).not.toThrow();
  });
});
