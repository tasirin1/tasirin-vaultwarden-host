package com.tasirin.vaultwardenhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Pemicu backup terjadwal (AlarmManager) — jalan walau app tidak dibuka. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ServerService.backupNow(context);
    }
}
