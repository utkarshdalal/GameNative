/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
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
package org.iq80.leveldb;

import java.io.Closeable;
import java.util.Map;

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public interface DB
        extends Iterable<Map.Entry<byte[], byte[]>>, Closeable
{
    byte[] get(byte[] key)
            throws DBException;

    byte[] get(byte[] key, ReadOptions options)
            throws DBException;

    @Override
    DBIterator iterator();

    DBIterator iterator(ReadOptions options);

    void put(byte[] key, byte[] value)
            throws DBException;

    void delete(byte[] key)
            throws DBException;

    void write(WriteBatch updates)
            throws DBException;

    WriteBatch createWriteBatch();

    /**
     * @return null if options.isSnapshot()==false otherwise returns a snapshot
     * of the DB after this operation.
     */
    Snapshot put(byte[] key, byte[] value, WriteOptions options)
            throws DBException;

    /**
     * @return null if options.isSnapshot()==false otherwise returns a snapshot
     * of the DB after this operation.
     */
    Snapshot delete(byte[] key, WriteOptions options)
            throws DBException;

    /**
     * @return null if options.isSnapshot()==false otherwise returns a snapshot
     * of the DB after this operation.
     */
    Snapshot write(WriteBatch updates, WriteOptions options)
            throws DBException;

    Snapshot getSnapshot();

    long[] getApproximateSizes(Range... ranges);

    String getProperty(String name);

    /**
     * Suspends any background compaction threads.  This methods
     * returns once the background compactions are suspended.
     */
    void suspendCompactions()
            throws InterruptedException;

    /**
     * Resumes the background compaction threads.
     */
    void resumeCompactions();

    /**
     * Force a compaction of the specified key range.
     *
     * @param begin if null then compaction start from the first key
     * @param end if null then compaction ends at the last key
     */
    void compactRange(byte[] begin, byte[] end)
            throws DBException;
}
