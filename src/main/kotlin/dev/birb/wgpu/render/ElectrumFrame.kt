package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack

/**
 * Presents a frame the engine drew, in place of the one GLFW would have swapped.
 *
 * **This is the first thing this branch does with the engine, and it is a test rather than a
 * renderer**: the frame is one cleared colour in a target of its own. What it proves is the part no
 * other test can reach - that the graphics half of the JNI bridge works end to end. A
 * `wgpu::Texture` and a view over it come back from two wrappers, the shared encoder records a clear
 * into them, `blit_from_texture` blits them into an acquired swapchain image, and `presentSurface`
 * hands that image over. Every one of those steps is a pointer this side holds and a layout it wrote
 * into a direct buffer, and none of it had run on Java 21 before.
 *
 * ## Why the frame has to be taken away rather than added to
 *
 * A wgpu surface and a GL context cannot both present to one window: whichever swaps last is what
 * the compositor shows, and the two are not synchronised with each other. So a frame presented here
 * is a frame GL must not also present, and the redirect in `RenderSystemMixin` is what makes the
 * choice - it is the same place the pre-26.1 mod overwrote, for the same reason.
 *
 * ## The switch
 *
 * Off, nothing in this class is called and the game swaps buffers the way it always has. On, the
 * window is this cleared colour, because there is nothing else in the frame yet.
 *
 * It is a system property because **there is no settings screen on this branch yet**. On 26.1 every
 * diagnostic like this is a setting with a row on the Electrum page, and that is where this belongs
 * as soon as the settings bridge is ported. Until then a property is the honest shape: it says in
 * the command line what it did, and it cannot be left behind in a directory by accident.
 *
 * `gradlew runClient -Pwgpu_mc_present_test`
 */
object ElectrumFrame {
	/** The property that turns the present test on. Whatever reads it must agree with the build script. */
	const val PRESENT_TEST_PROPERTY: String = "wgpu_mc.present_test"

	/** `GpuFormat::RGBA8_UNORM` - the format number the ABI carries for it. */
	private const val FORMAT_RGBA8_UNORM: Int = 7

	/**
	 * The two usage bits the ABI's own numbering uses, which is **not** wgpu's.
	 *
	 * `create_texture` reads bit 0 as `COPY_DST`, bit 2 as `TEXTURE_BINDING` and bit 3 as
	 * `RENDER_ATTACHMENT` and adds `COPY_SRC` itself, so a target that is drawn into and then sampled
	 * by the presenting blit is `4 or 8`.
	 */
	private const val USAGE_TEXTURE_BINDING: Int = 4
	private const val USAGE_RENDER_ATTACHMENT: Int = 8

	/** ARGB, the encoding `clear_color_texture` takes - the blue the window turns when this works. */
	private const val CLEAR_COLOUR: Int = 0xFF1E3FA8.toInt()

	/** Zero is "follow the vsync setting", so a reconfigure here cannot disagree with creation. */
	private const val PRESENT_MODE_FROM_SETTINGS: Int = 0

	@Volatile
	private var enabled: Boolean = java.lang.Boolean.getBoolean(PRESENT_TEST_PROPERTY)

	private var target: Long = 0L
	private var targetView: Long = 0L
	private var encoder: Long = 0L
	private var targetWidth: Int = 0
	private var targetHeight: Int = 0
	private var presents: Long = 0L

	/** Whether a frame presented here is the frame the window shows. */
	@JvmStatic
	fun isEnabled(): Boolean = enabled

	/**
	 * Draws and presents one frame, and says whether it did.
	 *
	 * `false` means the caller must swap buffers itself - which is every frame with the test off,
	 * every frame before the renderer exists, and any frame this side could not present. It is
	 * deliberately not an exception: this runs inside `RenderSystem.flipFrame`, and a throw there is
	 * a crash on the render thread rather than a frame that did not happen.
	 *
	 * @param window the GLFW window handle `flipFrame` was given
	 * @return whether this presented the frame
	 */
	@JvmStatic
	fun present(window: Long): Boolean {
		if (!enabled) {
			return false
		}

		val renderer = WgpuRenderer.handle()
		if (renderer == 0L) {
			return false
		}

		var width = 0
		var height = 0
		MemoryStack.stackPush().use { stack ->
			val w = stack.mallocInt(1)
			val h = stack.mallocInt(1)
			GLFW.glfwGetFramebufferSize(window, w, h)
			width = w.get(0)
			height = h.get(0)
		}
		if (width <= 0 || height <= 0) {
			return false
		}

		if (!ensureTarget(renderer, width, height)) {
			enabled = false
			return false
		}

		val surface = WmJni.acquireNextTexture(renderer)
		if (surface == 0L) {
			// The surface handed over nothing - the window is occluded, or the swapchain is being
			// rebuilt. It is still this side's frame, so the caller must not swap buffers.
			return true
		}

		// Recorded into the frame's own encoder and submitted by the blit below, which is the one
		// submission point there is: a submission of its own here would be a second frame boundary,
		// and the engine's frame counter is what the terrain gather reads.
		WmJni.clearColorTexture(encoder, target, CLEAR_COLOUR)
		WmJni.blitFromTexture(renderer, targetView, surface)
		WmJni.presentSurface(renderer, surface)

		presents++
		if (presents == 1L) {
			WgpuMcMod.LOGGER.info("wgpu-mc: presented a frame wgpu drew, {}x{}", width, height)
		}
		return true
	}

	/**
	 * Makes sure there is a target of the window's size to draw the frame into.
	 *
	 * The surface is configured here rather than only at creation because a window that has been
	 * resized has a swapchain of the old size, and `acquire_next_texture` cannot tell a stale
	 * configuration from a driver hiccup: it recovers to the size it was last configured with. The
	 * call is cheap when nothing moved - `configure_surface_inner` compares the size and the present
	 * mode and returns early - which is why it is safe to ask every time the size changes.
	 */
	private fun ensureTarget(renderer: Long, width: Int, height: Int): Boolean {
		if (target != 0L && width == targetWidth && height == targetHeight) {
			return true
		}

		releaseTarget()

		return try {
			WmJni.configureSurface(renderer, width, height, PRESENT_MODE_FROM_SETTINGS)

			target = WmJni.createTexture(
				renderer,
				FORMAT_RGBA8_UNORM,
				width,
				height,
				1,
				USAGE_TEXTURE_BINDING or USAGE_RENDER_ATTACHMENT,
				1,
				"wgpu-mc present test target",
			)
			targetView = WmJni.createTextureView(renderer, target, 0, 0, 1)

			// One handle for the life of the target. It is an empty box on the native side - the whole
			// process records into one encoder - so this is an identity rather than a resource, and
			// holding it across frames is what the ABI expects.
			if (encoder == 0L) {
				encoder = WmJni.createCommandEncoder(renderer)
			}

			targetWidth = width
			targetHeight = height
			true
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.error("wgpu-mc: could not make a render target to present: {}", throwable.toString())
			WgpuMcMod.LOGGER.debug("wgpu-mc present target failure", throwable)
			false
		}
	}

	/** Gives the target back, view first: the view holds a handle to the texture it was made from. */
	private fun releaseTarget() {
		if (targetView != 0L) {
			WmJni.dropTextureView(targetView)
			targetView = 0L
		}
		if (target != 0L) {
			WmJni.dropTexture(target)
			target = 0L
		}
		targetWidth = 0
		targetHeight = 0
	}
}