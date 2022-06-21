/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.encryption;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.primitives.Ints;

public class AesGcmOutputStream extends PositionOutputStream {
  // AES-GCM parameters
  public static final int GCM_NONCE_LENGTH = 12; // in bytes
  public static final int GCM_TAG_LENGTH = 16; // in bytes
  public static final int GCM_TAG_LENGTH_BITS = 8 * GCM_TAG_LENGTH;
  public static final String MAGIC_STRING = "GCM1";

  static final byte[] MAGIC_ARRAY = MAGIC_STRING.getBytes(StandardCharsets.UTF_8);
  static final int PREFIX_LENGTH = MAGIC_ARRAY.length + 4; // magic_len + block_size_len

  private PositionOutputStream targetStream;

  private Cipher gcmCipher;
  private SecureRandom random;
  private SecretKey key;
  private byte[] nonce;

  private int blockSize = 1024 * 1024;
  private byte[] plaintextBlockBuffer;
  private int positionInBuffer;
  private long streamPosition;
  private int currentBlockIndex;
  private byte[] fileAadPrefix;

  AesGcmOutputStream(PositionOutputStream targetStream, byte[] aesKey, byte[] fileAadPrefix) throws IOException {
    this.targetStream = targetStream;
    try {
      gcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
    this.random = new SecureRandom();
    this.nonce = new byte[GCM_NONCE_LENGTH];
    this.key = new SecretKeySpec(aesKey, "AES");
    this.plaintextBlockBuffer = new byte[blockSize];
    this.positionInBuffer = 0;
    this.streamPosition = 0;
    this.currentBlockIndex = 0;
    this.fileAadPrefix = fileAadPrefix;

    byte[] prefixBytes = ByteBuffer.allocate(PREFIX_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        .put(MAGIC_ARRAY)
        .putInt(blockSize)
        .array();
    targetStream.write(prefixBytes);
  }

  @Override
  public void write(int b) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void write(byte[] b)  throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    int remaining = len;
    int offset = off;

    while (remaining > 0) {
      int freeBlockBytes = blockSize - positionInBuffer;
      int toWrite = freeBlockBytes <= remaining ? freeBlockBytes : remaining;

      System.arraycopy(b, offset, plaintextBlockBuffer, positionInBuffer, toWrite);
      positionInBuffer += toWrite;
      if (positionInBuffer == blockSize) {
        encryptAndWriteBlock();
        positionInBuffer = 0;
      }
      offset += toWrite;
      remaining -= toWrite;
    }

    streamPosition += len;
  }

  @Override
  public long getPos() throws IOException {
    return streamPosition;
  }

  @Override
  public void flush() throws IOException {
    targetStream.flush();
  }

  @Override
  public void close() throws IOException {
    if (positionInBuffer > 0) {
      encryptAndWriteBlock();
    }
    targetStream.close();
  }

  private void encryptAndWriteBlock() throws IOException {
    random.nextBytes(nonce);
    GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
    try {
      gcmCipher.init(Cipher.ENCRYPT_MODE, key, spec);
    } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
      throw new IOException("Failed to init GCM cipher", e);
    }

    byte[] aad = calculateAAD(fileAadPrefix, currentBlockIndex);
    gcmCipher.updateAAD(aad);

    byte[] cipherText = new byte[GCM_NONCE_LENGTH + positionInBuffer + GCM_TAG_LENGTH];
    System.arraycopy(nonce, 0, cipherText, 0, GCM_NONCE_LENGTH);
    try {
      int encrypted = gcmCipher.doFinal(plaintextBlockBuffer, 0, positionInBuffer, cipherText, GCM_NONCE_LENGTH);
      Preconditions.checkArgument((encrypted == (positionInBuffer + GCM_TAG_LENGTH)),
          "Wrong length of encrypted output: " + encrypted + " vs " + (positionInBuffer + GCM_TAG_LENGTH));
    } catch (GeneralSecurityException e) {
      throw new IOException("Failed to encrypt", e);
    }

    currentBlockIndex++;

    targetStream.write(cipherText);
  }

  static byte[] calculateAAD(byte[] fileAadPrefix, int currentBlockIndex) {
    byte[] blockAAD = Ints.toByteArray(currentBlockIndex);
    if (null == fileAadPrefix) {
      return blockAAD;
    } else {
      byte[] aad = new byte[fileAadPrefix.length + 4];
      System.arraycopy(fileAadPrefix, 0, aad, 0, fileAadPrefix.length);
      System.arraycopy(blockAAD, 0, aad, fileAadPrefix.length, 4);
      return aad;
    }
  }
}
