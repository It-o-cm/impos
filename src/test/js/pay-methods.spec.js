import { describe, it, expect, vi } from 'vitest';
import { loadScript } from './harness.js';

const SOURCE = 'src/main/resources/META-INF/resources/pos/pay-methods.js';

/**
 * Builds the payment column with the given number of keys, a key wrapped in its
 * form among them as on the real screen, and the navigation row.
 */
function column(count) {
    let keys = '';
    for (let i = 1; i <= count; i++) {
        keys += i === 2
            ? '<form id="k2" style="display:contents;"><button type="submit" class="btn">K2</button></form>'
            : '<button type="button" class="btn" id="k' + i + '">K' + i + '</button>';
    }
    return '<div id="payMethods">' + keys
        + '<div class="pm-nav"><button type="button" data-pm="main">PRINCIPAUX</button>'
        + '<button type="button" data-pm="more">AUTRE</button></div></div>';
}

/** Loads the script afresh against the given document body. */
async function boot(html) {
    document.body.innerHTML = html;
    vi.resetModules();
    return loadScript(SOURCE);
}

/** Lists the ids of the keys currently drawn. */
function shown() {
    return [...document.querySelectorAll('#payMethods > [id]')]
        .filter((key) => !key.classList.contains('pm-off'))
        .map((key) => key.id);
}

/** Tells whether a navigation key is drawn. */
function navShown(which) {
    const nav = document.querySelector('.pm-nav');
    return !nav.classList.contains('pm-off')
        && !document.querySelector('[data-pm="' + which + '"]').classList.contains('pm-off');
}

describe('Règlement: main payment keys first, the others behind AUTRE', () => {
    it('shows the six main keys and AUTRE only', async () => {
        await boot(column(14));
        expect(shown()).toEqual(['k1', 'k2', 'k3', 'k4', 'k5', 'k6']);
        expect(navShown('more')).toBe(true);
        expect(navShown('main')).toBe(false);
    });

    it('replaces the main keys by the following ones, then the last ones, and brings the main ones back', async () => {
        await boot(column(14));
        document.querySelector('[data-pm="more"]').click();
        expect(shown()).toEqual(['k7', 'k8', 'k9', 'k10', 'k11', 'k12']);
        expect(navShown('more')).toBe(true);
        expect(navShown('main')).toBe(true);
        document.querySelector('[data-pm="more"]').click();
        expect(shown()).toEqual(['k13', 'k14']);
        expect(navShown('more')).toBe(false);
        expect(navShown('main')).toBe(true);
        document.querySelector('[data-pm="main"]').click();
        expect(shown()).toEqual(['k1', 'k2', 'k3', 'k4', 'k5', 'k6']);
    });

    it('draws every key and no navigation when they fit, one more than a page included', async () => {
        await boot(column(7));
        expect(shown()).toEqual(['k1', 'k2', 'k3', 'k4', 'k5', 'k6', 'k7']);
        expect(document.querySelector('.pm-nav').classList.contains('pm-off')).toBe(true);
    });

    it('bounds the page asked to the pages that exist', async () => {
        const api = await boot(column(8));
        const payMethods = document.getElementById('payMethods');
        expect(api.show(payMethods, 9)).toBe(1);
        expect(shown()).toEqual(['k7', 'k8']);
        expect(api.show(payMethods, -3)).toBe(0);
        expect(api.pageCount(13)).toBe(3);
    });

    it('does nothing on a screen without the payment column', async () => {
        await expect(boot('<div id="other"></div>')).resolves.toBeDefined();
    });
});
