package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;

/**
 * Row key helpers for the Bigtable conformance operations, built independently from the library.
 */
final class BigtableTestFixtures {
  private BigtableTestFixtures() {}

  /**
   * The row key prefix of an aggregate, built from the normative formula (FNV-1a 64, 16-bit length,
   * UTF-8) and not through the library's key class, so the tests keep an independent oracle.
   */
  static ByteString prefix(String aid) {
    byte[] a = aid.getBytes(StandardCharsets.UTF_8);
    long h = 0xcbf29ce484222325L;
    for (byte b : a) {
      h ^= (b & 0xffL);
      h *= 0x100000001b3L;
    }
    byte[] out = new byte[8 + 2 + a.length];
    for (int i = 0; i < 8; i++) out[i] = (byte) (h >>> (56 - 8 * i));
    out[8] = (byte) (a.length >>> 8);
    out[9] = (byte) a.length;
    System.arraycopy(a, 0, out, 10, a.length);
    return ByteString.copyFrom(out);
  }
}
