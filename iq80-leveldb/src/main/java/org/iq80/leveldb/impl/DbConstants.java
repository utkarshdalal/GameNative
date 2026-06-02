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

public final class DbConstants
{
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;

    // todo this should be part of the configuration

    /**
     * Max number of levels
     */
    public static final int NUM_LEVELS = 7;

    /**
     * Level-0 compaction is started when we hit this many files.
     */
    public static final int L0_COMPACTION_TRIGGER = 4;

    /**
     * Soft limit on number of level-0 files.  We slow down writes at this point.
     */
    public static final int L0_SLOWDOWN_WRITES_TRIGGER = 8;

    /**
     * Maximum number of level-0 files.  We stop writes at this point.
     */
    public static final int L0_STOP_WRITES_TRIGGER = 12;

    /**
     * Maximum level to which a new compacted memtable is pushed if it
     * does not create overlap.  We try to push to level 2 to avoid the
     * relatively expensive level 0=>1 compactions and to avoid some
     * expensive manifest file operations.  We do not push all the way to
     * the largest level since that can generate a lot of wasted disk
     * space if the same key space is being repeatedly overwritten.
     */
    public static final int MAX_MEM_COMPACT_LEVEL = 2;

    private DbConstants()
    {
    }
}
