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

import static com.google.common.base.Preconditions.checkArgument;

public enum LogChunkType
{
    ZERO_TYPE(0),
    FULL(1),
    FIRST(2),
    MIDDLE(3),
    LAST(4),
    EOF,
    BAD_CHUNK,
    UNKNOWN;

    public static LogChunkType getLogChunkTypeByPersistentId(int persistentId)
    {
        for (LogChunkType logChunkType : LogChunkType.values()) {
            if (logChunkType.persistentId != null && logChunkType.persistentId == persistentId) {
                return logChunkType;
            }
        }
        return UNKNOWN;
    }

    private final Integer persistentId;

    LogChunkType()
    {
        this.persistentId = null;
    }

    LogChunkType(int persistentId)
    {
        this.persistentId = persistentId;
    }

    public int getPersistentId()
    {
        checkArgument(persistentId != null, "%s is not a persistent chunk type", name());
        return persistentId;
    }
}
