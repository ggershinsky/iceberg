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
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.encryption.EncryptedInputFile;
import org.apache.iceberg.encryption.EncryptionKeyMetadata;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.encryption.EncryptionUtil;
import org.apache.iceberg.encryption.PlaintextEncryptionManager;
import org.apache.iceberg.encryption.StandardEncryptionManager;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Objects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;

class BaseSnapshot implements Snapshot {
  private final long snapshotId;
  private final Long parentId;
  private final long sequenceNumber;
  private final long timestampMillis;
  private final String manifestListLocation;
  private final String operation;
  private final Map<String, String> summary;
  private final Integer schemaId;
  private final String[] v1ManifestLocations;
  private final String manifestListKeyMetadata;

  // lazily initialized
  private transient List<ManifestFile> allManifests = null;
  private transient List<ManifestFile> dataManifests = null;
  private transient List<ManifestFile> deleteManifests = null;
  private transient List<DataFile> addedDataFiles = null;
  private transient List<DataFile> removedDataFiles = null;
  private transient List<DeleteFile> addedDeleteFiles = null;
  private transient List<DeleteFile> removedDeleteFiles = null;

  /** Tests only */
  BaseSnapshot(
      long sequenceNumber,
      long snapshotId,
      Long parentId,
      long timestampMillis,
      String operation,
      Map<String, String> summary,
      Integer schemaId,
      String manifestList) {
    this(
        sequenceNumber,
        snapshotId,
        parentId,
        timestampMillis,
        operation,
        summary,
        schemaId,
        manifestList,
        null);
  }

  BaseSnapshot(
      long sequenceNumber,
      long snapshotId,
      Long parentId,
      long timestampMillis,
      String operation,
      Map<String, String> summary,
      Integer schemaId,
      String manifestList,
      String manifestListKeyMetadata) {
    this.sequenceNumber = sequenceNumber;
    this.snapshotId = snapshotId;
    this.parentId = parentId;
    this.timestampMillis = timestampMillis;
    this.operation = operation;
    this.summary = summary;
    this.schemaId = schemaId;
    this.manifestListLocation = manifestList;
    this.v1ManifestLocations = null;
    this.manifestListKeyMetadata = manifestListKeyMetadata;
  }

  BaseSnapshot(
      long sequenceNumber,
      long snapshotId,
      Long parentId,
      long timestampMillis,
      String operation,
      Map<String, String> summary,
      Integer schemaId,
      String[] v1ManifestLocations) {
    this.sequenceNumber = sequenceNumber;
    this.snapshotId = snapshotId;
    this.parentId = parentId;
    this.timestampMillis = timestampMillis;
    this.operation = operation;
    this.summary = summary;
    this.schemaId = schemaId;
    this.manifestListLocation = null;
    this.v1ManifestLocations = v1ManifestLocations;
    this.manifestListKeyMetadata = null;
  }

  @Override
  public long sequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public long snapshotId() {
    return snapshotId;
  }

  @Override
  public Long parentId() {
    return parentId;
  }

  @Override
  public long timestampMillis() {
    return timestampMillis;
  }

  @Override
  public String operation() {
    return operation;
  }

  @Override
  public Map<String, String> summary() {
    return summary;
  }

  @Override
  public Integer schemaId() {
    return schemaId;
  }

  @Override
  public String manifestKeyMetadata() {
    return manifestListKeyMetadata;
  }

  private void cacheManifests(FileIO fileIO, EncryptionManager encryption) {
    if (fileIO == null) {
      throw new IllegalArgumentException("Cannot cache changes: FileIO is null");
    }

    if (allManifests == null && v1ManifestLocations != null) {
      // if we have a collection of manifest locations, then we need to load them here
      allManifests =
          Lists.transform(
              Arrays.asList(v1ManifestLocations),
              location -> new GenericManifestFile(fileIO.newInputFile(location), 0));
    }

    if (allManifests == null) {
      // if manifests isn't set, then the snapshotFile is set and should be read to get the list

      InputFile manifestListFile = fileIO.newInputFile(manifestListLocation);

      if (manifestListKeyMetadata != null) { // encrypted manifest list file
        Preconditions.checkArgument(
            encryption != null, "Encryption manager not provided for encrypted manifest list file");
        Preconditions.checkArgument(
            encryption instanceof StandardEncryptionManager,
            "Encryption manager for encrypted manifest list files can currently only be an instance of "
                + StandardEncryptionManager.class);

        ByteBuffer keyMetadataBytes =
            ByteBuffer.wrap(Base64.getDecoder().decode(manifestListKeyMetadata));
        ByteBuffer unwrappedManfestListKey = null;
        // Unwrap manifest list key
        EncryptionKeyMetadata keyMetadata = EncryptionUtil.parseKeyMetadata(keyMetadataBytes);
        unwrappedManfestListKey =
            ((StandardEncryptionManager) encryption).unwrapKey(keyMetadata.encryptionKey());

        EncryptionKeyMetadata unwrappedKeyMetadata =
            EncryptionUtil.createKeyMetadata(unwrappedManfestListKey, keyMetadata.aadPrefix());

        EncryptedInputFile encryptedInputFile =
            EncryptedFiles.encryptedInput(manifestListFile, unwrappedKeyMetadata);
        manifestListFile = encryption.decrypt(encryptedInputFile);
      }

      this.allManifests = ManifestLists.read(manifestListFile);
    }

    if (dataManifests == null || deleteManifests == null) {
      this.dataManifests =
          ImmutableList.copyOf(
              Iterables.filter(
                  allManifests, manifest -> manifest.content() == ManifestContent.DATA));
      this.deleteManifests =
          ImmutableList.copyOf(
              Iterables.filter(
                  allManifests, manifest -> manifest.content() == ManifestContent.DELETES));
    }
  }

  @Override
  public List<ManifestFile> allManifests(FileIO fileIO) {
    return allManifests(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public List<ManifestFile> allManifests(FileIO fileIO, EncryptionManager encryption) {
    if (allManifests == null) {
      cacheManifests(fileIO, encryption);
    }
    return allManifests;
  }

  @Override
  public List<ManifestFile> dataManifests(FileIO fileIO) {
    return dataManifests(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public List<ManifestFile> dataManifests(FileIO fileIO, EncryptionManager encryption) {
    if (dataManifests == null) {
      cacheManifests(fileIO, encryption);
    }
    return dataManifests;
  }

  @Override
  public List<ManifestFile> deleteManifests(FileIO fileIO) {
    return deleteManifests(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public List<ManifestFile> deleteManifests(FileIO fileIO, EncryptionManager encryptionManager) {
    if (deleteManifests == null) {
      cacheManifests(fileIO, encryptionManager);
    }
    return deleteManifests;
  }

  @Override
  public List<DataFile> addedDataFiles(FileIO fileIO) {
    return addedDataFiles(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public List<DataFile> addedDataFiles(FileIO fileIO, EncryptionManager encryptionManager) {
    if (addedDataFiles == null) {
      cacheDataFileChanges(fileIO, encryptionManager);
    }
    return addedDataFiles;
  }

  @Override
  public List<DataFile> removedDataFiles(FileIO fileIO) {
    return removedDataFiles(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public List<DataFile> removedDataFiles(FileIO fileIO, EncryptionManager encryptionManager) {
    if (removedDataFiles == null) {
      cacheDataFileChanges(fileIO, encryptionManager);
    }
    return removedDataFiles;
  }

  @Override
  public Iterable<DeleteFile> addedDeleteFiles(FileIO fileIO) {
    return addedDeleteFiles(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public Iterable<DeleteFile> addedDeleteFiles(FileIO fileIO, EncryptionManager encryptionManager) {
    if (addedDeleteFiles == null) {
      cacheDeleteFileChanges(fileIO, encryptionManager);
    }
    return addedDeleteFiles;
  }

  @Override
  public Iterable<DeleteFile> removedDeleteFiles(FileIO fileIO) {
    return removedDeleteFiles(fileIO, PlaintextEncryptionManager.instance());
  }

  @Override
  public Iterable<DeleteFile> removedDeleteFiles(
      FileIO fileIO, EncryptionManager encryptionManager) {
    if (removedDeleteFiles == null) {
      cacheDeleteFileChanges(fileIO, encryptionManager);
    }
    return removedDeleteFiles;
  }

  @Override
  public String manifestListLocation() {
    return manifestListLocation;
  }

  private void cacheDeleteFileChanges(FileIO fileIO, EncryptionManager encryptionManager) {
    Preconditions.checkArgument(fileIO != null, "Cannot cache delete file changes: FileIO is null");

    ImmutableList.Builder<DeleteFile> adds = ImmutableList.builder();
    ImmutableList.Builder<DeleteFile> deletes = ImmutableList.builder();

    Iterable<ManifestFile> changedManifests =
        Iterables.filter(
            deleteManifests(fileIO, encryptionManager),
            manifest -> Objects.equal(manifest.snapshotId(), snapshotId));

    for (ManifestFile manifest : changedManifests) {
      try (ManifestReader<DeleteFile> reader =
          ManifestFiles.readDeleteManifest(manifest, fileIO, encryptionManager, null)) {
        for (ManifestEntry<DeleteFile> entry : reader.entries()) {
          switch (entry.status()) {
            case ADDED:
              adds.add(entry.file().copy());
              break;
            case DELETED:
              deletes.add(entry.file().copyWithoutStats());
              break;
            default:
              // ignore existing
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to close manifest reader", e);
      }
    }

    this.addedDeleteFiles = adds.build();
    this.removedDeleteFiles = deletes.build();
  }

  private void cacheDataFileChanges(FileIO fileIO, EncryptionManager encryptionManager) {
    Preconditions.checkArgument(fileIO != null, "Cannot cache data file changes: FileIO is null");

    ImmutableList.Builder<DataFile> adds = ImmutableList.builder();
    ImmutableList.Builder<DataFile> deletes = ImmutableList.builder();

    // read only manifests that were created by this snapshot
    Iterable<ManifestFile> changedManifests =
        Iterables.filter(
            dataManifests(fileIO, encryptionManager),
            manifest -> Objects.equal(manifest.snapshotId(), snapshotId));
    try (CloseableIterable<ManifestEntry<DataFile>> entries =
        new ManifestGroup(fileIO, encryptionManager, changedManifests).ignoreExisting().entries()) {
      for (ManifestEntry<DataFile> entry : entries) {
        switch (entry.status()) {
          case ADDED:
            adds.add(entry.file().copy());
            break;
          case DELETED:
            deletes.add(entry.file().copyWithoutStats());
            break;
          default:
            throw new IllegalStateException(
                "Unexpected entry status, not added or deleted: " + entry);
        }
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to close entries while caching changes");
    }

    this.addedDataFiles = adds.build();
    this.removedDataFiles = deletes.build();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o instanceof BaseSnapshot) {
      BaseSnapshot other = (BaseSnapshot) o;
      return this.snapshotId == other.snapshotId()
          && Objects.equal(this.parentId, other.parentId())
          && this.sequenceNumber == other.sequenceNumber()
          && this.timestampMillis == other.timestampMillis()
          && Objects.equal(this.schemaId, other.schemaId());
    }

    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        this.snapshotId, this.parentId, this.sequenceNumber, this.timestampMillis, this.schemaId);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("id", snapshotId)
        .add("timestamp_ms", timestampMillis)
        .add("operation", operation)
        .add("summary", summary)
        .add("manifest-list", manifestListLocation)
        .add("schema-id", schemaId)
        .toString();
  }
}
