package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.net.URI;

public final class GitHubSourceAcl extends RemoteSourceAcl {
  public GitHubSourceAcl(AclParserRegistry parsers) {
    super(parsers);
  }

  public String configPrefix() {
    return "github";
  }

  public void configure(Config c) {
    uri =
        URI.create(
            base(c.getString("hostname"))
                + "/repos/"
                + encoded(c.getString("user"))
                + "/"
                + encoded(c.getString("repo"))
                + "/contents/"
                + path(c.getString("filepath"))
                + "?ref="
                + encoded(c.getString("branch")));
    parser = parsers.getParserByFilename(c.getString("filepath"));
    base64Json = true;
    headers.put("Accept", "application/vnd.github+json");
    if (c.hasPath("auth.basic")) basic(c.getString("auth.basic"));
    if (c.hasPath("auth.token"))
      headers.put("Authorization", "Bearer " + c.getString("auth.token"));
    initialize(c);
  }
}
