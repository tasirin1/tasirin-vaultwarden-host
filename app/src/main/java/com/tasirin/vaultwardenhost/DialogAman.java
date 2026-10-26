package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.Dialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Satu pintu menampilkan dialog milik Activity: dialog dilacak lalu ditutup
 *  di onDestroy. Manifest sengaja tanpa configChanges, jadi setiap rotasi
 *  membongkar Activity; dialog yang masih terbuka saat itu membocorkan window
 *  (WindowLeaked di log) dan isinya hilang tanpa jejak. */
final class DialogAman {

    private DialogAman() {
    }

    private static final class Pasangan {
        final WeakReference<Activity> activity;
        final Dialog dialog;

        Pasangan(Activity activity, Dialog dialog) {
            this.activity = new WeakReference<>(activity);
            this.dialog = dialog;
        }
    }

    private static final List<Pasangan> DAFTAR = new ArrayList<>();

    /** Tampilkan dialog dan lacak agar ditutup saat Activity dibongkar.
     *  Dialog tidak ditampilkan bila Activity sedang selesai/dibongkar
     *  (window-nya sudah tidak sah). */
    static <T extends Dialog> T tampil(Activity a, T d) {
        if (a == null || d == null) {
            return d;
        }
        if (a.isFinishing() || a.isDestroyed()) {
            return d;
        }
        synchronized (DAFTAR) {
            sapu();
            DAFTAR.add(new Pasangan(a, d));
        }
        d.show();
        return d;
    }

    /** Tutup semua dialog milik Activity ini (panggil dari onDestroy). */
    static void tutup(Activity a) {
        if (a == null) {
            return;
        }
        synchronized (DAFTAR) {
            for (int i = DAFTAR.size() - 1; i >= 0; i--) {
                Pasangan p = DAFTAR.get(i);
                Activity pemilik = p.activity.get();
                if (pemilik == null) {
                    DAFTAR.remove(i);
                } else if (pemilik == a) {
                    DAFTAR.remove(i);
                    tutupDiam(p.dialog);
                }
            }
        }
    }

    /** Buang entri yang Activity-nya sudah GC atau dialognya sudah ditutup. */
    private static void sapu() {
        for (int i = DAFTAR.size() - 1; i >= 0; i--) {
            Pasangan p = DAFTAR.get(i);
            if (p.activity.get() == null || !p.dialog.isShowing()) {
                DAFTAR.remove(i);
            }
        }
    }

    private static void tutupDiam(Dialog d) {
        try {
            if (d.isShowing()) {
                d.dismiss();
            }
        } catch (Exception ignored) {
        }
    }
}
