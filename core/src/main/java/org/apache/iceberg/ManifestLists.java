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
package org.apache.iceberg;

import java.io.IOException;
import java.util.List;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.encryption.PlaintextEncryptionManager;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;

class ManifestLists {
  private ManifestLists() {}

  static List<ManifestFile> read(InputFile manifestList) {
    try (CloseableIterable<ManifestFile> files =
        Avro.read(manifestList)
            .rename("manifest_file", GenericManifestFile.class.getName())
            .rename("partitions", GenericPartitionFieldSummary.class.getName())
            .rename("r508", GenericPartitionFieldSummary.class.getName())
            .classLoader(GenericManifestFile.class.getClassLoader())
            .project(ManifestFile.schema())
            .reuseContainers(false)
            .build()) {

      return Lists.newLinkedList(files);

    } catch (IOException e) {
      throw new RuntimeIOException(
          e, "Cannot read manifest list file: %s", manifestList.location());
    }
  }

  static ManifestListWriter write(
      int formatVersion,
      OutputFile manifestListFile,
      long snapshotId,
      Long parentSnapshotId,
      long sequenceNumber) {
    return write(
        formatVersion,
        PlaintextEncryptionManager.instance(),
        manifestListFile,
        snapshotId,
        parentSnapshotId,
        sequenceNumber);
  }

  static ManifestListWriter write(
      int formatVersion,
      EncryptionManager encryptionManager,
      OutputFile manifestListFile,
      long snapshotId,
      Long parentSnapshotId,
      long sequenceNumber) {
    switch (formatVersion) {
      case 1:
        Preconditions.checkArgument(
            sequenceNumber == TableMetadata.INITIAL_SEQUENCE_NUMBER,
            "Invalid sequence number for v1 manifest list: %s",
            sequenceNumber);
        return new ManifestListWriter.V1Writer(
            manifestListFile, encryptionManager, snapshotId, parentSnapshotId);
      case 2:
        return new ManifestListWriter.V2Writer(
<<<<<<< HEAD
            manifestListFile, snapshotId, parentSnapshotId, sequenceNumber);
      case 3:
        return new ManifestListWriter.V3Writer(
            manifestListFile, snapshotId, parentSnapshotId, sequenceNumber);
=======
            manifestListFile, encryptionManager, snapshotId, parentSnapshotId, sequenceNumber);
>>>>>>> 684e5e3f3 (draft commit)
    }
    throw new UnsupportedOperationException(
        "Cannot write manifest list for table version: " + formatVersion);
  }
}
