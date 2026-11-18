// FTMS Fitness Machine Control Point (0x2AD9) et Fitness Machine Status (0x2ADA).
// Sans dépendance matérielle : testable sur PC (pio test -e native).
#pragma once

#include <stdint.h>
#include <stddef.h>

#include "control.h"

namespace relance {

namespace ftms {
// Codes d'opération du Control Point.
constexpr uint8_t OP_REQUEST_CONTROL = 0x00;
constexpr uint8_t OP_RESET = 0x01;
constexpr uint8_t OP_SET_TARGET_SPEED = 0x02;
constexpr uint8_t OP_START_RESUME = 0x07;
constexpr uint8_t OP_STOP_PAUSE = 0x08;
constexpr uint8_t OP_RESPONSE = 0x80;
// Résultats.
constexpr uint8_t RESULT_SUCCESS = 0x01;
constexpr uint8_t RESULT_NOT_SUPPORTED = 0x02;
constexpr uint8_t RESULT_INVALID_PARAMETER = 0x03;
constexpr uint8_t RESULT_OPERATION_FAILED = 0x04;
constexpr uint8_t RESULT_CONTROL_NOT_PERMITTED = 0x05;
// Codes du Fitness Machine Status.
constexpr uint8_t STATUS_RESET = 0x01;
constexpr uint8_t STATUS_STOPPED_OR_PAUSED = 0x02;  // paramètre 0x01 = arrêt
constexpr uint8_t STATUS_TARGET_SPEED_CHANGED = 0x05;  // paramètre uint16, 0,01 km/h
}  // namespace ftms

// Message à envoyer (réponse ou statut) : jusqu'à 3 octets.
struct FtmsMessage {
  uint8_t data[3] = {0, 0, 0};
  size_t len = 0;
};

inline FtmsMessage statusTargetSpeed(double kmh) {
  FtmsMessage m;
  uint32_t v = kmh <= 0 ? 0 : (uint32_t)(kmh * 100.0 + 0.5);
  if (v > 0xFFFF) v = 0xFFFF;
  m.data[0] = ftms::STATUS_TARGET_SPEED_CHANGED;
  m.data[1] = v & 0xFF;
  m.data[2] = (v >> 8) & 0xFF;
  m.len = 3;
  return m;
}

inline FtmsMessage statusStopped() {
  FtmsMessage m;
  m.data[0] = ftms::STATUS_STOPPED_OR_PAUSED;
  m.data[1] = 0x01;
  m.len = 2;
  return m;
}

inline FtmsMessage statusReset() {
  FtmsMessage m;
  m.data[0] = ftms::STATUS_RESET;
  m.len = 1;
  return m;
}

// Traite une écriture sur le Control Point. `controlGranted` est propre à la connexion : une application
// doit d'abord demander la main (Request Control), comme le prévoit la norme FTMS.
// Renvoie la réponse à indiquer ; `status` reçoit un éventuel statut à notifier.
inline FtmsMessage handleControlPoint(const uint8_t* data, size_t len, bool& controlGranted,
                                      SpeedController& controller, double measuredKmh, uint32_t nowMs,
                                      FtmsMessage& status) {
  FtmsMessage r;
  status = FtmsMessage();
  if (len == 0) return r;
  uint8_t op = data[0];
  r.data[0] = ftms::OP_RESPONSE;
  r.data[1] = op;
  r.len = 3;
  auto result = [&](uint8_t code) {
    r.data[2] = code;
    return r;
  };

  if (op == ftms::OP_REQUEST_CONTROL) {
    controlGranted = true;
    return result(ftms::RESULT_SUCCESS);
  }
  if (op != ftms::OP_RESET && op != ftms::OP_SET_TARGET_SPEED && op != ftms::OP_START_RESUME &&
      op != ftms::OP_STOP_PAUSE) {
    return result(ftms::RESULT_NOT_SUPPORTED);
  }
  if (!controlGranted) return result(ftms::RESULT_CONTROL_NOT_PERMITTED);

  switch (op) {
    case ftms::OP_RESET:
      controller.cancel();
      controlGranted = false;
      status = statusReset();
      return result(ftms::RESULT_SUCCESS);

    case ftms::OP_SET_TARGET_SPEED: {
      if (len != 3) return result(ftms::RESULT_INVALID_PARAMETER);
      double kmh = (data[1] | (data[2] << 8)) / 100.0;
      SetResult s = controller.setTarget(kmh, measuredKmh, nowMs);
      if (s == SetResult::OutOfRange) return result(ftms::RESULT_INVALID_PARAMETER);
      if (s == SetResult::BeltStopped) return result(ftms::RESULT_OPERATION_FAILED);
      status = statusTargetSpeed(controller.target());
      return result(ftms::RESULT_SUCCESS);
    }

    case ftms::OP_START_RESUME:
      // Le boîtier ne démarre jamais le tapis : c'est à la personne d'appuyer sur start.
      return result(measuredKmh >= RUNNING_KMH ? ftms::RESULT_SUCCESS : ftms::RESULT_OPERATION_FAILED);

    case ftms::OP_STOP_PAUSE:
      if (len != 2 || (data[1] != 0x01 && data[1] != 0x02)) return result(ftms::RESULT_INVALID_PARAMETER);
      // Bande déjà à l'arrêt : pas d'appui (sur la console, stop à l'arrêt remet les compteurs à zéro).
      if (measuredKmh >= RUNNING_KMH) controller.stop(nowMs);
      else controller.cancel();
      status = statusStopped();
      return result(ftms::RESULT_SUCCESS);
  }
  return result(ftms::RESULT_NOT_SUPPORTED);
}

}  // namespace relance
