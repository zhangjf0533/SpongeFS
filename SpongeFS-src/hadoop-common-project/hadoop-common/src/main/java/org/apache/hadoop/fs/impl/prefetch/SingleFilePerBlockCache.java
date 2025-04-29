/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hadoop.fs.impl.prefetch;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalDirAllocator;

import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.fs.impl.prefetch.Validate.checkNotNull;

/**
 * Provides functionality necessary for caching blocks of data read from FileSystem.
 * Each cache block is stored on the local disk as a separate file.
 */
public class SingleFilePerBlockCache implements BlockCache {
  private static final Logger LOG = LoggerFactory.getLogger(SingleFilePerBlockCache.class);

  /**
   * Blocks stored in this cache.
   */
  private final Map<Integer, Entry> blocks = new ConcurrentHashMap<>();

  /**
   * Number of times a block was read from this cache.
   * Used for determining cache utilization factor.
   */
  private int numGets = 0;

  private boolean closed;

  private final PrefetchingStatistics prefetchingStatistics;

  /**
   * Timeout to be used by close, while acquiring prefetch block write lock.
   */
  private static final int PREFETCH_WRITE_LOCK_TIMEOUT = 5;

  /**
   * Lock timeout unit to be used by the thread while acquiring prefetch block write lock.
   */
  private static final TimeUnit PREFETCH_WRITE_LOCK_TIMEOUT_UNIT = TimeUnit.SECONDS;

  /**
   * File attributes attached to any intermediate temporary file created during index creation.
   */
  private static final Set<PosixFilePermission> TEMP_FILE_ATTRS =
      ImmutableSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  /**
   * Cache entry.
   * Each block is stored as a separate file.
   */
  private static final class Entry {
    private final int blockNumber;
    private final Path path;
    private final int size;
    private final long checksum;
    private final ReentrantReadWriteLock lock;
    private enum LockType {
      READ,
      WRITE
    }

    Entry(int blockNumber, Path path, int size, long checksum) {
      this.blockNumber = blockNumber;
      this.path = path;
      this.size = size;
      this.checksum = checksum;
      this.lock = new ReentrantReadWriteLock();
    }

    @Override
    public String toString() {
      return String.format(
          "([%03d] %s: size = %d, checksum = %d)",
          blockNumber, path, size, checksum);
    }

    /**
     * Take the read or write lock.
     *
     * @param lockType type of the lock.
     */
    private void takeLock(LockType lockType) {
      if (LockType.READ == lockType) {
        lock.readLock().lock();
      } else if (LockType.WRITE == lockType) {
        lock.writeLock().lock();
      }
    }

    /**
     * Release the read or write lock.
     *
     * @param lockType type of the lock.
     */
    private void releaseLock(LockType lockType) {
      if (LockType.READ == lockType) {
        lock.readLock().unlock();
      } else if (LockType.WRITE == lockType) {
        lock.writeLock().unlock();
      }
    }

    /**
     * Try to take the read or write lock within the given timeout.
     *
     * @param lockType type of the lock.
     * @param timeout the time to wait for the given lock.
     * @param unit the time unit of the timeout argument.
     * @return true if the lock of the given lock type was acquired.
     */
    private boolean takeLock(LockType lockType, long timeout, TimeUnit unit) {
      try {
        if (LockType.READ == lockType) {
          return lock.readLock().tryLock(timeout, unit);
        } else if (LockType.WRITE == lockType) {
          return lock.writeLock().tryLock(timeout, unit);
        }
      } catch (InterruptedException e) {
        LOG.warn("Thread interrupted while trying to acquire {} lock", lockType, e);
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  /**
   * Constructs an instance of a {@code SingleFilePerBlockCache}.
   *
   * @param prefetchingStatistics statistics for this stream.
   */
  public SingleFilePerBlockCache(PrefetchingStatistics prefetchingStatistics) {
    this.prefetchingStatistics = requireNonNull(prefetchingStatistics);
  }

  /**
   * Indicates whether the given block is in this cache.
   */
  @Override
  public boolean containsBlock(int blockNumber) {
    return blocks.containsKey(blockNumber);
  }

  /**
   * Gets the blocks in this cache.
   */
  @Override
  public Iterable<Integer> blocks() {
    return Collections.unmodifiableList(new ArrayList<>(blocks.keySet()));
  }

  /**
   * Gets the number of blocks in this cache.
   */
  @Override
  public int size() {
    return blocks.size();
  }

  /**
   * Gets the block having the given {@code blockNumber}.
   *
   * @throws IllegalArgumentException if buffer is null.
   */
  @Override
  public void get(int blockNumber, ByteBuffer buffer) throws IOException {
    if (closed) {
      return;
    }

    checkNotNull(buffer, "buffer");

    Entry entry = getEntry(blockNumber);
    entry.takeLock(Entry.LockType.READ);
    try {
      buffer.clear();
      readFile(entry.path, buffer);
      buffer.rewind();
      validateEntry(entry, buffer);
    } finally {
      entry.releaseLock(Entry.LockType.READ);
    }
  }

  protected int readFile(Path path, ByteBuffer buffer) throws IOException {
    int numBytesRead = 0;
    int numBytes;
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    while ((numBytes = channel.read(buffer)) > 0) {
      numBytesRead += numBytes;
    }
    buffer.limit(buffer.position());
    channel.close();
    return numBytesRead;
  }

  private Entry getEntry(int blockNumber) {
    Validate.checkNotNegative(blockNumber, "blockNumber");

    Entry entry = blocks.get(blockNumber);
    if (entry == null) {
      throw new IllegalStateException(String.format("block %d not found in cache", blockNumber));
    }
    numGets++;
    return entry;
  }

  /**
   * Puts the given block in this cache.
   *
   * @param blockNumber the block number, used as a key for blocks map.
   * @param buffer buffer contents of the given block to be added to this cache.
   * @param conf the configuration.
   * @param localDirAllocator the local dir allocator instance.
   * @throws IOException if either local dir allocator fails to allocate file or if IO error
   * occurs while writing the buffer content to the file.
   * @throws IllegalArgumentException if buffer is null, or if buffer.limit() is zero or negative.
   */
  @Override
  public void put(int blockNumber, ByteBuffer buffer, Configuration conf,
      LocalDirAllocator localDirAllocator) throws IOException {
    if (closed) {
      return;
    }

    checkNotNull(buffer, "buffer");

    if (blocks.containsKey(blockNumber)) {
      Entry entry = blocks.get(blockNumber);
      entry.takeLock(Entry.LockType.READ);
      try {
        validateEntry(entry, buffer);
      } finally {
        entry.releaseLock(Entry.LockType.READ);
      }
      return;
    }

    Validate.checkPositiveInteger(buffer.limit(), "buffer.limit()");

    Path blockFilePath = getCacheFilePath(conf, localDirAllocator);
    long size = Files.size(blockFilePath);
    if (size != 0) {
      String message =
          String.format("[%d] temp file already has data. %s (%d)",
          blockNumber, blockFilePath, size);
      throw new IllegalStateException(message);
    }

    writeFile(blockFilePath, buffer);
    long checksum = BufferData.getChecksum(buffer);
    Entry entry = new Entry(blockNumber, blockFilePath, buffer.limit(), checksum);
    blocks.put(blockNumber, entry);
    // Update stream_read_blocks_in_cache stats only after blocks map is updated with new file
    // entry to avoid any discrepancy related to the value of stream_read_blocks_in_cache.
    // If stream_read_blocks_in_cache is updated before updating the blocks map here, closing of
    // the input stream can lead to the removal of the cache file even before blocks is added with
    // the new cache file, leading to incorrect value of stream_read_blocks_in_cache.
    prefetchingStatistics.blockAddedToFileCache();
  }

  private static final Set<? extends OpenOption> CREATE_OPTIONS =
      EnumSet.of(StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);

  protected void writeFile(Path path, ByteBuffer buffer) throws IOException {
    buffer.rewind();
    WritableByteChannel writeChannel = Files.newByteChannel(path, CREATE_OPTIONS);
    while (buffer.hasRemaining()) {
      writeChannel.write(buffer);
    }
    writeChannel.close();
  }

  /**
   * Return temporary file created based on the file path retrieved from local dir allocator.
   *
   * @param conf The configuration object.
   * @param localDirAllocator Local dir allocator instance.
   * @return Path of the temporary file created.
   * @throws IOException if IO error occurs while local dir allocator tries to retrieve path
   * from local FS or file creation fails or permission set fails.
   */
  protected Path getCacheFilePath(final Configuration conf,
      final LocalDirAllocator localDirAllocator)
      throws IOException {
    return getTempFilePath(conf, localDirAllocator);
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;

    LOG.info(getStats());
    int numFilesDeleted = 0;

    for (Entry entry : blocks.values()) {
      boolean lockAcquired = entry.takeLock(Entry.LockType.WRITE, PREFETCH_WRITE_LOCK_TIMEOUT,
          PREFETCH_WRITE_LOCK_TIMEOUT_UNIT);
      if (!lockAcquired) {
        LOG.error("Cache file {} deletion would not be attempted as write lock could not"
                + " be acquired within {} {}", entry.path, PREFETCH_WRITE_LOCK_TIMEOUT,
            PREFETCH_WRITE_LOCK_TIMEOUT_UNIT);
        continue;
      }
      try {
        Files.deleteIfExists(entry.path);
        prefetchingStatistics.blockRemovedFromFileCache();
        numFilesDeleted++;
      } catch (IOException e) {
        LOG.debug("Failed to delete cache file {}", entry.path, e);
      } finally {
        entry.releaseLock(Entry.LockType.WRITE);
      }
    }

    if (numFilesDeleted > 0) {
      LOG.info("Deleted {} cache files", numFilesDeleted);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("stats: ");
    sb.append(getStats());
    sb.append(", blocks:[");
    sb.append(getIntList(blocks()));
    sb.append("]");
    return sb.toString();
  }

  private void validateEntry(Entry entry, ByteBuffer buffer) {
    if (entry.size != buffer.limit()) {
      String message = String.format(
          "[%d] entry.size(%d) != buffer.limit(%d)",
          entry.blockNumber, entry.size, buffer.limit());
      throw new IllegalStateException(message);
    }

    long checksum = BufferData.getChecksum(buffer);
    if (entry.checksum != checksum) {
      String message = String.format(
          "[%d] entry.checksum(%d) != buffer checksum(%d)",
          entry.blockNumber, entry.checksum, checksum);
      throw new IllegalStateException(message);
    }
  }

  /**
   * Produces a human readable list of blocks for the purpose of logging.
   * This method minimizes the length of returned list by converting
   * a contiguous list of blocks into a range.
   * for example,
   * 1, 3, 4, 5, 6, 8 becomes 1, 3~6, 8
   */
  private String getIntList(Iterable<Integer> nums) {
    List<String> numList = new ArrayList<>();
    List<Integer> numbers = new ArrayList<Integer>();
    for (Integer n : nums) {
      numbers.add(n);
    }
    Collections.sort(numbers);

    int index = 0;
    while (index < numbers.size()) {
      int start = numbers.get(index);
      int prev = start;
      int end = start;
      while ((++index < numbers.size()) && ((end = numbers.get(index)) == prev + 1)) {
        prev = end;
      }

      if (start == prev) {
        numList.add(Integer.toString(start));
      } else {
        numList.add(String.format("%d~%d", start, prev));
      }
    }

    return String.join(", ", numList);
  }

  private String getStats() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(
        "#entries = %d, #gets = %d",
        blocks.size(), numGets));
    return sb.toString();
  }

  private static final String CACHE_FILE_PREFIX = "fs-cache-";

  /**
   * Determine if the cache space is available on the local FS.
   *
   * @param fileSize The size of the file.
   * @param conf The configuration.
   * @param localDirAllocator Local dir allocator instance.
   * @return True if the given file size is less than the available free space on local FS,
   * False otherwise.
   */
  public static boolean isCacheSpaceAvailable(long fileSize, Configuration conf,
      LocalDirAllocator localDirAllocator) {
    try {
      Path cacheFilePath = getTempFilePath(conf, localDirAllocator);
      long freeSpace = new File(cacheFilePath.toString()).getUsableSpace();
      LOG.info("fileSize = {}, freeSpace = {}", fileSize, freeSpace);
      Files.deleteIfExists(cacheFilePath);
      return fileSize < freeSpace;
    } catch (IOException e) {
      LOG.error("isCacheSpaceAvailable", e);
      return false;
    }
  }

  // The suffix (file extension) of each serialized index file.
  private static final String BINARY_FILE_SUFFIX = ".bin";

  /**
   * Create temporary file based on the file path retrieved from local dir allocator
   * instance. The file is created with .bin suffix. The created file has been granted
   * posix file permissions available in TEMP_FILE_ATTRS.
   *
   * @param conf the configuration.
   * @param localDirAllocator the local dir allocator instance.
   * @return path of the file created.
   * @throws IOException if IO error occurs while local dir allocator tries to retrieve path
   * from local FS or file creation fails or permission set fails.
   */
  private static Path getTempFilePath(final Configuration conf,
      final LocalDirAllocator localDirAllocator) throws IOException {
    org.apache.hadoop.fs.Path path =
        localDirAllocator.getLocalPathForWrite(CACHE_FILE_PREFIX, conf);
    File dir = new File(path.getParent().toUri().getPath());
    String prefix = path.getName();
    File tmpFile = File.createTempFile(prefix, BINARY_FILE_SUFFIX, dir);
    Path tmpFilePath = Paths.get(tmpFile.toURI());
    return Files.setPosixFilePermissions(tmpFilePath, TEMP_FILE_ATTRS);
  }
}
