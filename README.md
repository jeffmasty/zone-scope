# zone-scope

**Real-time audio visualization toolkit for Java / Swing**

A lightweight, low-latency Java / Swing spectroscope. Visualizes audio files and live audio (JavaSound/Jack) with a spectrogram, spectrometer, RMS meter as well as caret/seek playback. Built with minimal allocations for continuous real-time rendering for the [JudahZone](https://github.com/jeffmasty/JudahZone) project.

---

## Features

- **Live audio capture** – JACK or JavaSound (fallback) input with rolling RMS and spectrogram displays.
- **File mode** – load audio files, precompute FFT transforms, inspect spectrum with a draggable caret/seek control.
- **Spectrometer** – adjustable frequency response (20 Hz – 14 kHz), sensitivity and tilt controls, logarithmic frequency axis.
- **Zoomable time-domain view** – pan/zoom large recordings with mouse wheel and drag.
- **Shared playback UI** – single `BoomBox` control delegates to low-level `PlayAudio` player for file playback/seeking.
- **Standalone runnable** – works outside JudahZone with zero configuration (JavaSound mode).
- **Zero-copy RT path** – allocation-free audio callbacks suitable for continuous visualization.

---

## Quick Start (Standalone Mode)

### Prerequisites
- **Java 21** or newer (tested on OpenJDK 21).
- **Maven 3.8+** (build tool).
- **Memory to store uncompressed audio + spectra if importing large files.

### Clone and Build

[zone-scope-full.jar](zone-scope-full.jar)

**Recommended**: clone the parent aggregator (builds all modules in one step):

	git clone https://github.com/jeffmasty/meta-zone.git
	cd meta-zone

Build zone-scope:

	mvn -pl zone-scope -am clean package

Alternative: build all modules: 

	mvn clean package

Note: If you cd into zone-scope and run mvn package directly, Maven expects the parent pom.xml at the relative path defined in the module's pom.xml. Building from the aggregator root is simpler.

### Run

After building, launch the the shaded (fat) JAR:

	java -jar ../zone-scope/target/zone-scope-0.3-SNAPSHOT-shaded.jar

Or, with all projects libraries in place, the slim jar:

	java -jar ../zone-scope/target/zone-scope-0.3-SNAPSHOT.jar

The app opens a Swing window with JavaSound input controls, live spectrogram, RMS meter, spectrometer and audio player. 

---

## How It Works

**Real-time path** (zero-allocation):
- Audio callbacks (`process(float[] left, float[] right)`) snapshot data into a ring buffer (`Recording`).
- When enough samples accumulate (configurable buffer size), the `Analysis<T>` base class submits an off-thread FFT job to an executor.
- Subclass `Transformer` performs windowed FFT (Hamming window via TarsosDSP) and computes modulus + RMS metrics.
- Results (`Transform` objects containing amplitudes and RMS) are delivered to the UI via a `Consumer<T>` callback.
- UI components (`Spectrogram`, `Spectrometer`, `RMSMeter`) repaint using the latest `Transform` data.

**Spectrometer**:
- X-axis: log-scale frequency (min 20 Hz, max Nyquist/2), consistent with human perception.
- Y-axis: amplitude/power mapped to dB, normalized to color intensity or bar height.
- Sensitivity and tilt controls adjust the visible dynamic range and frequency response.

---

## Key Classes

- **`JudahScope`** – main view/controller, mode switching (live/stopped/file), wiring between components.
- **`Spectrometer`** – adjustable real-time frequency spectrum display (55 Hz – 14 kHz).
- **`TimeDomain`** – handles zoom, caret, mouse interaction and delegates to visualization components.
- **`Spectrogram`** – rolling spectrogram (frequency vs. time heatmap).
- **`RMSMeter`** – peak/RMS level meter with decay.
- **`BoomBox`** – playback UI wrapper (play/pause/seek) that delegates low-level `PlayAudio` player.
- **`Analysis<T>`** – base class for "copy & analyze" offline effects; standardizes snapshot/FFT workflow.
- **`Transformer`** – Analysis implementation that produces FFT `Transform` objects (amplitudes + RMS).

---

## Dependencies

Managed by the parent `pom.xml`:

- **`zone-core`** – shared utilities (RT logger, constants, memory pools).
- **`zone-fx`** – analysis base classes.
- **`zone-gui`** – GUI helpers and widgets.
- **`zone-javax`** – JavaSound input/output helpers (`JavaxIn`, `JavaxOut`).

When building inside `meta-zone`, all versions/repos are inherited from the parent.

---

## Runtime Notes (Linux)

- **File playback** works out-of-the-box via JavaSound; no JACK required.
- For full JudahZone integration (MIDI routing, FluidSynth, etc.), see the main [JudahZone README](https://github.com/jeffmasty/JudahZone).
- **JACK support** requires a running JACK server (`jackd` or `jackd2`) and native JACK libraries in embedded mode.  
- The stand-alone app uses JavaSound (higher latency).

---

## Screenshots

![zone-scope logo](/screen1.png)

---

![zone-scope logo2](/screen2.png)

---

## Credits

- **FFT / tuner**: [TarsosDSP](https://github.com/JorenSix/TarsosDSP) by Joren Six.
- **Spectrometer/Spectograph ideas**: adapted from Tarsos. 
- **JACK bindings**: [JNAJack](https://github.com/jaudiolibs/jnajack) (via `zone-jnajack` module).

---

## Links

- Main project: [JudahZone](https://github.com/jeffmasty/JudahZone)
- Aggregator: [meta-zone](https://github.com/jeffmasty/meta-zone)
- JavaSound module: [zone-javax](https://github.com/jeffmasty/zone-javax)
- Analysis Plugin(+DSP): [zone-fx](https://github.com/jeffmasty/zone-fx)
- TarsosDSP: [https://github.com/JorenSix/TarsosDSP](https://github.com/JorenSix/TarsosDSP)
- JACK Audio: [https://jackaudio.org/](https://jackaudio.org/)

---

**Enjoy visualizing your audio!** 🎵📊

---
