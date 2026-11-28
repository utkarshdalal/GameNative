package com.winlator.sysvshm;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Log;
import android.util.SparseArray;

import com.winlator.xconnector.XConnectorEpoll;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class SysVSharedMemory {
    private final SparseArray<SHMemory> shmemories = new SparseArray<>();
    private int maxSHMemoryId = 0;

    static {
        System.loadLibrary("winlator");
    }

    private static class SHMemory {
        private int fd;
        private long size;
        private ByteBuffer data;

        // Constructor for SHMemory
        private SHMemory() {}

        // Synthetic access methods for accessing the private fields
        static int access$000(SHMemory shMemory) {
            return shMemory.fd;
        }

        static int access$002(SHMemory shMemory, int fd) {
            shMemory.fd = fd;
            return fd;
        }

        static long access$200(SHMemory shMemory) {
            return shMemory.size;
        }

        static long access$202(SHMemory shMemory, long size) {
            shMemory.size = size;
            return size;
        }

        static ByteBuffer access$300(SHMemory shMemory) {
            return shMemory.data;
        }

        static ByteBuffer access$302(SHMemory shMemory, ByteBuffer data) {
            shMemory.data = data;
            return data;
        }
    }

    public int getFd(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            return shmemory != null ? SHMemory.access$000(shmemory) : -1;
        }
    }

    public int get(long size) {
        synchronized (shmemories) {
            int index = shmemories.size();
            int fd = ashmemCreateRegion(index, size);
            if (fd < 0) fd = createSharedMemory("sysvshm-"+index, (int)size);
            if (fd < 0) return -1;

            SHMemory shmemory = new SHMemory();
            int id = ++maxSHMemoryId;
            SHMemory.access$002(shmemory, fd);
            SHMemory.access$202(shmemory, size);
            shmemories.put(id, shmemory);
            return id;
        }
    }

    public void delete(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory != null) {
                if (SHMemory.access$000(shmemory) != -1) {
                    XConnectorEpoll.closeFd(SHMemory.access$000(shmemory));
                    SHMemory.access$002(shmemory, -1);
                }
                shmemories.remove(shmid);
            }
        }
    }

    public void deleteAll() {
        synchronized (shmemories) {
            for (int i = shmemories.size() - 1; i >= 0; i--) delete(shmemories.keyAt(i));
        }
    }

    public ByteBuffer attach(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory != null) {
                if (SHMemory.access$300(shmemory) == null) {
                    SHMemory.access$302(shmemory, mapSHMSegment(SHMemory.access$000(shmemory),
                            SHMemory.access$200(shmemory), 0, true));
            }
                return SHMemory.access$300(shmemory);
            } else {
                return null;
            }
        }
    }

    public void detach(ByteBuffer data) {
        synchronized (shmemories) {
            for (int i = 0; i < shmemories.size(); i++) {
                SHMemory shmemory = shmemories.valueAt(i);
                if (SHMemory.access$300(shmemory) == data) {
                    if (SHMemory.access$300(shmemory) != null) {
                        unmapSHMSegment(SHMemory.access$300(shmemory), SHMemory.access$200(shmemory));
                        SHMemory.access$302(shmemory, null);
                    }
                    break;
                }
            }
        }
    }

    private static int createSharedMemory(String name, int size) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                SharedMemory sharedMemory = SharedMemory.create(name, size);
                Method getFdMethod = sharedMemory.getClass().getMethod("getFd");
                Integer fd = (Integer) getFdMethod.invoke(sharedMemory);
                return fd != null ? fd : -1;
                }
        } catch (ErrnoException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public static native int createMemoryFd(String name, int size);

    private static native int ashmemCreateRegion(int index, long size);

    public static native ByteBuffer mapSHMSegment(int fd, long size, int offset, boolean readonly);

    public static native void unmapSHMSegment(ByteBuffer data, long size);
}
