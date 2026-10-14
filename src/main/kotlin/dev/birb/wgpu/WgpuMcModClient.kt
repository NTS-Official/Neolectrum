package dev.birb.wgpu

import dev.birb.wgpu.rust.WgpuNative
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLPaths

/**
 * The client half of the mod: everything that must not run on a dedicated server.
 *
 * The only thing it does so far is bring the native library up and tell it where the game directory
 * is, which is what the 26.1 branch does in the same place and in the same order. The renderer
 * itself is not started from here - on this branch it has to be started from `Minecraft`'s own
 * constructor, because 1.21.1 creates its GL context there and the window is claimed before that.
 *
 * The calls are wrapped in a `try` on purpose rather than left to throw. A 1.21.1 client that cannot
 * load the cdylib should still reach the title screen and say so; the alternative is a crash during
 * mod loading, with no window and no log line naming the library it wanted.
 */
@Mod(value = WgpuMcMod.MOD_ID, dist = [Dist.CLIENT])
class WgpuMcModClient(modEventBus: IEventBus) {
	init {
		modEventBus.addListener(::onClientSetup)
	}

	private fun onClientSetup(event: FMLClientSetupEvent) {
		event.enqueueWork {
			try {
				WgpuNative.loadWm()
				WgpuNative.sendRunDirectory(FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString())
				WgpuNative.setPanicHook()
				WgpuMcMod.LOGGER.info("wgpu-mc native bridge initialised")
			} catch (throwable: Throwable) {
				WgpuMcMod.LOGGER.error(
					"wgpu-mc could not bring the native renderer library up, so this client will run on " +
						"Minecraft's own renderer: {}",
					throwable.toString(),
				)
				WgpuMcMod.LOGGER.debug("wgpu-mc native bootstrap failure", throwable)
			}
		}
	}
}