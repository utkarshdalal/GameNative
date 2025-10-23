package com.winlator.alsaserver;

import android.util.Log;
import com.winlator.alsaserver.ALSAClient;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override // com.winlator.xconnector.RequestHandler
    public boolean handleRequest(Client client) throws IOException {
        XStreamLock lock;
        ALSAClient alsaClient = (ALSAClient) client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();
        if (inputStream.available() < 5) {
            return false;
        }
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();
        switch (requestCode) {
            case RequestCodes.CLOSE:
                alsaClient.release();
                return true;
            case RequestCodes.START:
                alsaClient.start();
                return true;
            case RequestCodes.STOP:
                alsaClient.stop();
                return true;
            case RequestCodes.PAUSE:
                alsaClient.pause();
                return true;
            case RequestCodes.PREPARE:
                if (inputStream.available() < requestLength) {
                    return false;
                }
                alsaClient.setChannels(inputStream.readByte());
                alsaClient.setDataType(ALSAClient.DataType.values()[inputStream.readByte()]);
                alsaClient.setSampleRate(inputStream.readInt());
                alsaClient.setBufferSize(inputStream.readInt());
                alsaClient.prepare();
                createSharedMemory(alsaClient, outputStream);
                return true;
            case RequestCodes.WRITE:
                ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
                int dataCap = sharedBuffer != null ? sharedBuffer.capacity() - 4 : 0;

                if (sharedBuffer != null && requestLength <= dataCap) {
                    copySharedBuffer(alsaClient, requestLength, outputStream);
                    alsaClient.writeDataToTrack(alsaClient.getAuxBuffer());
                    sharedBuffer.putInt(0, alsaClient.pointer());
                    return true;
                }
                if (inputStream.available() < requestLength) {
                    return false;
                }
                alsaClient.writeDataToTrack(inputStream.readByteBuffer(requestLength));
                return true;
            case RequestCodes.DRAIN:
                alsaClient.drain();
                return true;
            case RequestCodes.POINTER:
                lock = outputStream.lock();
                try {
                    outputStream.writeInt(alsaClient.pointer());
                    if (lock != null) {
                        lock.close();
                        return true;
                    }
                    return true;
                } finally {
                }
            case RequestCodes.MIN_BUFFER_SIZE:
                byte channels = inputStream.readByte();
                ALSAClient.DataType dataType = ALSAClient.DataType.values()[inputStream.readByte()];
                int sampleRate = inputStream.readInt();
                int minBufferSize = ALSAClient.latencyMillisToBufferSize(alsaClient.options.latencyMillis, channels, dataType, sampleRate);
                lock = outputStream.lock();
                try {
                    outputStream.writeInt(minBufferSize);
                    if (lock != null) {
                        lock.close();
                        return true;
                    }
                    return true;
                } finally {
                }
            default:
                return true;
        }
    }

    private void copySharedBuffer(ALSAClient alsaClient, int requestLength, XOutputStream outputStream) throws IOException {
        ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
        ByteBuffer auxBuffer = alsaClient.getAuxBuffer();
        auxBuffer.position(0).limit(requestLength);
        sharedBuffer.position(4).limit(requestLength + 4);
        auxBuffer.put(sharedBuffer);
        try (XStreamLock lock = outputStream.lock()){
            outputStream.writeByte((byte) 1);
        }
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        ByteBuffer buffer;
        int shmSize = alsaClient.getBufferSizeInBytes() + 4;
        StringBuilder sb = new StringBuilder();
        sb.append("alsa-shm");
        int i = this.maxSHMemoryId + 1;
        this.maxSHMemoryId = i;
        sb.append(i);
        int fd = SysVSharedMemory.createMemoryFd(sb.toString(), shmSize);
        if (fd >= 0 && (buffer = SysVSharedMemory.mapSHMSegment(fd, shmSize, 0, false)) != null) {
            alsaClient.setSharedBuffer(buffer);
        }
        try {
            XStreamLock lock = outputStream.lock();
            try {
                outputStream.writeByte((byte) 0);
                outputStream.setAncillaryFd(fd);
                if (lock != null) {
                    lock.close();
                }
            } finally {
            }
        } finally {
            if (fd >= 0) {
                XConnectorEpoll.closeFd(fd);
            }
        }
    }
}
