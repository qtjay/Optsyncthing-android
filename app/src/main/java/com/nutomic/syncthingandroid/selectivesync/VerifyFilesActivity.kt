package com.nutomic.syncthingandroid.selectivesync

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.nutomic.syncthingandroid.theme.ApplicationTheme

/**
 * 校验文件：按当前忽略规则，找出本机磁盘上本不该还在的东西并删除。
 *
 * <p>与「选择性同步」分工明确：那边只写规则，这边才动手删。
 */
class VerifyFilesActivity : SelectiveSyncBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (folderId.isEmpty()) {
            return
        }

        setContent {
            ApplicationTheme {
                VerifyFilesScreen(
                    repo = repository,
                    folderScanning = folderScanning,
                    ready = ready,
                    onBack = { finish() },
                    message = message,
                    onMessage = { message = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        startStatePolling()
    }
}
