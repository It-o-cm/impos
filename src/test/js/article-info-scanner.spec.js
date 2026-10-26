import { readFileSync } from 'node:fs';
import { describe, it, expect, vi } from 'vitest';

/** LC-02-14-12: execute the actual consultation scanner script with an isolated browser and fake transport. */
function scanner() {
  const source = readFileSync('src/main/resources/templates/article-info.html', 'utf8');
  const start = source.indexOf("    const scanToken =");
  const end = source.indexOf("    setInterval(() => { document.getElementById('clock')", start);
  expect(start).toBeGreaterThan(0);
  expect(end).toBeGreaterThan(start);
  const code = source.slice(start, end).replace('{scanToken}', 'page-1');
  const fetch = vi.fn();
  const location = { replace: vi.fn() };
  const navigator = { sendBeacon: vi.fn() };
  const window = { addEventListener: vi.fn() };
  const interval = vi.fn();
  new Function('fetch', 'location', 'navigator', 'window', 'setInterval', code)(fetch, location, navigator, window, interval);
  expect(interval).toHaveBeenCalledWith(expect.any(Function), 500);
  return { fetch, location, navigator, window, poll: interval.mock.calls[0][0] };
}

describe('LC-02-14-12 read-only scanner screen', () => {
  it('shows a captured article through the lookup route without an add action', async () => {
    const screen = scanner();
    screen.fetch.mockResolvedValue({ ok: true, json: async () => ({ code: '12345670' }) });
    await screen.poll();
    expect(screen.fetch).toHaveBeenCalledWith('/article-info/scan?token=page-1', { cache: 'no-store' });
    expect(screen.location.replace).toHaveBeenCalledWith('/article-info?code=12345670');
  });

  it('keeps an empty mailbox on the same screen and releases its page lease on exit', async () => {
    const screen = scanner();
    screen.fetch.mockResolvedValue({ ok: true, json: async () => ({ code: '' }) });
    await screen.poll();
    expect(screen.location.replace).not.toHaveBeenCalled();
    expect(screen.window.addEventListener).toHaveBeenCalledWith('pagehide', expect.any(Function));
    screen.window.addEventListener.mock.calls[0][1]();
    expect(screen.navigator.sendBeacon).toHaveBeenCalledWith('/article-info/leave?token=page-1');
  });

  it('contains failed responses and transport errors, then retries the next poll', async () => {
    const screen = scanner();
    screen.fetch.mockResolvedValueOnce({ ok: false }).mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ ok: true, json: async () => ({ code: 'A?/42' }) });
    await screen.poll();
    await screen.poll();
    expect(screen.location.replace).not.toHaveBeenCalled();
    await screen.poll();
    expect(screen.location.replace).toHaveBeenCalledWith('/article-info?code=A%3F%2F42');
  });

  it('serializes slow polls so a scanner code is never consumed by overlapping requests', async () => {
    const screen = scanner();
    let finish;
    screen.fetch.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const pending = screen.poll();
    await screen.poll();
    expect(screen.fetch).toHaveBeenCalledTimes(1);
    finish({ ok: true, json: async () => ({ code: '' }) });
    await pending;
  });
});
