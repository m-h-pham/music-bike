#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

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
#define ZERO_BUTTON_PIN 4  // New pin for zeroing button

// MPU9250 registers
#define MPU9250_ADDRESS 0x68
#define ACCEL_XOUT_H 0x3B
#define GYRO_XOUT_H 0x43

// Physical constants
#define WHEEL_DIAMETER_INCHES 26.0
#define WHEEL_CIRCUMFERENCE_CM (WHEEL_DIAMETER_INCHES * 2.54 * 3.14159)
#define HALF_CIRCUMFERENCE_CM (WHEEL_CIRCUMFERENCE_CM / 2.0)  // Distance between magnet triggers

// Threshold values for jump and drop detection
#define JUMP_THRESHOLD 1.5        // G-force threshold indicating potential weightlessness
#define JUMP_DURATION_MIN 100      // Minimum time in weightlessness to count as jump (ms)
#define LANDING_THRESHOLD 1.8      // G-force threshold indicating landing impact
#define DROP_THRESHOLD 0.0         // G-force threshold for drop landing
#define DIRECTION_THRESHOLD 0.3    // Forward/backward acceleration threshold

// Variables for sensor readings
float pitch = 0.0;
float roll = 0.0;
float yaw = 0.0;
float pitchOffset = 0.0;  // Offset for zeroing
float rollOffset = 0.0;   // Offset for zeroing
float yawOffset = 0.0;    // Offset for zeroing
int hallSensorValue = 0;
int lastHallSensorValue = HIGH;
unsigned long lastDisplayUpdate = 0;
unsigned long lastSerialUpdate = 0;
const int displayUpdateInterval = 100;  // 10Hz for display
const int serialUpdateInterval = 1000;  // 1Hz for serial output

// Speed calculation variables
unsigned long lastTriggerTime = 0;
unsigned long currentTriggerTime = 0;
float currentSpeed = 0.0;  // km/h
const unsigned long SPEED_TIMEOUT = 3000;  // If no trigger for 3 seconds, assume speed is 0

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
const unsigned long EVENT_DISPLAY_DURATION = 2000;  // How long to show jump/drop indicator

// Button debounce variables
bool lastButtonState = HIGH;
unsigned long lastDebounceTime = 0;
unsigned long debounceDelay = 50;

// Raw sensor data
int16_t ax, ay, az;
int16_t gx, gy, gz;
float accelX, accelY, accelZ;
float gyroX, gyroY, gyroZ;

// Complementary filter variables
unsigned long prevTime = 0;
float alpha = 0.96; // Complementary filter constant

void setup() {
  // Initialize Serial
  Serial.begin(115200);
  Serial.println("Music Bike Sensor System Initializing...");
  
  // Initialize Hall Effect Sensor and Zero Button
  pinMode(HALL_SENSOR_PIN, INPUT);
  pinMode(ZERO_BUTTON_PIN, INPUT_PULLUP);  // Use INPUT_PULLUP for button that grounds the pin
  
  // Initialize I2C
  Wire.begin(SDA_PIN, SCL_PIN);
  
  // Initialize MPU9250
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(0x6B);  // PWR_MGMT_1 register
  Wire.write(0);     // set to zero (wakes up the MPU-9250)
  Wire.endTransmission(true);
  
  // Configure accelerometer sensitivity (±2g)
  Wire.beginTransmission(MPU9250_ADDRESS);
  Wire.write(0x1C);  // ACCEL_CONFIG register
  Wire.write(0x00);  // 2g full scale range
  Wire.endTransmission(true);
  
  // Initialize OLED display
  if(!display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    Serial.println(F("SSD1306 allocation failed"));
    for(;;); // Don't proceed, loop forever
  }
  
  // Clear the display buffer
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.println("Music Bike v2.0");
  display.println("Initializing...");
  display.println("");
  display.println("Press button to");
  display.println("set zero position");
  display.display();
  delay(2000);
  
  Serial.println("Initialization complete!");
  Serial.println("Press the button to set the zero position at any time.");
  prevTime = millis();
  lastTriggerTime = millis();  // Initialize trigger time
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
  
  // Normalize yaw to 0-360 range
  while (zeroedYaw < 0) zeroedYaw += 360;
  while (zeroedYaw >= 360) zeroedYaw -= 360;
  
  // Detect forward/backward movement from acceleration in longitudinal axis
  // Assuming X-axis is along the bike's longitudinal axis
  forwardAccel = -accelY;  // May need to adjust sign based on your sensor orientation
  movingForward = (forwardAccel > DIRECTION_THRESHOLD);
  
  // Check for jump and drop conditions
  detectJumpAndDrop();
  
  // Read Hall Effect Sensor and calculate speed
  int currentHallValue = digitalRead(HALL_SENSOR_PIN);
  
  // Detect falling edge (magnet detection)
  if (currentHallValue == LOW && lastHallSensorValue == HIGH) {
    currentTriggerTime = millis();
    
    // Calculate speed if we have previous trigger data
    if (lastTriggerTime > 0) {
      unsigned long timeDiff = currentTriggerTime - lastTriggerTime;
      
      // Calculate speed in km/h: distance in cm / time in ms * conversion factor to km/h
      currentSpeed = (HALF_CIRCUMFERENCE_CM / timeDiff) * 36.0;  // 36 = 3600*10/1000 for unit conversion
    }
    
    lastTriggerTime = currentTriggerTime;
  }
  
  // If no magnet detected for a timeout period, set speed to zero
  if (millis() - lastTriggerTime > SPEED_TIMEOUT) {
    currentSpeed = 0.0;
  }
  
  lastHallSensorValue = currentHallValue;
  hallSensorValue = currentHallValue;
  
// Check if zero button is pressed (with debounce)
int reading = digitalRead(ZERO_BUTTON_PIN);

// Check for button press (falling edge)
if (reading == LOW && lastButtonState == HIGH) {
  // Debounce
  if ((millis() - lastDebounceTime) > debounceDelay) {
    // Set current angles as zero reference
    pitchOffset = pitch;
    rollOffset = roll;
    yawOffset = yaw;
    
    // Show confirmation
    display.clearDisplay();
    display.setTextSize(1);
    display.setCursor(0, 0);
    display.println("Zero position set!");
    display.println("Pitch: " + String(pitch, 1));
    display.println("Roll: " + String(roll, 1));
    display.println("Yaw: " + String(yaw, 1));
    display.display();
    
    Serial.println("Zero position set!");
    
    delay(1000);
    lastDebounceTime = millis(); // Reset debounce timer
  }
}
lastButtonState = reading;
  
  // Update display at defined interval
  if (currentMillis - lastDisplayUpdate >= displayUpdateInterval) {
    lastDisplayUpdate = currentMillis;
    updateDisplay(zeroedPitch, zeroedRoll, zeroedYaw);
  }
  
  // Update serial output at defined interval
  if (currentMillis - lastSerialUpdate >= serialUpdateInterval) {
    lastSerialUpdate = currentMillis;
    printSerialData(zeroedPitch, zeroedRoll, zeroedYaw);
  }
}

void detectJumpAndDrop() {
  // Vertical acceleration (may need to adjust axis based on sensor orientation)
  float verticalAccel = accelZ - 1.0;  // Subtract 1g to get deviation from gravity
  
  // Jump detection - Start of jump (weightlessness)
  if (!inJumpState && verticalAccel < JUMP_THRESHOLD) {
    inJumpState = true;
    jumpStartTime = millis();
  }
  
  // End of jump (landing impact)
  if (inJumpState && verticalAccel > LANDING_THRESHOLD) {
    unsigned long jumpDuration = millis() - jumpStartTime;
    
    // Only count as jump if duration exceeds minimum
    if (jumpDuration > JUMP_DURATION_MIN) {
      jumpDetected = true;
      lastJumpTime = millis();
      Serial.println("JUMP DETECTED! Duration: " + String(jumpDuration) + "ms");
      Serial.println("Landing G-force: " + String(verticalAccel + 1.0) + "g");
    }
    
    inJumpState = false;
  }
  
  // Drop detection (strong impact without prior weightlessness)
  if (!inJumpState && verticalAccel > DROP_THRESHOLD) {
    dropDetected = true;
    lastDropTime = millis();
    Serial.println("DROP DETECTED! Impact G-force: " + String(verticalAccel + 1.0) + "g");
  }
  
  // Reset jump indicator after display time
  if (jumpDetected && (millis() - lastJumpTime > EVENT_DISPLAY_DURATION)) {
    jumpDetected = false;
  }
  
  // Reset drop indicator after display time
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
  
  // Convert raw values to g's
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
  
  // Convert raw values to degrees/sec
  gyroX = gx / 131.0;
  gyroY = gy / 131.0;
  gyroZ = gz / 131.0;
}

void calculateAngles() {
  // Calculate pitch and roll from accelerometer (in degrees)
  float accelPitch = atan2(accelY, sqrt(accelX * accelX + accelZ * accelZ)) * 180.0 / PI;
  float accelRoll = atan2(-accelX, accelZ) * 180.0 / PI;
  
  // Get time delta
  unsigned long currentTime = millis();
  float dt = (currentTime - prevTime) / 1000.0; // Convert to seconds
  prevTime = currentTime;
  
  // Calculate gyro angles
  float gyroPitch = pitch + gyroX * dt;
  float gyroRoll = roll + gyroY * dt;
  float gyroYaw = yaw + gyroZ * dt;
  
  // Apply complementary filter
  pitch = alpha * gyroPitch + (1.0 - alpha) * accelPitch;
  roll = alpha * gyroRoll + (1.0 - alpha) * accelRoll;
  yaw = gyroYaw; // No magnetometer correction for yaw
  
  // Keep yaw between 0-360
  if (yaw < 0) yaw += 360;
  if (yaw >= 360) yaw -= 360;
}

void updateDisplay(float p, float r, float y) {
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  
  // G-force at the top
  display.setCursor(0, 0);
  display.print("G:");
  display.println(accelZ, 2);
  
  // Jump and drop indicators directly under G-force
  display.setCursor(0, 10);
  display.print("Jump:");
  display.println(jumpDetected ? "YES!" : "No");
  
  display.setCursor(64, 10);
  display.print("Drop:");
  display.println(dropDetected ? "YES!" : "No");
  
  // Skip a line, then show other metrics
  display.setCursor(0, 25);
  display.print("P:");
  display.print(p, 1);
  
  display.setCursor(64, 25);
  display.print("R:");
  display.println(r, 1);
  
  display.setCursor(0, 35);
  display.print("Y:");
  display.println(y, 1);
  
  // Speed and direction
  display.setCursor(0, 45);
  display.print("Spd:");
  display.print(currentSpeed, 1);
  
  display.setCursor(64, 45);
  display.print("Dir:");
  display.println(movingForward ? "Fwd" : "Rev");
  
  display.display();
}

void printSerialData(float p, float r, float y) {
  Serial.println("--- Sensor Data ---");
  Serial.print("Pitch: "); Serial.print(p, 2);
  Serial.print(" Roll: "); Serial.print(r, 2);
  Serial.print(" Yaw: "); Serial.println(y, 2);
  
  Serial.print("Speed: "); Serial.print(currentSpeed, 2); Serial.println(" km/h");
  Serial.print("Direction: "); Serial.println(movingForward ? "Forward" : "Reverse");
  Serial.print("Hall Sensor: ");
  Serial.println(hallSensorValue == LOW ? "Magnet Detected" : "No Magnet");
  
  Serial.print("G-Force: "); Serial.println(accelZ, 2);
  Serial.print("Vertical Accel: "); Serial.println(accelZ - 1.0, 2);
  Serial.print("Forward Accel: "); Serial.println(forwardAccel, 2);
  
  if (jumpDetected) Serial.println("JUMP DETECTED!");
  if (dropDetected) Serial.println("DROP DETECTED!");
  
  Serial.println();
}