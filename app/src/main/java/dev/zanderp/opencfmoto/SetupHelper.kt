package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Readiness checks for the guided setup ([SetupActivity]).
 *
 * Some prerequisites are programmatically detectable (is Android Auto installed, are runtime
 * permissions granted); others are not (Android Auto "developer mode" + unknown head-unit
 * projection is buried in Gearhead's own settings with no public API), so for those we can only
 * deep-link the user to the right screen and explain what to toggle.
 */
object SetupHelper {
    const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"

    fun isAndroidAutoInstalled(ctx: Context): Boolean = packageInstalled(ctx, GEARHEAD_PACKAGE)

    fun isPlayServicesPresent(ctx: Context): Boolean = packageInstalled(ctx, PLAY_SERVICES_PACKAGE)

    private fun packageInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Permissions needed to join the bike and run the AA service (reconnect / auto-connect). */
    fun connectPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Camera is only required when scanning a new QR — not for Connect to a saved bike. */
    fun scanPermissions(): List<String> = listOf(Manifest.permission.CAMERA)

    /** Full first-run checklist (Setup screen). */
    fun requiredPermissions(): List<String> = (connectPermissions() + scanPermissions()).distinct()

    /**
     * `NEARBY_WIFI_DEVICES` on Android 13+ — the grant the two Wi-Fi Direct connectors (BLE / P2P) cannot
     * work without. Empty below API 33, where the permission does not exist as a runtime grant.
     *
     * Deliberately NOT part of [requiredPermissions]: it is not required to ride a SoftAP bike, so it must
     * never turn the setup checklist red (nor push anyone into onboarding) for riders who will never need
     * it. Its real home is pairing — `ui/scan/ScanScreen` asks for it when the bike's connector actually
     * uses Wi-Fi Direct — and [onboardingPermissions] simply lets first-run get it out of the way in the
     * same batch as the rest.
     */
    fun wifiDirectPermissions(): List<String> =
        if (NearbyDevices.required()) listOf(NearbyDevices.PERMISSION) else emptyList()

    /** What the first-run flows ASK for: the checklist plus the optional Wi-Fi Direct grant. */
    fun onboardingPermissions(): List<String> = (requiredPermissions() + wifiDirectPermissions()).distinct()

    fun missingPermissions(ctx: Context): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    fun missingConnectPermissions(ctx: Context): List<String> = connectPermissions().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    fun missingScanPermissions(ctx: Context): List<String> = scanPermissions().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }

    fun permissionsGranted(ctx: Context): Boolean = missingPermissions(ctx).isEmpty()

    /**
     * "Display over other apps" (overlay) is granted. Not required for normal use, but it exempts the
     * app from Android's background-activity-launch limits, so [AndroidAutoService] can relaunch Google
     * Android Auto by itself after a long outage — a fully seamless auto-resume with the phone stowed.
     * Without it, resume falls back to a tap-to-resume notification.
     */
    fun canAutoResume(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Everything we can verify is in place. Head-unit mode can't be verified, so it isn't gated. */
    fun coreReady(ctx: Context): Boolean =
        isAndroidAutoInstalled(ctx) && isPlayServicesPresent(ctx) && permissionsGranted(ctx)
}
