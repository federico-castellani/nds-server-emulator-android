// Network diagnostics for the per-game network log (see NetStats.cpp).
#pragma once

#include <string>

#include <types.h>

namespace ndscore
{
/// Records an Ethernet frame sent by (outgoing) or delivered to the console.
void RecordFrame(const melonDS::u8* frame, int len, bool outgoing);
std::string BuildNetReport(const std::string& connection);
void ResetNetStats();
}
