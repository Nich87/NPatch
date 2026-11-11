package top.nkbe.npatch.billing;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import top.nkbe.npatch.BuildConfig;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Preserves BillingClient identity while binding to manager billing. */
final class MicrogBillingRedirector {
    private static final String TAG = "NPatch-MicrogBilling";
    private static final String PLAY_STORE = "com.android.vending";
    private static final String BILLING_ACTION = "com.android.vending.billing.InAppBillingService.BIND";
    private static final String BILLING_SERVICE = "top.nkbe.npatch.billing.ManagerBillingService";
    private static final String QUERY_REDIRECTED = "npatch.microgBillingQuery";

    private MicrogBillingRedirector() {}

    static Intent redirectIntent(Intent intent, ComponentName manager) {
        if (intent == null || manager == null || !BILLING_ACTION.equals(intent.getAction())) {
            return intent;
        }
        ComponentName component = intent.getComponent();
        String pkg = intent.getPackage();
        if ((pkg != null && !PLAY_STORE.equals(pkg))
                || (component != null && !PLAY_STORE.equals(component.getPackageName()))) {
            return intent;
        }
        Intent redirected = new Intent(intent).setPackage(manager.getPackageName());
        if (component != null) {
            redirected.setComponent(manager);
        }
        return redirected;
    }

    static List<ResolveInfo> redirectServiceResults(List<ResolveInfo> results, ComponentName manager) {
        if (results == null || results.isEmpty() || manager == null) return results;
        List<ResolveInfo> redirected = new ArrayList<>(results.size());
        for (ResolveInfo result : results) {
            ServiceInfo service = result.serviceInfo;
            if (service == null || !manager.getPackageName().equals(service.packageName)
                    || !manager.getClassName().equals(service.name)) {
                redirected.add(result);
                continue;
            }
            ResolveInfo copy = new ResolveInfo(result);
            copy.serviceInfo = new ServiceInfo(service);
            copy.serviceInfo.packageName = PLAY_STORE;
            if (service.applicationInfo != null) {
                copy.serviceInfo.applicationInfo = new ApplicationInfo(service.applicationInfo);
                copy.serviceInfo.applicationInfo.packageName = PLAY_STORE;
            }
            redirected.add(copy);
        }
        return redirected;
    }

    static boolean activate(Context context) {
        ComponentName manager = new ComponentName(BuildConfig.APPLICATION_ID, BILLING_SERVICE);
        try {
            ServiceInfo service = context.getPackageManager().getServiceInfo(manager, 0);
            if (!service.enabled || !service.exported || service.applicationInfo == null
                    || !service.applicationInfo.enabled) return false;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Manager billing service is unavailable", e);
            return false;
        }

        Set<XC_MethodHook.Unhook> hooks = new HashSet<>();
        try {
            hooks.addAll(XposedBridge.hookAllMethods(context.getPackageManager().getClass(), "queryIntentServices",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof Intent intent)) return;
                            Intent redirected = redirectIntent(intent, manager);
                            if (redirected != intent) {
                                param.args[0] = redirected;
                                param.setObjectExtra(QUERY_REDIRECTED, Boolean.TRUE);
                            }
                        }

                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getObjectExtra(QUERY_REDIRECTED))
                                    && !param.hasThrowable() && param.getResult() instanceof List<?>) {
                                // BillingClient 8 checks this exact package before constructing its bind Intent.
                                param.setResult(redirectServiceResults((List<ResolveInfo>) param.getResult(), manager));
                            }
                        }
                    }));
            if (hooks.isEmpty()) throw new IllegalStateException("No billing service query methods found");
            XC_MethodHook bind = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof Intent intent) {
                            param.args[i] = redirectIntent(intent, manager);
                            break;
                        }
                    }
                }
            };
            for (Class<?> type : new Class<?>[]{Class.forName("android.app.ContextImpl"), ContextWrapper.class}) {
                Set<XC_MethodHook.Unhook> binding = XposedBridge.hookAllMethods(type, "bindService", bind);
                hooks.addAll(binding);
                if (binding.isEmpty()) throw new IllegalStateException("No bindService methods found on " + type);
                hooks.addAll(XposedBridge.hookAllMethods(type, "bindServiceAsUser", bind));
            }
            Log.i(TAG, "Billing service routed to manager: " + manager.flattenToShortString());
            return true;
        } catch (Throwable t) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            Log.e(TAG, "Failed to install billing compatibility hooks", t);
            return false;
        }
    }
}
