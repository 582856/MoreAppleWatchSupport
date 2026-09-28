package jianchen.applewatch.supportmore;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

final class WatchHooks {
    static final String TAG = "AppleWatchSupport";
    static final String WATCH_ALIAS_PACKAGE = "com.android.mms";
    static final String WATCH_DEVICE_ID = "83E0EB1D";
    static final String EXTRA_OPEN_NOTIFICATION =
            "jianchen.applewatch.supportmore.OPEN_NOTIFICATION";

    private static final int WATCH_DEVICE_TYPE = 16777280;
    private static final String XHS_PACKAGE = "com.xingin.xhs";
    private static final String PREFS_NAME = "apple_watch_all_apps_support";
    private static final String PREF_WHITELIST_MODE = "whitelist_mode";
    private static final String PREF_VISIBLE_APPS = "visible_apps";
    private static final String PREF_DEVICE_NAME = "device_name";
    private static final String KEY_WHITELIST_MODE = "pref_key_watch_whitelist_mode";
    private static final String KEY_DEVICE_NAME = "pref_key_watch_device_name";
    private static final String KEY_ADD_APP = "pref_key_watch_add_app";
    private static final String APP_KEY_PREFIX = "pref_key_watch_noti_app_dynamic_";
    private static final String WEWORK_KEY = "pref_key_watch_noti_app_wework";
    private static final String DEFAULT_DEVICE_NAME = "手机";

    private static final List<String> DEFAULT_PACKAGE_ORDER = List.of(
            "com.android.mms",
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "com.tencent.wework"
    );
    private static final Set<String> DEFAULT_PACKAGES = Set.copyOf(DEFAULT_PACKAGE_ORDER);
    private static final Map<String, String> APP_LABELS = new ConcurrentHashMap<>();
    private static volatile List<ApplicationInfo> cachedApplications = List.of();
    private static volatile Context appContext;

    private WatchHooks() {
    }

    @SuppressWarnings("unchecked")
    static void addPreferences(Object fragment, ClassLoader classLoader) throws Throwable {
        Context context = (Context) getField(fragment, "mContext");
        appContext = context.getApplicationContext();
        Class<?> appPreferenceClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.preference.SubscriptionCheckBoxPreference"
        );
        Class<?> preferenceClass = classLoader.loadClass("androidx.preference.Preference");
        Map<String, String> packageMap =
                (Map<String, String>) getField(fragment, "mKeyPackageMap");
        Map<String, Object> preferenceMap =
                (Map<String, Object>) getField(fragment, "mAppCheckBoxMap");
        Object parent = findPreferenceParent(fragment);

        Object whitelistPreference = addControlPreference(
                fragment,
                parent,
                appPreferenceClass,
                context,
                KEY_WHITELIST_MODE,
                "白名单模式",
                getPrefs(context).getBoolean(PREF_WHITELIST_MODE, true),
                1
        );
        invoke(whitelistPreference, "setVisible", true);
        addDeviceNamePreference(fragment, parent, preferenceClass, context);
        addApplicationButton(
                fragment,
                parent,
                preferenceClass,
                context,
                classLoader
        );

        Map<String, String> existingKeys = new HashMap<>();
        for (Map.Entry<String, String> entry : packageMap.entrySet()) {
            existingKeys.put(entry.getValue(), entry.getKey());
        }

        int order = 100;
        for (String packageName : DEFAULT_PACKAGE_ORDER) {
            Object preference = null;
            String key = existingKeys.get(packageName);
            if (key != null) {
                preference = preferenceMap.get(key);
            } else if (XHS_PACKAGE.equals(packageName) && isInstalled(context, packageName)) {
                preference = ensureAppPreference(
                        fragment,
                        parent,
                        appPreferenceClass,
                        packageMap,
                        preferenceMap,
                        packageName
                );
            }
            if (preference != null) {
                configureAppPreference(context, preference, packageName, order++);
            }
        }

        List<String> selectedPackages = new ArrayList<>(getVisibleApps(context));
        selectedPackages.removeIf(packageName -> DEFAULT_PACKAGES.contains(packageName)
                || !isInstalled(context, packageName));
        selectedPackages.sort((left, right) -> getAppLabel(context, left)
                .compareToIgnoreCase(getAppLabel(context, right)));
        saveVisibleApps(context, new HashSet<>(selectedPackages));
        for (String packageName : selectedPackages) {
            Object preference = ensureAppPreference(
                    fragment,
                    parent,
                    appPreferenceClass,
                    packageMap,
                    preferenceMap,
                    packageName
            );
            configureAppPreference(context, preference, packageName, order++);
        }
    }

    static Boolean handlePreferenceChange(Object fragment, Object preference, Object newValue)
            throws Throwable {
        if (!(newValue instanceof Boolean)) {
            return null;
        }
        String key = String.valueOf(invoke(preference, "getKey"));
        if (!KEY_WHITELIST_MODE.equals(key)) {
            return null;
        }
        boolean enabled = (Boolean) newValue;
        Context context = (Context) getField(fragment, "mContext");
        getPrefs(context).edit().putBoolean(PREF_WHITELIST_MODE, enabled).apply();
        invoke(preference, "setChecked", enabled);
        publishNotificationStatus(
                fragment,
                fragment.getClass().getClassLoader(),
                !enabled || hasCheckedApp(fragment)
        );
        return true;
    }

    static boolean isNotificationPackage(Context context, String packageName) {
        return isManagedPackage(context, packageName);
    }

    static boolean containsNotificationPackage(Context context, Set<?> packages) {
        if (!getPrefs(context).getBoolean(PREF_WHITELIST_MODE, true)) {
            return true;
        }
        for (Object value : packages) {
            if (value instanceof String && isManagedPackage(context, (String) value)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNotificationAllowed(Object notification, Object deviceSubInfo) {
        if (!(notification instanceof StatusBarNotification)) {
            return false;
        }
        try {
            if (!Integer.valueOf(1).equals(invoke(deviceSubInfo, "getDeviceSwitch"))
                    || !isAppleWatchDevice(deviceSubInfo)) {
                return false;
            }
            String packageName = ((StatusBarNotification) notification).getPackageName();
            boolean subscribed = false;
            for (Object appInfo : (List<?>) invoke(
                    deviceSubInfo,
                    "getSubscriptionAppInfoList"
            )) {
                if (packageName.equals(invoke(appInfo, "getPackageName"))) {
                    subscribed = true;
                    break;
                }
            }
            Context context = appContext;
            boolean managed = context != null && isManagedPackage(context, packageName);
            boolean enabled = managed && subscribed;
            boolean whitelist = context == null
                    || getPrefs(context).getBoolean(PREF_WHITELIST_MODE, true);
            return whitelist ? enabled : !enabled;
        } catch (Throwable throwable) {
            Log.w(TAG, "failed to verify notification subscription", throwable);
            return false;
        }
    }

    static boolean isDynamicIslandNotification(Object notification) {
        if (!(notification instanceof StatusBarNotification)) {
            return false;
        }
        android.app.Notification value =
                ((StatusBarNotification) notification).getNotification();
        if (value == null || value.extras == null) {
            return false;
        }
        String focusParam = value.extras.getString("miui.focus.param");
        return focusParam != null && !focusParam.isEmpty();
    }

    static boolean isAppleWatchDevice(Object deviceSubInfo) {
        try {
            return Integer.valueOf(WATCH_DEVICE_TYPE).equals(
                    invoke(deviceSubInfo, "getDeviceType")
            );
        } catch (Throwable throwable) {
            return false;
        }
    }

    static boolean prepareNotification(Object notificationMessage, String packageName)
            throws Throwable {
        if (notificationMessage == null || packageName == null) {
            return false;
        }
        Context context = appContext;
        String appLabel = context == null ? packageName : getAppLabel(context, packageName);
        String prefix = appLabel + " | ";
        Object titleValue = getField(notificationMessage, "title");
        String title = titleValue == null ? "" : String.valueOf(titleValue);
        setField(notificationMessage, "packageName", WATCH_ALIAS_PACKAGE);
        setField(
                notificationMessage,
                "deviceName",
                " " + (context == null ? DEFAULT_DEVICE_NAME : getDeviceName(context))
        );
        if (!title.startsWith(prefix)) {
            setField(notificationMessage, "title", prefix + title);
        }
        return true;
    }

    static void initializeContext(Context context) {
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext != null ? applicationContext : context;
    }

    static void openNotificationPage(Activity activity, Intent source, ClassLoader classLoader)
            throws Throwable {
        if (source == null || !source.getBooleanExtra(EXTRA_OPEN_NOTIFICATION, false)) {
            return;
        }
        source.removeExtra(EXTRA_OPEN_NOTIFICATION);
        String deviceId = source.getStringExtra("device_id");
        Class<?> deviceClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.discover.NotifiDevice"
        );
        Object device = deviceClass.getConstructor(String.class, boolean.class)
                .newInstance(deviceId, true);
        invoke(device, "setDeviceType", WATCH_DEVICE_TYPE);
        invoke(device, "setIsServer", Boolean.TRUE);

        Intent target = new Intent();
        target.setClassName(
                "com.milink.service",
                "com.xiaomi.dist.notification.setting.watch.WatchNotificationSyncActivity"
        );
        target.putExtra("device_info", (Parcelable) device);
        activity.startActivity(target);
        activity.finish();
    }

    private static void addApplicationButton(
            Object fragment,
            Object parent,
            Class<?> preferenceClass,
            Context context,
            ClassLoader classLoader
    ) throws Throwable {
        Object preference = invoke(fragment, "findPreference", KEY_ADD_APP);
        if (preference == null) {
            preference = preferenceClass.getConstructor(Context.class).newInstance(context);
            invoke(preference, "setKey", KEY_ADD_APP);
            if (!addPreference(parent, preference)) {
                throw new IllegalStateException("Add application button was not added");
            }
        }
        invoke(preference, "setTitle", "添加应用");
        invoke(preference, "setSummary", "选择需要显示开关的应用");
        invoke(preference, "setOrder", 2);
        setPreferenceClickListener(
                preference,
                () -> showApplicationPicker(fragment, classLoader)
        );
    }

    private static void addDeviceNamePreference(
            Object fragment,
            Object parent,
            Class<?> preferenceClass,
            Context context
    ) throws Throwable {
        Object preference = invoke(fragment, "findPreference", KEY_DEVICE_NAME);
        if (preference == null) {
            preference = preferenceClass.getConstructor(Context.class).newInstance(context);
            invoke(preference, "setKey", KEY_DEVICE_NAME);
            if (!addPreference(parent, preference)) {
                throw new IllegalStateException("Device name preference was not added");
            }
        }
        invoke(preference, "setTitle", "设备名称自定义");
        invoke(preference, "setSummary", getDeviceName(context));
        invoke(preference, "setOrder", 0);
        Object deviceNamePreference = preference;
        setPreferenceClickListener(
                preference,
                () -> showDeviceNameEditor(context, deviceNamePreference)
        );
    }

    private static void showDeviceNameEditor(Context context, Object preference) {
        try {
            EditText input = newMiuixEditText(context);
            String currentName = getDeviceName(context);
            input.setSingleLine(true);
            input.setHint("例如：小米手机");
            input.setText(currentName);
            input.setSelection(currentName.length());
            LinearLayout inputContainer = new LinearLayout(context);
            inputContainer.setPadding(dp(context, 28), dp(context, 4), dp(context, 28), 0);
            inputContainer.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            Object builder = newMiuixDialogBuilder(context);
            invoke(builder, "B", "设备名称自定义");
            invoke(builder, "C", inputContainer);
            invoke(
                    builder,
                    "r",
                    "取消",
                    (DialogInterface.OnClickListener) (dialog, which) -> dialog.dismiss()
            );
            invoke(
                    builder,
                    "x",
                    "保存",
                    (DialogInterface.OnClickListener) (dialog, which) -> {
                        String deviceName = input.getText().toString().trim();
                        if (deviceName.isEmpty()) {
                            showToast(context, "机型名不能为空");
                            return;
                        }
                        getPrefs(context).edit().putString(PREF_DEVICE_NAME, deviceName).apply();
                        try {
                            invoke(preference, "setSummary", deviceName);
                        } catch (Throwable throwable) {
                            Log.e(TAG, "failed to update device name summary", throwable);
                        }
                    }
            );
            invoke(builder, "D");
        } catch (Throwable throwable) {
            Log.e(TAG, "failed to open device name editor", throwable);
            showToast(context, "设备名称编辑器打开失败");
        }
    }

    private static void showApplicationPicker(Object fragment, ClassLoader classLoader) {
        try {
            Context context = (Context) getField(fragment, "mContext");
            PackageManager packageManager = context.getPackageManager();
            Set<String> selected = new HashSet<>(getVisibleApps(context));
            ApplicationAdapter adapter = new ApplicationAdapter(
                    context,
                    new ArrayList<>(cachedApplications),
                    selected
            );
            adapter.setOnToggleListener((application, checked) -> {
                String packageName = application.packageName;
                if (DEFAULT_PACKAGES.contains(packageName)) {
                    showToast(context, "默认适配应用不可取消");
                    return;
                }
                try {
                    packageManager.getApplicationInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException exception) {
                    showToast(context, "应用已卸载");
                    return;
                }
                adapter.setSelected(packageName, checked);
                saveVisibleApps(context, adapter.getSelectedPackages());
                try {
                    refreshDisplayedApps(fragment, classLoader);
                } catch (Throwable throwable) {
                    Log.e(TAG, "failed to refresh displayed applications", throwable);
                    showToast(context, "应用列表更新失败");
                }
            });

            View searchView = newMiuixSearchView(context);
            invoke(searchView, "setHint", "搜索应用名或包名");
            EditText searchInput = (EditText) invoke(searchView, "getEditText");
            if (cachedApplications.isEmpty()) {
                searchInput.setEnabled(false);
                searchInput.setHint("正在加载应用…");
            }
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    adapter.filter(text == null ? "" : text.toString());
                }

                @Override
                public void afterTextChanged(Editable editable) {
                }
            });
            LinearLayout searchContainer = new LinearLayout(context);
            searchContainer.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 8));
            searchContainer.addView(searchView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            int pickerHeight = context.getResources().getDisplayMetrics().heightPixels * 3 / 4;
            Object builder = newMiuixDialogBuilder(context);
            invoke(builder, "B", "添加应用");
            invoke(builder, "d", adapter, (Object) null);
            invoke(
                    builder,
                    "x",
                    "完成",
                    (DialogInterface.OnClickListener) (dialog, which) -> dialog.dismiss()
            );
            Dialog dialog = (Dialog) invoke(builder, "a");
            Activity hostActivity = context instanceof Activity ? (Activity) context : null;
            if (hostActivity != null) {
                hostActivity.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                );
            }
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                );
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                );
            }
            int[] panelBottom = {Integer.MIN_VALUE};
            View.OnClickListener showKeyboard = ignored -> {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().clearFlags(
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    );
                }
                if (panelBottom[0] == Integer.MIN_VALUE) {
                    int parentPanelId = context.getResources().getIdentifier(
                            "parentPanel",
                            "id",
                            "com.milink.service"
                    );
                    View parentPanel = dialog.findViewById(parentPanelId);
                    if (parentPanel != null) {
                        int[] location = new int[2];
                        parentPanel.getLocationOnScreen(location);
                        panelBottom[0] = location[1]
                                - Math.round(parentPanel.getTranslationY())
                                + parentPanel.getHeight();
                    }
                }
                searchInput.setFocusableInTouchMode(true);
                searchInput.requestFocus();
                searchInput.postDelayed(() -> {
                    InputMethodManager inputMethodManager = (InputMethodManager)
                            context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputMethodManager.showSoftInput(
                            searchInput,
                            InputMethodManager.SHOW_IMPLICIT
                    );
                }, 100L);
            };
            searchView.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    showKeyboard.onClick(view);
                }
                return false;
            });
            searchInput.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    showKeyboard.onClick(view);
                }
                return false;
            });
            searchView.setOnClickListener(showKeyboard);
            searchInput.setOnClickListener(showKeyboard);
            ListView listView = (ListView) invoke(dialog, "getListView");
            int listHeight = pickerHeight - dp(context, 205);
            int panelHeight = pickerHeight - dp(context, 44);
            listView.setMinimumHeight(listHeight);
            View searchSpacer = new View(context);
            searchSpacer.setMinimumHeight(dp(context, 64));
            listView.setAdapter(null);
            listView.addHeaderView(searchSpacer, null, false);
            listView.setAdapter(adapter);
            listView.setVerticalScrollBarEnabled(true);
            listView.setScrollbarFadingEnabled(false);
            listView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            listView.setDividerHeight(dp(context, 8));
            invoke(dialog, "setNonImmersiveDialogHeight", pickerHeight);
            attachSearchBarAfterLayout(
                    dialog,
                    searchContainer,
                    listView,
                    panelBottom,
                    listHeight,
                    panelHeight
            );
            dialog.show();
            listView.setOnItemClickListener((parent, view, position, id) -> {
                Object item = parent.getItemAtPosition(position);
                if (!(item instanceof ApplicationInfo)) {
                    return;
                }
                ApplicationInfo application = (ApplicationInfo) item;
                String packageName = application.packageName;
                if (DEFAULT_PACKAGES.contains(packageName)) {
                    showToast(context, "默认适配应用不可取消");
                    return;
                }
                adapter.toggle(application);
            });
            new Thread(() -> {
                try {
                    List<ApplicationInfo> applications = loadApplications(context);
                    cachedApplications = List.copyOf(applications);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!dialog.isShowing()) {
                            return;
                        }
                        adapter.replaceApplications(applications);
                        searchInput.setEnabled(true);
                        searchInput.setHint("搜索应用名或包名");
                    });
                } catch (Throwable throwable) {
                    Log.e(TAG, "failed to load applications", throwable);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (dialog.isShowing()) {
                            searchInput.setEnabled(true);
                            searchInput.setHint("搜索应用名或包名");
                            showToast(context, "应用列表加载失败");
                        }
                    });
                }
            }, "AppleWatchSupport-app-loader").start();
        } catch (Throwable throwable) {
            Log.e(TAG, "failed to open application picker", throwable);
        }
    }

    private static void attachSearchBarAfterLayout(
            Dialog dialog,
            View searchContainer,
            ListView listView,
            int[] panelBottom,
            int listHeight,
            int panelHeight
    ) {
        dialog.setOnShowListener(ignored -> {
            ViewGroup.LayoutParams listParams = listView.getLayoutParams();
            if (listParams != null) {
                listParams.height = listHeight;
                listView.setLayoutParams(listParams);
            }
            listView.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            if (listView.getWidth() <= 0 || listView.getHeight() <= 0) {
                                return true;
                            }
                            listView.getViewTreeObserver().removeOnPreDrawListener(this);
                            listView.post(
                                    () -> attachSearchBar(
                                            dialog,
                                            searchContainer,
                                            listView,
                                            panelBottom,
                                            listHeight,
                                            panelHeight
                                    )
                            );
                            return true;
                        }
                    }
            );
        });
    }

    private static void attachSearchBar(
            Dialog dialog,
            View searchContainer,
            ListView listView,
            int[] panelBottom,
            int listHeight,
            int panelHeight
    ) {
        if (searchContainer.getParent() != null || dialog.getWindow() == null) {
            return;
        }
        View decor = dialog.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        int[] listLocation = new int[2];
        int[] decorLocation = new int[2];
        listView.getLocationOnScreen(listLocation);
        decor.getLocationOnScreen(decorLocation);
        int searchHeight = dp(listView.getContext(), 64);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                listView.getWidth(),
                searchHeight
        );
        ((ViewGroup) decor).addView(searchContainer, params);
        int parentPanelId = listView.getContext().getResources().getIdentifier(
                "parentPanel",
                "id",
                "com.milink.service"
        );
        View parentPanel = dialog.findViewById(parentPanelId);
        ViewTreeObserver.OnPreDrawListener positionListener = () -> {
            boolean sizeChanged = false;
            ViewGroup.LayoutParams listParams = listView.getLayoutParams();
            if (listParams != null && listParams.height != listHeight) {
                listParams.height = listHeight;
                listView.setLayoutParams(listParams);
                sizeChanged = true;
            }
            if (parentPanel != null) {
                ViewGroup.LayoutParams panelParams = parentPanel.getLayoutParams();
                if (panelParams != null && panelParams.height != panelHeight) {
                    panelParams.height = panelHeight;
                    parentPanel.setLayoutParams(panelParams);
                    sizeChanged = true;
                }
            }
            if (sizeChanged) {
                return false;
            }
            if (parentPanel != null && panelBottom[0] != Integer.MIN_VALUE) {
                int[] location = new int[2];
                parentPanel.getLocationOnScreen(location);
                float rawBottom = location[1]
                        - parentPanel.getTranslationY()
                        + parentPanel.getHeight();
                float translationY = panelBottom[0] - rawBottom;
                if (parentPanel.getTranslationY() != translationY) {
                    parentPanel.setTranslationY(translationY);
                }
            }
            listView.getLocationOnScreen(listLocation);
            decor.getLocationOnScreen(decorLocation);
            float targetX = listLocation[0] - decorLocation[0];
            float targetY = listLocation[1] - decorLocation[1];
            if (searchContainer.getX() != targetX) {
                searchContainer.setX(targetX);
            }
            if (searchContainer.getY() != targetY) {
                searchContainer.setY(targetY);
            }
            return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(positionListener);
        dialog.setOnDismissListener(ignored -> {
            if (decor.getViewTreeObserver().isAlive()) {
                decor.getViewTreeObserver().removeOnPreDrawListener(positionListener);
            }
        });
        searchContainer.bringToFront();
    }

    private static List<ApplicationInfo> loadApplications(Context context) {
        List<ApplicationInfo> applications = new ArrayList<>(
                context.getPackageManager().getInstalledApplications(0)
        );
        for (ApplicationInfo application : applications) {
            APP_LABELS.put(application.packageName, getAppLabel(context, application));
        }
        List<ApplicationInfo> orderedApplications = new ArrayList<>();
        for (String packageName : DEFAULT_PACKAGE_ORDER) {
            for (ApplicationInfo application : applications) {
                if (packageName.equals(application.packageName)) {
                    orderedApplications.add(application);
                    break;
                }
            }
        }
        applications.removeIf(application -> DEFAULT_PACKAGES.contains(application.packageName));
        applications.sort((left, right) -> APP_LABELS.get(left.packageName)
                .compareToIgnoreCase(APP_LABELS.get(right.packageName)));
        orderedApplications.addAll(applications);
        return orderedApplications;
    }

    @SuppressWarnings("unchecked")
    private static void refreshDisplayedApps(Object fragment, ClassLoader classLoader)
            throws Throwable {
        Context context = (Context) getField(fragment, "mContext");
        Class<?> appPreferenceClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.preference.SubscriptionCheckBoxPreference"
        );
        Map<String, String> packageMap =
                (Map<String, String>) getField(fragment, "mKeyPackageMap");
        Map<String, Object> preferenceMap =
                (Map<String, Object>) getField(fragment, "mAppCheckBoxMap");
        Object parent = findPreferenceParent(fragment);
        Set<String> selected = getVisibleApps(context);

        for (Map.Entry<String, String> entry : new ArrayList<>(packageMap.entrySet())) {
            String key = entry.getKey();
            String packageName = entry.getValue();
            if (!key.startsWith(APP_KEY_PREFIX)
                    || DEFAULT_PACKAGES.contains(packageName)
                    || selected.contains(packageName)) {
                continue;
            }
            Object preference = preferenceMap.get(key);
            if (preference != null) {
                if (Boolean.TRUE.equals(invoke(preference, "isChecked"))) {
                    invoke(fragment, "onPreferenceChange", preference, Boolean.FALSE);
                }
                removePreference(parent, preference);
            }
            packageMap.remove(key);
            preferenceMap.remove(key);
        }

        List<String> packages = new ArrayList<>(selected);
        packages.removeIf(packageName -> DEFAULT_PACKAGES.contains(packageName)
                || !isInstalled(context, packageName));
        packages.sort((left, right) -> getAppLabel(context, left)
                .compareToIgnoreCase(getAppLabel(context, right)));
        int order = 200;
        for (String packageName : packages) {
            Object preference = ensureAppPreference(
                    fragment,
                    parent,
                    appPreferenceClass,
                    packageMap,
                    preferenceMap,
                    packageName
            );
            configureAppPreference(context, preference, packageName, order++);
        }
        invoke(fragment, "querySubscribeInfo");
    }

    private static Object addControlPreference(
            Object fragment,
            Object parent,
            Class<?> preferenceClass,
            Context context,
            String key,
            String title,
            boolean checked,
            int order
    ) throws Throwable {
        Object preference = invoke(fragment, "findPreference", key);
        if (preference == null) {
            preference = preferenceClass.getConstructor(Context.class).newInstance(context);
            invoke(preference, "setKey", key);
            if (!addPreference(parent, preference)) {
                throw new IllegalStateException("Preference was not added: " + key);
            }
        }
        invoke(preference, "setTitle", title);
        invoke(preference, "setChecked", checked);
        invoke(preference, "setOrder", order);
        invoke(preference, "setOnPreferenceChangeListener", fragment);
        return preference;
    }

    private static Object ensureAppPreference(
            Object fragment,
            Object parent,
            Class<?> preferenceClass,
            Map<String, String> packageMap,
            Map<String, Object> preferenceMap,
            String packageName
    ) throws Throwable {
        String key = APP_KEY_PREFIX + packageName;
        Object preference = preferenceMap.get(key);
        if (preference != null) {
            return preference;
        }
        Context context = (Context) getField(fragment, "mContext");
        preference = preferenceClass.getConstructor(Context.class).newInstance(context);
        invoke(preference, "setKey", key);
        invoke(preference, "setOnPreferenceChangeListener", fragment);
        packageMap.put(key, packageName);
        preferenceMap.put(key, preference);
        if (!addPreference(parent, preference)) {
            packageMap.remove(key);
            preferenceMap.remove(key);
            throw new IllegalStateException("Application preference was not added: " + packageName);
        }
        return preference;
    }

    private static void configureAppPreference(
            Context context,
            Object preference,
            String packageName,
            int order
    ) throws Throwable {
        invoke(preference, "setOrder", order);
        invoke(preference, "setVisible", true);
        if (!isInstalled(context, packageName)) {
            return;
        }
        invoke(preference, "setTitle", getAppLabel(context, packageName));
        try {
            invoke(preference, "setIcon", getSizedAppIcon(context, packageName));
        } catch (Throwable throwable) {
            Log.w(TAG, "app icon unavailable: " + packageName, throwable);
        }
    }

    private static void setPreferenceClickListener(Object preference, Runnable callback)
            throws Throwable {
        Method listenerMethod = null;
        for (Class<?> type = preference.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("setOnPreferenceClickListener")
                        && method.getParameterCount() == 1) {
                    listenerMethod = method;
                    break;
                }
            }
            if (listenerMethod != null) {
                break;
            }
        }
        if (listenerMethod == null) {
            throw new NoSuchMethodException("setOnPreferenceClickListener");
        }
        Class<?> listenerClass = listenerMethod.getParameterTypes()[0];
        Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        return "AddApplicationClickListener";
                    }
                    callback.run();
                    return true;
                }
        );
        listenerMethod.setAccessible(true);
        listenerMethod.invoke(preference, listener);
    }

    private static Object findPreferenceParent(Object fragment) throws Throwable {
        Object reference = invoke(fragment, "findPreference", WEWORK_KEY);
        Object parent = reference == null
                ? invoke(fragment, "getPreferenceScreen")
                : invoke(reference, "getParent");
        if (parent == null) {
            throw new IllegalStateException("Preference parent not found");
        }
        return parent;
    }

    private static boolean addPreference(Object parent, Object preference) throws Throwable {
        Object result;
        try {
            result = invoke(parent, "addPreference", preference);
        } catch (NoSuchMethodException ignored) {
            result = invoke(parent, "l", preference);
        }
        return !(result instanceof Boolean) || (Boolean) result;
    }

    private static void removePreference(Object parent, Object preference) throws Throwable {
        try {
            invoke(parent, "removePreference", preference);
        } catch (NoSuchMethodException ignored) {
            invoke(parent, "u", preference);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasCheckedApp(Object fragment) throws Throwable {
        Map<String, Object> preferenceMap =
                (Map<String, Object>) getField(fragment, "mAppCheckBoxMap");
        for (Object preference : preferenceMap.values()) {
            if (Boolean.TRUE.equals(invoke(preference, "isChecked"))) {
                return true;
            }
        }
        return false;
    }

    private static void publishNotificationStatus(
            Object fragment,
            ClassLoader classLoader,
            boolean enabled
    ) throws Throwable {
        Context context = (Context) getField(fragment, "mContext");
        Object deviceInfo = getField(fragment, "mDeviceInfo");
        if (context == null || deviceInfo == null) {
            return;
        }
        Class<?> helperClass = classLoader.loadClass(
                "com.xiaomi.dist.notification.setting.watch.AppleWatchTopLevelStatusHelper"
        );
        invokeStatic(helperClass, "setNotificationStatus", context, deviceInfo, enabled);
    }

    private static boolean isManagedPackage(Context context, String packageName) {
        return DEFAULT_PACKAGES.contains(packageName)
                || getVisibleApps(context).contains(packageName);
    }

    private static Set<String> getVisibleApps(Context context) {
        return new HashSet<>(getPrefs(context).getStringSet(PREF_VISIBLE_APPS, Set.of()));
    }

    private static void saveVisibleApps(Context context, Set<String> packages) {
        getPrefs(context).edit().putStringSet(PREF_VISIBLE_APPS, new HashSet<>(packages)).apply();
    }

    private static String getDeviceName(Context context) {
        String deviceName = getPrefs(context).getString(PREF_DEVICE_NAME, DEFAULT_DEVICE_NAME);
        return deviceName == null || deviceName.trim().isEmpty()
                ? DEFAULT_DEVICE_NAME
                : deviceName.trim();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static String getAppLabel(Context context, String packageName) {
        if (XHS_PACKAGE.equals(packageName)) {
            return "小红书";
        }
        String cached = APP_LABELS.get(packageName);
        if (cached != null) {
            return cached;
        }
        try {
            ApplicationInfo application = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            String label = getAppLabel(context, application);
            APP_LABELS.put(packageName, label);
            return label;
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private static String getAppLabel(Context context, ApplicationInfo application) {
        if (XHS_PACKAGE.equals(application.packageName)) {
            return "小红书";
        }
        CharSequence label = context.getPackageManager().getApplicationLabel(application);
        return label == null ? application.packageName : label.toString();
    }

    private static Object newMiuixDialogBuilder(Context context) throws Throwable {
        Class<?> builderClass = context.getClassLoader().loadClass("miuix.appcompat.app.u$a");
        return builderClass.getConstructor(Context.class).newInstance(context);
    }

    private static EditText newMiuixEditText(Context context) throws Throwable {
        Class<?> editTextClass = context.getClassLoader().loadClass(
                "miuix.androidbasewidget.widget.EditText"
        );
        return (EditText) editTextClass.getConstructor(Context.class).newInstance(context);
    }

    private static View newMiuixSearchView(Context context) throws Throwable {
        Class<?> searchViewClass = context.getClassLoader().loadClass(
                "miuix.appcompat.app.SearchView"
        );
        return (View) searchViewClass.getConstructor(Context.class).newInstance(context);
    }

    private static Drawable getSizedAppIcon(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        Drawable icon = context.getPackageManager().getApplicationIcon(packageName);
        int size = dp(context, 40);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(bitmap));
        BitmapDrawable result = new BitmapDrawable(context.getResources(), bitmap);
        result.setBounds(0, 0, size, size);
        return result;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static boolean isNightMode(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private static Object getField(Object target, String name) throws Throwable {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object target, String name, Object value) throws Throwable {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name, Object... args) throws Throwable {
        Method method = findCompatibleMethod(target.getClass(), name, args);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Object invokeStatic(Class<?> type, String name, Object... args) throws Throwable {
        Method method = findCompatibleMethod(type, name, args);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Method findCompatibleMethod(Class<?> start, String name, Object[] args)
            throws NoSuchMethodException {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!method.getName().equals(name) || parameterTypes.length != args.length) {
                    continue;
                }
                boolean compatible = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null) {
                        compatible &= !parameterTypes[i].isPrimitive();
                    } else {
                        compatible &= boxed(parameterTypes[i]).isAssignableFrom(args[i].getClass());
                    }
                }
                if (compatible) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(start.getName() + "#" + name);
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static final class ApplicationAdapter extends BaseAdapter {
        private final Context context;
        private final List<ApplicationInfo> allApplications;
        private final List<ApplicationInfo> filteredApplications = new ArrayList<>();
        private final Set<String> selectedPackages;
        private BiConsumer<ApplicationInfo, Boolean> onToggleListener;
        private String query = "";

        ApplicationAdapter(
                Context context,
                List<ApplicationInfo> applications,
                Set<String> selectedPackages
        ) {
            this.context = context;
            this.allApplications = applications;
            this.selectedPackages = selectedPackages;
            this.filteredApplications.addAll(applications);
        }

        void filter(String query) {
            this.query = query == null ? "" : query;
            String normalized = this.query.trim().toLowerCase(Locale.ROOT);
            filteredApplications.clear();
            for (ApplicationInfo application : allApplications) {
                String label = APP_LABELS.get(application.packageName);
                if (normalized.isEmpty()
                        || application.packageName.toLowerCase(Locale.ROOT).contains(normalized)
                        || label.toLowerCase(Locale.ROOT).contains(normalized)) {
                    filteredApplications.add(application);
                }
            }
            notifyDataSetChanged();
        }

        void replaceApplications(List<ApplicationInfo> applications) {
            allApplications.clear();
            allApplications.addAll(applications);
            filter(query);
        }

        boolean isSelected(String packageName) {
            return selectedPackages.contains(packageName);
        }

        void setOnToggleListener(BiConsumer<ApplicationInfo, Boolean> listener) {
            onToggleListener = listener;
        }

        void toggle(ApplicationInfo application) {
            if (onToggleListener != null) {
                onToggleListener.accept(
                        application,
                        !selectedPackages.contains(application.packageName)
                );
            }
        }

        void setSelected(String packageName, boolean selected) {
            if (selected) {
                selectedPackages.add(packageName);
            } else {
                selectedPackages.remove(packageName);
            }
            notifyDataSetChanged();
        }

        Set<String> getSelectedPackages() {
            return new HashSet<>(selectedPackages);
        }

        @Override
        public int getCount() {
            return filteredApplications.size();
        }

        @Override
        public ApplicationInfo getItem(int position) {
            return filteredApplications.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            AppRowHolder holder;
            if (convertView instanceof LinearLayout
                    && convertView.getTag() instanceof AppRowHolder) {
                row = (LinearLayout) convertView;
                holder = (AppRowHolder) row.getTag();
            } else {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
                row.setMinimumHeight(dp(context, 76));
                GradientDrawable background = new GradientDrawable();
                background.setColor(isNightMode(context) ? 0xff242426 : 0xffffffff);
                background.setCornerRadius(dp(context, 14));
                row.setBackground(background);

                ImageView icon = new ImageView(context);
                row.addView(icon, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                labelsParams.setMarginStart(dp(context, 16));
                row.addView(labels, labelsParams);

                TextView title = new TextView(context);
                title.setTextSize(17);
                labels.addView(title);

                TextView packageName = new TextView(context);
                packageName.setTextSize(12);
                packageName.setAlpha(0.65f);
                labels.addView(packageName);

                CompoundButton toggle;
                try {
                    Class<?> slidingButtonClass = context.getClassLoader().loadClass(
                            "miuix.slidingwidget.widget.SlidingButton"
                    );
                    toggle = (CompoundButton) slidingButtonClass
                            .getConstructor(Context.class)
                            .newInstance(context);
                } catch (Throwable throwable) {
                    toggle = new Switch(context);
                }
                toggle.setFocusable(false);
                row.addView(toggle);
                holder = new AppRowHolder(icon, title, packageName, toggle);
                row.setTag(holder);
            }
            ApplicationInfo application = getItem(position);
            boolean fixed = DEFAULT_PACKAGES.contains(application.packageName);
            holder.title.setText(APP_LABELS.get(application.packageName)
                    + (fixed ? "  （默认）" : ""));
            holder.packageName.setText(application.packageName);
            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(fixed || selectedPackages.contains(application.packageName));
            holder.toggle.setEnabled(!fixed);
            holder.toggle.setClickable(!fixed);
            holder.toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!fixed && onToggleListener != null) {
                    onToggleListener.accept(application, isChecked);
                }
            });
            row.setAlpha(fixed ? 0.6f : 1f);
            try {
                holder.icon.setImageDrawable(getSizedAppIcon(context, application.packageName));
            } catch (PackageManager.NameNotFoundException ignored) {
                holder.icon.setImageDrawable(null);
            }
            return row;
        }
    }

    private static final class AppRowHolder {
        final ImageView icon;
        final TextView title;
        final TextView packageName;
        final CompoundButton toggle;

        AppRowHolder(
                ImageView icon,
                TextView title,
                TextView packageName,
                CompoundButton toggle
        ) {
            this.icon = icon;
            this.title = title;
            this.packageName = packageName;
            this.toggle = toggle;
        }
    }
}
