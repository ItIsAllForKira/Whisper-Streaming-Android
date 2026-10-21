package org.whisper.enstream.audio;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioConverter {
    private static final String TAG = "AudioConverter";

    // Целевой формат для Whisper (16kHz, mono)
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1;

    public byte[] convertToWhisperFormat(byte[] pcmData, int originalSampleRate, int originalChannels) {
        if (pcmData == null || pcmData.length == 0) {
            return null;
        }

        // 1. Конвертируем byte[] в float[]
        float[] originalSamples = bytesToFloat(pcmData, originalChannels);

        // 2. Если нужно, конвертируем стерео в моно
        float[] monoSamples;
        if (originalChannels == 2) {
            monoSamples = stereoToMono(originalSamples);
            Log.d(TAG, "Converted stereo to mono: " + originalSamples.length + " -> " + monoSamples.length + " samples");
        } else {
            monoSamples = originalSamples;
        }

        // 3. Ресемплинг (изменение частоты дискретизации)
        float[] resampledSamples;
        if (originalSampleRate != TARGET_SAMPLE_RATE) {
            resampledSamples = resample(monoSamples, originalSampleRate, TARGET_SAMPLE_RATE);
            Log.d(TAG, "Resampled: " + originalSampleRate + "Hz -> " + TARGET_SAMPLE_RATE +
                    "Hz, " + monoSamples.length + " -> " + resampledSamples.length + " samples");
        } else {
            resampledSamples = monoSamples;
        }

        // 4. Конвертируем float[] обратно в byte[]
        return floatToBytes(resampledSamples);
    }

    private float[] stereoToMono(float[] stereo) {
        int monoLength = stereo.length / 2;
        float[] mono = new float[monoLength];

        for (int i = 0; i < monoLength; i++) {
            mono[i] = (stereo[i * 2] + stereo[i * 2 + 1]) / 2.0f;
        }

        return mono;
    }

    private float[] resample(float[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            return input;
        }

        float ratio = (float) outputRate / inputRate;
        int outputLength = (int) (input.length * ratio);
        float[] output = new float[outputLength];

        for (int i = 0; i < outputLength; i++) {
            float srcIndex = i / ratio;
            int srcIndexInt = (int) srcIndex;
            float frac = srcIndex - srcIndexInt;

            if (srcIndexInt + 1 < input.length) {
                output[i] = input[srcIndexInt] * (1 - frac) + input[srcIndexInt + 1] * frac;
            } else if (srcIndexInt < input.length) {
                output[i] = input[srcIndexInt];
            } else {
                output[i] = 0;
            }
        }

        return output;
    }

    private float[] bytesToFloat(byte[] audioBytes, int channels) {
        int numSamples = audioBytes.length / 2;
        float[] samples = new float[numSamples];

        ByteBuffer buffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            samples[i] = buffer.getShort() / 32768.0f;
        }

        return samples;
    }

    private byte[] floatToBytes(float[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        for (float sample : samples) {
            short shortSample = (short) (sample * 32767);
            buffer.putShort(shortSample);
        }

        return bytes;
    }

    public float[] bytesToFloatArray(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length < 2) {
            return new float[0];
        }
        return bytesToFloat(audioBytes, 1);
    }
}