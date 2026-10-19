package com.mibess.notify.media;

import java.io.*;
import javax.imageio.ImageIO;

/** Inspect headers before decoding; never allocate a bitmap with unbounded dimensions. */
public final class ImageRules {

  public record Validated(
    byte[] bytes,
    String contentType,
    int width,
    int height
  ) {}

  private ImageRules() {}

  public static Validated validate(
    byte[] bytes,
    String claimedType,
    int width,
    int height,
    int maxBytes
  ) {
    if (
      bytes.length == 0 || bytes.length > maxBytes
    ) throw new IllegalArgumentException(
      "A imagem deve ter até " + maxBytes / 1_000 + " KB."
    );
    try (
      var input = ImageIO.createImageInputStream(
        new ByteArrayInputStream(bytes)
      )
    ) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IllegalArgumentException(
        "Envie uma imagem JPG ou PNG válida."
      );
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        String type = switch (
          reader.getFormatName().toLowerCase(java.util.Locale.ROOT)
        ) {
          case "png" -> "image/png";
          case "jpeg", "jpg" -> "image/jpeg";
          default -> throw new IllegalArgumentException(
            "Envie apenas JPG ou PNG."
          );
        };
        if (!type.equals(claimedType)) throw new IllegalArgumentException(
          "O formato do arquivo não corresponde ao conteúdo da imagem."
        );
        if (
          reader.getWidth(0) != width || reader.getHeight(0) != height
        ) throw new IllegalArgumentException(
          "A imagem deve ter exatamente " + width + " × " + height + " px."
        );
        if (reader.read(0) == null) throw new IllegalArgumentException(
          "Imagem inválida."
        );
        return new Validated(bytes, type, width, height);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Não foi possível ler a imagem.");
    }
  }
}
