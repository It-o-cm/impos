import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * LC-09-01-01: the register screens adapt to every screen definition (800x600, 1024x768,
 * 1360x768...) and ratio (4/3, 16/9...) without anything looking blurred, squashed or
 * stretched. The design is a responsive one, held by three rules this bench guards on
 * the templates themselves, because a single screen breaking one of them is enough to
 * bring a fixed geometry back:
 *
 *  1. ONE SCALE: theme.html sizes the root font on min(vw, vh), so every rem of every
 *     screen follows the SMALLER dimension of the panel, and the frame fills the panel.
 *  2. NO FIXED FRAME: no screen gives its .pos-terminal a width or height in pixels.
 *  3. DEVICE WIDTH: every full page declares the device-width viewport. Without it a
 *     touch browser lays the page out on a 980-pixel virtual width and then shrinks the
 *     whole picture onto the panel, which is the blurred, too-small rendering the line
 *     forbids.
 *  4. NO DISTORTED PICTURE: an article picture is fitted with object-fit: contain, never
 *     stretched to its box.
 *
 * The rendering itself was checked in the browser at the listed definitions; this bench
 * keeps the rules from regressing.
 */

/**
 * Absolute path of the template directory.
 */
const TEMPLATES = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', 'main', 'resources', 'templates');

/**
 * Reads one template.
 *
 * @param {string} name The file name, e.g. "main.html".
 * @returns {string} The template source.
 */
function template(name) {
  return readFileSync(resolve(TEMPLATES, name), 'utf8');
}

/**
 * The register screens: every template that includes the shared theme.
 */
const SCREENS = readdirSync(TEMPLATES)
  .filter((name) => name.endsWith('.html'))
  .filter((name) => template(name).includes('{#include theme /}'));

/**
 * The register screens that are full pages, not fragments included by another one.
 */
const PAGES = SCREENS.filter((name) => /<html[\s>]/.test(template(name)));

describe('LC-09-01-01: responsive register screens', () => {
  /** The theme scales the root font on the smaller dimension and fills the panel. */
  it('scales every screen from one root on min(vw, vh)', () => {
    const theme = template('theme.html');
    expect(theme).toMatch(/html \{ font-size: clamp\(11px, min\(1\.45vw, 2\.3vh\), 22px\); \}/);
    const frame = theme.slice(theme.indexOf('.pos-terminal {'));
    expect(frame.slice(0, frame.indexOf('}'))).toMatch(/width: 100vw; height: 100vh;/);
  });

  /** The bench sees the screens it guards. */
  it('finds the register screens', () => {
    expect(SCREENS.length).toBeGreaterThan(40);
    expect(PAGES).toContain('main.html');
    expect(PAGES).not.toContain('endorsement.html');
  });

  /** No screen pins its frame to a pixel width or height. */
  it.each(SCREENS)('%s gives its frame no pixel size', (name) => {
    expect(template(name)).not.toMatch(/\.pos-terminal\s*\{[^}]*\b(width|height)\s*:\s*\d+px/);
  });

  /** Every full page lays out on the device width. */
  it.each(PAGES)('%s declares the device-width viewport', (name) => {
    expect(template(name)).toMatch(/<meta name="viewport" content="width=device-width,\s*initial-scale=1(\.0)?">/);
  });

  /** Every classed picture of a screen is fitted, never stretched. */
  it.each(SCREENS)('%s fits its pictures', (name) => {
    const source = template(name);
    for (const match of source.matchAll(/<img class="([\w-]+)"/g)) {
      const rule = new RegExp('\\.' + match[1] + '\\s*\\{[^}]*object-fit:\\s*contain');
      expect(source, match[1]).toMatch(rule);
    }
  });
});
