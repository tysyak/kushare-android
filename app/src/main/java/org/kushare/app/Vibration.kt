package org.kushare.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** El vibrador del teléfono en cualquier versión (VibratorManager es de Android 12). */
fun vibrator(ctx: Context): Vibrator =
    if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Vibrator::class.java)
    }

/** Vibra como una alarma (suena aunque el teléfono esté en silencio). */
fun vibrateAsAlarm(ctx: Context, effect: VibrationEffect) {
    val v = vibrator(ctx)
    if (Build.VERSION.SDK_INT >= 33) {
        v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    } else {
        @Suppress("DEPRECATION")
        v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }
}
