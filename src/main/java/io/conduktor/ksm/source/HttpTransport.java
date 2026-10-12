package io.conduktor.ksm.source;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Bounds the whole request, including a slow or oversized response body. Never follows redirects.
 */
public final class HttpTransport implements AutoCloseable {
  private final HttpClient client;
  private final Duration timeout;
  private final int maxBytes;

  public HttpTransport(Duration timeout, int maxBytes) {
    if (timeout.isNegative() || timeout.isZero() || maxBytes < 1 || maxBytes == Integer.MAX_VALUE)
      throw new IllegalArgumentException("HTTP timeout and max.bytes must be positive and bounded");
    this.timeout = timeout;
    this.maxBytes = maxBytes;
    client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public HttpResponse<byte[]> send(
      URI uri, String method, Map<String, String> headers, byte[] payload) throws Exception {
    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null)
      throw new IllegalArgumentException("HTTP source requires http(s) and credentials in headers");
    var builder = HttpRequest.newBuilder(uri).timeout(timeout);
    headers.forEach(builder::header);
    var request =
        builder
            .method(
                method,
                payload == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
    var future = client.sendAsync(request, info -> new LimitedBody(maxBytes));
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      // Do not include URLs, request headers or response bodies in user-facing errors.
      throw new java.io.IOException(
          "HTTP request failed (" + e.getCause().getClass().getSimpleName() + ")");
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  public void close() {
    client.shutdownNow();
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int limit;
    private Flow.Subscription subscription;

    LimitedBody(int limit) {
      this.limit = limit;
    }

    public CompletionStage<byte[]> getBody() {
      return result.minimalCompletionStage();
    }

    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new java.io.IOException("Response exceeds max.bytes"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
