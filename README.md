# DepthAI
### Depth-aware AR on Android using ARCore + Gemini Vision

An Android AR application that uses ARCore's Depth API for real-time depth sensing, surface detection, and depth-accurate object placement. Built as a research project into what becomes possible when spatial understanding meets vision AI.

---

## What it does right now

**ARCore session with depth**
- Full ARCore camera passthrough with real-time plane detection
- Automatically detects whether the device supports the Depth API and enables it if available
- Depth texture rendered in real time — toggle button switches between normal camera view and depth map visualization

**Depth-accurate object placement**
- Tap on any detected surface to place a 3D object anchored to that point
- On depth-supported devices, placed objects are correctly occluded by real-world geometry in front of them — a box behind your hand disappears behind it
- Supports up to 20 simultaneous anchors
- Tracks anchor state — objects only render when ARCore is actively tracking

**Compose UI overlay**
- Jetpack Compose UI rendered on top of the OpenGL surface
- Live status messages: tracking state, surface detection progress, placement instructions
- Depth toggle button — switch between camera view and depth visualization at runtime

**Tracking awareness**
- Detects tracking loss and surfaces it to the user
- Guides the user through the initialization and surface detection flow

---

## Tech stack

- **Kotlin** — Android app
- **ARCore** — session management, plane detection, hit testing, anchor placement
- **ARCore Depth API** — real-time depth texture, occlusion rendering
- **OpenGL ES 2.0** — camera passthrough, depth visualization, 3D object rendering
- **Jetpack Compose** — UI overlay on top of the GL surface

---

## Device requirements

- Android 8.0+
- ARCore supported device
- Depth API supported device for occlusion rendering (falls back gracefully without it)

---

## Project structure

```
app/src/main/java/com/varun/depthai/
├── MainActivity.kt          # ARCore session, GL renderer, tap handling
├── arcore/
│   ├── BackgroundRenderer   # Camera feed + depth map rendering
│   ├── DepthTextureHandler  # Depth texture lifecycle
│   ├── ObjectRenderer       # Standard 3D object (no occlusion)
│   └── OcclusionObjectRenderer # Depth-occluded 3D object
├── helpers/
│   └── TapHelper            # Touch event queue for GL thread
└── ui/
    └── theme/               # Compose theme
```

---

## What's next

The foundation is the spatial understanding layer — depth, planes, anchors. The next phase adds Gemini Vision on top: point the camera at an object, Gemini identifies it, and a 3D label anchors to its real-world position via hit testing. The goal is AI-generated AR overlays on arbitrary physical objects without any pre-training on specific objects.

---

*Part of ongoing research into spatial AI and depth-aware augmented reality.*
