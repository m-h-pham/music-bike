import tensorflow as tf
import numpy as np
import os
import glob
from sklearn.model_selection import train_test_split

def load_real_data(timesteps=150):
    X = []
    y = []
    label_map = {}
    
    # Find all data files in current directory
    files = glob.glob('./*.txt')
    
    if not files:
        raise FileNotFoundError("No .txt files found in directory")
    
    for file_path in files:
        # Extract trick name from filename (format: [trick]_[num].txt)
        # TODO: Update this with hardcoded trick names when we decide what tricks to use
        trick_name = os.path.basename(file_path).split('_')[0]
        
        # Read IMU data from file
        sequence = []
        with open(file_path, 'r') as f:
            for line in f:
                if line.startswith("IMU: "):
                    try:
                        # Extract and convert values
                        values = line.strip().replace("IMU: ", "").split(", ")
                        pitch, roll, yaw = map(float, values)
                        sequence.append([pitch, roll, yaw])
                    except:
                        continue  # Skip malformed lines
        
        # Pad/truncate to fixed timesteps
        if len(sequence) < timesteps:
            # Pad with last value repeated
            pad_needed = timesteps - len(sequence)
            sequence += [sequence[-1]] * pad_needed if sequence else [[0.0, 0.0, 0.0]] * pad_needed
        else:
            sequence = sequence[:timesteps]
        
        X.append(sequence)
        y.append(trick_name)
    
    # Create label mapping
    unique_labels = sorted(list(set(y)))
    if len(unique_labels) != 3:
        raise ValueError(f"Exactly 3 classes required. Found: {unique_labels}")
    label_map = {label: idx for idx, label in enumerate(unique_labels)}
    
    return np.array(X, dtype=np.float32), np.array([label_map[label] for label in y])

def create_imu_model(input_shape, num_classes):
    model = tf.keras.Sequential([
        tf.keras.layers.InputLayer(input_shape=input_shape),
        tf.keras.layers.Conv1D(64, 3, activation='relu'),
        tf.keras.layers.MaxPooling1D(2),
        tf.keras.layers.BatchNormalization(),
        tf.keras.layers.Conv1D(128, 3, activation='relu'),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(64, activation='relu'),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(num_classes, activation='softmax')
    ])
    
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss='sparse_categorical_crossentropy',
        metrics=['accuracy']
    )
    
    return model

# Parameters
TIMESTEPS = 100  # 2 seconds at 50Hz
NUM_FEATURES = 3  # pitch, roll, yaw
NUM_CLASSES = 3
BATCH_SIZE = 32
EPOCHS = 50

# Load and prepare data
X, y = load_real_data(TIMESTEPS)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, stratify=y)

# Create and train model
model = create_imu_model((TIMESTEPS, NUM_FEATURES), NUM_CLASSES)
model.summary()

# Callbacks
checkpoint = tf.keras.callbacks.ModelCheckpoint(
    'best_model.h5',
    monitor='val_accuracy',
    save_best_only=True,
    mode='max'
)

early_stop = tf.keras.callbacks.EarlyStopping(
    patience=10,
    restore_best_weights=True
)

# Train model
history = model.fit(
    X_train, y_train,
    validation_split=0.1,
    epochs=EPOCHS,
    batch_size=BATCH_SIZE,
    callbacks=[checkpoint, early_stop]
)

# Evaluate
test_loss, test_acc = model.evaluate(X_test, y_test)
print(f"\nTest accuracy: {test_acc:.2f}")

# Convert to TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

with open('trick_detector.tflite', 'wb') as f:
    f.write(tflite_model)

# Optional quantization
converter.optimizations = [tf.lite.Optimize.DEFAULT]
quantized_model = converter.convert()

with open('trick_detector_quant.tflite', 'wb') as f:
    f.write(quantized_model)

print("\nConversion successful. Models saved as:")
print("- trick_detector.tflite (FP32)")
print("- trick_detector_quant.tflite (INT8)")
