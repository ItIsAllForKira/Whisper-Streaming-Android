package org.whisper.enstream.stream;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

@OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class Media3StreamProcessor {
    private static final String TAG = "Media3StreamProcessor";

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SECOND = TARGET_SAMPLE_RATE * 2;
    private static final int CHUNK_DURATION_SEC = 15;
    private static final int CHUNK_SIZE = BYTES_PER_SECOND * CHUNK_DURATION_SEC;

    // === Параметры переподключения ===
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final int RECONNECT_DELAY_MS = 2000;
    private int reconnectAttempts = 0;
    private boolean isReconnecting = false;

    private final Context context;
    private ExoPlayer exoPlayer;
    private StreamListener listener;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    private volatile boolean isStopped = false;
    private volatile boolean formatNotified = false;
    private volatile int currentSampleRate = 0;
    private volatile int currentChannelCount = 0;
    private volatile boolean isPaused = false;
    private String currentUrl = null;
    private long currentPlaybackPosition = 0;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    public void setPaused(boolean paused) {
        this.isPaused = paused;
        if (exoPlayer != null) {
            if (paused) {
                exoPlayer.setPlayWhenReady(false);
            } else {
                exoPlayer.setPlayWhenReady(true);
            }
        }
        Log.d(TAG, paused ? "Stream paused" : "Stream resumed");
    }

    public interface StreamListener {
        void onAudioChunk(byte[] pcmData);
        void onAudioFormat(int sampleRate, int channelCount);
        void onError(String error);
        void onStreamStarted();
        void onStreamStopped();
        void onReconnecting();      // ДОБАВЛЕНО: уведомление о начале переподключения
        void onReconnected();       // ДОБАВЛЕНО: уведомление об успешном переподключении
    }

    public Media3StreamProcessor(Context context, StreamListener listener) {
        this.context = context;
        this.listener = listener;
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Log.d(TAG, "Media3StreamProcessor created, chunk size: " + CHUNK_SIZE + " bytes (" + CHUNK_DURATION_SEC + " sec)");
    }

    public void startStream(String url) {
        Log.d(TAG, "startStream: " + url);

        this.currentUrl = url;
        this.reconnectAttempts = 0;
        this.isReconnecting = false;

        isStopped = false;
        formatNotified = false;
        currentSampleRate = 0;
        currentChannelCount = 0;

        if (isProcessing.get()) {
            Log.d(TAG, "Already processing");
            return;
        }

        createAndStartPlayer(url);
        registerNetworkCallback();
    }

    /**
     * Проверка наличия интернет-соединения
     */
    public boolean isNetworkAvailable() {
        if (connectivityManager == null) return true;

        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /**
     * Регистрация слушателя изменения сети
     */
    private void registerNetworkCallback() {
        if (connectivityManager == null) return;

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available");
                if (isPaused && !isStopped && exoPlayer != null && exoPlayer.getPlaybackState() == Player.STATE_IDLE) {
                    Log.d(TAG, "Network restored, attempting reconnect");
                    attemptReconnect();
                }
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Network lost");
                if (listener != null && !isStopped) {
                    listener.onError("Network connection lost");
                }
            }
        };

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    /**
     * Попытка переподключения при Resume
     */
    public boolean checkAndReconnectIfNeeded() {
        if (isStopped || isReconnecting) return false;

        // Проверяем, нужно ли переподключение
        boolean needsReconnect = false;

        if (exoPlayer == null) {
            Log.d(TAG, "ExoPlayer is null, need reconnect");
            needsReconnect = true;
        } else if (exoPlayer.getPlaybackState() == Player.STATE_IDLE) {
            Log.d(TAG, "ExoPlayer in IDLE state, need reconnect");
            needsReconnect = true;
        } else if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
            Log.d(TAG, "ExoPlayer ended, need reconnect");
            needsReconnect = true;
        } else if (!isNetworkAvailable()) {
            Log.d(TAG, "No network available, will retry later");
            if (listener != null) {
                listener.onError("No internet connection - check network");
            }
            return false;
        }

        if (needsReconnect && currentUrl != null) {
            attemptReconnect();
            return true;
        }

        return false;
    }

    /**
     * Выполнение переподключения
     */
    private void attemptReconnect() {
        if (isReconnecting || isStopped) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached");
            if (listener != null) {
                listener.onError("Failed to reconnect after " + MAX_RECONNECT_ATTEMPTS + " attempts");
            }
            reconnectAttempts = 0;
            return;
        }

        isReconnecting = true;
        reconnectAttempts++;

        Log.d(TAG, "Attempting reconnect (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");

        if (listener != null) {
            listener.onReconnecting();
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (isStopped) {
                    isReconnecting = false;
                    return;
                }

                // Сохраняем текущую позицию
                if (exoPlayer != null) {
                    currentPlaybackPosition = exoPlayer.getCurrentPosition();
                    exoPlayer.release();
                    exoPlayer = null;
                }

                // Создаём новый плеер
                createAndStartPlayer(currentUrl);

                // Восстанавливаем позицию (если возможно)
                if (exoPlayer != null && currentPlaybackPosition > 0) {
                    exoPlayer.seekTo(currentPlaybackPosition);
                }

                isReconnecting = false;
                reconnectAttempts = 0;

                Log.d(TAG, "Reconnect successful");
                if (listener != null) {
                    listener.onReconnected();
                }

            } catch (Exception e) {
                Log.e(TAG, "Reconnect failed: " + e.getMessage());
                isReconnecting = false;

                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    attemptReconnect();
                } else if (listener != null) {
                    listener.onError("Failed to reconnect: " + e.getMessage());
                }
            }
        }, RECONNECT_DELAY_MS);
    }

    private void createAndStartPlayer(String url) {
        try {
            TeeAudioProcessor.AudioBufferSink audioSink = new TeeAudioProcessor.AudioBufferSink() {
                @Override
                public void flush(int sampleRate, int channelCount, int encoding) {
                    Log.d(TAG, String.format("Audio format: %d Hz, %d ch, encoding=%d",
                            sampleRate, channelCount, encoding));

                    currentSampleRate = sampleRate;
                    currentChannelCount = channelCount;

                    if (!formatNotified && listener != null) {
                        formatNotified = true;
                        listener.onAudioFormat(sampleRate, channelCount);
                    }
                }

                @Override
                public void handleBuffer(ByteBuffer buffer) {
                    if (isStopped || !isProcessing.get()) {
                        return;
                    }

                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);

                    synchronized (audioBuffer) {
                        audioBuffer.write(data, 0, data.length);

                        if (audioBuffer.size() >= CHUNK_SIZE) {
                            byte[] chunk = audioBuffer.toByteArray();
                            byte[] processChunk = new byte[CHUNK_SIZE];
                            System.arraycopy(chunk, 0, processChunk, 0, CHUNK_SIZE);

                            audioBuffer.reset();
                            if (chunk.length > CHUNK_SIZE) {
                                audioBuffer.write(chunk, CHUNK_SIZE, chunk.length - CHUNK_SIZE);
                            }

                            if (listener != null && !isStopped) {
                                Log.d(TAG, "Audio chunk ready: " + processChunk.length + " bytes");
                                listener.onAudioChunk(processChunk);
                            }
                        }
                    }
                }
            };

            TeeAudioProcessor audioProcessor = new TeeAudioProcessor(audioSink);

            DefaultAudioSink defaultAudioSink = new DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new AudioProcessor[]{audioProcessor})
                    .build();

            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context) {
                @Override
                protected AudioSink buildAudioSink(Context ctx, boolean enableFloatOutput,
                                                   boolean enableAudioTrackPlaybackParams) {
                    return defaultAudioSink;
                }
            };

            exoPlayer = new ExoPlayer.Builder(context, renderersFactory)
                    .setTrackSelector(new DefaultTrackSelector(context))
                    .build();

            DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(30000)
                    .setReadTimeoutMs(30000);

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));

            boolean isHls = url.contains(".m3u8") || url.contains("m3u8") ||
                    url.contains("akamaized.net") || url.contains("hls") ||
                    url.endsWith("/playlist.m3u8") || url.toLowerCase().contains(".isml");

            if (isHls) {
                HlsMediaSource hlsMediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem);
                exoPlayer.setMediaSource(hlsMediaSource);
            } else {
                ProgressiveMediaSource progressiveMediaSource =
                        new ProgressiveMediaSource.Factory(dataSourceFactory, new DefaultExtractorsFactory())
                                .createMediaSource(mediaItem);
                exoPlayer.setMediaSource(progressiveMediaSource);
            }

            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
            exoPlayer.setVolume(0f);

            isProcessing.set(true);

            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && !isStopped) {
                        if (listener != null) {
                            listener.onStreamStarted();
                        }
                        Log.d(TAG, "Stream started");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage(), error);

                    if (listener != null && !isStopped) {
                        // При ошибке пробуем переподключиться
                        if (!isReconnecting && isNetworkAvailable()) {
                            attemptReconnect();
                        } else {
                            listener.onError("Stream error: " + error.getMessage());
                        }
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start stream: " + e.getMessage(), e);
            if (listener != null) {
                listener.onError("Failed to start stream: " + e.getMessage());
            }
        }
    }

    public void stopStream() {
        Log.d(TAG, "stopStream called");
        isStopped = true;
        isProcessing.set(false);
        isReconnecting = false;
        reconnectAttempts = 0;

        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback: " + e.getMessage());
            }
            networkCallback = null;
        }

        if (exoPlayer != null) {
            try {
                exoPlayer.stop();
                exoPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing ExoPlayer: " + e.getMessage());
            }
            exoPlayer = null;
        }

        synchronized (audioBuffer) {
            audioBuffer.reset();
        }

        if (listener != null) {
            listener.onStreamStopped();
        }
    }

    public boolean isProcessing() {
        return isProcessing.get() && !isStopped;
    }

    public int getCurrentSampleRate() {
        return currentSampleRate;
    }

    public int getCurrentChannelCount() {
        return currentChannelCount;
    }
}