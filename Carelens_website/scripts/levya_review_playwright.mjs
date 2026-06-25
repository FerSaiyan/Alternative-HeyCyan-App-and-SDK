import { chromium } from '@playwright/test';
import fs from 'fs/promises';
import path from 'path';

const baseURL = process.env.BASE_URL || 'http://127.0.0.1:4100';
const outDir = '/tmp/opencode/carelens-review-shots';
const viewports = [
  { name: '390x844', width: 390, height: 844 },
  { name: '430x932', width: 430, height: 932 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1440x1000', width: 1440, height: 1000 },
];
const sections = [
  { key: 'top', y: 0 },
  { key: 'founder_hero_lower', text: /fundadora|founder|quote|m[eé]tricas|resultados/i },
  { key: 'patients_value_trust', text: /o que pacientes valorizam|what patients value|confian[çc]a|compliance|trust/i },
  { key: 'flow', text: /como funciona|flow|passo 1|etapa 1/i },
  { key: 'conditions', text: /condi[çc][õo]es|reembolso|refund|pol[ií]tica/i },
  { key: 'faq', text: /faq|perguntas frequentes/i },
  { key: 'footer', toBottom: true },
];
const slug = (s) => s.replace(/[^a-z0-9_\-]/gi, '_').toLowerCase();

const requiredSnippets = [
  /R\$\s*1\.700|1700/i,
  /m[ée]dico[s]? independente[s]?|decis[aã]o m[ée]dica independente/i,
  /passo\s*4|etapa\s*4/i,
  /reembolso|refund/i,
];

async function run() {
  await fs.mkdir(outDir, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const report = { baseURL, outDir, viewports: {}, requiredSnippetResults: {} };

  for (const vp of viewports) {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const page = await context.newPage();
    const consoleErrors = [];
    page.on('console', (msg) => msg.type() === 'error' && consoleErrors.push(msg.text()));
    page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));

    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.waitForTimeout(700);

    const screenshots = [];
    for (const section of sections) {
      if (section.toBottom) {
        await page.evaluate(() => window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }));
      } else if (typeof section.y === 'number') {
        await page.evaluate((y) => window.scrollTo({ top: y, behavior: 'instant' }), section.y);
      } else {
        const loc = page.getByText(section.text).first();
        if (await loc.count()) {
          try {
            await loc.scrollIntoViewIfNeeded({ timeout: 2000 });
            await page.evaluate(() => window.scrollBy(0, -120));
          } catch {
            // Fallback: keep current viewport if target is not visible/actionable.
          }
        }
      }
      await page.waitForTimeout(250);
      const file = `${vp.name}__${slug(section.key)}.png`;
      await page.screenshot({ path: path.join(outDir, file) });
      screenshots.push(file);
    }

    const evalData = await page.evaluate(() => {
      const text = document.body.innerText || '';
      const hasDraft = /RASCUNHO DE PRODUTO V0\.2/i.test(text);
      const hasRawHyphenBullets = /(^|\n)\s*-\s+\S+/m.test(text);
      const guaranteeWords = /(garantid[ao]|100%|resultado garantido|prescri[çc][aã]o garantida)/i.test(text);
      const horizontalOverflow = document.documentElement.scrollWidth > window.innerWidth + 1;
      const links = Array.from(document.querySelectorAll('a[href]')).map((a) => ({ text: (a.textContent || '').trim(), href: a.getAttribute('href') || '' }));
      const floatingN = Array.from(document.querySelectorAll('*')).some((el) => {
        const t = (el.textContent || '').trim();
        if (t !== 'N') return false;
        return getComputedStyle(el).position === 'fixed';
      });
      return { text, hasDraft, hasRawHyphenBullets, guaranteeWords, horizontalOverflow, links, floatingN };
    });

    report.viewports[vp.name] = { screenshots, consoleErrors, evalData };
    await context.close();
  }

  const allText = Object.values(report.viewports).map((v) => v.evalData.text).join('\n');
  for (const re of requiredSnippets) {
    report.requiredSnippetResults[re.toString()] = re.test(allText);
  }

  await browser.close();
  const reportPath = path.join(outDir, 'report.json');
  await fs.writeFile(reportPath, JSON.stringify(report, null, 2));
  console.log(reportPath);
}

run().catch((e) => {
  console.error(e);
  process.exit(1);
});
