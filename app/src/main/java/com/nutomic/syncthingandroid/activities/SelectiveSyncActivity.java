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
 * 选择性同步：浏览远端（全局模型）目录树，勾选需要同步的文件夹或文件。
 *
 * <p>数据优先来自本机缓存，远端暂时不可达时也能勾选；打开后再用网络结果刷新缓存。
 * 取消勾选已同步的内容时，会检查远端是否仍持有该文件，避免误删唯一副本。
 */
public class SelectiveSyncActivity extends SyncthingActivity {

    public static final String EXTRA_FOLDER_ID =
            ".activities.SelectiveSyncActivity.FOLDER_ID";

    private static final String TAG = "SelectiveSyncActivity";

    /** 每次只取一层：官方明确提示 browse 调用开销大。 */
    private static final int BROWSE_LEVELS = 1;

    /** 检查远端目录是否变化的轮询间隔。 */
    private static final long REFRESH_INTERVAL_MS = 8000L;

    private ConfigRouter mConfig;
    private SelectiveSyncStore mStore;
    private SelectiveSyncTreeCache mCache;
    private Folder mFolder;
    private String mPrefix = "";
    private boolean mHasParentEntry = false;

    private final List<BrowseEntry> mEntries = new ArrayList<>();
    private Set<String> mSelectedDirs = new LinkedHashSet<>();
    private Set<String> mSelectedFiles = new LinkedHashSet<>();
    /** 进入界面时的选择集，用于识别"本次取消勾选"的项。 */
    private Set<String> mInitialDirs = new LinkedHashSet<>();
    private Set<String> mInitialFiles = new LinkedHashSet<>();

    private EntryAdapter mAdapter;
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

        ListView listView = findViewById(R.id.list);
        mAdapter = new EntryAdapter();
        listView.setAdapter(mAdapter);
        listView.setEmptyView(findViewById(R.id.empty));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            BrowseEntry entry = mAdapter.getItem(position);
            if (mHasParentEntry && position == 0) {
                navigateUp();
                return;
            }
            if (entry.isDirectory()) {
                mPrefix = entry.getPath(mPrefix);
                loadLevel();
            }
        });

        findViewById(R.id.clear_selection).setOnClickListener(v -> {
            mSelectedDirs.clear();
            mSelectedFiles.clear();
            mAdapter.notifyDataSetChanged();
        });
        findViewById(R.id.save).setOnClickListener(v -> onSave());

        updatePathView();
        loadLevel();
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
        loadLevel();
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

    /** 先用缓存立刻渲染，再用网络结果刷新。 */
    private void loadLevel() {
        if (mFolder == null) {
            return;
        }
        if (mEntries.isEmpty()) {
            SelectiveSyncTreeCache.Level cached = mCache.getLevel(mFolder.id, mPrefix);
            if (cached != null) {
                applyEntries(convertCached(cached), true);
            }
        }
        refreshFromNetwork();
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

    private void refreshFromNetwork() {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            return;
        }
        restApi.getFolderBrowse(mFolder.id, mPrefix, BROWSE_LEVELS, entries -> {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            mCache.putLevel(mFolder.id, mPrefix, entries);
            mCache.save();
            applyEntries(entries, false);
        });
    }

    /** 远端条目数变化时刷新，保持缓存最新。 */
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
            refreshFromNetwork();
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

    private void applyEntries(List<BrowseEntry> entries, boolean fromCache) {
        mEntries.clear();
        mHasParentEntry = !mPrefix.isEmpty();
        if (mHasParentEntry) {
            BrowseEntry parent = new BrowseEntry();
            parent.name = "..";
            parent.type = BrowseEntry.TYPE_DIRECTORY;
            mEntries.add(parent);
        }
        if (entries != null) {
            mEntries.addAll(entries);
        }
        List<BrowseEntry> sortable = mEntries.subList(mHasParentEntry ? 1 : 0, mEntries.size());
        Collections.sort(sortable, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return safeName(a).compareToIgnoreCase(safeName(b));
        });
        updatePathView();
        mAdapter.notifyDataSetChanged();
        if (fromCache) {
            Toast.makeText(this, R.string.selective_sync_from_cache, Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateUp() {
        int index = mPrefix.lastIndexOf('/');
        mPrefix = (index < 0) ? "" : mPrefix.substring(0, index);
        loadLevel();
    }

    private void updatePathView() {
        if (mPathView == null) {
            return;
        }
        mPathView.setText(mPrefix.isEmpty() ? getString(R.string.selective_sync_root) : mPrefix);
    }

    private void toggle(BrowseEntry entry, boolean checked) {
        String path = entry.getPath(mPrefix);
        if (entry.isDirectory()) {
            if (checked) {
                mSelectedDirs.add(path);
            } else {
                mSelectedDirs.remove(path);
            }
        } else {
            if (checked) {
                mSelectedFiles.add(path);
            } else {
                mSelectedFiles.remove(path);
            }
        }
    }

    private boolean isSelected(BrowseEntry entry) {
        String path = entry.getPath(mPrefix);
        return entry.isDirectory() ? mSelectedDirs.contains(path) : mSelectedFiles.contains(path);
    }

    /** 该路径已由某个选中的父目录覆盖。 */
    private boolean isCoveredByParent(String path) {
        for (String dir : mSelectedDirs) {
            if (path.equals(dir) || path.startsWith(dir + "/")) {
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
        List<String> removed = removedSelections();
        if (removed.isEmpty()) {
            commitSelection(null);
            return;
        }
        checkRemoteAvailability(removed);
    }

    /** 本次取消勾选、且本地确实存在的路径。 */
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

    private void checkRemoteAvailability(List<String> removed) {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            // 无法确认远端状态时保守处理：只写规则，不删除本地文件。
            Toast.makeText(this, R.string.selective_sync_offline_no_delete, Toast.LENGTH_LONG).show();
            commitSelection(null);
            return;
        }
        final List<String> safeToDelete = new ArrayList<>();
        final List<String> risky = new ArrayList<>();
        final String localDeviceId = restApi.getLocalDeviceId();
        final int[] remaining = {removed.size()};
        for (String path : removed) {
            File local = new File(mFolder.path, path);
            if (local.isDirectory()) {
                // 目录不在未确认前递归删除，按风险项提示。
                risky.add(path + "  " + getString(R.string.selective_sync_dir_manual));
                if (--remaining[0] == 0) {
                    showRemovalDialog(safeToDelete, risky);
                }
                continue;
            }
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
                        (dialog, which) -> commitSelection(null));
        if (!safeToDelete.isEmpty()) {
            builder.setPositiveButton(R.string.selective_sync_delete_safe,
                    (dialog, which) -> commitSelection(safeToDelete));
        }
        if (!risky.isEmpty()) {
            builder.setNeutralButton(R.string.selective_sync_delete_all, (dialog, which) -> {
                List<String> all = new ArrayList<>(safeToDelete);
                all.addAll(risky);
                commitSelection(all);
            });
        }
        builder.show();
    }

    private void commitSelection(final List<String> pathsToDelete) {
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
            SelectiveSyncPatterns.Result result =
                    SelectiveSyncPatterns.buildStignore(existing, selected, dirs);
            mConfig.postFolderIgnoreList(restApi, mFolder, result.text.split("\n"));
            mStore.save(mFolder.id, mSelectedDirs, mSelectedFiles);
            showWarnings(result.warnings);

            if (restApi != null && restApi.isConfigLoaded()) {
                restApi.rescanFolder(mFolder.id);
            }

            if (pathsToDelete != null && !pathsToDelete.isEmpty()) {
                Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_deleting,
                        Toast.LENGTH_SHORT).show();
                final ExecutorService executor = Executors.newSingleThreadExecutor();
                final Handler mainHandler = new Handler(Looper.getMainLooper());
                executor.execute(() -> {
                    // 必须等忽略规则生效后再删：否则 Syncthing 会把这次删除
                    // 当作本地真实删除并传播到远端，造成远端数据丢失。
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
                return;
            }

            Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_saved,
                    Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    /** 等待文件夹回到 idle，最多等待 timeoutMs 毫秒。 */
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

    private static String safeName(BrowseEntry entry) {
        return (entry == null || entry.name == null) ? "" : entry.name;
    }

    private class EntryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public BrowseEntry getItem(int position) {
            return mEntries.get(position);
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
                holder.checkBox = view.findViewById(R.id.checkbox);
                holder.name = view.findViewById(R.id.name);
                holder.hint = view.findViewById(R.id.hint);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            BrowseEntry entry = getItem(position);
            holder.name.setText(entry.name);

            if (mHasParentEntry && position == 0) {
                holder.checkBox.setVisibility(View.GONE);
                holder.hint.setVisibility(View.GONE);
                return view;
            }

            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setOnCheckedChangeListener(null);
            boolean checked = isSelected(entry);
            holder.checkBox.setChecked(checked);
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                toggle(entry, isChecked);
                notifyDataSetChanged();
            });

            String path = entry.getPath(mPrefix);
            if (!checked && isCoveredByParent(path)) {
                holder.hint.setVisibility(View.VISIBLE);
            } else {
                holder.hint.setVisibility(View.GONE);
            }
            return view;
        }
    }

    private static class ViewHolder {
        CheckBox checkBox;
        TextView name;
        TextView hint;
    }
}
