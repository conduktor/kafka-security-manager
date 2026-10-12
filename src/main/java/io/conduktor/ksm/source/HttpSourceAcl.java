package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import io.conduktor.ksm.source.security.GoogleIAM;
import java.net.URI;
import java.util.*;

public final class HttpSourceAcl extends RemoteSourceAcl {
  private GoogleIAM authentication;

  public HttpSourceAcl(AclParserRegistry parsers) {
    super(parsers);
  }

  public String configPrefix() {
    return "http";
  }

  public void configure(Config config) {
    uri = URI.create(config.getString("url"));
    parser = parsers.getParser(config.getString("parser"));
    method = config.getString("method").toUpperCase(Locale.ROOT);
    if (method.equals("HEAD"))
      throw new IllegalArgumentException("HTTP source method must return the ACL document");
    switch (config.getString("auth.type").toLowerCase(Locale.ROOT)) {
      case "none" -> {}
      case "googleiam", "google_iam" ->
          authentication = new GoogleIAM(config.getConfig("auth.googleiam"));
      default -> throw new IllegalArgumentException("Unknown HTTP authentication type");
    }
    initialize(config);
  }

  @Override
  protected Map<String, String> requestHeaders() throws Exception {
    Map<String, String> result = super.requestHeaders();
    if (authentication != null) result.put("Authorization", authentication.authorization());
    return result;
  }
}
