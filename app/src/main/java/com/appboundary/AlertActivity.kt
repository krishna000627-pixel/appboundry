package com.appboundary

import android.app.Activity
import android.app.AlertDialog
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle

class AlertActivity : Activity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDialog(intent.getStringExtra(EXTRA_PACKAGE))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dialog?.dismiss()
        showDialog(intent.getStringExtra(EXTRA_PACKAGE))
    }

    private fun showDialog(targetPackage: String?) {
        val label = resolveLabel(targetPackage)
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(R.string.alert_title)
            .setMessage(getString(R.string.alert_message, label))
            .setCancelable(true)
            .setPositiveButton(R.string.alert_ok) { _, _ -> goHomeAndFinish() }
            .setOnCancelListener { goHomeAndFinish() }
            .create()
            .also { it.show() }
    }

    private fun resolveLabel(targetPackage: String?): String {
        if (targetPackage == null) return getString(R.string.fallback_app_name)
        return try {
            val info = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            targetPackage
        }
    }

    private fun goHomeAndFinish() {
        val service = BoundaryAccessibilityService.instance
        if (service != null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } else {
            // Fallback if the service handle is unavailable.
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PACKAGE = "extra_package"

        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
