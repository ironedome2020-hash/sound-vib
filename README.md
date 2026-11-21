# SoundLab
Kotlin + Jetpack Compose. Mic -> FFT (Hann, 2048 pt, 48 log bands) or Daubechies-4 wavelet (9 levels).
History ~8 records/s (max 3000). Saves CSV -> Downloads/SoundLab, PNG spectrogram -> Pictures/SoundLab.
CSV: b0..b47 are dBFS per band (FFT: 40 Hz-16 kHz log; Wavelet: b0..b8 = level 9 (lowest) .. level 1 (highest)).

## Get the APK
A) No installs: push this folder to a new GitHub repo -> Actions tab -> "Build APK" -> download artifact SoundLab-apk.
B) Android Studio: File > Open this folder, let it sync, Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
Install on the phone (allow "install unknown apps"), grant microphone permission.
