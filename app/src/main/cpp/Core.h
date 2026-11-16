// Emulator core for the Android app: melonDS on its own thread, AAudio output and the
// Wi-Fi network. Mirrors the Windows client's ndscore.dll; driven from Kotlin via Jni.cpp.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ndscore
{

constexpr int kScreenWidth = 256;
constexpr int kScreenHeight = 192;
constexpr int kFramePixels = kScreenWidth * kScreenHeight * 2;   // top + bottom

// DS buttons as bits of the input mask (1 = pressed); same order as the Windows client.
enum Key : uint32_t
{
    KeyA = 1 << 0, KeyB = 1 << 1, KeySelect = 1 << 2, KeyStart = 1 << 3,
    KeyRight = 1 << 4, KeyLeft = 1 << 5, KeyUp = 1 << 6, KeyDown = 1 << 7,
    KeyR = 1 << 8, KeyL = 1 << 9, KeyX = 1 << 10, KeyY = 1 << 11,
};

struct BootArgs
{
    std::vector<uint8_t> rom;
    std::vector<uint8_t> save;       // empty: no save yet
    std::vector<uint8_t> firmware;   // empty: generate a fresh one
    std::string saveName;            // used by direct boot, usually the game id
    std::string nickname;            // firmware nickname (max 10 characters)
    uint8_t primaryDns[4] {};        // WFC DNS; 0.0.0.0 keeps the firmware default
    uint8_t secondaryDns[4] {};
    uint8_t mac[6] {};               // console MAC; all zero keeps the firmware's
    bool useJit = true;
};

bool Init();
void Shutdown();

bool Boot(const BootArgs& args);
void Stop();      // stops the emulation thread; the console stays readable
void Unload();
bool IsRunning();

void SetPaused(bool paused);
void SetSpeed(float multiplier);   // 1 normal, >1 fast forward (muted), 0 unlimited
void SetVolume(float volume);      // 0..1

void SetInput(uint32_t pressedMask);
void SetTouch(bool down, int x, int y);

/// Copies both screens (top then bottom, 256x384, RGBA as Android's ARGB_8888 Bitmap
/// expects) when a frame newer than lastFrame exists. Returns its id, or 0.
uint64_t CopyFrame(uint32_t* dst, uint64_t lastFrame);
float Fps();

uint32_t SaveCounter();
std::vector<uint8_t> GetSave();
uint32_t FirmwareCounter();
std::vector<uint8_t> GetFirmware();

std::vector<uint8_t> SaveState();
bool LoadState(const uint8_t* data, size_t length);

/// (Re)creates the Wi-Fi driver for the current network; only while no game runs.
void ConfigureNetwork();
std::string NetDescription();
std::string NetReport();

std::string LastError();

}
