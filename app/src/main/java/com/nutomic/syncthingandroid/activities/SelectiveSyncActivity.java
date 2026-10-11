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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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
                removeChildrenOf(position, row.depth);
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
            mCache.putLevel(mFolder.id, prefix, entries);
            mCache.save();
            applyChildren(parentIndex, parentRow, entries, false);
            if (parentRow != null) {
                parentRow.childrenLoaded = true;
            }
        });
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
        if (mLastKnownItems != -1L && items != mLastKnownItems) {
            Log.d(TAG, "远端条目数变化 (" + mLastKnownItems + " -> " + items + ")，刷新目录树");
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
            } else {
                mSelectedDirs.remove(row.path);
            }
        } else {
            if (checked) {
                mSelectedFiles.add(row.path);
            } else {
                mSelectedFiles.remove(row.path);
            }
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
        final int[] remaining = {removed.size()};
        for (String path : removed) {
            restApi.getFolderFile(mFolder.id, path, entry -> {
                if (entry != null && entry.hasRemoteCopy(localDeviceId)) {
                    safeToDelete.add(path);
                } else {
                    risky.add(path + "  " + getString(R.string.selective_sync_remote_missing));
                }
                if (--remaining[0] == 0) {
                    showRemovalDialog(safeToDelete, risky);
                }
            });
        }
    }

    private void showRemovalDialog(final List<String> safeToDelete, final List<String> risky) {
        StringBuilder message = new StringBuilder();
        if (!safeToDelete.isEmpty()) {
            message.append(getString(R.string.selective_sync_safe_to_delete)).append("\n");
            for (String path : safeToDelete) {
                message.append("  - ").append(path).append('\n');
            }
        }
        if (!risky.isEmpty()) {
            message.append('\n').append(getString(R.string.selective_sync_risky_header)).append("\n");
            for (String path : risky) {
                message.append("  - ").append(path).append('\n');
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.selective_sync_removal_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.selective_sync_keep_files,
                        (dialog, which) -> commitSelection(true, null));
        if (!safeToDelete.isEmpty()) {
            builder.setPositiveButton(R.string.selective_sync_delete_safe,
                    (dialog, which) -> commitSelection(true, safeToDelete));
        }
        if (!risky.isEmpty()) {
            builder.setNeutralButton(R.string.selective_sync_delete_all, (dialog, which) -> {
                List<String> all = new ArrayList<>(safeToDelete);
                all.addAll(risky);
                commitSelection(true, all);
            });
        }
        builder.show();
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
                int deleted = 0;
                for (String path : pathsToDelete) {
                    if (deleteRecursively(new File(mFolder.path, path))) {
                        deleted++;
                    }
                }
                final int finalDeleted = deleted;
                mainHandler.post(() -> {
                    Toast.makeText(SelectiveSyncActivity.this,
                            getString(R.string.selective_sync_deleted_count,
                                    finalDeleted, pathsToDelete.size()),
                            Toast.LENGTH_SHORT).show();
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

            boolean selected = isSelected(row);
            holder.checkBox.setOnCheckedChangeListener(null);
            holder.checkBox.setChecked(selected);
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                toggle(row, isChecked);
                notifyDataSetChanged();
            });

            if (!selected && isCoveredByParent(row)) {
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
        CheckBox checkBox;
        TextView name;
        TextView hint;
    }
}
