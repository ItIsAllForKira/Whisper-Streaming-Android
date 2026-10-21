package org.whisper.enstream.audio;

import java.util.concurrent.locks.ReentrantLock;
import java.util.Arrays;

public class CircularAudioBuffer {
    private final byte[] buffer;
    private final int capacity;
    private int writeIndex;
    private int readIndex;
    private int size;
    private final ReentrantLock lock = new ReentrantLock();

    // Предположим, мы хотим буфер на 1 минуту PCM 16kHz, 16-bit, mono
    // 1 мин = 60 * 1000 мс
    // 16000 семплов/сек * 2 байта/семпл = 32000 байт/сек
    // 32000 * 60 = 1 920 000 байт
    public static final int DEFAULT_BUFFER_SIZE_SAMPLES = 16000 * 300; // 1 минут
    public static final int BYTES_PER_SAMPLE = 2; // 16-bit = 2 байта
    public static final int DEFAULT_BUFFER_SIZE_BYTES = DEFAULT_BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE;

    public CircularAudioBuffer(int sizeInBytes) {
        this.buffer = new byte[sizeInBytes];
        this.capacity = sizeInBytes;
        this.writeIndex = 0;
        this.readIndex = 0;
        this.size = 0;
        Arrays.fill(this.buffer, (byte) 0); // Инициализируем тишиной
    }

    public CircularAudioBuffer() {
        this(DEFAULT_BUFFER_SIZE_BYTES);
    }

    public void write(byte[] data) {
        lock.lock();
        try {
            int len = data.length;
            if (len > capacity) {
                // Данные слишком длинные, берем последние N байт
                System.arraycopy(data, len - capacity, buffer, 0, capacity);
                writeIndex = 0;
                readIndex = 0;
                size = capacity;
                return;
            }

            // Если данных будет больше, чем размер буфера, сдвигаем readIndex
            if (size + len > capacity) {
                int overflow = (size + len) - capacity;
                readIndex = (readIndex + overflow) % capacity;
                size = capacity - len; // Уменьшаем размер на длину новых данных, чтобы потом добавить их
            }

            int availableSpace = capacity - writeIndex;
            if (len <= availableSpace) {
                // Данные помещаются в один кусок
                System.arraycopy(data, 0, buffer, writeIndex, len);
                writeIndex = (writeIndex + len) % capacity;
            } else {
                // Данные разбиваются на 2 части
                System.arraycopy(data, 0, buffer, writeIndex, availableSpace);
                System.arraycopy(data, availableSpace, buffer, 0, len - availableSpace);
                writeIndex = (len - availableSpace) % capacity;
            }
            size += len;
        } finally {
            lock.unlock();
        }
    }

    public byte[] read(int length) {
        lock.lock();
        try {
            if (length > size) {
                // Запрашиваем больше, чем есть, возвращаем то, что есть
                length = size;
            }
            if (length <= 0) {
                return new byte[0]; // Нечего читать
            }

            byte[] result = new byte[length];
            int availableUntilEnd = capacity - readIndex;

            if (length <= availableUntilEnd) {
                // Чтение в один кусок
                System.arraycopy(buffer, readIndex, result, 0, length);
                readIndex = (readIndex + length) % capacity;
            } else {
                // Чтение разбито на 2 части
                System.arraycopy(buffer, readIndex, result, 0, availableUntilEnd);
                System.arraycopy(buffer, 0, result, availableUntilEnd, length - availableUntilEnd);
                readIndex = (length - availableUntilEnd) % capacity;
            }
            size -= length;
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int available() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            writeIndex = 0;
            readIndex = 0;
            size = 0;
            Arrays.fill(buffer, (byte) 0);
        } finally {
            lock.unlock();
        }
    }
}