package com.nutomic.syncthingandroid.util;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 持久化每个文件夹的选择性同步选择集。
 *
 * <p>规则文本可以从选择集完整重建，因此这里只存选择集，不反解 .stignore。
 */
public class SelectiveSyncStore {

    private static final String PREF_DIRS = "selective_sync_dirs_";

    private static final String PREF_PARTIAL_DIRS = "selective_sync_partial_dirs_";

    private static final String PREF_FILES = "selective_sync_files_";

    private static final String PREF_ENABLED = "selective_sync_enabled_";

    private final SharedPreferences mPreferences;

    public SelectiveSyncStore(SharedPreferences preferences) {
        mPreferences = preferences;
    }

    /**
     * 整棵子树都被放行的目录。
     *
     * <p>与 {@link #getPartialDirs} 的区别是关键的：只有这里面的目录才会"连带子项一起放行"，
     * 部分放行的目录只放行自身，子项各自按自己的勾选算。
     */
    public Set<String> getSelectedDirs(String folderId) {
        return new LinkedHashSet<>(readSet(PREF_DIRS + folderId));
    }

    /** 只放行了自身、其下子项各自单独决定的目录。 */
    public Set<String> getPartialDirs(String folderId) {
        return new LinkedHashSet<>(readSet(PREF_PARTIAL_DIRS + folderId));
    }

    public Set<String> getSelectedFiles(String folderId) {
        return new LinkedHashSet<>(readSet(PREF_FILES + folderId));
    }

    /** 选中的全部路径（目录 + 文件），顺序为目录在前。 */
    public Set<String> getSelected(String folderId) {
        Set<String> all = getSelectedDirs(folderId);
        all.addAll(getSelectedFiles(folderId));
        return all;
    }

    public boolean hasSelection(String folderId) {
        return !readSet(PREF_DIRS + folderId).isEmpty() || !readSet(PREF_FILES + folderId).isEmpty();
    }

    public void save(String folderId, Set<String> fullDirs, Set<String> partialDirs,
                     Set<String> files) {
        mPreferences.edit()
                .putStringSet(PREF_DIRS + folderId, new LinkedHashSet<>(fullDirs))
                .putStringSet(PREF_PARTIAL_DIRS + folderId, new LinkedHashSet<>(partialDirs))
                .putStringSet(PREF_FILES + folderId, new LinkedHashSet<>(files))
                .apply();
    }

    /** 是否启用选择性同步；未设置过（从未启用）时返回 null。 */
    public Boolean isEnabled(String folderId) {
        if (!mPreferences.contains(PREF_ENABLED + folderId)) {
            return null;
        }
        return mPreferences.getBoolean(PREF_ENABLED + folderId, false);
    }

    public void setEnabled(String folderId, boolean enabled) {
        mPreferences.edit().putBoolean(PREF_ENABLED + folderId, enabled).apply();
    }

    public void clear(String folderId) {
        mPreferences.edit()
                .remove(PREF_DIRS + folderId)
                .remove(PREF_PARTIAL_DIRS + folderId)
                .remove(PREF_FILES + folderId)
                .apply();
    }

    private Set<String> readSet(String key) {
        Set<String> value = mPreferences.getStringSet(key, null);
        return (value == null) ? Collections.<String>emptySet() : value;
    }
}
