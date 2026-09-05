// IFileBridgeService.aidl
// 在 Shizuku UserService 进程（shell/root 身份）内执行的文件桥接接口。
// 主进程通过 Shizuku.bindUserService 调用本接口，从而突破 Android/data 访问限制。
package com.dhhxfggg.pjm;

interface IFileBridgeService {
    /**
     * 列出目录内容。
     * @param path 绝对路径，如 /storage/emulated/0/Android/data/tv.danmaku.bili/download
     * @return 每行格式: type|name|size（type: D=目录, F=文件）
     */
    String[] listFiles(String path);

    /**
     * 复制文件到目标路径。
     * @param srcPath 源文件绝对路径
     * @param destPath 目标文件绝对路径
     * @return 成功复制的字节数；失败返回 -1
     */
    long copyFile(String srcPath, String destPath);

    /**
     * 删除文件或目录（目录递归删除）。
     * @return 是否成功
     */
    boolean deletePath(String path);

    /**
     * 检查路径是否存在。
     */
    boolean exists(String path);

    /**
     * 读取小文本文件（如 entry.json）。
     * @return 文件内容，失败返回 null
     */
    String readTextFile(String path);

    /**
     * 销毁服务进程（Shizuku 保留事务码）。
     */
    void destroy();
}
