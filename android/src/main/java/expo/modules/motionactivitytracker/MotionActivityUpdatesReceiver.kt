package expo.modules.motionactivitytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

class MotionActivityUpdatesReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    val result = ActivityRecognitionResult.extractResult(intent) ?: return
    val best = result.mostProbableActivity

    val rec = mapOf(
      "ts" to result.time,
      "type" to when (best.type) {
        DetectedActivity.WALKING     -> "WALK"
        DetectedActivity.RUNNING     -> "RUN"
        DetectedActivity.IN_VEHICLE  -> "AUTO"
        DetectedActivity.ON_BICYCLE  -> "BIKE"
        DetectedActivity.STILL       -> "STILL"
        else -> "UNK"
      },
      "conf" to best.confidence
    )

    MotionEventStore.append(ctx, rec) // writes to disk and enforces caps
  }
}
