package expo.modules.motionactivitytracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.functions.Coroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal const val ACTIVITY_TRANSITION_EVENT = "onMotionStateChange"
internal const val REQUEST_CODE = 1001
internal const val ACTIVITY_TRANSITION_ACTION = "com.motionactivitytracker.ACTIVITY_TRANSITION"
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


  private var receiver: BroadcastReceiver? = null
  private lateinit var context: Context

  private val pendingIntent: PendingIntent by lazy {
    val intent = Intent(ACTIVITY_TRANSITION_ACTION)
        .setPackage(context.packageName)

    PendingIntent.getBroadcast(
      context,
      REQUEST_CODE,
      intent,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
          PendingIntent.FLAG_UPDATE_CURRENT
      }
    )
  }

  override fun definition() = ModuleDefinition {
    Name("MotionActivityTracker")

    OnCreate {
      context = appContext.reactContext ?: throw Exceptions.ReactContextLost()
      registerReceiver()
    }

    OnDestroy {
      unregisterReceiver()
    }

    OnActivityEntersForeground {
      if (receiver == null) {
        registerReceiver()
      }
    }

    OnActivityEntersBackground {
      unregisterReceiver()
    }

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

    Function("simulateActivityTransition") {
      activityType: String,
      transitionType: String,
      timestamp: String,
      confidence: String,
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

  // REGISTER RECEIVER
  @SuppressLint("UnspecifiedRegisterReceiverFlag")
  private fun registerReceiver() {

    if (receiver != null) {
      Log.d(TAG, "Receiver already registered, skipping")
      return
    }

    receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val reactContext = appContext.reactContext
        if (reactContext == null) {
          Log.w(TAG, "React context is null, ignoring activity update")
          return
        }
        
        val events = mutableListOf<Map<String, Any>>()

        if (ActivityRecognitionResult.hasResult(intent)) {
          val result = ActivityRecognitionResult.extractResult(intent)
          if (result == null) {
              Log.w(TAG, "No activity recognition result found")
              return
          }
          val probableActivities = result.probableActivities
          val resultTime = result.time 

          events.addAll(probableActivities
          .filter { it.confidence > 50 }
          .map { activity: DetectedActivity ->
            mapOf(
              "activityType" to when (activity.type) {
                DetectedActivity.IN_VEHICLE -> ActivityType.AUTOMOTIVE
                DetectedActivity.WALKING -> ActivityType.WALKING
                DetectedActivity.RUNNING -> ActivityType.RUNNING
                DetectedActivity.ON_BICYCLE -> ActivityType.CYCLING
                DetectedActivity.STILL -> ActivityType.STATIONARY
                else -> ActivityType.UNKNOWN
              },
              "transitionType" to TransitionType.UNKNOWN, // Not available here
              "confidence" to activity.confidence,
              "timestamp" to resultTime
            )
          })
        }

        if (events.isNotEmpty()) {
          if (reactContext != null) {
            try {
              sendEvent(ACTIVITY_TRANSITION_EVENT, mapOf("events" to events))
            } catch (e: Exception) {
              Log.e(TAG, "Error sending event", e)
            }
          } else {
            Log.w(TAG, "ReactContext is null — skipping event emit")
          }
        }
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(
        receiver,
        IntentFilter(ACTIVITY_TRANSITION_ACTION),
        Context.RECEIVER_NOT_EXPORTED
      )
    } else {
      context.registerReceiver(
        receiver,
        IntentFilter(ACTIVITY_TRANSITION_ACTION),
      )
    }
  }

  private fun unregisterReceiver() {
    receiver?.let { 
        try {
            context.unregisterReceiver(it)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered, ignore
        }
        receiver = null
    }
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
