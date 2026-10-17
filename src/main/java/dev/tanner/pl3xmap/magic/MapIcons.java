package dev.tanner.pl3xmap.magic;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.image.IconImage;

final class MapIcons {
    static final String WAND = "pl3xmap-magic-wand";
    static final String WARP = "pl3xmap-magic-warp";

    private MapIcons() {
    }

    static void register(Pl3xMap api) {
        register(api, WAND, wand());
        register(api, WARP, marker(new Color(70, 140, 255)));
    }

    private static void register(Pl3xMap api, String key, BufferedImage image) {
        if (api.getIconRegistry().has(key)) {
            return;
        }
        api.getIconRegistry().register(key, new IconImage(key, image, "png"));
    }

    private static BufferedImage wand() {
        BufferedImage image = canvas();
        Graphics2D graphics = graphics(image);
        graphics.setColor(new Color(92, 48, 168));
        graphics.fillRoundRect(14, 8, 4, 18, 3, 3);
        graphics.setColor(new Color(244, 208, 96));
        graphics.fillOval(11, 3, 10, 10);
        graphics.dispose();
        return image;
    }

    static BufferedImage marker(Color color) {
        BufferedImage image = canvas();
        Graphics2D graphics = graphics(image);
        graphics.setColor(new Color(35, 35, 35));
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawLine(8, 6, 8, 27);
        graphics.setColor(color);
        graphics.fillPolygon(new int[]{8, 26, 8}, new int[]{6, 13, 20}, 3);
        graphics.dispose();
        return image;
    }

    private static BufferedImage canvas() {
        return new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return graphics;
    }
}
