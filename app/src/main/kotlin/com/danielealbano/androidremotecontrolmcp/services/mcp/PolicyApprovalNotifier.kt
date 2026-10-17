package com.danielealbano.androidremotecontrolmcp.services.mcp

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.danielealbano.androidremotecontrolmcp.McpApplication
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PolicyApprovalLabels
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.ui.PolicyApprovalActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map

/**
 * Posts/cancels the single heads-up notification for NOTIFY approvals. It has NO action buttons (an agent could press
 * them through the notification tools): a tap opens [PolicyApprovalActivity]. The lock-screen version names neither
 * the app nor the action, no version shows the amount, and it times out with the latest pending window. It is
 * re-posted only when the set of awaiting ids changes ([awaitingChanges]) and alerts only once while it is shown.
 */
object PolicyApprovalNotifier {
    const val NOTIFICATION_ID = 1004
    private const val REQUEST_CODE = 4
    private const val MIN_TIMEOUT_MS = 1_000L

    /**
     * The [awaiting] entries of each [pending] list, emitted only when their set of ids differs from the previous
     * emission: a pending list that changes only in ASK_IN_CHAT entries, or re-emits the same NOTIFY ids, posts
     * nothing. Pure (JVM-tested); [clock] gives the time each list is filtered at.
     */
    fun awaitingChanges(
        pending: Flow<List<PendingAction>>,
        clock: () -> Long,
    ): Flow<List<PendingAction>> =
        pending
            .map { entries -> awaiting(entries, clock()) }
            .distinctUntilChangedBy { entries -> entries.mapTo(HashSet()) { it.id } }

    /** Reflects [pending]: only [awaiting] entries count. Posting is a no-op without POST_NOTIFICATIONS. */
    fun update(
        context: Context,
        pending: List<PendingAction>,
        nowMs: Long,
    ) {
        val notifyEntries = awaiting(pending, nowMs)
        if (notifyEntries.isEmpty()) {
            cancel(context)
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, notifyEntries, nowMs))
        }
    }

    /** Unexpired NOTIFY entries in creation order; ASK_IN_CHAT entries never notify. Pure (JVM-tested). */
    internal fun awaiting(
        pending: List<PendingAction>,
        nowMs: Long,
    ): List<PendingAction> = pending.filter { it.method == RuleMode.NOTIFY && it.expiresAtMs > nowMs }

    /** Cancels the notification, if any. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun build(
        context: Context,
        awaiting: List<PendingAction>,
        nowMs: Long,
    ): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, PolicyApprovalActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val single = awaiting.singleOrNull()
        val title =
            if (single != null) {
                context.getString(
                    R.string.notification_policy_approval_title_single,
                    appLabel(context, single.decision.packageName),
                )
            } else {
                context.getString(R.string.notification_policy_approval_title_multiple)
            }
        val body =
            if (single != null) {
                context.getString(
                    R.string.notification_policy_approval_body_single,
                    context.getString(PolicyApprovalLabels.actionType(single.decision.actionType)),
                )
            } else {
                context.getString(R.string.notification_policy_approval_body_multiple, awaiting.size)
            }
        val publicVersion =
            NotificationCompat
                .Builder(context, McpApplication.POLICY_APPROVAL_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notification_policy_approval_public_title))
                .setContentText(context.getString(R.string.notification_policy_approval_public_body))
                .setSmallIcon(R.drawable.ic_notification)
                .build()
        return NotificationCompat
            .Builder(context, McpApplication.POLICY_APPROVAL_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setTimeoutAfter((awaiting.maxOf { it.expiresAtMs } - nowMs).coerceAtLeast(MIN_TIMEOUT_MS))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun appLabel(
        context: Context,
        packageName: String?,
    ): String {
        if (packageName == null) return context.getString(R.string.policy_approval_unknown_app)
        val pm = context.packageManager
        return try {
            pm
                .getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L)))
                .toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
