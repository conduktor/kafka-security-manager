package io.conduktor.ksm.source;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

public final class Content {
  private Content() {}

  public static String utf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  public static byte[] read(InputStream input, int maxBytes) throws IOException {
    byte[] bytes = input.readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) throw new IOException("Source exceeds max.bytes");
    return bytes;
  }
}
