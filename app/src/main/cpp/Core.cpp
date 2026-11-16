#include "Core.h"

#include <aaudio/AAudio.h>

#include <NDS.h>
#include <NDSCart.h>
#include <Savestate.h>
#include <SPI_Firmware.h>
#include <net/Net.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>

#include "CoreHooks.h"
#include "NetStats.h"
#include "SlirpDriver.h"

using namespace melonDS;

namespace ndscore
{
namespace
{

constexpr double kFrameRate = 59.8260982880808;
constexpr int kScreenPixels = kScreenWidth * kScreenHeight;

struct State
{
    bool initialized = false;

    // Console; guarded by emuMutex while the emulation thread runs a frame.
    std::mutex emuMutex;
    std::unique_ptr<NDS> nds;

    std::thread emuThread;
    std::atomic<bool> running{false};
    std::atomic<bool> stopRequested{false};
    std::atomic<bool> paused{false};
    std::mutex pauseMutex;
    std::condition_variable pauseCv;

    std::atomic<float> speed{1.0f};
    std::atomic<float> volume{0.8f};
    std::atomic<float> fps{0.0f};

    std::atomic<uint32_t> inputMask{0};
    std::atomic<bool> touchDown{false};
    std::atomic<int32_t> touchX{0};
    std::atomic<int32_t> touchY{0};

    std::mutex frameMutex;
    uint32_t frame[kFramePixels] = {};
    uint64_t frameId = 0;

    std::atomic<uint32_t> saveCounter{0};
    std::atomic<uint32_t> firmwareCounter{0};

    // Audio: the AAudio callback drains the SPU; the emulation thread waits on audioCv
    // when the SPU buffer is full, which keeps emulation in step with the speaker.
    AAudioStream* audio = nullptr;
    int audioRate = 48000;
    int audioBufferFrames = 1024;
    std::atomic<bool> audioLost{false};
    std::mutex audioMutex;
    std::condition_variable audioCv;

    std::unique_ptr<Net> net;
    std::mutex netDescriptionMutex;
    std::string netDescription = "Rete non configurata";

    std::mutex errorMutex;
    std::string lastError;
};

State g;

void SetError(const std::string& message)
{
    std::lock_guard lock(g.errorMutex);
    g.lastError = message;
}

std::string IpText(uint32_t ip)
{
    char text[16];
    snprintf(text, sizeof(text), "%u.%u.%u.%u", ip >> 24, (ip >> 16) & 0xFF, (ip >> 8) & 0xFF, ip & 0xFF);
    return text;
}

// --- Audio -----------------------------------------------------------------

aaudio_data_callback_result_t AudioCallback(AAudioStream*, void*, void* audioData, int32_t frames)
{
    auto* samples = static_cast<int16_t*>(audioData);
    int read = 0;
    {
        std::lock_guard lock(g.audioMutex);
        if (g.nds && g.running && !g.paused)
            read = g.nds->SPU.ReadOutput(samples, frames);
    }
    g.audioCv.notify_one();

    const float volume = g.volume.load();
    if (read <= 0 || volume <= 0.001f || g.speed.load() != 1.0f)
    {
        std::memset(samples, 0, frames * 4);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (volume < 0.999f)
    {
        for (int i = 0; i < read * 2; i++) samples[i] = static_cast<int16_t>(samples[i] * volume);
    }
    // Stretch the last sample instead of inserting silence to avoid pops on underrun.
    auto* pairs = reinterpret_cast<uint32_t*>(samples);
    for (int i = read; i < frames; i++) pairs[i] = pairs[read - 1];
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioError(AAudioStream*, void*, aaudio_result_t)
{
    // Headphones unplugged, Bluetooth switched...: reopen from the emulation thread.
    g.audioLost = true;
}

void CloseAudio()
{
    std::lock_guard lock(g.audioMutex);
    if (g.audio)
    {
        AAudioStream_requestStop(g.audio);
        AAudioStream_close(g.audio);
        g.audio = nullptr;
    }
}

void OpenAudio()
{
    CloseAudio();
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, AudioCallback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, AudioError, nullptr);

    AAudioStream* stream = nullptr;
    if (AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK)
    {
        std::lock_guard lock(g.audioMutex);
        g.audio = stream;
        g.audioRate = AAudioStream_getSampleRate(stream);
        g.audioBufferFrames = std::max(512, AAudioStream_getFramesPerBurst(stream) * 4);
        AAudioStream_requestStart(stream);
    }
    AAudioStreamBuilder_delete(builder);
    g.audioLost = false;
}

// --- Emulation thread ------------------------------------------------------

void CopyFramebuffers()
{
    void* top = nullptr;
    void* bottom = nullptr;
    if (!g.nds->GPU.GetFramebuffers(&top, &bottom) || !top || !bottom) return;

    std::lock_guard lock(g.frameMutex);
    const uint32_t* src[2] = {static_cast<const uint32_t*>(top), static_cast<const uint32_t*>(bottom)};
    for (int s = 0; s < 2; s++)
    {
        uint32_t* dst = g.frame + s * kScreenPixels;
        for (int i = 0; i < kScreenPixels; i++)
        {
            // melonDS: 0xAARRGGBB; Android Bitmap memory: R, G, B, A (0xAABBGGRR).
            const uint32_t p = src[s][i];
            dst[i] = 0xFF000000u | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF);
        }
    }
    g.frameId++;
}

void WaitForAudio()
{
    if (!g.audio) return;
    std::unique_lock lock(g.audioMutex);
    g.audioCv.wait_for(lock, std::chrono::milliseconds(50), [] {
        return !g.nds || g.nds->SPU.GetOutputSize() < g.audioBufferFrames || g.stopRequested;
    });
}

void EmuThreadMain()
{
    using clock = std::chrono::steady_clock;
    auto nextFrame = clock::now();
    auto fpsWindowStart = clock::now();
    int framesInWindow = 0;

    while (!g.stopRequested)
    {
        if (g.audioLost) OpenAudio();

        if (g.paused)
        {
            std::unique_lock lock(g.pauseMutex);
            g.pauseCv.wait(lock, [] { return !g.paused || g.stopRequested; });
            nextFrame = clock::now();
            fpsWindowStart = nextFrame;
            framesInWindow = 0;
            continue;
        }

        {
            std::lock_guard lock(g.emuMutex);
            NDS& nds = *g.nds;
            nds.SetKeyMask(~g.inputMask.load() & 0xFFF);
            if (g.touchDown)
                nds.TouchScreen(static_cast<u16>(g.touchX.load()), static_cast<u16>(g.touchY.load()));
            else
                nds.ReleaseScreen();
            nds.RunFrame();
            CopyFramebuffers();
        }

        framesInWindow++;
        const auto now = clock::now();
        const double windowSeconds = std::chrono::duration<double>(now - fpsWindowStart).count();
        if (windowSeconds >= 1.0)
        {
            g.fps = static_cast<float>(framesInWindow / windowSeconds);
            fpsWindowStart = now;
            framesInWindow = 0;
        }

        const float speed = g.speed.load();
        if (speed == 1.0f) WaitForAudio();

        if (speed > 0.0f)
        {
            const auto step = std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(1.0 / (kFrameRate * speed)));
            nextFrame += step;
            const auto current = clock::now();
            if (nextFrame > current) std::this_thread::sleep_until(nextFrame);
            else if (current - nextFrame > step * 3) nextFrame = current;   // too far behind: don't catch up
        }
        else
        {
            nextFrame = clock::now();
        }
    }
}

void JoinEmuThread()
{
    if (!g.emuThread.joinable()) return;
    g.stopRequested = true;
    g.pauseCv.notify_all();
    g.audioCv.notify_all();
    g.emuThread.join();
    g.running = false;
}

void ApplyFirmwareSettings(Firmware& firmware, const BootArgs& args)
{
    auto& ap = firmware.GetAccessPoints()[0];
    if (args.primaryDns[0] | args.primaryDns[1] | args.primaryDns[2] | args.primaryDns[3])
    {
        std::copy(std::begin(args.primaryDns), std::end(args.primaryDns), ap.PrimaryDns.begin());
        std::copy(std::begin(args.secondaryDns), std::end(args.secondaryDns), ap.SecondaryDns.begin());
        ap.ConnectionConfigured = 0x01;
    }

    if (!args.nickname.empty())
    {
        // UTF-8 -> UTF-16, Basic Multilingual Plane only (DS nicknames are 10 characters).
        char16_t name[10] = {};
        int len = 0;
        const auto* s = reinterpret_cast<const unsigned char*>(args.nickname.c_str());
        while (*s && len < 10)
        {
            uint32_t c = *s++;
            if (c >= 0xE0 && s[0] && s[1]) { c = ((c & 0x0F) << 12) | ((s[0] & 0x3F) << 6) | (s[1] & 0x3F); s += 2; }
            else if (c >= 0xC0 && s[0]) { c = ((c & 0x1F) << 6) | (s[0] & 0x3F); s += 1; }
            name[len++] = static_cast<char16_t>(c);
        }
        for (auto& user : firmware.GetUserData())
        {
            std::memset(user.Nickname, 0, sizeof(user.Nickname));
            std::copy(name, name + len, user.Nickname);
            user.NameLength = static_cast<u16>(len);
        }
    }

    firmware.UpdateChecksums();
}

void SetNetDescription(std::string text)
{
    std::lock_guard lock(g.netDescriptionMutex);
    g.netDescription = std::move(text);
}

}

// --- Hooks from Platform.cpp -------------------------------------------------

}

namespace ndscore_hooks
{
using ndscore::g;
void OnSaveWritten() { g.saveCounter++; }
void OnFirmwareWritten() { g.firmwareCounter++; }
void OnStopSignal() { g.stopRequested = true; }

int NetSend(u8* data, int len)
{
    if (!g.net) return 0;
    ndscore::RecordFrame(data, len, true);
    return g.net->SendPacket(data, len, 0);
}

int NetRecv(u8* data)
{
    if (!g.net) return 0;
    int len = g.net->RecvPacket(data, 0);
    if (len > 0) ndscore::RecordFrame(data, len, false);
    return len;
}
}

namespace ndscore
{

// --- Public API ----------------------------------------------------------------

bool Init()
{
    if (g.initialized) return true;
    OpenAudio();
    ConfigureNetwork();
    g.initialized = true;
    return true;
}

void Shutdown()
{
    if (!g.initialized) return;
    Unload();
    CloseAudio();
    g.net.reset();
    g.initialized = false;
}

void ConfigureNetwork()
{
    if (g.running) return;
    if (!g.net)
    {
        g.net = std::make_unique<Net>();
        g.net->RegisterInstance(0);
    }

    auto enqueue = [](const u8* data, int len) {
        if (g.net) g.net->RXEnqueue(data, len);
    };

    // Release the previous driver first so its LAN relay port is free to bind again.
    g.net->SetDriver(nullptr);
    auto slirp = std::make_unique<SlirpDriver>(enqueue, SlirpAddressing::ForLan(SlirpAddressing::DetectLanAddress()));
    if (!slirp->IsValid())
    {
        SetNetDescription("Rete non disponibile");
        return;
    }

    const auto& addr = slirp->Addressing();
    std::string description = "Rete integrata (NAT)";
    if (!addr.lanIp) description += " - nessuna rete Wi-Fi: niente partite con dispositivi della stessa rete";
    else if (slirp->RelayActive())
        description += " - console " + IpText(addr.guestIp) + ", collegamento con la rete locale attivo (UDP " +
                       std::to_string(addr.relayPort) + ")";
    else description += " - porta " + std::to_string(addr.relayPort) + " occupata: niente partite con dispositivi della stessa rete";
    SetNetDescription(description);
    g.net->SetDriver(std::move(slirp));
}

bool Boot(const BootArgs& args)
{
    if (!g.initialized) Init();
    if (args.rom.empty())
    {
        SetError("Nessuna ROM.");
        return false;
    }

    Unload();
    ConfigureNetwork();   // the device may have joined another network since last time

    NDSArgs ndsArgs;
    ndsArgs.OutputSampleRate = g.audioRate;
    if (!args.useJit) ndsArgs.JIT = std::nullopt;

    // Reuse the console's own firmware when the app kept one, so the WFC user ID and
    // profile survive between sessions (games tie the friend roster to them).
    if (args.firmware.size() >= 0x20000)
    {
        Firmware saved(args.firmware.data(), static_cast<u32>(args.firmware.size()));
        if (saved.Buffer() && saved.GetEffectiveUserData().ChecksumValid())
            ndsArgs.Firmware = std::move(saved);
    }
    if (args.mac[0] | args.mac[1] | args.mac[2] | args.mac[3] | args.mac[4] | args.mac[5])
        std::copy(std::begin(args.mac), std::end(args.mac), ndsArgs.Firmware.GetHeader().MacAddr.begin());
    ApplyFirmwareSettings(ndsArgs.Firmware, args);

    NDSCart::NDSCartArgs cartArgs;
    if (!args.save.empty())
    {
        cartArgs.SRAMLength = static_cast<u32>(args.save.size());
        cartArgs.SRAM = std::make_unique<u8[]>(cartArgs.SRAMLength);
        std::memcpy(cartArgs.SRAM.get(), args.save.data(), cartArgs.SRAMLength);
    }

    auto cart = NDSCart::ParseROM(args.rom.data(), static_cast<u32>(args.rom.size()), nullptr, std::move(cartArgs));
    if (!cart)
    {
        SetError("La ROM non è un'immagine valida di una cartuccia Nintendo DS.");
        return false;
    }

    auto nds = std::make_unique<NDS>(std::move(ndsArgs));
    nds->SetNDSCart(std::move(cart));
    nds->Reset();
    nds->SetupDirectBoot(args.saveName.empty() ? "game" : args.saveName);
    nds->Start();

    {
        std::lock_guard emuLock(g.emuMutex);
        std::lock_guard audioLock(g.audioMutex);
        g.nds = std::move(nds);
    }
    {
        std::lock_guard lock(g.frameMutex);
        std::memset(g.frame, 0, sizeof(g.frame));
        g.frameId = 0;
    }

    g.saveCounter = 0;
    g.firmwareCounter = 0;
    g.fps = 0.0f;
    ResetNetStats();
    g.paused = false;
    g.stopRequested = false;
    g.running = true;
    if (!g.audio) OpenAudio();
    g.emuThread = std::thread(EmuThreadMain);
    return true;
}

void Stop()
{
    JoinEmuThread();
    std::lock_guard lock(g.emuMutex);
    if (g.nds) g.nds->Stop();
}

void Unload()
{
    Stop();
    std::lock_guard emuLock(g.emuMutex);
    std::lock_guard audioLock(g.audioMutex);
    g.nds.reset();
}

bool IsRunning() { return g.running; }

void SetPaused(bool paused)
{
    {
        std::lock_guard lock(g.pauseMutex);
        g.paused = paused;
    }
    g.pauseCv.notify_all();
}

void SetSpeed(float multiplier) { g.speed = std::max(0.0f, multiplier); }
void SetVolume(float volume) { g.volume = std::clamp(volume, 0.0f, 1.0f); }
void SetInput(uint32_t pressedMask) { g.inputMask = pressedMask & 0xFFF; }

void SetTouch(bool down, int x, int y)
{
    g.touchX = std::clamp(x, 0, kScreenWidth - 1);
    g.touchY = std::clamp(y, 0, kScreenHeight - 1);
    g.touchDown = down;
}

uint64_t CopyFrame(uint32_t* dst, uint64_t lastFrame)
{
    std::lock_guard lock(g.frameMutex);
    if (!dst || g.frameId == 0 || g.frameId == lastFrame) return 0;
    std::memcpy(dst, g.frame, sizeof(g.frame));
    return g.frameId;
}

float Fps() { return g.fps; }

uint32_t SaveCounter() { return g.saveCounter; }

std::vector<uint8_t> GetSave()
{
    std::lock_guard lock(g.emuMutex);
    if (!g.nds || !g.nds->CartInserted()) return {};
    const u8* save = g.nds->GetNDSSave();
    const u32 length = g.nds->GetNDSSaveLength();
    if (!save || length == 0) return {};
    return std::vector<uint8_t>(save, save + length);
}

uint32_t FirmwareCounter() { return g.firmwareCounter; }

std::vector<uint8_t> GetFirmware()
{
    std::lock_guard lock(g.emuMutex);
    if (!g.nds) return {};
    const Firmware& firmware = g.nds->SPI.GetFirmware();
    return std::vector<uint8_t>(firmware.Buffer(), firmware.Buffer() + firmware.Length());
}

std::vector<uint8_t> SaveState()
{
    std::lock_guard lock(g.emuMutex);
    if (!g.nds) return {};
    Savestate state;
    if (state.Error || !g.nds->DoSavestate(&state) || state.Error)
    {
        SetError("Impossibile creare lo stato salvato.");
        return {};
    }
    const auto* data = static_cast<const uint8_t*>(state.Buffer());
    return std::vector<uint8_t>(data, data + state.Length());
}

bool LoadState(const uint8_t* data, size_t length)
{
    if (!data || length == 0) return false;
    std::lock_guard lock(g.emuMutex);
    if (!g.nds) return false;

    Savestate backup;
    if (backup.Error || !g.nds->DoSavestate(&backup) || backup.Error)
    {
        SetError("Impossibile salvare lo stato attuale prima di caricare.");
        return false;
    }

    auto copy = std::make_unique<u8[]>(length);
    std::memcpy(copy.get(), data, length);
    Savestate state(copy.get(), static_cast<u32>(length), false);
    if (!g.nds->DoSavestate(&state) || state.Error)
    {
        Savestate restore(backup.Buffer(), backup.Length(), false);
        g.nds->DoSavestate(&restore);
        SetError("Lo stato salvato non è valido o viene da un'altra versione.");
        return false;
    }
    return true;
}

std::string NetDescription()
{
    std::lock_guard lock(g.netDescriptionMutex);
    return g.netDescription;
}

std::string NetReport() { return BuildNetReport(NetDescription()); }

std::string LastError()
{
    std::lock_guard lock(g.errorMutex);
    return g.lastError;
}

}
