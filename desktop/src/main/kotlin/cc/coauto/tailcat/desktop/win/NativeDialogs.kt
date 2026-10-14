package cc.coauto.tailcat.desktop.win

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * 原生选择对话框。
 *
 * 注意：AWT 的 [FileDialog] 在 Windows 上**不能选目录**（`apple.awt.fileDialogForDirectories`
 * 只对 macOS 有效），所以「选择目录」必须退回 Swing 的 [JFileChooser]。
 */
public object NativeDialogs {

    public fun chooseDirectory(title: String, initial: String? = null): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            initial?.let { path -> File(path).takeIf { it.isDirectory }?.let { currentDirectory = it } }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    /**
     * 选择文件**或目录**。
     *
     * 上传要支持整个目录（`adb push` 对目录是递归的），而 [FileDialog] 只能选文件，
     * 所以这里同样退回 [JFileChooser]。APK 之类的纯文件场景仍用 [chooseFile]，
     * 那样能带上扩展名过滤。
     */
    public fun chooseFileOrDirectory(title: String, initial: String? = null): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = false
            initial?.let { path ->
                val file = File(path)
                val start = if (file.isDirectory) file else file.parentFile
                if (start != null && start.isDirectory) currentDirectory = start
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    public fun chooseFile(title: String, extension: String? = null): String? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extension != null) {
            dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
        }
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val fileName = dialog.file ?: return null
        return File(directory, fileName).absolutePath
    }

    /** 让 Swing 对话框跟系统外观一致，免得在 Windows 上突兀。 */
    public fun applySystemLookAndFeel() {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }
}
