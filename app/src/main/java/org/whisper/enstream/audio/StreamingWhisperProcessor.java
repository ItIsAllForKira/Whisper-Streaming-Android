package org.whisper.enstream.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.whisper.enstream.asr.Whisper;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class StreamingWhisperProcessor {
    private static final String TAG = "StreamingProcessor";

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1;
    private static final int TARGET_BYTES_PER_SECOND = TARGET_SAMPLE_RATE * TARGET_CHANNELS * 2;
    private static final int BLOCK_SIZE_BYTES = TARGET_BYTES_PER_SECOND * 15;
    private static final int PAUSE_AFTER_PLAYBACK_MS = 500;

    private final CircularAudioBuffer circularBuffer;
    private final Queue<AudioBlock> audioBlockBuffer = new LinkedList<>();
    private final BlockingQueue<AudioBlock> playbackQueue = new LinkedBlockingQueue<>();

    private Thread transcriberThread;
    private Thread playbackThread;
    private volatile boolean isRunning = true;
    private final AtomicBoolean isStopping = new AtomicBoolean(false);

    // Флаг паузы воспроизведения
    private volatile boolean isPlaybackPaused = false;
    private final Object pauseLock = new Object();

    // ОЧЕРЕДЬ для отложенных блоков (вместо одного pendingPlaybackBlock)
    private final Queue<AudioBlock> pendingPlaybackQueue = new LinkedList<>();

    private int blockCounter = 0;
    private AudioTrack audioTrack;
    private boolean audioTrackInitialized = false;

    private final StringBuilder fullHistory = new StringBuilder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Whisper whisper;
    private final MainActivityCallback callback;

    private volatile boolean isPlaying = false;
    private AudioBlock currentPlayingBlock = null;

    private int currentSampleRate = 0;
    private int currentChannelCount = 0;

    private static final Pattern SENTENCE_PATTERN = Pattern.compile("(?<=[.!?])\\s+");

    private static class AudioBlock {
        byte[] audioData;
        String transcribedText;
        int blockNumber;
        long timestamp;
        boolean textDisplayed = false;

        double getDurationSeconds() {
            return (double) audioData.length / TARGET_BYTES_PER_SECOND;
        }
    }

    public interface MainActivityCallback {
        void onTranscriptionText(String text);
        void onHistoryReady(String history);
        void onTranscriptionError(String error);
    }

    public StreamingWhisperProcessor(Whisper whisper, MainActivityCallback callback) {
        this.whisper = whisper;
        this.callback = callback;
        this.circularBuffer = new CircularAudioBuffer(CircularAudioBuffer.DEFAULT_BUFFER_SIZE_BYTES);

        Log.d(TAG, "CircularAudioBuffer initialized: " + CircularAudioBuffer.DEFAULT_BUFFER_SIZE_BYTES + " bytes");

        if (whisper != null) {
            whisper.setLanguage("en");
        }

        initAudioTrack();
        startTranscriberThread();
        startPlaybackThread();

        Log.d(TAG, "StreamingWhisperProcessor created");
        Log.d(TAG, "Block size: " + BLOCK_SIZE_BYTES + " bytes (15 sec)");
    }

    public void setPlaybackPaused(boolean paused) {
        synchronized (pauseLock) {
            this.isPlaybackPaused = paused;

            if (audioTrack != null && audioTrackInitialized) {
                if (paused) {
                    audioTrack.pause();
                    Log.d(TAG, "Playback PAUSED by user");
                } else {
                    audioTrack.play();
                    Log.d(TAG, "Playback RESUMED by user");
                    pauseLock.notifyAll();
                }
            }
        }
    }

    public boolean isPlaybackPaused() {
        return isPlaybackPaused;
    }

    public void setAudioFormat(int sampleRate, int channelCount) {
        this.currentSampleRate = sampleRate;
        this.currentChannelCount = channelCount;
        Log.d(TAG, "Audio format set: " + sampleRate + " Hz, " + channelCount + " ch");
    }

    private void initAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(
                TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = minBufferSize * 8;

        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(TARGET_SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            audioTrack.play();
            audioTrackInitialized = true;
            Log.d(TAG, "AudioTrack initialized");
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack init failed: " + e.getMessage());
            audioTrackInitialized = false;
        }
    }

    private void updateUIText(final String newText) {
        if (callback == null) return;
        mainHandler.post(() -> {
            callback.onTranscriptionText(newText);
            Log.d(TAG, "UI updated, text length: " + newText.length());
        });
    }

    private void processBlockSequentially(final AudioBlock block) {
        if (isStopping.get() || !isRunning) {
            return;
        }

        // Проверка паузы перед началом
        synchronized (pauseLock) {
            while (isPlaybackPaused && !isStopping.get()) {
                Log.d(TAG, "Block " + block.blockNumber + " waiting - playback paused");
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // Если сейчас играет другой блок - добавляем в ОЧЕРЕДЬ, а не перезаписываем!
        if (isPlaying) {
            pendingPlaybackQueue.offer(block);
            Log.d(TAG, "Block " + block.blockNumber + " queued - currently playing. Queue size: " + pendingPlaybackQueue.size());
            return;
        }

        Log.d(TAG, "Processing block " + block.blockNumber + " (duration: " + block.getDurationSeconds() + " sec)");

        currentPlayingBlock = block;

        // Вывод текста (только если ещё не выведен)
        if (block.transcribedText != null && !block.transcribedText.isEmpty()) {
            if (!block.textDisplayed) {
                block.textDisplayed = true;
                updateUIText(block.transcribedText);
                Log.d(TAG, "Block " + block.blockNumber + " - TEXT DISPLAYED (length: " + block.transcribedText.length() + ")");

                if (fullHistory.length() > 0) {
                    fullHistory.append("\n");
                }
                fullHistory.append(block.transcribedText);
            } else {
                Log.d(TAG, "Block " + block.blockNumber + " - text already displayed, skipping");
            }
        } else {
            Log.d(TAG, "Block " + block.blockNumber + " - no text to display");
        }

        // Воспроизведение аудио
        isPlaying = true;

        new Thread(() -> {
            try {
                playAudio(block);
                Log.d(TAG, "Block " + block.blockNumber + " - PLAYBACK COMPLETED");
                isPlaying = false;

                if (PAUSE_AFTER_PLAYBACK_MS > 0 && !isPlaybackPaused) {
                    Thread.sleep(PAUSE_AFTER_PLAYBACK_MS);
                }

                // Берём СЛЕДУЮЩИЙ блок из очереди (FIFO)
                AudioBlock nextBlock = pendingPlaybackQueue.poll();
                if (nextBlock != null && !isStopping.get()) {
                    Log.d(TAG, "Processing next queued block " + nextBlock.blockNumber + " (remaining in queue: " + pendingPlaybackQueue.size() + ")");
                    processBlockSequentially(nextBlock);
                } else if (currentPlayingBlock == block) {
                    currentPlayingBlock = null;
                }

            } catch (Exception e) {
                Log.e(TAG, "Playback error: " + e.getMessage());
                isPlaying = false;
            }
        }).start();
    }

    private void startTranscriberThread() {
        transcriberThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            Log.d(TAG, "Transcriber thread started");

            while (isRunning && !isStopping.get()) {
                AudioBlock block = null;

                synchronized (audioBlockBuffer) {
                    while (audioBlockBuffer.isEmpty() && isRunning && !isStopping.get()) {
                        try {
                            audioBlockBuffer.wait(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (!audioBlockBuffer.isEmpty()) {
                        block = audioBlockBuffer.poll();
                    }
                }

                if (block != null && isRunning && !isStopping.get()) {
                    transcribeBlock(block);
                }
            }
            Log.d(TAG, "Transcriber thread stopped");
        });
        transcriberThread.start();
    }

    private void startPlaybackThread() {
        playbackThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.d(TAG, "Playback thread started");

            while (isRunning && !isStopping.get()) {
                try {
                    AudioBlock block = playbackQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (block != null && isRunning && !isStopping.get()) {
                        processBlockSequentially(block);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Log.d(TAG, "Playback thread stopped");
        });
        playbackThread.start();
    }

    private void transcribeBlock(AudioBlock block) {
        try {
            if (!isRunning || isStopping.get()) return;

            long startTime = System.currentTimeMillis();
            float[] audioSamples = bytesToFloat(block.audioData);
            String transcribedText = whisper.transcribeSync(audioSamples);
            long elapsed = System.currentTimeMillis() - startTime;

            Log.d(TAG, "Block " + block.blockNumber + " transcribed in " + elapsed + "ms");

            if (!isRunning || isStopping.get()) return;

            String formattedText = formatText(transcribedText);
            block.transcribedText = formattedText;

            // Отправляем в очередь воспроизведения
            playbackQueue.offer(block);

        } catch (Exception e) {
            Log.e(TAG, "Transcribe error: " + e.getMessage());
        }
    }

    private String formatText(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] sentences = SENTENCE_PATTERN.split(text);
        StringBuilder result = new StringBuilder();
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (!sentence.isEmpty()) {
                if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
                    sentence += ".";
                }
                result.append(sentence).append("\n");
            }
        }
        return result.toString().trim();
    }

    private float[] bytesToFloat(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 2) return new float[0];
        int numSamples = audioBytes.length / 2;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((audioBytes[i * 2] & 0xFF) | (audioBytes[i * 2 + 1] << 8));
            samples[i] = sample / 32768.0f;
        }
        return samples;
    }

    private void playAudio(AudioBlock block) {
        if (!isRunning || isStopping.get()) return;
        if (audioTrack == null || !audioTrackInitialized) return;

        byte[] audioData = block.audioData;
        int offset = 0;
        int chunkSize = 4096;

        while (offset < audioData.length && isRunning && !isStopping.get()) {
            // Проверка паузы во время воспроизведения
            synchronized (pauseLock) {
                while (isPlaybackPaused && !isStopping.get()) {
                    Log.d(TAG, "Playback paused during block " + block.blockNumber);
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            int currentChunk = Math.min(chunkSize, audioData.length - offset);
            int written = audioTrack.write(audioData, offset, currentChunk);
            if (written < 0) break;
            offset += written;
        }

        Log.d(TAG, "Played block " + block.blockNumber + ": " + offset + "/" + audioData.length + " bytes");
    }

    public void processAudioChunk(byte[] convertedPcm) {
        if (!isRunning || isStopping.get()) return;
        if (convertedPcm == null || convertedPcm.length == 0) return;

        circularBuffer.write(convertedPcm);
        int available = circularBuffer.available();

        if (available >= BLOCK_SIZE_BYTES) {
            byte[] blockData = circularBuffer.read(BLOCK_SIZE_BYTES);
            if (blockData.length == BLOCK_SIZE_BYTES) {
                AudioBlock block = new AudioBlock();
                block.audioData = blockData;
                block.blockNumber = ++blockCounter;
                block.timestamp = System.currentTimeMillis();

                synchronized (audioBlockBuffer) {
                    audioBlockBuffer.add(block);
                    audioBlockBuffer.notify();
                }
                Log.d(TAG, "Block " + block.blockNumber + " created, size: " + blockData.length + " bytes");
                Log.d(TAG, "  audioBlockBuffer size: " + audioBlockBuffer.size());
                Log.d(TAG, "  playbackQueue size: " + playbackQueue.size());
            }
        }
    }

    public void stopImmediately() {
        Log.d(TAG, "Stopping processor");
        isStopping.set(true);
        isRunning = false;

        if (playbackThread != null) playbackThread.interrupt();
        if (transcriberThread != null) transcriberThread.interrupt();

        // Очищаем кольцевой буфер
        circularBuffer.clear();

        // Очищаем очередь отложенных блоков
        pendingPlaybackQueue.clear();

        synchronized (audioBlockBuffer) {
            audioBlockBuffer.clear();
        }
        playbackQueue.clear();

        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing AudioTrack: " + e.getMessage());
            }
            audioTrack = null;
        }

        final String history = fullHistory.toString();
        if (callback != null) {
            mainHandler.post(() -> callback.onHistoryReady(history));
        }

        Log.d(TAG, "Processor stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void close() {
        stopImmediately();
    }
}