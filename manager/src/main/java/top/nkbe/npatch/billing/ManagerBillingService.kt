package top.nkbe.npatch.billing

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.android.vending.billing.InAppBillingService
import org.microg.vending.billing.NPatchBillingBridge

/** Hosts genuine microG billing for clients that selected manager mode and microG. */
class ManagerBillingService : InAppBillingService() {
    override fun onCreate() {
        super.onCreate()
        NPatchBillingBridge.callerVerifier = NPatchBillingBridge.CallerVerifier { _, uid ->
            isAllowed(uid)
        }
        NPatchBillingBridge.originalVersionResolver =
            NPatchBillingBridge.OriginalVersionResolver { context, packageName ->
                OriginalBillingIdentity.versionCode(context, packageName)
            }
        NPatchBillingBridge.originalCertificateResolver =
            NPatchBillingBridge.OriginalCertificateResolver { context, packageName ->
                OriginalBillingIdentity.certificate(context, packageName)
            }
    }

    private fun isAllowed(uid: Int): Boolean = packageManager.getPackagesForUid(uid).orEmpty()
        .any { BillingModuleScope.isEligible(packageManager, it) }

    override fun onBind(intent: Intent): IBinder {
        val billing = super.onBind(intent)
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != INTERFACE_TRANSACTION && !isAllowed(getCallingUid())) {
                    throw SecurityException("Caller has not enabled NPatch manager mode with microG")
                }
                return billing.transact(code, data, reply, flags)
            }
        }
    }
}
