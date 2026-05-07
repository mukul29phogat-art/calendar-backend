package com.childcarewow.calendar.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Lets the filter consume the body once for hashing and the controller re-read it. Spring's {@code
 * ContentCachingRequestWrapper} only caches AFTER the stream is read by the chain, which means the
 * filter would see an empty buffer at hash time. This wrapper accepts the bytes up-front and
 * replays them to downstream consumers.
 */
class BodyCachingRequestWrapper extends HttpServletRequestWrapper {

  private byte[] cachedBody = new byte[0];

  BodyCachingRequestWrapper(HttpServletRequest request) {
    super(request);
  }

  void setCachedBody(byte[] body) {
    this.cachedBody = body == null ? new byte[0] : body;
  }

  @Override
  public ServletInputStream getInputStream() {
    final ByteArrayInputStream in = new ByteArrayInputStream(cachedBody);
    return new ServletInputStream() {
      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // not needed for synchronous reads
      }

      @Override
      public int read() throws IOException {
        return in.read();
      }
    };
  }
}
