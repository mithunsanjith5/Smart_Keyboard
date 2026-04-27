package com.example.systemkeyboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private TextView downloadStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        Button openKeyboardSettings = findViewById(R.id.open_keyboard_settings);
        Button chooseKeyboard = findViewById(R.id.choose_keyboard);
        Button openSettings = findViewById(R.id.open_settings);
        Button downloadModel = findViewById(R.id.download_model);
        downloadStatus = findViewById(R.id.download_status);

        updateDownloadStatus(getString(R.string.download_model_ready));

        openKeyboardSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        });

        chooseKeyboard.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
        });

        openSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        downloadModel.setOnClickListener(v -> downloadTinyLlamaModel());
    }

    @Override
    protected void onDestroy() {
        executorService.shutdownNow();
        super.onDestroy();
    }

    private void downloadTinyLlamaModel() {
        File modelFile = getModelFile();
        if (modelFile.exists() && modelFile.length() > 0) {
            saveModelPath(modelFile);
            updateDownloadStatus(getString(R.string.download_model_exists));
            return;
        }

        updateDownloadStatus(getString(R.string.download_model_starting));
        executorService.execute(() -> {
            HttpURLConnection connection = null;
            try {
                File parentDir = modelFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                URL url = new URL(getString(R.string.tiny_llm_download_url));
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.connect();

                if (connection.getResponseCode() >= 400) {
                    throw new IOException("HTTP " + connection.getResponseCode());
                }

                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(modelFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0L;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        long finalTotalBytes = totalBytes;
                        runOnUiThread(() -> updateDownloadStatus(getString(R.string.download_model_downloading) + " " + finalTotalBytes + " bytes"));
                    }
                    outputStream.flush();
                }

                saveModelPath(modelFile);
                runOnUiThread(() -> updateDownloadStatus(getString(R.string.download_model_finished) + "\n" + modelFile.getAbsolutePath()));
            } catch (Exception exception) {
                if (modelFile.exists()) {
                    // Best effort cleanup if the download was partial.
                    modelFile.delete();
                }
                runOnUiThread(() -> updateDownloadStatus(getString(R.string.download_model_failed) + "\n" + exception.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private File getModelFile() {
        File baseDir = getExternalFilesDir(null);
        if (baseDir == null) {
            // Fallback for devices where external app storage is temporarily unavailable.
            baseDir = getFilesDir();
        }
        File modelDir = new File(baseDir, "models");
        return new File(modelDir, getString(R.string.tiny_llm_file_name));
    }

    private void saveModelPath(File modelFile) {
        prefs.edit().putString("tiny_llm_model_path", modelFile.getAbsolutePath()).apply();
    }

    private void updateDownloadStatus(String message) {
        if (downloadStatus != null) {
            downloadStatus.setText(message);
        }
    }
}
