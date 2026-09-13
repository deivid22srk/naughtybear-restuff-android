/* Stub de native_handle.h para checagem de sintaxe no host (NDK o fornece). */
#ifndef _STUB_NATIVE_HANDLE_H
#define _STUB_NATIVE_HANDLE_H
typedef struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[1];
} native_handle_t;
#endif
