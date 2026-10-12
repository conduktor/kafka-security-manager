package io.conduktor.ksm.source;

import com.typesafe.config.Config;
import io.conduktor.ksm.parser.AclParserRegistry;
import java.net.URI;

public final class BitbucketServerSourceAcl extends RemoteSourceAcl {
  public BitbucketServerSourceAcl(AclParserRegistry parsers) {
    super(parsers);
  }

  public String configPrefix() {
    return "bitbucket-server";
  }

  public void configure(Config c) {
    uri =
        URI.create(
            c.getString("protocol")
                + "://"
                + c.getString("hostname")
                + ":"
                + c.getString("port")
                + "/rest/api/1.0/projects/"
                + encoded(c.getString("project"))
                + "/repos/"
                + encoded(c.getString("repo"))
                + "/raw/"
                + path(c.getString("filepath"))
                + (c.hasPath("branch") ? "?at=" + encoded(c.getString("branch")) : ""));
    parser = parsers.getParserByFilename(c.getString("filepath"));
    basic(c.getString("auth.username") + ":" + c.getString("auth.password"));
    initialize(c);
  }
}
