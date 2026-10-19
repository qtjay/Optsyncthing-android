package com.nutomic.syncthingandroid.selectivesync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.util.SelectiveSyncPatterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校验时最多往下钻几层。
 *
 * 下钻是为了确认"整棵是否都安全"，但每层都要列一次目录，深度越大请求越多。
 * 超过这个深度就不再自动判定，留给用户点展开时才继续。
 */
private const val SCAN_MAX_DEPTH = 4

/**
 * 多余项的分类，决定默认勾不勾、以及要提示用户什么。
 *
 * <p>顺序即展示顺序：ROOT_NEW 必须排在最前面先提醒用户。
 *
 * <p>判断刻意做得很简单：目录被忽略本身就说明用户不打算要它，正常流程也是
 * 忽略后立刻来这里删掉，里面根本没机会再新增东西。所以**凡是被 .stignore
 * 覆盖的一律直接删**，只有"根目录下新增"这一种情况要单独标记 ——
 * 那通常是用户刚放进去、还没来得及勾选的，得让他自己看一眼。
 */
internal enum class ExcessKind {
    /** 根目录下新增、且远端索引里还没有 —— 需用户确认，默认不勾。 */
    ROOT_NEW,

    /** 已被忽略规则覆盖 —— 按定义就该删掉，默认勾上。 */
    IGNORED,
}

/** 一个"按当前勾选本不该存在"的条目，连同它的判定结果。 */
internal data class ExcessItem(
    val path: String,
    val dir: Boolean,
    /** 磁盘上的大小；目录为 -1。 */
    val size: Long,
    val kind: ExcessKind,
    val note: String,
)

/**
 * 当前生效的勾选集合，两项足够判定"多余的"：
 *  - [fullDirs]：整棵子树都放行；
 *  - [selected]：所有被放行的路径（含整棵放行的目录、只放行了部分子项的目录、单个文件）。
 */
internal data class ExcessSelection(
    val fullDirs: Set<String>,
    val selected: Set<String>,
)

/**
 * 给一个多余项分类。
 *
 * <p>只有一种情况需要单独标记：位于**根目录**、且远端索引里查不到它 ——
 * 那多半是用户刚放进去、还没勾选的东西。
 */
private fun classifyExcess(
    path: String,
    dir: Boolean,
    localSize: Long,
    knownToRemote: Boolean,
): ExcessItem {
    val atRoot = !path.contains("/")
    if (atRoot && !knownToRemote) {
        return ExcessItem(
            path, dir, localSize, ExcessKind.ROOT_NEW,
            "根目录下新增，索引里还没有它 —— 请确认是要保留还是要删除",
        )
    }
    return ExcessItem(path, dir, localSize, ExcessKind.IGNORED, "已被忽略规则覆盖")
}

/**
 * 判断某个真实存在的路径是否"多余"—— 按当前勾选它本不该出现在磁盘上。
 *
 * <p>比较必须走 [patternMatchesPath]：规则里的路径可能是退化形式
 * （名称以 `(` 开头被写成 `?`）。直接用字符串相等会把已勾选的整棵目录
 * 判成多余、建议删掉 —— 那是真的会丢数据的。
 *
 * 只有三种情况要保留：整棵子树被放行、自己被放行、
 * 或者是通往某个被放行项的中间目录（没有它里面的选中项也进不去）。
 */
private fun isExcess(path: String, dir: Boolean, selection: ExcessSelection): Boolean {
    if (selection.fullDirs.any { patternMatchesPath(it, path) }) return false
    if (selection.selected.any { patternMatchesPath(it, path) }) return false
    var slash = path.indexOf('/')
    while (slash > 0) {
        if (selection.fullDirs.any { patternMatchesPath(it, path.substring(0, slash)) }) return false
        slash = path.indexOf('/', slash + 1)
    }
    // 目录还可能是"通往被放行项的中间层"
    if (dir) {
        val prefix = "$path/"
        if (selection.selected.any { it.startsWith(prefix) }) return false
    }
    return true
}

private data class Hit(
    val path: String,
    val dir: Boolean,
    val size: Long,
    val depth: Int,
    val knownToRemote: Boolean,
)

/**
 * 扫描"按当前规则本不该存在、却仍留在本机磁盘上"的条目。
 *
 * <p>判定走 .stignore 反解出来的勾选集合，与生成规则用的是同一份数据，
 * 不会出现"规则说忽略、界面说同步"的口径打架。命中整棵子树的目录不再往下钻 ——
 * 整棵都是多余的，直接整棵删。
 *
 * <p><b>下钻条件不能用"磁盘 vs 索引"的指纹</b>：被忽略的文件**仍然在全局模型里**，
 * 所以一个已经同步的目录，磁盘内容和索引内容永远一致，指纹永远相同 ——
 * 用它剪枝会导致永远发现不了深层被忽略的子项（实测表现就是"校验文件扫不到任何文件"）。
 * 正确依据是勾选状态本身：整棵放行的目录不必下钻，其余一律下钻。
 */
private suspend fun scanExcess(
    repo: SelectiveSyncRepository,
    selection: ExcessSelection,
    onProgress: (scanned: Int, pending: Int, found: Int) -> Unit,
): List<ExcessItem> = withContext(Dispatchers.IO) {
    val hits = ArrayList<Hit>()
    var level: List<Pair<String, Int>> = listOf("" to 0)
    var scanned = 0
    while (level.isNotEmpty()) {
        val next = ArrayList<Pair<String, Int>>()
        for ((prefix, depth) in level) {
            val disk = repo.listDisk(prefix)
            val remote = repo.browse(prefix)
            val remoteNames = remote.mapNotNull { it.name }.toSet()
            for (entry in disk) {
                val name = entry.name ?: continue
                if (name.isEmpty() || PROTECTED_NAMES.contains(name)) continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val dir = entry.isDirectory()
                if (isExcess(path, dir, selection)) {
                    // 命中即停：整棵都是被忽略的，删掉父目录就够了，不必再逐条看
                    hits.add(Hit(path, dir, if (dir) -1L else entry.size, depth, remoteNames.contains(name)))
                } else if (dir && depth < SCAN_MAX_DEPTH &&
                    selection.fullDirs.none { patternMatchesPath(it, path) }) {
                    // 整棵放行的目录不必下钻（里面必然全部同步）；
                    // 其余一律下钻 —— 被忽略的子项不体现在"磁盘 vs 索引"的差异里。
                    next.add(path to depth + 1)
                }
            }
        }
        scanned += level.size
        onProgress(scanned, next.size, hits.size)
        level = next
    }

    val classified = hits.map {
        classifyExcess(it.path, it.dir, it.size, it.knownToRemote)
    }

    // 只保留顶层：父目录已经列出来了，里面几十上百条再逐条铺开，列表会长到没法看，
    // 用户还得一条条确认才敢下手。要看细节就点「展开」。
    val topOnly = classified.filter { item ->
        classified.none { other ->
            other.dir && other.path != item.path && item.path.startsWith("${other.path}/")
        }
    }
    topOnly.sortedWith(compareBy({ it.kind.ordinal }, { it.path }))
}

/** 只保留顶层路径：勾了 a 又勾了 a/b 时，删掉 a 就够了。 */
private fun topLevelPaths(paths: Set<String>): List<String> =
    paths.filter { path -> paths.none { other -> other != path && path.startsWith("$other/") } }
        .sortedBy { it.length }

/** 扁平化后的列表行：分组标题与条目混在一张表里，避免 LazyListScope.item() 的 DSL 限制。 */
private sealed class CleanupRow {
    data class Header(val kind: ExcessKind) : CleanupRow()
    data class Entry(val item: ExcessItem, val depth: Int) : CleanupRow()
}

/** 每种分类的小标题，让"危险的"和"安全的"一眼分得开。 */
@Composable
private fun ExcessGroupHeader(kind: ExcessKind) {
    val title = when (kind) {
        ExcessKind.ROOT_NEW -> "⚠ 根目录下新增 —— 默认不勾，先确认是新放的还是要删"
        ExcessKind.IGNORED -> "✓ 已被忽略规则覆盖 —— 默认勾上，直接删除"
    }
    val color = when (kind) {
        ExcessKind.ROOT_NEW -> MaterialTheme.colorScheme.error
        ExcessKind.IGNORED -> MaterialTheme.colorScheme.primary
    }
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 「校验文件」：按当前忽略规则，找出本机磁盘上本不该还在的东西并删除。
 *
 * <p>与「选择性同步」分工明确：那边只写规则，这边才动手删。
 * 删除前用索引确认过"它是不是真的被忽略了"，能挡住误删正在同步的文件。
 */
@Composable
fun VerifyFilesScreen(
    repo: SelectiveSyncRepository,
    /** Syncthing 正在扫描该文件夹 —— 此时索引在变，判定不可靠，必须拒绝操作。 */
    folderScanning: Boolean,
    ready: Boolean,
    onBack: () -> Unit,
    message: String?,
    onMessage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var excess by remember { mutableStateOf(emptyList<ExcessItem>()) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** 已展开的目录路径 —— 需要"保留某个子项"时展开它再单独取消。 */
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    /** 展开过的目录的子项，按父路径缓存。 */
    var children by remember { mutableStateOf(emptyMap<String, List<ExcessItem>>()) }

    /**
     * 勾选/取消一项，并维护父子联动。
     *
     * <p>默认目录是整棵勾上的（否则删完会留一地空目录）。
     * 只有用户在展开后取消某个子项时，才把父目录从"整棵删"降级成"逐项删"。
     */
    fun toggle(path: String, want: Boolean) {
        if (want) {
            chosen = chosen + path
            // 父整棵勾上后，子项不需要再各自勾（删父就够）
            val kids = children[path]
            if (kids != null) {
                chosen = chosen - kids.map { it.path }.toSet()
            }
            return
        }
        chosen = chosen - path
        val parent = path.substringBeforeLast('/', "")
        // 取消子项时，父目录不能再整棵删 —— 降级为逐项删
        if (parent.isNotBlank() && chosen.contains(parent)) {
            chosen = chosen - parent
            val siblings = children[parent]
            if (siblings != null) {
                chosen = chosen + siblings.filter { it.path != path }.map { it.path }.toSet()
            }
        }
    }

    /** 展开/收起一个目录，首次展开时加载其子项。 */
    fun toggleExpand(item: ExcessItem) {
        if (!item.dir) {
            return
        }
        val path = item.path
        if (children.containsKey(path)) {
            expanded = if (expanded.contains(path)) expanded - path else expanded + path
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val entries = repo.listDisk(path)
                val remote = repo.browse(path)
                val remoteNames = remote.mapNotNull { it.name }.toSet()
                entries.mapNotNull { entry ->
                    val name = entry.name
                    if (name.isNullOrEmpty() || PROTECTED_NAMES.contains(name)) {
                        null
                    } else {
                        val dir = entry.isDirectory()
                        classifyExcess(
                            "$path/$name", dir,
                            if (dir) -1L else entry.size,
                            remoteNames.contains(name),
                        )
                    }
                }
            }
            children = children + (path to loaded)
            expanded = expanded + path
        }
    }

    /** 把保留下来的文件纳入同步 —— 改 .stignore 放行它们。 */
    fun includeInSync(paths: List<String>, dirs: Set<String>) {
        if (paths.isEmpty()) {
            return
        }
        scope.launch {
            val text = withContext(Dispatchers.IO) { repo.readIgnores() }
            val parsed = SelectiveSyncPatterns.parseManagedBlock(text)
            val selected = parsed.selectedPaths.toMutableList()
            val full = parsed.fullDirs.toMutableSet()
            for (path in paths) {
                if (!selected.contains(path)) {
                    selected.add(path)
                }
                if (dirs.contains(path)) {
                    full.add(path)
                }
            }
            val written = withContext(Dispatchers.IO) {
                repo.writeIgnores(SelectiveSyncPatterns.buildStignore(text, selected, full).text)
            }
            if (!written) {
                onMessage("纳入同步失败：忽略规则没写成")
                return@launch
            }
            withContext(Dispatchers.IO) { repo.rescan() }
            excess = excess.filter { it.path !in paths }
            chosen = chosen - paths.toSet()
            onMessage("已把 ${paths.size} 项纳入同步")
        }
    }

    fun scan() {
        if (!ready) {
            return
        }
        if (folderScanning) {
            onMessage("Syncthing 正在扫描该文件夹，等扫描完成后再校验。")
            return
        }
        scanning = true
        excess = emptyList()
        chosen = emptySet()
        expanded = emptySet()
        children = emptyMap()
        progressLabel = "扫描目录…"
        scope.launch {
            // 没有托管块 = 全部同步，磁盘上不存在"多余文件"
            val text = withContext(Dispatchers.IO) { repo.readIgnores() }
            if (!SelectiveSyncPatterns.hasManagedBlock(text)) {
                excess = emptyList()
                chosen = emptySet()
                scanning = false
                progressLabel = null
                return@launch
            }
            val parsed = SelectiveSyncPatterns.parseManagedBlock(text)
            val selection = ExcessSelection(
                fullDirs = parsed.fullDirs,
                selected = parsed.selectedPaths.toSet(),
            )
            val found = scanExcess(repo, selection) { done, _, hits ->
                progressLabel = "已扫 $done 个目录，发现 $hits 项待校验"
            }
            excess = found
            // 目录也一起勾：只删文件不删目录会留下一地空目录。
            // 被忽略的一律勾上；根目录下新增的不勾，交给用户确认
            chosen = found.filter { it.kind == ExcessKind.IGNORED }.map { it.path }.toSet()
            scanning = false
            progressLabel = null
        }
    }

    LaunchedEffect(ready, folderScanning) {
        if (ready && !folderScanning) {
            scan()
        }
    }

    // 分组标题混进数据里：LazyListScope.item() 不能在 items 的 lambda 里调用
    val rows = remember(excess, expanded, children) {
        val out = ArrayList<CleanupRow>()
        var last: ExcessKind? = null
        fun walk(items: List<ExcessItem>, depth: Int) {
            for (item in items) {
                if (depth == 0 && item.kind != last) {
                    out.add(CleanupRow.Header(item.kind))
                    last = item.kind
                }
                out.add(CleanupRow.Entry(item, depth))
                if (item.dir && expanded.contains(item.path)) {
                    val kids = children[item.path]
                    if (!kids.isNullOrEmpty()) {
                        walk(kids, depth + 1)
                    }
                }
            }
        }
        walk(excess, 0)
        out
    }

    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                "校验文件",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (folderScanning) {
            Text(
                "Syncthing 正在扫描该文件夹，此时索引还在变动，" +
                    "判断结果不可靠 —— 已暂停扫描与删除，请等扫描完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Text(
            "按当前规则，下面这些文件本不该还留在本机（已忽略但磁盘上还在）。" +
                "危险的分在前面且不勾，安全的已默认勾上。" +
                "要保留某个子项，展开它的父目录再单独取消即可。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedButton(
                onClick = { scan() },
                enabled = ready && !scanning && !deleting && !folderScanning,
            ) {
                Text(if (scanning) "扫描中…" else "重新扫描")
            }
            TextButton(
                onClick = {
                    val all = excess.map { it.path }.toSet()
                    chosen = if (chosen.size == all.size) emptySet() else all
                },
                enabled = excess.isNotEmpty() && !deleting,
            ) {
                Text(if (chosen.size == excess.size && excess.isNotEmpty()) "全不选" else "全选")
            }
            // 恢复默认：勾上所有被忽略的，根目录新增的保持不勾
            TextButton(
                onClick = {
                    val picked = excess.filter { it.kind == ExcessKind.IGNORED }
                        .map { it.path }.toSet()
                    chosen = picked.filter { path ->
                        picked.none { other -> other != path && path.startsWith("$other/") }
                    }.toSet()
                },
                enabled = excess.isNotEmpty() && !deleting,
            ) {
                Text("只选被忽略的")
            }
        }

        progressLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        when {
            scanning && excess.isEmpty() -> {
                Text("扫描中…", modifier = Modifier.padding(16.dp))
            }

            excess.isEmpty() -> {
                Text(
                    "没有需要处理的条目，本机内容与当前勾选一致。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(
                        rows,
                        key = { row ->
                            when (row) {
                                is CleanupRow.Header -> "hdr_" + row.kind
                                is CleanupRow.Entry -> row.item.path
                            }
                        },
                    ) { row ->
                        when (row) {
                            is CleanupRow.Header -> ExcessGroupHeader(row.kind)
                            is CleanupRow.Entry -> {
                                val entry = row.item
                                val on = chosen.contains(entry.path)
                                val open = expanded.contains(entry.path)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (row.depth * 16).dp)
                                        .clickable(enabled = !deleting) {
                                            toggle(entry.path, !on)
                                        }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Checkbox(
                                        checked = on,
                                        onCheckedChange = { want -> toggle(entry.path, want) },
                                        enabled = !deleting,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.path + if (entry.dir) "/" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (entry.kind) {
                                                ExcessKind.ROOT_NEW ->
                                                    MaterialTheme.colorScheme.error
                                                ExcessKind.IGNORED ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    if (entry.dir) {
                                        TextButton(
                                            onClick = { toggleExpand(entry) },
                                            enabled = !deleting && !folderScanning,
                                        ) {
                                            Text(if (open) "收起" else "展开")
                                        }
                                    }
                                    if (entry.kind == ExcessKind.ROOT_NEW) {
                                        TextButton(
                                            onClick = {
                                                includeInSync(
                                                    listOf(entry.path),
                                                    if (entry.dir) setOf(entry.path) else emptySet(),
                                                )
                                            },
                                            enabled = !deleting && !folderScanning,
                                        ) {
                                            Text("保留")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (excess.isNotEmpty()) {
            Button(
                onClick = { confirmDelete = true },
                enabled = chosen.isNotEmpty() && !deleting && !folderScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (deleting) "删除中…" else "删除选中的 ${chosen.size} 项")
            }
            OutlinedButton(
                onClick = {
                    includeInSync(
                        excess.map { it.path },
                        excess.filter { it.dir }.map { it.path }.toSet(),
                    )
                },
                enabled = !deleting && !folderScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("把保留的 ${excess.size} 项纳入同步")
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    "将从本机删除 ${chosen.size} 项，删除后无法恢复：\n" +
                        chosen.joinToString("\n") { "· " + it },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    progressLabel = "准备删除…"
                    // 只保留顶层：勾了 a 又勾了 a/b 时，删掉 a 就够了。
                    // 同时保证删除只作用于用户勾选的这一层 —— 绝不会顺带删掉它们的父目录，
                    // 这样"子项要保留、父项要删除"的组合才不会把保留的那份一起带走。
                    val targets = topLevelPaths(chosen)
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            repo.delete(targets) { done, total ->
                                progressLabel = "正在删除 $done/$total"
                            }
                        }
                        val gone = outcome.deleted.toSet()
                        excess = excess.filter { it.path !in gone }
                        chosen = chosen - gone
                        val notes = ArrayList<String>()
                        notes.add("已删除 ${outcome.deleted.size} 项")
                        if (outcome.failed.isNotEmpty()) {
                            notes.add("失败 ${outcome.failed.size} 项")
                            if (outcome.reasons.isNotEmpty()) {
                                notes.add(
                                    "失败原因：" + outcome.reasons.entries.take(3)
                                        .joinToString("；") { "${it.key} → ${it.value}" },
                                )
                            }
                        }
                        if (excess.isNotEmpty()) {
                            notes.add("还有 ${excess.size} 项保留着，可点下方「纳入同步」")
                        }
                        onMessage(notes.joinToString("；"))
                        deleting = false
                        progressLabel = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
