package com.nutomic.syncthingandroid.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
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
import java.util.LinkedHashMap;
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

    /** Syncthing 内部条目，内核硬编码忽略，不展示。清理时也绝不能删。 */
    private static final Set<String> INTERNAL_ENTRIES =
            new LinkedHashSet<>(Arrays.asList(".stignore", ".stfolder", ".stversions"));

    private static final int MENU_CLEANUP = 1;

    /** 查询远端副本状态的超时，避免个别请求无回调时一直卡住。 */
    private static final long AVAILABILITY_TIMEOUT_MS = 15000L;

    private ConfigRouter mConfig;
    private SelectiveSyncStore mStore;
    private SelectiveSyncTreeCache mCache;
    private Folder mFolder;

    private final List<TreeRow> mRows = new ArrayList<>();
    /**
     * 勾选状态分三桶保存 —— 对应 .stignore 里的两种放行写法：
     * <ul>
     *   <li>{@link #mSelectedDirs}：整棵子树都放行；</li>
     *   <li>{@link #mPartialDirs}：只放行了目录自身，其下子项各自单独决定；</li>
     *   <li>{@link #mSelectedFiles}：单个文件被放行。</li>
     * </ul>
     * 必须区分前两者：若只保留"目录被选中"这一种状态，那么取消子目录时
     * 父目录仍整棵放行，子项会被父规则连带放行，表现为"怎么点都取消不掉"。
     */
    private Set<String> mSelectedDirs = new LinkedHashSet<>();
    private Set<String> mPartialDirs = new LinkedHashSet<>();
    private Set<String> mSelectedFiles = new LinkedHashSet<>();
    private Set<String> mInitialDirs = new LinkedHashSet<>();
    private Set<String> mInitialPartial = new LinkedHashSet<>();
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
        mPartialDirs = mStore.getPartialDirs(folderId);
        mSelectedFiles = mStore.getSelectedFiles(folderId);
        mInitialDirs = new LinkedHashSet<>(mSelectedDirs);
        mInitialPartial = new LinkedHashSet<>(mPartialDirs);
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
            mPartialDirs.clear();
            mSelectedFiles.clear();
            recomputeStates();
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
        // 目标端的 .stignore 才是准的：缓存的选择集可能与它不一致
        // （别的设备改过、或上次保存没写成功），所以用反解结果覆盖一次。
        reloadFromRemoteIgnores();
    }

    /**
     * 读取目标端的 .stignore，反解出勾选状态并覆盖本地选择集。
     *
     * <p>规则里没有类型信息，所以"只放行自身"的条目（可能是目录也可能是文件）
     * 统一放进 mPartialDirs —— 两者生成的规则写法一样（放行自身、不放行子树），
     * 因此不需要区分，也不会写错规则。
     */
    private void reloadFromRemoteIgnores() {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            return;
        }
        mConfig.getFolderIgnoreList(restApi, mFolder, folderIgnoreList -> {
            if (folderIgnoreList == null || folderIgnoreList.ignore == null) {
                return;
            }
            String existing = TextUtils.join("\n", folderIgnoreList.ignore);
            SelectiveSyncPatterns.Selection parsed =
                    SelectiveSyncPatterns.parseManagedBlock(existing);
            if (!parsed.hasBlock) {
                // 没有托管块 = 目标端没有选择性同步规则 = 全部同步
                markAllLoadedSelected();
                return;
            }
            mSelectedDirs = new LinkedHashSet<>(parsed.fullDirs);
            mPartialDirs = new LinkedHashSet<>();
            mSelectedFiles = new LinkedHashSet<>();
            for (String path : parsed.selectedPaths) {
                if (!parsed.fullDirs.contains(path)) {
                    mPartialDirs.add(path);
                }
            }
            mInitialDirs = new LinkedHashSet<>(mSelectedDirs);
            mInitialPartial = new LinkedHashSet<>(mPartialDirs);
            mInitialFiles = new LinkedHashSet<>(mSelectedFiles);
            recomputeStates();
            mAdapter.notifyDataSetChanged();
        });
    }

    /** 已加载的行全部打勾（目标端没有忽略规则时使用）。 */
    private void markAllLoadedSelected() {
        if (mRows.isEmpty()) {
            return;
        }
        mSelectedDirs = new LinkedHashSet<>();
        mPartialDirs = new LinkedHashSet<>();
        mSelectedFiles = new LinkedHashSet<>();
        for (TreeRow row : mRows) {
            if (row.dir) {
                mSelectedDirs.add(row.path);
            } else {
                mSelectedFiles.add(row.path);
            }
        }
        mInitialDirs = new LinkedHashSet<>(mSelectedDirs);
        mInitialPartial = new LinkedHashSet<>(mPartialDirs);
        mInitialFiles = new LinkedHashSet<>(mSelectedFiles);
        recomputeStates();
        mAdapter.notifyDataSetChanged();
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

    /** 取某一层内容的回调；自定义接口而非 java.util.function，避免 minSdk 23 的脱糖问题。 */
    private interface EntriesCallback {
        void onEntries(List<BrowseEntry> entries);
    }

    private void toggle(TreeRow row, boolean checked) {
        if (checked) {
            if (row.dir) {
                mSelectedDirs.add(row.path);
                mPartialDirs.remove(row.path);
                // 整棵放行后，子项上的独立状态就没有意义了，清掉避免混淆
                clearDescendants(row.path);
            } else {
                mSelectedFiles.add(row.path);
            }
            recomputeStates();
            mAdapter.notifyDataSetChanged();
            return;
        }
        if (!row.dir) {
            mSelectedFiles.remove(row.path);
            recomputeStates();
            mAdapter.notifyDataSetChanged();
            return;
        }
        // 取消目录：先把"整棵放行"的祖先拆成逐项放行，再摘掉自己。
        // 不拆的话父规则会把这一支连带放行，表现为"子目录怎么点都取消不掉"。
        materializeAncestors(row.path, () -> {
            removeWithDescendants(row.path);
            recomputeStates();
            mAdapter.notifyDataSetChanged();
        });
    }

    /**
     * 取消勾选前，把"整棵放行"的祖先逐层拆成逐项放行。
     *
     * <p>不拆的话，那条"放行父目录整棵子树"的规则会把子项一起放行 ——
     * .stignore 首个匹配生效，父规则先命中就轮不到子项，子项单独取消是不生效的。
     * 必须一路拆到直接父目录，只拆一层是不够的。
     */
    private void materializeAncestors(String path, Runnable done) {
        materializeStep(ancestorsOf(path), 0, done);
    }

    private void materializeStep(final List<String> ancestors, final int index,
                                 final Runnable done) {
        if (index >= ancestors.size()) {
            done.run();
            return;
        }
        final String ancestor = ancestors.get(index);
        if (!mSelectedDirs.contains(ancestor)) {
            materializeStep(ancestors, index + 1, done);
            return;
        }
        fetchEntries(ancestor, entries -> {
            if (entries != null && !entries.isEmpty()) {
                for (BrowseEntry entry : entries) {
                    if (entry == null || entry.name == null) {
                        continue;
                    }
                    String child = ancestor + "/" + entry.name;
                    if (entry.isDirectory()) {
                        mSelectedDirs.add(child);
                    } else {
                        mSelectedFiles.add(child);
                    }
                    mPartialDirs.remove(child);
                }
                mSelectedDirs.remove(ancestor);
                mPartialDirs.add(ancestor);
            }
            materializeStep(ancestors, index + 1, done);
        });
    }

    /** 从根到该路径的直接父目录，逐层列出（顶层路径返回空表）。 */
    private List<String> ancestorsOf(String path) {
        List<String> out = new ArrayList<>();
        String[] comps = path.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < comps.length - 1; i++) {
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(comps[i]);
            out.add(current.toString());
        }
        return out;
    }

    /**
     * 取某一层的内容：优先用缓存，缓存没有再走 REST。
     * 拆分"整棵放行"的祖先时必须要知道它有哪些子项，否则拆不成逐项。
     */
    private void fetchEntries(String prefix, EntriesCallback callback) {
        SelectiveSyncTreeCache.Level cached = mCache.getLevel(mFolder.id, prefix);
        if (cached != null && cached.entries != null && !cached.entries.isEmpty()) {
            callback.onEntries(convertCached(cached));
            return;
        }
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            callback.onEntries(null);
            return;
        }
        restApi.getFolderBrowse(mFolder.id, prefix, BROWSE_LEVELS, callback::onEntries);
    }

    /**
     * 把这一项连同它的后代一起从勾选里摘掉。
     *
     * 目录整棵取消时，它下面被单独勾选的子项也必须一起摘掉 ——
     * 父目录被忽略后，子项单独放行在 Syncthing 里根本不生效，
     * 留着只会让生成的 .stignore 自相矛盾。
     */
    private void removeWithDescendants(String path) {
        mSelectedDirs.remove(path);
        mPartialDirs.remove(path);
        mSelectedFiles.remove(path);
        String prefix = path + "/";
        removePrefixed(mSelectedDirs, prefix);
        removePrefixed(mPartialDirs, prefix);
        removePrefixed(mSelectedFiles, prefix);
    }

    private void removePrefixed(Set<String> set, String prefix) {
        Iterator<String> it = set.iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    /** 清除该目录下所有后代的独立选择状态。 */
    private void clearDescendants(String dirPath) {
        String prefix = dirPath + "/";
        removePrefixed(mSelectedDirs, prefix);
        removePrefixed(mPartialDirs, prefix);
        removePrefixed(mSelectedFiles, prefix);
    }

    /** 统一计算各行的显示状态，供 getView 直接读取。 */
    private void recomputeStates() {
        for (TreeRow row : mRows) {
            boolean selected = isSelected(row);
            row.selected = selected;
            row.covered = !selected && isCoveredByParent(row);
            row.partial = row.dir && !selected && !row.covered
                    && hasSelectedDescendant(row.path);
        }
    }

    private boolean isSelected(TreeRow row) {
        return row.dir
                ? (mSelectedDirs.contains(row.path) || mPartialDirs.contains(row.path))
                : mSelectedFiles.contains(row.path);
    }

    /**
     * 该路径已由某个"整棵放行"的父目录覆盖（隐式选中）。
     *
     * 只放行了自身的目录不覆盖子项 —— 这正是拆分成三桶的意义所在。
     */
    private boolean isCoveredByParent(TreeRow row) {
        for (String dir : mSelectedDirs) {
            if (row.path.startsWith(dir + "/")) {
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
        for (String dir : mPartialDirs) {
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
        for (String dir : mPartialDirs) {
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

    /** 只有"整棵放行"的祖先会连带放行子项；部分放行的祖先不会。 */
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
            if (!mSelectedDirs.contains(path) && !mPartialDirs.contains(path)
                    && new File(mFolder.path, path).exists()) {
                removed.add(path);
            }
        }
        for (String path : mInitialPartial) {
            if (!mSelectedDirs.contains(path) && !mPartialDirs.contains(path)
                    && new File(mFolder.path, path).exists()) {
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
            } else if (mInitialPartial.contains(path)) {
                mPartialDirs.add(path);
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
            mStore.save(mFolder.id, mSelectedDirs, mPartialDirs, mSelectedFiles);
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
                final DeleteOutcome outcome = deletePaths(pathsToDelete, (done, total) -> {
                    // 每 50 条刷新一次就够：逐条刷新界面会把主线程刷死
                    if (done % 50 == 0 || done == total) {
                        final int shown = done;
                        mainHandler.post(() -> showWarnings(Collections.singletonList(
                                getString(R.string.selective_sync_deleting_progress,
                                        shown, total))));
                    }
                });
                mainHandler.post(() -> {
                    Toast.makeText(SelectiveSyncActivity.this,
                            getString(R.string.selective_sync_deleted_count,
                                    outcome.deleted.size(), pathsToDelete.size()),
                            Toast.LENGTH_SHORT).show();
                    if (!outcome.failed.isEmpty()) {
                        List<String> notes = new ArrayList<>();
                        for (Map.Entry<String, String> entry : outcome.failed.entrySet()) {
                            notes.add(entry.getKey() + " → " + entry.getValue());
                        }
                        showWarnings(notes);
                    } else {
                        showWarnings(null);
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

    /** 删除结果：成功清单 + 失败清单（路径 → 原因）。 */
    private static final class DeleteOutcome {
        final List<String> deleted = new ArrayList<>();
        final Map<String, String> failed = new LinkedHashMap<>();
    }

    private interface ProgressListener {
        void onProgress(int done, int total);
    }

    /**
     * 删除指定路径。
     *
     * <p>先摊平再删，两个理由：
     * <ul>
     *   <li>删除前就知道总数，好给进度 —— 大目录（实测目标端一个目录含 8449 个文件）
     *       串行删要两分钟，没有反馈跟卡死没区别；</li>
     *   <li>能定位到底是哪一条删不掉。只报"失败 N 项"用户根本无从下手。</li>
     * </ul>
     * 顺序上深的先删、父目录最后删，避免把"已被父目录带走"误报成失败。
     */
    private DeleteOutcome deletePaths(List<String> paths, ProgressListener listener) {
        DeleteOutcome outcome = new DeleteOutcome();
        List<String> sorted = new ArrayList<>(paths);
        // 不用 Comparator.comparingInt：那需要 API 24，而 minSdk 是 23，Lint 会判为错误
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.length() - b.length();
            }
        });

        List<File> targets = new ArrayList<>();
        for (String path : sorted) {
            File file = new File(mFolder.path, path);
            if (file.exists()) {
                collectDeletable(file, targets);
            }
        }
        final int total = targets.size();
        Collections.sort(targets, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getAbsolutePath().length() - a.getAbsolutePath().length();
            }
        });

        // 中间条目失败的原因，最后汇总到它所属的顶层路径上
        Map<String, String> intermediate = new LinkedHashMap<>();
        int done = 0;
        for (File file : targets) {
            boolean ok = file.delete();
            if (!ok && file.exists()) {
                // 可能正被 Syncthing 占用，稍等后重试一次
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ok = file.delete();
            }
            if (!ok && file.exists()) {
                intermediate.put(relativeOf(file), "可能被占用或没有写权限");
            }
            done++;
            if (listener != null) {
                listener.onProgress(done, total);
            }
        }

        for (String path : sorted) {
            File file = new File(mFolder.path, path);
            if (!file.exists()) {
                outcome.deleted.add(path);
                continue;
            }
            String cause = null;
            for (Map.Entry<String, String> entry : intermediate.entrySet()) {
                if (entry.getKey().startsWith(path + "/")) {
                    cause = entry.getKey();
                    break;
                }
            }
            outcome.failed.put(path, cause != null
                    ? "子树里有没删掉的条目：" + cause
                    : "删除失败：可能被占用或没有写权限");
        }
        return outcome;
    }

    /** 摊平：先收集所有后代，最后是父目录自身。 */
    private void collectDeletable(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectDeletable(child, out);
                }
            }
        }
        out.add(file);
    }

    /** 绝对路径还原成相对文件夹根的路径。 */
    private String relativeOf(File file) {
        String base = new File(mFolder.path).getAbsolutePath();
        String abs = file.getAbsolutePath();
        if (abs.startsWith(base + File.separator)) {
            return abs.substring(base.length() + 1);
        }
        return abs;
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_CLEANUP, Menu.NONE, R.string.selective_sync_cleanup);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEANUP) {
            runCleanup();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 扫描本机上"按当前勾选本不该存在"的条目，列出来让用户勾选删除。
     *
     * <p>用途：以前同步过、后来取消勾选的东西，磁盘上可能还留着，
     * 光靠"取消勾选时顺带删"清不掉历史遗留。
     *
     * <p>判定只看当前勾选，不再去解析 .stignore 文本 —— 两者口径是一致的，
     * 因为 .stignore 本来就是由这份勾选生成的。
     */
    private void runCleanup() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        showWarnings(Collections.singletonList(
                getString(R.string.selective_sync_cleanup_scanning)));
        executor.execute(() -> {
            final List<String> excess = scanExcessLocally();
            mainHandler.post(() -> {
                showWarnings(null);
                if (excess.isEmpty()) {
                    Toast.makeText(SelectiveSyncActivity.this,
                            R.string.selective_sync_cleanup_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                showExcessDialog(excess);
            });
        });
        executor.shutdown();
    }

    private List<String> scanExcessLocally() {
        List<String> out = new ArrayList<>();
        if (mFolder == null || mFolder.path == null) {
            return out;
        }
        File root = new File(mFolder.path);
        if (!root.isDirectory()) {
            return out;
        }
        collectExcess("", root, out);
        Collections.sort(out);
        return out;
    }

    private void collectExcess(String prefix, File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (INTERNAL_ENTRIES.contains(name)) {
                continue;
            }
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (isExcess(path, child.isDirectory())) {
                // 整棵都是多余的，直接记目录，不用再下去
                out.add(path);
            } else if (child.isDirectory()) {
                collectExcess(path, child, out);
            }
        }
    }

    /**
     * 按当前勾选判定是否多余：整棵放行 / 自身被放行 / 是被放行项的中间层 → 保留；
     * 其余都算多余。目录还要留着当"通道"，否则里面被单独放行的内容也进不去。
     */
    private boolean isExcess(String path, boolean dir) {
        if (mSelectedDirs.contains(path)) {
            return false;
        }
        if (mPartialDirs.contains(path)) {
            return false;
        }
        if (mSelectedFiles.contains(path)) {
            return false;
        }
        for (String selected : mSelectedDirs) {
            if (path.startsWith(selected + "/")) {
                return false;
            }
        }
        if (dir) {
            String prefix = path + "/";
            for (String selected : mSelectedDirs) {
                if (selected.startsWith(prefix)) {
                    return false;
                }
            }
            for (String selected : mPartialDirs) {
                if (selected.startsWith(prefix)) {
                    return false;
                }
            }
            for (String selected : mSelectedFiles) {
                if (selected.startsWith(prefix)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void showExcessDialog(final List<String> excess) {
        final boolean[] checked = new boolean[excess.size()];
        Arrays.fill(checked, true);
        CharSequence[] items = excess.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.selective_sync_cleanup_title)
                .setMultiChoiceItems(items, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.selective_sync_cleanup, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < excess.size(); i++) {
                        if (checked[i]) {
                            chosen.add(excess.get(i));
                        }
                    }
                    if (!chosen.isEmpty()) {
                        confirmCleanupDelete(chosen);
                    }
                })
                .show();
    }

    private void confirmCleanupDelete(final List<String> chosen) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.selective_sync_cleanup_title)
                .setMessage(getString(R.string.selective_sync_cleanup_confirm, chosen.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.selective_sync_cleanup, (dialog, which) -> {
                    final ExecutorService executor = Executors.newSingleThreadExecutor();
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    executor.execute(() -> {
                        final DeleteOutcome outcome = deletePaths(chosen, null);
                        mainHandler.post(() -> {
                            Toast.makeText(SelectiveSyncActivity.this,
                                    getString(R.string.selective_sync_cleanup_done,
                                            outcome.deleted.size()),
                                    Toast.LENGTH_SHORT).show();
                            if (!outcome.failed.isEmpty()) {
                                List<String> notes = new ArrayList<>();
                                for (Map.Entry<String, String> entry
                                        : outcome.failed.entrySet()) {
                                    notes.add(entry.getKey() + " → " + entry.getValue());
                                }
                                showWarnings(notes);
                            }
                        });
                    });
                    executor.shutdown();
                })
                .show();
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
