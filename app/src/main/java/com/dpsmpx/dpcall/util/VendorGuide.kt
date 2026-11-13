package com.dpsmpx.dpcall.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dpsmpx.dpcall.R

/**
 * Оболочки производителей, которые сверх обычной экономии батареи Android сами
 * закрывают фоновые приложения. На них «Без ограничений батареи» недостаточно:
 * нужен ещё автозапуск или отдельное исключение в собственных настройках.
 *
 * @property screens экраны собственных настроек оболочки, от наиболее подходящего;
 *   на разных версиях они называются по-разному, поэтому пробуются по очереди.
 * @property steps куда нажать, если ни один экран не открылся.
 */
enum class Vendor(val title: String, val screens: List<String>, val steps: Int) {
    XIAOMI(
        "Xiaomi", listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        ), R.string.vendor_steps_xiaomi
    ),
    HUAWEI(
        "Huawei", listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity"
        ), R.string.vendor_steps_huawei
    ),
    HONOR(
        "Honor", listOf(
            "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ), R.string.vendor_steps_huawei
    ),
    OPPO(
        "OPPO / realme / OnePlus", listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ), R.string.vendor_steps_oppo
    ),
    VIVO(
        "vivo / iQOO", listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ), R.string.vendor_steps_vivo
    ),
    SAMSUNG(
        "Samsung", listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm/com.samsung.android.sm.battery.ui.BatteryActivity"
        ), R.string.vendor_steps_samsung
    ),
    MEIZU("Meizu", listOf("com.meizu.safe/com.meizu.safe.permission.SmartBGActivity"), R.string.vendor_steps_meizu),
    ASUS(
        "ASUS", listOf(
            "com.asus.mobilemanager/com.asus.mobilemanager.powersaver.PowerSaverSettings",
            "com.asus.mobilemanager/com.asus.mobilemanager.autostart.AutoStartActivity"
        ), R.string.vendor_steps_asus
    );

    companion object {
        /** По производителю и бренду из [Build]; null — оболочка без своих ограничений. */
        fun detect(manufacturer: String, brand: String): Vendor? {
            val names = "${manufacturer.lowercase()} ${brand.lowercase()}"
            return when {
                listOf("xiaomi", "redmi", "poco").any { it in names } -> XIAOMI
                "honor" in names -> HONOR
                "huawei" in names -> HUAWEI
                listOf("oppo", "realme", "oneplus").any { it in names } -> OPPO
                listOf("vivo", "iqoo").any { it in names } -> VIVO
                "samsung" in names -> SAMSUNG
                "meizu" in names -> MEIZU
                "asus" in names -> ASUS
                else -> null
            }
        }

        fun current(): Vendor? = detect(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
    }
}

object VendorSettings {
    /**
     * Открывает экран автозапуска или фоновой работы оболочки; если ни один не открылся —
     * сведения о приложении, где на большинстве оболочек есть те же пункты.
     * @return true, если открылся собственный экран оболочки.
     */
    fun open(context: Context, vendor: Vendor): Boolean {
        for (screen in vendor.screens) {
            val component = ComponentName.unflattenFromString(screen) ?: continue
            try {
                context.startActivity(
                    Intent().setComponent(component)
                        .putExtra("package_name", context.packageName)
                        .putExtra("packageName", context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (e: Exception) {
                // Нет такого экрана в этой версии оболочки или он закрыт для сторонних приложений.
            }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
        }
        return false
    }
}
