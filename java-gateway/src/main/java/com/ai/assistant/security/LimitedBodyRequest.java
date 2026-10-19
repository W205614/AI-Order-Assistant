package com.ai.assistant.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Counts bytes actually consumed, including unknown-length and chunked request bodies. */
public final class LimitedBodyRequest extends HttpServletRequestWrapper {
  public static final int MAX_BYTES = 128 * 1024;
  public static final class TooLarge extends IOException {
    public TooLarge() { super("请求内容过大"); }
  }
  private ServletInputStream limited;

  public LimitedBodyRequest(HttpServletRequest request) { super(request); }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (limited == null) {
      var input = super.getInputStream();
      limited = new ServletInputStream() {
        private long count;
        private void consumed(int amount) throws TooLarge {
          if (amount > 0 && (count += amount) > MAX_BYTES) throw new TooLarge();
        }
        @Override public int read() throws IOException {
          if (count > MAX_BYTES) throw new TooLarge();
          int value = input.read();
          if (value >= 0) consumed(1);
          return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          if (count > MAX_BYTES) throw new TooLarge();
          int amount = input.read(bytes, offset, (int) Math.min(length, MAX_BYTES - count + 1));
          consumed(amount);
          return amount;
        }
        @Override public boolean isFinished() { return input.isFinished(); }
        @Override public boolean isReady() { return input.isReady(); }
        @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
        @Override public void close() throws IOException { input.close(); }
      };
    }
    return limited;
  }

  @Override public BufferedReader getReader() throws IOException {
    String encoding = getCharacterEncoding();
    return new BufferedReader(new InputStreamReader(getInputStream(),
        encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
  }
}
