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

/** Halaman login PIN layar penuh (pengganti popup dialog).
 *  Dipakai MainActivity & SettingsActivity saat PIN aktif dan grace habis:
 *  hasil sukses/keluar disampaikan via setResult agar pemanggil bisa
 *  lanjut (catatPinDibuka) atau finish. Tombol Back = Keluar (finish),
 *  pemanggil yang dibatalkan ikut finish sehingga kunci tak bisa dilewat. */
public class PinActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText pinInput;
    private TextView pinError;
    private Button bukaBtn;
    private volatile boolean sedangPeriksa = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_TasirinVaultwardenHost);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        SharedPreferences sp = getSharedPreferences(ServerService.PREFS, MODE_PRIVATE);
        if (!TgBackup.amanBoolean(sp, PinGate.KEY_PIN_ON, false)
                || PinGate.dalamGraceBersama()) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        String pinHash = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
        if (pinHash == null || pinHash.isEmpty()) {
            // Fail-closed: hash hilang/rusak jangan dimatikan & jangan RESULT_OK.
            // Akses ditolak; user wajib setup ulang PIN dari pengaturan.
            // (View belum di-inflate di titik ini, jadi hanya log + tolak.)
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
        // Cegah NPE bila layout tak memuat semua view yang dibutuhkan.
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

    @Override
    public void onBackPressed() {
        batalKeluar();
    }

    private void batalKeluar() {
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
        sedangPeriksa = true;
        bukaBtn.setEnabled(false);
        tampilInfo(getString(R.string.pin_memeriksa));
        // Residu sadar: String PIN tak bisa di-wipe (immutable) dan singgah di heap
        // selama PBKDF2. Diterima: umur pendek (thread selesai → eligible GC),
        // layar FLAG_SECURE anti-screenshot, PIN tersimpan selalu PBKDF2+salt.
        // Editable sumber langsung dibersihkan di bawah agar tak ganda.
        final String entered = pinInput.getText() == null
                ? "" : pinInput.getText().toString();
        bersihkanInput();
        final android.content.Context appCtx = getApplicationContext();
        // Pool tugas lama bersama: verifikasi PBKDF2 30rb iterasi itu CPU-bound
        // detik di STB 1 GB (bukan thread baru tiap ketukan). Hasil tetap
        // kembali via ui.post di bawah.
        Util.jalankanLama(() -> {
            SharedPreferences sp = appCtx.getSharedPreferences(
                    ServerService.PREFS, MODE_PRIVATE);
            String segar = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
            boolean cocok = !segar.isEmpty()
                    && PinCrypto.verify(segar, entered);
            PinGate.catatHasil(appCtx, cocok, System.currentTimeMillis());
            if (cocok && PinCrypto.perluUpgradeHash(segar)) {
                String kini = TgBackup.amanString(sp, PinGate.KEY_PIN_HASH, "");
                if (segar.equals(kini)) {
                    try {
                        sp.edit().putString(PinGate.KEY_PIN_HASH,
                                PinCrypto.hash(entered)).apply();
                    } catch (Exception ignored) {
                    }
                }
            }
            final boolean hasil = cocok;
            ui.post(() -> {
                sedangPeriksa = false;
                bukaBtn.setEnabled(true);
                bersihkanInput();
                if (hasil) {
                    // Cegah race: activity sudah finish/destroy jangan buka kunci.
                    if (isFinishing() || isDestroyed()) return;
                    PinGate.bukaKunciBersama();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    tampilGalat(getString(R.string.pin_salah));
                }
            });
        });
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

    /** Menit lockout brute-force (ceiling). Murni agar bisa unit test. */
    static long menitSisaKunci(long sisaMs) {
        long menit = (sisaMs + 59999) / 60000;
        return menit < 1 ? 1 : menit;
    }

    /** Teks lockout Inggris (fallback + dikunci test). Untuk UI pakai
     *  {@link #teksSisaKunci(android.content.Context, long)} agar ikut locale. */
    static String teksSisaKunci(long sisaMs) {
        long menit = menitSisaKunci(sisaMs);
        return menit == 1 ? "Locked, try again in 1 minute."
                : "Locked, try again in " + menit + " minutes.";
    }

    /** Teks lockout ikut locale perangkat (Indonesia bila locale ID). */
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
