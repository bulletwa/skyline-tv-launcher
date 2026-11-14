package com.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by the alarm set in {@link SleepTimer}. */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (SleepTimer.ACTION.equals(intent.getAction())) SleepTimer.fire(context);
    }
}
