import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;

/**
 * 从 macOS 那份应用图标生成 Android 的自适应图标。
 *
 * 用法（JDK 11+ 可以直接跑单文件源码）：
 *
 * <pre>
 *   java android-shell/icons/GenerateIcon.java \
 *        legacy-macos/resources/AppIcon.png \
 *        android-shell/src/main/res
 * </pre>
 *
 * 为什么只出自适应图标（`mipmap-anydpi-v26`）而**不出传统的多密度 PNG**：
 * 本项目的 `minSdk` 是 26，而自适应图标正是 API 26 引入的 —— 也就是说所有能装这个包
 * 的设备都支持它。再摆一套方图/圆图 PNG 只是重复内容。
 *
 * 尺寸依据：自适应图标的画布固定 108dp，其中只有内圈 **72dp** 保证不被启动器的遮罩裁掉。
 * 源图是一张**透明底、居中、灰度**的画（实测不透明像素只占 42.5%，主色是深灰 #383838/#080808），
 * 不是满幅圆角图 —— 所以不能「铺满画布」，必须按**不透明内容的包围盒**缩放进那 72dp 里，
 * 否则图形会被启动器的圆形/方形遮罩切掉边角。
 */
public final class GenerateIcon {

    /** dp → px 的倍率，对应 Android 的五档密度。 */
    private static final String[] DENSITY_DIRS = {
            "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi",
    };
    private static final float[] DENSITY_SCALES = {1f, 1.5f, 2f, 3f, 4f};

    /** 自适应图标画布边长。 */
    private static final int CANVAS_DP = 108;

    /** 画布内保证不被遮罩裁掉的内圈。 */
    private static final int SAFE_DP = 72;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("用法: java GenerateIcon.java <源图标.png> <res 目录>");
            System.exit(2);
        }
        File source = new File(args[0]);
        File resDir = new File(args[1]);
        if (!source.isFile()) {
            System.err.println("找不到源图标: " + source.getAbsolutePath());
            System.exit(1);
        }

        BufferedImage src = ImageIO.read(source);
        if (src == null) {
            System.err.println("读不出图片（不是 PNG/JPEG？）: " + source.getAbsolutePath());
            System.exit(1);
        }
        System.out.printf("源图标 %s  %dx%d%n", source.getPath(), src.getWidth(), src.getHeight());

        Box content = opaqueBounds(src);
        System.out.printf("不透明内容包围盒 %d,%d %dx%d（占画布 %.0f%%）%n",
                content.x, content.y, content.w, content.h,
                100.0 * content.w / src.getWidth());

        for (int i = 0; i < DENSITY_DIRS.length; i++) {
            int canvas = Math.round(CANVAS_DP * DENSITY_SCALES[i]);
            int safe = Math.round(SAFE_DP * DENSITY_SCALES[i]);
            File dir = new File(resDir, DENSITY_DIRS[i]);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("建不出目录: " + dir);
            }
            File out = new File(dir, "ic_launcher_foreground.png");
            ImageIO.write(layer(src, content, canvas, safe), "png", out);
            System.out.printf("  %-34s 画布 %dpx，内容 %dpx%n",
                    DENSITY_DIRS[i] + "/ic_launcher_foreground.png", canvas, safe);
        }

        int bg = pickBackground(src, content);
        File valuesDir = new File(resDir, "values");
        if (!valuesDir.isDirectory() && !valuesDir.mkdirs()) {
            throw new IllegalStateException("建不出目录: " + valuesDir);
        }
        try (PrintWriter w = new PrintWriter(new File(valuesDir, "colors.xml"), "UTF-8")) {
            w.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            w.println("<!-- 由 android-shell/icons/GenerateIcon.java 从 macOS 图标取样生成，不要手改。 -->");
            w.println("<resources>");
            w.printf("    <color name=\"ic_launcher_background\">#%06X</color>%n", bg);
            w.println("</resources>");
        }
        System.out.printf("  values/colors.xml                  背景色 #%06X%n", bg);

        File anydpi = new File(resDir, "mipmap-anydpi-v26");
        if (!anydpi.isDirectory() && !anydpi.mkdirs()) {
            throw new IllegalStateException("建不出目录: " + anydpi);
        }
        for (String name : new String[]{"ic_launcher", "ic_launcher_round"}) {
            try (PrintWriter w = new PrintWriter(new File(anydpi, name + ".xml"), "UTF-8")) {
                w.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
                w.println("<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">");
                w.println("    <background android:drawable=\"@color/ic_launcher_background\" />");
                w.println("    <foreground android:drawable=\"@mipmap/ic_launcher_foreground\" />");
                w.println("</adaptive-icon>");
            }
        }
        System.out.println("  mipmap-anydpi-v26/ic_launcher.xml  (+ ic_launcher_round.xml)");
    }

    /**
     * 把源图里 [content] 这块内容按最大边缩放进 [safePx]，居中画到 [canvasPx] 的透明画布上。
     */
    private static BufferedImage layer(BufferedImage src, Box content, int canvasPx, int safePx) {
        BufferedImage out = new BufferedImage(canvasPx, canvasPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            double k = (double) safePx / Math.max(content.w, content.h);
            int w = (int) Math.round(content.w * k);
            int h = (int) Math.round(content.h * k);
            int x = (canvasPx - w) / 2;
            int y = (canvasPx - h) / 2;
            g.drawImage(src, x, y, x + w, y + h,
                    content.x, content.y, content.x + content.w, content.y + content.h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * 不透明内容的包围盒。
     *
     * ⚠️ 不能简单地取「第一个/最后一个 alpha>0 的像素」：实测这张图在紧贴边角处有几颗
     * 孤立的接近不透明的像素，那样算出来的包围盒就是整张画布，缩进安全区之后图形只剩
     * 一半大。所以按**行/列的覆盖量**来判定 —— 某一行里不透明像素太少就不算内容。
     */
    private static Box opaqueBounds(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int minPerLine = Math.max(2, (int) (Math.min(w, h) * 0.005));

        int left = 0, right = w - 1, top = 0, bottom = h - 1;
        while (left < w && columnCount(src, left) < minPerLine) left++;
        while (right > left && columnCount(src, right) < minPerLine) right--;
        while (top < h && rowCount(src, top) < minPerLine) top++;
        while (bottom > top && rowCount(src, bottom) < minPerLine) bottom--;

        if (right <= left || bottom <= top) {
            throw new IllegalStateException("这张图里找不到不透明内容，无法生成图标");
        }
        return new Box(left, top, right - left + 1, bottom - top + 1);
    }

    private static int columnCount(BufferedImage src, int x) {
        int n = 0;
        for (int y = 0; y < src.getHeight(); y++) {
            if (isContent(src.getRGB(x, y))) n++;
        }
        return n;
    }

    private static int rowCount(BufferedImage src, int y) {
        int n = 0;
        for (int x = 0; x < src.getWidth(); x++) {
            if (isContent(src.getRGB(x, y))) n++;
        }
        return n;
    }

    /** 半透明以上才算内容，把抗锯齿的淡淡边缘排除掉。 */
    private static boolean isContent(int argb) {
        return ((argb >>> 24) & 0xFF) >= 128;
    }

    /**
     * 背景色：按内容自身的明暗来定 —— **深色图案配白底，浅色图案配近黑底**。
     *
     * 取内容区域的平均亮度来判，而不是取某个角的像素：角上大概率是透明的，
     * 取样点稍微偏一点结果就完全不同（第一版就是这么取到 #000000 的）。
     */
    private static int pickBackground(BufferedImage src, Box content) {
        long sum = 0;
        long n = 0;
        for (int y = content.y; y < content.y + content.h; y++) {
            for (int x = content.x; x < content.x + content.w; x++) {
                int argb = src.getRGB(x, y);
                if (!isContent(argb)) continue;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                // 亮度用整数近似：Rec.601 的 0.299/0.587/0.114 放大到千分比
                sum += (299 * r + 587 * g + 114 * b) / 1000;
                n++;
            }
        }
        double avg = n == 0 ? 0 : (double) sum / n;
        int chosen = avg < 128 ? 0xFFFFFF : 0x1A1A1A;
        System.out.printf("内容平均亮度 %.0f/255 → 背景色 #%06X%n", avg, chosen);
        return chosen;
    }

    private static final class Box {
        final int x, y, w, h;

        Box(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private GenerateIcon() {
    }
}
