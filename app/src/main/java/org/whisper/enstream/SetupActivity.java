package org.whisper.enstream;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SetupActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> installLauncher;
    private ProgressBar progressBar;
    private TextView extractedFileTV;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        progressBar = findViewById(R.id.progress_bar);
        extractedFileTV = findViewById(R.id.extracted_file);
        startButton = findViewById(R.id.button_start);

        File modelFolder = getExternalFilesDir(null);

        if (isModelInstalled(modelFolder)) {
            startMainActivity();
        }

        installLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getData() != null && result.getData().getData() != null) {
                        extractZip(modelFolder, result.getData().getData());
                    }
                });
    }

    private boolean isModelInstalled(File folder) {
        if (folder == null || !folder.exists()) return false;

        File[] files = folder.listFiles();
        if (files == null) return false;

        int fileCount = 0;
        for (File file : files) {
            if (file.isFile()) {
                fileCount++;
            }
        }
        return fileCount >= 6;
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, EnWhisperStream.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    public void downloadModel(View v) {
        Toast.makeText(this, "Open browser to download model", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://huggingface.co/DocWolle/whisperOnnx/blob/main/whisper_small_int8.zip")));
    }

    public void installModel(View v) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");
        installLauncher.launch(intent);
    }

    private void extractZip(File targetDir, Uri zipFile) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        new Thread(() -> {
            try (InputStream src = getContentResolver().openInputStream(zipFile);
                 ZipInputStream zipInputStream = new ZipInputStream(src)) {

                ZipEntry zipEntry;
                byte[] buffer = new byte[4096];

                while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                    File extractedFile = new File(targetDir, zipEntry.getName());
                    
                    runOnUiThread(() -> {
                        extractedFileTV.setVisibility(View.VISIBLE);
                        extractedFileTV.setText(extractedFile.getName());
                    });

                    try (OutputStream outputStream = Files.newOutputStream(extractedFile.toPath())) {
                        int length;
                        while ((length = zipInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, length);
                        }
                    }
                }

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    extractedFileTV.setVisibility(View.GONE);
                    startButton.setVisibility(View.VISIBLE);
                });

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void startMain(View v) {
        startMainActivity();
    }
}