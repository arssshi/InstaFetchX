package com.instafetchx;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Quick Download entry point: receives an Instagram link from the Android share
 * sheet (or a link tapped in the browser), kicks off a background download and
 * finishes immediately — the app never visibly opens.
 */
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String text = null;
        String action = getIntent().getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            } else if (Intent.ACTION_VIEW.equals(action)) {
                text = String.valueOf(getIntent().getData());
            }
        } catch (Exception ignored) { }
        String url = Res.extractUrl(text);
        if (url == null) {
            Toast.makeText(this, "InstaFetchX: no Instagram link found in the shared content",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        DownloadService.start(this, url);
        Toast.makeText(this, "Downloading in background — check notifications", Toast.LENGTH_SHORT).show();
        finish();
        overridePendingTransition(0, 0);
    }
}
