package expo.modules.smsaudit

import android.content.Intent
import com.berelson.smsaudit.MainActivity
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class SmsAuditModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("SmsAudit")
        Function("open") {
            val activity = appContext.currentActivity
                ?: throw IllegalStateException("Activity is not available")
            activity.runOnUiThread {
                activity.startActivity(Intent(activity, MainActivity::class.java))
            }
        }
    }
}
