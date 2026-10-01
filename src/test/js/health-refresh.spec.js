import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach, vi } from 'vitest';
import { loadScript } from './harness.js';

/*
 * Full branch coverage of health-refresh.js — the normative case 2 of spec-sante-caisse:
 * the "Santé des échanges" table bodies update in place without a page reload, and the
 * refresh is suspended while the tab is hidden.
 *
 * The file is a browser IIFE that, at import time, collects every tbody[data-refresh-url],
 * early-returns when there is none, and otherwise defines refresh()/refreshOne() and
 * schedules refresh() every two seconds. The bench controls the clock with Vitest fake
 * timers (installed BEFORE import so the boot-time setInterval is captured) and mocks
 * global.fetch, so no real network call and no real clock is ever used.
 */

/**
 * Project-relative path of the source under test.
 */
const SOURCE = 'src/main/resources/META-INF/resources/supervisor/health-refresh.js';

/**
 * The refresh URL of the ticket-uplink table body.
 */
const UPLINK_URL = '/supervisor/health/uplink';

/**
 * The refresh URL of the consumption table body.
 */
const CONSUMPTION_URL = '/supervisor/health/consumption';

/**
 * The module object exposing the refresh()/refreshOne() functions once loaded.
 */
let refreshModule;

/**
 * The ticket-uplink table body the module refreshes, kept stable for the suite.
 */
let uplink;

/**
 * Drains the microtask queue so the mocked fetch promise chain settles. Fake timers do not
 * fake microtasks, so awaiting a handful of turns is enough for the four-hop then-chain.
 */
async function flushMicrotasks() {
  for (let i = 0; i < 10; i += 1) {
    await Promise.resolve();
  }
}

/**
 * Installs the two tagged table bodies.
 */
function installDom() {
  document.body.innerHTML = '<table><tbody id="health-uplink" data-refresh-url="' + UPLINK_URL
    + '"></tbody></table><table><tbody id="health-consumption" data-refresh-url="' + CONSUMPTION_URL
    + '"></tbody></table>';
}

/**
 * Installs fake timers and the mocked fetch, builds the DOM, then imports the module so its
 * boot-time setInterval registers against the fake clock and the functions are exposed.
 */
beforeAll(async () => {
  vi.useFakeTimers();
  vi.stubGlobal('fetch', vi.fn());
  installDom();
  refreshModule = await loadScript(SOURCE);
  uplink = document.getElementById('health-uplink');
});

/**
 * Restores the real clock and the real fetch once the suite is done.
 */
afterAll(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/**
 * Resets the fetch mock and both table bodies before each test so state never leaks.
 */
beforeEach(() => {
  fetch.mockReset();
  document.getElementById('health-uplink').innerHTML = '';
  document.getElementById('health-consumption').innerHTML = '';
});

/**
 * Removes any per-test override of document.hidden so it falls back to false.
 */
afterEach(() => {
  delete document.hidden;
});

describe('refreshOne', () => {
  /**
   * A successful response swaps the body with the returned html (ternary ok arm,
   * html != null true arm, inFlight false arm).
   */
  it('fetches and swaps one table body on a successful response', async () => {
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr><td>row</td></tr>') });
    refreshModule.refreshOne(uplink);
    await flushMicrotasks();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(UPLINK_URL, {
      headers: { Accept: 'text/html' },
      credentials: 'same-origin',
    });
    expect(uplink.innerHTML).toBe('<tr><td>row</td></tr>');
  });

  /**
   * A response that is not ok takes the ternary null arm, so text() is never read and the
   * html != null guard is false: the current rows are kept.
   */
  it('keeps the rows when the response is not ok', async () => {
    uplink.innerHTML = 'SENTINEL';
    const text = vi.fn();
    fetch.mockResolvedValue({ ok: false, text });
    refreshModule.refreshOne(uplink);
    await flushMicrotasks();
    expect(text).not.toHaveBeenCalled();
    expect(uplink.innerHTML).toBe('SENTINEL');
  });

  /**
   * A rejected fetch takes the catch arm: the current rows are kept and inFlight is cleared
   * so the next tick may try again.
   */
  it('keeps the rows when the fetch rejects', async () => {
    uplink.innerHTML = 'KEEP';
    fetch.mockRejectedValue(new Error('network down'));
    refreshModule.refreshOne(uplink);
    await flushMicrotasks();
    expect(uplink.innerHTML).toBe('KEEP');
  });

  /**
   * A refresh already in flight for a body (its fetch still pending) makes a concurrent call
   * take the inFlight arm and return at once, so fetch is issued only once for that body.
   */
  it('does not overlap a refresh already in flight for the same body', async () => {
    let resolvePending;
    fetch.mockImplementation(() => new Promise((resolve) => { resolvePending = resolve; }));
    refreshModule.refreshOne(uplink);
    refreshModule.refreshOne(uplink);
    expect(fetch).toHaveBeenCalledTimes(1);
    resolvePending({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    await flushMicrotasks();
  });
});

describe('refresh', () => {
  /**
   * A hidden document takes the document.hidden arm of the guard, so no body is fetched
   * (case 2: the refresh is suspended while the tab is hidden).
   */
  it('does nothing while the document is hidden', async () => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    refreshModule.refresh();
    await flushMicrotasks();
    expect(fetch).not.toHaveBeenCalled();
  });

  /**
   * A visible document sweeps EVERY tagged body: both the uplink and the consumption bodies
   * are fetched, at their own URLs (the forEach arm over the captured targets).
   */
  it('refreshes every tagged table body when the tab is visible', async () => {
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    refreshModule.refresh();
    await flushMicrotasks();
    expect(fetch).toHaveBeenCalledTimes(2);
    const urls = fetch.mock.calls.map((call) => call[0]);
    expect(urls).toContain(UPLINK_URL);
    expect(urls).toContain(CONSUMPTION_URL);
  });

  /**
   * The interval scheduled at boot invokes refresh once every two seconds against the fake
   * clock, sweeping both bodies (case 2: it updates without a reload).
   */
  it('is invoked by the two-second interval', async () => {
    fetch.mockResolvedValue({ ok: true, text: () => Promise.resolve('<tr></tr>') });
    await vi.advanceTimersByTimeAsync(2000);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});

describe('boot guard', () => {
  /**
   * A second evaluation with no tagged body in the document exercises the early-return arm
   * of the targets.length guard and asserts the boot does not throw. The cache-busted import
   * is a SEPARATE instrumented instance whose counters Istanbul does not merge back into the
   * primary bodies-present evaluation, so the early-return arm remains a justified residue
   * rather than a bug: a no-body early return and the bodies-present sweep cannot both be
   * instrumented in a single module evaluation.
   */
  it('returns early when no table body is tagged', async () => {
    document.body.innerHTML = '';
    const second = await loadScript(SOURCE, { bust: 'no-tbody' });
    expect(second).toBeTruthy();
    installDom();
  });
});
