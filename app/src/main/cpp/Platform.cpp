// melonDS Platform layer for the Android core.
#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <Platform.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

#include "CoreHooks.h"

namespace melonDS::Platform
{

void SignalStop(StopReason reason, void* userdata)
{
    ndscore_hooks::OnStopSignal();
}

// --- Files -----------------------------------------------------------------
// The core only touches files for optional assets (BIOS, firmware, SD images), which
// this frontend never configures: firmware and saves travel through memory buffers.

std::string GetLocalFilePath(const std::string& filename) { return filename; }

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & FileMode::ReadWrite) == FileMode::None) return nullptr;

    const char* m;
    if ((mode & FileMode::ReadWrite) == FileMode::ReadWrite)
        m = (mode & FileMode::Preserve) ? "r+b" : "w+b";
    else if (mode & FileMode::Write)
        m = (mode & FileMode::Append) ? "ab" : "wb";
    else
        m = "rb";

    FILE* f = fopen(path.c_str(), m);
    if (!f && (mode & FileMode::Preserve) && (mode & FileMode::Write)) f = fopen(path.c_str(), "w+b");
    return reinterpret_cast<FileHandle*>(f);
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode) { return OpenFile(path, mode); }

bool FileExists(const std::string& name)
{
    struct stat st {};
    return stat(name.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool LocalFileExists(const std::string& name) { return FileExists(name); }
bool CheckFileWritable(const std::string& filepath) { return true; }
bool CheckLocalFileWritable(const std::string& filepath) { return true; }

bool CloseFile(FileHandle* file) { return file && fclose(reinterpret_cast<FILE*>(file)) == 0; }
bool IsEndOfFile(FileHandle* file) { return feof(reinterpret_cast<FILE*>(file)) != 0; }
bool FileReadLine(char* str, int count, FileHandle* file) { return fgets(str, count, reinterpret_cast<FILE*>(file)) != nullptr; }
u64 FilePosition(FileHandle* file) { return ftello(reinterpret_cast<FILE*>(file)); }

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    int whence = SEEK_SET;
    if (origin == FileSeekOrigin::Current) whence = SEEK_CUR;
    else if (origin == FileSeekOrigin::End) whence = SEEK_END;
    return fseeko(reinterpret_cast<FILE*>(file), offset, whence) == 0;
}

void FileRewind(FileHandle* file) { rewind(reinterpret_cast<FILE*>(file)); }
u64 FileRead(void* data, u64 size, u64 count, FileHandle* file) { return fread(data, size, count, reinterpret_cast<FILE*>(file)); }
bool FileFlush(FileHandle* file) { return fflush(reinterpret_cast<FILE*>(file)) == 0; }
u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file) { return fwrite(data, size, count, reinterpret_cast<FILE*>(file)); }

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    int res = vfprintf(reinterpret_cast<FILE*>(file), fmt, args);
    va_end(args);
    return res > 0 ? res : 0;
}

u64 FileLength(FileHandle* file)
{
    FILE* f = reinterpret_cast<FILE*>(file);
    off_t cur = ftello(f);
    fseeko(f, 0, SEEK_END);
    off_t len = ftello(f);
    fseeko(f, cur, SEEK_SET);
    return len;
}

// --- Logging ---------------------------------------------------------------

void Log(LogLevel level, const char* fmt, ...)
{
    if (!fmt) return;
    int priority = ANDROID_LOG_INFO;
    switch (level)
    {
    case LogLevel::Debug: priority = ANDROID_LOG_DEBUG; break;
    case LogLevel::Warn: priority = ANDROID_LOG_WARN; break;
    case LogLevel::Error: priority = ANDROID_LOG_ERROR; break;
    default: break;
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, "melonDS", fmt, args);
    va_end(args);
}

// --- Threads and synchronization -------------------------------------------

struct Thread { std::thread t; };

Thread* Thread_Create(std::function<void()> func)
{
    Thread* th = new Thread();
    th->t = std::thread(std::move(func));
    return th;
}

void Thread_Free(Thread* thread)
{
    if (thread->t.joinable()) thread->t.detach();
    delete thread;
}

void Thread_Wait(Thread* thread)
{
    if (thread->t.joinable()) thread->t.join();
}

struct Semaphore
{
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};

Semaphore* Semaphore_Create() { return new Semaphore(); }
void Semaphore_Free(Semaphore* sema) { delete sema; }

void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard lock(sema->m);
    sema->count = 0;
}

void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock lock(sema->m);
    sema->cv.wait(lock, [sema] { return sema->count > 0; });
    sema->count--;
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock lock(sema->m);
    if (timeout_ms == 0)
    {
        if (sema->count == 0) return false;
    }
    else if (!sema->cv.wait_for(lock, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; }))
    {
        return false;
    }
    sema->count--;
    return true;
}

void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard lock(sema->m);
        sema->count += count;
    }
    sema->cv.notify_all();
}

struct Mutex { std::mutex m; };
Mutex* Mutex_Create() { return new Mutex(); }
void Mutex_Free(Mutex* mutex) { delete mutex; }
void Mutex_Lock(Mutex* mutex) { mutex->m.lock(); }
void Mutex_Unlock(Mutex* mutex) { mutex->m.unlock(); }
bool Mutex_TryLock(Mutex* mutex) { return mutex->m.try_lock(); }

void Sleep(u64 usecs) { std::this_thread::sleep_for(std::chrono::microseconds(usecs)); }

u64 GetMSCount()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

u64 GetUSCount()
{
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// --- Saves and firmware ----------------------------------------------------

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    ndscore_hooks::OnSaveWritten();
}

void WriteGBASave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata) {}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void* userdata)
{
    ndscore_hooks::OnFirmwareWritten();
}

void WriteDateTime(int year, int month, int day, int hour, int minute, int second, void* userdata) {}

// --- Local multiplayer (not supported) -------------------------------------

void MP_Begin(void* userdata) {}
void MP_End(void* userdata) {}
int MP_SendPacket(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvPacket(u8* data, u64* timestamp, void* userdata) { return 0; }
int MP_SendCmd(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_SendReply(u8* data, int len, u64 timestamp, u16 aid, void* userdata) { return 0; }
int MP_SendAck(u8* data, int len, u64 timestamp, void* userdata) { return 0; }
int MP_RecvHostPacket(u8* data, u64* timestamp, void* userdata) { return 0; }
u16 MP_RecvReplies(u8* data, u64 timestamp, u16 aidmask, void* userdata) { return 0; }

// --- Wi-Fi -----------------------------------------------------------------

int Net_SendPacket(u8* data, int len, void* userdata) { return ndscore_hooks::NetSend(data, len); }
int Net_RecvPacket(u8* data, void* userdata) { return ndscore_hooks::NetRecv(data); }

// --- Peripherals (not supported) -------------------------------------------

void Camera_Start(int num, void* userdata) {}
void Camera_Stop(int num, void* userdata) {}
void Camera_CaptureFrame(int num, u32* frame, int width, int height, bool yuv, void* userdata) {}

void Mic_Start(void* userdata) {}
void Mic_Stop(void* userdata) {}
int Mic_ReadInput(s16* data, int maxlength, void* userdata) { return 0; }

AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder* dec) {}
bool AAC_Configure(AACDecoder* dec, int frequency, int channels) { return false; }
bool AAC_DecodeFrame(AACDecoder* dec, const void* input, int inputlen, void* output, int outputlen) { return false; }

bool Addon_KeyDown(KeyType type, void* userdata) { return false; }
void Addon_RumbleStart(u32 len, void* userdata) {}
void Addon_RumbleStop(void* userdata) {}
float Addon_MotionQuery(MotionQueryType type, void* userdata) { return 0.0f; }

// --- Dynamic libraries (the JIT on Android loads libandroid for shared memory) --------

DynamicLibrary* DynamicLibrary_Load(const char* lib) { return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW)); }
void DynamicLibrary_Unload(DynamicLibrary* lib) { if (lib) dlclose(lib); }

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name)
{
    return lib ? dlsym(lib, name) : nullptr;
}

}
