/* Stub de jni.h para CHECAGEM DE SINTAXE no host (Linux/glibc) dos fontes do
 * vortekrenderer. NÃO é usado no build Android (o NDK fornece o jni.h real).
 * Cobre apenas a superfície de API usada pelo vortekrenderer + jni_utils.h. */
#ifndef _STUB_JNI_H
#define _STUB_JNI_H

#include <stdint.h>
#include <stddef.h>
#include <stdarg.h>

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef uint16_t jchar;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

typedef void* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jarray jobjectArray;
typedef jarray jbyteArray;
typedef struct _jfieldID* jfieldID;
typedef struct _jmethodID* jmethodID;

typedef union jvalue {
    jboolean z;
    jbyte b;
    jchar c;
    jshort s;
    jint i;
    jlong j;
    jfloat f;
    jdouble d;
    jobject l;
} jvalue;

struct JNINativeInterface;
struct JNIInvokeInterface;
typedef const struct JNINativeInterface* JNIEnv;
typedef const struct JNIInvokeInterface* JavaVM;

struct JNINativeInterface {
    jclass (*GetObjectClass)(JNIEnv*, jobject);
    jfieldID (*GetFieldID)(JNIEnv*, jclass, const char*, const char*);
    jmethodID (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
    jobject (*NewGlobalRef)(JNIEnv*, jobject);
    void (*DeleteGlobalRef)(JNIEnv*, jobject);
    void (*DeleteLocalRef)(JNIEnv*, jobject);
    const char* (*GetStringUTFChars)(JNIEnv*, jstring, jboolean*);
    void (*ReleaseStringUTFChars)(JNIEnv*, jstring, const char*);
    jsize (*GetArrayLength)(JNIEnv*, jarray);
    jobject (*GetObjectArrayElement)(JNIEnv*, jobjectArray, jsize);
    jint (*GetIntField)(JNIEnv*, jobject, jfieldID);
    jboolean (*GetBooleanField)(JNIEnv*, jobject, jfieldID);
    jbyte (*GetByteField)(JNIEnv*, jobject, jfieldID);
    jshort (*GetShortField)(JNIEnv*, jobject, jfieldID);
    jlong (*GetLongField)(JNIEnv*, jobject, jfieldID);
    void (*FatalError)(JNIEnv*, const char*);
    void (*SetObjectField)(JNIEnv*, jobject, jfieldID, jobject);
    void (*SetBooleanField)(JNIEnv*, jobject, jfieldID, jboolean);
    void (*SetByteField)(JNIEnv*, jobject, jfieldID, jbyte);
    void (*SetCharField)(JNIEnv*, jobject, jfieldID, jchar);
    void (*SetShortField)(JNIEnv*, jobject, jfieldID, jshort);
    void (*SetIntField)(JNIEnv*, jobject, jfieldID, jint);
    void (*SetLongField)(JNIEnv*, jobject, jfieldID, jlong);
    void (*SetFloatField)(JNIEnv*, jobject, jfieldID, jfloat);
    void (*SetDoubleField)(JNIEnv*, jobject, jfieldID, jdouble);
    const jchar* (*GetStringChars)(JNIEnv*, jstring, jboolean*);
    jsize (*GetStringLength)(JNIEnv*, jstring);
    void (*ReleaseStringChars)(JNIEnv*, jstring, const jchar*);
    jsize (*GetStringUTFLength)(JNIEnv*, jstring);
    jstring (*NewStringUTF)(JNIEnv*, const char*);
    jint (*CallIntMethod)(JNIEnv*, jobject, jmethodID, ...);
    jlong (*CallLongMethod)(JNIEnv*, jobject, jmethodID, ...);
    void (*CallVoidMethod)(JNIEnv*, jobject, jmethodID, ...);
    jboolean (*CallBooleanMethod)(JNIEnv*, jobject, jmethodID, ...);
    jobject (*NewObject)(JNIEnv*, jclass, jmethodID, ...);
    jobject (*NewDirectByteBuffer)(JNIEnv*, void*, jlong);
    void* (*GetDirectBufferAddress)(JNIEnv*, jobject);
    jclass (*FindClass)(JNIEnv*, const char*);
    jboolean (*ExceptionCheck)(JNIEnv*);
    jobject (*GetObjectField)(JNIEnv*, jobject, jfieldID);
    jchar (*GetCharField)(JNIEnv*, jobject, jfieldID);
    jfloat (*GetFloatField)(JNIEnv*, jobject, jfieldID);
    jdouble (*GetDoubleField)(JNIEnv*, jobject, jfieldID);
    jint (*GetJavaVM)(JNIEnv*, JavaVM**);
};

struct JNIInvokeInterface {
    jint (*AttachCurrentThread)(JavaVM*, JNIEnv**, void*);
    jint (*DetachCurrentThread)(JavaVM*);
};

#endif
