import { chromium } from '@playwright/test';
import fs from 'fs/promises';

const baseURL = 'http://127.0.0.1:4100';
const viewports = [
  { name: '390x844', width: 390, height: 844 },
  { name: '430x932', width: 430, height: 932 },
  { name: '768x1024', width: 768, height: 1024 },
  { name: '1440x1000', width: 1440, height: 1000 },
];

const run = async () => {
  const browser = await chromium.launch({ headless: true });
  const result = {};
  for (const vp of viewports) {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const page = await context.newPage();
    await page.goto(baseURL, { waitUntil: 'networkidle' });
    await page.waitForTimeout(500);
    result[vp.name] = await page.evaluate(() => {
      const isSticky = (el) => {
        const s = getComputedStyle(el);
        return s.position === 'sticky' || s.position === 'fixed';
      };
      const all = Array.from(document.querySelectorAll('*'));
      const sticky = all.filter(isSticky).sort((a, b) => b.getBoundingClientRect().height - a.getBoundingClientRect().height)[0];
      const stickyBottom = sticky ? sticky.getBoundingClientRect().bottom : 0;

      const titleLike = all.filter((el) => /H[1-4]/.test(el.tagName) || /faq|condi|processo|pacientes|confian|rodap|footer/i.test(el.textContent || ''));
      const overlapCandidates = titleLike
        .map((el) => ({ t: (el.textContent || '').trim().slice(0, 60), top: el.getBoundingClientRect().top, bottom: el.getBoundingClientRect().bottom }))
        .filter((x) => x.bottom > 0 && x.top < stickyBottom && x.top > 0);

      const footer = document.querySelector('footer');
      const faq = all.find((el) => /faq|perguntas frequentes/i.test(el.textContent || ''));

      const glassCandidates = all.filter((el) => {
        const s = getComputedStyle(el);
        return (s.backdropFilter && s.backdropFilter !== 'none') || /(rgba\(255,\s*255,\s*255,\s*0\.[4-8])/.test(s.backgroundColor);
      }).length;

      const processSection = document.getElementById('processo') || all.find((el) => /como funciona/i.test(el.textContent || ''));
      let processGridCols = null;
      if (processSection) {
        const grids = Array.from(processSection.querySelectorAll('*')).filter((el) => getComputedStyle(el).display.includes('grid'));
        if (grids[0]) processGridCols = getComputedStyle(grids[0]).gridTemplateColumns;
      }

      const blobStrong = all.some((el) => {
        const s = getComputedStyle(el);
        return (s.backgroundImage || '').includes('gradient') && parseFloat(s.opacity || '1') > 0.35;
      });

      return {
        stickyBottom,
        overlapCount: overlapCandidates.length,
        overlapCandidates: overlapCandidates.slice(0, 8),
        hasFooter: !!footer,
        hasFaqNode: !!faq,
        glassCandidates,
        processGridCols,
        blobStrong,
      };
    });
    await context.close();
  }
  await browser.close();
  const p = '/tmp/opencode/carelens-review-shots/checks.json';
  await fs.writeFile(p, JSON.stringify(result, null, 2));
  console.log(p);
};

run().catch((e) => { console.error(e); process.exit(1); });
