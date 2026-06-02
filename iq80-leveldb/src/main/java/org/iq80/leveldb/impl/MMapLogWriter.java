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

import org.iq80.leveldb.util.ByteBufferSupport;
import org.iq80.leveldb.util.Closeables;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.SliceInput;
import org.iq80.leveldb.util.SliceOutput;
import org.iq80.leveldb.util.Slices;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.LogConstants.BLOCK_SIZE;
import static org.iq80.leveldb.impl.LogConstants.HEADER_SIZE;
import static org.iq80.leveldb.impl.Logs.getChunkChecksum;

public class MMapLogWriter
        implements LogWriter
{
    private static final int PAGE_SIZE = 1024 * 1024;

    private final File file;
    private final long fileNumber;
    private final FileChannel fileChannel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private MappedByteBuffer mappedByteBuffer;
    private long fileOffset;
    /**
     * Current offset in the current block
     */
    private int blockOffset;

    public MMapLogWriter(File file, long fileNumber)
            throws IOException
    {
        requireNonNull(file, "file is null");
        checkArgument(fileNumber >= 0, "fileNumber is negative");
        this.file = file;
        this.fileNumber = fileNumber;
        this.fileChannel = new RandomAccessFile(file, "rw").getChannel();
        mappedByteBuffer = fileChannel.map(MapMode.READ_WRITE, 0, PAGE_SIZE);
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public synchronized void close()
            throws IOException
    {
        closed.set(true);

        destroyMappedByteBuffer();

        if (fileChannel.isOpen()) {
            fileChannel.truncate(fileOffset);
        }

        // close the channel
        Closeables.closeQuietly(fileChannel);
    }

    @Override
    public synchronized void delete()
            throws IOException
    {
        close();

        // try to delete the file
        file.delete();
    }

    private void destroyMappedByteBuffer()
    {
        if (mappedByteBuffer != null) {
            fileOffset += mappedByteBuffer.position();
            unmap();
        }
        mappedByteBuffer = null;
    }

    @Override
    public File getFile()
    {
        return file;
    }

    @Override
    public long getFileNumber()
    {
        return fileNumber;
    }

    // Writes a stream of chunks such that no chunk is split across a block boundary
    @Override
    public synchronized void addRecord(Slice record, boolean force)
            throws IOException
    {
        checkState(!closed.get(), "Log has been closed");

        SliceInput sliceInput = record.input();

        // used to track first, middle and last blocks
        boolean begin = true;

        // Fragment the record int chunks as necessary and write it.  Note that if record
        // is empty, we still want to iterate once to write a single
        // zero-length chunk.
        do {
            int bytesRemainingInBlock = BLOCK_SIZE - blockOffset;
            checkState(bytesRemainingInBlock >= 0);

            // Switch to a new block if necessary
            if (bytesRemainingInBlock < HEADER_SIZE) {
                if (bytesRemainingInBlock > 0) {
                    // Fill the rest of the block with zeros
                    // todo lame... need a better way to write zeros
                    ensureCapacity(bytesRemainingInBlock);
                    mappedByteBuffer.put(new byte[bytesRemainingInBlock]);
                }
                blockOffset = 0;
                bytesRemainingInBlock = BLOCK_SIZE - blockOffset;
            }

            // Invariant: we never leave less than HEADER_SIZE bytes available in a block
            int bytesAvailableInBlock = bytesRemainingInBlock - HEADER_SIZE;
            checkState(bytesAvailableInBlock >= 0);

            // if there are more bytes in the record then there are available in the block,
            // fragment the record; otherwise write to the end of the record
            boolean end;
            int fragmentLength;
            if (sliceInput.available() > bytesAvailableInBlock) {
                end = false;
                fragmentLength = bytesAvailableInBlock;
            }
            else {
                end = true;
                fragmentLength = sliceInput.available();
            }

            // determine block type
            LogChunkType type;
            if (begin && end) {
                type = LogChunkType.FULL;
            }
            else if (begin) {
                type = LogChunkType.FIRST;
            }
            else if (end) {
                type = LogChunkType.LAST;
            }
            else {
                type = LogChunkType.MIDDLE;
            }

            // write the chunk
            writeChunk(type, sliceInput.readBytes(fragmentLength));

            // we are no longer on the first chunk
            begin = false;
        } while (sliceInput.isReadable());

        if (force) {
            mappedByteBuffer.force();
        }
    }

    private void writeChunk(LogChunkType type, Slice slice)
            throws IOException
    {
        checkArgument(slice.length() <= 0xffff, "length %s is larger than two bytes", slice.length());
        checkArgument(blockOffset + HEADER_SIZE <= BLOCK_SIZE);

        // create header
        Slice header = newLogRecordHeader(type, slice);

        // write the header and the payload
        ensureCapacity(header.length() + slice.length());
        header.getBytes(0, mappedByteBuffer);
        slice.getBytes(0, mappedByteBuffer);

        blockOffset += HEADER_SIZE + slice.length();
    }

    private void ensureCapacity(int bytes)
            throws IOException
    {
        if (mappedByteBuffer.remaining() < bytes) {
            // remap
            fileOffset += mappedByteBuffer.position();
            unmap();

            mappedByteBuffer = fileChannel.map(MapMode.READ_WRITE, fileOffset, PAGE_SIZE);
        }
    }

    private void unmap()
    {
        ByteBufferSupport.unmap(mappedByteBuffer);
    }

    private static Slice newLogRecordHeader(LogChunkType type, Slice slice)
    {
        int crc = getChunkChecksum(type.getPersistentId(), slice.getRawArray(), slice.getRawOffset(), slice.length());

        // Format the header
        Slice header = Slices.allocate(HEADER_SIZE);
        SliceOutput sliceOutput = header.output();
        sliceOutput.writeInt(crc);
        sliceOutput.writeByte((byte) (slice.length() & 0xff));
        sliceOutput.writeByte((byte) (slice.length() >>> 8));
        sliceOutput.writeByte((byte) (type.getPersistentId()));

        return header;
    }
}
