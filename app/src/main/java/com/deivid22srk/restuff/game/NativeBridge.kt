package com.deivid22srk.restuff.game

/**
 * Declarações JNI do motor nativo do ReStuff (librestuff.so).
 *
 * O lado nativo (native/jni/android_main.cpp) implementa:
 *  - [nativeGetAbi]: sanity check do carregamento da .so
 *  - [nativeSetVirtualPadState]: alimenta o gamepad virtual SDL (P1)
 *
 * O fluxo principal (cvar::Init → SDLWindowedAppContext → rex::ui app
 * "restuff") roda na thread SDL_main criada pelo SDLActivity via
 * SDL_RunApp — ver android_main.cpp.
 */
object NativeBridge {
    @Volatile
    var loaded = false
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("restuff")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeGetAbi(): String

    /**
     * Total acumulado de quadros APRESENTADOS pelo backend Vulkan (contados
     * por um trampoline sobre vkQueuePresentKHR no vulkan_device.cpp — cada
     * present com resultado SUCCESS/SUBOPTIMAL incrementa o contador global).
     *
     * O overlay de FPS (FpsCounterView) faz a diferença entre duas leituras
     * para calcular a taxa real do MOTOR — não a taxa de vsync do painel.
     */
    external fun nativeGetPresentCount(): Long

    /**
     * Envia o estado consolidado do gamepad virtual (overlay) ao SDL virtual
     * joystick P1. Chamado a cada frame de toque (throttled pelo overlay).
     *
     * @param buttons bitmask: bit0 A, 1 B, 2 X, 3 Y, 4 Back, 5 Guide, 6 Start,
     *                7 LB, 8 RB, 9 LS, 10 RS, 11 DUp, 12 DDown, 13 DLeft, 14 DRight
     * @param lx/ly/rx/ry eixos analógicos em [-32768, 32767] (ly/ry no sentido
     *                SDL: negativo = para cima)
     * @param lt/rt gatilhos em [0, 32767]
     */
    external fun nativeSetVirtualPadState(
        buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int, lt: Int, rt: Int
    )
}
