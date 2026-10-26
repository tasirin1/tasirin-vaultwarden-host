package com.tasirin.vaultwardenhost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

final class AboutDialog {

    private AboutDialog() {
    }

    @SuppressLint("InflateParams")
    static void tampil(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            return;
        }
        SharedPreferences sp = a.getSharedPreferences(ServerService.PREFS,
                Activity.MODE_PRIVATE);
        String dataDir = TgBackup.amanString(sp, ServerService.KEY_DATA_DIR,
                ServerService.dataDirBawaanSegar());
        if (TextUtils.isEmpty(dataDir)) {
            dataDir = ServerService.dataDirBawaanSegar();
        }
        String bin = Updater.currentServerVersion(a);
        String wv = Updater.readWvVersion(new File(dataDir, "web-vault/vw-version.json"));
        int build = 0;
        try {
            build = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        String versiApp = Updater.appVersionName(a);
        String versi = versiApp == null || versiApp.isEmpty() ? "?" : versiApp;
        StringBuilder infoVersi = new StringBuilder(versi);
        if (bin != null) {
            infoVersi.append(" \u00b7 binary v").append(bin);
        }
        if (wv != null) {
            infoVersi.append(" \u00b7 vault v").append(wv);
        }
        LayoutInflater pompa = LayoutInflater.from(a);
        View lihat = pompa.inflate(R.layout.dialog_about, null);
        TextView versiView = (TextView) lihat.findViewById(R.id.about_version);
        versiView.setText(a.getString(R.string.about_version, infoVersi.toString()));
        LinearLayout baris = (LinearLayout) lihat.findViewById(R.id.about_rows);
        String[] ikon = a.getResources().getStringArray(R.array.about_icons);
        String[] teks = a.getResources().getStringArray(R.array.about_rows);
        float kepadatan = a.getResources().getDisplayMetrics().density;
        int jarak = (int) (10 * kepadatan + 0.5f);
        int atas = (int) (6 * kepadatan + 0.5f);
        for (int i = 0; i < ikon.length && i < teks.length; i++) {
            LinearLayout sel = new LinearLayout(a);
            sel.setOrientation(LinearLayout.HORIZONTAL);
            sel.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.topMargin = atas;
            }
            TextView ikonView = new TextView(a);
            ikonView.setText(ikon[i]);
            ikonView.setTextSize(16);
            ikonView.setPadding(0, 0, jarak, 0);
            TextView teksView = new TextView(a);
            teksView.setText(teks[i]);
            teksView.setTextSize(13.5f);
            teksView.setTextColor(a.getResources().getColor(R.color.text_secondary));
            sel.addView(ikonView);
            sel.addView(teksView, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            baris.addView(sel, lp);
        }
        int targetSdk = 28;
        try {
            targetSdk = a.getPackageManager()
                    .getApplicationInfo(a.getPackageName(), 0).targetSdkVersion;
        } catch (Exception ignored) {
        }
        TextView kaki = (TextView) lihat.findViewById(R.id.about_footer);
        kaki.setText(a.getString(R.string.about_tech, 21, targetSdk, build));

        ScrollView gulir = (ScrollView) lihat.findViewById(R.id.about_scroll);
        int lebarLayar = a.getResources().getDisplayMetrics().widthPixels;
        int lebar = Math.min((int) (560 * kepadatan + 0.5f),
                lebarLayar - (int) (48 * kepadatan + 0.5f));
        int lebarIsi = (lebar > 0 ? lebar : lebarLayar)
                - (int) (40 * kepadatan + 0.5f);
        baris.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, lebarIsi),
                        View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int tinggiMaks = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.40f);
        if (gulir != null && tinggiMaks > 0
                && baris.getMeasuredHeight() > tinggiMaks) {
            android.view.ViewGroup.LayoutParams lp = gulir.getLayoutParams();
            lp.height = tinggiMaks;
            gulir.setLayoutParams(lp);
        }
        lihat.findViewById(R.id.btn_about_github).setOnClickListener(v -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/tasirin1/tasirin-vaultwarden-host")));
            } catch (Exception ignored) {
            }
        });
        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(lihat);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent);
            if (lebar > 0) {
                dialog.getWindow().setLayout(lebar,
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        }
        View tombolUpdate = lihat.findViewById(R.id.btn_about_update);
        if (a instanceof SettingsActivity) {

            tombolUpdate.setOnClickListener(v -> {
                dialog.dismiss();
                ((SettingsActivity) a).fokuskanTombolUpdate();
            });
        } else {
            tombolUpdate.setOnClickListener(v -> {
                dialog.dismiss();
                try {
                    a.startActivity(new Intent(a, SettingsActivity.class));
                } catch (Exception ignored) {
                }
            });
        }
        DialogAman.tampil(a, dialog);
        lihat.findViewById(R.id.btn_about_ok).setOnClickListener(v -> dialog.dismiss());
    }
}
