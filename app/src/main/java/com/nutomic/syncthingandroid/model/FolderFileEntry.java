package com.nutomic.syncthingandroid.model;

/**
 * 单个文件在全局模型中的信息，取自 {@code GET /rest/db/file}。
 *
 * <p>实测返回结构（syncthing v2.1.6）：
 * <pre>
 * {
 *   "availability": [ { "id": "DEVICE-ID", "fromTemporary": false } ],
 *   "global": {...},
 *   "local": {...}
 * }
 * </pre>
 * 注意 availability 是<b>对象数组</b>而不是字符串数组；
 * 早期版本按 String[] 解析，导致永远被判定为"远端无副本"。
 */
public class FolderFileEntry {

    public static final class Availability {
        public String id;
        public boolean fromTemporary;
    }

    public Availability[] availability;

    /**
     * @param localDeviceId 本机设备 ID
     * @return 除本机之外，是否还有其它设备持有该文件的完整内容
     */
    public boolean hasRemoteCopy(String localDeviceId) {
        if (availability == null) {
            return false;
        }
        for (Availability item : availability) {
            if (item == null || item.id == null || item.fromTemporary) {
                continue;
            }
            if (localDeviceId == null || !item.id.equals(localDeviceId)) {
                return true;
            }
        }
        return false;
    }
}
