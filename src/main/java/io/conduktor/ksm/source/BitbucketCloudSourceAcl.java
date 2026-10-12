package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.net.URI;

public final class BitbucketCloudSourceAcl extends RemoteSourceAcl {
  public BitbucketCloudSourceAcl(AclParserRegistry parsers) {
    super(parsers);
  }

  public String configPrefix() {
    return "bitbucket-cloud";
  }

  public void configure(Config c) {
    uri =
        URI.create(
            base(c.getString("api.url"))
                + "/repositories/"
                + encoded(c.getString("organization"))
                + "/"
                + encoded(c.getString("repo"))
                + "/src/"
                + encoded(c.hasPath("branch") ? c.getString("branch") : "master")
                + "/"
                + path(c.getString("filepath")));
    parser = parsers.getParserByFilename(c.getString("filepath"));
    basic(c.getString("auth.username") + ":" + c.getString("auth.password"));
    initialize(c);
  }
}
