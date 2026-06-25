<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Levya implementation rules

## Product context
- Levya coordinates consumers, partner doctors, and partner compounding pharmacies.
- Monthly subscription is BRL 1700 and unlocks short onboarding plus medical appointment scheduling.
- Prescription decisions are fully independent and made by doctors only.
- If tirzepatide is not prescribed, or if patient chooses a non-partner pharmacy, refund follows policy with medical appointment deduction.

## Build order
1. Keep design tokens and glassmorphism visual language consistent.
2. Keep marketing and onboarding experience short and conversion-focused.
3. Keep checkout disclosures visible before payment.
4. Keep post-purchase scheduling immediate and obvious.
5. Keep account and admin status views explicit and auditable.

## Non-negotiables
- Never hardcode secrets or API keys.
- Never remove checkout refund disclosure text without explicit approval.
- Never claim guaranteed prescription outcomes.
- Never skip webhook signature validation in production integrations.
- Never merge code that fails lint/build checks.

## Definition of done
- Works in mobile and desktop breakpoints.
- Critical legal text appears in landing, checkout, and confirmation path.
- API draft endpoints return deterministic JSON or valid redirects.
- Docs in `/docs` are updated when logic or policy changes.

## Playwright self-check protocol (mandatory)
- Before shipping UI or flow changes, run Playwright smoke checks.
- First reference: `node_modules/next/dist/docs/01-app/02-guides/testing/playwright.md`.
- Install browser if needed: `npx playwright install chromium`.
- Required local commands after implementation:
  1. `npm run lint`
  2. `npm run build`
  3. `npm run db:push`
  4. `npm run test:e2e:smoke`
- If smoke test fails, fix and rerun until green.
- Include failing scenario and fix summary in PR notes when relevant.
