import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 由项目图标原图生成 Windows 应用图标 {@code desktop/icons/TailcatManager.ico}。
 *
 * 原图就是上游仓库的 {@code resources/AppIcon.png}（官方 Tailcat 猫图衍生，BSD 3-Clause，
 * 见 THIRD_PARTY_NOTICES.md），原样放在 {@code desktop/src/main/resources/icons/app-icon.png}：
 * 一处文件同时供运行时取用（窗口与托盘图标，见 AppIcons）和这里生成 exe 图标，
 * 三处图形因此必然一致。
 *
 * 严格遵守「不改图形」：只做整体缩放，不裁剪、不描边、不加底色。
 *
 * 用法（仓库根目录）：
 * <pre>java desktop/icons/GenerateIcon.java</pre>
 *
 * 不放进 Gradle 构建：图标是提交进仓库的静态资源，只在换图时手工重跑一次。
 */
public class GenerateIcon {

    /** exe 图标要覆盖 Windows 各处取用尺寸：16 托盘/列表、32 任务栏、48 桌面、256 大图标视图。 */
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    /** 只有 256 用 PNG 压（BMP 单这一张就要 256KB）。其余用 BMP：Windows 两者都认，但 .NET 的
     *  {@code System.Drawing.Icon} 不解析 PNG 条目，用 BMP 才能把每个尺寸都验一遍。 */
    private static final int PNG_FROM = 256;

    private static final String SRC = "desktop/src/main/resources/icons/app-icon.png";

    public static void main(String[] args) throws Exception {
        File src = new File(args.length > 0 ? args[0] : SRC);
        File out = new File(args.length > 1 ? args[1] : "desktop/icons/TailcatManager.ico");
        if (!src.isFile()) throw new FileNotFoundException("找不到图标原图：" + src.getPath());
        out.getParentFile().mkdirs();

        BufferedImage source = ImageIO.read(src);
        System.out.println("source " + src.getPath() + " " + source.getWidth() + "x" + source.getHeight());

        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(size >= PNG_FROM ? png(scale(source, size)) : dib(scale(source, size)));
        }

        try (DataOutputStream d = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            writeLE16(d, 0);              // reserved
            writeLE16(d, 1);              // type: icon
            writeLE16(d, SIZES.length);
            int offset = 6 + 16 * SIZES.length;
            for (int i = 0; i < SIZES.length; i++) {
                int size = SIZES[i];
                byte[] data = images.get(i);
                d.writeByte(size >= 256 ? 0 : size);   // 0 表示 256
                d.writeByte(size >= 256 ? 0 : size);
                d.writeByte(0);                        // 调色板数
                d.writeByte(0);                        // 保留
                writeLE16(d, 1);                       // 色彩平面
                writeLE16(d, 32);                      // 位深
                writeLE32(d, data.length);
                writeLE32(d, offset);
                offset += data.length;
            }
            for (byte[] data : images) d.write(data);
        }
        System.out.println("wrote " + out.getPath() + "  (" + SIZES.length + " sizes)");
    }

    /**
     * 逐级折半缩放。
     *
     * 一次从 1254 直接缩到 16 会严重走样（采样点太少），先反复折半到接近目标再用双线性收尾，
     * 这是缩图的标准做法。像素画在这种处理下不会糊成一片。
     */
    private static BufferedImage scale(BufferedImage src, int target) {
        BufferedImage cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2;
            h /= 2;
            cur = resize(cur, w, h);
        }
        return (w == target && h == target) ? cur : resize(cur, target, target);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** ICO 里的 BMP 条目：BITMAPINFOHEADER + 自下而上的 BGRA + 全零 AND 掩码。 */
    private static byte[] dib(BufferedImage img) throws IOException {
        int w = img.getWidth();
        int h = img.getHeight();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        writeLE32(d, 40);                 // biSize
        writeLE32(d, w);                  // biWidth
        writeLE32(d, h * 2);              // biHeight —— ICO 里要写两倍（含掩码）
        writeLE16(d, 1);                  // biPlanes
        writeLE16(d, 32);                 // biBitCount
        writeLE32(d, 0);                  // biCompression = BI_RGB
        writeLE32(d, w * h * 4);          // biSizeImage
        writeLE32(d, 0);                  // biXPelsPerMeter
        writeLE32(d, 0);                  // biYPelsPerMeter
        writeLE32(d, 0);                  // biClrUsed
        writeLE32(d, 0);                  // biClrImportant
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                d.writeByte(argb & 0xFF);          // B
                d.writeByte((argb >> 8) & 0xFF);   // G
                d.writeByte((argb >> 16) & 0xFF);  // R
                d.writeByte((argb >>> 24) & 0xFF); // A
            }
        }
        int maskRowBytes = ((w + 31) / 32) * 4;
        d.write(new byte[maskRowBytes * h]);   // 32bpp 下掩码不起作用，全零即可
        return bos.toByteArray();
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void writeLE16(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }

    private static void writeLE32(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
        d.writeByte((v >> 16) & 0xFF);
        d.writeByte((v >> 24) & 0xFF);
    }
}
