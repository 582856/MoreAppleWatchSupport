package jianchen.applewatch.supportmore;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent();
        intent.setClassName(
                "com.milink.service",
                "com.xiaomi.dist.notification.setting.watch.AppleWatchSettingActivity"
        );
        intent.putExtra("device_id", WatchHooks.WATCH_DEVICE_ID);
        intent.putExtra(WatchHooks.EXTRA_OPEN_NOTIFICATION, true);
        startActivity(intent);
        finish();
    }
}
