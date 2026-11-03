package com.uasrelay;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.uasrelay.rid.RidScanner;
import com.uasrelay.tak.TakCertEnroller;
import com.uasrelay.tak.TakQr;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;

/**
 * SettingsActivity — all setup, grouped into cards: drone name, video server, map
 * marker outputs (multicast / TAK Server with certificate enrollment), Remote ID, and
 * advanced video options. Everything is saved when the screen is left.
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText callsign, mtxHost, mtxPort, mtxPath, mtxUser, mtxPass, bitrate, flyLeftPct;
    private EditText takHost, takEnrollPort, takStreamPort, takUser, takPass, ridSerial;
    private MaterialSwitch mtxTcp, cropToFly, multicast, takEnabled, ridEnabled;
    private TextView enrollStatus, capability;
    private View takFields, ridSerialLayout, flyLeftPctLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        callsign = findViewById(R.id.callsign);
        mtxHost = findViewById(R.id.mtxHost);
        mtxPort = findViewById(R.id.mtxPort);
        mtxPath = findViewById(R.id.mtxPath);
        mtxUser = findViewById(R.id.mtxUser);
        mtxPass = findViewById(R.id.mtxPass);
        bitrate = findViewById(R.id.bitrate);
        mtxTcp = findViewById(R.id.mtxTcp);
        cropToFly = findViewById(R.id.cropToFly);
        flyLeftPct = findViewById(R.id.flyLeftPct);
        flyLeftPctLayout = findViewById(R.id.flyLeftPctLayout);
        multicast = findViewById(R.id.multicast);
        takEnabled = findViewById(R.id.takEnabled);
        takFields = findViewById(R.id.takFields);
        takHost = findViewById(R.id.takHost);
        takEnrollPort = findViewById(R.id.takEnrollPort);
        takStreamPort = findViewById(R.id.takStreamPort);
        takUser = findViewById(R.id.takUser);
        takPass = findViewById(R.id.takPass);
        enrollStatus = findViewById(R.id.enrollStatus);
        ridEnabled = findViewById(R.id.ridEnabled);
        ridSerial = findViewById(R.id.ridSerial);
        ridSerialLayout = findViewById(R.id.ridSerialLayout);
        capability = findViewById(R.id.capability);

        show(Prefs.load(this));
        showEnrolled();
        showCapability();

        // Only show the fields a switch actually uses.
        takEnabled.setOnCheckedChangeListener((b, on) -> takFields.setVisibility(on ? View.VISIBLE : View.GONE));
        ridEnabled.setOnCheckedChangeListener((b, on) -> ridSerialLayout.setVisibility(on ? View.VISIBLE : View.GONE));
        cropToFly.setOnCheckedChangeListener((b, on) -> flyLeftPctLayout.setVisibility(on ? View.VISIBLE : View.GONE));
        takFields.setVisibility(takEnabled.isChecked() ? View.VISIBLE : View.GONE);
        ridSerialLayout.setVisibility(ridEnabled.isChecked() ? View.VISIBLE : View.GONE);
        flyLeftPctLayout.setVisibility(cropToFly.isChecked() ? View.VISIBLE : View.GONE);

        findViewById(R.id.enroll).setOnClickListener(v -> enroll());
        findViewById(R.id.scanQr).setOnClickListener(v -> qrScan.launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the TAK Server QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)));
    }

    /**
     * A scanned TAK Server QR fills in the server. The ATAK enrollment form also carries
     * username + password, so it gets the certificate straight away; the iTAK form only
     * names the server and port, so the user types their login.
     */
    private final ActivityResultLauncher<ScanOptions> qrScan = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() == null) return; // cancelled
                TakQr qr = TakQr.parse(result.getContents());
                if (qr == null) {
                    Toast.makeText(this, "That isn't a TAK Server QR code", Toast.LENGTH_LONG).show();
                    return;
                }
                takEnabled.setChecked(true);
                takHost.setText(qr.host);
                if (qr.port > 0) takStreamPort.setText(String.valueOf(qr.port));
                if (!qr.username.isEmpty()) takUser.setText(qr.username);
                if (qr.hasCredentials()) {
                    takPass.setText(qr.password);
                    enroll();
                } else {
                    enrollStatus.setText("Server filled in. Enter your password, then tap Get certificate.");
                    (qr.username.isEmpty() ? takUser : takPass).requestFocus();
                }
            });

    @Override
    protected void onPause() {
        super.onPause();
        read().save(this);
    }

    private void enroll() {
        Prefs p = read();
        String pass = takPass.getText().toString();
        if (TextUtils.isEmpty(p.takHost) || TextUtils.isEmpty(p.takUser) || pass.isEmpty()) {
            Toast.makeText(this, "Enter the TAK Server address, username and password", Toast.LENGTH_SHORT).show();
            return;
        }
        p.save(this);
        enrollStatus.setText("Getting certificate…");
        String uid = Prefs.deviceUid(this);
        // Enrollment does blocking HTTPS — must not run on the UI thread.
        new Thread(() -> TakCertEnroller.enroll(p.takHost, p.takEnrollPort, p.takUser, pass, uid,
                getFilesDir(), new TakCertEnroller.EnrollmentCallback() {
                    @Override public void onSuccess(String trustPath, String clientPath) {
                        runOnUiThread(() -> enrollStatus.setText("Certificate saved ✓"));
                    }
                    @Override public void onError(String error) {
                        runOnUiThread(() -> enrollStatus.setText("Couldn't get certificate: " + error));
                    }
                })).start();
    }

    private void showEnrolled() {
        boolean enrolled = new File(getFilesDir(), "tak_truststore.p12").exists()
                && new File(getFilesDir(), "tak_clientcert.p12").exists();
        enrollStatus.setText(enrolled ? "Certificate saved ✓" : "No certificate yet");
    }

    private void showCapability() {
        RidScanner probe = new RidScanner(this, NOOP_LISTENER);
        capability.setText(probe.isLongRangeRidSupported()
                ? "This phone can hear Remote ID over Bluetooth and Wi-Fi."
                : "This phone can hear Remote ID over Wi-Fi only.");
    }

    private void show(Prefs p) {
        callsign.setText(p.callsign);
        mtxHost.setText(p.mtxHost);
        mtxPort.setText(String.valueOf(p.mtxPort));
        mtxPath.setText(p.mtxPath);
        mtxUser.setText(p.mtxUser);
        mtxPass.setText(p.mtxPass);
        bitrate.setText(String.valueOf(p.bitrateKbps));
        mtxTcp.setChecked(p.mtxTcp);
        cropToFly.setChecked(p.cropToFly);
        flyLeftPct.setText(String.valueOf(p.flyLeftPct));
        multicast.setChecked(p.multicastEnabled);
        takEnabled.setChecked(p.takEnabled);
        takHost.setText(p.takHost);
        takEnrollPort.setText(String.valueOf(p.takEnrollPort));
        takStreamPort.setText(String.valueOf(p.takStreamPort));
        takUser.setText(p.takUser);
        ridEnabled.setChecked(p.ridEnabled);
        ridSerial.setText(p.ridSerial);
    }

    private Prefs read() {
        Prefs d = new Prefs();
        Prefs p = new Prefs();
        p.callsign = text(callsign).isEmpty() ? d.callsign : text(callsign);
        p.mtxHost = text(mtxHost);
        p.mtxPort = parseInt(text(mtxPort), d.mtxPort);
        p.mtxPath = text(mtxPath).isEmpty() ? d.mtxPath : text(mtxPath);
        p.mtxUser = text(mtxUser);
        p.mtxPass = mtxPass.getText().toString();
        p.bitrateKbps = parseInt(text(bitrate), d.bitrateKbps);
        p.mtxTcp = mtxTcp.isChecked();
        p.cropToFly = cropToFly.isChecked();
        p.flyLeftPct = parseFloat(text(flyLeftPct), d.flyLeftPct);
        p.multicastEnabled = multicast.isChecked();
        p.takEnabled = takEnabled.isChecked();
        p.takHost = text(takHost);
        p.takEnrollPort = parseInt(text(takEnrollPort), d.takEnrollPort);
        p.takStreamPort = parseInt(text(takStreamPort), d.takStreamPort);
        p.takUser = text(takUser);
        p.ridEnabled = ridEnabled.isChecked();
        p.ridSerial = text(ridSerial);
        return p;
    }

    private static String text(EditText e) { return e.getText().toString().trim(); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }

    private static final RidScanner.RidListener NOOP_LISTENER = new RidScanner.RidListener() {
        @Override public void onDrone(com.uasrelay.rid.RidMessage m) {}
        @Override public void onStatus(String message, boolean isError) {}
    };
}
