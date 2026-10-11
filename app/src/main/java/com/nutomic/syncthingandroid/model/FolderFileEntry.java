package com.nutomic.syncthingandroid.model;

/**
 * 单个文件在全局模型中的信息，取自 {@code GET /rest/db/file}。
 *
 * <p>availability 保存"拥有该文件全部数据块的设备 ID 列表"，
 * 用来判断取消勾选后删除本地文件是否安全。
 */
public class FolderFileEntry {

    public String name;

    public long size;

    public long modified;

    public boolean deleted;

    public boolean invalid;

    /** 拥有完整内容的设备 ID；为空表示只有本机有该文件。 */
    public String[] availability;

    /**
     * @param localDeviceId 本机设备 ID
     * @return 除本机之外，是否还有其它设备持有该文件
     */
    public boolean hasRemoteCopy(String localDeviceId) {
        if (availability == null) {
            return false;
        }
        for (String deviceId : availability) {
            if (deviceId == null) {
                continue;
            }
            if (localDeviceId == null || !deviceId.equals(localDeviceId)) {
                return true;
            }
        }
        return false;
    }
}
