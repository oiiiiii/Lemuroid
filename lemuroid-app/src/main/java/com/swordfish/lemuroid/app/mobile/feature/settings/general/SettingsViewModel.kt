package com.swordfish.lemuroid.app.mobile.feature.settings.general

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(
    context: Context,
    private val settingsInteractor: SettingsInteractor,
    saveSyncManager: SaveSyncManager,
    sharedPreferences: FlowSharedPreferences,
    private val retrogradeDb: RetrogradeDatabase,
    private val coresSelection: CoresSelection,
) : ViewModel() {
    class Factory(
        private val context: Context,
        private val settingsInteractor: SettingsInteractor,
        private val saveSyncManager: SaveSyncManager,
        private val sharedPreferences: FlowSharedPreferences,
        private val retrogradeDb: RetrogradeDatabase,
        private val coresSelection: CoresSelection,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                context,
                settingsInteractor,
                saveSyncManager,
                sharedPreferences,
                retrogradeDb,
                coresSelection,
            ) as T
        }
    }
    
    data class CoreInfo(
        val systemName: String,
        val coreName: String,
        val coreDisplayName: String,
        val libretroFileName: String,
        val architectures: List<String>,
    )

    data class State(
        val currentDirectory: String = "",
        val currentCoresDirectory: String = "",
        val isSaveSyncSupported: Boolean = false,
    )

    val indexingInProgress = PendingOperationsMonitor(context).anyLibraryOperationInProgress()

    val directoryScanInProgress = PendingOperationsMonitor(context).isDirectoryScanInProgress()

    val uiState = sharedPreferences
        .getString(context.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder))
        .asFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, "")
        .combine(
            sharedPreferences
                .getString(context.getString(com.swordfish.lemuroid.lib.R.string.pref_key_cores_folder))
                .asFlow()
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.Lazily, "")
        ) { currentDirectory, currentCoresDirectory ->
            State(currentDirectory, currentCoresDirectory, saveSyncManager.isSupported())
        }

    fun changeLocalStorageFolder() {
        settingsInteractor.changeLocalStorageFolder()
    }

    fun changeCoresStorageFolder() {
        settingsInteractor.changeCoresStorageFolder()
    }
    
    fun getRequiredCoresInfo() = flow {
        // 首先获取ROM库中的系统ID
        val systemIds = retrogradeDb.gameDao().selectSystems()
        
        // 然后获取用户选择的核心（有多个核心配置的系统）
        coresSelection.getSelectedCores().collect { selectedCores ->
            // 基于ROM库中的系统，获取所需的核心信息
            val requiredCoreInfos = systemIds.mapNotNull { systemId ->
                // 找到对应的GameSystem
                val system = GameSystem.all().find { gameSystem -> gameSystem.id.dbname == systemId }
                system?.let {
                    // 对于有多个核心配置的系统，使用用户选择的核心
                    val selectedCore = selectedCores.find { core -> core.system.id.dbname == systemId }
                    val coreConfig = selectedCore?.coreConfig ?: system.systemCoreConfigs.first()
                    
                    val architectures = coreConfig.supportedOnlyArchitectures?.toList() ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    CoreInfo(
                        systemName = system.titleResId.toString(),
                        coreName = coreConfig.coreID.coreName,
                        coreDisplayName = coreConfig.coreID.coreDisplayName,
                        libretroFileName = coreConfig.coreID.libretroFileName,
                        architectures = architectures,
                    )
                }
            }
            
            emit(requiredCoreInfos)
        }
    }.flowOn(Dispatchers.IO)
}
