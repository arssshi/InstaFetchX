import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Generates the legacy launcher icons (pre-API-26) for InstaFetchX. Run once. */
public class MakeIcons {
    public static void main(String[] args) throws Exception {
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            File dir = new File("app/src/main/res/" + dirs[i]);
            dir.mkdirs();
            BufferedImage img = draw(sizes[i]);
            File out = new File(dir, "ic_launcher.png");
            ImageIO.write(img, "PNG", out);
            System.out.println(out + " " + out.length() + " bytes");
        }
    }

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 48f;

        RoundRectangle2D bg = new RoundRectangle2D.Float(0, 0, size - 0.5f, size - 0.5f,
                size * 0.22f, size * 0.22f);
        // gradient background
        LinearGradientPaint paint = new LinearGradientPaint(0, 0, size, size,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0xF9, 0xA2, 0x3F), new Color(0xE1, 0x30, 0x6C), new Color(0x8B, 0x5C, 0xF6)});
        g.setPaint(paint);
        g.fill(bg);

        // white download arrow (matches ic_launcher_foreground)
        g.setColor(Color.WHITE);
        Polygon p = new Polygon();
        p.addPoint((int) (24 * s), (int) (17 * s));   // tip
        p.addPoint((int) (13 * s), (int) (33 * s));   // left shoulder
        p.addPoint((int) (20 * s), (int) (33 * s));   // left bottom
        p.addPoint((int) (20 * s), (int) (44 * s));   // left stem bottom
        p.addPoint((int) (28 * s), (int) (44 * s));   // right stem bottom
        p.addPoint((int) (28 * s), (int) (33 * s));   // right bottom
        p.addPoint((int) (35 * s), (int) (33 * s));   // right shoulder
        g.fillPolygon(p);

        // tray
        RoundRectangle2D tray = new RoundRectangle2D.Float(10 * s, 38 * s, 28 * s, 4.4f * s, 2.2f * s, 2.2f * s);
        g.fill(tray);

        g.dispose();
        return img;
    }
}
