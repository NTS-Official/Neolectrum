package dev.birb.wgpu.rust

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the native library may sit inside the mod jar, in probe order.
 *
 * Deliberately a top-level declaration rather than a member of [WgpuNative]: the object loads the
 * native library from its own `init` block, and Kotlin runs an object's initializers in declaration
 * order, so a property declared below that `init` block is still `null` when the loader reads it.
 * Keeping it here makes the ordering impossible to get wrong.
 */
private val NATIVE_RESOURCE_ROOTS = listOf("META-INF/natives/", "assets/wgpu_mc/natives/")

/**
 * The properties a launcher uses to name the directory it unpacks the game's natives into, in probe
 * order. NeoForge's own version JSON sets every one of them to the natives directory.
 */
private val NATIVE_DIRECTORY_PROPERTIES = listOf(
	"org.lwjgl.system.SharedLibraryExtractPath",
	"jna.tmpdir",
	"io.netty.native.workdir",
	"org.lwjgl.librarypath",
)

/**
 * The directory the native library and its symbols are unpacked into.
 *
 * The launcher's natives directory rather than a `lib` folder of this mod's own: that is where the
 * game's other natives (LWJGL, GLFW) already sit, it is on `java.library.path`, and it is the
 * directory a debugger searches when it resolves a module's symbols. The launcher names it in
 * [NATIVE_DIRECTORY_PROPERTIES]; failing that it is found by looking through `java.library.path` for
 * the directory holding LWJGL's own libraries, identified by their names rather than by the
 * directory's, so it works on every platform and cannot be confused with the JDK's own `bin`.
 *
 * A development run has neither, so the fallback is `natives/` under the working directory, which is
 * the same layout one level down.
 */
private fun nativeDirectory(): File {
	for (property in NATIVE_DIRECTORY_PROPERTIES) {
		val configured = System.getProperty(property)?.takeIf { it.isNotBlank() } ?: continue
		val directory = File(configured)
		if (directory.isDirectory) return directory
	}

	val searchPath = System.getProperty("java.library.path").orEmpty()
	for (entry in searchPath.split(File.pathSeparator)) {
		val directory = File(entry)
		if (!directory.isDirectory) continue
		val holdsLwjgl = directory.listFiles()?.any { file ->
			val name = file.name.lowercase()
			name.contains("lwjgl") || name.contains("glfw")
		} == true
		if (holdsLwjgl) return directory
	}

	return File("natives")
}

/**
 * JNI side of the native bridge: shader settings, entity model registration, block-state baking,
 * palettes, panic handling and the renderer handle itself.
 *
 * **This branch is the one the engine's `all-jni` branch exists for.** The 26.1 branch splits the
 * bridge in two - JNI for the calls the renderer makes per frame, and the C ABI bound through
 * `java.lang.foreign` for the GPU objects - because describing a struct once and passing a pointer is
 * cheaper than a JNI call per field. On Java 21 `java.lang.foreign` is a *preview* API: it compiles
 * with `--enable-preview` and throws without it at run time, and a mod cannot ask a launcher to pass
 * a preview flag. So every function that branch binds by name is exported as a JNI entry point
 * instead, and both halves live in this file.
 *
 * That makes the declarations below a contract with `rust/wgpu-mc-jni`: each one must exist as a
 * `#[jni_fn]` there, and a declaration without one throws `UnsatisfiedLinkError` - an `Error`, so
 * `catch (e: Exception)` does not see it - the first time it is called. `cargo test` in the engine
 * reads this file and checks exactly that (see `abi_tests.rs`).
 */
object WgpuNative {
	init {
		loadWm()
	}

	/**
	 * Extracts and loads the native library, then hands the JVM side what Rust needs from it.
	 *
	 * Only two things happen here, and both matter:
	 *
	 *  - [load] puts the cdylib in place and `System.load`s it;
	 *  - [setClassLoader] gives Rust the loader it must use to reach mod classes. `FindClass` from an
	 *    attached native thread resolves against the *system* loader, which cannot see NeoForge's
	 *    transformed classes, so without this every resource lookup panics on an empty cell - and a
	 *    panic on a JNI frame aborts the process rather than unwinding.
	 *
	 * Anything added here has to exist as a `#[jni_fn]` in `rust/wgpu-mc-jni`, or the first touch of
	 * this object kills the game.
	 */
	@JvmStatic
	fun loadWm() {
		try {
			load("wgpu_mc_jni", true)
			// This object's own loader, not the thread context loader: it is the one that can
			// resolve the mod's classes, and the only one Rust ever needs to look a class up.
			setClassLoader(WgpuNative::class.java.classLoader)
		} catch (e: Exception) {
			throw IllegalStateException(e)
		}
	}

	@JvmStatic
	fun getClassLoader(): ClassLoader = WgpuNative::class.java.classLoader

	private external fun setClassLoader(contextClassLoader: ClassLoader)

	/**
	 * Tells Rust where the game directory is, which is where it reads and writes
	 * `config/wgpu-mc-renderer.json`.
	 *
	 * The write to disk is not optional on the Rust side: the settings are loaded as part of this
	 * call, so a renderer that comes up before it has the defaults rather than the player's file.
	 */
	external fun sendRunDirectory(directory: String)

	/**
	 * Installs the panic hook and the logger.
	 *
	 * Order matters against [sendRunDirectory]: the settings are read before `env_logger` exists, so
	 * the renderer's own "loaded settings" line is dropped. That is why the 26.1 branch reads the
	 * document back once the bridge is up and logs `renderer settings as loaded:` itself, rather than
	 * trusting the engine's line to appear.
	 */
	external fun setPanicHook()

	/**
	 * Loads a native library from the resources of this jar.
	 *
	 * @param name Library to load
	 * @param forceOverwrite Force overwrite the library file
	 * @throws FileNotFoundException Library not found in resources
	 * @throws IOException Cannot move library out of jar
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun load(name: String, forceOverwrite: Boolean) {
		System.load(resolveNativeLibrary(name, forceOverwrite))
	}

	/**
	 * Extracts the native library [name] out of the mod jar and returns the absolute path of the
	 * extracted file, without loading it.
	 *
	 * Both roots in [NATIVE_RESOURCE_ROOTS] are probed. This build populates the
	 * `assets/wgpu_mc/natives/` one - `copyNatives` in build.gradle.kts puts the Rust build's library
	 * and its PDB there - so the other is only for a jar assembled the other way around.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun resolveNativeLibrary(name: String, forceOverwrite: Boolean = false): String {
		val mappedName = System.mapLibraryName(name)
		val libraryFile = File(nativeDirectory(), mappedName)
		if (!libraryFile.exists() || forceOverwrite) {
			libraryFile.parentFile?.mkdirs()
			val resourceName = NATIVE_RESOURCE_ROOTS
				.firstOrNull { WgpuNative::class.java.classLoader.getResource(it + mappedName) != null }
				?: throw FileNotFoundException(
					"Could not find lib $mappedName in jar (looked in ${NATIVE_RESOURCE_ROOTS.joinToString()})"
				)

			WgpuNative::class.java.classLoader.getResourceAsStream(resourceName + mappedName).use { input ->
				try {
					Files.copy(input!!, libraryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
				} catch (denied: AccessDeniedException) {
					// Windows keeps a loaded DLL locked until the process that mapped it exits, and
					// two clients sharing a run directory is what that looks like: the copy cannot
					// replace the file the other instance is running. The file that is there is the
					// same library this build produced, so use it rather than refusing to start -
					// the alternative is a crash on launch.
					if (!libraryFile.exists()) throw denied
					dev.birb.wgpu.WgpuMcMod.LOGGER.warn(
						"wgpu: {} is locked by another process; using the copy already on disk",
						libraryFile,
					)
				}
			}

			// The symbols for that same build, next to the library - which is where a debugger looks
			// for them. Written at the same time as the library and for the same reason: the two are
			// one build, and a PDB from the previous one no longer matches.
			copySymbolsBeside(mappedName)
		}
		return libraryFile.absolutePath
	}

	/**
	 * Puts the PDB for [mappedName] beside the extracted library, if this build has one.
	 *
	 * Best effort on purpose: a build without symbols - or a packaged jar that carries only the
	 * library - works exactly as before, it just profiles by address. The file is not locked by the
	 * process that loaded the library (only debuggers read it), so it can be replaced while another
	 * client is running.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun copySymbolsBeside(mappedName: String) {
		val symbolsName = mappedName.substringBeforeLast('.') + ".pdb"
		val resourceName = NATIVE_RESOURCE_ROOTS
			.firstOrNull { WgpuNative::class.java.classLoader.getResource(it + symbolsName) != null }
			?: return

		val symbolsFile = File(nativeDirectory(), symbolsName)

		try {
			WgpuNative::class.java.classLoader.getResourceAsStream(resourceName + symbolsName).use { input ->
				Files.copy(input!!, symbolsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
		} catch (denied: AccessDeniedException) {
			if (!symbolsFile.exists()) throw denied
		}
	}
}