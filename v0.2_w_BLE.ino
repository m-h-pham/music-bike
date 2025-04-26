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
// Use the Service UUID you provided
#define SERVICE_UUID        "0fb899fa-2b3a-4e11-911d-4fa05d130dc1" // <<< YOUR SERVICE UUID
// Generate unique UUIDs for each characteristic (used an online generator)
#define SPEED_CHARACTERISTIC_UUID    "a635fed5-9a19-4e31-8091-84d020481329"
#define PITCH_CHARACTERISTIC_UUID    "726c4b96-bc56-47d2-95a1-a6c49cce3a1f"
#define ROLL_CHARACTERISTIC_UUID     "a1e929e3-5a2e-4418-806a-c50ab877d126"
#define YAW_CHARACTERISTIC_UUID      "cd6fc0f8-089a-490e-8e36-74af84977c7b"
#define GFORCE_CHARACTERISTIC_UUID   "a6210f30-654f-32ea-9e37-432a639fb38e"
#define EVENT_CHARACTERISTIC_UUID    "26205d71-58d1-45e6-9ad1-1931cd7343c3" //  (For Jump/Drop)
// Add more if needed (e.g., G-force, Direction)

// BLE: Global BLE Objects
BLEServer* pServer = NULL;
BLECharacteristic* pSpeedCharacteristic = NULL;
BLECharacteristic* pPitchCharacteristic = NULL;
BLECharacteristic* pRollCharacteristic = NULL;
BLECharacteristic* pYawCharacteristic = NULL;
BLECharacteristic* pGForceCharacteristic = NULL;
BLECharacteristic* pEventCharacteristic = NULL; // For jump/drop

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
int lastHallSensorValue = HIGH;
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
float gForce;

// Complementary filter variables
unsigned long prevTime = 0;
float alpha = 0.96;

// BLE: Server Callback Class
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServerInstance) { // Use a different name to avoid shadowing global pServer
      deviceConnected = true;
      Serial.println("BLE Device Connected");
      // Optional: Stop advertising once connected to save power
      // BLEDevice::getAdvertising()->stop();
    }

    void onDisconnect(BLEServer* pServerInstance) {
      deviceConnected = false;
      Serial.println("BLE Device Disconnected");
      // Important: Restart advertising so the app can find it again
      // Add a small delay before restarting advertising
      // Note: The global pServer pointer should still be valid here
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
  Wire.write(0x6B); // PWR_MGMT_1
  Wire.write(0);    // Wake up
  Wire.endTransmission(true);
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(0x1C); // ACCEL_CONFIG
  Wire.write(0x00); // +/- 2g
  Wire.endTransmission(true);

  // Initialize OLED display
  if(!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    Serial.println(F("SSD1306 allocation failed"));
    for(;;);
  }
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.println("Music Bike v2.0");
  display.println("Initializing...");
  display.display();
  delay(500); // Short delay

  // --- BLE: Initialization ---
  Serial.println("Initializing BLE...");
  BLEDevice::init("MusicBike_Sensor"); // Set advertised device name

  pServer = BLEDevice::createServer(); // Create the BLE Server
  pServer->setCallbacks(new MyServerCallbacks()); // Set connection callbacks

  BLEService *pService = pServer->createService(SERVICE_UUID); // Create the Service

  // Create Characteristics
  pSpeedCharacteristic = pService->createCharacteristic(
                         SPEED_CHARACTERISTIC_UUID,
                         BLECharacteristic::PROPERTY_READ |
                         BLECharacteristic::PROPERTY_NOTIFY
                       );
  pSpeedCharacteristic->addDescriptor(new BLE2902()); // Needed for notify

  pPitchCharacteristic = pService->createCharacteristic(
                         PITCH_CHARACTERISTIC_UUID,
                         BLECharacteristic::PROPERTY_READ |
                         BLECharacteristic::PROPERTY_NOTIFY
                       );
  pPitchCharacteristic->addDescriptor(new BLE2902());

  pRollCharacteristic = pService->createCharacteristic(
                         ROLL_CHARACTERISTIC_UUID,
                         BLECharacteristic::PROPERTY_READ |
                         BLECharacteristic::PROPERTY_NOTIFY
                       );
  pRollCharacteristic->addDescriptor(new BLE2902());

  pYawCharacteristic = pService->createCharacteristic(
                         YAW_CHARACTERISTIC_UUID,
                         BLECharacteristic::PROPERTY_READ |
                         BLECharacteristic::PROPERTY_NOTIFY
                       );
  pYawCharacteristic->addDescriptor(new BLE2902());

  pGForceCharacteristic = pService->createCharacteristic(
                          GFORCE_CHARACTERISTIC_UUID,
                          BLECharacteristic::PROPERTY_READ |
                          BLECharacteristic::PROPERTY_NOTIFY
                        );
  pGForceCharacteristic->addDescriptor(new BLE2902());

  pEventCharacteristic = pService->createCharacteristic(
                         EVENT_CHARACTERISTIC_UUID,
                         BLECharacteristic::PROPERTY_READ |
                         BLECharacteristic::PROPERTY_NOTIFY
                       );
  pEventCharacteristic->addDescriptor(new BLE2902());

  pService->start(); // Start the service

  // Start Advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID); // Make service discoverable
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x0); // Helps compatibility
  BLEDevice::startAdvertising();
  Serial.println("BLE Initialized and Advertising!");
  // --- End BLE Initialization ---

  // Update display after BLE init
  display.clearDisplay();
  display.setCursor(0,0);
  display.println("BLE Advertising!");
  display.println("Press button to zero");
  display.display();
  delay(1500);

  Serial.println("Initialization complete!");
  prevTime = millis();
  lastTriggerTime = millis();
}

void loop() {
  unsigned long currentMillis = millis();

  // Read MPU9250 data
  readMPU9250Data();

  // Calculate angles using complementary filter
  calculateAngles();

  // Apply offsets to get zeroed angles
  float zeroedPitch = pitch - pitchOffset;
  float zeroedRoll = roll - rollOffset;
  float zeroedYaw = yaw - yawOffset;

  // Normalize yaw
  while (zeroedYaw < 0) zeroedYaw += 360;
  while (zeroedYaw >= 360) zeroedYaw -= 360;

  // Detect forward/backward movement
  forwardAccel = -accelY; // Adjust sign based on orientation
  movingForward = (forwardAccel > DIRECTION_THRESHOLD);

  // Store previous jump/drop state before detecting new ones
  bool wasJumpDetected = jumpDetected;
  bool wasDropDetected = dropDetected;
  // Check for jump and drop conditions
  detectJumpAndDrop();

  // Read Hall Effect Sensor and calculate speed
  int currentHallValue = digitalRead(HALL_SENSOR_PIN);
  if (currentHallValue == LOW && lastHallSensorValue == HIGH) {
    currentTriggerTime = millis();
    if (lastTriggerTime > 0) {
      unsigned long timeDiff = currentTriggerTime - lastTriggerTime;
      if (timeDiff > 0) { // Avoid division by zero
         currentSpeed = (HALF_CIRCUMFERENCE_CM / timeDiff) * 36.0;
      }
    }
    lastTriggerTime = currentTriggerTime;
  }
  if (millis() - lastTriggerTime > SPEED_TIMEOUT) {
    currentSpeed = 0.0;
  }
  lastHallSensorValue = currentHallValue;
  hallSensorValue = currentHallValue;

  // Check zero button
  int reading = digitalRead(ZERO_BUTTON_PIN);
  if (reading == LOW && lastButtonState == HIGH) {
    if ((millis() - lastDebounceTime) > debounceDelay) {
      pitchOffset = pitch;
      rollOffset = roll;
      yawOffset = yaw;
      display.clearDisplay();
      display.setTextSize(1);
      display.setCursor(0, 0);
      display.println("Zero position set!");
      display.display();
      Serial.println("Zero position set!");
      delay(1000);
      lastDebounceTime = millis();
    }
  }
  lastButtonState = reading;

  // Update display at defined interval
  if (currentMillis - lastDisplayUpdate >= displayUpdateInterval) {
    lastDisplayUpdate = currentMillis;
    updateDisplay(zeroedPitch, zeroedRoll, zeroedYaw, gForce);
  }

  // Update serial output at defined interval
  if (currentMillis - lastSerialUpdate >= serialUpdateInterval) {
    lastSerialUpdate = currentMillis;
    printSerialData(zeroedPitch, zeroedRoll, zeroedYaw);
  }

  // --- BLE: Notify connected client ---
  if (deviceConnected) {
    // Convert sensor values to strings (or byte arrays if preferred)
    char buffer[10]; // Small buffer for conversions

    // Speed
    dtostrf(currentSpeed, 4, 2, buffer); // Format: XXX.YY
    pSpeedCharacteristic->setValue(buffer);
    pSpeedCharacteristic->notify();

    // Pitch
    dtostrf(zeroedPitch, 4, 1, buffer); // Format: XXX.Y
    pPitchCharacteristic->setValue(buffer);
    pPitchCharacteristic->notify();

    // Roll
    dtostrf(zeroedRoll, 4, 1, buffer);
    pRollCharacteristic->setValue(buffer);
    pRollCharacteristic->notify();

    // Yaw
    dtostrf(zeroedYaw, 4, 1, buffer);
    pYawCharacteristic->setValue(buffer);
    pYawCharacteristic->notify();

    // Event (Jump/Drop) - Send a simple code
    // Only notify if the state *just* changed to detected
    if (jumpDetected && !wasJumpDetected) {
        pEventCharacteristic->setValue("JUMP"); // Send "JUMP" string
        pEventCharacteristic->notify();
        Serial.println("BLE Notify: JUMP");
    } else if (dropDetected && !wasDropDetected) {
        pEventCharacteristic->setValue("DROP"); // Send "DROP" string
        pEventCharacteristic->notify();
        Serial.println("BLE Notify: DROP");
    }
     // Consider sending an "IDLE" or "NONE" event periodically or after event display duration?
     // else if (!jumpDetected && wasJumpDetected || !dropDetected && wasDropDetected) {
     //    pEventCharacteristic->setValue("NONE");
     //    pEventCharacteristic->notify();
     // }

  } // End if(deviceConnected)

  // Handle connection state changes (for restarting advertising after disconnect)
  // This logic is now primarily handled by the MyServerCallbacks class
  // We still keep track of oldDeviceConnected for potential future use if needed
  if (!deviceConnected && oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
      // Advertising restart is handled in onDisconnect callback
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
      // Actions to take upon new connection can go here if needed
  }

  // Small delay to prevent overwhelming the loop, adjust as needed
  delay(10); // Reduced delay for potentially faster BLE updates, test responsiveness

} // End loop()


// --- Sensor Reading and Calculation Functions (Unchanged) ---
void detectJumpAndDrop() {
  float verticalAccel = accelZ - 1.0;
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
  // Simplified drop detection based on impact without prior jump state
  if (!inJumpState && verticalAccel > DROP_THRESHOLD && !dropDetected) { // Only trigger once until reset
     // Add a check to ensure it's a significant drop, not just noise
     // Maybe check if speed is low or zero?
     // if (currentSpeed < 1.0) { // Example: only detect drops if nearly stationary
        dropDetected = true;
        lastDropTime = millis();
        Serial.println("DROP DETECTED! Impact G-force: " + String(verticalAccel + 1.0) + "g");
     // }
  }
  if (jumpDetected && (millis() - lastJumpTime > EVENT_DISPLAY_DURATION)) {
    jumpDetected = false;
  }
  if (dropDetected && (millis() - lastDropTime > EVENT_DISPLAY_DURATION)) {
    dropDetected = false;
  }
}

void readMPU9250Data() {
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(ACCEL_XOUT_H);
  Wire.endTransmission(false);
  Wire.requestFrom(MPU9250_ADDRESS, 6, true);
  ax = Wire.read() << 8 | Wire.read();
  ay = Wire.read() << 8 | Wire.read();
  az = Wire.read() << 8 | Wire.read();
  accelX = ax / 16384.0;
  accelY = ay / 16384.0;
  accelZ = az / 16384.0;

  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(GYRO_XOUT_H);
  Wire.endTransmission(false);
  Wire.requestFrom(MPU9250_ADDRESS, 6, true);
  gx = Wire.read() << 8 | Wire.read();
  gy = Wire.read() << 8 | Wire.read();
  gz = Wire.read() << 8 | Wire.read();
  gyroX = gx / 131.0;
  gyroY = gy / 131.0;
  gyroZ = gz / 131.0;
}

void calculateAngles() {
  // 1) compute dt
  unsigned long now = millis();
  float dt = (now - lastQuatTime) * 0.001f;
  lastQuatTime = now;

  // 2) Mahony quaternion update (gyro in rad/s, accel in g's)
  float ax = accelX, ay = accelY, az = accelZ;
  float gx = gyroX * PI/180.0f,  // deg/s → rad/s
        gy = gyroY * PI/180.0f,
        gz = gyroZ * PI/180.0f;

  // normalize accel measurement
  float norm = sqrt(ax*ax + ay*ay + az*az);
  if (norm == 0.0f) return;    // invalid data
  ax /= norm; ay /= norm; az /= norm;

  // estimated gravity direction (from quaternion)
  float vx = 2.0f*(q1*q3 - q0*q2);
  float vy = 2.0f*(q0*q1 + q2*q3);
  float vz = q0*q0 - q1*q1 - q2*q2 + q3*q3;

  // error = cross(accel, gravity)
  float ex = (ay*vz - az*vy);
  float ey = (az*vx - ax*vz);
  float ez = (ax*vy - ay*vx);

  // integral feedback
  if (twoKi > 0.0f) {
    integralFBx += twoKi * ex * dt;
    integralFBy += twoKi * ey * dt;
    integralFBz += twoKi * ez * dt;
    gx += integralFBx;
    gy += integralFBy;
    gz += integralFBz;
  }

  // proportional feedback
  gx += twoKp * ex;
  gy += twoKp * ey;
  gz += twoKp * ez;

  // integrate rate of change of quaternion
  float qDot0 = -q1*gx - q2*gy - q3*gz;
  float qDot1 =  q0*gx + q2*gz - q3*gy;
  float qDot2 =  q0*gy - q1*gz + q3*gx;
  float qDot3 =  q0*gz + q1*gy - q2*gx;

  q0 += qDot0 * (0.5f*dt);
  q1 += qDot1 * (0.5f*dt);
  q2 += qDot2 * (0.5f*dt);
  q3 += qDot3 * (0.5f*dt);

  // normalize quaternion
  norm = sqrt(q0*q0 + q1*q1 + q2*q2 + q3*q3);
  q0 /= norm; q1 /= norm; q2 /= norm; q3 /= norm;

  // 3) extract Euler for your display/use
  pitch = atan2(2*(q0*q1 + q2*q3), 1 - 2*(q1*q1+q2*q2)) * 180.0/PI;
  roll  = asin (2*(q0*q2 - q3*q1))                  * 180.0/PI;
  yaw   = atan2(2*(q0*q3 + q1*q2), 1 - 2*(q2*q2+q3*q3)) * 180.0/PI;
  if (yaw < 0) yaw += 360;

  // 4) compute true vertical g-force
  // q0…q3 is current unit quaternion (body→world)
  float gravityVectorX = 2.0f * (q1 * q3 - q0 * q2);
  float gravityVectorY = 2.0f * (q0 * q1 + q2 * q3);
  float gravityVectorZ = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3;

  // Project raw accel onto gravity vector → vertical accel in G’s --
  // accelX/Y/Z are in G’s from sensor
  float verticalAcceleration = accelX * gravityVectorX + accelY * gravityVectorY + accelZ * gravityVectorZ;

  // Vertical acceleration in G's
  gForce = verticalAcceleration;
}

// --- Display and Serial Functions (Modified for BLE indicator) ---
void updateDisplay(float p, float r, float y, float gForce) {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  // BLE: Add connection indicator at top right
  if (deviceConnected) {
      display.setCursor(SCREEN_WIDTH - 12, 0); // Position for indicator
      display.print(" B"); // Simple 'B' for Bluetooth connected
  }

  // G-force at the top left
  display.setCursor(0, 0);
  display.print("G:");
  display.print(gForce, 2); // Display G-force with 2 decimal places

  // Jump and drop indicators
  display.setCursor(0, 10);
  display.print("J:");
  display.print(jumpDetected ? "Y" : "N");

  display.setCursor(64, 10);
  display.print("D:");
  display.print(dropDetected ? "Y" : "N");

  // Angles
  display.setCursor(0, 25);
  display.print("P:");
  display.print(p, 1);

  display.setCursor(64, 25);
  display.print("R:");
  display.print(r, 1);

  display.setCursor(0, 35);
  display.print("Y:");
  display.print(y, 1);

  // Speed and direction
  display.setCursor(0, 45);
  display.print("Spd:");
  display.print(currentSpeed, 1);

  display.setCursor(64, 45);
  display.print("Dir:");
  display.print(movingForward ? "F" : "R");

  display.display();
}

void printSerialData(float p, float r, float y) {
  Serial.println("--- Sensor Data ---");
  Serial.print("Pitch: "); Serial.print(p, 2);
  Serial.print(" Roll: "); Serial.print(r, 2);
  Serial.print(" Yaw: "); Serial.println(y, 2);
  Serial.print("Speed: "); Serial.print(currentSpeed, 2); Serial.println(" km/h");
  Serial.print("Direction: "); Serial.println(movingForward ? "Forward" : "Reverse");
  Serial.print("Hall Sensor: "); Serial.println(hallSensorValue == LOW ? "Magnet Detected" : "No Magnet");
  Serial.print("G-Force: "); Serial.println(gForce, 2);
  Serial.print("Vertical Accel: "); Serial.println(accelZ - 1.0, 2);
  Serial.print("Forward Accel: "); Serial.println(forwardAccel, 2);
  if (jumpDetected) Serial.println("JUMP DETECTED!");
  if (dropDetected) Serial.println("DROP DETECTED!");
  Serial.print("BLE Connected: "); Serial.println(deviceConnected ? "Yes" : "No"); // BLE: Add connection status to serial
  Serial.println();
}
