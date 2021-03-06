package ohi.andre.consolelauncher.managers;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ohi.andre.comparestring.Compare;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.tuils.Tuils;
import ohi.andre.consolelauncher.tuils.interfaces.Outputable;

public class AppsManager {

    public static final int SHOWN_APPS = 10;
    public static final int HIDDEN_APPS = 11;

    public static final int MIN_RATE = 5;
    public static final boolean USE_SCROLL_COMPARE = true;

    private final int SUGGESTED_APPS_LENGTH = 5;

    private final String APPS_PREFERENCES = "appsPreferences";

    private Context context;
    private SharedPreferences.Editor prefsEditor;

    private AppsHolder appsHolder;
    private List<AppInfo> hiddenApps;

    private Outputable outputable;

    private boolean useCompareString;

    private BroadcastReceiver appsBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String data = intent.getData().getSchemeSpecificPart();
            if (action.equals(Intent.ACTION_PACKAGE_ADDED))
                add(data);
            else
                remove(data);
        }
    };

    public AppsManager(Context context, boolean useCompareString, Outputable outputable) {
        this.context = context;
        this.useCompareString = useCompareString;

        this.outputable = outputable;

        SharedPreferences preferences = context.getSharedPreferences(APPS_PREFERENCES, Context.MODE_PRIVATE);
        prefsEditor = preferences.edit();
        fill(preferences);

        initAppListener(context);
    }

    private void initAppListener(Context c) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");

        c.registerReceiver(appsBroadcast, intentFilter);
    }

    public void fill(SharedPreferences preferences) {
        Map<String, AppInfo> map = createAppMap(context.getPackageManager());
        List<AppInfo> shownApps = new ArrayList<>();
        hiddenApps = new ArrayList<>();

        Map<String, ?> values;
        try {
            values = preferences.getAll();
        } catch (Exception e) {
            for(Map.Entry<String, AppInfo> entry : map.entrySet()) {
                shownApps.add(entry.getValue());
            }
            appsHolder = new AppsHolder(shownApps);
            return;
        }

        for(Map.Entry<String, ?> entry : values.entrySet()) {
            if(entry.getValue() instanceof Boolean) {
                if((Boolean) entry.getValue()) {
                    AppInfo info = map.get(entry.getKey());
                    hiddenApps.add(info);
                    map.remove(entry.getKey());
                }
            } else {
                AppInfo info = map.get(entry.getKey());
                if(info == null) {
                    continue;
                } else {
                    info.launchedTimes = (Integer) entry.getValue();
                }
            }
        }

        for (Map.Entry<String, AppInfo> stringAppInfoEntry : map.entrySet()) {
            AppInfo app = stringAppInfoEntry.getValue();
            shownApps.add(app);
        }

        appsHolder = new AppsHolder(shownApps);
        AppUtils.checkEquality(hiddenApps);
    }

    private Map<String, AppInfo> createAppMap(PackageManager mgr) {
        Map<String, AppInfo> map = new HashMap<>();

        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = mgr.queryIntentActivities(i, 0);

        for (ResolveInfo info : infos) {
            AppInfo app = new AppInfo(info.activityInfo.packageName, info.loadLabel(mgr).toString());
            map.put(info.activityInfo.packageName, app);
        }

        return map;
    }

    private void add(String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            AppInfo app = new AppInfo(packageName, info.loadLabel(manager).toString(), 0);
            appsHolder.add(app);
            outputable.onOutput(context.getString(R.string.app_installed) + Tuils.SPACE + packageName);
        } catch (NameNotFoundException e) {}
    }

    private void remove(String packageName) {
        AppInfo info = AppUtils.findAppInfo(packageName, appsHolder.getApps());
        if(info != null) {
            appsHolder.remove(info);
            appsHolder.update(true);
        }
    }

//    this looks EVERYWHERE!
//    public String findPackage(String name) {
//        List<AppInfo> apps = appsHolder.getApps();
//        if(apps != null) {
//            apps.addAll(hiddenApps);
//            return findPackage(apps, null, name);
//        }
//        return null;
//    }

    public String findPackage(String name, int type) {
        List<AppInfo> appList;
        List<String> labelList;
        if(type == SHOWN_APPS) {
            appList = appsHolder.getApps();
            labelList = appsHolder.getAppLabels();
        } else {
            appList = hiddenApps;
            labelList = AppUtils.labelList(appList);
        }

        return findPackage(appList, labelList, name);
    }

    public String findPackage(List<AppInfo> appList, List<String> labels, String name) {
        name = Compare.removeSpaces(name).toLowerCase();
        if(labels == null) {
            labels = AppUtils.labelList(appList);
        }

        if(useCompareString) {
            String label = Compare.similarString(labels, name, MIN_RATE, USE_SCROLL_COMPARE);
            if (label == null) {
                return null;
            }

            for(AppInfo info : appList) {
                if (info.publicLabel.equals(name)) {
                    return info.packageName;
                }
            }
        } else {
            for(AppInfo info : appList) {
                if(name.equals(Compare.removeSpaces(info.publicLabel.toLowerCase()))) {
                    return info.packageName;
                }
            }
        }

        return null;
    }

    public Intent getIntent(String packageName) {
        AppInfo info = AppUtils.findAppInfo(packageName, appsHolder.getApps());
        if(info == null) {
            return null;
        }

        info.launchedTimes++;
        appsHolder.updateSuggestion(info);

        prefsEditor.putInt(packageName, info.launchedTimes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            applyPrefs();
        } else {
            prefsEditor.commit();
        }

        return context.getPackageManager().getLaunchIntentForPackage(packageName);
    }

    public String hideApp(String packageName) {
        AppInfo info = AppUtils.findAppInfo(packageName, appsHolder.getApps());
        if(info == null) {
            return null;
        }

        appsHolder.remove(info);
        appsHolder.update(true);
        hiddenApps.add(info);
        AppUtils.checkEquality(hiddenApps);

        prefsEditor.putBoolean(packageName, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            applyPrefs();
        } else {
            prefsEditor.commit();
        }

        return info.publicLabel;
    }

    public String unhideApp(String packageName) {
        AppInfo info = AppUtils.findAppInfo(packageName, hiddenApps);
        if(info == null) {
            return null;
        }

        hiddenApps.remove(info);
        appsHolder.add(info);
        appsHolder.update(false);

        prefsEditor.putBoolean(packageName, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            applyPrefs();
        } else {
            prefsEditor.commit();
        }

        return info.publicLabel;
    }

    public List<String> getAppLabels() {
        return appsHolder.getAppLabels();
    }

    public List<String> getHiddenAppsLabels() {
        return AppUtils.labelList(hiddenApps);
    }

    public String[] getSuggestedApps() {
        return appsHolder.getSuggestedApps();
    }

    public String printApps(int type) {
        List<String> labels = type == SHOWN_APPS ? appsHolder.appLabels : AppUtils.labelList(hiddenApps);
        return AppUtils.printApps(labels);
    }

    public void unregisterReceiver(Context context) {
        context.unregisterReceiver(appsBroadcast);
    }

    public void onDestroy() {
        unregisterReceiver(context);
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private void applyPrefs() {
        prefsEditor.apply();
    }

    public static class AppInfo {

        public String packageName;
        public String publicLabel;
        public int launchedTimes;

        public AppInfo(String packageName, String publicLabel, int launchedTimes) {
            this.packageName = packageName;
            this.publicLabel = publicLabel;
            this.launchedTimes = launchedTimes;
        }

        public AppInfo(String packageName, String publicLabel) {
            this.packageName = packageName;
            this.publicLabel = publicLabel;
        }

        @Override
        public boolean equals(Object o) {
            if(o == null) {
                return false;
            }

            if(o instanceof AppInfo) {
                AppInfo i = (AppInfo) o;
                return this.packageName.equals(i.packageName);
            } else if(o instanceof String) {
                return this.packageName.equals(o);
            }
            return false;
        }

        @Override
        public String toString() {
            return packageName + " - " + publicLabel + ", n=" + launchedTimes;
        }

        @Override
        public int hashCode() {
            return packageName.hashCode();
        }
    }

    private class AppsHolder {

        private List<AppInfo> infos;
        private List<String> appLabels;
        private AppInfo[] suggestedApps = new AppInfo[SUGGESTED_APPS_LENGTH];

        Comparator<AppInfo> mostUsedComparator = new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo lhs, AppInfo rhs) {
                return rhs.launchedTimes > lhs.launchedTimes ? -1 : rhs.launchedTimes == lhs.launchedTimes ? 0 : 1;
            }
        };

//        workaround to check if in suggested apps there are duplicates
        private Handler handler = new Handler();
        private Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if(suggestedApps != null) {
                    if(duplicates()) {
                        fillSuggestions();
                    }
                }
                handler.postDelayed(runnable, 1000 * 60 * 2);
            }

            private boolean duplicates() {
                for (int count =0; count < suggestedApps.length; count++)
                    for (int count2 = count+1 ; count2 < suggestedApps.length; count2++)
                        if (count != count2 && suggestedApps[count] == suggestedApps[count2])
                            return true;
                return false;
            }
        };

        public AppsHolder(List<AppInfo> infos) {
            this.infos = infos;
            update(true);

            handler.postDelayed(runnable, 1000 * 60 * 5);
        }

        public void add(AppInfo info) {
            if(! infos.contains(info) ) {
                infos.add(info);
                update(false);
            }
        }

        public void remove(AppInfo info) {
            infos.remove(info);
            update(true);
        }

        public void updateSuggestion(AppInfo info) {
            int index = suggestionIndex(info);

            if(index == -1) {
                attemptInsertSuggestion(info);
            } else if(index == 0) {
                return;
            } else {
                for(int count = 0; count < index; count++) {
                    if(suggestedApps[count] == null) {
                        if(count == lastNull()) {
                            suggestedApps[count] = info;
                            return;
                        }
                    } else if(suggestedApps[count].launchedTimes < info.launchedTimes) {

                        System.arraycopy(suggestedApps, count, suggestedApps, count + 1, index - count);
                        suggestedApps[count] = info;

                        return;
                    }
                }
            }
        }

        private int suggestionIndex(AppInfo app) {
            for(int count = 0; count < suggestedApps.length; count++) {
                if(app.equals(suggestedApps[count])) {
                    return count;
                }
            }
            return -1;
        }

        private void sort() {
            try {
                Collections.sort(infos, mostUsedComparator);
            } catch (NullPointerException e) {}
        }

        private void fillLabels() {
            appLabels = AppUtils.labelList(infos);
        }

        private void fillSuggestions() {
            suggestedApps = new AppInfo[SUGGESTED_APPS_LENGTH];
            for(AppInfo info : infos) {
                attemptInsertSuggestion(info);
            }
        }

        private void attemptInsertSuggestion(AppInfo info) {
            if(info.launchedTimes == 0){
                return;
            }
            for(int count = 0; count < suggestedApps.length; count++) {
                if(suggestedApps[count] == null) {
                    suggestedApps[count] = info;
                    return;
                } else {
                    if(info.launchedTimes > suggestedApps[count].launchedTimes) {
                        System.arraycopy(suggestedApps, count, suggestedApps, count + 1, suggestedApps.length - (count + 1));
                        suggestedApps[count] = info;
                        return;
                    }
                }
            }
        }

        private int lastNull() {
            for(int count = suggestedApps.length - 1; count >= 0; count--) {
                if(suggestedApps[count] == null) {
                    return count;
                }
            }
            return -1;
        }

        private void update(boolean refreshSuggestions) {
            AppUtils.checkEquality(infos);
            sort();
            fillLabels();
            if(refreshSuggestions) {
                fillSuggestions();
            }
        }

        public List<String> getAppLabels() {
            return appLabels;
        }

        public List<AppInfo> getApps() {
            return infos;
        }

        public String[] getSuggestedApps() {
            return AppUtils.labelList(suggestedApps);
        }

    }

    private static class AppUtils {

        public static void checkEquality(List<AppInfo> list) {

            for (AppInfo info : list) {

                if(info == null || info.publicLabel == null) {
                    continue;
                }

                for (int count = 0; count < list.size(); count++) {
                    AppInfo info2 = list.get(count);

                    if(info2 == null || info2.publicLabel == null) {
                        continue;
                    }

                    if(info == info2) {
                        continue;
                    }

                    if (info.publicLabel.toLowerCase().replace(Tuils.SPACE, Tuils.EMPTYSTRING).equals(info2.publicLabel.toLowerCase().replace(Tuils.SPACE, Tuils.EMPTYSTRING))) {
                        list.set(count, new AppInfo(info2.packageName, getNewLabel(info2.publicLabel, info2.packageName), info2.launchedTimes));
                    }
                }
            }
        }

        public static String getNewLabel(String oldLabel, String packageName) {
            try {

                int firstDot = packageName.indexOf(Tuils.DOT);
                if(firstDot == -1) {
//                    no dots in package name (nearly impossible)
                    return packageName;
                }
                firstDot++;

                int secondDot = packageName.substring(firstDot).indexOf(Tuils.DOT);
                String prefix;
                if(secondDot == -1) {
//                    only one dot, so two words. The first is most likely to be the company name
//                    facebook.messenger
//                    is better than
//                    messenger.facebook
                    prefix = packageName.substring(0, firstDot - 1);
                    prefix = prefix.substring(0,1).toUpperCase() + prefix.substring(1).toLowerCase();
                    return prefix + Tuils.SPACE + oldLabel;
                } else {
//                    two dots or more, the second word is the company name
                    prefix = packageName.substring(firstDot, secondDot + firstDot);
                    prefix = prefix.substring(0,1).toUpperCase() + prefix.substring(1).toLowerCase();
                    return prefix + Tuils.SPACE + oldLabel;
                }

            } catch (Exception e) {
                return packageName;
            }
        }

        protected static AppInfo findAppInfo(String packageName, List<AppInfo> infos) {
            for(AppInfo info : infos) {
                if(info.packageName.equals(packageName)) {
                    return info;
                }
            }
            return null;
        }

        public static String printApps(List<String> apps) {
            if(apps.size() == 0) {
                return apps.toString();
            }

            List<String> list = new ArrayList<>(apps);

            Collections.sort(list, new Comparator<String>() {
                @Override
                public int compare(String lhs, String rhs) {
                    return Compare.alphabeticCompare(lhs, rhs);
                }
            });

            Tuils.addPrefix(list, Tuils.DOUBLE_SPACE);
            Tuils.insertHeaders(list, false);
            return Tuils.toPlanString(list);
        }

        public static List<String> labelList(List<AppInfo> infos) {
            List<String> labels = new ArrayList<>();
            for (AppInfo info : infos) {
                labels.add(info.publicLabel);
            }
            Collections.sort(labels);
            return labels;
        }

        public static String[] labelList(AppInfo[] infos) {
            String[] labels = new String[infos.length];
            for(int count = 0; count < infos.length; count++) {
                if(infos[count] != null) {
                    labels[count] = infos[count].publicLabel;
                }
            }
            return labels;
        }
    }

}