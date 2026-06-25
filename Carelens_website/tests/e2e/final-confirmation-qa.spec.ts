import { expect, test } from "@playwright/test";

const viewports = [
  { name: "390x844", width: 390, height: 844 },
  { name: "430x932", width: 430, height: 932 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "1440x1000", width: 1440, height: 1000 },
];

const requiredSnippets = [
  "Seu cuidado com tirzepatida em um só fluxo",
  "Condições de assinatura e reembolso",
  "A CareLens garante prescrição de tirzepatida?",
  "4. Reembolso quando aplicável",
  "Se não houver prescrição de tirzepatida ou se o paciente optar por compra externa",
];

const cardSelector = ":is(.glass-card, .glass-hero, .glass-card-hover, .glass-minimal)";

test.describe("Final confirmation QA", () => {
  for (const viewport of viewports) {
    test(`landing QA @ ${viewport.name}`, async ({ page }) => {
      const consoleErrors: string[] = [];
      page.on("console", (msg) => {
        if (msg.type() === "error") consoleErrors.push(msg.text());
      });

      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto("/");

      const safeName = viewport.name;

      // Screenshots are optional - fail silently if environment doesn't support them
      await page.screenshot({ path: `test-results/final-qa/${safeName}-00-fullpage.png`, fullPage: true }).catch(() => {});

      await expect(page.getByRole("heading", { level: 1 })).toContainText("Seu cuidado com tirzepatida em um só fluxo");

      await page.locator("#processo").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `test-results/final-qa/${safeName}-01-processo.png` }).catch(() => {});
      await page.locator("#faq").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `test-results/final-qa/${safeName}-02-faq.png` }).catch(() => {});
      await page.locator("footer").scrollIntoViewIfNeeded();
      await page.screenshot({ path: `test-results/final-qa/${safeName}-03-footer.png` }).catch(() => {});

      // Known section anchors - deterministically check these after navigation
      const knownAnchors = [
        { id: "tratamento", headingLevel: 1 }, // Hero with H1
        { id: "processo", headingLevel: 2 }, // Process section with H2
        { id: "condicoes", headingLevel: 2 }, // Conditions section with H2
        { id: "faq", headingLevel: 2 }, // FAQ section with H2
      ];

      const overlapResults = [];
      for (const anchor of knownAnchors) {
        // Navigate to anchor using scrollIntoView({ block: 'start' }) which respects
        // CSS scroll-margin-top, matching real user hash-navigation behavior
        await page.evaluate((anchorId) => {
          document.getElementById(anchorId)?.scrollIntoView({ block: "start", behavior: "instant" });
        }, anchor.id);
        await page.waitForTimeout(150);
        // Check heading position relative to sticky header
        const result = await page.evaluate(
          ({ id, level }) => {
            const header = document.querySelector("header");
            const section = document.getElementById(id);
            if (!header || !section) return { ok: false, reason: `missing ${id}` };
            const h = header.getBoundingClientRect();
            // Get the first heading of the correct level within the section
            const heading = section.querySelector(`h${level}`);
            if (!heading) return { ok: false, reason: `no h${level} in ${id}` };
            const rect = heading.getBoundingClientRect();
            return {
              ok: rect.top >= h.bottom - 2,
              target: `${id} h${level}`,
              targetTop: rect.top,
              headerBottom: h.bottom,
            };
          },
          { id: anchor.id, level: anchor.headingLevel },
        );
        overlapResults.push(result);
      }

      const noHorizontalOverflow = await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      );

      const hasDebugN = await page.evaluate(() => {
        const nodes = Array.from(document.querySelectorAll("body *"));
        return nodes.some((el) => {
          const text = (el.textContent || "").trim();
          if (text !== "N") return false;
          const style = window.getComputedStyle(el as Element);
          const rect = (el as Element).getBoundingClientRect();
          const isFloating = style.position === "fixed" && rect.width <= 64 && rect.height <= 64;
          const dark = style.backgroundColor.includes("0, 0, 0") || style.backgroundColor.includes("17, 17, 17");
          return isFloating && dark;
        });
      });

      const rawHyphenBullets = await page.evaluate(() => {
        const lis = Array.from(document.querySelectorAll("li"));
        return lis.map((li) => (li.textContent || "").trim()).filter((txt) => txt.startsWith("- "));
      });

      const pageText = (await page.locator("body").innerText()).replace(/\s+/g, " ");
      for (const snippet of requiredSnippets) {
        expect(pageText).toContain(snippet);
      }

      expect(pageText).not.toContain("RASCUNHO DE PRODUTO V0.2");
      expect(pageText).not.toMatch(/garantia de prescri|prescrição garantida|100%/i);
      expect(rawHyphenBullets).toHaveLength(0);
      expect(hasDebugN).toBeFalsy();
      expect(noHorizontalOverflow).toBeTruthy();
      for (const result of overlapResults) {
        expect(result.ok, JSON.stringify(result)).toBeTruthy();
      }

      const processCards = page.locator(`#processo ${cardSelector}`);
      await expect(processCards).toHaveCount(4);

      // FAQ accordion validation - new implementation
      const faqSection = page.locator("#faq");
      await expect(faqSection).toBeVisible();

      // Assert at least 6 FAQ questions are present
      const faqButtons = page.locator("#faq button[aria-expanded]");
      await expect(faqButtons).toHaveCount(6);

      // First item should be open by default (aria-expanded="true")
      const firstFaqButton = faqButtons.first();
      await expect(firstFaqButton).toHaveAttribute("aria-expanded", "true");

      // Answer region should be visible for first item
      const firstAnswer = page.locator("#faq-answer-0");
      await expect(firstAnswer).toBeVisible();

      // Test keyboard toggle - close first item using Enter key
      await firstFaqButton.focus();
      await firstFaqButton.press("Enter");

      // Verify aria-expanded changed to false
      await expect(firstFaqButton).toHaveAttribute("aria-expanded", "false");

      // Verify answer region is hidden (max-h-0 and opacity-0)
      await expect(firstAnswer).toHaveCSS("max-height", "0px");

      // Reopen using Space key
      await firstFaqButton.press("Space");

      // Verify aria-expanded is true again
      await expect(firstFaqButton).toHaveAttribute("aria-expanded", "true");

      // Verify answer is visible again
      await expect(firstAnswer).toBeVisible();

      if (viewport.width < 640) {
        const first = page.locator(`#processo ${cardSelector}`).first();
        const second = page.locator(`#processo ${cardSelector}`).nth(1);
        const [a, b] = await Promise.all([first.boundingBox(), second.boundingBox()]);
        expect(a && b).toBeTruthy();
        if (a && b) {
          expect(Math.abs(a.x - b.x)).toBeLessThan(8);
          expect(b.y).toBeGreaterThan(a.y + a.height - 2);
        }
      }

      // Use first() to avoid strict mode violation when multiple CTA links exist
      const ctaStart = page.getByRole("link", { name: "Iniciar triagem" }).first();
      await expect(ctaStart).toBeVisible();
      await expect(ctaStart).toHaveAttribute("href", "/signin?next=%2Fsub_onboarding");
      const ctaRefund = page.getByRole("link", { name: "Ver condições de reembolso" }).first();
      await expect(ctaRefund).toBeVisible();
      await expect(ctaRefund).toHaveAttribute("href", "/refund");

      expect(consoleErrors, `Console errors on ${viewport.name}: ${consoleErrors.join(" | ")}`).toHaveLength(0);
    });
  }
});
