package dev.birb.wgpu.backend

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.glfw.GLFWNativeWayland
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11

/**
 * The native renderer itself: created once, against the window the game already made.
 *
 * ## Why the handles are passed at creation rather than looked up later
 *
 * `createWmRendererOnWindow` takes the window so that Rust can create the **surface before it asks for
 * an adapter**, and therefore ask for an adapter that can actually present to it. A renderer created
 * without a window picks an adapter on its own and then finds out it cannot present, which on a machine
 * with two GPUs is the usual outcome rather than the rare one.
 *
 * ## Where this runs on 1.21.1, and why not earlier
 *
 * 26.1 hands the renderer creation to `Minecraft`'s own backend selection - the game asks a `GpuBackend`
 * for its device during construction. **1.21.1 has no such seam**: it creates a GL context in `Window`
 * and never asks anyone. So the renderer is created from the client setup work queue instead, which runs
 * on the first tick - after `Minecraft`'s constructor has finished and therefore after there is a window
 * with a handle, and before the first frame is drawn.
 *
 * The GL context that 1.21.1 creates is left alone. It exists because the game insists on it; the
 * surface is a second, independent one on the same HWND, and nothing here draws through GL.
 */
object WgpuRenderer {
	/** The renderer pointer, or `0` before [create] has run and after a failed one. */
	@Volatile
	private var handle: Long = 0L

	/** Whether [create] has been tried, so a second tick is not a second renderer. */
	@Volatile
	private var attempted: Boolean = false

	/** The renderer pointer, or `0`. The bridge is handed this for every call that needs a renderer. */
	@JvmStatic
	fun handle(): Long = handle

	/**
	 * Creates the renderer once, or reports why it could not be.
	 *
	 * @return whether there is a renderer now
	 */
	@JvmStatic
	fun create(): Boolean {
		if (attempted) {
			return handle != 0L
		}

		attempted = true

		val client = Minecraft.getInstance() ?: run {
			WgpuMcMod.LOGGER.error("wgpu-mc: there is no client to take a window from")
			return false
		}

		val windowHandle = client.window.window
		val (display, window) = windowHandles(windowHandle)

		// The framebuffer size, in pixels, read from GLFW rather than from the game's window object:
		// this runs before the game's own sizes have settled, and a surface configured for the wrong
		// size is a stretched picture until the first resize.
		val width = IntArray(1)
		val height = IntArray(1)
		GLFW.glfwGetFramebufferSize(windowHandle, width, height)

		return try {
			val created = WgpuNative.createWmRendererOnWindow(display, window, width[0], height[0])

			if (created == 0L) {
				WgpuMcMod.LOGGER.error(
					"wgpu-mc: the renderer could not be created on this window, so nothing will be " +
						"drawn by it; the log above says which step failed",
				)
				false
			} else {
				handle = created
				WgpuMcMod.LOGGER.info(
					"wgpu-mc: renderer created on a {}x{} window",
					width[0],
					height[0],
				)
				true
			}
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.error("wgpu-mc: creating the renderer failed: {}", throwable.toString())
			WgpuMcMod.LOGGER.debug("wgpu-mc renderer creation failure", throwable)
			false
		}
	}

	/**
	 * Translates a GLFW window handle into the display/window pair the surface needs.
	 *
	 * Two of the four platforms answer with a display and two do not, which is the whole reason this is
	 * a pair rather than one number: on Windows the display is not part of the identity of a window - the
	 * instance is read back from the window itself - while on X11 the window id is only meaningful
	 * together with the `Display` it belongs to. Rust discards whichever of the two it does not need.
	 */
	private fun windowHandles(windowHandle: Long): Pair<Long, Long> = when (GLFW.glfwGetPlatform()) {
		GLFW.GLFW_PLATFORM_WIN32 -> 0L to GLFWNativeWin32.glfwGetWin32Window(windowHandle)
		GLFW.GLFW_PLATFORM_X11 ->
			GLFWNativeX11.glfwGetX11Display() to GLFWNativeX11.glfwGetX11Window(windowHandle)
		GLFW.GLFW_PLATFORM_COCOA ->
			GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle) to 0L
		GLFW.GLFW_PLATFORM_WAYLAND ->
			0L to GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
		else -> throw IllegalStateException(
			"wgpu-mc: unsupported GLFW platform ${GLFW.glfwGetPlatform()}",
		)
	}
}