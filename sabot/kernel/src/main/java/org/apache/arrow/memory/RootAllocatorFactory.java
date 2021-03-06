/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.arrow.memory;

import java.lang.reflect.Field;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.memory.DremioRootAllocator;

import io.netty.buffer.PooledByteBufAllocator;

public class RootAllocatorFactory {

  private static final String IO_NETTY_ALLOCATOR_USE_CACHE_FOR_ALL_THREADS_PROPERTY = "io.netty.allocator.useCacheForAllThreads";

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RootAllocatorFactory.class);

  public static final String TOP_LEVEL_MAX_ALLOC = "dremio.memory.top.max";

  /**
   * Constructor to prevent instantiation of this static utility class.
   */
  private RootAllocatorFactory() {}

  /**
   * Create a new Root Allocator
   * @param config
   *          the SabotConfig
   * @return a new root allocator
   */
  public synchronized static BufferAllocator newRoot(final SabotConfig config) {
    // DX-11065: the Netty 4.1 caching algorithm changes causes more objects to be cached, which in turn causes the
    // DirectMemoryArena(s) to contain a lot of PoolChunk objects that are mostly free, with just a few kilobytes locked
    // in the caches. These chunks then become unavailable to the other arenas, which causes execution to run out of
    // memory.
    final String previousProperty = System.getProperty(IO_NETTY_ALLOCATOR_USE_CACHE_FOR_ALL_THREADS_PROPERTY);
    try {
      System.setProperty(IO_NETTY_ALLOCATOR_USE_CACHE_FOR_ALL_THREADS_PROPERTY, "false");
      return new DremioRootAllocator(Math.min(SabotConfig.getMaxDirectMemory(), config.getLong(TOP_LEVEL_MAX_ALLOC)));
    } finally {
      if (previousProperty == null) {
        System.getProperties().remove(IO_NETTY_ALLOCATOR_USE_CACHE_FOR_ALL_THREADS_PROPERTY);
      } else {
        System.setProperty(IO_NETTY_ALLOCATOR_USE_CACHE_FOR_ALL_THREADS_PROPERTY, previousProperty);
      }
      checkPoolConfiguration();
    }
  }

  private static void checkPoolConfiguration() {
    try {
      // check that the check is taken into account
      Field field = PooledByteBufAllocator.class.getDeclaredField("DEFAULT_USE_CACHE_FOR_ALL_THREADS");
      field.setAccessible(true);
      boolean value = field.getBoolean(null);
      if (value) {
        LOGGER.error("!!! Root allocator configured to use cache for all threads !!! Expect memory issues");
      }
    } catch (ReflectiveOperationException e) {
      LOGGER.warn("Not able to check memory pool configuration", e);
    }
  }

}
