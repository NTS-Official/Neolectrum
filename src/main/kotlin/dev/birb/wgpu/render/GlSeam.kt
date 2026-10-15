package dev.birb.wgpu.render

/**
 * The one switch that decides whether this branch's window has a GL context.
 *
 * It lives in its own object because two mixins have to agree about it - the window one, which
 * decides how the window is created, and the [dev.birb.wgpu.mixin.core.RenderSystemMixin] one, which
 * decides whether the game's GL calls are allowed to reach a driver at all - and a branch where those
 * two disagree is a branch that fails in a way neither of them can explain.
 *
 * The property is set from the build script's `-Pwgpu_mc_no_gl_window`; see `WindowMixin` for why the
 * window has to lose its context before a wgpu surface can exist on it.
 */
object GlSeam {

    const val NO_GL_WINDOW_PROPERTY = "wgpu_mc.no_gl_window"

    /** True when the game's window is created with `GLFW_NO_API` and GL is therefore not available. */
    @JvmStatic
    val isGlWindowDisabled: Boolean
        get() = java.lang.Boolean.getBoolean(NO_GL_WINDOW_PROPERTY)
}