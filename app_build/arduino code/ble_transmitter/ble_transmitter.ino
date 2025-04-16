#include <Wire.h>
// Because this is a BLE application please use seeed XIAO BLE Sense in the mbed library as opposed to non mbed version.
#include <ArduinoBLE.h>

#define PIN_CHG 23
#define CHARACTERISTIC_SIZE 20  // default is 20, but utilize MTU change on central to set to 24. 
#define DATA_SIZE 18  // The size of the data payload in a packet (24 - 3 for overhead anf 4)

BLEService customService("10336bc0-c8f9-4de7-b637-a68b7ef33fc9");  // 1816 is the defined UUID for cycling tech...
BLECharacteristic txCharacteristic("43336bc0-c8f9-4de7-b637-a68b7ef33fc9",  // Custom characteristic UUID
                                   BLERead | BLEWrite | BLENotify, 
                                   CHARACTERISTIC_SIZE);  // Characteristic value length
BLEDescriptor myDescriptor("00002902-0000-1000-8000-00805f9b34fb", "0");  // Used for enabling notifications.

uint8_t currentPacketNumber = 0; // Used to track the order of packets.
uint8_t rear_packet_index = 0; // Used to track the order of packets from the rear sensor.
uint8_t side_packet_index = 0; // Used to track the order of packets from the side sensor.
int payloadStartingIndex = 6; // Starts at 6 to make room for timestamp (4 bytes) and IDs and counters (2 bytes). MUST BE EVEN. 

void setup() {
  Serial.begin(115200);
  delay(100);
  pinMode(LED_BUILTIN, OUTPUT);

  Serial.println("Starting...");

  Wire.begin(); //This resets to 100kHz I2C
  Wire.setClock(1000000); //Sensor has max I2C freq of 1MHz change to 400000 for 400kHz

  delay(50);
  // Initialize BLE hardware
  if (!BLE.begin()) {
    while (1) {
      Serial.println("Starting BLE failed!");
      delay(1000);
    }
  }

  delay(100);
  digitalWrite(LED_BUILTIN, HIGH);

  // Set the local name and service information
  BLE.setLocalName("LocationWakelockKeychain");
  BLE.setAdvertisedService(customService);
  // Add custom characteristic
  customService.addCharacteristic(txCharacteristic);
  txCharacteristic.addDescriptor(myDescriptor);
  // Add custom service
  BLE.addService(customService);
  // Start advertising
  BLE.advertise();
  Serial.println("Bluetooth device active, waiting for connections...");
}

void loop() {
  //delay(100);
  BLEDevice central = BLE.central();
  Serial.println("Waiting to connect to central.");
  digitalWrite(LED_BUILTIN, HIGH);  // turn the LED on (HIGH is the voltage level)
  delay(250);                      
  digitalWrite(LED_BUILTIN, LOW);   // turn the LED off by making the voltage LOW
  delay(250);                      
  if (central) {
    Serial.print("Connected to central: ");
    Serial.println(central.address());

    while (central.connected()) {
      sendSensorData(0x01, currentPacketNumber);
      currentPacketNumber++;
      delay(55);  // Delay to ensure I am not sending bluetooth packets like crazy.
      sendSensorData(0x02, currentPacketNumber);
      currentPacketNumber++;
      delay(55);  // Delay to ensure I am not sending bluetooth packets like crazy.
    }
    Serial.print("Disconnected from central: ");
    Serial.println(central.address());
    delay(1000);
  }
}

void sendSensorData(uint8_t sensorPacketIndex, uint8_t readCount) {

  uint8_t packet[CHARACTERISTIC_SIZE];

  int payloadIndex = payloadStartingIndex;
  // Initialize packet header
  packet[0] = sensorPacketIndex;
  packet[1] = readCount;

  uint32_t timestamp = millis();  // Get the current timestamp in milliseconds
  // Add timestamp to the packet
  packet[2] = (timestamp >> 24) & 0xFF;
  packet[3] = (timestamp >> 16) & 0xFF;
  packet[4] = (timestamp >> 8) & 0xFF;
  packet[5] = timestamp & 0xFF;


  txCharacteristic.writeValue(packet, CHARACTERISTIC_SIZE);
}

