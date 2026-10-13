package com.danielealbano.androidremotecontrolmcp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalScreenVisibility
import com.danielealbano.androidremotecontrolmcp.ui.screens.PolicyApprovalScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts the action-approval screen. Not exported: opened only from the policy approval notification or the Server
 * screen card. No FLAG_SECURE: the agent may already read the screen; the own-package guard blocks it from acting.
 */
@AndroidEntryPoint
class PolicyApprovalActivity : ComponentActivity() {
    @Inject
    lateinit var screenVisibility: ApprovalScreenVisibility

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidRemoteControlMcpTheme {
                PolicyApprovalScreen(onAllHandled = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        screenVisibility.onShown()
    }

    override fun onStop() {
        screenVisibility.onHidden()
        super.onStop()
    }
}
