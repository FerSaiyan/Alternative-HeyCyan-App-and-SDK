import { test } from '@playwright/test';

const URL = 'https://akiosmachine.tailf6097b.ts.net:9443/dashboard';

test('dashboard collapsible-menu & tabs verification', async ({ page }) => {
  const results: { step: number; status: string; detail: string }[] = [];

  // ---- Navigate ----
  await page.goto(URL, { waitUntil: 'networkidle', timeout: 60000 });
  await page.waitForTimeout(2000);

  // Step 1: sidebar-toggle-btn exists and has text
  const toggleBtn = page.locator('#sidebar-toggle-btn');
  const btnExists = await toggleBtn.count() > 0;
  const btnText = btnExists ? await toggleBtn.textContent() : '(none)';
  results.push({
    step: 1,
    status: btnExists && btnText?.trim().length > 0 ? 'PASS' : 'FAIL',
    detail: `Button #sidebar-toggle-btn exists=${btnExists}, text="${btnText}"`
  });

  // Step 2: Click once → collapse
  if (btnExists) await toggleBtn.click();
  const sidebarShell = page.locator('.sidebar-shell');
  const hasCollapsedClass = await sidebarShell.evaluate(el =>
    el.classList.contains('menu-collapsed')
  );
  const sidebarNav = page.locator('.sidebar-nav');
  const navHiddenAttr = await sidebarNav.evaluate(el => el.hidden === true);
  const navNotVisible = await sidebarNav.isVisible({ timeout: 3000 }).catch(() => false);
  results.push({
    step: 2,
    status: (hasCollapsedClass && !navNotVisible) ? 'PASS' : 'FAIL',
    detail: `.sidebar-shell.menu-collapsed=${hasCollapsedClass}, .sidebar-nav hidden-attr=${navHiddenAttr} visible=${navNotVisible}`
  });

  // Step 3: Click again → re-expand
  if (btnExists) await toggleBtn.click();
  const hasExpandedClass = await sidebarShell.evaluate(el =>
    !el.classList.contains('menu-collapsed')
  );
  const navVisibleAfter = await sidebarNav.isVisible({ timeout: 3000 }).catch(() => false);
  results.push({
    step: 3,
    status: (hasExpandedClass && navVisibleAfter) ? 'PASS' : 'FAIL',
    detail: `.sidebar-shell no menu-collapsed=${hasExpandedClass}, .sidebar-nav visible=${navVisibleAfter}`
  });

  // Step 4: Click discovery tab
  const discTab = page.locator("button[data-tab='discovery']").first();
  const tabExists = await discTab.count() > 0;
  if (tabExists) {
    await discTab.click();
    await page.waitForTimeout(1000);
  }

  const allTabs = page.locator('[data-tab-page]');
  const totalTabs = await allTabs.count();
  const hiddenTabs = await page.locator('[data-tab-page][hidden]').count();
  const activeTabs = await page.locator('[data-tab-page].active').count();
  const discoveryVisible = await page
    .locator('[data-tab-page="discovery"]')
    .first()
    .isVisible({ timeout: 5000 })
    .catch(() => false);
  const otherHidden = totalTabs > 1
    ? await page
        .locator('[data-tab-page]:not([data-tab-page="discovery"])')
        .first()
        .evaluate(el => el.hidden === true)
      : true;

  results.push({
    step: 4,
    status: (totalTabs > 0 && hiddenTabs === totalTabs - 1 && activeTabs === 1 && discoveryVisible && otherHidden)
      ? 'PASS' : 'FAIL',
    detail: `total=${totalTabs} hidden=${hiddenTabs} active=${activeTabs} discoveryVisible=${discoveryVisible} otherHidden=${otherHidden}`
  });

  // Step 5: Screenshot
  await page.screenshot({ path: '/tmp/tirz-dashboard-collapsible-menu.png', fullPage: true });
  results.push({
    step: 5,
    status: 'PASS',
    detail: 'Screenshot saved to /tmp/tirz-dashboard-collapsible-menu.png'
  });

  // ---- Print results ----
  console.log('\n=== Dashboard Verification Results ===');
  for (const r of results) {
    console.log(`  Step ${r.step}: ${r.status}  ${r.detail}`);
  }
  console.log('=====================================\n');
});
