package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** PackageManager resolution (QUERY_ALL_PACKAGES is declared, so every installed package is visible). */
class IntentTargetResolverImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : IntentTargetResolver {
        override fun implicitPackages(
            kind: IntentKind,
            action: String?,
            data: String?,
            mimeType: String?,
        ): Set<String> {
            val intent = buildIntent(action, data, mimeType)
            val packageManager = context.packageManager
            val matches: List<ResolveInfo> =
                when (kind) {
                    IntentKind.ACTIVITY -> {
                        packageManager.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(MATCH_DEFAULT_ONLY_FLAGS),
                        )
                    }

                    IntentKind.BROADCAST -> {
                        packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(NO_FLAGS))
                    }

                    IntentKind.SERVICE -> {
                        packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(NO_FLAGS))
                    }
                }
            return matches.mapNotNull { it.activityInfo?.packageName ?: it.serviceInfo?.packageName }.toSet()
        }

        private fun buildIntent(
            action: String?,
            data: String?,
            mimeType: String?,
        ): Intent {
            val intent = Intent()
            if (action != null) intent.action = action
            val uri = data?.let { Uri.parse(it) }
            when {
                uri != null && mimeType != null -> intent.setDataAndType(uri, mimeType)
                uri != null -> intent.data = uri
                mimeType != null -> intent.type = mimeType
            }
            return intent
        }

        companion object {
            private const val NO_FLAGS = 0L
            private val MATCH_DEFAULT_ONLY_FLAGS: Long = PackageManager.MATCH_DEFAULT_ONLY.toLong()
        }
    }
