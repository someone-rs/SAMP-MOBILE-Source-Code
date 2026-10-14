#pragma once

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <list>
#include <unistd.h>

#define SAMP_VERSION	"0.3.7"
#define PORT_VERSION	"0.69"

#define MAX_PLAYERS		1004
#define MAX_VEHICLES	2000
#define MAX_PLAYER_NAME	24

#define RAKSAMP_CLIENT
#define NETCODE_CONNCOOKIELULZ 0x6969
#include "vendor/RakNet/SAMP/samp_netencr.h"
#include "vendor/RakNet/SAMP/SAMPRPC.h"

#include "util/util.h"

extern uintptr_t g_libGTASA;
extern char g_szStorage[512]; // set from Java via initializeSAMP(); defaults to legacy path

class CServerBrowser;
extern CServerBrowser* pServerBrowser;
extern bool g_bConnectRequested;
void ConnectToServer(const char* szHost, int iPort);

void Log(const char *fmt, ...);
uint32_t GetTickCount();