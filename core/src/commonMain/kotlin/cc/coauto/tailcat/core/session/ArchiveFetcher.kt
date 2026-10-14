package cc.coauto.tailcat.core.session

import cc.coauto.tailcat.core.tools.DirectDownload

/**
 * 下载并解压官方发布包。
 *
 * 放在平台侧是因为它要落盘。之所以要做这条路径：新电脑上很可能既没有 winget，
 * 也没有 Android SDK（连 `adb` 都没有），此时唯一的自助出路就是从官方地址直接拿。
 */
public interface ArchiveFetcher {
    /**
     * @param onProgress 面向用户的进度文本，会显示在安装日志里
     * @return 解压出来的可执行文件的**绝对路径**
     * @throws Exception 下载或解压失败；消息应当是可读的中文，会直接展示给用户
     */
    public suspend fun fetch(download: DirectDownload, onProgress: (String) -> Unit): String
}
