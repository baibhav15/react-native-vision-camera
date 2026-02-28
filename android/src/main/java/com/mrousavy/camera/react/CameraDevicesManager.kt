package com.mrousavy.camera.react

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.mrousavy.camera.core.CameraDeviceDetails
import com.mrousavy.camera.core.CameraQueues
import com.mrousavy.camera.core.extensions.await
import kotlinx.coroutines.*
import android.annotation.SuppressLint


class CameraDevicesManager(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    private const val TAG = "CameraDevices"
  }
  private val executor = CameraQueues.cameraExecutor
  private val coroutineScope = CoroutineScope(executor.asCoroutineDispatcher())
  private val cameraManager = reactContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  @Volatile private var cameraProvider: ProcessCameraProvider? = null
  @Volatile private var extensionsManager: ExtensionsManager? = null
  private val camerasReadyDeferred = CompletableDeferred<Unit>()


  private val callback = object : CameraManager.AvailabilityCallback() {
    private var deviceIds = cameraManager.cameraIdList.toMutableList()

    // Check if device is still physically connected (even if onCameraUnavailable() is called)
    private fun isDeviceConnected(cameraId: String): Boolean =
      try {
        cameraManager.getCameraCharacteristics(cameraId)
        true
      } catch (_: Throwable) {
        false
      }

    override fun onCameraAvailable(cameraId: String) {
      Log.i(TAG, "Camera #$cameraId is now available.")
      if (!camerasReadyDeferred.isCompleted && (cameraProvider?.availableCameraInfos?.isNotEmpty() == true)) {
        camerasReadyDeferred.complete(Unit)
      }
      if (!deviceIds.contains(cameraId)) {
        deviceIds.add(cameraId)
        sendAvailableDevicesChangedEvent()
      }
    }

    override fun onCameraUnavailable(cameraId: String) {
      Log.i(TAG, "Camera #$cameraId is now unavailable.")
      if (deviceIds.contains(cameraId) && !isDeviceConnected(cameraId)) {
        deviceIds.remove(cameraId)
        sendAvailableDevicesChangedEvent()
      }
    }
  }

  override fun getName(): String = TAG

  // Init cameraProvider + manager as early as possible
  init {
    coroutineScope.launch {
      try {
        Log.i(TAG, "Initializing ProcessCameraProvider...")
        cameraProvider = ProcessCameraProvider.getInstance(reactContext).await(executor)
        Log.i(TAG, "Initializing ExtensionsManager...")

        // ExtensionsManager is OPTIONAL — failure here should not block camera enumeration
        try {
          extensionsManager = ExtensionsManager.getInstanceAsync(reactContext, cameraProvider!!).await(executor)
          Log.i(TAG, "ExtensionsManager initialized.")
        } catch (e: Throwable) {
          Log.w(TAG, "ExtensionsManager unavailable: ${e.message}")
          extensionsManager = null
        }
        // Wait until CameraX has populated availableCameraInfos
        waitForCameras()
        if (!camerasReadyDeferred.isCompleted)
          camerasReadyDeferred.complete(Unit)
        sendAvailableDevicesChangedEvent()
      } catch (e: Throwable) {
        Log.e(TAG, "Camera initialization failed: ${e.message}", e)
        // Always complete to avoid blocking ensureInitialized indefinitely
        if (!camerasReadyDeferred.isCompleted)
          camerasReadyDeferred.complete(Unit)
      }
    }
  }

  private suspend fun waitForCameras() {
    var attempts = 0
    while (attempts < 20) {
      val count = cameraProvider?.availableCameraInfos?.size ?: 0
      Log.d(TAG, "availableCameraInfos size: $count (attempt $attempts)")
      if (count > 0) return
      delay(250)
      attempts++
    }
    Log.w(TAG, "waitForCameras timed out — proceeding with whatever is available")
  }

  // Note: initialize() will be called after getConstants on new arch!
  override fun initialize() {
    super.initialize()
    cameraManager.registerAvailabilityCallback(callback, null)
    sendAvailableDevicesChangedEvent()
  }

  override fun invalidate() {
    cameraManager.unregisterAvailabilityCallback(callback)
    super.invalidate()
  }

  @SuppressLint("UnsafeOptInUsageError")
  private fun getDevicesJson(): ReadableArray {
    val devices = Arguments.createArray()
    val provider = cameraProvider ?: return devices

    provider.availableCameraInfos.forEach { cameraInfo ->
      try {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val cameraId = camera2Info.cameraId

        // Skip if physically not accessible
        try {
          cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: Throwable) {
          Log.w(TAG, "Skipping disconnected camera: $cameraId")
          return@forEach
        }        

        val deviceDetails = CameraDeviceDetails(cameraInfo, extensionsManager)
        devices.pushMap(deviceDetails.toMap())
      } catch (e: Throwable) {
        Log.w(TAG, "Error enumerating camera: ${e.message}")
      }
    }
    return devices
  }


  fun sendAvailableDevicesChangedEvent(retryCount: Int = 0) {
    if (!reactContext.hasActiveReactInstance()) {
      if (retryCount >= 10) return
      coroutineScope.launch {
        delay(500)
        sendAvailableDevicesChangedEvent(retryCount + 1)
      }
      return
    }
    try {
      val devices = getDevicesJson()
      if (devices.size() == 0 && !camerasReadyDeferred.isCompleted) return
      val emitter = reactContext.getJSModule(RCTDeviceEventEmitter::class.java)
      emitter.emit("CameraDevicesChanged", devices)
    } catch (e: Exception) {
      Log.w(TAG, "Emit failed: ${e.message}")
    }
  }

  override fun getConstants(): MutableMap<String, Any?> {
    val devices = getDevicesJson()
    val preferredDevice = if (devices.size() > 0) devices.getMap(0) else null

    return mutableMapOf(
      "availableCameraDevices" to devices,
      "userPreferredCameraDevice" to preferredDevice?.toHashMap()
    )
  }

  // Required for NativeEventEmitter, this is just a dummy implementation:
  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactMethod
  fun addListener(eventName: String) {}

  @Suppress("unused", "UNUSED_PARAMETER")
  @ReactMethod
  fun removeListeners(count: Int) {}

  private suspend fun ensureInitialized() {
    camerasReadyDeferred.await()
    if (cameraProvider == null) {
      try {
        cameraProvider = ProcessCameraProvider.getInstance(reactContext).await(executor)
      } catch (e: Throwable) {
        Log.w(TAG, "ensureInitialized: failed to reinit ProcessCameraProvider: ${e.message}")
        // leave provider null — callers will get empty list but won't hang
      }
    }
    if (extensionsManager == null && cameraProvider != null) {
      try {
        extensionsManager = ExtensionsManager.getInstanceAsync(reactContext, cameraProvider!!).await(executor)
        Log.i(TAG, "ensureInitialized: ExtensionsManager re-initialized.")
      } catch (_: Throwable) {
        // ignore — extensions are optional
      }
    }
    if (cameraProvider?.availableCameraInfos?.isEmpty() == true) {
        waitForCameras() 
    }
  }

  @ReactMethod
  fun getAvailableDeviceManually(promise: Promise) {
    coroutineScope.launch {
      try {
        ensureInitialized()
        val devices = getDevicesJson()
        promise.resolve(devices)
      } catch (t: Throwable) {
        promise.resolve(Arguments.createArray())
      }
    }
  }
}
