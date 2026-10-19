package com.nutomic.syncthingandroid.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 选择性同步的"选择集"持久化。
 *
 * <p>为什么不能只靠 .stignore 反解：反解拿得到"哪些路径被放行"，但拿不到"用户当初是勾了整个
 * 目录还是只勾了其中几个子项"之外的信息，更拿不到"关闭选择性同步后仍要保留的选择集"。
 * 所以勾选结果单独存一份，.stignore 只作为"当前是否生效"的事实来源。
 *
 * <p>存储格式刻意做得非常简单（每行一条 {@code D:path} 或 {@code F:path}），
 * 不引第三方序列化：路径里出现换行的情况在真实文件系统中不存在。
 */
public final class SelectiveSyncStore {

    /** 整棵子树都被选中的目录。 */
    public static final String PREFIX_FULL_DIR = "D:";

    /** 只选中自身的项（单个文件，或"只放了部分子项"的目录）。 */
    public static final String PREFIX_SELF_ONLY = "F:";

    private static final String PREF_ENABLED = "selective_sync_enabled_";
    private static final String PREF_PAUSED = "selective_sync_paused_";
    private static final String PREF_SELECTION = "selective_sync_selection_";
    private static final String PREF_EXPANDED = "selective_sync_expanded_";

    private final SharedPreferences mPrefs;

    public SelectiveSyncStore(Context context) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** 该文件夹是否开启了选择性同步。 */
    public boolean isEnabled(String folderId) {
        return mPrefs.getBoolean(PREF_ENABLED + folderId, false);
    }

    public void setEnabled(String folderId, boolean enabled) {
        mPrefs.edit().putBoolean(PREF_ENABLED + folderId, enabled).apply();
    }

    /** 是否处于"暂停下载"状态：托管块里只有"忽略一切"。 */
    public boolean isPaused(String folderId) {
        return mPrefs.getBoolean(PREF_PAUSED + folderId, false);
    }

    public void setPaused(String folderId, boolean paused) {
        mPrefs.edit().putBoolean(PREF_PAUSED + folderId, paused).apply();
    }

    /** 勾选项（含整目录与仅自身两类），保持插入顺序。 */
    public List<String> selectedPaths(String folderId) {
        List<String> out = new ArrayList<>();
        for (String line : readSelection(folderId)) {
            out.add(line.substring(2));
        }
        return out;
    }

    /** 整棵子树都被选中的目录集合。 */
    public Set<String> fullDirs(String folderId) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : readSelection(folderId)) {
            if (line.startsWith(PREFIX_FULL_DIR)) {
                out.add(line.substring(2));
            }
        }
        return out;
    }

    public boolean hasSelection(String folderId) {
        return !readSelection(folderId).isEmpty();
    }

    /**
     * 展开过的目录路径。
     *
     * <p>不持久化的话，每次打开目录树都只有根目录一层，用户得一路重新点开；
     * 更糟的是深层路径在 rows 里不存在，反解时判断不出它是目录还是文件，
     * 只能先塞进 partialDirs —— 文件因此永远显示成"未勾选"。
     */
    public Set<String> expandedPaths(String folderId) {
        Set<String> out = new LinkedHashSet<>();
        String raw = mPrefs.getString(PREF_EXPANDED + folderId, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    public void saveExpanded(String folderId, Set<String> paths) {
        mPrefs.edit().putString(
                PREF_EXPANDED + folderId,
                paths == null ? "" : TextUtils.join("\n", paths)
        ).apply();
    }

    /**
     * 覆盖写入选择集。
     *
     * @param selected 所有被勾选的路径
     * @param fullDirs 其中整棵子树都被选中的目录
     */
    public void save(String folderId, List<String> selected, Set<String> fullDirs) {
        StringBuilder sb = new StringBuilder();
        if (selected != null) {
            for (String path : selected) {
                if (path == null || path.isEmpty()) {
                    continue;
                }
                sb.append(fullDirs != null && fullDirs.contains(path) ? PREFIX_FULL_DIR : PREFIX_SELF_ONLY);
                sb.append(path).append('\n');
            }
        }
        mPrefs.edit().putString(PREF_SELECTION + folderId, sb.toString()).apply();
    }

    /** 清空选择集（文件夹被删除时用，避免留下孤儿数据）。 */
    public void clear(String folderId) {
        mPrefs.edit()
                .remove(PREF_ENABLED + folderId)
                .remove(PREF_PAUSED + folderId)
                .remove(PREF_SELECTION + folderId)
                .remove(PREF_EXPANDED + folderId)
                .apply();
    }

    private List<String> readSelection(String folderId) {
        String raw = mPrefs.getString(PREF_SELECTION + folderId, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.startsWith(PREFIX_FULL_DIR) || line.startsWith(PREFIX_SELF_ONLY)) {
                if (line.length() > 2) {
                    out.add(line);
                }
            }
        }
        return out;
    }
}
