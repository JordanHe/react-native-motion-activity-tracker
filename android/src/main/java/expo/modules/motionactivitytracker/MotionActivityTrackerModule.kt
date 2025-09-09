package expo.modules.motionactivitytracker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.functions.Coroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal const val ACTIVITY_TRANSITION_EVENT = "onMotionStateChange"
internal const val REQUEST_CODE = 1001
internal const val TAG = "MotionActivityTracker"

class MotionActivityTrackerModule : Module() {

  enum class PermissionStatus {
    AUTHORIZED,
    DENIED,
    NOT_DETERMINED,
    UNAVAILABLE
  }

  enum class TrackingStatus {
    STARTED,
    STOPPED,
    FAILED,
    UNAUTHORIZED
  }

  enum class ActivityType {
    UNKNOWN,
    WALKING,
    RUNNING,
    AUTOMOTIVE,
    STATIONARY,
    CYCLING
  }

  enum class TransitionType {
    ENTER,
    EXIT,
    UNKNOWN
  }



  private lateinit var context: Context

  private val pendingIntent: PendingIntent by lazy {
    val intent = Intent(context, MotionActivityUpdatesReceiver::class.java)
    PendingIntent.getBroadcast(
      context,
      REQUEST_CODE,
      intent,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      else
        PendingIntent.FLAG_UPDATE_CURRENT
    )
  }

  override fun definition() = ModuleDefinition {
    Name("MotionActivityTracker")

    OnCreate {
      context = (appContext.reactContext ?: throw Exceptions.ReactContextLost()).applicationContext
    }

    // OnActivityEntersForeground {
    //   val events = MotionEventStore.readAllAndClear(context)
    //   if (events.isNotEmpty()) {
    //     sendEvent(ACTIVITY_TRANSITION_EVENT, mapOf("events" to events))
    //   }
    // }

    Constants{
      val reactContext = appContext.reactContext
      val availability = if (reactContext != null) {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(reactContext)
      } else {
        ConnectionResult.SERVICE_MISSING
      }
      val isGooglePlayServicesAvailable = availability == ConnectionResult.SUCCESS

      mapOf(
        "isGooglePlayServicesAvailable" to isGooglePlayServicesAvailable,
      )
    }

    Events(ACTIVITY_TRANSITION_EVENT)

    AsyncFunction("getPermissionStatus") {
      val status = checkPermissions()

      return@AsyncFunction when (status) {
          PackageManager.PERMISSION_GRANTED -> PermissionStatus.AUTHORIZED
          PackageManager.PERMISSION_DENIED -> PermissionStatus.DENIED
          4 -> PermissionStatus.UNAVAILABLE
          else -> PermissionStatus.NOT_DETERMINED
      }
    }

    AsyncFunction("startTracking") Coroutine  { ->
      return@Coroutine startActivityTransitionMonitoring()
    }

    AsyncFunction("stopTracking") Coroutine  { ->
      return@Coroutine stopActivityTransitionMonitoring()
    }

    AsyncFunction("drainMotionEvents") {
      return@AsyncFunction MotionEventStore.readAllAndClear(context)
    }

    Function("simulateActivityTransition") {
      activityType: String,
      transitionType: String,
      timestamp: String,
      confidence: Int,
      ->
      val events = listOf(
        mapOf(
          "activityType" to activityType,
          "transitionType" to transitionType,
          "timestamp" to timestamp.toLong(),
          "confidence" to confidence
        )
      )
      sendEvent(ACTIVITY_TRANSITION_EVENT,
        mapOf (
          "events" to events
      ))
    }
  }

  // CHECK PERMISSIONS
  // API v.29 is required for ACTIVITY_RECOGNITION
  private fun checkPermissions(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return  ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION
      )
    }
    return 4
  }


  // START/STOP MONITORING
  private suspend fun startActivityTransitionMonitoring(): TrackingStatus {
    if (ActivityCompat.checkSelfPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      return TrackingStatus.UNAUTHORIZED
    }

    return suspendCoroutine { continuation ->
    ActivityRecognition.getClient(context)
      .requestActivityUpdates(15000L, pendingIntent)
      .addOnSuccessListener {
        Log.i(TAG, "Successfully registered for activity updates")
        continuation.resume(TrackingStatus.STARTED)
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "Failed to register for activity updates", e)
        continuation.resume(TrackingStatus.FAILED)
      }
    }
  }

  private suspend fun stopActivityTransitionMonitoring(): TrackingStatus {

    if (ActivityCompat.checkSelfPermission(
          context,
          Manifest.permission.ACTIVITY_RECOGNITION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      return TrackingStatus.UNAUTHORIZED
    }

    return suspendCoroutine { continuation ->
      try {
        ActivityRecognition.getClient(context)
        .removeActivityUpdates(pendingIntent)
        .addOnSuccessListener {
          Log.i(TAG, "Successfully deregistered from activity updates")
          continuation.resume(TrackingStatus.STOPPED)
        }
        .addOnFailureListener { e ->
          Log.e(TAG, "Failed to deregister from activity updates", e)
          continuation.resume(TrackingStatus.FAILED)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Exception during activity recognition removal", e)
        continuation.resume(TrackingStatus.FAILED)
      }
    }
  }
}
