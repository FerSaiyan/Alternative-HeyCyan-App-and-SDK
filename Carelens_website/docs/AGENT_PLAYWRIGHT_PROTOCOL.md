# Agent Playwright Protocol

## Goal
Ensure every AI-driven implementation validates critical pages in a real browser before handoff.

## Reference guide
- `node_modules/next/dist/docs/01-app/02-guides/testing/playwright.md`

## Required workflow for agents
1. Implement change.
2. Run `npm run lint`.
3. Run `npm run build`.
4. Run `npm run db:push`.
5. Run `npm run test:e2e:smoke`.
6. If any check fails, fix and rerun all relevant checks.

## Playwright commands
- Install browser binaries (first time):

```bash
npx playwright install chromium
```

- Run all E2E tests:

```bash
npm run test:e2e
```

- Run smoke subset:

```bash
npm run test:e2e:smoke
```

- Open UI mode for debugging:

```bash
npm run test:e2e:ui
```

## Smoke coverage baseline
- Landing page renders key conversion blocks.
- Checkout page renders payment CTA and disclosures.
- Schedule page renders provider status and slot section.
- Schedule booking action performs redirect with confirmation.

## Notes for Funnel/path previews
- Path-based proxies can break static assets when another app owns `/`.
- Prefer dedicated host or dedicated funnel port for accurate visual validation.
- For Funnel validation of interactive UI, prefer `next start` instead of `next dev`.
- If HMR websocket fails behind Funnel, hydration can degrade and animations/click handlers may appear broken.

## Practical Funnel validation checklist
1. Start production server in background on the Funnel target port.
2. Confirm Funnel route target with `tailscale funnel status`.
3. Run Playwright check against Funnel URL (not only localhost).
4. For animation features, compare state at t=0 and after ~3-5 seconds.
5. Save screenshot/video artifacts when debugging regressions.

Detailed operational commands:
- `docs/DEV_SERVER_FUNNEL_PLAYWRIGHT.md`
