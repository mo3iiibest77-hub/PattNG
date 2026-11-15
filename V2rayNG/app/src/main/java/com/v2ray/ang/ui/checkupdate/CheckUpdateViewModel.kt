package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.ApkInstallResult
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NovaPulseApkDownloader
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {

    private val _checkPreRelease = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    )
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()

    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    fun toggleCheckPreRelease(enabled: Boolean) {
        _checkPreRelease.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
    }

    fun checkForUpdates() {
        launchLoading {
            toast(R.string.update_checking_for_update)
            try {
                val result = UpdateCheckerManager.checkForUpdate(_checkPreRelease.value)
                if (result.hasUpdate) {
                    _updateResult.value = result
                    _showUpdateDialog.value = true
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to check for updates", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun downloadAndInstall(option: ApkAssetOption) {
        val app = getApplication<Application>()
        launchLoading {
            _downloadProgress.value = 0
            try {
                val apkFile = NovaPulseApkDownloader.downloadApk(
                    context = app,
                    downloadUrl = option.downloadUrl,
                    fileName = option.fileName,
                    expectedSha256 = option.sha256,
                ) { pct ->
                    _downloadProgress.value = pct
                }
                _downloadProgress.value = null
                when (val installRes = NovaPulseApkDownloader.launchApkInstaller(app, apkFile)) {
                    is ApkInstallResult.NeedsUnknownSourcesPermission -> {
                        toast(R.string.novapulse_update_perm_needed)
                        app.startActivity(NovaPulseApkDownloader.buildUnknownSourcesSettingsIntent(app))
                    }
                    is ApkInstallResult.Failed -> toastError(R.string.toast_failure)
                    ApkInstallResult.LaunchedInstaller -> _showUpdateDialog.value = false
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download failed", e)
                _downloadProgress.value = null
                toastError(R.string.toast_failure)
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }
}
