#ifndef VORTEK_SERVER_HOST_H
#define VORTEK_SERVER_HOST_H

/*
 * Port Android (naughtybear-restuff-android): API C de hospedagem do servidor
 * Vortek — substitui a hospedagem Java do Winlator (VortekRendererComponent +
 * XConnectorEpoll) quando o jogo roda como processo nativo puro.
 *
 * Ciclo de vida (chamado do android_main.cpp ANTES de qualquer init gráfico):
 *
 *   vortek_server_init(nativeLibDir, hostDriverPath);   // 1x por processo
 *   vortek_server_start(socketPath, &options);          // listener em thread
 *   ... engine carrega libvulkan_vortek.so e conecta ...
 *   vortek_server_stop();                               // encerramento
 *
 * O cliente (libvulkan_vortek.so) conecta no socket e envia
 * REQUEST_CODE_CREATE_CONTEXT; o servidor cria o VkContext (thread de
 * requests + ring buffers em ASharedMemory) e passa a servir o protocolo.
 * Dados "extra" grandes (REQUEST_CODE_SEND_EXTRA_DATA) continuam chegando
 * pelo socket e são lidos pela thread leitora daqui.
 */

#include "vk_context.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Inicializa o wrapper Vulkan do servidor com o driver host. Chamar UMA vez,
 * antes de vortek_server_start.
 *
 * nativeLibDir    — nativeLibraryDir do app (onde está o libadrenotools.so e
 *                   os hooks usados para drivers custom).
 * hostLibvulkanPath — NULL: driver Vulkan do SISTEMA (caso padrão — é o que
 *                   interessa para Mali e demais GPUs não-Adreno). Não-NULL:
 *                   caminho absoluto de um driver custom (padrão AdrenoTools)
 *                   para o servidor operar por cima dele.
 *
 * Retorna 0 em sucesso, != 0 em falha (logada via logcat, tag "System.out").
 */
int vortek_server_init(const char* nativeLibDir, const char* hostLibvulkanPath);

/*
 * Sobe o servidor no socket Unix dado (path no filesDir do app) e aceita UM
 * cliente (o motor, no mesmo processo). A thread de accept + a thread leitora
 * de extra-data vivem em background; esta chamada retorna assim que o
 * listener está ativo.
 *
 * Retorna 0 se o listener está ativo; != 0 em erro (path inválido, bind falhou).
 */
int vortek_server_start(const char* socketPath, const VortekServerOptionsC* options);

/* Encerramento best-effort (destrói o contexto se houver). */
void vortek_server_stop(void);

#ifdef __cplusplus
}
#endif

#endif
