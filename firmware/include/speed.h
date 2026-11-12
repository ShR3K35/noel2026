// Logique de mesure du boîtier, sans dépendance matérielle : testable sur PC (pio test -e native).
#pragma once

#include <stdint.h>
#include <stddef.h>

namespace relance {

// Impulsions plus rapprochées que ceci : rebond du capteur, ignorées.
// À 14 km/h, un rouleau de 40 mm de diamètre fait un tour toutes les 32 ms environ.
constexpr uint32_t DEBOUNCE_MS = 5;
// Fenêtre de calcul de la vitesse.
constexpr uint32_t WINDOW_MS = 2000;
// Sans impulsion depuis ce délai, la bande est considérée à l'arrêt.
constexpr uint32_t STOP_MS = 2000;
// Valeur par défaut de la distance par tour : π × 50 mm, en micromètres.
constexpr uint32_t DEFAULT_UM_PER_REV = 157080;
// Bornes acceptées pour l'étalonnage : rouleau de 20 à 120 mm de diamètre.
constexpr uint32_t MIN_UM_PER_REV = 60000;
constexpr uint32_t MAX_UM_PER_REV = 380000;

constexpr size_t MAX_PULSES = 128;

inline bool validUmPerRev(uint32_t um) { return um >= MIN_UM_PER_REV && um <= MAX_UM_PER_REV; }

class SpeedMeter {
 public:
  explicit SpeedMeter(uint32_t umPerRev = DEFAULT_UM_PER_REV) : umPerRev_(umPerRev) {}

  void setUmPerRev(uint32_t um) {
    if (validUmPerRev(um)) umPerRev_ = um;
  }
  uint32_t umPerRev() const { return umPerRev_; }

  // Une impulsion du capteur (un tour de rouleau). Renvoie false si elle est ignorée comme rebond.
  bool pulse(uint32_t nowMs) {
    if (count_ > 0 && nowMs - last() < DEBOUNCE_MS) return false;
    times_[head_] = nowMs;
    head_ = (head_ + 1) % MAX_PULSES;
    if (count_ < MAX_PULSES) count_++;
    totalUm_ += umPerRev_;
    return true;
  }

  // Vitesse de la bande en km/h.
  double speedKmh(uint32_t nowMs) const {
    if (count_ < 2 || nowMs - last() > STOP_MS) return 0.0;
    // Intervalles entre les impulsions récentes, dans la fenêtre.
    uint32_t newest = last();
    uint32_t oldest = newest;
    size_t intervals = 0;
    for (size_t i = 1; i < count_; i++) {
      uint32_t t = at(i);
      if (newest - t > WINDOW_MS) break;
      oldest = t;
      intervals++;
    }
    if (intervals == 0) {
      // Une seule impulsion dans la fenêtre (marche très lente) : on prend le dernier intervalle.
      oldest = at(1);
      intervals = 1;
    }
    uint32_t span = newest - oldest;
    if (span == 0) return 0.0;
    double metersPerSecond = (double)intervals * umPerRev_ / 1e6 / (span / 1000.0);
    return metersPerSecond * 3.6;
  }

  double totalMeters() const { return totalUm_ / 1e6; }
  void resetDistance() { totalUm_ = 0; }

 private:
  uint32_t last() const { return at(0); }
  // i-ème impulsion en partant de la plus récente (0 = la plus récente).
  uint32_t at(size_t i) const { return times_[(head_ + MAX_PULSES - 1 - i) % MAX_PULSES]; }

  uint32_t umPerRev_;
  uint32_t times_[MAX_PULSES] = {0};
  size_t head_ = 0;
  size_t count_ = 0;
  uint64_t totalUm_ = 0;
};

// Trame FTMS « Treadmill Data » (0x2ACD) : vitesse instantanée et distance totale.
// Flags 0x0004 : vitesse (uint16, 0,01 km/h) puis distance totale (uint24, mètres). 7 octets au total.
constexpr size_t TREADMILL_FRAME_LEN = 7;

inline void encodeTreadmillData(double speedKmh, double totalMeters, uint8_t out[TREADMILL_FRAME_LEN]) {
  if (speedKmh < 0) speedKmh = 0;
  uint32_t speed = (uint32_t)(speedKmh * 100.0 + 0.5);
  if (speed > 0xFFFF) speed = 0xFFFF;
  uint32_t dist = totalMeters < 0 ? 0 : (uint32_t)totalMeters;
  if (dist > 0xFFFFFF) dist = 0xFFFFFF;
  out[0] = 0x04;
  out[1] = 0x00;
  out[2] = speed & 0xFF;
  out[3] = (speed >> 8) & 0xFF;
  out[4] = dist & 0xFF;
  out[5] = (dist >> 8) & 0xFF;
  out[6] = (dist >> 16) & 0xFF;
}

}  // namespace relance
