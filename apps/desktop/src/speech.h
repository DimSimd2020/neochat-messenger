#pragma once

#include <QString>

// On-device speech recognition for voice messages and circles (sherpa-onnx with the Russian
// Zipformer model that the core downloads). Nothing leaves the computer.
namespace speech {

struct Result {
    bool ok = false;
    QString text; // empty when no speech was found
    QString error;
};

// False when this build has no recognizer runtime next to the executable.
bool available();

// Recognizes the audio track of `mediaPath` using the model files in `modelDir`. Blocks; run it
// off the interface thread.
Result transcribe(const QString &mediaPath, const QString &modelDir);

// Frees the loaded model (about 150 MB of memory); the next transcribe() loads it again.
void release();

}
