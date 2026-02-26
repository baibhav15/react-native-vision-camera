"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.useCameraDevices = useCameraDevices;
var _react = require("react");
var _CameraDevices = require("../CameraDevices");
/**
 * Get all available Camera Devices this phone has.
 *
 * Camera Devices attached to this phone (`back` or `front`) are always available,
 * while `external` devices might be plugged in or out at any point,
 * so the result of this function might update over time.
 */
function useCameraDevices() {
  const [devices, setDevices] = (0, _react.useState)(() => _CameraDevices.CameraDevices.getAvailableCameraDevices());
  const numberOfDevicesRef = (0, _react.useRef)(devices.length);
  (0, _react.useEffect)(() => {
    let isMounted = true;
    const listener = _CameraDevices.CameraDevices.addCameraDevicesChangedListener(newDevices => {
      setDevices(newDevices);
    });
    // Only update if we got new devices and the component is still mounted
    // This happens with Android only
    if (numberOfDevicesRef.current === 0) {
      _CameraDevices.CameraDevices.getAvailableCameraDevicesManually().then(newDevices => {
        if (isMounted && newDevices.length > 0) setDevices(newDevices);
      });
    }
    return () => {
      isMounted = false;
      listener.remove();
    };
  }, []);
  return devices;
}
//# sourceMappingURL=useCameraDevices.js.map