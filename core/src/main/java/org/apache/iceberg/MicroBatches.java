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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.encryption.PlaintextEncryptionManager;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MicroBatches {
  private MicroBatches() {}

  public static List<Pair<ManifestFile, Integer>> skippedManifestIndexesFromSnapshot(
      FileIO io, Snapshot snapshot, long startFileIndex, boolean scanAllFiles) {
    List<ManifestFile> manifests =
        scanAllFiles
            ? snapshot.dataManifests(io)
            : snapshot.dataManifests(io).stream()
                .filter(m -> m.snapshotId().equals(snapshot.snapshotId()))
                .collect(Collectors.toList());

    List<Pair<ManifestFile, Integer>> manifestIndexes = indexManifests(manifests);

    return skipManifests(manifestIndexes, startFileIndex);
  }

  public static CloseableIterable<FileScanTask> openManifestFile(
      FileIO io,
      Map<Integer, PartitionSpec> specsById,
      boolean caseSensitive,
      Snapshot snapshot,
      ManifestFile manifestFile,
      boolean scanAllFiles) {
    return openManifestFile(
        io,
        PlaintextEncryptionManager.instance(),
        specsById,
        caseSensitive,
        snapshot,
        manifestFile,
        scanAllFiles);
  }

  public static CloseableIterable<FileScanTask> openManifestFile(
      FileIO io,
      EncryptionManager encryption,
      Map<Integer, PartitionSpec> specsById,
      boolean caseSensitive,
      Snapshot snapshot,
      ManifestFile manifestFile,
      boolean scanAllFiles) {

    ManifestGroup manifestGroup =
        new ManifestGroup(io, encryption, ImmutableList.of(manifestFile))
            .specsById(specsById)
            .caseSensitive(caseSensitive);
    if (!scanAllFiles) {
      manifestGroup =
          manifestGroup
              .filterManifestEntries(
                  entry ->
                      entry.snapshotId() == snapshot.snapshotId()
                          && entry.status() == ManifestEntry.Status.ADDED)
              .ignoreDeleted();
    }

    return manifestGroup.planFiles();
  }

  /**
   * Method to index the data files for each manifest. For example, if manifest m1 has 3 data files,
   * manifest m2 has 2 data files, manifest m3 has 1 data file, then the index will be (m1, 0), (m2,
   * 3), (m3, 5).
   *
   * @param manifestFiles List of input manifests used to index.
   * @return a list pairing each manifest with the index number of the first data file entry in that
   *     manifest.
   */
  private static List<Pair<ManifestFile, Integer>> indexManifests(
      List<ManifestFile> manifestFiles) {
    int currentFileIndex = 0;
    List<Pair<ManifestFile, Integer>> manifestIndexes = Lists.newArrayList();

    for (ManifestFile manifest : manifestFiles) {
      manifestIndexes.add(Pair.of(manifest, currentFileIndex));
      currentFileIndex += manifest.addedFilesCount() + manifest.existingFilesCount();
    }

    return manifestIndexes;
  }

  /**
   * Method to skip the manifest file whose index is smaller than startFileIndex. For example, if
   * the index list is : (m1, 0), (m2, 3), (m3, 5), and startFileIndex is 4, then the returned
   * manifest index list is: (m2, 3), (m3, 5).
   *
   * @param indexedManifests List of input manifests.
   * @param startFileIndex Index used to skip all manifests with an index less than or equal to this
   *     value.
   * @return a sub-list of manifest file index which only contains the manifest indexes larger than
   *     the startFileIndex.
   */
  private static List<Pair<ManifestFile, Integer>> skipManifests(
      List<Pair<ManifestFile, Integer>> indexedManifests, long startFileIndex) {
    if (startFileIndex == 0) {
      return indexedManifests;
    }

    int manifestIndex = 0;
    for (Pair<ManifestFile, Integer> manifest : indexedManifests) {
      if (manifest.second() > startFileIndex) {
        break;
      }

      manifestIndex++;
    }

    return indexedManifests.subList(Math.max(manifestIndex - 1, 0), indexedManifests.size());
  }

  public static class MicroBatch {
    private final long snapshotId;
    private final long startFileIndex;
    private final long endFileIndex;
    private final long sizeInBytes;
    private final List<FileScanTask> tasks;
    private final boolean lastIndexOfSnapshot;

    private MicroBatch(
        long snapshotId,
        long startFileIndex,
        long endFileIndex,
        long sizeInBytes,
        List<FileScanTask> tasks,
        boolean lastIndexOfSnapshot) {
      this.snapshotId = snapshotId;
      this.startFileIndex = startFileIndex;
      this.endFileIndex = endFileIndex;
      this.sizeInBytes = sizeInBytes;
      this.tasks = tasks;
      this.lastIndexOfSnapshot = lastIndexOfSnapshot;
    }

    public long snapshotId() {
      return snapshotId;
    }

    public long startFileIndex() {
      return startFileIndex;
    }

    public long endFileIndex() {
      return endFileIndex;
    }

    public long sizeInBytes() {
      return sizeInBytes;
    }

    public List<FileScanTask> tasks() {
      return tasks;
    }

    public boolean lastIndexOfSnapshot() {
      return lastIndexOfSnapshot;
    }
  }

  /** Tests only */
  public static MicroBatchBuilder from(Snapshot snapshot, FileIO io) {
    return from(snapshot, io, PlaintextEncryptionManager.instance());
  }

  public static MicroBatchBuilder from(Snapshot snapshot, FileIO io, EncryptionManager encryption) {
    return new MicroBatchBuilder(snapshot, io, encryption);
  }

  public static class MicroBatchBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(MicroBatchBuilder.class);

    private final Snapshot snapshot;
    private final FileIO io;
    private final EncryptionManager encryption;
    private boolean caseSensitive;
    private Map<Integer, PartitionSpec> specsById;

    private MicroBatchBuilder(Snapshot snapshot, FileIO io, EncryptionManager encryption) {
      this.snapshot = snapshot;
      this.io = io;
      this.encryption = encryption;
      this.caseSensitive = true;
    }

    public MicroBatchBuilder caseSensitive(boolean sensitive) {
      this.caseSensitive = sensitive;
      return this;
    }

    public MicroBatchBuilder specsById(Map<Integer, PartitionSpec> specs) {
      this.specsById = specs;
      return this;
    }

    public MicroBatch generate(long startFileIndex, long targetSizeInBytes, boolean scanAllFiles) {
      return generate(
          startFileIndex,
          Iterables.size(snapshot.addedDataFiles(io, encryption)),
          targetSizeInBytes,
          scanAllFiles);
    }

    public MicroBatch generate(
        long startFileIndex, long endFileIndex, long targetSizeInBytes, boolean scanAllFiles) {
      Preconditions.checkArgument(endFileIndex >= 0, "endFileIndex is unexpectedly smaller than 0");
      Preconditions.checkArgument(
          startFileIndex >= 0, "startFileIndex is unexpectedly smaller than 0");
      Preconditions.checkArgument(
          targetSizeInBytes > 0, "targetSizeInBytes should be larger than 0");

      return generateMicroBatch(
          skippedManifestIndexesFromSnapshot(io, snapshot, startFileIndex, scanAllFiles),
          startFileIndex,
          endFileIndex,
          targetSizeInBytes,
          scanAllFiles);
    }

    /**
     * Method to generate MicroBatch of this snapshot based on the indexed manifests, controlled by
     * targetSizeInBytes.
     *
     * @param indexedManifests A list of indexed manifests to generate MicroBatch
     * @param startFileIndex A startFileIndex used to skip processed files.
     * @param endFileIndex An endFileIndex used to find files to include, exclusive.
     * @param targetSizeInBytes Used to control the size of MicroBatch, the processed file bytes
     *     must be smaller than this size.
     * @param scanAllFiles Used to check whether all the data files should be processed, or only
     *     added files.
     * @return A MicroBatch.
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private MicroBatch generateMicroBatch(
        List<Pair<ManifestFile, Integer>> indexedManifests,
        long startFileIndex,
        long endFileIndex,
        long targetSizeInBytes,
        boolean scanAllFiles) {
      if (indexedManifests.isEmpty()) {
        return new MicroBatch(
            snapshot.snapshotId(), startFileIndex, endFileIndex, 0L, Collections.emptyList(), true);
      }

      long currentSizeInBytes = 0L;
      int currentFileIndex = 0;
      boolean isLastIndex = false;
      List<FileScanTask> tasks = Lists.newArrayList();

      for (int idx = 0; idx < indexedManifests.size(); idx++) {
        currentFileIndex = indexedManifests.get(idx).second();

        try (CloseableIterable<FileScanTask> taskIterable =
                openManifestFile(
                    io,
                    encryption,
                    specsById,
                    caseSensitive,
                    snapshot,
                    indexedManifests.get(idx).first(),
                    scanAllFiles);
            CloseableIterator<FileScanTask> taskIter = taskIterable.iterator()) {
          while (taskIter.hasNext()) {
            FileScanTask task = taskIter.next();
            // want to read [startFileIndex ... endFileIndex)
            if (currentFileIndex >= startFileIndex && currentFileIndex < endFileIndex) {
              // Make sure there's at least one task in each MicroBatch to void job to be stuck,
              // always add task
              // firstly.
              tasks.add(task);
              currentSizeInBytes += task.length();
            }

            currentFileIndex++;
            if (currentSizeInBytes >= targetSizeInBytes || currentFileIndex >= endFileIndex) {
              break;
            }
          }

          if (idx + 1 == indexedManifests.size() && !taskIter.hasNext()) {
            // If this is the last file scan task in last manifest, set the flag to true.
            isLastIndex = true;
          }
        } catch (IOException ioe) {
          LOG.warn("Failed to close task iterable", ioe);
        }

        if (currentSizeInBytes >= targetSizeInBytes) {
          if (tasks.size() > 1 && currentSizeInBytes > targetSizeInBytes) {
            // If there's more than 1 task in this batch, and the size exceeds the limit, we should
            // revert last
            // task to make sure we don't exceed the size limit.
            FileScanTask extraTask = tasks.remove(tasks.size() - 1);
            currentSizeInBytes -= extraTask.length();
            currentFileIndex--;
            isLastIndex = false;
          }

          break;
        }
      }

      // [startFileIndex ....currentFileIndex)
      return new MicroBatch(
          snapshot.snapshotId(),
          startFileIndex,
          currentFileIndex,
          currentSizeInBytes,
          tasks,
          isLastIndex);
    }
  }
}
