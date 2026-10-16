// Fork-only: drives the web player with its extension loaded and takes screenshots.
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const ext = path.resolve(process.env.EXT_DIR);
const out = path.resolve(process.env.OUT_DIR);
mkdirSync(out, { recursive: true });
const site = 'http://localhost:5173';
const log = [];
const note = (m) => { log.push(m); console.log(m); };

const ctx = await chromium.launchPersistentContext('/tmp/profile', {
  headless: false,
  viewport: { width: 1440, height: 900 },
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
page.on('console', (m) => log.push(`console.${m.type()}: ${m.text()}`.slice(0, 400)));
let n = 0;
const shot = async (name) => {
  n += 1;
  await page.screenshot({ path: `${out}/${String(n).padStart(2, '0')}-${name}.png` });
  note(`shot ${name}`);
};
const step = async (name, fn) => {
  try { await fn(); } catch (e) { note(`STEP FAILED ${name}: ${e.message.split('\n')[0]}`); await shot(`${name}-failed`).catch(() => {}); }
};

await step('settings', async () => {
  await page.goto(`${site}/settings`);
  await page.evaluate(() => localStorage.setItem('bitchord:explicit', JSON.stringify({ block: true, playClean: false, allowed: [] })));
  await page.reload();
  await page.waitForTimeout(3000);
  await page.getByText('Play Clean Versions Instead').click();
  await page.waitForTimeout(500);
  await page.getByText('Play Clean Versions Instead').scrollIntoViewIfNeeded();
  await shot('settings-clean');
  note(`stored: ${await page.evaluate(() => localStorage.getItem('bitchord:explicit'))}`);
});

await step('search', async () => {
  await page.goto(`${site}/search?q=${encodeURIComponent('humble kendrick lamar')}`);
  await page.waitForTimeout(9000);
  await shot('search-clean');
  await page.locator('section[aria-label="Top Results"] button:has([aria-label="Plays clean"])').first().click();
  await page.waitForTimeout(15000);
  await shot('playing-clean');
});

await step('album', async () => {
  await page.goto(`${site}/search?q=${encodeURIComponent('good kid maad city')}`);
  await page.waitForTimeout(9000);
  await shot('search-editions');
  await page.locator('a', { hasText: 'good kid, m.A.A.d city' }).first().click();
  await page.waitForTimeout(8000);
  await page.getByRole('button', { name: /^Play$/ }).first().click();
  await page.waitForTimeout(25000);
  await shot('album-playing');
  await page.getByRole('button', { name: /up next/i }).first().click();
  await page.waitForTimeout(1500);
  await shot('up-next');
  await page.getByRole('button', { name: /up next/i }).first().click();
  const row = page.locator('[role="table"] div[data-variant]:has([aria-label="Plays clean"])').first();
  await row.hover();
  await row.getByRole('button', { name: /^More options for/ }).click({ force: true });
  await page.waitForTimeout(800);
  await shot('menu');
  await page.keyboard.press('Escape');
});

await step('upload', async () => {
  await page.goto(`${site}/search?q=${encodeURIComponent('gold digger kanye west')}`);
  await page.waitForTimeout(9000);
  await page.locator('section[aria-label="Top Results"] button:has([aria-label="Plays clean"])').first().click();
  await page.waitForTimeout(20000);
  await shot('gold-digger');
});

writeFileSync(`${out}/log.txt`, log.join('\n'));
await ctx.close();
