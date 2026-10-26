package com.fersaiyan.cyanbridge.ui.nav

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * An exported entry point kept under its old component name (deep links, app links, the Meta
 * callback): it hands the intent to [route] in MainActivity's task and closes, without a window.
 *
 * MainActivity is single-task, so the intent reaches the running instance (NEW_TASK only says so
 * when the link was opened from another app's task); the route's own rules decide the rest.
 */
open class RouteTrampolineActivity(private val route: AppRoute) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(forward(intent))
        finish()
    }

    private fun forward(source: Intent?): Intent = route.intent(this).apply {
        data = source?.data
        source?.extras?.let(::putExtras)
        putExtra(AppRoute.EXTRA_ROUTE, route.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
