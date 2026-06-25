# Dev Server, Funnel, and Playwright Guide

## Why this guide exists
- Long-running server commands (`next dev`, `next start`) can look like hanging commands when run in a foreground chain.
- Tailscale Funnel can proxy correctly while app hydration still fails in dev mode due to HMR websocket issues.
- UI animation bugs should be validated in a real browser flow, not only with static checks.

## Start server without hangs (recommended)

1. Build first (required for `next start`):

```bash
npm run build
```

2. Stop previous app process on the same port (example: `3010`):

```bash
kill <old-next-pid>
```

3. Start server in background (do not block terminal):

```bash
nohup npm run start -- --port 3010 --hostname 127.0.0.1 > /tmp/levya-start.log 2>&1 &
```

4. Verify process and local health:

```bash
pgrep -af "next start --port 3010|next-server"
curl -I http://127.0.0.1:3010
```

Notes:
- Prefer `next start` for Funnel validation. Avoid `next dev` behind Funnel when validating interactive behavior.
- Keep startup and checks as separate commands to avoid command-session timeouts.

## Serve through Tailscale Funnel

Current project convention maps Funnel root to `127.0.0.1:3010`.

Verify active mapping:

```bash
tailscale funnel status
```

Expected target for this app:
- `https://<machine>.tail*.ts.net` -> `proxy http://127.0.0.1:3010`

Health checks:

```bash
curl -I http://127.0.0.1:3010
curl -I https://<machine>.tail*.ts.net/coupon?purchaseType=subscription
```

If funnel URL loads but client interactions do not work, check that:
- You are serving `next start` build output (not `next dev`).
- Funnel proxy target points to the same port your app is actually listening on.

## Test feature behavior with Playwright

Always run baseline checks first:

```bash
npm run lint
npm run build
npm run db:push
npm run test:e2e:smoke
```

### Verify animation happened after a few seconds

Use a focused Playwright probe against Funnel URL and compare state at `t=0` vs `t=3.6s`.

Key signals used in this project:
- `data-testid="printer-running-indicator"` should disappear after print completes.
- `data-testid="printer-idle-indicator"` should appear after print completes.
- coupon wrapper inline style should move from `translateY(...)` to `translateY(0px)`.

### Record video for regressions

Use Playwright `recordVideo` context and store artifacts in `/tmp/pw-coupon`:

```bash
node -e "const { chromium } = require('playwright'); (async () => { const browser = await chromium.launch({ headless: true }); const context = await browser.newContext({ recordVideo: { dir: '/tmp/pw-coupon', size: { width: 1280, height: 720 } } }); const page = await context.newPage(); await page.goto('https://<machine>.tail*.ts.net/coupon?purchaseType=subscription', { waitUntil: 'load' }); await page.waitForTimeout(4200); await page.screenshot({ path: '/tmp/pw-coupon/funnel-check.png', fullPage: true }); await context.close(); await browser.close(); })();"
```

Artifacts:
- video files (`.webm`) in `/tmp/pw-coupon`
- screenshots for before/after checks

## Troubleshooting checklist

- Print animation stuck at initial transform/opacity:
  - Ensure server is `next start` on Funnel target port.
  - Restart server and re-run Funnel health checks.
- Buttons appear visible but do nothing:
  - Verify client hydration by checking animation style changes over time.
  - Run Playwright probe against Funnel URL, not only localhost.
- Command appears stuck during server restart:
  - Do not chain long-running server commands with checks in the same foreground session.
  - Start in background, then run `pgrep`/`curl` as separate commands.
