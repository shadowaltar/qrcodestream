package com.mu.qrcodestreamclient;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.mu.qrcodestreamclient.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;
    private static final long TICK_INTERVAL_MS = 1000L;

    /** Mirrors {@code R.array.resolution_labels}: width/height, or 0/0 for the device default. */
    private static final int[][] TARGET_RESOLUTIONS = {
        {0, 0}, {640, 480}, {1280, 720}, {1920, 1080}
    };

    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String pendingFileName;
    private boolean tickerRunning;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updatePathPreview();
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.startButton.setOnClickListener(v -> onStartClicked());

        binding.queueSlider.setValue(CaptureActivity.DEFAULT_DECODE_QUEUE_MAX);
        updateQueueLabel((int) binding.queueSlider.getValue());
        binding.queueSlider.addOnChangeListener(
                (slider, value, fromUser) -> updateQueueLabel((int) value));

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.resolution_labels, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.resolutionSpinner.setAdapter(adapter);
        binding.resolutionSpinner.setSelection(2);
    }

    private void updateQueueLabel(int frames) {
        binding.queueLabel.setText(getString(R.string.queue_label, frames));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!tickerRunning) {
            tickerRunning = true;
            ticker.run();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        tickerRunning = false;
        handler.removeCallbacks(ticker);
    }

    private void updatePathPreview() {
        String fileName = utcTimestamp() + ".capture";
        binding.pathText.setText(CaptureWriter.downloadDisplayPath(fileName));
    }

    private String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private void onStartClicked() {
        pendingFileName = utcTimestamp() + ".capture";
        List<String> needed = new ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (needed.isEmpty()) {
            launchCapture();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void launchCapture() {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.putExtra(CaptureActivity.EXTRA_FILE_NAME, pendingFileName);
        intent.putExtra(CaptureActivity.EXTRA_QUEUE_MAX, (int) binding.queueSlider.getValue());
        int position = binding.resolutionSpinner.getSelectedItemPosition();
        int[] size = TARGET_RESOLUTIONS[Math.max(0, Math.min(TARGET_RESOLUTIONS.length - 1, position))];
        intent.putExtra(CaptureActivity.EXTRA_TARGET_WIDTH, size[0]);
        intent.putExtra(CaptureActivity.EXTRA_TARGET_HEIGHT, size[1]);
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            launchCapture();
        } else {
            showPermissionDeniedDialog();
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_denied_title)
                .setMessage(R.string.permission_denied_message)
                .setPositiveButton(R.string.retry, (d, w) -> onStartClicked())
                .setNegativeButton(R.string.open_settings, (d, w) -> openAppSettings())
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }
}
