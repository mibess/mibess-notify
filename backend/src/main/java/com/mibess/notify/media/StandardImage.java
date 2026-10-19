package com.mibess.notify.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** A compact generic fallback; application branding can be supplied by upload. */
public final class StandardImage {

  private StandardImage() {}

  public static byte[] create(String name, int width, int height) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    try {
      g.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON
      );
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON
      );
      g.setColor(new Color(15, 23, 42));
      g.fillRect(0, 0, width, height);
      double scale = Math.min(width / 960.0, height / 320.0);
      g.translate((width - 960 * scale) / 2, (height - 320 * scale) / 2);
      g.scale(scale, scale);
      g.setColor(new Color(34, 211, 168));
      g.fillRoundRect(56, 48, 44, 5, 5, 5);
      g.setColor(new Color(148, 163, 184));
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
      g.drawString("NOTIFICAÇÕES", 56, 88);
      int size = 54;
      do {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size--));
      } while (g.getFontMetrics().stringWidth(name) > 848 && size > 6);
      g.setColor(Color.WHITE);
      g.drawString(name, 56, 177);
      g.setColor(new Color(148, 163, 184));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
      g.drawString("Informações e atualizações", 56, 215);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
      g.drawString("MIBESS NOTIFY", 56, 273);
    } finally {
      g.dispose();
    }
    try (var output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpeg", output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao criar imagem padrão", e);
    }
  }
}
