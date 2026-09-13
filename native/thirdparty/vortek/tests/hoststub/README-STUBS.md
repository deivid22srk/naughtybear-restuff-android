# Stubs de headers Android para o round-trip test (host/CI)

Usados APENAS para compilar o `roundtrip_server.c` (modo `VT_SERVER` do
vortek.h) no host Linux do CI — o build Android real usa os headers do NDK.
Cobrem só a superfície que vortek.h/resource_memory.h referenciam:
`jni.h`, `android/log.h`, `android/hardware_buffer.h`.
