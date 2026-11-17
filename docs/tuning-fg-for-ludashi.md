# Tuning FG — notes for Ludashi

Hi Steven,

This is a short technical note for the Tuning FG pull request on Ludashi 5.0.

Tuning FG is part of Winlator Tuning, my fork of Winlator Ludashi. I am bringing this piece upstream so Ludashi users can use the same engine without depending on the full Tuning tree.

Status

Tuning FG is still under development. It started from an earlier study: a long pass of logging, device testing and timing work before the current design. The design and analysis were done by hand. The code was written with help from Opus 5.5 and Grok 4.7.

If you take it in, it may still contain bugs, and it may not be ideal for every user or every game. Most Winlator Tuning users who tried it think it is a solid implementation, which is why I am opening this PR toward Ludashi.

The X++ path took the most work, especially Dynamic mode. Dynamic needs more pacing and timeline math than Fixed, and matching Ludashi’s present order on X++ was slow to get right.

Branch: tuning-frame-generation on palazos/winlatorCmod, base your current 5.0.

What it is

Tuning FG is a third frame-generation engine next to LSFG and WFG. Only one engine owns the swapchain at a time.

Native code lives alone under app/src/main/cpp/winlator/renderer/vulkan/tuningFG/. No Lossless Scaling DLL and no WFG library. Motion search and blend run as Vulkan compute.

Modes: Fixed and Dynamic. Motion quality: Low / Medium / High / Extreme (default High). Works on Vulkan X11 and Vulkan X++. EGL and DisplayX are unsupported for Tuning FG.

In the in-game sidebar Rendering section there is now a place to pick the frame-generation engine (Tuning FG / LSFG / WFG). The Tuning controls sit under that selector. Those choices are persisted on the live shortcut (or the container if there is no shortcut), so the engine, switch, mode, multiplier and motion quality survive leaving the game. Android view-state restore is turned off on those controls so a rotation or recreation does not overwrite the saved values.

Fixed mode

Fixed caps the game at a fraction of the panel refresh (X2 or X3) and fills the remaining refreshes with interpolated frames.

Source rate is about refresh / multiplier. On X++, Present reads that cap through VulkanRendererFrameGenSourceCap() so the game stays paced even when the Java FPS limit alone would not. A still image (loading screens) can lift the X3 cap so the game is not stuck slow. Uses less power and keeps a steady cadence.

Dynamic mode

Dynamic does not cap the game. It only fills refreshes the game leaves empty, up to vsync, and never past it. Every real frame is shown. About one game interval of interpolation latency is expected.

A content timeline (dynX) follows the panel, with a one-interval hold. Between two real frames it blends at t = (x - a.ct) / (f.ct - a.ct). The raw frame is shown only when that step is outside (0, 1); otherwise the blend keeps an even pan.

On X++, Dynamic copies the real frame, queues one blend for the next refresh, and returns. A second image is queued only when the next game frame is more than one refresh away. Use the system driver in Renderer Driver. Another renderer driver stops Dynamic from filling refreshes. If the GPU overloads, Tuning steps motion quality down, or presents through the normal renderer while the switch stays on.

Integration points

These are the hooks to wire Tuning FG into your tree. LSFG and WFG stay on their own path.

Native (own folder): tuningFG/FrameGen.cpp, shaders, and the public hooks in FrameGen.h.

- VulkanRendererFrameGenRender — called from renderFrame; returns true when Tuning handled the frame
- VulkanRendererFrameGenNoteContent — new game pixels
- VulkanRendererFrameGenConfigure / GetStats / Destroy
- VulkanRendererFrameGenNoteVsync — Choreographer timestamps for Dynamic
- VulkanRendererFrameGenSourceCap / SourceUncapped — Fixed pacing on X++

Vulkan renderer: VulkanRendererContext / filters / JNI. Offscreen compose path, then Tuning present instead of a normal swapchain present when Tuning is on.

X++: native_renderer.cpp, native_screen.cpp, native_jni.cpp, NativeX11View.java.

- Acquire the swapchain image, then latch the scene (nativeFrameReady)
- Reuse one Draw slot for every present in the call; advance currentFrame once
- Present notes content so Dynamic can wake without waiting for the next Draw
- Power-saver cap applied in PacingLimit()

Java UI / sidebar: FrameGenEngine (tuning / lsfg / wfg), Rendering sidebar in left_sidebar_original.xml (SPFrameGenEngine plus the Tuning block), XServerDisplayActivity.setupSidebarFrameGen, settings/shortcut editors, HUD stats row.

Persistence: frameGenPut writes to the live shortcut (or container). Extras: frameGenEngine, graphicsFrameGen, graphicsFrameGenMode, graphicsFrameGenMultiplier, graphicsFrameGenMotion. The engine spinner and Tuning controls use setSaveEnabled(false) so view-state restore does not flip them after the saved values are applied.

License and feedback

The Frame Gen work in this PR is under GPLv3, same as Ludashi.

If anyone improves Tuning FG, please let me know. I want to keep the design aligned and pull useful fixes back into the Tuning line.

Thanks for looking at it.
— palazos
