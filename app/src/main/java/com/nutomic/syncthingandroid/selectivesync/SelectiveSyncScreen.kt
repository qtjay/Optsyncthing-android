package com.nutomic.syncthingandroid.selectivesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.model.BrowseEntry
import com.nutomic.syncthingandroid.util.SelectiveSyncPatterns
import com.nutomic.syncthingandroid.util.SelectiveSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录树中的一行。 */
private class TreeRow(
    val name: String,
    val path: String,
    val dir: Boolean,
    val depth: Int,
    var expanded: Boolean = false,
    var loading: Boolean = false,
)

/**
 * 勾选状态，分三桶保存 —— 对应 .stignore 里的两种放行写法：
 *  - [fullDirs]：整棵子树都同步；
 *  - [partialDirs]：只放行了其中一部分子项（目录本身要放行，子项各自算）；
 *  - [files]：单个文件被放行。
 */
private data class SelectionSets(
    val fullDirs: Set<String> = emptySet(),
    val partialDirs: Set<String> = emptySet(),
    val files: Set<String> = emptySet(),
) {
    fun all(): Set<String> = fullDirs + partialDirs + files
}

/**
 * 清理时永不触碰的 Syncthing 元数据。
 * .stignore 删了等于把选择性同步规则清空，.stfolder 删了文件夹会报错。
 */
internal val PROTECTED_NAMES = setOf(".stignore", ".stfolder", ".stversions")

/** 目录树还空着时的重试间隔：首次扫描通常要几十秒到几分钟。 */
private const val TREE_RETRY_MS = 10_000L

/** 重试多久就停下（10 分钟）。再扫不出来基本不是扫描慢，而是配置有问题。 */
private const val TREE_WAIT_MS = 600_000L

/**
 * 选择性同步主界面：目录树勾选 → 生成 .stignore 托管块。
 *
 * <p>移植自 SyncScope 的 FolderScreen。行为保持一致：
 * <ul>
 *   <li>勾选＝同步；取消勾选＝写进忽略规则，本机不再下载；</li>
 *   <li>本页<b>只写规则，不删文件</b>。删除一律交给「校验文件」，
 *       那边会先确认这项是不是真的该删，能挡住误删；</li>
 *   <li>刷新以目标端 .stignore 为准重新加载，未保存的勾选会丢失。</li>
 * </ul>
 */
@Composable
fun SelectiveSyncScreen(
    folderLabel: String,
    repo: SelectiveSyncRepository,
    store: SelectiveSyncStore,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    /** REST 与文件夹对象是否已就绪；未就绪时只能看缓存，不能操作。 */
    ready: Boolean,
    onOpenVerifyFiles: () -> Unit,
    onBack: () -> Unit,
    message: String?,
    onMessage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TreeRow>() }
    val fullDirs = remember { mutableStateMapOf<String, Boolean>() }
    val partialDirs = remember { mutableStateMapOf<String, Boolean>() }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }

    /** 打开时读到的基准勾选，用于计算"本次取消了哪些"。 */
    var baseline by remember { mutableStateOf(SelectionSets()) }
    /** 取消勾选触发的祖先拆分还没做完；保存前必须等它，否则写出去的是旧状态。 */
    var pendingToggle by mutableStateOf<Job?>(null)
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stignoreText by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }

    fun currentSelection(): SelectionSets = SelectionSets(
        fullDirs = fullDirs.keys.toSet(),
        partialDirs = partialDirs.keys.toSet(),
        files = selectedFiles.keys.toSet(),
    )

    fun isSelected(path: String): Boolean =
        fullDirs.containsKey(path) || partialDirs.containsKey(path) || selectedFiles.containsKey(path)

    /** 祖先里有"整棵子树都同步"的目录 —— 那自己也是同步的。 */
    fun hasSelectedAncestor(path: String): Boolean {
        val comps = path.split("/")
        var current = ""
        for (index in 0 until comps.size - 1) {
            current = if (current.isEmpty()) comps[index] else "$current/${comps[index]}"
            if (fullDirs.containsKey(current)) {
                return true
            }
        }
        return false
    }

    fun hasSelectedDescendant(path: String): Boolean {
        val prefix = "$path/"
        val all = fullDirs.keys + partialDirs.keys + selectedFiles.keys
        return all.any { it.startsWith(prefix) }
    }

    /** 已加载的直接子项（只下一层，不含更深的）。 */
    fun directChildren(path: String): List<TreeRow> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val out = ArrayList<TreeRow>()
        for (row in rows) {
            if (!row.path.startsWith(prefix)) {
                continue
            }
            val rest = row.path.substring(prefix.length)
            if (rest.isEmpty() || rest.contains("/")) {
                continue
            }
            out.add(row)
        }
        return out
    }

    /**
     * 某一行的三态。
     *
     * <p>目录的状态要由它**已加载的子项**算出来，不能只看"自己在不在某个桶里"：
     * 父目录整棵放行后取消一个子项，只看桶会一直显示全勾，实际应该是半选。
     */
    fun stateOf(row: TreeRow): TriState {
        if (hasSelectedAncestor(row.path)) {
            return TriState.ON
        }
        if (!row.dir) {
            return if (selectedFiles.containsKey(row.path)) TriState.ON else TriState.OFF
        }
        if (fullDirs.containsKey(row.path)) {
            return TriState.ON
        }
        val kids = directChildren(row.path)
        // 没展开过就没有子项可算，退回按桶判断
        if (kids.isEmpty()) {
            return if (partialDirs.containsKey(row.path)) TriState.PARTIAL else TriState.OFF
        }
        val on = kids.count { stateOf(it) == TriState.ON }
        return when {
            on == 0 -> TriState.OFF
            on == kids.size -> TriState.ON
            else -> TriState.PARTIAL
        }
    }

    /** 已加载的行里查它是目录还是文件；没加载到就返回 null。 */
    fun dirKind(path: String): Boolean? = rows.firstOrNull { it.path == path }?.dir

    /** 一整棵子树都勾上（目录）。 */
    fun markFull(path: String) {
        partialDirs.remove(path)
        fullDirs[path] = true
    }

    fun markSelected(path: String) {
        when (dirKind(path)) {
            true -> markFull(path)
            false -> selectedFiles[path] = true
            // 没展开到这一层的路径保持"只放行本身"，语义与原规则一致
            else -> partialDirs[path] = true
        }
    }

    /**
     * 取某一层的内容。
     *
     * <p>远端（全局模型）为准，本机磁盘上独有的补在后面 —— 新放进去、还没进索引的东西
     * 也要显示在树上（默认未勾选），否则用户根本不知道它存在。
     */
    suspend fun fetchChildren(prefix: String): List<TreeRow> {
        val remote = withContext(Dispatchers.IO) { repo.browse(prefix) }
        val local = withContext(Dispatchers.IO) { repo.listDisk(prefix) }
        val merged = LinkedHashMap<String, BrowseEntry>()
        for (entry in remote) {
            val name = entry.name
            if (!name.isNullOrEmpty() && !PROTECTED_NAMES.contains(name)) {
                merged[name] = entry
            }
        }
        for (entry in local) {
            val name = entry.name
            if (!name.isNullOrEmpty() && !merged.containsKey(name) && !PROTECTED_NAMES.contains(name)) {
                merged[name] = entry
            }
        }
        val depth = if (prefix.isEmpty()) 0 else prefix.split("/").size
        return merged.values
            .filter { !it.name.isNullOrEmpty() }
            .map { entry ->
                val name = entry.name!!
                TreeRow(
                    name = name,
                    path = if (prefix.isEmpty()) name else "$prefix/$name",
                    dir = entry.isDirectory(),
                    depth = depth,
                )
            }
            .sortedWith(compareBy({ !it.dir }, { it.name }))
    }

    /** 按当前展开状态重新拉取所有可见行。 */
    suspend fun refreshTree() {
        val expanded = rows.filter { it.expanded }.map { it.path }.toSet()
        val built = ArrayList<TreeRow>()
        suspend fun walk(prefix: String) {
            for (row in fetchChildren(prefix)) {
                row.expanded = row.dir && expanded.contains(row.path)
                built.add(row)
                if (row.expanded) {
                    walk(row.path)
                }
            }
        }
        walk("")
        rows.clear()
        rows.addAll(built)
    }

    /**
     * 把反解出来的路径还原成目录树里的真实路径。
     *
     * <p>目录树里找不到原样匹配时，按 glob 语义找：规则里的 `?` 匹配任意单字符，
     * 这正是生成侧对"名称以 ( 开头"的兜底写法。
     */
    fun realPath(path: String): String {
        if (rows.any { it.path == path }) {
            return path
        }
        return rows.firstOrNull { patternMatchesPath(path, it.path) }?.path ?: path
    }

    /**
     * 把勾选集合的 key 对齐到目录树里的真实路径。
     *
     * <p>反解出来的路径可能是退化形式（名称以 `(` 开头被写成 `?`）。
     * 当时目录树还没加载到那一层时无法还原；等展开加载到之后再对齐一次，
     * 否则界面上显示"没勾选"，而 .stignore 里规则其实已经生效。
     *
     * <p>只改 key，不动勾选结果 —— 对齐前后"勾了哪些项"是完全一样的。
     */
    fun alignSelectionKeys() {
        if (rows.isEmpty()) {
            return
        }
        fun align(map: SnapshotStateMap<String, Boolean>) {
            for (key in map.keys.toList()) {
                val real = realPath(key)
                if (real != key) {
                    map.remove(key)
                    map[real] = true
                }
            }
        }
        align(fullDirs)
        align(partialDirs)
        align(selectedFiles)
    }

    /**
     * 把反解出来的勾选写回界面。
     *
     * <p>没有托管块 = 没有忽略规则 = 全部同步 → 所有已加载的行都打勾。
     */
    fun applyParsed(parsed: SelectiveSyncPatterns.Selection) {
        fullDirs.clear()
        partialDirs.clear()
        selectedFiles.clear()
        paused = parsed.paused
        if (!parsed.hasBlock) {
            for (row in rows) {
                if (row.dir) fullDirs[row.path] = true else selectedFiles[row.path] = true
            }
            return
        }
        for (path in parsed.fullDirs) {
            fullDirs[realPath(path)] = true
        }
        for (path in parsed.selectedPaths) {
            if (parsed.fullDirs.contains(path)) {
                continue
            }
            // 必须先还原成目录树里的真实名字再判断类型：
            // 退化路径在 rows 里查不到，dirKind 会返回 null 把文件误放进 partialDirs
            val real = realPath(path)
            when (dirKind(real)) {
                true -> partialDirs[real] = true
                false -> selectedFiles[real] = true
                else -> partialDirs[real] = true
            }
        }
        alignSelectionKeys()
    }

    /** 保存之后用：目录结构没动过，只有规则变了，所以只读回 .stignore 反解。 */
    suspend fun refreshFromRemote() {
        stignoreText = withContext(Dispatchers.IO) { repo.readIgnores() }
        refreshTree()
        applyParsed(SelectiveSyncPatterns.parseManagedBlock(stignoreText))
    }

    suspend fun refreshRulesOnly() {
        stignoreText = withContext(Dispatchers.IO) { repo.readIgnores() }
        applyParsed(SelectiveSyncPatterns.parseManagedBlock(stignoreText))
    }

    fun persist() {
        val current = currentSelection()
        store.save(repo.folderId, current.all().toList(), current.fullDirs)
    }

    fun buildStignoreText(): String {
        val allOn = rows.isNotEmpty() && rows.all { stateOf(it) == TriState.ON }
        if (allOn) {
            // 全部打勾 = 不需要任何忽略规则，移除本应用生成的块（用户自己的规则保留）
            return SelectiveSyncPatterns.stripManagedBlock(stignoreText)
        }
        val all = ArrayList<String>()
        all.addAll(fullDirs.keys)
        all.addAll(partialDirs.keys)
        all.addAll(selectedFiles.keys)
        return SelectiveSyncPatterns.buildStignore(stignoreText, all, fullDirs.keys.toSet()).text
    }

    /** 已经下钻过、但下面没有任何勾选的目录，不必再保留"只放行本身"。 */
    fun pruneEmptyPartialDirs() {
        val expanded = rows.filter { it.expanded }.map { it.path }.toSet()
        for (path in partialDirs.keys.toList()) {
            if (expanded.contains(path) && !hasSelectedDescendant(path)) {
                partialDirs.remove(path)
            }
        }
    }

    /**
     * 取消勾选前，先把"整棵子树被放行"的祖先逐个拆成逐项放行。
     *
     * <p>不拆的话，那条"放行父目录整棵子树"的规则会把子项一起放行 ——
     * .stignore 首个匹配生效，父规则先命中就轮不到子项，表现就是"子目录怎么点都取消不掉"。
     */
    suspend fun materializeAncestors(path: String) {
        val comps = path.split("/")
        for (depth in 1 until comps.size) {
            val ancestor = comps.subList(0, depth).joinToString("/")
            if (!fullDirs.containsKey(ancestor)) {
                continue
            }
            val children = fetchChildren(ancestor)
            if (children.isEmpty()) {
                continue
            }
            for (child in children) {
                if (child.dir) {
                    fullDirs[child.path] = true
                } else {
                    selectedFiles[child.path] = true
                }
                partialDirs.remove(child.path)
            }
            fullDirs.remove(ancestor)
            partialDirs[ancestor] = true
        }
    }

    /** 把这一项连同它的后代一起从勾选里摘掉。 */
    fun removeWithDescendants(row: TreeRow) {
        fullDirs.remove(row.path)
        partialDirs.remove(row.path)
        selectedFiles.remove(row.path)
        if (!row.dir) {
            return
        }
        val prefix = row.path + "/"
        for (path in fullDirs.keys.toList()) {
            if (path.startsWith(prefix)) fullDirs.remove(path)
        }
        for (path in partialDirs.keys.toList()) {
            if (path.startsWith(prefix)) partialDirs.remove(path)
        }
        for (path in selectedFiles.keys.toList()) {
            if (path.startsWith(prefix)) selectedFiles.remove(path)
        }
    }

    fun onToggle(row: TreeRow, wantSelected: Boolean) {
        if (wantSelected) {
            if (row.dir) {
                markFull(row.path)
            } else {
                selectedFiles[row.path] = true
            }
            persist()
            return
        }
        pendingToggle = scope.launch {
            materializeAncestors(row.path)
            removeWithDescendants(row)
            pruneEmptyPartialDirs()
            persist()
        }
    }

    fun loadChildren(parentIndex: Int, parent: TreeRow?) {
        val prefix = parent?.path.orEmpty()
        if (parent != null) {
            parent.loading = true
        }
        scope.launch {
            val created = fetchChildren(prefix)
            val parentDepth = parent?.depth ?: -1
            val insertAt = parentIndex + 1
            while (rows.size > insertAt && rows[insertAt].depth > parentDepth) {
                rows.removeAt(insertAt)
            }
            rows.addAll(insertAt, created)
            if (parent != null) {
                parent.loading = false
            }
            // 新加载的行可能正好是之前无法还原的退化路径，对齐一次让勾选状态显示正确
            alignSelectionKeys()
            persist()
        }
    }

    fun collapse(index: Int, row: TreeRow) {
        row.expanded = false
        var removeAt = index + 1
        while (rows.size > removeAt && rows[removeAt].depth > row.depth) {
            rows.removeAt(removeAt)
        }
        persist()
    }

    fun onRowClick(index: Int, row: TreeRow) {
        if (!row.dir) {
            return
        }
        if (row.expanded) {
            collapse(index, row)
        } else {
            row.expanded = true
            loadChildren(index, row)
        }
    }

    /** 只保留顶层路径：a 与 a/b 同时被取消时，删掉 a 就够了。 */
    fun topLevelPaths(paths: Set<String>): List<String> =
        paths.filter { path -> paths.none { other -> other != path && path.startsWith("$other/") } }
            .sortedBy { it.length }

    fun save() {
        if (rows.isEmpty()) {
            onMessage("目录树还没有内容，先点刷新取一次再保存")
            return
        }
        busy = true
        onMessage(null)
        scope.launch {
            // 取消勾选会先拆分祖先（要拉一次目录树），保存前必须等它做完，
            // 否则写出去的是拆分前的旧状态 —— 表现就是"刚取消的又同步了"。
            pendingToggle?.join()
            val text = buildStignoreText()
            val written = withContext(Dispatchers.IO) { repo.writeIgnores(text) }
            if (!written) {
                onMessage("写入 .stignore 失败，请重试；多次失败可先刷新再保存")
                busy = false
                return@launch
            }
            // 修改 .stignore 本身就会让 Syncthing 重新扫描，这里再显式触发一次
            withContext(Dispatchers.IO) { repo.rescan() }
            // 本次取消勾选的：拿打开时读到的规则做差集
            val removed = topLevelPaths(baseline.all() - currentSelection().all())
            refreshRulesOnly()
            baseline = currentSelection()
            persist()
            // 这一页只负责写规则，删除一律交给「校验文件」
            onMessage(
                if (removed.isEmpty()) {
                    "已保存"
                } else {
                    "已保存并写入忽略规则。本次取消勾选 ${removed.size} 项，" +
                        "等 Syncthing 完成扫描（修改忽略规则文件会触发扫描）后，" +
                        "记得去「校验文件」删除这些不再同步的文件。"
                },
            )
            busy = false
        }
    }

    /**
     * 暂停下载：立刻写入"忽略一切"的托管块，本机不再下载任何内容。
     *
     * <p>场景：新加的文件夹还没被扫进全局模型时，browse 是空的、无从勾选，
     * 而等扫出来这段时间本机已经在下载，等能勾时文件已经下来一大堆。
     */
    fun pauseDownload() {
        pausing = true
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                repo.writeIgnores(SelectiveSyncPatterns.buildPausedStignore(stignoreText).text)
            }
            if (!written) {
                onMessage("写入忽略规则失败，未能暂停下载。")
                pausing = false
                return@launch
            }
            withContext(Dispatchers.IO) { repo.rescan() }
            refreshRulesOnly()
            onMessage(
                "已暂停下载，本机不会下载任何内容。" +
                    "等目录结构出现后勾选要同步的内容，保存即开始下载。",
            )
            pausing = false
        }
    }

    LaunchedEffect(ready, repo.folderId) {
        if (!ready) {
            return@LaunchedEffect
        }
        loading = true
        // 先把上次保存的选择集填进来，避免白屏干等
        if (store.hasSelection(repo.folderId)) {
            fullDirs.clear()
            store.fullDirs(repo.folderId).forEach { fullDirs[it] = true }
            partialDirs.clear()
            selectedFiles.clear()
            for (path in store.selectedPaths(repo.folderId)) {
                if (fullDirs.containsKey(path)) {
                    continue
                }
                partialDirs[path] = true
            }
        }
        refreshFromRemote()
        baseline = currentSelection()
        persist()
        loading = false

        // 目录结构还空着就定时重试：新加的文件夹要等本机扫完才有内容
        var waited = 0L
        while (rows.isEmpty() && waited < TREE_WAIT_MS) {
            delay(TREE_RETRY_MS)
            waited += TREE_RETRY_MS
            if (rows.isEmpty()) {
                loading = true
                refreshFromRemote()
                baseline = currentSelection()
                loading = false
            }
        }
    }

    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "选择性同步",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderLabel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggleEnabled, enabled = !busy)
        }

        Text(
            "关闭＝全部同步（移除本功能生成的规则块，你自己写的规则保留）。" +
                "开启后，只有勾上的目录/文件会同步到本机。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("目录树", modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    scope.launch {
                        refreshing = true
                        refreshFromRemote()
                        baseline = currentSelection()
                        persist()
                        refreshing = false
                    }
                },
                enabled = ready && !refreshing && !busy,
            ) {
                Text(if (refreshing) "刷新中…" else "刷新")
            }
            OutlinedButton(
                onClick = {
                    val allRowsOn = rows.isNotEmpty() && rows.all { stateOf(it) == TriState.ON }
                    if (allRowsOn) {
                        fullDirs.clear()
                        partialDirs.clear()
                        selectedFiles.clear()
                    } else {
                        for (row in rows) {
                            if (row.dir) markFull(row.path) else selectedFiles[row.path] = true
                        }
                    }
                    persist()
                },
                enabled = rows.isNotEmpty(),
            ) {
                val allRowsOn = rows.isNotEmpty() && rows.all { stateOf(it) == TriState.ON }
                Text(if (allRowsOn) "全取消" else "全选")
            }
        }

        when {
            !ready -> {
                Text(
                    "正在等待 Syncthing 就绪…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }

            loading && rows.isEmpty() -> {
                Text("加载中…", modifier = Modifier.padding(16.dp))
            }

            rows.isEmpty() -> {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    if (paused) {
                        Text("已暂停下载，本机不会下载任何内容。")
                        Text(
                            "等本机扫描完成，这里会自动出现目录结构；" +
                                "届时勾选要同步的内容，保存即开始下载。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("还没有目录结构，本机可能还在首次扫描。")
                        Text(
                            "可以先暂停下载，免得文件先下来一大堆；" +
                                "等目录结构出现后再勾选要同步的内容。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { pauseDownload() },
                            enabled = !pausing && !busy,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(if (pausing) "正在暂停…" else "暂停下载")
                        }
                    }
                    TextButton(
                        onClick = { scope.launch { refreshFromRemote() } },
                        enabled = !loading && !busy,
                    ) {
                        Text("立即重试")
                    }
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(rows) { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRowClick(index, row) }
                                .padding(
                                    horizontal = (4 + row.depth * 16).dp,
                                    vertical = 2.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriCheckbox(
                                state = stateOf(row),
                                onToggle = { onToggle(row, it) },
                            )
                            val label = when {
                                row.dir && row.loading -> "${row.name}/ …"
                                row.dir -> "${row.name}/"
                                else -> row.name
                            }
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { save() },
                enabled = ready && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }
            OutlinedButton(
                onClick = onOpenVerifyFiles,
                enabled = ready && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("校验文件")
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
