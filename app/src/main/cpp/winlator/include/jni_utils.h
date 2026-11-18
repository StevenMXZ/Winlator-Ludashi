#include <jni.h>
#include <android/log.h>
#include <stdio.h>

#define JSIGNATURE_STRING "Ljava/lang/String;"
#define JSIGNATURE_ARRAY_STRING "[Ljava/lang/String;"

static inline void setJFieldByName(JNIEnv *env, jobject obj, const char *name, const char *signature, ...) {
    jclass objClass = (*env)->GetObjectClass(env, obj);
    jfieldID fieldId = (*env)->GetFieldID(env, objClass, name, signature);

    if (fieldId == 0) {
        (*env)->DeleteLocalRef(env, objClass);
        return;
    }

    va_list args;
    va_start(args, signature);

    switch (*signature) {
        case '[' :
        case 'L' :
            (*env)->SetObjectField(env, obj, fieldId, va_arg(args, jobject));
            break;
        case 'Z' :
            (*env)->SetBooleanField(env, obj, fieldId, (jboolean) va_arg(args, int));
            break;
        case 'B' :
            (*env)->SetByteField(env, obj, fieldId, (jbyte) va_arg(args, int));
            break;
        case 'C' :
            (*env)->SetCharField(env, obj, fieldId, (jchar) va_arg(args, int));
            break;
        case 'S' :
            (*env)->SetShortField(env, obj, fieldId, (jshort) va_arg(args, int));
            break;
        case 'I' :
            (*env)->SetIntField(env, obj, fieldId, va_arg(args, jint));
            break;
        case 'J' :
            (*env)->SetLongField(env, obj, fieldId, va_arg(args, jlong));
            break;
        case 'F' :
            (*env)->SetFloatField(env, obj, fieldId, (jfloat) va_arg(args, jdouble));
            break;
        case 'D' :
            (*env)->SetDoubleField(env, obj, fieldId, va_arg(args, jdouble));
            break;
        default :
            (*env)->FatalError(env, "Illegal signature");
    }

    va_end(args);

    (*env)->DeleteLocalRef(env, objClass);
}

static inline jvalue getJFieldByName(JNIEnv *env, jobject obj, const char *name, const char *signature) {
    jvalue result;
    result.i = 0;

    jclass objClass = (*env)->GetObjectClass(env, obj);
    jfieldID fieldId = (*env)->GetFieldID(env, objClass, name, signature);

    if (fieldId == 0) {
        (*env)->DeleteLocalRef(env, objClass);
        return result;
    }

    switch (*signature) {
        case '[':
        case 'L':
            result.l = (*env)->GetObjectField(env, obj, fieldId);
            break;
        case 'Z':
            result.z = (*env)->GetBooleanField(env, obj, fieldId);
            break;
        case 'B':
            result.b = (*env)->GetByteField(env, obj, fieldId);
            break;
        case 'C':
            result.c = (*env)->GetCharField(env, obj, fieldId);
            break;
        case 'S':
            result.s = (*env)->GetShortField(env, obj, fieldId);
            break;
        case 'I':
            result.i = (*env)->GetIntField(env, obj, fieldId);
            break;
        case 'J':
            result.j = (*env)->GetLongField(env, obj, fieldId);
            break;
        case 'F':
            result.f = (*env)->GetFloatField(env, obj, fieldId);
            break;
        case 'D':
            result.d = (*env)->GetDoubleField(env, obj, fieldId);
            break;
        default:
            (*env)->FatalError(env, "Illegal signature");
    }

    (*env)->DeleteLocalRef(env, objClass);
    return result;
}