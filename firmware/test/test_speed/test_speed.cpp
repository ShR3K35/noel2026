#include <unity.h>
#include "speed.h"

using namespace relance;

void setUp() {}
void tearDown() {}

// Simule une bande à vitesse constante pendant `ms` millisecondes.
static uint32_t run(SpeedMeter& m, double kmh, uint32_t startMs, uint32_t ms) {
  double periodMs = m.umPerRev() / 1e6 / (kmh / 3.6) * 1000.0;
  double t = startMs;
  while (t < startMs + ms) {
    m.pulse((uint32_t)t);
    t += periodMs;
  }
  return (uint32_t)t;
}

void test_constant_speed() {
  const double speeds[] = {1.0, 3.0, 5.0, 8.0, 12.0, 14.0};
  for (double v : speeds) {
    SpeedMeter m;
    uint32_t end = run(m, v, 1000, 10000);
    TEST_ASSERT_FLOAT_WITHIN(0.1, v, m.speedKmh(end));
  }
}

void test_stops_after_two_seconds() {
  SpeedMeter m;
  uint32_t end = run(m, 6.0, 0, 5000);
  TEST_ASSERT_TRUE(m.speedKmh(end + 500) > 5.5);
  TEST_ASSERT_EQUAL_FLOAT(0.0, m.speedKmh(end + 2500));
}

void test_no_pulse_means_zero() {
  SpeedMeter m;
  TEST_ASSERT_EQUAL_FLOAT(0.0, m.speedKmh(1000));
  m.pulse(1000);
  TEST_ASSERT_EQUAL_FLOAT(0.0, m.speedKmh(1100));
}

void test_bounces_are_ignored() {
  SpeedMeter m;
  uint32_t t = 0;
  for (int i = 0; i < 50; i++) {
    TEST_ASSERT_TRUE(m.pulse(t));
    TEST_ASSERT_FALSE(m.pulse(t + 2));  // rebond
    t += 100;
  }
  // 157,08 mm tous les 100 ms = 5,65 km/h
  TEST_ASSERT_FLOAT_WITHIN(0.05, 5.65, m.speedKmh(t - 100));
  TEST_ASSERT_FLOAT_WITHIN(0.01, 50 * 0.15708, m.totalMeters());
}

void test_speed_change_is_followed() {
  SpeedMeter m;
  uint32_t t = run(m, 5.0, 0, 6000);
  t = run(m, 10.0, t, 4000);
  TEST_ASSERT_FLOAT_WITHIN(0.15, 10.0, m.speedKmh(t));
}

void test_calibration_bounds() {
  SpeedMeter m;
  m.setUmPerRev(10);
  TEST_ASSERT_EQUAL_UINT32(DEFAULT_UM_PER_REV, m.umPerRev());
  m.setUmPerRev(125664);
  TEST_ASSERT_EQUAL_UINT32(125664, m.umPerRev());
}

void test_frame_encoding() {
  uint8_t f[TREADMILL_FRAME_LEN];
  encodeTreadmillData(7.0, 1234.6, f);
  const uint8_t expected[] = {0x04, 0x00, 0xBC, 0x02, 0xD2, 0x04, 0x00};
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, f, TREADMILL_FRAME_LEN);
  encodeTreadmillData(-1.0, -5.0, f);
  TEST_ASSERT_EQUAL_UINT8(0, f[2]);
  TEST_ASSERT_EQUAL_UINT8(0, f[4]);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_constant_speed);
  RUN_TEST(test_stops_after_two_seconds);
  RUN_TEST(test_no_pulse_means_zero);
  RUN_TEST(test_bounces_are_ignored);
  RUN_TEST(test_speed_change_is_followed);
  RUN_TEST(test_calibration_bounds);
  RUN_TEST(test_frame_encoding);
  return UNITY_END();
}
