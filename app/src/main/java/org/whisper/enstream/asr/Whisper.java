package org.whisper.enstream.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.whisper.enstream.SetupActivity;
import org.whisper.enstream.neural_networks.NeuralNetworkApi;
import org.whisper.enstream.neural_networks.voice.Recognizer;
import org.whisper.enstream.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public void setAction(Recognizer.Action action) {
    }

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Text + Audio!";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;

    public Whisper(Context context) {
        mContext = context;

        File sdcardDataFolder = mContext.getExternalFilesDir(null);

        if (sdcardDataFolder != null && !sdcardDataFolder.exists() && !sdcardDataFolder.mkdirs()) {
            Log.e(TAG, "Failed to make directory: " + sdcardDataFolder);
            return;
        }

        File[] files = sdcardDataFolder.listFiles();
        int fileCount = 0;
        for (File file : files) {
            if (file.isFile()) {
                fileCount++;
            }
        }
        if (fileCount != 6) {
            Intent intent = new Intent(mContext, SetupActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } else {
            Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
            threadProcessRecordBuffer.start();
        }
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel() {
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
            }
        });

        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text, languageCode, mAction);
                sendResult(whisperResult);

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "ERROR during recognition");
                mInProgress.set(false);
            }
        });
    }

    public void unloadModel() {
        if (recognizer != null) {
            recognizer.destroy();
        }
    }

    public void setLanguage(String language) {
        this.mLangCode = language;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    /**
     * Обработка потоковых аудиоданных
     */
    public void processStreamChunk(float[] audioData) {
        Log.d(TAG, "processStreamChunk: received " + audioData.length + " samples");
        if (audioData == null || audioData.length == 0) {
            Log.e(TAG, "processStreamChunk: empty audio data");
            return;
        }

        ByteBuffer buffer = ByteBuffer.allocate(audioData.length * 2);
        buffer.order(ByteOrder.nativeOrder());
        for (float sample : audioData) {
            buffer.putShort((short) (sample * 32768));
        }

        RecordBuffer.setOutputBuffer(buffer.array());
        Log.d(TAG, "processStreamChunk: saved to RecordBuffer, size=" + buffer.array().length);

        if (!isInProgress()) {
            Log.d(TAG, "processStreamChunk: starting Whisper");
            start();
        } else {
            Log.d(TAG, "processStreamChunk: Whisper already processing");
        }
    }

    public void removeCallback(RecognizerListener listener) {
        if (recognizer != null) {
            recognizer.removeCallback(listener);
        }
    }

    /**
     * Синхронное распознавание для потоковой обработки
     * Возвращает текст распознавания (блокирующий вызов)
     */
    public String transcribeSync(float[] audioData) {
        Log.d(TAG, "transcribeSync: processing " + audioData.length + " samples");

        if (audioData == null || audioData.length == 0) {
            return "";
        }

        ByteBuffer buffer = ByteBuffer.allocate(audioData.length * 2);
        buffer.order(ByteOrder.nativeOrder());
        for (float sample : audioData) {
            float clipped = Math.max(-1.0f, Math.min(1.0f, sample));
            buffer.putShort((short) (clipped * 32767));
        }

        byte[] audioBytes = buffer.array();
        Log.d(TAG, "transcribeSync: converted to " + audioBytes.length + " bytes");

        RecordBuffer.setOutputBuffer(audioBytes);

        final String[] result = new String[1];
        final Object lock = new Object();

        RecognizerListener tempListener = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode,
                                                 double confidenceScore, boolean isFinal) {
                Log.d(TAG, "transcribeSync result: " + text);
                result[0] = text;
                synchronized (lock) {
                    lock.notify();
                }
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.e(TAG, "transcribeSync error");
                result[0] = "";
                synchronized (lock) {
                    lock.notify();
                }
            }
        };

        recognizer.addCallback(tempListener);

        long startTime = System.currentTimeMillis();
        // Используем стандартный метод recognize без дополнительных параметров
        recognizer.recognize(RecordBuffer.getSamples(), 1, mLangCode, mAction);

        synchronized (lock) {
            try {
                lock.wait(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result[0] = "";
            }
        }

        long timeTaken = System.currentTimeMillis() - startTime;
        Log.d(TAG, "transcribeSync completed in " + timeTaken + "ms");

        recognizer.removeCallback(tempListener);

        return result[0] != null ? result[0] : "";
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);
                recognizer.recognize(RecordBuffer.getSamples(), 1, mLangCode, mAction);
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }
}