package com.nutomic.syncthingandroid.selectivesync

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.SelectiveSyncPatterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择性同步：在目录树上勾选要同步的内容，保存后写进 .stignore。
 *
 * <p>从编辑文件夹界面最下方的开关/入口进来。开关本身也在这里，方便顺手切换。
 */
class SelectiveSyncActivity : SelectiveSyncBaseActivity() {

    private var enabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (folderId.isEmpty()) {
            return
        }
        enabled = store.isEnabled(folderId)

        setContent {
            ApplicationTheme {
                SelectiveSyncScreen(
                    folderLabel = folderTitle,
                    repo = repository,
                    store = store,
                    enabled = enabled,
                    onToggleEnabled = ::toggleEnabled,
                    ready = ready,
                    onOpenVerifyFiles = ::openVerifyFiles,
                    onBack = { finish() },
                    message = message,
                    onMessage = { message = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        startStatePolling()
    }

    private fun openVerifyFiles() {
        startActivity(
            Intent(this, VerifyFilesActivity::class.java)
                .putExtra(EXTRA_FOLDER_ID, folderId),
        )
    }

    /**
     * 打开/关闭选择性同步。
     *
     * <p>关闭＝移除本功能生成的托管块（用户自己写的规则保留），恢复全部同步。
     * 选择集仍然留着，重新打开时不用再勾一遍。
     *
     * <p>打开时如果还没有任何选择集，**不写任何规则**：
     * 空选择集生成的块等于"什么都不同步"，那会把整个文件夹静默停掉，
     * 用户还没勾过任何东西就先什么都收不到 —— 这种默认行为太危险。
     * 只把开关置为开，等用户在目录树上勾完再保存。
     */
    private fun toggleEnabled(want: Boolean) {
        if (want == enabled) {
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { repository.readIgnores() }
            val target = if (want) {
                if (!store.hasSelection(folderId)) {
                    null
                } else {
                    SelectiveSyncPatterns.buildStignore(
                        text,
                        store.selectedPaths(folderId),
                        store.fullDirs(folderId),
                    ).text
                }
            } else {
                SelectiveSyncPatterns.stripManagedBlock(text)
            }
            if (target != null) {
                val written = withContext(Dispatchers.IO) { repository.writeIgnores(target) }
                if (!written) {
                    message = "写入忽略规则失败，开关未生效。"
                    return@launch
                }
                withContext(Dispatchers.IO) { repository.rescan() }
            }
            store.setEnabled(folderId, want)
            enabled = want
            message = if (want) {
                if (target == null) {
                    "已开启选择性同步。请在目录树上勾选要同步的内容，然后保存。"
                } else {
                    "已开启选择性同步，沿用上次的选择集。"
                }
            } else {
                "已关闭选择性同步，恢复全部同步。"
            }
        }
    }
}
