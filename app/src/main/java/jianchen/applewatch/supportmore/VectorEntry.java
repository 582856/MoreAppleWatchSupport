package jianchen.applewatch.supportmore;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class VectorEntry extends XposedModule {
    private static final String TARGET_PACKAGE = "com.milink.service";
    private static final AtomicBoolean ATTACH_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()
                || !TARGET_PACKAGE.equals(param.getPackageName())
                || !ATTACH_HOOKED.compareAndSet(false, true)) {
            return;
        }

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Context context = (Context) chain.getArg(0);
                        if (INSTALLED.compareAndSet(false, true)) {
                            WatchHooks.initializeContext(context);
                            installHooks(context.getClassLoader());
                            logInfo("modern hooks installed");
                        }
                        return result;
                    });
            logInfo("hooked Application.attach");
        } catch (Throwable throwable) {
            logError("hook Application.attach failed", throwable);
        }
    }

    private void installHooks(ClassLoader classLoader) throws Throwable {
        Class<?> fragmentClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.watch.WatchNotificationSyncFragment"
        );
        Method initPreferences = fragmentClass.getDeclaredMethod("initPreferences");
        initPreferences.setAccessible(true);
        hook(initPreferences)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        WatchHooks.addPreferences(chain.getThisObject(), classLoader);
                        logInfo("application preferences added");
                    } catch (Throwable throwable) {
                        logError("add preferences failed", throwable);
                    }
                    return result;
                });

        Class<?> preferenceClass = classLoader.loadClass("androidx.preference.Preference");
        Method onPreferenceChange = fragmentClass.getDeclaredMethod(
                "onPreferenceChange",
                preferenceClass,
                Object.class
        );
        onPreferenceChange.setAccessible(true);
        hook(onPreferenceChange)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Boolean result = WatchHooks.handlePreferenceChange(
                            chain.getThisObject(),
                            chain.getArg(0),
                            chain.getArg(1)
                    );
                    return result != null ? result : chain.proceed();
                });

        Class<?> helperClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.watch.AppleWatchTopLevelStatusHelper"
        );
        Class<?> rArrayClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.sub.setting.R$array"
        );
        int notificationArrayId = rArrayClass
                .getDeclaredField("watch_notification_sync_app_list")
                .getInt(null);

        Method isNotificationPackage = helperClass.getDeclaredMethod(
                "isNotificationPackage",
                Context.class,
                String.class
        );
        isNotificationPackage.setAccessible(true);
        hook(isNotificationPackage)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> WatchHooks.isNotificationPackage(
                        (Context) chain.getArg(0),
                        (String) chain.getArg(1)
                )
                        ? true
                        : chain.proceed());

        Method containsAny = helperClass.getDeclaredMethod(
                "containsAny",
                Context.class,
                Set.class,
                int.class
        );
        containsAny.setAccessible(true);
        hook(containsAny)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Set<?> packages = (Set<?>) chain.getArg(1);
                    int arrayId = (Integer) chain.getArg(2);
                    if (arrayId == notificationArrayId
                            && WatchHooks.containsNotificationPackage(
                            (Context) chain.getArg(0),
                            packages
                    )) {
                        return true;
                    }
                    return chain.proceed();
                });

        Class<?> notificationHandlerClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.listener.handle.NotificationHandler"
        );
        Class<?> statusBarNotificationClass = classLoader.loadClass(
                "android.service.notification.StatusBarNotification"
        );
        Class<?> deviceSubInfoClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.common.data.DeviceSubInfo"
        );
        Method isDeviceSupported = notificationHandlerClass.getDeclaredMethod(
                "isDeviceSupported",
                statusBarNotificationClass,
                deviceSubInfoClass
        );
        isDeviceSupported.setAccessible(true);
        hook(isDeviceSupported)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (WatchHooks.isAppleWatchDevice(chain.getArg(1))) {
                        if (WatchHooks.isDynamicIslandNotification(chain.getArg(0))) {
                            logInfo("Dynamic Island notification allowed for Apple Watch");
                            return true;
                        }
                        boolean allowed = WatchHooks.isNotificationAllowed(
                                chain.getArg(0),
                                chain.getArg(1)
                        );
                        logInfo("Apple Watch notification allowed=" + allowed);
                        return allowed;
                    }
                    return chain.proceed();
                });

        Class<?> notificationBuilderClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.listener.handle.NotificationBuilder"
        );
        Method buildNotificationMessage = notificationBuilderClass.getDeclaredMethod(
                "buildNotificationMessage",
                statusBarNotificationClass,
                deviceSubInfoClass
        );
        buildNotificationMessage.setAccessible(true);
        hook(buildNotificationMessage)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    StatusBarNotification notification =
                            (StatusBarNotification) chain.getArg(0);
                    if (WatchHooks.isDynamicIslandNotification(notification)) {
                        return result;
                    }
                    String packageName = notification.getPackageName();
                    if (WatchHooks.isAppleWatchDevice(chain.getArg(1))
                            && WatchHooks.prepareNotification(result, packageName)) {
                        logInfo("notification prepared for Apple Watch transport");
                    }
                    return result;
                });

        Class<?> appleWatchSettingActivityClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.watch.AppleWatchSettingActivity"
        );
        Method onCreate = appleWatchSettingActivityClass.getDeclaredMethod(
                "onCreate",
                Bundle.class
        );
        onCreate.setAccessible(true);
        hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    WatchHooks.openNotificationPage(
                            activity,
                            activity.getIntent(),
                            classLoader
                    );
                    return result;
                });

        Method onNewIntent = Activity.class.getDeclaredMethod("onNewIntent", Intent.class);
        onNewIntent.setAccessible(true);
        hook(onNewIntent)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (appleWatchSettingActivityClass.isInstance(chain.getThisObject())) {
                        WatchHooks.openNotificationPage(
                                (Activity) chain.getThisObject(),
                                (Intent) chain.getArg(0),
                                classLoader
                        );
                    }
                    return result;
                });
    }

    private void logInfo(String message) {
        Log.i(WatchHooks.TAG, message);
        try {
            log(Log.INFO, WatchHooks.TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private void logError(String message, Throwable throwable) {
        Log.e(WatchHooks.TAG, message, throwable);
        try {
            log(Log.ERROR, WatchHooks.TAG, message, throwable);
        } catch (Throwable ignored) {
        }
    }
}
