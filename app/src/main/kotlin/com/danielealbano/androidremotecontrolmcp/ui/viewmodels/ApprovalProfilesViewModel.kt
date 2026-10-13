package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileDeleteResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.policy.engine.OwnPackageName
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the profile list and the add-profile picker. */
@HiltViewModel
class ApprovalProfilesViewModel
    @Inject
    constructor(
        private val repository: ApprovalProfileRepository,
        private val appIconCache: AppIconCache,
        private val events: ApprovalEventLogger,
        @param:OwnPackageName private val ownPackageName: String,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val launchableApps = MutableStateFlow<List<Pair<String, String>>>(emptyList())
        private val icons = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
        private val deleteFailed = MutableStateFlow(false)

        /** Loads the store on first collection; stays [ProfilesSnapshot.EMPTY] while the store is unreadable. */
        private val snapshot: StateFlow<ProfilesSnapshot> =
            repository
                .observeProfiles()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), ProfilesSnapshot.EMPTY)

        val appIcons: StateFlow<Map<String, Bitmap>> = icons.asStateFlow()

        val listState: StateFlow<ProfilesListUiState> =
            combine(snapshot, launchableApps, repository.storeStatus, deleteFailed) { current, apps, status, failed ->
                ProfilesListUiState(
                    items = profileListItems(current, apps),
                    storeUnreadable = status == ProfileStoreStatus.UNREADABLE,
                    deleteFailed = failed,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), ProfilesListUiState())

        val pickerApps: StateFlow<List<PickerApp>> =
            combine(snapshot, launchableApps) { current, apps -> toPickerApps(current, apps, ownPackageName) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), emptyList())

        /** Publishes the cached launcher apps at once, then again after the cache refresh (installs/uninstalls). */
        fun loadApps() {
            viewModelScope.launch(ioDispatcher) {
                publishApps()
                appIconCache.refresh { publishApps() }
            }
        }

        /** Retries reading an unreadable store; the list follows [ApprovalProfileRepository.storeStatus]. */
        fun retryLoad() {
            viewModelScope.launch(ioDispatcher) {
                try {
                    repository.current()
                } catch (_: ProfileStoreUnreadableException) {
                    // storeStatus stays UNREADABLE, so the error card stays on screen.
                }
            }
        }

        fun delete(packageName: String) {
            deleteFailed.value = false
            viewModelScope.launch(ioDispatcher) {
                when (repository.delete(packageName)) {
                    ProfileDeleteResult.DELETED -> {
                        events.log(ApprovalLogRecord(ApprovalLogOutcome.PROFILE_DELETED, packageName = packageName))
                    }

                    ProfileDeleteResult.NOT_FOUND -> {
                        Unit
                    }

                    ProfileDeleteResult.STORE_UNAVAILABLE -> {
                        deleteFailed.value = true
                    }
                }
            }
        }

        fun dismissDeleteError() {
            deleteFailed.value = false
        }

        fun validateManualPackage(text: String): ManualPackageError? {
            val packageName = text.trim()
            return manualPackageError(packageName, snapshot.value, ownPackageName)
        }

        private fun publishApps() {
            launchableApps.value = appIconCache.getLaunchableApps()
            icons.value = appIconCache.getAll()
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
