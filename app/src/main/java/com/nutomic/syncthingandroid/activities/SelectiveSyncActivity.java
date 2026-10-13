package com.nutomic.syncthingandroid.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.model.BrowseEntry;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.model.FolderIgnoreList;
import com.nutomic.syncthingandroid.model.FolderStatus;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.util.ConfigRouter;
import com.nutomic.syncthingandroid.util.SelectiveSyncPatterns;
import com.nutomic.syncthingandroid.util.SelectiveSyncStore;
import com.nutomic.syncthingandroid.util.SelectiveSyncTreeCache;
import com.nutomic.syncthingandroid.views.IndeterminateCheckBox;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 选择性同步：以可展开的目录树勾选任意层级的文件夹或文件。
 *
 * <p>要点：
 * <ul>
 *   <li>树按需懒加载，任意深度都可展开并勾选；本机有缓存时离线也能展示。</li>
 *   <li>顶部开关可随时在"选择性同步"与"全部同步"之间切换。</li>
 *   <li>取消勾选已同步内容时，用 {@code GET /rest/db/file} 的 availability 判断远端是否仍有副本：
 *       有副本即可直接删除本地文件（目录递归删除）；无副本才提示风险。</li>
 * </ul>
 */
public class SelectiveSyncActivity extends SyncthingActivity {

    public static final String EXTRA_FOLDER_ID =
            ".activities.SelectiveSyncActivity.FOLDER_ID";

    private static final String TAG = "SelectiveSyncActivity";

    private static final int BROWSE_LEVELS = 1;

    private static final long REFRESH_INTERVAL_MS = 8000L;

    /** 自动刷新目录树的最小间隔，避免远端频繁变化时反复重建整棵树。 */
    private static final long MIN_AUTO_REFRESH_INTERVAL_MS = 30000L;

    /** 缓存嵌套子项的深度/条目上限，防止超大目录拖垮界面。 */
    private static final int MAX_CACHE_DEPTH = 6;

    private static final int MAX_CACHE_ENTRIES = 5000;

    /** Syncthing 内部条目，内核硬编码忽略，不展示。 */
    private static final Set<String> INTERNAL_ENTRIES =
            new LinkedHashSet<>(Arrays.asList(".stignore", ".stfolder", ".stversions"));

    /** 查询远端副本状态的超时，避免个别请求无回调时一直卡住。 */
    private static final long AVAILABILITY_TIMEOUT_MS = 15000L;

    private ConfigRouter mConfig;
    private SelectiveSyncStore mStore;
    private SelectiveSyncTreeCache mCache;
    private Folder mFolder;

    private final List<TreeRow> mRows = new ArrayList<>();
    private Set<String> mSelectedDirs = new LinkedHashSet<>();
    private Set<String> mSelectedFiles = new LinkedHashSet<>();
    private Set<String> mInitialDirs = new LinkedHashSet<>();
    private Set<String> mInitialFiles = new LinkedHashSet<>();

    private TreeAdapter mAdapter;
    private SwitchCompat mEnableSwitch;
    private TextView mPathView;
    private TextView mWarningsView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastKnownItems = -1L;
    private long mLastAutoRefreshMs = 0L;
    private final ExecutorService mCacheExecutor = Executors.newSingleThreadExecutor();
    private int mCachedEntries = 0;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshIfRemoteChanged();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private static final class TreeRow {
        String name;
        String path;
        boolean dir;
        int depth;
        boolean expanded;
        boolean childrenLoaded;
        /** 预先算好的显示状态，避免 getView 里重复遍历选择集造成卡顿。 */
        boolean selected;
        boolean covered;
        boolean partial;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mConfig = new ConfigRouter(SelectiveSyncActivity.this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selective_sync);
        mStore = new SelectiveSyncStore(PreferenceManager.getDefaultSharedPreferences(this));
        mCache = new SelectiveSyncTreeCache(this);

        String folderId = getIntent().getStringExtra(EXTRA_FOLDER_ID);
        if (folderId == null) {
            Log.w(TAG, "Missing EXTRA_FOLDER_ID");
            finish();
            return;
        }
        mFolder = findFolder(folderId);
        if (mFolder == null) {
            Toast.makeText(this, R.string.selective_sync_folder_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mSelectedDirs = mStore.getSelectedDirs(folderId);
        mSelectedFiles = mStore.getSelectedFiles(folderId);
        mInitialDirs = new LinkedHashSet<>(mSelectedDirs);
        mInitialFiles = new LinkedHashSet<>(mSelectedFiles);

        mPathView = findViewById(R.id.current_path);
        mWarningsView = findViewById(R.id.warnings);
        mEnableSwitch = findViewById(R.id.enableSwitch);

        Boolean enabled = mStore.isEnabled(folderId);
        mEnableSwitch.setChecked(enabled == null || enabled);
        mEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateEnabledUi());

        ListView listView = findViewById(R.id.list);
        mAdapter = new TreeAdapter();
        listView.setAdapter(mAdapter);
        listView.setEmptyView(findViewById(R.id.empty));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            TreeRow row = mRows.get(position);
            if (!row.dir) {
                return;
            }
            if (row.expanded) {
                row.expanded = false;
                // 折叠后清空子项，下次展开重新加载
                removeChildrenOf(position, row.depth);
                row.childrenLoaded = false;
                mAdapter.notifyDataSetChanged();
            } else {
                row.expanded = true;
                loadChildren(position, row);
                mAdapter.notifyDataSetChanged();
            }
        });

        findViewById(R.id.clear_selection).setOnClickListener(v -> {
            mSelectedDirs.clear();
            mSelectedFiles.clear();
            mAdapter.notifyDataSetChanged();
        });
        findViewById(R.id.save).setOnClickListener(v -> onSave());

        updateEnabledUi();
        loadChildren(-1, null);
    }

    private void updateEnabledUi() {
        boolean enabled = mEnableSwitch != null && mEnableSwitch.isChecked();
        View list = findViewById(R.id.list);
        if (list != null) {
            list.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (mPathView != null) {
            mPathView.setText(enabled
                    ? R.string.selective_sync_summary
                    : R.string.selective_sync_disabled_hint);
        }
        View hint = findViewById(R.id.new_files_hint);
        if (hint != null) {
            hint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.postDelayed(mRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefreshRunnable);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mCacheExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onServiceConnected(android.content.ComponentName componentName,
                                   android.os.IBinder iBinder) {
        super.onServiceConnected(componentName, iBinder);
        loadChildren(-1, null);
    }

    private Folder findFolder(String folderId) {
        List<Folder> folders = mConfig.getFolders(null);
        if (folders == null) {
            return null;
        }
        for (Folder folder : folders) {
            if (folder != null && folderId.equals(folder.id)) {
                return folder;
            }
        }
        return null;
    }

    /** 加载某一节点的子项；parentRow 为 null 表示加载根目录。 */
    private void loadChildren(int parentIndex, TreeRow parentRow) {
        String prefix = (parentRow == null) ? "" : parentRow.path;
        boolean hadRows = !mRows.isEmpty();

        if (parentRow != null && parentRow.childrenLoaded && !mRows.isEmpty()) {
            // 已有子项，直接展开即可（避免重复请求）
            return;
        }
        SelectiveSyncTreeCache.Level cached = mCache.getLevel(mFolder.id, prefix);
        if (cached != null && (parentRow == null ? mRows.isEmpty() : !parentRow.childrenLoaded)) {
            applyChildren(parentIndex, parentRow, convertCached(cached), true);
            if (parentRow != null) {
                parentRow.childrenLoaded = true;
            }
        }

        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            return;
        }
        restApi.getFolderBrowse(mFolder.id, prefix, BROWSE_LEVELS, entries -> {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            applyChildren(parentIndex, parentRow, entries, false);
            if (parentRow != null) {
                parentRow.childrenLoaded = true;
            }
            // 缓存写入放后台：目录很大时，递归遍历 + Gson 序列化会卡住主线程导致 ANR。
            final List<BrowseEntry> snapshot = new ArrayList<>(entries);
            mCacheExecutor.execute(() -> {
                mCachedEntries = 0;
                mCache.putLevel(mFolder.id, prefix, snapshot);
                // browse 常会连带返回嵌套子项，一并缓存：展开时无需再发请求，离线也能展开
                cacheNested(mFolder.id, prefix, snapshot, 0);
                mCache.save();
            });
        });
    }

    private void cacheNested(String folderId, String parentPrefix,
                             List<BrowseEntry> entries, int depth) {
        if (entries == null || depth > MAX_CACHE_DEPTH) {
            return;
        }
        for (BrowseEntry entry : entries) {
            if (mCachedEntries >= MAX_CACHE_ENTRIES) {
                return;
            }
            if (entry == null || entry.name == null
                    || entry.children == null || entry.children.isEmpty()) {
                continue;
            }
            String path = parentPrefix.isEmpty()
                    ? entry.name : parentPrefix + "/" + entry.name;
            mCache.putLevel(folderId, path, entry.children);
            mCachedEntries += entry.children.size();
            cacheNested(folderId, path, entry.children, depth + 1);
        }
    }

    private List<BrowseEntry> convertCached(SelectiveSyncTreeCache.Level level) {
        List<BrowseEntry> entries = new ArrayList<>();
        if (level == null || level.entries == null) {
            return entries;
        }
        for (SelectiveSyncTreeCache.Entry cached : level.entries) {
            BrowseEntry entry = new BrowseEntry();
            entry.name = cached.name;
            entry.type = cached.dir ? BrowseEntry.TYPE_DIRECTORY : "FILE_INFO_TYPE_FILE";
            entries.add(entry);
        }
        return entries;
    }

    private void applyChildren(int parentIndex, TreeRow parentRow,
                               List<BrowseEntry> entries, boolean fromCache) {
        int parentDepth = (parentRow == null) ? -1 : parentRow.depth;
        removeChildrenOf(parentIndex, parentDepth);
        List<TreeRow> rows = new ArrayList<>();
        for (BrowseEntry entry : entries) {
            if (entry == null || entry.name == null) {
                continue;
            }
            // Syncthing 的内部条目由内核硬编码忽略，用 ! 也放行不了，
            // 列出来只会让人以为可以勾选，直接隐藏。
            if (INTERNAL_ENTRIES.contains(entry.name)) {
                continue;
            }
            TreeRow row = new TreeRow();
            row.name = entry.name;
            row.dir = entry.isDirectory();
            row.depth = parentDepth + 1;
            row.path = (parentRow == null) ? entry.name : parentRow.path + "/" + entry.name;
            rows.add(row);
        }
        Collections.sort(rows, (a, b) -> {
            if (a.dir != b.dir) {
                return a.dir ? -1 : 1;
            }
            return nullSafe(a.name).compareToIgnoreCase(nullSafe(b.name));
        });
        mRows.addAll(parentIndex + 1, rows);
        recomputeStates();
        mAdapter.notifyDataSetChanged();
        if (fromCache && parentRow == null) {
            Toast.makeText(this, R.string.selective_sync_from_cache, Toast.LENGTH_SHORT).show();
        }
    }

    private void removeChildrenOf(int parentIndex, int parentDepth) {
        int i = parentIndex + 1;
        while (i < mRows.size() && mRows.get(i).depth > parentDepth) {
            mRows.remove(i);
        }
    }

    /** 远端条目数变化时刷新缓存与整棵树。 */
    private void refreshIfRemoteChanged() {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded() || mFolder == null) {
            return;
        }
        FolderStatus status = readFolderStatus(restApi);
        if (status == null || !"idle".equals(status.state)) {
            return;
        }
        long items = status.globalFiles + status.globalDirectories;
        long now = System.currentTimeMillis();
        if (mLastKnownItems != -1L && items != mLastKnownItems
                && now - mLastAutoRefreshMs > MIN_AUTO_REFRESH_INTERVAL_MS) {
            Log.d(TAG, "远端条目数变化 (" + mLastKnownItems + " -> " + items + ")，刷新目录树");
            mLastAutoRefreshMs = now;
            mCache.clearFolder(mFolder.id);
            loadChildren(-1, null);
        }
        mLastKnownItems = items;
    }

    private FolderStatus readFolderStatus(RestApi restApi) {
        try {
            Map.Entry<FolderStatus, ?> entry = restApi.getFolderStatus(mFolder.id);
            return entry == null ? null : entry.getKey();
        } catch (Exception e) {
            Log.w(TAG, "读取文件夹状态失败", e);
            return null;
        }
    }

    private void toggle(TreeRow row, boolean checked) {
        if (row.dir) {
            if (checked) {
                mSelectedDirs.add(row.path);
                // 父目录选中 -> 整棵子树随之选中，清掉子项上的独立状态避免混淆
                clearDescendants(row.path);
            } else {
                mSelectedDirs.remove(row.path);
                // 父目录取消 -> 子项一起取消
                clearDescendants(row.path);
            }
        } else {
            if (checked) {
                mSelectedFiles.add(row.path);
            } else {
                mSelectedFiles.remove(row.path);
            }
        }
        recomputeStates();
    }

    /** 清除该目录下所有后代的独立选择状态。 */
    private void clearDescendants(String dirPath) {
        String prefix = dirPath + "/";
        Iterator<String> dirs = mSelectedDirs.iterator();
        while (dirs.hasNext()) {
            if (dirs.next().startsWith(prefix)) {
                dirs.remove();
            }
        }
        Iterator<String> files = mSelectedFiles.iterator();
        while (files.hasNext()) {
            if (files.next().startsWith(prefix)) {
                files.remove();
            }
        }
    }

    /** 统一计算各行的显示状态，供 getView 直接读取。 */
    private void recomputeStates() {
        for (TreeRow row : mRows) {
            boolean selected = row.dir
                    ? mSelectedDirs.contains(row.path)
                    : mSelectedFiles.contains(row.path);
            row.selected = selected;
            row.covered = !selected && isCoveredByParent(row);
            row.partial = row.dir && !selected && !row.covered
                    && hasSelectedDescendant(row.path);
        }
    }

    private boolean isSelected(TreeRow row) {
        return row.dir ? mSelectedDirs.contains(row.path) : mSelectedFiles.contains(row.path);
    }

    /** 该路径已由某个选中的父目录覆盖（隐式选中）。 */
    private boolean isCoveredByParent(TreeRow row) {
        for (String dir : mSelectedDirs) {
            if (row.path.equals(dir) || row.path.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 该目录下是否存在被选中的后代（用于显示"部分选中"）。 */
    private boolean hasSelectedDescendant(String dirPath) {
        String prefix = dirPath + "/";
        for (String dir : mSelectedDirs) {
            if (dir.startsWith(prefix)) {
                return true;
            }
        }
        for (String file : mSelectedFiles) {
            if (file.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> effectiveSelection() {
        List<String> result = new ArrayList<>();
        for (String dir : mSelectedDirs) {
            if (!hasSelectedAncestor(dir)) {
                result.add(dir);
            }
        }
        for (String file : mSelectedFiles) {
            if (!hasSelectedAncestor(file)) {
                result.add(file);
            }
        }
        return result;
    }

    private boolean hasSelectedAncestor(String path) {
        for (String dir : mSelectedDirs) {
            if (path.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    private void onSave() {
        boolean enabled = mEnableSwitch != null && mEnableSwitch.isChecked();
        if (!enabled) {
            commitSelection(false, null);
            return;
        }
        List<String> removed = removedSelections();
        if (removed.isEmpty()) {
            commitSelection(true, null);
            return;
        }
        checkRemoteAvailability(removed);
    }

    /** 本次取消勾选、且本地确实存在的路径（含目录）。 */
    private List<String> removedSelections() {
        List<String> removed = new ArrayList<>();
        for (String path : mInitialDirs) {
            if (!mSelectedDirs.contains(path) && new File(mFolder.path, path).exists()) {
                removed.add(path);
            }
        }
        for (String path : mInitialFiles) {
            if (!mSelectedFiles.contains(path) && new File(mFolder.path, path).exists()) {
                removed.add(path);
            }
        }
        return removed;
    }

    /**
     * 判断远端是否仍持有该文件或目录。
     * 实测：GET /rest/db/file 对目录同样返回 availability，因此目录与文件用同一套判断。
     */
    private void checkRemoteAvailability(List<String> removed) {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            Toast.makeText(this, R.string.selective_sync_offline_no_delete, Toast.LENGTH_LONG).show();
            commitSelection(true, null);
            return;
        }
        final List<String> safeToDelete = new ArrayList<>();
        final List<String> risky = new ArrayList<>();
        final String localDeviceId = restApi.getLocalDeviceId();
        final Set<String> pending = new LinkedHashSet<>(removed);
        final boolean[] done = {false};

        // 超时兜底：个别请求若始终没有回调，界面会一直等在这里。
        // 未拿到结果的一律按"远端状态未知"处理（不删除），保证安全。
        final Runnable timeout = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            Log.w(TAG, "查询远端副本状态超时，未返回的路径按有风险处理");
            risky.addAll(pending);
            pending.clear();
            finishAvailabilityCheck(safeToDelete, risky);
        };
        mHandler.postDelayed(timeout, AVAILABILITY_TIMEOUT_MS);

        for (String path : removed) {
            restApi.getFolderFile(mFolder.id, path, entry -> {
                if (done[0]) {
                    return;
                }
                pending.remove(path);
                // 只存原始路径，提示文案在展示时拼接，避免污染后续删除/恢复操作
                if (entry != null && entry.hasRemoteCopy(localDeviceId)) {
                    safeToDelete.add(path);
                } else {
                    risky.add(path);
                }
                if (pending.isEmpty()) {
                    done[0] = true;
                    mHandler.removeCallbacks(timeout);
                    finishAvailabilityCheck(safeToDelete, risky);
                }
            });
        }
    }

    /** 远端有副本 -> 直接删除；远端无副本 -> 提示，由用户选择保持勾选或强制删除。 */
    private void finishAvailabilityCheck(final List<String> safeToDelete,
                                         final List<String> risky) {
        if (risky.isEmpty()) {
            commitSelection(true, safeToDelete);
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.selective_sync_risky_header)).append("\n");
        for (String path : risky) {
            message.append("  - ").append(path)
                    .append("  ").append(getString(R.string.selective_sync_remote_missing))
                    .append('\n');
        }
        if (!safeToDelete.isEmpty()) {
            message.append('\n').append(getString(R.string.selective_sync_safe_to_delete))
                    .append("\n");
            for (String path : safeToDelete) {
                message.append("  - ").append(path).append('\n');
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.selective_sync_removal_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.selective_sync_keep_selected,
                        (dialog, which) -> {
                            // 保持勾选：把这些路径放回去，规则里仍包含它们
                            restoreSelection(risky);
                            commitSelection(true, safeToDelete);
                        })
                .setPositiveButton(R.string.selective_sync_force_delete, (dialog, which) -> {
                    List<String> all = new ArrayList<>(safeToDelete);
                    all.addAll(risky);
                    commitSelection(true, all);
                })
                .show();
    }

    /** 恢复勾选（用户选择"保持勾选"时）。 */
    private void restoreSelection(List<String> paths) {
        for (String path : paths) {
            if (mInitialDirs.contains(path)) {
                mSelectedDirs.add(path);
            } else {
                mSelectedFiles.add(path);
            }
        }
        recomputeStates();
        mAdapter.notifyDataSetChanged();
    }

    private void commitSelection(final boolean enabled, final List<String> pathsToDelete) {
        final RestApi restApi = getApi();
        List<String> selected = effectiveSelection();
        Set<String> dirs = new LinkedHashSet<>();
        for (String path : selected) {
            if (mSelectedDirs.contains(path)) {
                dirs.add(path);
            }
        }

        mConfig.getFolderIgnoreList(restApi, mFolder, folderIgnoreList -> {
            String existing = "";
            if (folderIgnoreList != null && folderIgnoreList.ignore != null) {
                existing = TextUtils.join("\n", folderIgnoreList.ignore);
            }
            SelectiveSyncPatterns.Result result = SelectiveSyncPatterns.buildStignore(
                    existing, selected, dirs, SelectiveSyncPatterns.DEFAULT_MAX_PATTERNS, enabled);
            mConfig.postFolderIgnoreList(restApi, mFolder, result.text.split("\n"));
            mStore.save(mFolder.id, mSelectedDirs, mSelectedFiles);
            mStore.setEnabled(mFolder.id, enabled);
            showWarnings(result.warnings);

            if (restApi != null && restApi.isConfigLoaded()) {
                restApi.rescanFolder(mFolder.id);
            }

            if (!enabled || pathsToDelete == null || pathsToDelete.isEmpty()) {
                Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_saved,
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
                return;
            }

            Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_deleting,
                    Toast.LENGTH_SHORT).show();
            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            executor.execute(() -> {
                // 等忽略规则生效后再删，避免 Syncthing 把删除当作本地真实删除传播到远端
                waitForFolderIdle(restApi, 20000L);
                final List<String> failed = deletePaths(pathsToDelete);
                final int finalDeleted = pathsToDelete.size() - failed.size();
                mainHandler.post(() -> {
                    Toast.makeText(SelectiveSyncActivity.this,
                            getString(R.string.selective_sync_deleted_count,
                                    finalDeleted, pathsToDelete.size()),
                            Toast.LENGTH_SHORT).show();
                    if (!failed.isEmpty()) {
                        Toast.makeText(SelectiveSyncActivity.this,
                                getString(R.string.selective_sync_delete_failed,
                                        TextUtils.join("\n", failed)),
                                Toast.LENGTH_LONG).show();
                    }
                    Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_saved,
                            Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            });
            executor.shutdown();
        });
    }

    private void waitForFolderIdle(RestApi restApi, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            FolderStatus status = readFolderStatus(restApi);
            if (status != null && "idle".equals(status.state)) {
                return;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 删除指定路径，返回仍然删除失败的路径。
     *
     * <p>先删父目录（路径短的在前），子项会随之消失，既避免重复操作，
     * 也不会把"已被父目录带走"误报成失败。
     */
    private List<String> deletePaths(List<String> paths) {
        List<String> sorted = new ArrayList<>(paths);
        Collections.sort(sorted, Comparator.comparingInt(String::length));
        List<String> failed = new ArrayList<>();
        for (String path : sorted) {
            File file = new File(mFolder.path, path);
            if (!file.exists()) {
                continue;
            }
            boolean ok = deleteRecursively(file);
            if (!ok && file.exists()) {
                // 文件可能正被 Syncthing 占用，稍等后重试一次
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ok = deleteRecursively(file);
            }
            if (!ok && file.exists()) {
                failed.add(path);
            }
        }
        return failed;
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    private void showWarnings(List<String> warnings) {
        if (mWarningsView == null) {
            return;
        }
        if (warnings == null || warnings.isEmpty()) {
            mWarningsView.setVisibility(View.GONE);
            return;
        }
        mWarningsView.setVisibility(View.VISIBLE);
        mWarningsView.setText(TextUtils.join("\n", warnings));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private class TreeAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public TreeRow getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            ViewHolder holder;
            if (view == null) {
                view = LayoutInflater.from(SelectiveSyncActivity.this)
                        .inflate(R.layout.item_selective_sync, parent, false);
                holder = new ViewHolder();
                holder.indent = view.findViewById(R.id.indent);
                holder.expander = view.findViewById(R.id.expander);
                holder.checkBox = view.findViewById(R.id.checkbox);
                holder.name = view.findViewById(R.id.name);
                holder.hint = view.findViewById(R.id.hint);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            final TreeRow row = getItem(position);
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < row.depth; i++) {
                indent.append("    ");
            }
            holder.indent.setText(indent.toString());
            holder.expander.setText(row.dir ? (row.expanded ? "-" : "+") : "");
            holder.name.setText(row.name);

            holder.checkBox.setOnCheckedChangeListener(null);
            holder.checkBox.setIndeterminate(row.partial);
            holder.checkBox.setChecked(row.selected);
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                toggle(row, isChecked);
                notifyDataSetChanged();
            });

            if (row.covered) {
                holder.hint.setVisibility(View.VISIBLE);
            } else {
                holder.hint.setVisibility(View.GONE);
            }
            return view;
        }
    }

    private static class ViewHolder {
        TextView indent;
        TextView expander;
        IndeterminateCheckBox checkBox;
        TextView name;
        TextView hint;
    }
}
