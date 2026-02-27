package com.example.sdcardbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * This receiver detects when an SD card is inserted
 * You can use this to automatically open the app
 */
public class SDCardReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action != null && action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
            // SD card was inserted
            Toast.makeText(context, "SD Card detected!", Toast.LENGTH_SHORT).show();

            // Optionally, open the app automatically
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
        }
    }
}