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

import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.ENCRYPTION_DATA_ALGORITHM;
import static org.apache.iceberg.TableProperties.ENCRYPTION_DATA_ALGORITHM_DEFAULT;
import static org.apache.iceberg.TableProperties.ENCRYPTION_DEK_LENGTH;
import static org.apache.iceberg.TableProperties.ENCRYPTION_DEK_LENGTH_DEFAULT;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Map;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.util.PropertyUtil;

/**
 * Encryption manager which in conjunction with a KMS can encrypt {@link OutputFile} and decrypt
 * {@link InputFile}. Envelope encryption uses a key wrapping strategy, where a Key Encryption Key
 * (KEK) is used to wrap or unwrap a Data Encryption Key DEK which is used to encrypt the underlying
 * files.
 *
 * <p>When generating new DEKs for OutputFiles, this class will first attempt to have the KMS
 * generate a new key. If the KMS does not support key generation a new key will be produced by
 * pulling bytes from a {@link SecureRandom} on the JVM writing the file.
 */
public class EnvelopeEncryptionManager implements EncryptionManager {
  private final EnvelopeConfiguration dataEncryptionConfig;
  private final KmsClient kmsClient;
  private final int dataKeyLength;
  private final boolean kmsGeneratedKeys;

  private transient volatile SecureRandom workerRNG = null;

  /**
   * @param kmsClient Client of KMS used to wrap/unwrap keys in envelope encryption
   * @param encryptionProperties encryption properties
   * @param tableKeyId table encryption key id
   */
  public EnvelopeEncryptionManager(
      KmsClient kmsClient, Map<String, String> encryptionProperties, String tableKeyId) {
    Preconditions.checkNotNull(
        kmsClient, "Cannot create EnvelopeEncryptionManager because KmsClient is null");
    Preconditions.checkNotNull(
        encryptionProperties,
        "Cannot create EnvelopeEncryptionManager because encryptionProperties are not passed");
    Preconditions.checkNotNull(
        tableKeyId,
        "Cannot create EnvelopeEncryptionManager because table encryption key ID is not specified");
    this.kmsClient = kmsClient;
    this.kmsGeneratedKeys = kmsClient.supportsKeyGeneration();

    String dataEncryptionAlgorithm =
        PropertyUtil.propertyAsString(
            encryptionProperties, ENCRYPTION_DATA_ALGORITHM, ENCRYPTION_DATA_ALGORITHM_DEFAULT);

    this.dataEncryptionConfig =
        EnvelopeConfiguration.builder()
            .singleWrap(tableKeyId)
            .useAlgorithm(EncryptionAlgorithm.valueOf(dataEncryptionAlgorithm))
            .build();

    this.dataKeyLength =
        PropertyUtil.propertyAsInt(
            encryptionProperties, ENCRYPTION_DEK_LENGTH, ENCRYPTION_DEK_LENGTH_DEFAULT);
  }

  @Override
  public EncryptedOutputFile encrypt(OutputFile rawOutput) {
    EnvelopeMetadata metadata = generateEnvelopeMetadata(dataEncryptionConfig);

    NativeFileCryptoParameters nativeEncryptParams =
        NativeFileCryptoParameters.create(metadata.dek())
            .encryptionAlgorithm(metadata.algorithm())
            .build();

    if (!(rawOutput instanceof NativelyEncryptedFile)) {
      throw new RuntimeException(
          "Can't natively encrypt "
              + rawOutput.location()
              + " because the class "
              + rawOutput.getClass()
              + " doesn't implement NativelyEncryptedFile interface");
    }

    ((NativelyEncryptedFile) rawOutput).setNativeCryptoParameters(nativeEncryptParams);

    return new BaseEncryptedOutputFile(rawOutput, metadata);
  }

  @Override
  public InputFile decrypt(EncryptedInputFile encrypted) {
    if (encrypted.keyMetadata().buffer() == null) { // unencrypted file
      return encrypted.encryptedInputFile();
    }
    EnvelopeMetadata metadata = EnvelopeMetadataParser.fromJson(encrypted.keyMetadata().buffer());
    ByteBuffer fileDek = kmsClient.unwrapKey(metadata.wrappedDek(), metadata.kekId());

    NativeFileCryptoParameters nativeDecryptParams =
        NativeFileCryptoParameters.create(fileDek).build();
    InputFile rawInput = encrypted.encryptedInputFile();

    if (!(rawInput instanceof NativelyEncryptedFile)) {
      throw new RuntimeException(
          "Can't natively decrypt "
              + rawInput.location()
              + " because the class "
              + rawInput.getClass()
              + " doesn't implement NativelyEncryptedFile interface");
    }

    ((NativelyEncryptedFile) rawInput).setNativeCryptoParameters(nativeDecryptParams);

    return rawInput;
  }

  private EnvelopeMetadata generateEnvelopeMetadata(EnvelopeConfiguration config) {
    String tableKekID = config.kekId();
    byte[] fileDek;
    String wrappedFileDEK;

    if (kmsGeneratedKeys) {
      KmsClient.KeyGenerationResult generatedDek = kmsClient.generateKey(tableKekID);
      fileDek = generatedDek.key().array();
      wrappedFileDEK = generatedDek.wrappedKey();
    } else {
      if (null == workerRNG) {
        workerRNG = new SecureRandom();
      }
      fileDek = new byte[dataKeyLength];
      workerRNG.nextBytes(fileDek);
      wrappedFileDEK = kmsClient.wrapKey(ByteBuffer.wrap(fileDek), tableKekID);
    }

    EnvelopeMetadata fileEnvelopeMetadata =
        new EnvelopeMetadata(tableKekID, wrappedFileDEK, config.algorithm());
    fileEnvelopeMetadata.setDek(ByteBuffer.wrap(fileDek));

    return fileEnvelopeMetadata;
  }
}
