package com.nutomic.syncthingandroid.selectivesync

import android.content.Context
import android.util.Log
import com.nutomic.syncthingandroid.model.BrowseEntry
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.util.ConfigRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** 删除结果：成功了哪些、失败了哪些、以及失败原因。 */
data class DeleteOutcome(
    val deleted: List<String>,
    val failed: List<String>,
    val reasons: Map<String, String> = emptyMap(),
)

/**
 * 选择性同步的数据层：把 REST 调用与本机磁盘操作收在一处。
 *
 * <p>两个"面"的分工和 SyncScope 里的 CompositeChannel 是一样的：
 * <ul>
 *   <li><b>信息面</b> —— REST：全局模型（browse）能看到"远端有、本机没下载"的目录，
 *       这正是"先勾选再下载"能成立的前提；</li>
 *   <li><b>动作面</b> —— 本机磁盘：直接列目录、直接删文件。Syncthing 的 REST 没有删除端点，
 *       但本应用就跑在持有这些文件的设备上，java.io.File 就够了。</li>
 * </ul>
 *
 * <p>读写 .stignore 走 ConfigRouter：Syncthing 在跑就走 REST，没跑就直接改 config 目录下的
 * .stignore 文件 —— 否则服务没启动时整个功能会不可用。
 */
class SelectiveSyncRepository(
    context: Context,
    val folderId: String,
) {

    /** 由 Activity 在服务连上后写入；为 null 时除 config.xml 之外的操作全部不可用。 */
    @Volatile
    var api: RestApi? = null

    /** 文件夹对象；需要 path 才能碰磁盘。 */
    @Volatile
    var folder: Folder? = null

    private val configRouter = ConfigRouter(context.applicationContext)

    /** 取全局模型里某一层的内容。 */
    suspend fun browse(prefix: String): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext emptyList<BrowseEntry>()
        awaitApi<List<BrowseEntry>> { a.getBrowse(folderId, prefix, it) } ?: emptyList()
    }

    /** 读当前 .stignore 全文。 */
    suspend fun readIgnores(): String = withContext(Dispatchers.IO) {
        val f = folder ?: return@withContext ""
        val list = awaitConfig<FolderIgnoreList> { configRouter.getFolderIgnoreList(api, f, it) }
        list?.ignore?.joinToString("\n") ?: ""
    }

    /**
     * 写 .stignore，并回读校验。
     *
     * <p>只发不验会留下"界面显示成功、规则其实没生效"的假象 —— 那种问题在真机上极难排查，
     * 所以宁可多花一次读取。但校验不能逐字全等，两个原因都会让它永远对不上：
     * <ol>
     *   <li>Syncthing 保存 .stignore 时会丢掉空行（空行不是有效规则）；
     *       [SelectiveSyncPatterns.buildStignore] 又刻意在托管块前插了一个空行；</li>
     *   <li>REST 的 POST 是 fire-and-forget，紧接着回读可能读到写入前的旧内容。</li>
     * </ol>
     * 所以归一化（去空行、逐行 trim）后比较，并给几次重试。
     */
    suspend fun writeIgnores(text: String): Boolean = withContext(Dispatchers.IO) {
        val f = folder ?: return@withContext false
        val lines = text.split("\n").toTypedArray()
        runCatching { configRouter.postFolderIgnoreList(api, f, lines) }
            .onFailure { Log.w(TAG, "writeIgnores: 写入失败", it) }
        val expected = normalizeIgnores(text)
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (normalizeIgnores(readIgnores()) == expected) {
                return@withContext true
            }
            if (attempt < VERIFY_ATTEMPTS - 1) {
                delay(VERIFY_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "writeIgnores: 回读校验未通过，期望 $expected")
        false
    }

    /** 归一化忽略规则文本：去空行、逐行 trim。用于写入后的回读比对。 */
    private fun normalizeIgnores(text: String): String =
        text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** 让 Syncthing 立刻重扫该文件夹。 */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        runCatching { api?.scanFolder(folderId) }
            .onFailure { Log.w(TAG, "rescan: 触发失败", it) }
    }

    /** 文件夹当前状态（"idle" / "scanning" / "syncing" ...），查不到时返回空串。 */
    suspend fun folderState(): String = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext ""
        awaitApi<FolderStatus> { a.getFolderStatus(folderId, it) }?.state ?: ""
    }

    /** 本机设备 ID。判断"别的设备有没有副本"时必须排除自己。 */
    fun localDeviceId(): String? = runCatching { api?.localDevice?.deviceID }.getOrNull()

    /**
     * 列本机磁盘上某一层的真实内容。
     *
     * <p>这是 browse 看不到的那一面：刚放进去、还没进索引的文件只在这里出现。
     */
    suspend fun listDisk(prefix: String): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val base = folder?.path ?: return@withContext emptyList<BrowseEntry>()
        val dir = if (prefix.isEmpty()) File(base) else File(base, prefix)
        if (!dir.isDirectory) return@withContext emptyList<BrowseEntry>()
        val kids = dir.listFiles() ?: return@withContext emptyList<BrowseEntry>()
        kids.map { f ->
            BrowseEntry().apply {
                name = f.name
                type = if (f.isDirectory) BrowseEntry.TYPE_DIRECTORY else TYPE_FILE
                size = if (f.isDirectory) 0L else f.length()
                modTime = ""
            }
        }
    }

    /**
     * 删除本机上的这些相对路径。
     *
     * <p>父路径优先（按长度升序）：先删目录，子项随之消失，
     * 避免把"已被父目录带走"误报成失败。
     */
    suspend fun delete(
        paths: List<String>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): DeleteOutcome = withContext(Dispatchers.IO) {
        val base = folder?.path
        if (base.isNullOrBlank()) {
            return@withContext DeleteOutcome(
                emptyList(),
                paths,
                mapOf("" to "文件夹路径不可用，无法删除"),
            )
        }
        val sorted = paths.sortedBy { it.length }
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        val reasons = LinkedHashMap<String, String>()
        var done = 0
        for (path in sorted) {
            val target = File(base, path)
            if (!target.exists()) {
                // 本机没有这一项，不算失败（可能已被父目录带走）
                done++
                onProgress?.invoke(done, sorted.size)
                continue
            }
            var ok = target.deleteRecursively()
            if (!ok && target.exists()) {
                // 可能正被 Syncthing 占用，稍等后重试一次
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                ok = target.deleteRecursively()
            }
            if (ok && !target.exists()) {
                deleted.add(path)
            } else {
                failed.add(path)
                reasons[path] = "删除失败：文件可能正被 Syncthing 占用，或没有写权限"
            }
            done++
            onProgress?.invoke(done, sorted.size)
        }
        DeleteOutcome(deleted, failed, reasons)
    }

    companion object {
        private const val TAG = "SelectiveSyncRepo"
        private const val TYPE_FILE = "FILE_INFO_TYPE_FILE"

        /** 回读校验次数：写入是异步的，第一次回读很可能还是旧内容。 */
        private const val VERIFY_ATTEMPTS = 3

        /** 两次回读之间的间隔。 */
        private const val VERIFY_RETRY_DELAY_MS = 400L
    }
}

/** Volley 的 5s 超时 × 5 次重试最坏约 30s，取这个上界。 */
private val REQUEST_TIMEOUT = 30.seconds

/**
 * 把 Volley 风格的回调转成挂起函数。
 *
 * <p>超时不是可选的：`ApiRequest` 传的错误回调什么都不做（{@code error -> {}}），
 * 请求失败时成功回调永远不会被调用，协程会一直挂起。
 *
 * <p>取消协程并不会取消已经排进队列的请求，所以把 continuation 放在 AtomicReference 里、
 * 取消时置空：既保证最多恢复一次，也断开"Volley 队列 → 回调 → 协程 → Activity"的引用链。
 */
private suspend fun <T> awaitApi(call: (RestApi.OnResultListener1<T>) -> Unit): T? =
    withTimeoutOrNull(REQUEST_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val pending = AtomicReference(continuation)
            continuation.invokeOnCancellation { pending.set(null) }
            call { value -> pending.getAndSet(null)?.resume(value) }
        }
    }

/** 同上，但针对 ConfigRouter 的回调（Syncthing 没跑时它走 config.xml，是同步回调）。 */
private suspend fun <T> awaitConfig(call: (ConfigRouter.OnResultListener1<T>) -> Unit): T? =
    withTimeoutOrNull(REQUEST_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val pending = AtomicReference(continuation)
            continuation.invokeOnCancellation { pending.set(null) }
            call { value -> pending.getAndSet(null)?.resume(value) }
        }
    }
