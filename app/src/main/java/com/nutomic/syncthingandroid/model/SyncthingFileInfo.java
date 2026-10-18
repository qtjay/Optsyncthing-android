package com.nutomic.syncthingandroid.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Response of {@code GET /rest/db/file?folder=...&file=...}.
 *
 * <p>Only the fields the selective-sync feature needs are declared; Gson ignores the rest.
 *
 * <p>两个 ignored 的含义完全不同，删文件时不能混用：
 * <ul>
 *   <li>{@code global.ignored} —— 全局模型里这一项处于被忽略状态；</li>
 *   <li>{@code local.ignored} —— <b>本机</b>这一份被忽略（即不同步到本地），
 *       这才是"本地这份已经不在同步范围内"的直接依据。</li>
 * </ul>
 */
public class SyncthingFileInfo {

    public static final String TYPE_DIRECTORY = "FILE_INFO_TYPE_DIRECTORY";

    public GlobalInfo global;
    public LocalInfo local;
    public List<Availability> availability;

    public static final class GlobalInfo {
        public long size = -1;
        public String type = "";
        public String modified = "";
        public boolean deleted = false;
        public boolean ignored = false;
    }

    public static final class LocalInfo {
        public long size = -1;
        public String modified = "";
        public boolean deleted = false;
        /** 本机这一份是否处于"被忽略"状态。 */
        public boolean ignored = false;
    }

    public static final class Availability {
        public String id = "";
    }

    /** 集群里是否存在这一项（已删除视作不存在）。 */
    public boolean exists() {
        return global != null && !global.deleted;
    }

    public boolean isDirectory() {
        return global != null && TYPE_DIRECTORY.equals(global.type);
    }

    public boolean ignoredLocal() {
        return local != null && local.ignored;
    }

    public long globalSize() {
        return global == null ? -1L : global.size;
    }

    public List<String> heldBy() {
        List<String> ids = new ArrayList<>();
        if (availability == null) {
            return ids;
        }
        for (Availability a : availability) {
            if (a != null && a.id != null && !a.id.isEmpty()) {
                ids.add(a.id);
            }
        }
        return ids;
    }

    /**
     * 除本机之外是否还有其它设备持有当前版本。
     *
     * <p>必须排除本机：要删的正是本机这一份，本机持有并不代表删掉之后还有备份。
     * 只看"availability 非空"会把"仅此一份"误判成安全，那正是会丢数据的地方。
     */
    public boolean hasRemoteCopy(String localDeviceId) {
        if (localDeviceId == null || localDeviceId.isEmpty()) {
            return !heldBy().isEmpty();
        }
        for (String id : heldBy()) {
            if (!localDeviceId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本机这一份是否已经与全局版本一致。
     *
     * <p>availability 列出的是"持有当前版本的设备"，本机在列就说明本机这份是最新且完整的。
     */
    public boolean inSyncWith(String localDeviceId) {
        if (localDeviceId == null || localDeviceId.isEmpty()) {
            return false;
        }
        return heldBy().contains(localDeviceId);
    }
}
