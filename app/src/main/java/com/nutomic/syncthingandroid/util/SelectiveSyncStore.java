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

    private static final String PREF_FILES = "selective_sync_files_";

    private final SharedPreferences mPreferences;

    public SelectiveSyncStore(SharedPreferences preferences) {
        mPreferences = preferences;
    }

    public Set<String> getSelectedDirs(String folderId) {
        return new LinkedHashSet<>(readSet(PREF_DIRS + folderId));
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

    public void save(String folderId, Set<String> dirs, Set<String> files) {
        mPreferences.edit()
                .putStringSet(PREF_DIRS + folderId, new LinkedHashSet<>(dirs))
                .putStringSet(PREF_FILES + folderId, new LinkedHashSet<>(files))
                .apply();
    }

    public void clear(String folderId) {
        mPreferences.edit()
                .remove(PREF_DIRS + folderId)
                .remove(PREF_FILES + folderId)
                .apply();
    }

    private Set<String> readSet(String key) {
        Set<String> value = mPreferences.getStringSet(key, null);
        return (value == null) ? Collections.<String>emptySet() : value;
    }
}
