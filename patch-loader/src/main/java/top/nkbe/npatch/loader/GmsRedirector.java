package top.nkbe.npatch.loader;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import top.nkbe.npatch.share.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GmsRedirector {
    private static final String TAG = "NPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;
    private static final String CHOOSE_ACCOUNT_ACTION =
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT";
    private static final ComponentName SYSTEM_ACCOUNT_PICKER = ComponentName.unflattenFromString(
            "android/.accounts.ChooseTypeAndAccountActivity");

    // 鎖定社群主流的 MicroG 套件名稱
    private static final String[] MICROG_PACKAGES = {
            "app.revanced.android.gms",   // ReVanced GmsCore (推薦)
            "org.microg.gms",             // Original MicroG
    };

    private static String targetGms = null;
    private static String originalSignature;
    private static String vendorPackage = null;

    public static void activate(Context context, String origSig, String vendor, ClassLoader appClassLoader) {
        originalSignature = origSig;
        if (vendor != null && !vendor.isEmpty()) {
            vendorPackage = vendor + ".android.gms";
        }

        targetGms = findInstalledMicroG(context);
        if (targetGms == null) {
            Log.w(TAG, "No MicroG/GmsCore found! GMS redirect disabled.");
            return;
        }

        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + targetGms);
        setupC2dmRedirects();

        hookIntentSetPackage();
        hookIntentSetAction();
        hookIntentGetAction();
        hookIntentSetComponent();
        hookIntentResolve();
        hookContentResolverAcquire();
        hookPackageManagerGetPackageInfo(context);
        ClassLoader cl = appClassLoader != null ? appClassLoader : context.getClassLoader();

        Log.i(TAG, "GMS redirect hooks installed");
    }

    private static String findInstalledMicroG(Context context) {
        PackageManager pm = context.getPackageManager();
        // Vendor-specific package first (e.g. app.revanced.android.gms), then known community builds.
        if (vendorPackage != null) {
            try {
                pm.getPackageInfo(vendorPackage, 0);
                return vendorPackage;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        for (String pkg : MICROG_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    private static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg) || "com.google.android.gsf".equals(pkg)) {
            return targetGms;
        }
        return null;
    }

    // microG serves no Dynamite module, so redirecting chimera only made the
    // provider disagree with the caller's Uri. Left alone, real GMS answers it.
    private static final String CHIMERA_AUTHORITY = REAL_GMS + ".chimera";

    private static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (CHIMERA_AUTHORITY.equals(authority)) return null;
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        if (authority.startsWith("com.google.android.gsf")) {
            return authority.replace("com.google.android.gsf", targetGms);
        }
        return null;
    }

    // C2DM intent action -> vendor-namespaced equivalent, derived from targetGms.
    // Empty unless targetGms uses a vendor c2dm namespace (e.g. app.revanced.android.gms).
    private static String vendorC2dmPrefix = null;
    private static final Map<String, String> c2dmRedirectMap = new HashMap<>();
    private static final String[] C2DM_STANDARD_ACTIONS = {
            "com.google.android.c2dm.intent.REGISTER",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.android.c2dm.intent.UNREGISTER",
            "com.google.android.c2dm.intent.REGISTRATION",
    };

    /**
     * Derive the vendor c2dm action prefix from targetGms and populate c2dmRedirectMap.
     * e.g. "app.revanced.android.gms" -> "app.revanced.android.c2dm".
     * For forks that use the standard c2dm actions (e.g. org.microg.gms), the prefix
     * stays null and the map stays empty (no redirect).
     */
    private static void setupC2dmRedirects() {
        vendorC2dmPrefix = null;
        c2dmRedirectMap.clear();
        if (targetGms != null && targetGms.endsWith(".android.gms")) {
            vendorC2dmPrefix = targetGms.substring(0, targetGms.length() - ".android.gms".length()) + ".android.c2dm";
            for (String standard : C2DM_STANDARD_ACTIONS) {
                c2dmRedirectMap.put(standard, vendorC2dmPrefix + standard.substring("com.google.android.c2dm".length()));
            }
            Log.i(TAG, "C2DM redirect prefix derived: " + vendorC2dmPrefix);
        } else {
            Log.i(TAG, "C2DM redirect disabled for targetGms: " + targetGms);
        }
    }

    private static String redirectAction(String action) {
        if (action == null) return null;
        if (isChooseAccountAction(action)) return null;
        String redirected = c2dmRedirectMap.get(action);
        if (redirected != null) return redirected;
        if (targetGms != null && !REAL_GMS.equals(targetGms) && targetGms.endsWith(".android.gms")) {
            String prefix = "com.google.android.gms.";
            if (action.startsWith(prefix)) {
                String vendorAction = targetGms + action.substring(prefix.length() - 1);
                Log.d(TAG, "Redirecting GMS action: " + action + " -> " + vendorAction);
                return vendorAction;
            }
        }
        return null;
    }

    private static boolean isChooseAccountAction(String action) {
        return CHOOSE_ACCOUNT_ACTION.equals(action)
                || (targetGms != null
                && (targetGms + ".common.account.CHOOSE_ACCOUNT").equals(action));
    }

    private static void routeChooseAccountToSystem(Intent intent) {
        if (intent == null || SYSTEM_ACCOUNT_PICKER == null
                || !isChooseAccountAction(intent.getAction())) {
            return;
        }
        if (!SYSTEM_ACCOUNT_PICKER.equals(intent.getComponent()) || intent.getPackage() != null) {
            Log.d(TAG, "Routing CHOOSE_ACCOUNT directly to the system account picker");
            intent.setComponent(SYSTEM_ACCOUNT_PICKER);
            intent.setPackage(null);
        }
    }

    private static void hookIntentSetPackage() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    if (isChooseAccountAction(intent.getAction())) {
                        param.args[0] = null;
                        return;
                    }
                    String pkg = (String) param.args[0];
                    String redirected = redirectPackage(pkg);
                    if (redirected != null) param.args[0] = redirected;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setPackage", t);
        }
    }


    // Hook Intent.setAction to rewrite c2dm actions for microG-RE.
    private static void hookIntentSetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setAction", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String action = (String) param.args[0];
                    String redirected = redirectAction(action);
                    if (redirected != null) {
                        Log.d(TAG, "Redirecting c2dm action: " + action + " -> " + redirected);
                        param.args[0] = redirected;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    routeChooseAccountToSystem((Intent) param.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setAction", t);
        }
    }

    // Hook Intent.getAction to reverse-map only the incoming FCM RECEIVE action.
    // A vendor microG-RE (e.g. ReVanced GmsCore) delivers push messages with the
    // vendor-namespaced action "<vendor>.android.c2dm.intent.RECEIVE", but the
    // app's Firebase messaging code only recognizes the standard
    // "com.google.android.c2dm.intent.RECEIVE". This is a read-side shim only:
    // REGISTER/UNREGISTER/REGISTRATION are NOT reversed, so outgoing registration
    // keeps the standard -> vendor redirect.

    private static void hookIntentGetAction() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "getAction", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String action = (String) param.getResult();
                    if (vendorC2dmPrefix != null && action != null && action.equals(vendorC2dmPrefix + ".intent.RECEIVE")) {
                        String standard = "com.google.android.c2dm.intent.RECEIVE";
                        Log.d(TAG, "Redirecting c2dm action (getAction): " + action + " -> " + standard);
                        param.setResult(standard);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.getAction", t);
        }
    }

    private static void hookIntentSetComponent() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setComponent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ComponentName cn = (ComponentName) param.args[0];
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            param.args[0] = new ComponentName(redirected, cn.getClassName());
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    routeChooseAccountToSystem((Intent) param.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    private static void hookIntentResolve() {
        try {
            XposedBridge.hookAllConstructors(Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    ComponentName cn = intent.getComponent();
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            intent.setComponent(new ComponentName(redirected, cn.getClassName()));
                        }
                    }
                    String pkg = intent.getPackage();
                    if (pkg != null) {
                        String redirected = redirectPackage(pkg);
                        if (redirected != null) {
                            intent.setPackage(redirected);
                        }
                    }
                    // Rewrite c2dm actions passed via constructor
                    String action = intent.getAction();
                    if (action != null) {
                        String redirectedAction = redirectAction(action);
                        if (redirectedAction != null) {
                            Log.d(TAG, "Redirecting c2dm action (constructor): " + action + " -> " + redirectedAction);
                            intent.setAction(redirectedAction);
                        }
                    }
                    routeChooseAccountToSystem(intent);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent constructors", t);
        }
    }

    private static void hookContentResolverAcquire() {
        try {
            for (String method : new String[]{
                    "acquireProvider", "acquireContentProviderClient",
                    "acquireUnstableProvider", "acquireUnstableContentProviderClient"
            }) {
                try {
                    XposedBridge.hookAllMethods(ContentResolver.class, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Uri) {
                                Uri uri = (Uri) param.args[0];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[0] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[0] instanceof String) {
                                String newAuth = redirectAuthority((String) param.args[0]);
                                if (newAuth != null) {
                                    param.args[0] = newAuth;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // 攔截 ContentResolver.call，遇到 SecurityException 則自動重試
            try {
                XposedBridge.hookAllMethods(ContentResolver.class, "call", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Uri) {
                                Uri uri = (Uri) param.args[i];
                                String newAuth = redirectAuthority(uri.getAuthority());
                                if (newAuth != null) {
                                    param.args[i] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[i] instanceof String && i == 0) {
                                String newAuth = redirectAuthority((String) param.args[i]);
                                if (newAuth != null) {
                                    param.args[i] = newAuth;
                                }
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() instanceof SecurityException) {
                            String msg = param.getThrowable().getMessage();
                            if (msg != null && (msg.contains("GoogleCertificatesRslt") ||
                                    msg.contains("not allowed") ||
                                    msg.contains("Access denied"))) {
                                Log.i(TAG, "GMS rejected call, retrying with MicroG");
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Uri) {
                                        Uri uri = (Uri) param.args[i];
                                        String authority = uri.getAuthority();
                                        if (authority != null && authority.contains(REAL_GMS)) {
                                            param.args[i] = uri.buildUpon()
                                                    .authority(authority.replace(REAL_GMS, targetGms))
                                                    .build();
                                        }
                                    } else if (param.args[i] instanceof String && i == 0) {
                                        String s = (String) param.args[i];
                                        if (s.contains(REAL_GMS)) {
                                            param.args[i] = s.replace(REAL_GMS, targetGms);
                                        }
                                    }
                                }
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }

    private static void hookPackageManagerGetPackageInfo(Context context) {
        try {
            XposedHelpers.findAndHookMethod(
                    context.getPackageManager().getClass(),
                    "getPackageInfo",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Redirect package name query: "com.google.android.gms" -> microG-RE
                            String pkg = (String) param.args[0];
                            String redirected = redirectPackage(pkg);
                            if (redirected != null) {
                                Log.d(TAG, "Redirecting getPackageInfo: " + pkg + " -> " + redirected);
                                param.args[0] = redirected;
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            PackageInfo pi = (PackageInfo) param.getResult();
                            if (pi != null && targetGms != null) {
                                if (targetGms.equals(pi.packageName) && (((int) param.args[1]) & PackageManager.GET_SIGNATURES) != 0) {
                                    if (originalSignature != null && !originalSignature.isEmpty()) {
                                        try {
                                            pi.signatures = new Signature[]{new Signature(originalSignature)};
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PackageManager.getPackageInfo", t);
        }
    }
}