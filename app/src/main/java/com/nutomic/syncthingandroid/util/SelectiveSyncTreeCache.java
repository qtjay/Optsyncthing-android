package com.nutomic.syncthingandroid.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.nutomic.syncthingandroid.model.BrowseEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机缓存的远端目录结构。
 *
 * <p>目的：即使远端暂时不可达、或文件夹还没同步任何内容，用户也能立刻看到目录树并勾选；
 * 打开界面时再用网络结果刷新这份缓存，使其保持最新。
 */
public class SelectiveSyncTreeCache {

    private static final String TAG = "SelectiveSyncTreeCache";

    private static final String FILE_NAME = "selective_sync_tree.json";

    private final File mFile;

    private final Gson mGson = new Gson();

    private Root mRoot;

    public static final class Entry {
        public String name;
        public boolean dir;
    }

    public static final class Level {
        public long updatedAt;
        public List<Entry> entries = new ArrayList<>();
    }

    public static final class FolderTree {
        public Map<String, Level> levels = new LinkedHashMap<>();
    }

    public static final class Root {
        public Map<String, FolderTree> folders = new LinkedHashMap<>();
    }

    public SelectiveSyncTreeCache(Context context) {
        mFile = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    private void load() {
        if (!mFile.exists()) {
            mRoot = new Root();
            return;
        }
        try (Reader reader = new InputStreamReader(
                new java.io.FileInputStream(mFile), StandardCharsets.UTF_8)) {
            Root parsed = mGson.fromJson(reader, Root.class);
            mRoot = (parsed != null && parsed.folders != null) ? parsed : new Root();
        } catch (Exception e) {
            Log.w(TAG, "读取缓存失败，将重建", e);
            mRoot = new Root();
        }
    }

    public synchronized void save() {
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(mFile), StandardCharsets.UTF_8)) {
            mGson.toJson(mRoot, writer);
        } catch (Exception e) {
            Log.w(TAG, "写入缓存失败", e);
        }
    }

    /** 记录某一层的内容。prefix 为相对文件夹根目录的路径，根目录用 ""。 */
    public synchronized void putLevel(String folderId, String prefix, List<BrowseEntry> entries) {
        FolderTree tree = mRoot.folders.get(folderId);
        if (tree == null) {
            tree = new FolderTree();
            mRoot.folders.put(folderId, tree);
        }
        Level level = new Level();
        level.updatedAt = System.currentTimeMillis();
        if (entries != null) {
            for (BrowseEntry entry : entries) {
                if (entry == null || entry.name == null) {
                    continue;
                }
                Entry cached = new Entry();
                cached.name = entry.name;
                cached.dir = entry.isDirectory();
                level.entries.add(cached);
            }
        }
        tree.levels.put(prefix, level);
    }

    /** 读取缓存的某一层；无缓存时返回 null。 */
    public synchronized Level getLevel(String folderId, String prefix) {
        FolderTree tree = mRoot.folders.get(folderId);
        if (tree == null) {
            return null;
        }
        return tree.levels.get(prefix);
    }

    public synchronized boolean hasFolder(String folderId) {
        return mRoot.folders.containsKey(folderId);
    }

    public synchronized void clearFolder(String folderId) {
        mRoot.folders.remove(folderId);
    }
}
