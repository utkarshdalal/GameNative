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
package org.iq80.leveldb.impl;

import org.iq80.leveldb.util.Closeables;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class DbLock
{
    private final File lockFile;
    private final FileChannel channel;
    private final FileLock lock;

    public DbLock(File lockFile)
            throws IOException
    {
        requireNonNull(lockFile, "lockFile is null");
        this.lockFile = lockFile;

        // open and lock the file
        channel = new RandomAccessFile(lockFile, "rw").getChannel();
        try {
            lock = channel.tryLock();
        }
        catch (IOException e) {
            Closeables.closeQuietly(channel);
            throw e;
        }

        if (lock == null) {
            throw new IOException(format("Unable to acquire lock on '%s'", lockFile.getAbsolutePath()));
        }
    }

    public boolean isValid()
    {
        return lock.isValid();
    }

    public void release()
    {
        try {
            lock.release();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        finally {
            Closeables.closeQuietly(channel);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("DbLock");
        sb.append("{lockFile=").append(lockFile);
        sb.append(", lock=").append(lock);
        sb.append('}');
        return sb.toString();
    }
}
