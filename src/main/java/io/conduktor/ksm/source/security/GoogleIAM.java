package io.conduktor.ksm.source.security;

import com.google.auth.oauth2.*;
import com.typesafe.config.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class GoogleIAM {
  private final Config config;
  private IdTokenCredentials token;

  public GoogleIAM(Config config) {
    this.config = config;
    if (config.getString("target-audience").isBlank())
      throw new IllegalArgumentException("Google IAM requires target-audience");
  }

  public synchronized String authorization() throws IOException {
    if (token == null) {
      String key =
          config.hasPath("service-account-key") ? config.getString("service-account-key") : "";
      GoogleCredentials credentials =
          key.isBlank()
              ? GoogleCredentials.getApplicationDefault()
              : GoogleCredentials.fromStream(
                  new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
      if (!(credentials instanceof IdTokenProvider provider))
        throw new IOException("Google credentials must implement IdTokenProvider");
      token = credentials(provider, config.getString("target-audience"));
    }
    return token.getRequestMetadata().get("Authorization").getFirst();
  }

  public static IdTokenCredentials credentials(IdTokenProvider provider, String audience) {
    return IdTokenCredentials.newBuilder()
        .setIdTokenProvider(provider)
        .setTargetAudience(audience)
        .build();
  }
}
