package com.mibess.notify.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** A repeatable identity card with exact typography; no generated logos or text. */
public final class StandardImage {

  private StandardImage() {}

  public static byte[] create(String name, int width, int height) {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    try {
      g.scale(width / 1200.0, height / 630.0);
      g.setRenderingHint(
        RenderingHints.KEY_ANTIALIASING,
        RenderingHints.VALUE_ANTIALIAS_ON
      );
      g.setColor(new Color(15, 23, 42));
      g.fillRect(0, 0, 1200, 630);
      g.setColor(new Color(34, 211, 168));
      g.fillRoundRect(80, 90, 72, 10, 10, 10);
      g.setColor(new Color(148, 163, 184));
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
      g.drawString("CENTRAL DE NOTIFICAÇÕES", 80, 164);
      int size = 76;
      do {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size--));
      } while (g.getFontMetrics().stringWidth(name) > 1030 && size > 6);
      g.setColor(Color.WHITE);
      g.drawString(name, 80, 330);
      g.setColor(new Color(34, 211, 168));
      g.fillRect(80, 385, 1040, 2);
      g.setColor(new Color(148, 163, 184));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24));
      g.drawString("Informações e atualizações", 80, 447);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
      g.drawString("MIBESS NOTIFY", 80, 549);
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
