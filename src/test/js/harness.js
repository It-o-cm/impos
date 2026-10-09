import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * Absolute path of the project root, derived from this file's location
 * (src/test/js/harness.js is three directories below the root).
 */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/**
 * Loads one of the static browser sources and returns its top-level functions.
 * <p>
 * The sources are plain IIFEs, not ES modules, and stay untouched on disk. They are made
 * importable by the `expose-ui-iife` Vite plugin (see vitest.config.js), which injects a
 * capture of each IIFE's top-level functions onto a `__ui_exports__` object and default-
 * exports it. Importing through Vite — rather than reading the file and eval-ing it — is
 * what lets Istanbul instrument the code under its real path, so branch coverage is
 * attributed to the actual source file. The IIFE body runs once against the current jsdom
 * document on first import; callers that boot DOM code control the DOM beforehand so that
 * run does not throw.
 *
 * @param {string} relativePath Project-relative path of the source, e.g.
 *   "src/main/resources/META-INF/resources/supervisor/health-refresh.js".
 * @param {object} [options] Loading options.
 * @param {string|number} [options.bust] A cache-busting token appended to the import URL so
 *   the module is evaluated afresh (a second boot), used to cover a boot arm that a single
 *   evaluation cannot reach. Istanbul still attributes coverage to the real file path.
 * @returns {Promise<object>} The object exposing the source's top-level functions.
 */
export async function loadScript(relativePath, options = {}) {
  const absolute = resolve(ROOT, relativePath);
  let url = pathToFileURL(absolute).href;
  if (options.bust !== undefined) {
    url += `?bust=${options.bust}`;
  }
  const module = await import(/* @vite-ignore */ url);
  return module.default;
}


/**
 * Runs the inline script of a Qute TEMPLATE and returns the globals it installed.
 * <p>
 * The shared keypad library does not live in a static .js file: it is the inline
 * `<script>` of templates/keyboard-lib.html, included by every screen. It cannot be
 * imported like the supervisor sources, so the bench reads the template, takes its one
 * script block and runs it against the current jsdom window — which is exactly what a
 * screen does when the browser parses the include. The file is never touched, and the
 * globals it assigns (PosKeyboards, PosInput, PosPrefill, padText) are returned.
 *
 * @param {string} relativePath Project-relative path of the template, e.g.
 *   "src/main/resources/templates/keyboard-lib.html".
 * @returns {object} The jsdom window, after the script has installed its globals.
 */
export function loadTemplateScript(relativePath) {
  const source = readFileSync(resolve(ROOT, relativePath), 'utf8');
  const opening = source.indexOf('<script>');
  const closing = source.lastIndexOf('</script>');
  if (opening < 0 || closing < 0) {
    throw new Error(`no script block in ${relativePath}`);
  }
  const code = source.slice(opening + '<script>'.length, closing);
  new Function('window', 'document', code)(globalThis.window, globalThis.document);
  return globalThis.window;
}
