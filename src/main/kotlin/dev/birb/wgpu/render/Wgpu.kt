package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * The JVM half of the interface the native renderer calls back through.
 *
 * **The name and the signatures are a contract with `rust/wgpu-mc-jni`, not a choice made here.** The
 * engine resolves this class by name while it loads - `setClassLoader` calls `resolve_tint_helpers`,
 * which is the one moment it has both the game's class loader and a `JNIEnv` - and it looks up the four
 * tint methods eagerly. A class that is missing, or a method whose descriptor has drifted, fails the
 * whole load: `WgpuMcModClient` then logs that the client will run on Minecraft's own renderer, and
 * nothing says which name was wrong. The engine's `cargo test` is what keeps the two lists in step.
 *
 * The callbacks that exist here are the ones the engine resolves *now*: the four colour helpers, and the
 * two sinks it reports through. `helperSetBlockStateIndex` and `helperSetPartIndex` are declared because
 * the engine calls them by name, and are the two seams the terrain and entity ports land on.
 */
object Wgpu {
	/**
	 * Whether the native renderer has been created.
	 *
	 * Not "the game is ready": the renderer exists while the window is built, minutes before there is a
	 * world to draw. A resource reload and the block cache need the renderer; only the terrain needs the
	 * game.
	 */
	@Volatile
	private var rendererLive: Boolean = false

	@JvmStatic
	fun isRendererLive(): Boolean {
		return rendererLive
	}

	@JvmStatic
	fun setRendererLive(live: Boolean) {
		rendererLive = live
	}

	/**
	 * Where the engine reports a panic. It calls this instead of unwinding, because a panic on a JNI
	 * frame aborts the process - so this is the last thing that runs, and it is on the render thread.
	 */
	@JvmStatic
	fun rustPanic(message: String) {
		WgpuMcMod.LOGGER.error(message)
		throw IllegalStateException(message)
	}

	/** The engine's own log lines that it has no logger for. */
	@JvmStatic
	fun rustDebug(message: String) {
		WgpuMcMod.LOGGER.info("[Engine] {}", message)
	}

	/**
	 * Records which key the native side assigned to a block state.
	 *
	 * **Nothing is recorded yet, and that is the honest state of this branch.** The 26.1 build writes the
	 * key back onto the state through `RustBlockStateAccessor`, a duck interface mixed into `BlockState`,
	 * and then hands the state's occlusion shapes to `BlockFaceFlags`. Both belong to the terrain port:
	 * the engine only calls this while it is baking block models, and this branch never starts a bake, so
	 * the call cannot arrive. It exists because the engine resolves it by name.
	 */
	@JvmStatic
	@Suppress("UNUSED_PARAMETER")
	fun helperSetBlockStateIndex(state: Any?, blockstateKey: Int) {
	}

	/**
	 * Records which matrix index a named part of an entity model was given.
	 *
	 * The same gap as [helperSetBlockStateIndex]: on 26.1 this fills `EntityState.matrixIndices`, which
	 * the entity renderer reads back when it uploads a model's parts. Neither half is on this branch.
	 */
	@JvmStatic
	@Suppress("UNUSED_PARAMETER")
	fun helperSetPartIndex(entity: String, part: String, index: Int) {
	}

	/**
	 * The position the block-tint callbacks are asked about, reused per bake thread.
	 *
	 * A mutable position rests on one assumption: **a tint source reads the position and does not keep
	 * it.** Every source the game ships does. Thread-local because bakes run on a pool, where one position
	 * shared between two of them is a wrong colour rather than a race that throws.
	 */
	private val tintPos: ThreadLocal<BlockPos.MutableBlockPos> =
		ThreadLocal.withInitial { BlockPos.MutableBlockPos() }

	/** The answer for a section with no colour to give. Shared, and read-only: the engine copies out of it. */
	private val EMPTY_TINTS = LongArray(0)

	/**
	 * How many tint indices are probed per state.
	 *
	 * The game ships models with `tintindex` 0 and 1 and nothing beyond, and a probe that answers `-1` is
	 * a probe the game itself would have drawn untinted - so four is three more than vanilla needs and
	 * still cheap enough to run per distinct state rather than per position.
	 */
	private const val TINT_INDEX_PROBES = 4

	/**
	 * The colour a block's face is tinted by, in the packing the engine unpacks.
	 *
	 * **`r | g << 8 | b << 16`, and every colour that crosses must be packed here.** The engine's
	 * `scale_rgb` reads this order; a table that passed the game's `ARGB` through verbatim reads on screen
	 * as a red-blue swap, and nothing counts it - the counters count *keys found*, and every key was
	 * found. One function, because two of them drifting is exactly what went wrong once.
	 */
	private fun packTint(color: Int): Int {
		val r = color shr 16 and 0xFF
		val g = color shr 8 and 0xFF
		val b = color and 0xFF

		return r or (g shl 8) or (b shl 16)
	}

	/**
	 * The colour a block's face is tinted by at a position, asked of the game itself, or `-1` for a face
	 * the game would draw untinted.
	 *
	 * The game's tint is a property of the **position** - grass follows the biome under the block - so
	 * nothing here is cached: the engine asks once per colour a block needs and keeps that answer for the
	 * block, which is exactly as far as it stays true.
	 *
	 * `BlockColors#getColor` answering `-1` is the 1.21.1 spelling of what 26.1 split into
	 * `getTintSource` returning null and `colorInWorld` returning the colour; the two are one call here.
	 */
	@JvmStatic
	fun helperGetBlockColor(x: Int, y: Int, z: Int, tintIndex: Int): Int {
		val client = Minecraft.getInstance()
		val level = client.level ?: return -1

		val pos = tintPos.get().set(x, y, z)
		val state = level.getBlockState(pos)

		return client.blockColors.getColor(state, level, pos, tintIndex)
	}

	/**
	 * The colour a fluid is drawn with at a position, or `-1` where the fluid is untinted.
	 *
	 * The fluid's own block - `FluidState#createLegacyBlock`, which is `minecraft:water` for water - is
	 * what carries the tint source, and not the block the fluid is inside: a waterlogged slab's fluid is
	 * water and its block is a slab, and asking about the slab answers the slab's tint or nothing.
	 */
	@JvmStatic
	fun helperGetFluidColor(x: Int, y: Int, z: Int): Int {
		val client = Minecraft.getInstance()
		val level = client.level ?: return -1

		val pos = BlockPos(x, y, z)
		val fluidState = level.getFluidState(pos)

		if (fluidState.isEmpty) {
			return -1
		}

		return client.blockColors.getColor(fluidState.createLegacyBlock(), level, pos, 0)
	}

	/**
	 * Every biome tint one section needs, in **one** call, packed one colour per `Long`.
	 *
	 * The layout is shared with the engine's `TintHelpers::section_tints`, which unpacks it:
	 *
	 * ```text
	 * bits 63..36  the position in the section, in Minecraft's own storage order
	 *              (`x | z << 4 | y << 8`, which is the order the engine's baker walks blocks in)
	 * bits 35..32  the model's tint index
	 * bits 31..0   the colour, in the same packing `helperGetBlockColor` returns
	 * ```
	 *
	 * **A table rather than a call per face.** A bake asks for a colour per tinted face: a grass block is
	 * five of those and a leaf block six, and every one of them used to be a JNI call *and* the chunk
	 * lookup and biome read behind it. This answers all of them at once, on the same thread, against the
	 * section's own palette rather than through a fresh `BlockPos` each time.
	 *
	 * Which tint indices a state has at all is asked **per state**, not per position - a section holds a
	 * handful of distinct states and thousands of positions, and whether a state has a tint source does not
	 * depend on where it is. An empty answer means the engine draws those faces white, which is what the
	 * game's own `-1` means.
	 */
	@JvmStatic
	fun helperGetSectionTints(x: Int, y: Int, z: Int): LongArray {
		val client = Minecraft.getInstance()
		val level = client.level ?: return EMPTY_TINTS

		val section = level.getChunk(x, z).getSection(level.getSectionIndexFromSectionY(y))

		if (section.hasOnlyAir()) {
			return EMPTY_TINTS
		}

		val indices = HashMap<BlockState, IntArray>()
		var out = LongArray(0)
		var count = 0

		for (index in 0 until 4096) {
			val localX = index and 15
			val localY = index shr 8
			val localZ = index shr 4 and 15
			val state = section.getBlockState(localX, localY, localZ)
			val pos = tintPos.get().set((x shl 4) + localX, (y shl 4) + localY, (z shl 4) + localZ)
			val wanted = indices.getOrPut(state) { tintedIndices(client, state, level, pos) }

			for (tint in wanted) {
				val color = client.blockColors.getColor(state, level, pos, tint)

				if (color == -1) {
					continue
				}

				if (count == out.size) {
					out = out.copyOf(if (count == 0) 64 else count * 2)
				}

				out[count++] =
					(index.toLong() shl 36) or (tint.toLong() shl 32) or
						(packTint(color).toLong() and 0xFFFFFFFFL)
			}
		}

		return if (count == out.size) out else out.copyOf(count)
	}

	/**
	 * The same table for the fluids in a section, which are a different set of tints from the blocks'.
	 *
	 * Only index 0 is asked: a fluid is drawn with one colour, and the game's own fluid models carry a
	 * single `tintindex: 0` layer. A fluid with no tint source - lava - contributes nothing, which is what
	 * leaves it its own texture.
	 */
	@JvmStatic
	fun helperGetSectionFluidTints(x: Int, y: Int, z: Int): LongArray {
		val client = Minecraft.getInstance()
		val level = client.level ?: return EMPTY_TINTS

		val section = level.getChunk(x, z).getSection(level.getSectionIndexFromSectionY(y))

		if (section.hasOnlyAir()) {
			return EMPTY_TINTS
		}

		var out = LongArray(0)
		var count = 0

		for (index in 0 until 4096) {
			val localX = index and 15
			val localY = index shr 8
			val localZ = index shr 4 and 15
			val fluidState = section.getBlockState(localX, localY, localZ).fluidState

			if (fluidState.isEmpty) {
				continue
			}

			val pos = tintPos.get().set((x shl 4) + localX, (y shl 4) + localY, (z shl 4) + localZ)
			val color = client.blockColors.getColor(fluidState.createLegacyBlock(), level, pos, 0)

			if (color == -1) {
				continue
			}

			if (count == out.size) {
				out = out.copyOf(if (count == 0) 64 else count * 2)
			}

			out[count++] = (index.toLong() shl 36) or (packTint(color).toLong() and 0xFFFFFFFFL)
		}

		return if (count == out.size) out else out.copyOf(count)
	}

	/**
	 * Which tint indices a state answers for, asked once per distinct state in a section.
	 *
	 * The answer is positions-independent: `BlockColors#getColor` returns `-1` when the state has no tint
	 * source for an index, and a tint source that exists returns a colour - never `-1` - wherever it is
	 * asked. So a state with one source costs one probe and is then asked for one colour per position.
	 */
	private fun tintedIndices(
		client: Minecraft,
		state: BlockState,
		level: net.minecraft.world.level.BlockAndTintGetter,
		pos: BlockPos,
	): IntArray {
		var found = 0
		val indices = IntArray(TINT_INDEX_PROBES)

		for (tint in 0 until TINT_INDEX_PROBES) {
			if (client.blockColors.getColor(state, level, pos, tint) != -1) {
				indices[found++] = tint
			}
		}

		return if (found == indices.size) indices else indices.copyOf(found)
	}
}