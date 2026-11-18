#include <unity.h>
#include <math.h>
#include "control.h"
#include "ftms.h"

using namespace relance;

void setUp() {}
void tearDown() {}

// Tapis simulé : la console change sa consigne de 0,1 km/h à chaque appui relâché ; la bande rejoint
// la consigne à 1 km/h par seconde. La mesure peut être faussée (étalonnage) et des appuis perdus.
struct Treadmill {
  double console = 0.0;      // consigne affichée par la console
  double belt = 0.0;         // vitesse réelle de la bande
  double measureFactor = 1.0;
  int missEvery = 0;         // 1 appui sur N ignoré par la console (0 : aucun)
  Button held = Button::None;
  uint32_t heldSince = 0;
  uint32_t releasedAt = 0;
  int presses = 0;
  int stops = 0;
  uint32_t shortestPress = 0xFFFFFFFF;
  uint32_t shortestGap = 0xFFFFFFFF;

  double measured() const { return belt * measureFactor; }

  void apply(Button b, uint32_t now) {
    if (b == held) return;
    if (held != Button::None) {  // relâchement : la console prend l'appui en compte
      uint32_t d = now - heldSince;
      if (d < shortestPress) shortestPress = d;
      presses++;
      bool missed = missEvery > 0 && presses % missEvery == 0;
      if (!missed) {
        if (held == Button::Plus && console > 0) console = fmin(14.0, console + 0.1);
        if (held == Button::Minus && console > 0) console = fmax(1.0, console - 0.1);
        if (held == Button::Stop) {
          console = 0.0;
          stops++;
        }
      }
      releasedAt = now;
    }
    if (b != Button::None) {
      if (releasedAt != 0 && now - releasedAt < shortestGap) shortestGap = now - releasedAt;
      heldSince = now;
    }
    held = b;
  }

  void tick(uint32_t dtMs) {
    double step = 1.0 * dtMs / 1000.0;
    if (belt < console) belt = fmin(console, belt + step);
    else if (belt > console) belt = fmax(console, belt - step);
  }
};

// Fait tourner contrôleur et tapis pendant `ms` ; renvoie le dernier événement non nul.
static ControlEvent run(SpeedController& c, Treadmill& t, uint32_t& now, uint32_t ms) {
  ControlEvent last = ControlEvent::None;
  uint32_t end = now + ms;
  while (now < end) {
    t.apply(c.update(t.measured(), now), now);
    ControlEvent e = c.takeEvent();
    if (e != ControlEvent::None) last = e;
    t.tick(10);
    now += 10;
  }
  return last;
}

static Treadmill runningAt(double kmh) {
  Treadmill t;
  t.console = kmh;
  t.belt = kmh;
  return t;
}

void test_speeds_up_to_target() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  uint32_t now = 1000;
  TEST_ASSERT_TRUE(c.setTarget(8.0, t.measured(), now) == SetResult::Accepted);
  ControlEvent e = run(c, t, now, 30000);
  TEST_ASSERT_TRUE(e == ControlEvent::Reached);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 8.0, t.console);
  TEST_ASSERT_EQUAL_INT(30, t.presses);
  TEST_ASSERT_TRUE(c.hasTarget());
}

void test_slows_down_to_target() {
  SpeedController c;
  Treadmill t = runningAt(10.0);
  uint32_t now = 1000;
  c.setTarget(6.5, t.measured(), now);
  TEST_ASSERT_TRUE(run(c, t, now, 30000) == ControlEvent::Reached);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 6.5, t.console);
}

void test_press_timing() {
  SpeedController c;
  Treadmill t = runningAt(3.0);
  uint32_t now = 1000;
  c.setTarget(4.0, t.measured(), now);
  run(c, t, now, 20000);
  TEST_ASSERT_TRUE(t.shortestPress >= PRESS_MS);
  TEST_ASSERT_TRUE(t.shortestGap >= RELEASE_MS);
}

void test_corrects_calibration_error() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  t.measureFactor = 1.04;  // le boîtier mesure 4 % trop vite
  uint32_t now = 1000;
  c.setTarget(9.0, t.measured(), now);
  TEST_ASSERT_TRUE(run(c, t, now, 60000) == ControlEvent::Reached);
  TEST_ASSERT_FLOAT_WITHIN(TOLERANCE_KMH, 9.0, t.measured());
}

void test_corrects_missed_presses() {
  SpeedController c;
  Treadmill t = runningAt(4.0);
  t.missEvery = 7;
  uint32_t now = 1000;
  c.setTarget(7.0, t.measured(), now);
  TEST_ASSERT_TRUE(run(c, t, now, 60000) == ControlEvent::Reached);
  TEST_ASSERT_FLOAT_WITHIN(TOLERANCE_KMH, 7.0, t.console);
}

void test_new_target_during_presses() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  uint32_t now = 1000;
  c.setTarget(9.0, t.measured(), now);
  run(c, t, now, 2000);  // 6 appuis : la console est à 5,6, la bande encore en retard
  TEST_ASSERT_FLOAT_WITHIN(0.01, 5.6, t.console);
  TEST_ASSERT_TRUE(c.setTarget(5.2, t.measured(), now) == SetResult::Accepted);
  TEST_ASSERT_TRUE(run(c, t, now, 40000) == ControlEvent::Reached);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 5.2, t.console);
  // Les appuis déjà faits sont comptés : 6 montées puis 4 descentes, sans correction.
  TEST_ASSERT_EQUAL_INT(10, t.presses);
}

void test_refuses_when_belt_stopped_or_out_of_range() {
  SpeedController c;
  TEST_ASSERT_TRUE(c.setTarget(5.0, 0.0, 1000) == SetResult::BeltStopped);
  TEST_ASSERT_TRUE(c.setTarget(0.5, 4.0, 1000) == SetResult::OutOfRange);
  TEST_ASSERT_TRUE(c.setTarget(14.5, 4.0, 1000) == SetResult::OutOfRange);
  TEST_ASSERT_TRUE(c.update(4.0, 1010) == Button::None);
}

void test_never_starts_belt() {
  // Consigne refusée bande arrêtée : aucun appui, même longtemps après.
  SpeedController c;
  Treadmill t;
  uint32_t now = 1000;
  c.setTarget(5.0, t.measured(), now);
  run(c, t, now, 10000);
  TEST_ASSERT_EQUAL_INT(0, t.presses);
  TEST_ASSERT_EQUAL_FLOAT(0.0, t.belt);
}

void test_manual_change_releases_control() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  uint32_t now = 1000;
  c.setTarget(6.0, t.measured(), now);
  run(c, t, now, 20000);
  int presses = t.presses;
  t.console = 9.0;  // la personne monte la vitesse sur la console
  TEST_ASSERT_TRUE(run(c, t, now, 10000) == ControlEvent::ManualOverride);
  TEST_ASSERT_FALSE(c.hasTarget());
  TEST_ASSERT_EQUAL_INT(presses, t.presses);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 9.0, t.console);
}

void test_belt_stopped_during_adjustment() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  uint32_t now = 1000;
  c.setTarget(10.0, t.measured(), now);
  run(c, t, now, 500);
  t.console = 0.0;  // clé de sécurité retirée
  t.belt = 0.0;
  TEST_ASSERT_TRUE(run(c, t, now, 5000) == ControlEvent::BeltStopped);
  int presses = t.presses;
  run(c, t, now, 5000);
  TEST_ASSERT_EQUAL_INT(presses, t.presses);
  TEST_ASSERT_FALSE(c.hasTarget());
}

void test_overspeed_presses_stop() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  t.measureFactor = 3.2;  // mesure aberrante : 16 km/h
  uint32_t now = 1000;
  TEST_ASSERT_TRUE(run(c, t, now, 5000) == ControlEvent::OverspeedStop);
  TEST_ASSERT_EQUAL_INT(1, t.stops);
}

void test_stop_and_nudge() {
  SpeedController c;
  Treadmill t = runningAt(5.0);
  uint32_t now = 1000;
  c.nudge(1, now);
  run(c, t, now, 1000);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 5.1, t.console);
  c.nudge(-2, now);
  run(c, t, now, 1000);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 4.9, t.console);
  c.setTarget(8.0, t.measured(), now);
  run(c, t, now, 800);
  c.stop(now);
  run(c, t, now, 5000);
  TEST_ASSERT_EQUAL_INT(1, t.stops);
  TEST_ASSERT_EQUAL_FLOAT(0.0, t.console);
  TEST_ASSERT_FALSE(c.hasTarget());
}

void test_ftms_control_point() {
  SpeedController c;
  bool granted = false;
  FtmsMessage status;
  // 6,50 km/h = 650 = 0x028A
  const uint8_t setSpeed[3] = {0x02, 0x8A, 0x02};
  FtmsMessage r = handleControlPoint(setSpeed, 3, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(0x80, r.data[0]);
  TEST_ASSERT_EQUAL_UINT8(0x02, r.data[1]);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_CONTROL_NOT_PERMITTED, r.data[2]);

  const uint8_t request[1] = {0x00};
  r = handleControlPoint(request, 1, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_SUCCESS, r.data[2]);
  TEST_ASSERT_TRUE(granted);

  r = handleControlPoint(setSpeed, 3, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_SUCCESS, r.data[2]);
  TEST_ASSERT_FLOAT_WITHIN(0.01, 6.5, c.target());
  TEST_ASSERT_EQUAL_INT(3, (int)status.len);
  TEST_ASSERT_EQUAL_UINT8(0x05, status.data[0]);
  TEST_ASSERT_EQUAL_UINT8(0x8A, status.data[1]);
  TEST_ASSERT_EQUAL_UINT8(0x02, status.data[2]);

  const uint8_t tooFast[3] = {0x02, 0xDC, 0x05};  // 15,00 km/h
  r = handleControlPoint(tooFast, 3, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_INVALID_PARAMETER, r.data[2]);

  SpeedController stopped;
  r = handleControlPoint(setSpeed, 3, granted, stopped, 0.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_OPERATION_FAILED, r.data[2]);

  const uint8_t start[1] = {0x07};
  r = handleControlPoint(start, 1, granted, stopped, 0.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_OPERATION_FAILED, r.data[2]);

  const uint8_t incline[3] = {0x03, 0x1E, 0x00};
  r = handleControlPoint(incline, 3, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_NOT_SUPPORTED, r.data[2]);

  const uint8_t stop[2] = {0x08, 0x01};
  r = handleControlPoint(stop, 2, granted, c, 5.0, 1000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_SUCCESS, r.data[2]);
  TEST_ASSERT_TRUE(c.update(5.0, 1010) == Button::Stop);
  TEST_ASSERT_EQUAL_UINT8(0x02, status.data[0]);

  const uint8_t reset[1] = {0x01};
  r = handleControlPoint(reset, 1, granted, c, 5.0, 2000, status);
  TEST_ASSERT_EQUAL_UINT8(ftms::RESULT_SUCCESS, r.data[2]);
  TEST_ASSERT_FALSE(granted);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_speeds_up_to_target);
  RUN_TEST(test_slows_down_to_target);
  RUN_TEST(test_press_timing);
  RUN_TEST(test_corrects_calibration_error);
  RUN_TEST(test_corrects_missed_presses);
  RUN_TEST(test_new_target_during_presses);
  RUN_TEST(test_refuses_when_belt_stopped_or_out_of_range);
  RUN_TEST(test_never_starts_belt);
  RUN_TEST(test_manual_change_releases_control);
  RUN_TEST(test_belt_stopped_during_adjustment);
  RUN_TEST(test_overspeed_presses_stop);
  RUN_TEST(test_stop_and_nudge);
  RUN_TEST(test_ftms_control_point);
  return UNITY_END();
}
