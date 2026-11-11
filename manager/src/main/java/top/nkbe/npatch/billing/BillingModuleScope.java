package top.nkbe.npatch.billing;

import android.content.pm.PackageManager;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class BillingModuleScope {
    private BillingModuleScope() {}

    public static boolean isEligible(PackageManager packages, String packageName) {
        try {
            var info = packages.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return info.metaData != null && isEligibleConfig(info.metaData.getString("npatch"));
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isEligibleConfig(String encoded) {
        if (encoded == null) return false;
        try {
            var config = new JSONObject(new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8));
            return config.optBoolean("useManager", false) && config.optBoolean("useMicroG", false)
                    && "app.revanced".equals(config.optString("microgVendor", "app.revanced"));
        } catch (JSONException | IllegalArgumentException e) {
            return false;
        }
    }
}
