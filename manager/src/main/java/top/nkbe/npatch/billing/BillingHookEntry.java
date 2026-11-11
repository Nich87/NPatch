package top.nkbe.npatch.billing;

import android.app.Application;
import android.content.Context;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class BillingHookEntry implements IXposedHookLoadPackage {
    private static boolean installed;

    private static synchronized void install(Context context, String packageName) {
        if (!installed && packageName.equals(context.getPackageName())
                && BillingModuleScope.isEligible(context.getPackageManager(), packageName)) {
            installed = MicrogBillingRedirector.activate(context);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!param.isFirstApplication) return;
        Application application = AndroidAppHelper.currentApplication();
        if (application != null) {
            install(application, param.packageName);
            return;
        }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam hook) {
                install((Context) hook.args[0], param.packageName);
            }
        });
    }
}
