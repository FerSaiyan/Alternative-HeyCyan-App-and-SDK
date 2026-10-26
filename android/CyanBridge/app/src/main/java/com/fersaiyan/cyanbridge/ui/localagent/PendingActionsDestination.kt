package com.fersaiyan.cyanbridge.ui.localagent

import com.fersaiyan.cyanbridge.ui.nav.NavScreen
import android.content.Context
import android.os.Bundle
import com.fersaiyan.cyanbridge.ui.nav.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.localagent.actions.LocalAgentApprovalCoordinator
import com.fersaiyan.cyanbridge.shared.ui.localagent.PendingActionReply
import com.fersaiyan.cyanbridge.shared.ui.localagent.PendingActionsPlatform
import com.fersaiyan.cyanbridge.shared.ui.localagent.PendingActionsScreen
import com.fersaiyan.cyanbridge.shared.ui.localagent.PendingActionsViewModel
import com.fersaiyan.cyanbridge.shared.ui.UiMessage
import com.fersaiyan.cyanbridge.ui.MyApplication
import com.fersaiyan.cyanbridge.ui.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PendingActionsDestination : NavScreen() {

    private val viewModel by viewModels<PendingActionsViewModel> {
        viewModelFactory { initializer { PendingActionsViewModel(AndroidPendingActionsPlatform(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PendingActionsScreen(
                pendingCount = viewModel.pendingCount,
                renderedAction = viewModel.renderedAction,
                hasPendingAction = viewModel.hasPendingAction,
                onRefresh = viewModel::refresh,
                onApprove = viewModel::approve,
                onReject = viewModel::reject,
                onBack = ::finish,
            )
        }
    }
}

private class AndroidPendingActionsPlatform(private val context: Context) : PendingActionsPlatform {
    override suspend fun pendingActions(): List<PendingAction> =
        withContext(Dispatchers.IO) { MyApplication.database.pendingActionDao().getActionsByStatus("pending") }

    override suspend fun reply(approve: Boolean): PendingActionReply {
        val result = LocalAgentApprovalCoordinator.handleReply(context, if (approve) "yes" else "no")
        return PendingActionReply(result.action?.id, result.executed)
    }

    override fun formatTimestamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    override fun prettyJson(json: String): String = JSONObject(json).toString(2)

    override fun showMessage(message: UiMessage) = context.showToast(message)
}
