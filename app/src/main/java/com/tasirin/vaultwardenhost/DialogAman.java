package com.tasirin.vaultwardenhost;

import android.app.Activity;
import android.app.Dialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

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
        try {
            d.show();
        } catch (Exception e) {
            synchronized (DAFTAR) {
                for (int i = DAFTAR.size() - 1; i >= 0; i--) {
                    if (DAFTAR.get(i).dialog == d) {
                        DAFTAR.remove(i);
                    }
                }
            }
        }
        return d;
    }

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
