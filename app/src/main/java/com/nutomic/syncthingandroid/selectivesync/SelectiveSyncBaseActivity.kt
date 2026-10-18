package com.nutomic.syncthingandroid.selectivesync

import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.SelectiveSyncStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * 「选择性同步」与「校验文件」两个界面的公共部分。
 *
 * <p>两件事在这里做掉，子类就不用各写一遍：
 * <ul>
 *   <li>等 Syncthing 服务就绪并解析出文件夹对象 —— 没就绪时所有 REST 操作都不可用，
 *       界面必须显示"等待中"而不是给出一堆空结果让人误判；</li>
 *   <li>轮询文件夹状态 —— 正在扫描时索引在变，判定类操作必须挡住，
 *       否则"这项该不该删"就是拿一张还在改动的地图做的判断。</li>
 * </ul>
 */
abstract class SelectiveSyncBaseActivity : SyncthingActivity(),
    SyncthingService.OnServiceStateChangeListener {

    protected lateinit var repository: SelectiveSyncRepository
    protected lateinit var store: SelectiveSyncStore
    protected var folderId: String = ""

    /** REST 与文件夹对象都已就绪。 */
    protected var ready by mutableStateOf(false)

    /** Syncthing 正在扫描该文件夹。 */
    protected var folderScanning by mutableStateOf(false)

    /** 界面底部的一句话提示。 */
    protected var message by mutableStateOf<String?>(null)

    /** 文件夹名（没有 label 时退回 ID）。 */
    protected var folderTitle by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        folderId = intent.getStringExtra(EXTRA_FOLDER_ID).orEmpty()
        if (folderId.isEmpty()) {
            finish()
            return
        }
        store = SelectiveSyncStore(this)
        repository = SelectiveSyncRepository(this, folderId)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        service?.registerOnServiceStateChangeListener(this)
        refreshApi()
    }

    override fun onServiceStateChange(newState: SyncthingService.State) {
        refreshApi()
    }

    /** 服务状态一变就重新抓一次 api 与文件夹对象。 */
    private fun refreshApi() {
        val a = api?.takeIf { service?.currentState == SyncthingService.State.ACTIVE }
        repository.api = a
        val folder = a?.getFolders()?.firstOrNull { it.id == folderId }
        repository.folder = folder
        ready = a != null && folder != null
        if (folder != null) {
            folderTitle = folder.label.ifBlank { folder.id }
        }
    }

    override fun onDestroy() {
        service?.unregisterOnServiceStateChangeListener(this)
        super.onDestroy()
    }

    /** 只在前台轮询，屏幕不可见时自动停下。 */
    protected fun startStatePolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    folderScanning = repository.folderState() == STATE_SCANNING
                    delay(STATE_POLL_INTERVAL)
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOLDER_ID = "com.nutomic.syncthingandroid.selectivesync.FOLDER_ID"

        private const val STATE_SCANNING = "scanning"
        private val STATE_POLL_INTERVAL = 5.seconds
    }
}
