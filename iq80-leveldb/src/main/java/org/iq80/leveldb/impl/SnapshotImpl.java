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

import org.iq80.leveldb.Snapshot;

import java.util.concurrent.atomic.AtomicBoolean;

public class SnapshotImpl
        implements Snapshot
{
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Version version;
    private final long lastSequence;

    SnapshotImpl(Version version, long lastSequence)
    {
        this.version = version;
        this.lastSequence = lastSequence;
        this.version.retain();
    }

    @Override
    public void close()
    {
        // This is an end user API.. he might screw up and close multiple times.
        // but we don't want the version reference count going bad.
        if (closed.compareAndSet(false, true)) {
            this.version.release();
        }
    }

    public long getLastSequence()
    {
        return lastSequence;
    }

    public Version getVersion()
    {
        return version;
    }

    @Override
    public String toString()
    {
        return Long.toString(lastSequence);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SnapshotImpl snapshot = (SnapshotImpl) o;

        if (lastSequence != snapshot.lastSequence) {
            return false;
        }
        if (!version.equals(snapshot.version)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = version.hashCode();
        result = 31 * result + (int) (lastSequence ^ (lastSequence >>> 32));
        return result;
    }
}
