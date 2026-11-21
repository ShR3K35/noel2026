// Réglage de la vitesse du tapis par appuis simulés sur les touches + et − de la console (relais statiques PhotoMOS).
// Sans dépendance matérielle : testable sur PC (pio test -e native).
//
// Le boîtier ne connaît pas la consigne affichée par la console : il part de la vitesse mesurée par le
// capteur Hall, appuie autant de fois que nécessaire (pas de 0,1 km/h), attend que la bande se stabilise,
// puis corrige au plus MAX_CORRECTIONS fois. Il ne démarre jamais le tapis (la touche start n'est pas câblée).
#pragma once

#include <stdint.h>
#include <math.h>

namespace relance {

// Durée d'un appui, puis du relâchement avant l'appui suivant. Appuis courts : un appui long déclenche
// souvent le défilement rapide de la console.
constexpr uint32_t PRESS_MS = 150;
constexpr uint32_t RELEASE_MS = 200;
// Pas de la console à chaque appui.
constexpr double STEP_KMH = 0.1;
// Consignes acceptées (plage du Run100).
constexpr double MIN_TARGET_KMH = 1.0;
constexpr double MAX_TARGET_KMH = 14.0;
// En dessous, la bande est considérée à l'arrêt : aucune consigne n'est acceptée.
constexpr double RUNNING_KMH = 0.5;
// Stabilisation après une série d'appuis : au moins SETTLE_MIN_MS, mesure stable (à STABLE_KMH près)
// depuis STABLE_MS, et au plus SETTLE_MAX_MS.
constexpr uint32_t SETTLE_MIN_MS = 3000;
constexpr uint32_t STABLE_MS = 2500;
constexpr double STABLE_KMH = 0.1;
constexpr uint32_t SETTLE_MAX_MS = 20000;
// Écart accepté entre consigne et mesure, et nombre de séries de correction.
constexpr double TOLERANCE_KMH = 0.15;
constexpr int MAX_CORRECTIONS = 2;
// Consigne atteinte : un écart plus grand que ceci pendant OVERRIDE_MS veut dire que quelqu'un a changé
// la vitesse sur la console. Le boîtier lâche alors la main.
constexpr double OVERRIDE_KMH = 0.6;
constexpr uint32_t OVERRIDE_MS = 3000;
// Sécurité : au-delà de cette vitesse mesurée pendant OVERSPEED_MS, le boîtier appuie sur stop.
constexpr double OVERSPEED_KMH = MAX_TARGET_KMH + 1.0;
constexpr uint32_t OVERSPEED_MS = 2000;
// Nombre maximal d'appuis pour une consigne (de 1 à 14 km/h : 130).
constexpr int MAX_PRESSES = 140;

enum class Button : uint8_t { None, Plus, Minus, Stop };

enum class SetResult : uint8_t { Accepted, BeltStopped, OutOfRange };

enum class ControlEvent : uint8_t {
  None,
  Reached,         // consigne atteinte (ou corrections épuisées)
  BeltStopped,     // la bande s'est arrêtée pendant le réglage (stop console, clé retirée)
  ManualOverride,  // vitesse changée sur la console : consigne abandonnée
  OverspeedStop,   // vitesse excessive : stop appuyé
};

class SpeedController {
 public:
  // Nouvelle consigne en km/h, arrondie au pas de la console.
  SetResult setTarget(double kmh, double measuredKmh, uint32_t nowMs) {
    if (!(kmh >= MIN_TARGET_KMH - 1e-6 && kmh <= MAX_TARGET_KMH + 1e-6)) return SetResult::OutOfRange;
    if (measuredKmh < RUNNING_KMH) return SetResult::BeltStopped;
    if (state_ == State::Stopping) return SetResult::BeltStopped;
    target_ = roundStep(kmh);
    hasTarget_ = true;
    corrections_ = 0;
    // Pendant une série d'appuis, la mesure est en retard : on part de la consigne console estimée.
    if (state_ == State::Idle || state_ == State::Holding) expected_ = roundStep(measuredKmh);
    planPresses(nowMs);
    return SetResult::Accepted;
  }

  // Un appui sur + (n > 0) ou − (n < 0), sans consigne : pour vérifier le câblage.
  void nudge(int n, uint32_t nowMs) {
    if (state_ == State::Stopping || n == 0) return;
    hasTarget_ = false;
    remaining_ = n > 0 ? n : -n;
    if (remaining_ > MAX_PRESSES) remaining_ = MAX_PRESSES;
    next_ = n > 0 ? Button::Plus : Button::Minus;
    startPressing(nowMs);
  }

  // Appui sur stop : abandonne la consigne et les appuis en attente.
  void stop(uint32_t nowMs) {
    hasTarget_ = false;
    remaining_ = 0;
    down_ = Button::Stop;
    phaseStart_ = nowMs;
    isDown_ = true;
    state_ = State::Stopping;
  }

  // Abandonne la consigne sans toucher au tapis (déconnexion, remise à zéro).
  void cancel() {
    hasTarget_ = false;
    if (state_ != State::Stopping) {
      remaining_ = 0;
      // Un appui en cours se termine normalement (pas d'appui tronqué).
      state_ = isDown_ ? State::Pressing : State::Idle;
    }
  }

  // À appeler souvent (toutes les 5 à 20 ms). Renvoie la touche à tenir enfoncée maintenant.
  Button update(double measuredKmh, uint32_t nowMs) {
    checkOverspeed(measuredKmh, nowMs);
    switch (state_) {
      case State::Idle:
        break;
      case State::Pressing:
      case State::Stopping:
        stepPress(nowMs);
        if (state_ == State::Pressing && hasTarget_ && measuredKmh < RUNNING_KMH && !isDown_) {
          abandon(ControlEvent::BeltStopped);
        }
        break;
      case State::Settling:
        settle(measuredKmh, nowMs);
        break;
      case State::Holding:
        hold(measuredKmh, nowMs);
        break;
    }
    return isDown_ ? down_ : Button::None;
  }

  ControlEvent takeEvent() {
    ControlEvent e = event_;
    event_ = ControlEvent::None;
    return e;
  }

  bool hasTarget() const { return hasTarget_; }
  double target() const { return target_; }
  bool busy() const { return state_ == State::Pressing || state_ == State::Settling || state_ == State::Stopping; }

 private:
  enum class State : uint8_t { Idle, Pressing, Settling, Holding, Stopping };

  static double roundStep(double kmh) { return round(kmh / STEP_KMH) * STEP_KMH; }

  void planPresses(uint32_t nowMs) {
    // L'appui en cours (enfoncé) sera compté à son relâchement : on l'intègre déjà.
    double base = expected_;
    if (isDown_ && down_ == Button::Plus) base += STEP_KMH;
    if (isDown_ && down_ == Button::Minus) base -= STEP_KMH;
    int n = (int)lround((target_ - base) / STEP_KMH);
    if (n > MAX_PRESSES) n = MAX_PRESSES;
    if (n < -MAX_PRESSES) n = -MAX_PRESSES;
    remaining_ = n > 0 ? n : -n;
    next_ = n >= 0 ? Button::Plus : Button::Minus;
    if (remaining_ == 0 && !isDown_) {
      startSettling(nowMs);
    } else if (state_ != State::Pressing) {
      startPressing(nowMs);
    }
  }

  void startPressing(uint32_t nowMs) {
    state_ = State::Pressing;
    if (!isDown_) {
      // Phase relâchée déjà écoulée : le prochain appui part tout de suite.
      phaseStart_ = nowMs - RELEASE_MS;
    }
  }

  void startSettling(uint32_t nowMs) {
    state_ = State::Settling;
    settleStart_ = nowMs;
    stableSince_ = nowMs;
    stableRef_ = -1.0;
  }

  void stepPress(uint32_t nowMs) {
    if (isDown_) {
      if (nowMs - phaseStart_ < PRESS_MS) return;
      isDown_ = false;
      phaseStart_ = nowMs;
      if (down_ == Button::Plus) expected_ += STEP_KMH;
      if (down_ == Button::Minus) expected_ -= STEP_KMH;
      if (down_ == Button::Stop) {
        state_ = State::Idle;
        return;
      }
      if (remaining_ == 0) {
        if (hasTarget_) startSettling(nowMs);
        else state_ = State::Idle;
      }
      return;
    }
    if (nowMs - phaseStart_ < RELEASE_MS) return;
    if (remaining_ == 0) {
      if (hasTarget_) startSettling(nowMs);
      else state_ = State::Idle;
      return;
    }
    remaining_--;
    down_ = next_;
    isDown_ = true;
    phaseStart_ = nowMs;
  }

  void settle(double measuredKmh, uint32_t nowMs) {
    if (measuredKmh < RUNNING_KMH) {
      abandon(ControlEvent::BeltStopped);
      return;
    }
    if (stableRef_ < 0 || fabs(measuredKmh - stableRef_) > STABLE_KMH) {
      stableRef_ = measuredKmh;
      stableSince_ = nowMs;
    }
    uint32_t elapsed = nowMs - settleStart_;
    bool stable = elapsed >= SETTLE_MIN_MS && nowMs - stableSince_ >= STABLE_MS;
    if (!stable && elapsed < SETTLE_MAX_MS) return;
    double err = target_ - measuredKmh;
    if (fabs(err) >= TOLERANCE_KMH && corrections_ < MAX_CORRECTIONS) {
      corrections_++;
      expected_ = roundStep(measuredKmh);
      planPresses(nowMs);
      return;
    }
    state_ = State::Holding;
    expected_ = target_;
    overrideSince_ = 0;
    event_ = ControlEvent::Reached;
  }

  void hold(double measuredKmh, uint32_t nowMs) {
    if (measuredKmh < RUNNING_KMH) {
      abandon(ControlEvent::BeltStopped);
      return;
    }
    if (fabs(measuredKmh - target_) <= OVERRIDE_KMH) {
      overrideSince_ = 0;
      return;
    }
    if (overrideSince_ == 0) overrideSince_ = nowMs ? nowMs : 1;
    if (nowMs - overrideSince_ >= OVERRIDE_MS) abandon(ControlEvent::ManualOverride);
  }

  void checkOverspeed(double measuredKmh, uint32_t nowMs) {
    if (measuredKmh <= OVERSPEED_KMH) {
      overspeedSince_ = 0;
      return;
    }
    if (overspeedSince_ == 0) overspeedSince_ = nowMs ? nowMs : 1;
    if (state_ != State::Stopping && nowMs - overspeedSince_ >= OVERSPEED_MS) {
      stop(nowMs);
      event_ = ControlEvent::OverspeedStop;
      overspeedSince_ = 0;
    }
  }

  void abandon(ControlEvent e) {
    hasTarget_ = false;
    remaining_ = 0;
    state_ = isDown_ ? State::Pressing : State::Idle;
    event_ = e;
  }

  State state_ = State::Idle;
  bool hasTarget_ = false;
  double target_ = 0.0;
  double expected_ = 0.0;
  int corrections_ = 0;
  int remaining_ = 0;
  Button next_ = Button::Plus;
  Button down_ = Button::None;
  bool isDown_ = false;
  uint32_t phaseStart_ = 0;
  uint32_t settleStart_ = 0;
  uint32_t stableSince_ = 0;
  double stableRef_ = -1.0;
  uint32_t overrideSince_ = 0;
  uint32_t overspeedSince_ = 0;
  ControlEvent event_ = ControlEvent::None;
};

}  // namespace relance
