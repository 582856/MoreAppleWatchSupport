package jianchen.applewatch.supportmore;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.milink.service";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!TARGET_PACKAGE.equals(param.packageName)) {
            return;
        }

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hookParam) {
                if (!INSTALLED.compareAndSet(false, true)) {
                    return;
                }
                Context context = (Context) hookParam.args[0];
                try {
                    WatchHooks.initializeContext(context);
                    installHooks(context.getClassLoader());
                    Log.i(WatchHooks.TAG, "legacy hooks installed");
                } catch (Throwable throwable) {
                    XposedBridge.log("AppleWatchSupport: install failed");
                    XposedBridge.log(throwable);
                }
            }
        });
    }

    private static void installHooks(ClassLoader classLoader) {
        Class<?> fragmentClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.setting.watch.WatchNotificationSyncFragment",
                classLoader
        );
        XposedHelpers.findAndHookMethod(fragmentClass, "initPreferences", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    WatchHooks.addPreferences(param.thisObject, classLoader);
                    Log.i(WatchHooks.TAG, "application preferences added");
                } catch (Throwable throwable) {
                    XposedBridge.log("AppleWatchSupport: add preferences failed");
                    XposedBridge.log(throwable);
                }
            }
        });

        Class<?> preferenceClass = XposedHelpers.findClass(
                "androidx.preference.Preference",
                classLoader
        );
        XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onPreferenceChange",
                preferenceClass,
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Boolean result = WatchHooks.handlePreferenceChange(
                                param.thisObject,
                                param.args[0],
                                param.args[1]
                        );
                        if (result != null) {
                            param.setResult(result);
                        }
                    }
                }
        );

        Class<?> helperClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.setting.watch.AppleWatchTopLevelStatusHelper",
                classLoader
        );
        Class<?> rArrayClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.sub.setting.R$array",
                classLoader
        );
        int notificationArrayId = XposedHelpers.getStaticIntField(
                rArrayClass,
                "watch_notification_sync_app_list"
        );

        XposedHelpers.findAndHookMethod(
                helperClass,
                "isNotificationPackage",
                Context.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (WatchHooks.isNotificationPackage(
                                (Context) param.args[0],
                                (String) param.args[1]
                        )) {
                            param.setResult(true);
                        }
                    }
                }
        );
        XposedHelpers.findAndHookMethod(
                helperClass,
                "containsAny",
                Context.class,
                Set.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Set<?> packages = (Set<?>) param.args[1];
                        int arrayId = (Integer) param.args[2];
                        if (arrayId == notificationArrayId
                                && WatchHooks.containsNotificationPackage(
                                (Context) param.args[0],
                                packages
                        )) {
                            param.setResult(true);
                        }
                    }
                }
        );

        Class<?> notificationHandlerClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.listener.handle.NotificationHandler",
                classLoader
        );
        Class<?> deviceSubInfoClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.common.data.DeviceSubInfo",
                classLoader
        );
        XposedHelpers.findAndHookMethod(
                notificationHandlerClass,
                "isDeviceSupported",
                StatusBarNotification.class,
                deviceSubInfoClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (WatchHooks.isAppleWatchDevice(param.args[1])) {
                            if (WatchHooks.isDynamicIslandNotification(param.args[0])) {
                                Log.i(WatchHooks.TAG, "Dynamic Island notification allowed for Apple Watch");
                                param.setResult(true);
                                return;
                            }
                            boolean allowed = WatchHooks.isNotificationAllowed(
                                    param.args[0],
                                    param.args[1]
                            );
                            Log.i(WatchHooks.TAG, "Apple Watch notification allowed=" + allowed);
                            param.setResult(allowed);
                        }
                    }
                }
        );

        Class<?> notificationBuilderClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.listener.handle.NotificationBuilder",
                classLoader
        );
        XposedHelpers.findAndHookMethod(
                notificationBuilderClass,
                "buildNotificationMessage",
                StatusBarNotification.class,
                deviceSubInfoClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        StatusBarNotification notification =
                                (StatusBarNotification) param.args[0];
                        if (WatchHooks.isDynamicIslandNotification(notification)) {
                            return;
                        }
                        String packageName = notification.getPackageName();
                        if (WatchHooks.isAppleWatchDevice(param.args[1])
                                && WatchHooks.prepareNotification(param.getResult(), packageName)) {
                            Log.i(WatchHooks.TAG, "notification prepared for Apple Watch transport");
                        }
                    }
                }
        );

        Class<?> appleWatchSettingActivityClass = XposedHelpers.findClass(
                "com.xiaomi.dist.notification.setting.watch.AppleWatchSettingActivity",
                classLoader
        );
        XposedHelpers.findAndHookMethod(
                appleWatchSettingActivityClass,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Activity activity = (Activity) param.thisObject;
                        WatchHooks.openNotificationPage(
                                activity,
                                activity.getIntent(),
                                classLoader
                        );
                    }
                }
        );
        XposedHelpers.findAndHookMethod(
                Activity.class,
                "onNewIntent",
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (appleWatchSettingActivityClass.isInstance(param.thisObject)) {
                            WatchHooks.openNotificationPage(
                                    (Activity) param.thisObject,
                                    (Intent) param.args[0],
                                    classLoader
                            );
                        }
                    }
                }
        );
    }
}
