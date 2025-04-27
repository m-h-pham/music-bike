#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// BLE Libraries
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h> // For characteristic notification descriptors

// Display settings
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define SCREEN_ADDRESS 0x3C
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// Pin definitions
#define HALL_SENSOR_PIN 5
#define SDA_PIN 8
#define SCL_PIN 9
#define ZERO_BUTTON_PIN 4
#define HALL_SENSOR_PIN_2 6 // Pin for the second Hall sensor
#define BLE_LED_PIN 7       // Pin for the blue BLE connection indicator LED

// MPU9250 registers
#define MPU9250_ADDRESS 0x68
#define ACCEL_XOUT_H 0x3B
#define GYRO_XOUT_H 0x43

// Physical constants
#define WHEEL_DIAMETER_INCHES 26.0
#define WHEEL_CIRCUMFERENCE_CM (WHEEL_DIAMETER_INCHES * 2.54 * 3.14159)
#define HALF_CIRCUMFERENCE_CM (WHEEL_CIRCUMFERENCE_CM / 2.0)

// Threshold values
#define JUMP_THRESHOLD 1.5
#define JUMP_DURATION_MIN 100
#define LANDING_THRESHOLD 1.8
#define DROP_THRESHOLD 0.0 // Note: This might trigger easily on bumps if set to 0.0, consider adjusting.
#define DIRECTION_THRESHOLD 0.3

// BLE: Define Service and Characteristic UUIDs
#define SERVICE_UUID        "0fb899fa-2b3a-4e11-911d-4fa05d130dc1" // <<< YOUR SERVICE UUID
#define SPEED_CHARACTERISTIC_UUID    "a635fed5-9a19-4e31-8091-84d020481329" // Format: float (4 bytes)
#define PITCH_CHARACTERISTIC_UUID    "726c4b96-bc56-47d2-95a1-a6c49cce3a1f" // Format: float (4 bytes)
#define ROLL_CHARACTERISTIC_UUID     "a1e929e3-5a2e-4418-806a-c50ab877d126" // Format: float (4 bytes)
#define YAW_CHARACTERISTIC_UUID      "cd6fc0f8-089a-490e-8e36-74af84977c7b" // Format: float (4 bytes)
#define GFORCE_CHARACTERISTIC_UUID   "a6210f30-654f-32ea-9e37-432a639fb38e" // Format: float (4 bytes)
#define EVENT_CHARACTERISTIC_UUID    "26205d71-58d1-45e6-9ad1-1931cd7343c3" // Format: uint8_t (0=NONE, 1=JUMP, 2=DROP)
#define IMU_DIRECTION_CHARACTERISTIC_UUID "ceb04cf6-0555-4243-a27b-c85986ab4bd7" // Format: uint8_t (0=Rev, 1=Fwd)
#define HALL_DIRECTION_CHARACTERISTIC_UUID "f231de63-475c-463d-9b3f-f338d7458bb9" // Format: uint8_t (0=Rev, 1=Fwd)
#define IMU_SPEED_STATE_CHARACTERISTIC_UUID "738f5e54-5479-4941-ae13-caf4a9b07b2e" // Format: uint8_t (0=Stop/Slow, 1=Med, 2=Fast)


// BLE: Global BLE Objects
BLEServer* pServer = NULL;
BLECharacteristic* pSpeedCharacteristic = NULL;
BLECharacteristic* pPitchCharacteristic = NULL;
BLECharacteristic* pRollCharacteristic = NULL;
BLECharacteristic* pYawCharacteristic = NULL;
BLECharacteristic* pGForceCharacteristic = NULL;
BLECharacteristic* pEventCharacteristic = NULL; // For jump/drop
BLECharacteristic* pImuDirectionCharacteristic = NULL;
BLECharacteristic* pHallDirectionCharacteristic = NULL;
BLECharacteristic* pImuSpeedStateCharacteristic = NULL;

bool deviceConnected = false; // BLE: Track connection status
bool oldDeviceConnected = false; // BLE: Track previous connection status

// Variables for sensor readings
float pitch = 0.0;
float roll = 0.0;
float yaw = 0.0;
float pitchOffset = 0.0;
float rollOffset = 0.0;
float yawOffset = 0.0;
float q0 = 1.0f, q1 = 0, q2 = 0, q3 = 0;  // quaternion estimate
float twoKp = 2.0f * 0.5f;  // Mahony Kp gain (tune between 0.5–2.0)
float twoKi = 2.0f * 0.0f;  // Mahony Ki gain (usually zero or very small)
unsigned long lastQuatTime = 0;
float integralFBx = 0, integralFBy = 0, integralFBz = 0;
int hallSensorValue = 0;
int hallSensorValue2 = 0;
int lastHallSensorValue = HIGH;
int lastHallSensorValue2 = HIGH;
bool hallDirectionForward = true; // Direction determined by Hall sensors
unsigned long lastDisplayUpdate = 0;
unsigned long lastSerialUpdate = 0;
const int displayUpdateInterval = 100;
const int serialUpdateInterval = 1000;

// Speed calculation variables
unsigned long lastTriggerTime = 0;
unsigned long currentTriggerTime = 0;
float currentSpeed = 0.0; // km/h
const unsigned long SPEED_TIMEOUT = 3000;

// Direction detection
bool movingForward = true;
float forwardAccel = 0.0;
int imuSpeedState = 0; // 0=Stop/Slow, 1=Medium, 2=Fast
bool imuDirectionForward = true; // Direction determined by IMU

// Jump and drop detection
bool inJumpState = false;
bool jumpDetected = false;
bool dropDetected = false;
unsigned long jumpStartTime = 0;
unsigned long lastJumpTime = 0;
unsigned long lastDropTime = 0;
const unsigned long EVENT_DISPLAY_DURATION = 2000;

// Button debounce variables
bool lastButtonState = HIGH;
unsigned long lastDebounceTime = 0;
unsigned long debounceDelay = 50;

// Raw sensor data
int16_t ax, ay, az;
int16_t gx, gy, gz;
float accelX, accelY, accelZ;
float gyroX, gyroY, gyroZ;
float gForce; // Calculated vertical G

// Complementary filter variables
unsigned long prevTime = 0;
float alpha = 0.96;

// BLE: Server Callback Class
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServerInstance) {
      deviceConnected = true;
      Serial.println("BLE Device Connected");
      digitalWrite(BLE_LED_PIN, HIGH); // Turn LED ON
      BLEDevice::getAdvertising()->stop();
    }

    void onDisconnect(BLEServer* pServerInstance) {
      deviceConnected = false;
      Serial.println("BLE Device Disconnected");
      digitalWrite(BLE_LED_PIN, LOW); // Turn LED OFF
      delay(500); // Give BLE stack time
      pServer->startAdvertising();
      Serial.println("BLE Advertising restarted");
    }
};


void setup() {
  Serial.begin(115200);
  Serial.println("Music Bike Sensor System Initializing...");

  pinMode(HALL_SENSOR_PIN, INPUT);
  pinMode(ZERO_BUTTON_PIN, INPUT_PULLUP);

  Wire.begin(SDA_PIN, SCL_PIN);

  // Initialize MPU9250
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(0x6B); Wire.write(0); Wire.endTransmission(true);
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(0x1C); Wire.write(0x00); Wire.endTransmission(true);

  // Initialize OLED display
  if(!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    Serial.println(F("SSD1306 allocation failed")); for(;;);
  }
  display.clearDisplay(); display.setTextSize(1); display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0); display.println("Music Bike v2.0"); display.println("Initializing...");
  display.display(); delay(500);

  // --- BLE: Initialization ---
  pinMode(BLE_LED_PIN, OUTPUT); digitalWrite(BLE_LED_PIN, LOW);

  Serial.println("Initializing BLE...");
  BLEDevice::init("MusicBike_Sensor");

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create the BLE Service, requesting 32 attribute handles.
  // Default handle limit (~15) is too low for our 9 characteristics + notify CCCDs (min needed: 28).
  // This prevents errors during attribute registration.
  BLEService *pService = pServer->createService(BLEUUID(SERVICE_UUID), 32); // Request 32 handles

  //Create Characteristics
  pSpeedCharacteristic = pService->createCharacteristic(SPEED_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pSpeedCharacteristic->addDescriptor(new BLE2902());
  pPitchCharacteristic = pService->createCharacteristic(PITCH_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pPitchCharacteristic->addDescriptor(new BLE2902());
  pRollCharacteristic = pService->createCharacteristic(ROLL_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pRollCharacteristic->addDescriptor(new BLE2902());
  pYawCharacteristic = pService->createCharacteristic(YAW_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pYawCharacteristic->addDescriptor(new BLE2902());
  pEventCharacteristic = pService->createCharacteristic(EVENT_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pEventCharacteristic->addDescriptor(new BLE2902());
  pImuDirectionCharacteristic = pService->createCharacteristic(IMU_DIRECTION_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pImuDirectionCharacteristic->addDescriptor(new BLE2902());
  pHallDirectionCharacteristic = pService->createCharacteristic(HALL_DIRECTION_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pHallDirectionCharacteristic->addDescriptor(new BLE2902());
  pImuSpeedStateCharacteristic = pService->createCharacteristic(IMU_SPEED_STATE_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pImuSpeedStateCharacteristic->addDescriptor(new BLE2902());
  pGForceCharacteristic = pService->createCharacteristic(GFORCE_CHARACTERISTIC_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pGForceCharacteristic->addDescriptor(new BLE2902());

  pService->start(); // Start the service

  // Start Advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID); // Make service discoverable
  pAdvertising->setScanResponse(true); pAdvertising->setMinPreferred(0x0);
  BLEDevice::startAdvertising();
  Serial.println("BLE Initialized and Advertising!");
  // --- End BLE Initialization ---

  display.clearDisplay(); display.setCursor(0,0);
  display.println("BLE Advertising!"); display.println("Press button to zero");
  display.display(); delay(1500);

  Serial.println("Initialization complete!");
  prevTime = millis();
  lastTriggerTime = millis();
}

void loop() {
  unsigned long currentMillis = millis();

  readMPU9250Data();
  calculateAngles(); // Updates global pitch, roll, yaw, gForce

  float zeroedPitch = pitch - pitchOffset;
  float zeroedRoll = roll - rollOffset;
  float zeroedYaw = yaw - yawOffset;
  while (zeroedYaw < 0) zeroedYaw += 360;
  while (zeroedYaw >= 360) zeroedYaw -= 360;

  forwardAccel = -accelY;
  // movingForward = (forwardAccel > DIRECTION_THRESHOLD); // Old simple way

  bool wasJumpDetected = jumpDetected;
  bool wasDropDetected = dropDetected;
  detectJumpAndDrop();

  int currentHallValue = digitalRead(HALL_SENSOR_PIN);
  if (currentHallValue == LOW && lastHallSensorValue == HIGH) {
    currentTriggerTime = millis();
    if (lastTriggerTime > 0) {
      unsigned long timeDiff = currentTriggerTime - lastTriggerTime;
      if (timeDiff > 0) { currentSpeed = (HALF_CIRCUMFERENCE_CM / timeDiff) * 36.0; }
    }
    lastTriggerTime = currentTriggerTime;
  }
  if (millis() - lastTriggerTime > SPEED_TIMEOUT) { currentSpeed = 0.0; }
  lastHallSensorValue = currentHallValue;
  hallSensorValue = currentHallValue;

  int currentHallValue2 = digitalRead(HALL_SENSOR_PIN_2);
  if (currentHallValue == LOW && lastHallSensorValue == HIGH) {
    if (lastHallSensorValue2 == HIGH) { hallDirectionForward = true; }
  } else if (currentHallValue2 == LOW && lastHallSensorValue2 == HIGH) {
    if (lastHallSensorValue == HIGH) { hallDirectionForward = false; }
  }
  lastHallSensorValue = currentHallValue;
  lastHallSensorValue2 = currentHallValue2;

  int reading = digitalRead(ZERO_BUTTON_PIN);
  if (reading == LOW && lastButtonState == HIGH) {
    if ((millis() - lastDebounceTime) > debounceDelay) {
      pitchOffset = pitch; rollOffset = roll; yawOffset = yaw;
      display.clearDisplay(); display.setTextSize(1); display.setCursor(0, 0);
      display.println("Zero position set!"); display.display();
      Serial.println("Zero position set!");
      delay(1000); lastDebounceTime = millis();
    }
  }
  lastButtonState = reading;

  if (currentMillis - lastDisplayUpdate >= displayUpdateInterval) {
    lastDisplayUpdate = currentMillis;
    updateDisplay(zeroedPitch, zeroedRoll, zeroedYaw, gForce); // gForce updated in calculateAngles
  }

  if (currentMillis - lastSerialUpdate >= serialUpdateInterval) {
    lastSerialUpdate = currentMillis;
    printSerialData(zeroedPitch, zeroedRoll, zeroedYaw);
  }

  float gravityCompAccelY = accelY - sin(pitch * PI / 180.0);
  imuDirectionForward = (gravityCompAccelY < -DIRECTION_THRESHOLD);

  float accelMagnitudeXY = sqrt(accelX * accelX + gravityCompAccelY * gravityCompAccelY);
  if (accelMagnitudeXY > 0.8) { imuSpeedState = 2; }
  else if (accelMagnitudeXY > 0.2) { imuSpeedState = 1; }
  else { imuSpeedState = 0; }

  // --- BLE: Notify connected client (BINARY FORMAT) ---
  if (deviceConnected) {

    // Speed (float - 4 bytes)
    // Use the existing currentSpeed variable
    pSpeedCharacteristic->setValue((uint8_t*)&currentSpeed, sizeof(currentSpeed));
    pSpeedCharacteristic->notify();
    //Serial.println("Notified Speed");

    // Pitch (float - 4 bytes)
    // Use the existing zeroedPitch variable
    pPitchCharacteristic->setValue((uint8_t*)&zeroedPitch, sizeof(zeroedPitch));
    pPitchCharacteristic->notify();
    //Serial.println("Notified Pitch");

    // Roll (float - 4 bytes)
    // Use the existing zeroedRoll variable
    pRollCharacteristic->setValue((uint8_t*)&zeroedRoll, sizeof(zeroedRoll));
    pRollCharacteristic->notify();
    //Serial.println("Notified Roll");

    // Yaw (float - 4 bytes)
    // Use the existing zeroedYaw variable
    pYawCharacteristic->setValue((uint8_t*)&zeroedYaw, sizeof(zeroedYaw));
    pYawCharacteristic->notify();
    //Serial.println("Notified Yaw");

    // GForce (float - 4 bytes)
    // Use the global gForce variable calculated in calculateAngles()
    pGForceCharacteristic->setValue((uint8_t*)&gForce, sizeof(gForce));
    pGForceCharacteristic->notify();
    //Serial.println("Notified GForce");

    // Event (Jump/Drop) - Send code: 0=NONE, 1=JUMP, 2=DROP (uint8_t - 1 byte)
    // Only notify on transition to detected state for now
    uint8_t eventCode = 0; // Default to NONE
    if (jumpDetected && !wasJumpDetected) {
        eventCode = 1; // JUMP
        pEventCharacteristic->setValue(&eventCode, sizeof(eventCode));
        pEventCharacteristic->notify();
        Serial.println("BLE Notify: JUMP (Code 1)");
    } else if (dropDetected && !wasDropDetected) {
        eventCode = 2; // DROP
        pEventCharacteristic->setValue(&eventCode, sizeof(eventCode));
        pEventCharacteristic->notify();
        Serial.println("BLE Notify: DROP (Code 2)");
    }
    // Note: We are not sending 'NONE' currently, only JUMP or DROP when they occur.
    // You might want to send 0 periodically or after the event clears if the app needs it.

    // IMU Direction (0=Rev, 1=Fwd) (uint8_t - 1 byte)
    uint8_t imuDirCode = imuDirectionForward ? 1 : 0;
    pImuDirectionCharacteristic->setValue(&imuDirCode, sizeof(imuDirCode));
    pImuDirectionCharacteristic->notify();
    //Serial.println("Notified IMU Direction");

    // Hall Direction (0=Rev, 1=Fwd) (uint8_t - 1 byte)
    uint8_t hallDirCode = hallDirectionForward ? 1 : 0;
    pHallDirectionCharacteristic->setValue(&hallDirCode, sizeof(hallDirCode));
    pHallDirectionCharacteristic->notify();
    //Serial.println("Notified Hall Direction");

    // IMU Speed State (0=Stop/Slow, 1=Med, 2=Fast) (uint8_t - 1 byte)
    uint8_t speedStateCode = (uint8_t)imuSpeedState; // Cast int to uint8_t
    pImuSpeedStateCharacteristic->setValue(&speedStateCode, sizeof(speedStateCode));
    pImuSpeedStateCharacteristic->notify();
    //Serial.println("Notified IMU Speed State");

  } // End if(deviceConnected)

  // Handle connection state changes (for restarting advertising after disconnect)
  if (!deviceConnected && oldDeviceConnected) { oldDeviceConnected = deviceConnected; }
  if (deviceConnected && !oldDeviceConnected) { oldDeviceConnected = deviceConnected; }

  delay(10);

} // End loop()


// --- Sensor Reading and Calculation Functions ---
// (Keep your existing detectJumpAndDrop, readMPU9250Data, calculateAngles,
//  updateDisplay, and printSerialData functions exactly as they were)
// --- Start unchanged functions ---
void detectJumpAndDrop() {
  float verticalAccel = accelZ - 1.0; // Using global accelZ now
  if (!inJumpState && verticalAccel < JUMP_THRESHOLD) {
    inJumpState = true;
    jumpStartTime = millis();
  }
  if (inJumpState && verticalAccel > LANDING_THRESHOLD) {
    unsigned long jumpDuration = millis() - jumpStartTime;
    if (jumpDuration > JUMP_DURATION_MIN) {
      jumpDetected = true;
      lastJumpTime = millis();
      Serial.println("JUMP DETECTED! Duration: " + String(jumpDuration) + "ms");
      Serial.println("Landing G-force: " + String(verticalAccel + 1.0) + "g");
    }
    inJumpState = false;
  }
  if (!inJumpState && verticalAccel > DROP_THRESHOLD && !dropDetected) {
       dropDetected = true;
       lastDropTime = millis();
       Serial.println("DROP DETECTED! Impact G-force: " + String(verticalAccel + 1.0) + "g");
  }
  if (jumpDetected && (millis() - lastJumpTime > EVENT_DISPLAY_DURATION)) {
    jumpDetected = false;
  }
  if (dropDetected && (millis() - lastDropTime > EVENT_DISPLAY_DURATION)) {
    dropDetected = false;
  }
}

void readMPU9250Data() {
  Wire.beginTransmission(MPU9250_ADDRESS); Wire.write(ACCEL_XOUT_H); Wire.endTransmission(false);
  Wire.requestFrom(MPU9250_ADDRESS, 6, true);
  ax = Wire.read() << 8 | Wire.read(); ay = Wire.read() << 8 | Wire.read(); az = Wire.read() << 8 | Wire.read();
  accelX = ax / 16384.0; accelY = ay / 16384.0; accelZ = az / 16384.0; // Update globals

  Wire.beginTransmission(MPU9250_ADDRESS); Wire.write(GYRO_XOUT_H); Wire.endTransmission(false);
  Wire.requestFrom(MPU9250_ADDRESS, 6, true);
  gx = Wire.read() << 8 | Wire.read(); gy = Wire.read() << 8 | Wire.read(); gz = Wire.read() << 8 | Wire.read();
  gyroX = gx / 131.0; gyroY = gy / 131.0; gyroZ = gz / 131.0; // Update globals
}

void calculateAngles() {
  unsigned long now = millis(); float dt = (now - lastQuatTime) * 0.001f; lastQuatTime = now;
  float ax_calc = accelX, ay_calc = accelY, az_calc = accelZ; // Use global values
  float gx_calc = gyroX * PI/180.0f, gy_calc = gyroY * PI/180.0f, gz_calc = gyroZ * PI/180.0f; // Use global values

  float norm = sqrt(ax_calc*ax_calc + ay_calc*ay_calc + az_calc*az_calc);
  if (norm == 0.0f) return;
  ax_calc /= norm; ay_calc /= norm; az_calc /= norm;

  float vx = 2.0f*(q1*q3 - q0*q2); float vy = 2.0f*(q0*q1 + q2*q3); float vz = q0*q0 - q1*q1 - q2*q2 + q3*q3;
  float ex = (ay_calc*vz - az_calc*vy); float ey = (az_calc*vx - ax_calc*vz); float ez = (ax_calc*vy - ay_calc*vx);

  if (twoKi > 0.0f) {
    integralFBx += twoKi * ex * dt; integralFBy += twoKi * ey * dt; integralFBz += twoKi * ez * dt;
    gx_calc += integralFBx; gy_calc += integralFBy; gz_calc += integralFBz;
  }
  gx_calc += twoKp * ex; gy_calc += twoKp * ey; gz_calc += twoKp * ez;

  float qDot0 = -q1*gx_calc - q2*gy_calc - q3*gz_calc; float qDot1 =  q0*gx_calc + q2*gz_calc - q3*gy_calc;
  float qDot2 =  q0*gy_calc - q1*gz_calc + q3*gx_calc; float qDot3 =  q0*gz_calc + q1*gy_calc - q2*gx_calc;
  q0 += qDot0 * (0.5f*dt); q1 += qDot1 * (0.5f*dt); q2 += qDot2 * (0.5f*dt); q3 += qDot3 * (0.5f*dt);

  norm = sqrt(q0*q0 + q1*q1 + q2*q2 + q3*q3);
  q0 /= norm; q1 /= norm; q2 /= norm; q3 /= norm;

  // Update global pitch, roll, yaw
  pitch = atan2(2*(q0*q1 + q2*q3), 1 - 2*(q1*q1+q2*q2)) * 180.0/PI;
  roll  = asin (2*(q0*q2 - q3*q1)) * 180.0/PI;
  yaw   = atan2(2*(q0*q3 + q1*q2), 1 - 2*(q2*q2+q3*q3)) * 180.0/PI;
  if (yaw < 0) yaw += 360;

  // Update global gForce (vertical acceleration)
  float gravityVectorX = 2.0f * (q1 * q3 - q0 * q2);
  float gravityVectorY = 2.0f * (q0 * q1 + q2 * q3);
  float gravityVectorZ = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3;
  float verticalAcceleration = accelX * gravityVectorX + accelY * gravityVectorY + accelZ * gravityVectorZ;
  gForce = verticalAcceleration; // Update global gForce
}

void updateDisplay(float p, float r, float y, float gVal) { // Renamed gForce param to avoid shadowing global
  display.clearDisplay(); display.setTextSize(1); display.setTextColor(SSD1306_WHITE);
  if (deviceConnected) { display.setCursor(SCREEN_WIDTH - 12, 0); display.print(" B"); }
  display.setCursor(0, 0); display.print("G:"); display.print(gVal, 2); // Use parameter gVal
  display.setCursor(0, 10); display.print("J:"); display.print(jumpDetected ? "Y" : "N");
  display.setCursor(64, 10); display.print("D:"); display.print(dropDetected ? "Y" : "N");
  display.setCursor(0, 25); display.print("P:"); display.print(p, 1);
  display.setCursor(64, 25); display.print("R:"); display.print(r, 1);
  display.setCursor(0, 35); display.print("Y:"); display.print(y, 1);
  display.setCursor(0, 45); display.print("Spd:"); display.print(currentSpeed, 1);
  display.setCursor(64, 45); display.print("Dir:"); display.print(imuDirectionForward ? "F" : "R"); // Using IMU direction for display consistency
  display.display();
}

void printSerialData(float p, float r, float y) {
  Serial.println("--- Sensor Data ---");
  Serial.print("Pitch: "); Serial.print(p, 2); Serial.print(" Roll: "); Serial.print(r, 2); Serial.print(" Yaw: "); Serial.println(y, 2);
  Serial.print("Speed: "); Serial.print(currentSpeed, 2); Serial.println(" km/h");
  Serial.print("IMU Dir: "); Serial.print(imuDirectionForward ? "Forward" : "Reverse"); Serial.print(" | Hall Dir: "); Serial.println(hallDirectionForward ? "Forward" : "Reverse");
  Serial.print("IMU Speed State: "); Serial.println(imuSpeedState);
  Serial.print("Hall Sensor 1: "); Serial.print(hallSensorValue == LOW ? "LOW" : "HIGH"); Serial.print(" | Hall Sensor 2: "); Serial.println(hallSensorValue2 == LOW ? "LOW" : "HIGH");
  Serial.print("G-Force (Vert): "); Serial.println(gForce, 2); // Using global gForce
  // Serial.print("Vertical Accel (Raw Z-1): "); Serial.println(accelZ - 1.0, 2);
  // Serial.print("Forward Accel (Raw Y-Comp): "); Serial.println(forwardAccel, 2);
  if (jumpDetected) Serial.println("JUMP DETECTED!"); if (dropDetected) Serial.println("DROP DETECTED!");
  Serial.print("BLE Connected: "); Serial.println(deviceConnected ? "Yes" : "No");
  Serial.println();
}
// --- End unchanged functions ---