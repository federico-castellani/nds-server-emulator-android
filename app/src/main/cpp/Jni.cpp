// JNI bindings for io.github.federicocastellani.ndsstation.emulation.NativeCore.
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "Core.h"

namespace
{

std::vector<uint8_t> ToVector(JNIEnv* env, jbyteArray array)
{
    if (!array) return {};
    const jsize length = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(length));
    if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray ToArray(JNIEnv* env, const std::vector<uint8_t>& data)
{
    if (data.empty()) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(data.size()));
    if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return array;
}

std::string ToString(JNIEnv* env, jstring s)
{
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

template <size_t N>
void CopyBytes(JNIEnv* env, jbyteArray array, uint8_t (&dst)[N])
{
    if (array && env->GetArrayLength(array) >= static_cast<jsize>(N))
        env->GetByteArrayRegion(array, 0, N, reinterpret_cast<jbyte*>(dst));
}

}

#define NATIVE(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_federicocastellani_ndsstation_emulation_NativeCore_##name

NATIVE(jboolean, init)(JNIEnv*, jobject) { return ndscore::Init(); }
NATIVE(void, shutdown)(JNIEnv*, jobject) { ndscore::Shutdown(); }

// The ROM is read here from a file: DS ROMs reach 512 MB, far beyond what the Java heap
// of an app can hold.
std::vector<uint8_t> ReadFile(const std::string& path)
{
    std::vector<uint8_t> data;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return data;
    if (fseeko(f, 0, SEEK_END) == 0)
    {
        const off_t size = ftello(f);
        if (size > 0 && fseeko(f, 0, SEEK_SET) == 0)
        {
            data.resize(static_cast<size_t>(size));
            if (fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
        }
    }
    fclose(f);
    return data;
}

NATIVE(jboolean, boot)(JNIEnv* env, jobject, jstring romPath, jbyteArray save, jbyteArray firmware, jstring saveName,
                       jstring nickname, jbyteArray primaryDns, jbyteArray secondaryDns, jbyteArray mac, jboolean useJit)
{
    ndscore::BootArgs args;
    args.rom = ReadFile(ToString(env, romPath));
    args.save = ToVector(env, save);
    args.firmware = ToVector(env, firmware);
    args.saveName = ToString(env, saveName);
    args.nickname = ToString(env, nickname);
    CopyBytes(env, primaryDns, args.primaryDns);
    CopyBytes(env, secondaryDns, args.secondaryDns);
    CopyBytes(env, mac, args.mac);
    args.useJit = useJit;
    return ndscore::Boot(args);
}

NATIVE(void, stop)(JNIEnv*, jobject) { ndscore::Stop(); }
NATIVE(void, unload)(JNIEnv*, jobject) { ndscore::Unload(); }
NATIVE(jboolean, isRunning)(JNIEnv*, jobject) { return ndscore::IsRunning(); }

NATIVE(void, setPaused)(JNIEnv*, jobject, jboolean paused) { ndscore::SetPaused(paused); }
NATIVE(void, setSpeed)(JNIEnv*, jobject, jfloat multiplier) { ndscore::SetSpeed(multiplier); }
NATIVE(void, setVolume)(JNIEnv*, jobject, jfloat volume) { ndscore::SetVolume(volume); }
NATIVE(void, setFastForwardAudio)(JNIEnv*, jobject, jint mode) { ndscore::SetFastForwardAudio(mode); }
NATIVE(void, setInput)(JNIEnv*, jobject, jint mask) { ndscore::SetInput(static_cast<uint32_t>(mask)); }
NATIVE(void, setTouch)(JNIEnv*, jobject, jboolean down, jint x, jint y) { ndscore::SetTouch(down, x, y); }

NATIVE(jlong, copyFrame)(JNIEnv* env, jobject, jobject directBuffer, jlong lastFrame)
{
    auto* dst = static_cast<uint32_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!dst || env->GetDirectBufferCapacity(directBuffer) < static_cast<jlong>(ndscore::kFramePixels * 4)) return 0;
    return static_cast<jlong>(ndscore::CopyFrame(dst, static_cast<uint64_t>(lastFrame)));
}

NATIVE(jfloat, fps)(JNIEnv*, jobject) { return ndscore::Fps(); }

NATIVE(jint, saveCounter)(JNIEnv*, jobject) { return static_cast<jint>(ndscore::SaveCounter()); }
NATIVE(jbyteArray, getSave)(JNIEnv* env, jobject) { return ToArray(env, ndscore::GetSave()); }
NATIVE(jint, firmwareCounter)(JNIEnv*, jobject) { return static_cast<jint>(ndscore::FirmwareCounter()); }
NATIVE(jbyteArray, getFirmware)(JNIEnv* env, jobject) { return ToArray(env, ndscore::GetFirmware()); }

NATIVE(jbyteArray, saveState)(JNIEnv* env, jobject) { return ToArray(env, ndscore::SaveState()); }

NATIVE(jboolean, loadState)(JNIEnv* env, jobject, jbyteArray state)
{
    auto data = ToVector(env, state);
    return ndscore::LoadState(data.data(), data.size());
}

NATIVE(void, configureNetwork)(JNIEnv*, jobject) { ndscore::ConfigureNetwork(); }
NATIVE(jstring, netDescription)(JNIEnv* env, jobject) { return env->NewStringUTF(ndscore::NetDescription().c_str()); }
NATIVE(jstring, netReport)(JNIEnv* env, jobject) { return env->NewStringUTF(ndscore::NetReport().c_str()); }
NATIVE(jstring, lastError)(JNIEnv* env, jobject) { return env->NewStringUTF(ndscore::LastError().c_str()); }
