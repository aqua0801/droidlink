package io.github.aqua0801.droidlink.desktop.platform

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import java.awt.Image as AwtImage

/** Puts an image file on the system clipboard as a bitmap, so it pastes into chat apps and editors. */
object ImageClipboard {
    /** Blocking (decodes the file); call off the UI thread. Returns false if it could not be copied. */
    fun copy(file: File): Boolean = runCatching {
        val image = ImageIO.read(file) ?: decodeWithSkia(file) ?: return false
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
        true
    }.getOrDefault(false)

    /** Fallback for formats ImageIO lacks (e.g. WebP, HEIF): re-encode to PNG first. */
    private fun decodeWithSkia(file: File): AwtImage? {
        val png = Image.makeFromEncoded(file.readBytes()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
        return ImageIO.read(ByteArrayInputStream(png))
    }

    private class ImageSelection(private val image: AwtImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
