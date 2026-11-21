// Boîtier Relance pour Domyos Run100.
// Un aimant sur un rouleau et un capteur à effet Hall comptent les tours ; le boîtier publie la vitesse
// et la distance en Bluetooth au standard FTMS (Treadmill Data).
// Option : trois optocoupleurs branchés sur les touches +, − et stop de la console lui permettent de régler
// la vitesse (FTMS Control Point, Set Target Speed). Il ne démarre jamais le tapis : start n'est pas câblé.

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>

#include "control.h"
#include "ftms.h"
#include "speed.h"

using namespace relance;

// Câblage : module Hall KY-003 alimenté en 3,3 V, signal sur GPIO 27, masse commune (voir docs/MONTAGE.md).
static const int HALL_PIN = 27;
static const int LED_PIN = 2;  // LED bleue de la carte : clignote à chaque tour de rouleau
// Touches de la console, par optocoupleur. L'entrée IN de chaque voie est au 5 V, sa borne G sur le GPIO :
// GPIO à l'état bas = touche appuyée, à l'état haut = relâchée.
static const int PLUS_PIN = 25;
static const int MINUS_PIN = 26;
static const int STOP_PIN = 32;
// Module à masse d'entrée commune (les G d'entrée des voies sont reliés) : G commun sur GND, IN de chaque voie
// sur son GPIO, et l'ESP32 appuie en mettant le GPIO à l'état haut. Activé par l'environnement
// esp32dev_masse_commune de platformio.ini.
#ifdef RELANCE_OPTO_MASSE_COMMUNE
static const uint8_t PRESSED = HIGH;
static const uint8_t RELEASED = LOW;
#else
static const uint8_t PRESSED = LOW;
static const uint8_t RELEASED = HIGH;
#endif
static const char* DEVICE_NAME = "Relance Run100";

// Service d'étalonnage propre à Relance : distance parcourue par la bande pour un tour de rouleau.
static const char* CALIB_SERVICE = "6f1e0001-5c3a-4b8e-9d2f-7a1c0e5b9a01";
static const char* CALIB_CHAR = "6f1e0002-5c3a-4b8e-9d2f-7a1c0e5b9a01";
// Appuis directs sur + ou −, pour tester les optocoupleurs depuis l'app : un octet signé, nombre d'appuis
// (positif : +, négatif : −), de 1 à MAX_NUDGE.
static const char* NUDGE_CHAR = "6f1e0003-5c3a-4b8e-9d2f-7a1c0e5b9a01";
static const int MAX_NUDGE = 10;

static const uint16_t NO_CONN = 0xFFFF;

static SpeedMeter meter;
static SpeedController controller;
static Preferences prefs;
static NimBLECharacteristic* treadmillData = nullptr;
static NimBLECharacteristic* calibChar = nullptr;
static NimBLECharacteristic* controlPoint = nullptr;
static NimBLECharacteristic* machineStatus = nullptr;
static volatile bool connected = false;
// Connexion qui a demandé la main sur le tapis (FTMS Request Control).
static uint16_t controlOwner = NO_CONN;

// Impulsions relevées sous interruption, consommées dans la boucle principale.
static volatile uint32_t pulseTimes[32];
static volatile uint8_t pulseHead = 0;
static uint8_t pulseTail = 0;

// Écritures sur le Control Point et déconnexions : reçues dans la tâche Bluetooth, traitées dans loop()
// pour que le réglage de vitesse ne soit manipulé que par une seule tâche.
struct BleEvent {
  uint8_t data[20];
  uint8_t len;
  uint16_t conn;
  bool disconnect;
  int8_t nudge;  // appuis demandés par la caractéristique de test, 0 pour une écriture FTMS
};
static QueueHandle_t bleEvents = nullptr;

void IRAM_ATTR onHall() {
  pulseTimes[pulseHead] = millis();
  pulseHead = (pulseHead + 1) % 32;
}

static void writeUint32(NimBLECharacteristic* c, uint32_t v) {
  uint8_t b[4] = {(uint8_t)v, (uint8_t)(v >> 8), (uint8_t)(v >> 16), (uint8_t)(v >> 24)};
  c->setValue(b, 4);
}

// Toutes les touches relâchées, puis au plus une enfoncée.
static void holdButton(Button b) {
  digitalWrite(PLUS_PIN, b == Button::Plus ? PRESSED : RELEASED);
  digitalWrite(MINUS_PIN, b == Button::Minus ? PRESSED : RELEASED);
  digitalWrite(STOP_PIN, b == Button::Stop ? PRESSED : RELEASED);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server) override {
    connected = true;
    // On continue d'annoncer : une deuxième application (Zwift par exemple) peut se connecter.
    NimBLEDevice::startAdvertising();
  }
  void onDisconnect(NimBLEServer* server, ble_gap_conn_desc* desc) override {
    connected = server->getConnectedCount() > 0;
    BleEvent e = {};
    e.conn = desc->conn_handle;
    e.disconnect = true;
    xQueueSend(bleEvents, &e, 0);
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

class ControlPointCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, ble_gap_conn_desc* desc) override {
    std::string v = c->getValue();
    BleEvent e = {};
    e.len = v.size() > sizeof(e.data) ? sizeof(e.data) : v.size();
    memcpy(e.data, v.data(), e.len);
    e.conn = desc->conn_handle;
    e.disconnect = false;
    xQueueSend(bleEvents, &e, 0);
  }
};

class NudgeCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, ble_gap_conn_desc* desc) override {
    std::string v = c->getValue();
    if (v.size() != 1) return;
    int8_t n = (int8_t)v[0];
    if (n == 0 || n > MAX_NUDGE || n < -MAX_NUDGE) return;
    BleEvent e = {};
    e.conn = desc->conn_handle;
    e.nudge = n;
    xQueueSend(bleEvents, &e, 0);
  }
};

static void sendStatus(const FtmsMessage& m) {
  if (m.len == 0) return;
  machineStatus->setValue(m.data, m.len);
  if (connected) machineStatus->notify();
}

static void handleBleEvent(const BleEvent& e, double kmh, uint32_t now) {
  if (e.nudge != 0) {
    // Test des touches : jamais de + au-delà de la vitesse maximale ; la consigne en cours est abandonnée.
    if (e.nudge > 0 && kmh >= MAX_TARGET_KMH) {
      Serial.println("Appui sur + refusé : vitesse maximale atteinte.");
      return;
    }
    controller.nudge(e.nudge, now);
    Serial.printf("Test des touches : %d appui(s) sur %c\n", e.nudge > 0 ? e.nudge : -e.nudge, e.nudge > 0 ? '+' : '-');
    return;
  }
  if (e.disconnect) {
    if (e.conn == controlOwner) {
      // L'application qui réglait la vitesse est partie : on arrête d'appuyer, le tapis garde sa vitesse.
      controlOwner = NO_CONN;
      controller.cancel();
      Serial.println("Application déconnectée : réglage de vitesse abandonné.");
    }
    return;
  }
  bool granted = controlOwner == e.conn;
  // Une seule application à la fois a la main ; une autre peut la reprendre en la demandant.
  FtmsMessage status;
  FtmsMessage r = handleControlPoint(e.data, e.len, granted, controller, kmh, now, status);
  if (granted) controlOwner = e.conn;
  else if (controlOwner == e.conn) controlOwner = NO_CONN;
  if (r.len > 0) {
    controlPoint->setValue(r.data, r.len);
    controlPoint->indicate();
  }
  sendStatus(status);
  if (e.len > 0 && e.data[0] == ftms::OP_SET_TARGET_SPEED && r.data[2] == ftms::RESULT_SUCCESS) {
    Serial.printf("Consigne reçue : %.1f km/h\n", controller.target());
  } else if (e.len > 0 && r.data[2] != ftms::RESULT_SUCCESS) {
    Serial.printf("Commande FTMS 0x%02X refusée (résultat %u)\n", e.data[0], r.data[2]);
  }
}

static void reportEvent(ControlEvent e, double kmh) {
  switch (e) {
    case ControlEvent::None:
      return;
    case ControlEvent::Reached:
      Serial.printf("Consigne atteinte : %.1f km/h (mesuré %.2f)\n", controller.target(), kmh);
      return;
    case ControlEvent::BeltStopped:
      Serial.println("Bande arrêtée : réglage abandonné.");
      sendStatus(statusStopped());
      return;
    case ControlEvent::ManualOverride:
      Serial.printf("Vitesse changée sur la console (%.1f km/h) : le boîtier lâche la main.\n", kmh);
      sendStatus(statusTargetSpeed(kmh));
      return;
    case ControlEvent::OverspeedStop:
      Serial.printf("Vitesse excessive (%.1f km/h) : stop appuyé.\n", kmh);
      sendStatus(statusStopped());
      return;
  }
}

// Commandes au clavier, depuis `pio device monitor`, pour vérifier le câblage sans application :
//   v 6.5  consigne à 6,5 km/h   + / -  un appui   s  stop   x  abandonner la consigne
static void handleSerial(double kmh, uint32_t now) {
  static char line[24];
  static uint8_t n = 0;
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch != '\n' && ch != '\r') {
      if (n < sizeof(line) - 1) line[n++] = ch;
      continue;
    }
    line[n] = 0;
    n = 0;
    if (line[0] == '+') controller.nudge(1, now);
    else if (line[0] == '-') controller.nudge(-1, now);
    else if (line[0] == 's') controller.stop(now);
    else if (line[0] == 'x') controller.cancel();
    else if (line[0] == 'v') {
      double target = atof(line + 1);
      SetResult r = controller.setTarget(target, kmh, now);
      if (r == SetResult::Accepted) Serial.printf("Consigne : %.1f km/h\n", controller.target());
      else if (r == SetResult::BeltStopped) Serial.println("Refusé : démarre d'abord le tapis sur la console.");
      else Serial.printf("Refusé : consigne entre %.0f et %.0f km/h.\n", MIN_TARGET_KMH, MAX_TARGET_KMH);
    } else if (line[0] != 0) {
      Serial.println("Commandes : v 6.5 | + | - | s | x");
    }
  }
}

void setup() {
  // Touches relâchées avant tout le reste : aucun appui fantôme au démarrage.
  digitalWrite(PLUS_PIN, RELEASED);
  digitalWrite(MINUS_PIN, RELEASED);
  digitalWrite(STOP_PIN, RELEASED);
  pinMode(PLUS_PIN, OUTPUT);
  pinMode(MINUS_PIN, OUTPUT);
  pinMode(STOP_PIN, OUTPUT);
  holdButton(Button::None);

  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  pinMode(HALL_PIN, INPUT_PULLUP);
  bleEvents = xQueueCreate(8, sizeof(BleEvent));

  prefs.begin("relance", false);
  meter.setUmPerRev(prefs.getUInt("umPerRev", DEFAULT_UM_PER_REV));
  Serial.printf("Relance Run100 : %.2f mm par tour de rouleau\n", meter.umPerRev() / 1000.0);
  Serial.println(PRESSED == HIGH ? "Touches : masse commune, appui = GPIO à l'état haut"
                                 : "Touches : IN au 5 V, appui = GPIO à l'état bas");

  NimBLEDevice::init(DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* ftmsService = server->createService("1826");
  // Fitness Machine Feature : distance totale (bit 2) ; consigne de vitesse (bit 0 des Target Settings).
  NimBLECharacteristic* feature = ftmsService->createCharacteristic("2ACC", NIMBLE_PROPERTY::READ);
  uint8_t features[8] = {0x04, 0, 0, 0, 0x01, 0, 0, 0};
  feature->setValue(features, 8);
  // Supported Speed Range : 1 à 14 km/h par pas de 0,1 (unités de 0,01 km/h).
  NimBLECharacteristic* range = ftmsService->createCharacteristic("2AD4", NIMBLE_PROPERTY::READ);
  uint8_t speedRange[6] = {100, 0, 0x78, 0x05, 10, 0};
  range->setValue(speedRange, 6);
  treadmillData = ftmsService->createCharacteristic("2ACD", NIMBLE_PROPERTY::NOTIFY);
  controlPoint = ftmsService->createCharacteristic("2AD9", NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::INDICATE);
  controlPoint->setCallbacks(new ControlPointCallbacks());
  machineStatus = ftmsService->createCharacteristic("2ADA", NIMBLE_PROPERTY::NOTIFY);
  ftmsService->start();

  NimBLEService* calib = server->createService(CALIB_SERVICE);
  calibChar = calib->createCharacteristic(CALIB_CHAR, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  calibChar->setCallbacks(new CalibCallbacks());
  writeUint32(calibChar, meter.umPerRev());
  NimBLECharacteristic* nudgeChar = calib->createCharacteristic(NUDGE_CHAR, NIMBLE_PROPERTY::WRITE);
  nudgeChar->setCallbacks(new NudgeCallbacks());
  calib->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID("1826");
  // Service Data FTMS : machine disponible, type tapis de course (bit 0).
  uint8_t serviceData[3] = {0x01, 0x01, 0x00};
  adv->setServiceData(NimBLEUUID("1826"), std::string((char*)serviceData, 3));
  adv->setScanResponse(true);
  adv->start();

  attachInterrupt(digitalPinToInterrupt(HALL_PIN), onHall, FALLING);
  Serial.println("Annonce Bluetooth lancée. Commandes : v 6.5 | + | - | s | x");
}

void loop() {
  while (pulseTail != pulseHead) {
    if (meter.pulse(pulseTimes[pulseTail])) digitalWrite(LED_PIN, !digitalRead(LED_PIN));
    pulseTail = (pulseTail + 1) % 32;
  }

  uint32_t now = millis();
  double kmh = meter.speedKmh(now);

  BleEvent e;
  while (xQueueReceive(bleEvents, &e, 0) == pdTRUE) handleBleEvent(e, kmh, now);
  handleSerial(kmh, now);

  holdButton(controller.update(kmh, now));
  reportEvent(controller.takeEvent(), kmh);

  static uint32_t lastNotify = 0;
  if (now - lastNotify >= 1000) {
    lastNotify = now;
    if (kmh == 0.0) digitalWrite(LED_PIN, LOW);
    uint8_t frame[TREADMILL_FRAME_LEN];
    encodeTreadmillData(kmh, meter.totalMeters(), frame);
    treadmillData->setValue(frame, TREADMILL_FRAME_LEN);
    if (connected) treadmillData->notify();
    Serial.printf("%.2f km/h · %.0f m%s%s\n", kmh, meter.totalMeters(), connected ? " · connecté" : "",
                  controller.hasTarget() ? " · consigne active" : "");
  }
  delay(5);
}
