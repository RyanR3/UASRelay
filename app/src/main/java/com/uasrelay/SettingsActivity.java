package com.uasrelay;

import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.uasrelay.rid.RidScanner;
import com.uasrelay.tak.TakCertEnroller;
import com.uasrelay.tak.TakChannels;
import com.uasrelay.tak.TakQr;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SettingsActivity — all setup, grouped into cards: drone name, video server, map
 * marker outputs (multicast / TAK Server with certificate enrollment), Remote ID, and
 * advanced video options. Everything is saved when the screen is left.
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText callsign, mtxHost, mtxPort, mtxSrtPort, mtxPath, mtxUser, mtxPass, bitrate, flyLeftPct;
    private EditText takHost, takEnrollPort, takStreamPort, takApiPort, takUser, takPass, ridSerial;
    private MaterialSwitch mtxTcp, mtxSrtLink, lowBandwidth, cropToFly, multicast, takEnabled, ridEnabled;
    private TextView enrollStatus, capability, channelStatus;
    private LinearLayout channelList;
    /** Carried through read(): the channel picks aren't form fields. */
    private boolean channelsChosen;
    private Set<String> channels = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View takFields, ridSerialLayout, flyLeftPctLayout, mtxSrtPortLayout, mtxSrtLinkHelp;
    private TextInputLayout mtxPortLayout;
    private MaterialButtonToggleGroup mtxProtocol;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        callsign = findViewById(R.id.callsign);
        mtxHost = findViewById(R.id.mtxHost);
        mtxPort = findViewById(R.id.mtxPort);
        mtxPortLayout = findViewById(R.id.mtxPortLayout);
        mtxSrtPort = findViewById(R.id.mtxSrtPort);
        mtxSrtPortLayout = findViewById(R.id.mtxSrtPortLayout);
        mtxSrtLink = findViewById(R.id.mtxSrtLink);
        mtxSrtLinkHelp = findViewById(R.id.mtxSrtLinkHelp);
        mtxProtocol = findViewById(R.id.mtxProtocol);
        mtxPath = findViewById(R.id.mtxPath);
        mtxUser = findViewById(R.id.mtxUser);
        mtxPass = findViewById(R.id.mtxPass);
        bitrate = findViewById(R.id.bitrate);
        lowBandwidth = findViewById(R.id.lowBandwidth);
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
        takApiPort = findViewById(R.id.takApiPort);
        channelList = findViewById(R.id.channelList);
        channelStatus = findViewById(R.id.channelStatus);
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
        mtxProtocol.addOnButtonCheckedListener((g, id, checked) -> { if (checked) showProtocol(); });
        showProtocol();

        findViewById(R.id.enroll).setOnClickListener(v -> enroll());
        findViewById(R.id.loadChannels).setOnClickListener(v -> loadChannels());
        if (channelsChosen) channelStatus.setText(channels.size() + (channels.size() == 1 ? " channel" : " channels") + " chosen");
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

    /** SRT needs its own port; the RTSP port stays, since viewers may still pull RTSP. */
    private void showProtocol() {
        boolean srt = isSrt();
        mtxSrtPortLayout.setVisibility(srt ? View.VISIBLE : View.GONE);
        mtxSrtLink.setVisibility(srt ? View.VISIBLE : View.GONE);
        mtxSrtLinkHelp.setVisibility(srt ? View.VISIBLE : View.GONE);
        mtxPortLayout.setHint(srt ? "RTSP" : "Port");
    }

    private boolean isSrt() { return mtxProtocol.getCheckedButtonId() == R.id.protoSrt; }

    /** Fetch this account's channels from the TAK Server and show them as checkboxes. */
    private void loadChannels() {
        Prefs p = read();
        File trust = new File(getFilesDir(), "tak_truststore.p12");
        File client = new File(getFilesDir(), "tak_clientcert.p12");
        if (p.takHost.isEmpty() || !trust.exists() || !client.exists()) {
            channelStatus.setText("Get a certificate first");
            return;
        }
        channelStatus.setText("Loading channels…");
        new Thread(() -> {
            try {
                List<TakChannels.Channel> list = new TakChannels(p.takHost, p.takApiPort,
                        trust.getAbsolutePath(), client.getAbsolutePath(), TakCertEnroller.P12_PASSWORD).list();
                runOnUiThread(() -> showChannels(list));
            } catch (Exception e) {
                runOnUiThread(() -> channelStatus.setText("Couldn't load channels: " + e.getMessage()));
            }
        }, "TakChannels").start();
    }

    private void showChannels(List<TakChannels.Channel> list) {
        if (isFinishing()) return;
        channelList.removeAllViews();
        if (list.isEmpty()) {
            channelStatus.setText("This account has no channels");
            return;
        }
        // Until channels are picked here, show (and keep) what the server has now.
        if (!channelsChosen) {
            channels.clear();
            for (TakChannels.Channel c : list) if (c.active) channels.add(c.name);
        }
        for (TakChannels.Channel c : list) {
            MaterialCheckBox box = new MaterialCheckBox(this);
            box.setText(c.description.isEmpty() ? c.name : c.name + " — " + c.description);
            box.setChecked(channels.contains(c.name));
            box.setOnCheckedChangeListener((b, on) -> {
                if (on) channels.add(c.name); else channels.remove(c.name);
                channelsChosen = true;
                read().save(this);
                showChannelCount(list.size());
                // Several taps in a row become one update to the server.
                handler.removeCallbacks(applyChannels);
                handler.postDelayed(applyChannels, 1500);
            });
            channelList.addView(box);
        }
        showChannelCount(list.size());
    }

    private void showChannelCount(int total) {
        String count = channels.size() + " of " + total + " channels on";
        channelStatus.setText(!channelsChosen ? count + " (as set on the server)"
                : RelayService.running ? count + " · sent to TAK Server" : count + " · sent when you start");
    }

    /** While live, hand the new set to the running service; otherwise it applies on Start. */
    private final Runnable applyChannels = () -> {
        if (RelayService.running) {
            startService(new Intent(this, RelayService.class).setAction(RelayService.ACTION_APPLY_CHANNELS));
        }
    };

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
        mtxSrtPort.setText(String.valueOf(p.mtxSrtPort));
        mtxProtocol.check(p.mtxSrt ? R.id.protoSrt : R.id.protoRtsp);
        mtxSrtLink.setChecked(p.mtxSrtLink);
        mtxPath.setText(p.mtxPath);
        mtxUser.setText(p.mtxUser);
        mtxPass.setText(p.mtxPass);
        bitrate.setText(String.valueOf(p.bitrateKbps));
        lowBandwidth.setChecked(p.lowBandwidth);
        mtxTcp.setChecked(p.mtxTcp);
        cropToFly.setChecked(p.cropToFly);
        flyLeftPct.setText(String.valueOf(p.flyLeftPct));
        multicast.setChecked(p.multicastEnabled);
        takEnabled.setChecked(p.takEnabled);
        takHost.setText(p.takHost);
        takEnrollPort.setText(String.valueOf(p.takEnrollPort));
        takStreamPort.setText(String.valueOf(p.takStreamPort));
        takApiPort.setText(String.valueOf(p.takApiPort));
        channelsChosen = p.takChannelsChosen;
        channels = new HashSet<>(p.takChannels);
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
        p.mtxSrt = isSrt();
        p.mtxSrtPort = parseInt(text(mtxSrtPort), d.mtxSrtPort);
        p.mtxSrtLink = mtxSrtLink.isChecked();
        p.mtxPath = text(mtxPath).isEmpty() ? d.mtxPath : text(mtxPath);
        p.mtxUser = text(mtxUser);
        p.mtxPass = mtxPass.getText().toString();
        p.bitrateKbps = parseInt(text(bitrate), d.bitrateKbps);
        p.lowBandwidth = lowBandwidth.isChecked();
        p.mtxTcp = mtxTcp.isChecked();
        p.cropToFly = cropToFly.isChecked();
        p.flyLeftPct = parseFloat(text(flyLeftPct), d.flyLeftPct);
        p.multicastEnabled = multicast.isChecked();
        p.takEnabled = takEnabled.isChecked();
        p.takHost = text(takHost);
        p.takEnrollPort = parseInt(text(takEnrollPort), d.takEnrollPort);
        p.takStreamPort = parseInt(text(takStreamPort), d.takStreamPort);
        p.takApiPort = parseInt(text(takApiPort), d.takApiPort);
        p.takChannelsChosen = channelsChosen;
        p.takChannels = new HashSet<>(channels);
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
