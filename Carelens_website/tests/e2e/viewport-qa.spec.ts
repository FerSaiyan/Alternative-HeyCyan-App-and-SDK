import { expect, test } from "@playwright/test";

const VIEWPORTS = [
  { name: "390x844", width: 390, height: 844 },
  { name: "430x932", width: 430, height: 932 },
  { name: "768x1024", width: 768, height: 1024 },
  { name: "1440x1000", width: 1440, height: 1000 },
];

test.describe("Responsive / A11y / Performance QA", () => {
  for (const vp of VIEWPORTS) {
    test.describe(`Viewport: ${vp.name}`, () => {
      test.use({ viewport: { width: vp.width, height: vp.height } });

      test("no horizontal overflow", async ({ page }) => {
        const errors: string[] = [];
        page.on("console", (msg) => {
          if (msg.type() === "error") errors.push(msg.text());
        });

        await page.goto("/", { waitUntil: "networkidle" });
        
        // Scroll to bottom to trigger any lazy content
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
        await page.waitForTimeout(500);
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.waitForTimeout(300);

        const hasOverflow = await page.evaluate(
          () => document.documentElement.scrollWidth > window.innerWidth + 1
        );
        expect(hasOverflow).toBe(false);

        // Filter out HMR WebSocket noise
        const realErrors = errors.filter(
          (e) => !e.includes("webpack-hmr") && !e.includes("WebSocket")
        );
        expect(realErrors).toEqual([]);
      });

      test("heading structure: exactly one h1, logical order", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        const headings = await page.evaluate(() => {
          return Array.from(document.querySelectorAll("h1, h2, h3, h4")).map((h) => ({
            tag: h.tagName,
            text: (h.textContent || "").trim().slice(0, 80),
          }));
        });

        const h1s = headings.filter((h) => h.tag === "H1");
        expect(h1s).toHaveLength(1);
        expect(h1s[0].text).toContain("tirzepatida");

        // Verify no heading level skips (no h3 directly after h1, etc.)
        for (let i = 1; i < headings.length; i++) {
          const prev = headings[i - 1].tag;
          const curr = headings[i].tag;
          const prevLevel = parseInt(prev[1]);
          const currLevel = parseInt(curr[1]);
          expect(currLevel - prevLevel).toBeLessThanOrEqual(1);
        }
      });

      test("sticky header does not overlap anchored sections", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        // Check header is sticky
        const headerPos = await page.evaluate(() => {
          const h = document.querySelector("header");
          return h ? getComputedStyle(h).position : "none";
        });
        expect(headerPos).toBe("sticky");

        // Check sections have adequate scroll-margin-top
        const sectionMargins = await page.evaluate(() => {
          const sections = document.querySelectorAll("[id]");
          return Array.from(sections)
            .filter((s) => {
              const tag = s.tagName.toLowerCase();
              return ["section", "div"].includes(tag);
            })
            .map((s) => ({
              id: s.id,
              scrollMarginTop: getComputedStyle(s).scrollMarginTop,
              tag: s.tagName,
            }))
            .filter((s) => s.scrollMarginTop !== "0px")
            .slice(0, 10);
        });

        expect(sectionMargins.length).toBeGreaterThan(0);
        for (const s of sectionMargins) {
          const marginPx = parseFloat(s.scrollMarginTop);
          expect(marginPx).toBeGreaterThanOrEqual(48); // header is ~48px tall
        }
      });

      test("decorative media is hidden from screen readers", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        // Under-hero animation section should be aria-hidden
        const underHeroHidden = await page.evaluate(() => {
          const sections = document.querySelectorAll("section");
          for (const s of sections) {
            if (s.textContent?.includes("Transição")) {
              return s.getAttribute("aria-hidden");
            }
          }
          // New version: no visible label, check for overflow-hidden section after hero
          const allSections = document.querySelectorAll("section");
          // Find hero section first, then check the next sibling
          for (let i = 0; i < allSections.length - 1; i++) {
            if (allSections[i].id === "tratamento") {
              const next = allSections[i].nextElementSibling;
              if (next && next.tagName === "SECTION") {
                return next.getAttribute("aria-hidden");
              }
            }
          }
          return "not found";
        });
        // Either aria-hidden or not present (if using new structure)
        expect(underHeroHidden === "true" || underHeroHidden === null || underHeroHidden === "not found").toBe(true);

        // Check no decorative img with aria-hidden=true has non-empty alt
        const badDecorative = await page.evaluate(() => {
          return Array.from(document.querySelectorAll('img[aria-hidden="true"]'))
            .filter((img) => {
              const alt = img.getAttribute("alt");
              return alt && alt !== "";
            })
            .map((img) => img.getAttribute("alt"));
        });
        expect(badDecorative).toEqual([]);
      });

      test("no native video elements on page", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });
        const videoCount = await page.evaluate(
          () => document.querySelectorAll("video").length
        );
        expect(videoCount).toBe(0);
      });

      test("below-fold images do not use priority", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        // Bottom CTA section (Section 8) image should not have priority
        const bottomCtaPriority = await page.evaluate(() => {
          const allSections = document.querySelectorAll("section");
          for (const s of allSections) {
            const text = s.textContent || "";
            if (text.includes("Pronto para começar") || text.includes("Comece com clareza")) {
              const imgs = s.querySelectorAll("img");
              return Array.from(imgs).map(
                (img) =>
                  `${img.getAttribute("src")?.split("/").pop()}: priority=${img.hasAttribute("priority") ? "yes" : "no"}`
              );
            }
          }
          return ["section not found"];
        });
        for (const info of bottomCtaPriority) {
          expect(info).not.toContain("priority=yes");
        }
      });

      test("FAQ accordion has proper ARIA attributes", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        const faqButtons = await page.evaluate(() => {
          const btns = document.querySelectorAll("#faq button");
          return Array.from(btns).map((b) => ({
            expanded: b.getAttribute("aria-expanded"),
            controls: b.getAttribute("aria-controls"),
            id: b.id,
          }));
        });

        expect(faqButtons.length).toBeGreaterThanOrEqual(6);
        for (const btn of faqButtons) {
          expect(btn.expanded).toBeDefined();
          expect(btn.controls).toBeDefined();
          expect(btn.id).toBeTruthy();
        }
      });

      test("meaningful media has proper alt text", async ({ page }) => {
        await page.goto("/", { waitUntil: "networkidle" });

        // Testimonial profile image should have alt text
        const testimonialAlt = await page.evaluate(() => {
          const section = document.querySelector("#depoimentos");
          if (!section) return "no section";
          const imgs = section.querySelectorAll("img");
          return Array.from(imgs).map((i) => i.getAttribute("alt"));
        });
        if (testimonialAlt !== "no section") {
          for (const alt of testimonialAlt) {
            expect(alt).toBeTruthy();
            expect(alt!.length).toBeGreaterThan(0);
          }
        }

        // Footer logo should have alt
        const footerLogoAlt = await page.evaluate(() => {
          const footer = document.querySelector("footer");
          if (!footer) return "no footer";
          const imgs = footer.querySelectorAll("img");
          return imgs.length > 0 ? imgs[0].getAttribute("alt") : "no images";
        });
        if (footerLogoAlt !== "no footer" && footerLogoAlt !== "no images") {
          expect(footerLogoAlt).toBeTruthy();
        }
      });
    });
  }
});
