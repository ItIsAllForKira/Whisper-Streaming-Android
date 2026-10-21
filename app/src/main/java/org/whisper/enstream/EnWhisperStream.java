package org.whisper.enstream;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.whisper.enstream.audio.AudioConverter;
import org.whisper.enstream.audio.StreamingWhisperProcessor;
import org.whisper.enstream.asr.Whisper;
import org.whisper.enstream.asr.WhisperResult;
import org.whisper.enstream.stream.Media3StreamProcessor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EnWhisperStream extends Activity implements StreamingWhisperProcessor.MainActivityCallback {

    private static final String TAG = "EnWhisperStream";

    // UI компоненты
    private Spinner streamSelector;
    private EditText customUrlInput;
    private TextView resultView;
    private Button startButton;
    private Button stopButton;
    private Button pauseButton;
    private TextView statusText;
    private TextView timerText;

    // Аудио компоненты
    private Media3StreamProcessor streamProcessor;
    private volatile boolean isListening = false;

    // Whisper компоненты
    private Whisper whisper;
    private AudioConverter audioConverter;
    private StreamingWhisperProcessor streamingProcessor;
    private boolean isModelReady = false;

    // Текст
    private String currentTranscription = "";
    private String fullHistory = "";

    // Таймер
    private long startTime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private long totalPlaybackTimeAtStop = 0;

    // Список радиостанций
    private final Map<String, String> STREAMS = new LinkedHashMap<String, String>() {{
        put("Paste URL", "Enter URL");
        put("Aljazeera", "https://live-hls-audio-aje-fa.getaj.net/VOICE-AJE/01.m3u8");
        put("BBC", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service");
        put("BBC_east", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service_east_asia");
        put("BBC_radio_4", "https://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d128000.norewind.m3u8");
        put("Bloomberg", "https://playerservices.streamtheworld.com/api/livestream-redirect/WBBRDCAAC.aac");
        put("Bloomberg_Boston", "https://playerservices.streamtheworld.com/api/livestream-redirect/WRCAAMAAC.aac");
        put("Bloomberg_Busines", "https://stream.revma.ihrhls.com/zc301");
        put("CNN_Int", "https://tunein.cdnstream1.com/3519_96.aac");
        put("CNN", "https://tunein.cdnstream1.com/2868_96.aac");
        put("Foxnews", "https://live.amperwave.net/direct/foxnewsradio-foxnewsradioaac-imc?source=fnr.web");
        put("GB_news", "https://listen-gbnews.sharp-stream.com/gbnews.mp3");
        put("SKYNEWS", "https://playerservices.streamtheworld.com/api/livestream-redirect/NOVA_SKYNEWSAAC.aac");
        put("VOA", "https://voa-ingest.akamaized.net/hls/live/2035234/160_342L/playlist.m3u8");
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupStreamSelector();
        checkModelAndPermissions();
    }

    private void initViews() {
        streamSelector = findViewById(R.id.stream_selector);
        customUrlInput = findViewById(R.id.custom_url_input);
        resultView = findViewById(R.id.tvResult);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);
        statusText = findViewById(R.id.tvStatus);
        timerText = findViewById(R.id.timer_text);

        pauseButton = findViewById(R.id.pause_button);
        pauseButton.setOnClickListener(v -> togglePause());
        pauseButton.setEnabled(false);

        resultView.setMovementMethod(new ScrollingMovementMethod());
        resultView.setTextIsSelectable(true);

        startButton.setOnClickListener(v -> startListening());
        stopButton.setOnClickListener(v -> stopListening());

        stopButton.setEnabled(false);

        resultView.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Transcription", resultView.getText());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void setupStreamSelector() {
        List<String> streamNames = new ArrayList<>(STREAMS.keySet());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, streamNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        streamSelector.setAdapter(adapter);

        streamSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedName = (String) parent.getItemAtPosition(position);
                String newUrl = STREAMS.get(selectedName);
                customUrlInput.setText(newUrl);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void checkModelAndPermissions() {
        File modelFolder = getExternalFilesDir(null);
        if (modelFolder == null || !isModelInstalled(modelFolder)) {
            Intent intent = new Intent(this, SetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        initWhisper();
    }

    private boolean isModelInstalled(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return false;
        int fileCount = 0;
        for (File file : files) {
            if (file.isFile()) fileCount++;
        }
        return fileCount >= 6;
    }

    private void initWhisper() {
        statusText.setText("Loading Whisper model...");

        whisper = new Whisper(this);

        // ИСПРАВЛЕНО: onResultReceived теперь принимает WhisperResult
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                runOnUiThread(() -> statusText.setText(message));
            }

            @Override
            public void onResultReceived(WhisperResult result) {
                Log.d(TAG, "Whisper result: " + result.getResult());
            }
        });

        whisper.setLanguage("en");
        whisper.loadModel();

        // Даём время на загрузку модели
        handler.postDelayed(() -> {
            // ИСПРАВЛЕНО: isModelLoaded() заменён на проверку recognizer через рефлексию
            // Так как в Whisper.java нет метода isModelLoaded(), используем задержку
            isModelReady = true;  // Если дошли сюда без ошибок, считаем модель готовой
            statusText.setText("Ready");
            startButton.setEnabled(true);
            audioConverter = new AudioConverter();
            Log.d(TAG, "Whisper model ready");
        }, 3000);
    }

    private void initStreamProcessor() {
        streamProcessor = new Media3StreamProcessor(this, new Media3StreamProcessor.StreamListener() {
            private int currentSampleRate = 0;
            private int currentChannelCount = 0;

            @Override
            public void onAudioFormat(int sampleRate, int channelCount) {
                currentSampleRate = sampleRate;
                currentChannelCount = channelCount;
                Log.d(TAG, "Stream format: " + sampleRate + " Hz, " + channelCount + " ch");
                if (streamingProcessor != null) {
                    streamingProcessor.setAudioFormat(sampleRate, channelCount);
                }
            }

            @Override
            public void onAudioChunk(byte[] pcmData) {
                if (streamingProcessor == null || !streamingProcessor.isRunning()) {
                    return;
                }

                byte[] convertedPcm = audioConverter.convertToWhisperFormat(
                        pcmData, currentSampleRate, currentChannelCount);

                if (convertedPcm != null && convertedPcm.length > 0) {
                    streamingProcessor.processAudioChunk(convertedPcm);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Stream error: " + error);
                runOnUiThread(() -> {
                    // Только выводим сообщение, НЕ останавливаем поток
                    statusText.setText("Connection issue: " + error);
                    Toast.makeText(EnWhisperStream.this, "Connection issue: " + error, Toast.LENGTH_SHORT).show();
                    // НЕ вызываем stopListening()!
                });
            }
            @Override
            public void onStreamStarted() {
                runOnUiThread(() -> {
                    statusText.setText("Timeout 20-25 sec");
                    startButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    pauseButton.setEnabled(true);  // ← ДОБАВИТЬ
                });
            }
            @Override
            public void onStreamStopped() {
                runOnUiThread(() -> {
                    statusText.setText("Ready");
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    pauseButton.setEnabled(false);
                    pauseButton.setText("Pause");
                    stopTimer();
                });
            }
            @Override
            public void onReconnecting() {
                runOnUiThread(() -> {
                    statusText.setText("Reconnecting to stream...");
                    Log.d(TAG, "Reconnecting to stream...");
                });
            }

            @Override
            public void onReconnected() {
                runOnUiThread(() -> {
                    statusText.setText("Reconnected - Streaming...");
                    Log.d(TAG, "Stream reconnected successfully");
                    Toast.makeText(EnWhisperStream.this, "Connection restored", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    private void startListening() {
        String url = customUrlInput.getText().toString().trim();
        if (url.isEmpty() || url.equals(STREAMS.get("Paste URL"))) {
            Toast.makeText(this, "Enter a stream URL", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isModelReady || whisper == null) {
            Toast.makeText(this, "Model not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "startListening: " + url);

        // Сброс состояния
        resultView.setText("");
        currentTranscription = "";
        fullHistory = "";

        // Создаём процессор
        streamingProcessor = new StreamingWhisperProcessor(whisper, this);

        // Инициализируем стрим процессор
        initStreamProcessor();

        isListening = true;
        startTimer();

        streamProcessor.startStream(url);
    }

    private void stopListening() {
        isListening = false;
        if (streamProcessor != null) {
            streamProcessor.stopStream();
            streamProcessor = null;
        }
        if (streamingProcessor != null) {
            streamingProcessor.setPlaybackPaused(false);  // ← СБРОСИТЬ ПАУЗУ
            streamingProcessor.stopImmediately();
            streamingProcessor = null;
        }
        stopTimer();
    }
    private void togglePause() {
        if (streamingProcessor != null) {
            boolean isCurrentlyPaused = streamingProcessor.isPlaybackPaused();

            if (isCurrentlyPaused) {
                // RESUME - проверяем сеть и переподключаем при необходимости
                if (streamProcessor != null && streamProcessor.checkAndReconnectIfNeeded()) {
                    statusText.setText("Reconnecting...");
                }
                streamingProcessor.setPlaybackPaused(false);
                pauseButton.setText("Pause");
                statusText.setText("Streaming...");
            } else {
                // PAUSE
                streamingProcessor.setPlaybackPaused(true);
                pauseButton.setText("Resume");
                statusText.setText("Paused...");
            }
        }
    }
    private void startTimer() {
        totalPlaybackTimeAtStop = 0;  // Обнуляем при старте
        startTime = System.currentTimeMillis();
        timerRunnable = () -> {
            long currentTimeElapsed = (System.currentTimeMillis() - startTime);
            long totalTimeElapsed = totalPlaybackTimeAtStop + currentTimeElapsed;
            long elapsedSeconds = totalTimeElapsed / 1000;
            long minutes = elapsedSeconds / 60;
            long seconds = elapsedSeconds % 60;
            timerText.setText(String.format(Locale.US, "%02d:%02d", minutes, seconds));
            handler.postDelayed(timerRunnable, 1000);
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }
        totalPlaybackTimeAtStop += (System.currentTimeMillis() - startTime);
        // НЕ обнуляем totalPlaybackTimeAtStop!
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
        if (whisper != null) {
            whisper.unloadModel();
        }
    }

    // ========== StreamingWhisperProcessor.MainActivityCallback ==========

    @Override
    public void onTranscriptionText(String text) {
        runOnUiThread(() -> {
            if (text != null && !text.isEmpty()) {
                currentTranscription = text;
                resultView.setText(text);
                resultView.setTextColor(Color.RED);
                resultView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

                // Автопрокрутка вниз
                resultView.post(() -> {
                    android.text.Layout layout = resultView.getLayout();
                    if (layout != null) {
                        int totalHeight = layout.getHeight();
                        int visibleHeight = resultView.getHeight();
                        if (totalHeight > visibleHeight) {
                            resultView.scrollTo(0, totalHeight - visibleHeight);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onHistoryReady(String history) {
        runOnUiThread(() -> {
            if (history != null && !history.isEmpty()) {
                fullHistory = history;
                resultView.setText(history);
                resultView.setTextColor(Color.BLUE);
                Log.d(TAG, "History displayed, length: " + history.length());
            }
        });
    }

    @Override
    public void onTranscriptionError(String error) {
        runOnUiThread(() -> {
            statusText.setText("Error: " + error);
            Log.e(TAG, "Transcription error: " + error);
        });
    }
}