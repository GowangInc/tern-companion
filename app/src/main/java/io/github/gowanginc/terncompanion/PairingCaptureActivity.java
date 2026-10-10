package io.github.gowanginc.terncompanion;

import android.content.pm.PackageManager;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ToggleButton;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * Pairing capture activity: decoding, camera permission handling and result
 * encoding stay entirely in the library CaptureActivity/CaptureManager; this
 * subclass only swaps in the app's linework capture layout. Orientation stays
 * unlocked (manifest fullSensor plus ScanOptions.setOrientationLocked(false)),
 * so a phone held portrait stays portrait and rotates naturally.
 */
public class PairingCaptureActivity extends CaptureActivity {
    private DecoratedBarcodeView barcodeScannerView;
    private ToggleButton torch;
    private boolean torchSyncing;

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.tern_pairing_capture);
        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner);

        findViewById(R.id.tern_cancel).setOnClickListener(v -> finish());

        torch = findViewById(R.id.tern_torch);
        torch.setOnCheckedChangeListener(this::onTorchToggled);
        barcodeScannerView.setTorchListener(new TorchSync());

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            barcodeScannerView.getBarcodeView().addStateListener(new RevealTorchWhenReady());
        }
        return barcodeScannerView;
    }

    private void onTorchToggled(CompoundButton button, boolean checked) {
        if (torchSyncing) return;
        try {
            if (checked) {
                barcodeScannerView.setTorchOn();
            } else {
                barcodeScannerView.setTorchOff();
            }
        } catch (RuntimeException e) {
            // Devices that accept the flash feature flag but reject torch calls.
            torchSyncing = true;
            button.setChecked(false);
            torchSyncing = false;
        }
    }

    private void syncTorch(boolean on) {
        if (torch == null) return;
        torchSyncing = true;
        torch.setChecked(on);
        torchSyncing = false;
    }

    private final class TorchSync implements DecoratedBarcodeView.TorchListener {
        @Override
        public void onTorchOn() {
            syncTorch(true);
        }

        @Override
        public void onTorchOff() {
            syncTorch(false);
        }
    }

    /** Optional light control: only offered once a flash-capable camera is live. */
    private final class RevealTorchWhenReady implements CameraPreview.StateListener {
        @Override
        public void previewSized() {
            runOnUiThread(() -> torch.setVisibility(View.VISIBLE));
        }

        @Override
        public void previewStarted() {
        }

        @Override
        public void previewStopped() {
        }

        @Override
        public void cameraError(Exception error) {
            runOnUiThread(() -> torch.setVisibility(View.GONE));
        }

        @Override
        public void cameraClosed() {
        }
    }
}
