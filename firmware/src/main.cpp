// Boîtier Relance pour Domyos Run100.
// Un aimant sur un rouleau et un capteur à effet Hall comptent les tours ; le boîtier publie la vitesse
// et la distance en Bluetooth au standard FTMS (Treadmill Data). Lecture seule : il ne commande jamais le tapis.

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>

#include "speed.h"

using namespace relance;

// Câblage : module Hall (A3144 / KY-003) alimenté en 5 V (VIN), signal sur GPIO 27, masse commune.
// Sa sortie est à collecteur ouvert : la résistance de tirage interne de l'ESP32 la ramène en 3,3 V.
static const int HALL_PIN = 27;
static const int LED_PIN = 2;  // LED bleue de la carte : clignote à chaque tour de rouleau
static const char* DEVICE_NAME = "Relance Run100";

// Service d'étalonnage propre à Relance : distance parcourue par la bande pour un tour de rouleau.
static const char* CALIB_SERVICE = "6f1e0001-5c3a-4b8e-9d2f-7a1c0e5b9a01";
static const char* CALIB_CHAR = "6f1e0002-5c3a-4b8e-9d2f-7a1c0e5b9a01";

static SpeedMeter meter;
static Preferences prefs;
static NimBLECharacteristic* treadmillData = nullptr;
static NimBLECharacteristic* calibChar = nullptr;
static volatile bool connected = false;

// Impulsions relevées sous interruption, consommées dans la boucle principale.
static volatile uint32_t pulseTimes[32];
static volatile uint8_t pulseHead = 0;
static uint8_t pulseTail = 0;

void IRAM_ATTR onHall() {
  pulseTimes[pulseHead] = millis();
  pulseHead = (pulseHead + 1) % 32;
}

static void writeUint32(NimBLECharacteristic* c, uint32_t v) {
  uint8_t b[4] = {(uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24)};
  c->setValue(b, 4);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server) override {
    connected = true;
    // On continue d'annoncer : une deuxième application (Zwift par exemple) peut se connecter.
    NimBLEDevice::startAdvertising();
  }
  void onDisconnect(NimBLEServer* server) override {
    connected = server->getConnectedCount() > 0;
    NimBLEDevice::startAdvertising();
  }
};

class CalibCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    std::string v = c->getValue();
    if (v.size() != 4) return;
    uint32_t um = (uint8_t)v[0] | ((uint8_t)v[1] << 8) | ((uint8_t)v[2] << 16) | ((uint32_t)(uint8_t)v[3] << 24);
    if (validUmPerRev(um)) {
      meter.setUmPerRev(um);
      prefs.putUInt("umPerRev", um);
      Serial.printf("Étalonnage enregistré : %.2f mm par tour\n", um / 1000.0);
    } else {
      Serial.printf("Étalonnage refusé (hors bornes) : %u µm\n", um);
    }
    writeUint32(c, meter.umPerRev());
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  pinMode(HALL_PIN, INPUT_PULLUP);

  prefs.begin("relance", false);
  meter.setUmPerRev(prefs.getUInt("umPerRev", DEFAULT_UM_PER_REV));
  Serial.printf("Relance Run100 : %.2f mm par tour de rouleau\n", meter.umPerRev() / 1000.0);

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* ftms = server->createService("1826");
  // Fitness Machine Feature : distance totale (bit 2) ; aucune fonction de commande.
  NimBLECharacteristic* feature = ftms->createCharacteristic("2ACC", NIMBLE_PROPERTY::READ);
  uint8_t features[8] = {0x04, 0, 0, 0, 0, 0, 0, 0};
  feature->setValue(features, 8);
  // Supported Speed Range : 0,8 à 14 km/h par pas de 0,1 (unités de 0,01 km/h).
  NimBLECharacteristic* range = ftms->createCharacteristic("2AD4", NIMBLE_PROPERTY::READ);
  uint8_t speedRange[6] = {80, 0, 0x78, 0x05, 10, 0};
  range->setValue(speedRange, 6);
  treadmillData = ftms->createCharacteristic("2ACD", NIMBLE_PROPERTY::NOTIFY);
  ftms->start();

  NimBLEService* calib = server->createService(CALIB_SERVICE);
  calibChar = calib->createCharacteristic(CALIB_CHAR, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  calibChar->setCallbacks(new CalibCallbacks());
  writeUint32(calibChar, meter.umPerRev());
  calib->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID("1826");
  // Service Data FTMS : machine disponible, type tapis de course (bit 0).
  uint8_t serviceData[3] = {0x01, 0x01, 0x00};
  adv->setServiceData(NimBLEUUID("1826"), std::string((char*)serviceData, 3));
  adv->setScanResponse(true);
  adv->start();

  attachInterrupt(digitalPinToInterrupt(HALL_PIN), onHall, FALLING);
  Serial.println("Annonce Bluetooth lancée.");
}

void loop() {
  while (pulseTail != pulseHead) {
    if (meter.pulse(pulseTimes[pulseTail])) digitalWrite(LED_PIN, !digitalRead(LED_PIN));
    pulseTail = (pulseTail + 1) % 32;
  }

  static uint32_t lastNotify = 0;
  uint32_t now = millis();
  if (now - lastNotify >= 1000) {
    lastNotify = now;
    double kmh = meter.speedKmh(now);
    if (kmh == 0.0) digitalWrite(LED_PIN, LOW);
    uint8_t frame[TREADMILL_FRAME_LEN];
    encodeTreadmillData(kmh, meter.totalMeters(), frame);
    treadmillData->setValue(frame, TREADMILL_FRAME_LEN);
    if (connected) treadmillData->notify();
    Serial.printf("%.2f km/h · %.0f m%s\n", kmh, meter.totalMeters(), connected ? " · connecté" : "");
  }
  delay(5);
}
