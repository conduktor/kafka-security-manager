package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.net.URI;

public final class GitLabSourceAcl extends RemoteSourceAcl {
  public GitLabSourceAcl(AclParserRegistry parsers) {
    super(parsers);
  }

  public String configPrefix() {
    return "gitlab";
  }

  public void configure(Config c) {
    uri =
        URI.create(
            base(c.getString("hostname"))
                + "/api/v4/projects/"
                + encoded(c.getString("repoid"))
                + "/repository/files/"
                + encoded(c.getString("filepath"))
                + "?ref="
                + encoded(c.getString("branch")));
    parser = parsers.getParserByFilename(c.getString("filepath"));
    base64Json = true;
    headers.put("PRIVATE-TOKEN", c.getString("accesstoken"));
    initialize(c);
  }
}
