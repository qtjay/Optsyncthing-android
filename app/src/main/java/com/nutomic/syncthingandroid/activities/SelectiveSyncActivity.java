package com.nutomic.syncthingandroid.activities;

import android.os.Bundle;
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

import androidx.preference.PreferenceManager;

import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.model.BrowseEntry;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.model.FolderIgnoreList;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.util.ConfigRouter;
import com.nutomic.syncthingandroid.util.SelectiveSyncPatterns;
import com.nutomic.syncthingandroid.util.SelectiveSyncStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 选择性同步：浏览远端（全局模型）目录树，勾选需要同步的文件夹或文件，
 * 结果写入该文件夹的 .stignore 托管块。
 *
 * <p>只影响本机：忽略规则不会被同步到其他设备，也不会删除其他设备上的文件。
 */
public class SelectiveSyncActivity extends SyncthingActivity {

    public static final String EXTRA_FOLDER_ID =
            ".activities.SelectiveSyncActivity.FOLDER_ID";

    private static final String TAG = "SelectiveSyncActivity";

    /** 每次只取一层，官方明确提示 browse 调用开销大。 */
    private static final int BROWSE_LEVELS = 1;

    private ConfigRouter mConfig;
    private SelectiveSyncStore mStore;
    private Folder mFolder;
    private String mPrefix = "";
    private boolean mHasParentEntry = false;

    private final List<BrowseEntry> mEntries = new ArrayList<>();
    private Set<String> mSelectedDirs = new LinkedHashSet<>();
    private Set<String> mSelectedFiles = new LinkedHashSet<>();

    private EntryAdapter mAdapter;
    private TextView mPathView;
    private TextView mWarningsView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mConfig = new ConfigRouter(SelectiveSyncActivity.this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selective_sync);
        mStore = new SelectiveSyncStore(PreferenceManager.getDefaultSharedPreferences(this));

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
                loadEntries();
            }
        });

        findViewById(R.id.clear_selection).setOnClickListener(v -> {
            mSelectedDirs.clear();
            mSelectedFiles.clear();
            mAdapter.notifyDataSetChanged();
        });
        findViewById(R.id.save).setOnClickListener(v -> onSave());

        updatePathView();
    }

    @Override
    public void onServiceConnected(android.content.ComponentName componentName,
                                   android.os.IBinder iBinder) {
        super.onServiceConnected(componentName, iBinder);
        loadEntries();
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

    private void loadEntries() {
        if (mFolder == null) {
            return;
        }
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            Toast.makeText(this, R.string.selective_sync_waiting, Toast.LENGTH_SHORT).show();
            return;
        }
        restApi.getFolderBrowse(mFolder.id, mPrefix, BROWSE_LEVELS, this::onEntriesReceived);
    }

    private void onEntriesReceived(List<BrowseEntry> entries) {
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
    }

    private void navigateUp() {
        int index = mPrefix.lastIndexOf('/');
        mPrefix = (index < 0) ? "" : mPrefix.substring(0, index);
        loadEntries();
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

    /**
     * 生成规则时使用的选择集：已被父目录覆盖的项不再单独输出，避免规则膨胀。
     */
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
            if (restApi != null && restApi.isConfigLoaded()) {
                restApi.rescanFolder(mFolder.id);
            }
            mStore.save(mFolder.id, mSelectedDirs, mSelectedFiles);
            showWarnings(result.warnings);
            Toast.makeText(SelectiveSyncActivity.this, R.string.selective_sync_saved,
                    Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
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
