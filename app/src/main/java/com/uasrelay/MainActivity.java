package com.uasrelay;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — the home screen: one big Start/Stop button and three status lines
 * (video, map marker, position), each with a colored dot. Setup lives in
 * {@link SettingsActivity}; Start asks Android for screen-capture consent and hands the
 * token to {@link RelayService}.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;

    private MaterialButton startStop;
    private TextView subtitle, hint;
    private Row video, cot, position;

    /** One status line: dot + label + value. */
    private static final class Row {
        final View dot;
        final TextView value;

        Row(View root, String label) {
            dot = root.findViewById(R.id.dot);
            value = root.findViewById(R.id.value);
            ((TextView) root.findViewById(R.id.label)).setText(label);
        }
    }

    /** Screen-capture consent → start the service with the one-shot token. */
    private final ActivityResultLauncher<Intent> captureConsent = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    toast("Screen sharing wasn't allowed");
                    return;
                }
                Intent i = new Intent(this, RelayService.class)
                        .putExtra(RelayService.EXTRA_RESULT_CODE, result.getResultCode())
                        .putExtra(RelayService.EXTRA_RESULT_DATA, result.getData());
                ContextCompat.startForegroundService(this, i);
                showRunning(true);
            });

    /** Live state pushed from RelayService. */
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Also check the service's own flag: a status broadcast still in flight when the
            // service stops must not flip the button back to STOP.
            boolean running = intent.getBooleanExtra(RelayService.EXTRA_RUNNING, false)
                    && RelayService.running;
            showRunning(running);
            if (!running) return;
            set(video, intent.getIntExtra(RelayService.EXTRA_VIDEO_STATE, RelayService.STATE_OFF),
                    intent.getStringExtra(RelayService.EXTRA_VIDEO));
            set(cot, intent.getIntExtra(RelayService.EXTRA_COT_STATE, RelayService.STATE_OFF),
                    intent.getStringExtra(RelayService.EXTRA_COT));
            set(position, intent.getIntExtra(RelayService.EXTRA_POSITION_STATE, RelayService.STATE_OFF),
                    intent.getStringExtra(RelayService.EXTRA_POSITION));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startStop = findViewById(R.id.startStop);
        subtitle = findViewById(R.id.subtitle);
        hint = findViewById(R.id.hint);
        video = new Row(findViewById(R.id.rowVideo), "Video");
        cot = new Row(findViewById(R.id.rowCot), "Map marker");
        position = new Row(findViewById(R.id.rowPosition), "Position");

        startStop.setOnClickListener(v -> {
            if (RelayService.running) stopService(new Intent(this, RelayService.class));
            else start();
        });
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        requestPermissionsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, statusReceiver,
                new IntentFilter(RelayService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        Prefs p = Prefs.load(this);
        subtitle.setText(p.mtxHost.isEmpty() ? "Not set up yet" : p.callsign);
        showRunning(RelayService.running);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statusReceiver); } catch (IllegalArgumentException ignored) {}
    }

    private void start() {
        Prefs p = Prefs.load(this);
        if (p.mtxHost.isEmpty()) {
            toast("Set your video server first");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (!p.multicastEnabled && !p.takEnabled) {
            toast("Turn on a map marker output in Settings");
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        // Android 14+: ask for the whole screen only, so the consent dialog skips the
        // "single app" picker. Fly runs fullscreen, so the crop still lines up.
        captureConsent.launch(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent());
    }

    private void showRunning(boolean running) {
        startStop.setText(running ? "STOP" : "START");
        startStop.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, running ? R.color.stop : R.color.go)));
        hint.setText(running
                ? "Live. Switch to DJI Fly and keep it on screen —\nyou can stop from the notification."
                : "Tap Start, then switch to DJI Fly.\nThe whole screen is shared while live.");
        if (!running) {
            set(video, RelayService.STATE_OFF, "Off");
            set(cot, RelayService.STATE_OFF, "Off");
            set(position, RelayService.STATE_OFF, "Off");
        }
    }

    private void set(Row row, int state, String text) {
        int color;
        switch (state) {
            case RelayService.STATE_OK: color = R.color.state_ok; break;
            case RelayService.STATE_PENDING: color = R.color.state_pending; break;
            case RelayService.STATE_ERROR: color = R.color.state_error; break;
            default: color = R.color.state_off;
        }
        row.dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
        row.value.setText(text);
    }

    private void requestPermissionsIfNeeded() {
        List<String> need = new ArrayList<>();
        add(need, Manifest.permission.ACCESS_FINE_LOCATION);
        add(need, Manifest.permission.BLUETOOTH_SCAN);
        add(need, Manifest.permission.BLUETOOTH_CONNECT);
        add(need, Manifest.permission.NEARBY_WIFI_DEVICES);
        add(need, Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        }
    }

    private void add(List<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
