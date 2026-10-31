package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class PinActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText pinInput;
    private TextView pinError;
    private Button bukaBtn;
    private volatile boolean sedangPeriksa = false;
    private boolean pemeriksaGraceJalan = false;

    private final Runnable pemeriksaGrace = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                pemeriksaGraceJalan = false;
                return;
            }
            if (lolosGrace()) {
                pemeriksaGraceJalan = false;
                return;
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (lolosGrace()) {
            return;
        }
        String pinHash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        if (pinHash == null || pinHash.isEmpty()) {

            ServerService.catatLog("[app] PIN rusak: butuh setup ulang, akses ditolak.");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        setContentView(R.layout.activity_pin);
        pinInput = findViewById(R.id.pinInput);
        pinError = findViewById(R.id.pinError);
        bukaBtn = findViewById(R.id.pinBuka);
        Button keluarBtn = findViewById(R.id.pinKeluar);

        if (pinInput == null || pinError == null || bukaBtn == null || keluarBtn == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        pinInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pinInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (enter) {
                cobaBuka();
                return true;
            }
            return false;
        });
        bukaBtn.setOnClickListener(v -> cobaBuka());
        keluarBtn.setOnClickListener(v -> batalKeluar());
    }

    private boolean lolosGrace() {
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false)
                || PinGate.dalamGraceBersama()) {
            setResult(RESULT_OK);
            finish();
            return true;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!pemeriksaGraceJalan) {
            pemeriksaGraceJalan = true;
            ui.postDelayed(pemeriksaGrace, 500);
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(pemeriksaGrace);
        pemeriksaGraceJalan = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        batalKeluar();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            ui.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }
    }

    private void batalKeluar() {
        if (sedangPeriksa) {
            return;
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    private void cobaBuka() {
        if (sedangPeriksa) {
            return;
        }
        long sisa = PinGate.sisaKunciMs(this, System.currentTimeMillis());
        if (sisa > 0) {
            tampilGalat(teksSisaKunci(this, sisa));
            return;
        }
        if (pinInput.getText() == null || pinInput.getText().length() < 4) {
            tampilGalat(getString(R.string.pin_min));
            return;
        }
        sedangPeriksa = true;
        bukaBtn.setEnabled(false);
        tampilInfo(getString(R.string.pin_memeriksa));

        final String entered = pinInput.getText() == null
                ? "" : pinInput.getText().toString();
        bersihkanInput();
        final android.content.Context appCtx = getApplicationContext();

        boolean masuk = Util.jalankanPin(() -> {
            SharedPreferences sp = appCtx.getSharedPreferences(
                    ServerService.PREFS, MODE_PRIVATE);
            boolean cocok = false;
            boolean terkunci = false;
            long sisaKalah = 0;
            String segar = "";
            try {
                synchronized (PinGate.KUNCI_PERCOBAAN) {
                    // Hash dibaca DI DALAM kunci bersama (Audit 849):
                    // membaca sebelum masuk kunci membuat verifikasi bisa
                    // memakai hash lama tepat sesudah PIN diganti jalur
                    // lain — pola yang sudah ditutup jalur bot (Audit 809).
                    segar = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                    // Audit 791: cek ulang otoritatif di dalam kunci bersama
                    // dengan jalur bot — kunci yang terpasang jalur lain
                    // sejak cek awal membatalkan percobaan tanpa verifikasi.
                    long sisa2 = PinGate.sisaKunciMs(appCtx,
                            System.currentTimeMillis());
                    if (sisa2 > 0) {
                        terkunci = true;
                        sisaKalah = sisa2;
                    } else {
                        cocok = !segar.isEmpty()
                                && PinCrypto.verify(segar, entered);
                        PinGate.catatHasil(appCtx, cocok,
                                System.currentTimeMillis());
                    }
                }
                if (cocok) {
                    PinGate.bukaKunciBersama();
                }
                if (cocok && PinCrypto.perluUpgradeHash(segar)) {
                    try {
                        String hashBaru = PinCrypto.hash(entered);
                        synchronized (PinGate.KUNCI_PERCOBAAN) {
                            String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                            if (segar.equals(kini)) {
                                sp.edit().putString(PinGate.KEY_PIN_HASH, hashBaru).apply();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                final boolean hasil = cocok;
                final boolean kalah = terkunci;
                final long sisaFinal = sisaKalah;
                ui.post(() -> {
                    sedangPeriksa = false;

                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    bukaBtn.setEnabled(true);
                    bersihkanInput();
                    if (hasil) {
                        setResult(RESULT_OK);
                        finish();
                    } else if (kalah) {
                        tampilGalat(teksSisaKunci(this, sisaFinal));
                    } else {
                        tampilGalat(getString(R.string.pin_salah));
                    }
                });
            }
        });
        if (!masuk) {

            sedangPeriksa = false;
            bukaBtn.setEnabled(true);
            tampilGalat(getString(R.string.pin_sibuk));
        }
    }

    private void tampilGalat(String pesan) {
        pinError.setText(pesan);
        pinError.setTextColor(getResources().getColor(R.color.log_error));
    }

    private void tampilInfo(String pesan) {
        pinError.setText(pesan);
        pinError.setTextColor(getResources().getColor(R.color.text_secondary));
    }

    private void bersihkanInput() {
        try {
            if (pinInput.getText() != null) {
                pinInput.getText().clear();
            }
        } catch (Exception ignored) {
        }
    }

    static long menitSisaKunci(long sisaMs) {
        long menit = (sisaMs + 59999) / 60000;
        return menit < 1 ? 1 : menit;
    }

    static String teksSisaKunci(long sisaMs) {
        long menit = menitSisaKunci(sisaMs);
        return menit == 1 ? "Locked, try again in 1 minute."
                : "Locked, try again in " + menit + " minutes.";
    }

    static String teksSisaKunci(android.content.Context ctx, long sisaMs) {
        long menit = menitSisaKunci(sisaMs);
        try {
            int jumlah = menit > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) menit;
            return ctx.getResources().getQuantityString(R.plurals.pin_lockout, jumlah, menit);
        } catch (Exception e) {
            return teksSisaKunci(sisaMs);
        }
    }
}
