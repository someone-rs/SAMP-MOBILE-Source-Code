#include <jni.h>
#include <android/log.h>
#include <ucontext.h>
#include <pthread.h>

#include "main.h"
#include "game/game.h"
#include "game/RW/RenderWare.h"
#include "net/netgame.h"
#include "gui/gui.h"
#include "chatwindow.h"
#include "spawnscreen.h"
#include "playertags.h"
#include "dialog.h"
#include "keyboard.h"
#include "settings.h"
#include "debug.h"
#include "serverbrowser.h"

#include "util/armhook.h"
#include "checkfilehash.h"
#include "str_obfuscator_no_template.hpp"

uintptr_t g_libGTASA = 0;
// Изменено: теперь путь задается жестко на общую папку Documents
const char* g_pszStorage = "/storage/emulated/0/Documents/";

const auto encryptedAddress = cryptor::create("", 0);
unsigned short port = 0;

CGame *pGame = nullptr;
CNetGame *pNetGame = nullptr;
CChatWindow *pChatWindow = nullptr;
CSpawnScreen *pSpawnScreen = nullptr;
CPlayerTags *pPlayerTags = nullptr;
CDialogWindow *pDialogWindow = nullptr;

CGUI *pGUI = nullptr;
CKeyBoard *pKeyBoard = nullptr;
CDebug *pDebug = nullptr;
CSettings *pSettings = nullptr;
CServerBrowser *pServerBrowser = nullptr;
bool g_bConnectRequested = false;

void InitHookStuff();
void InstallSpecialHooks();
void InitRenderWareFunctions();
void ApplyInGamePatches();
void ApplyPatches_level0();
void MainLoop();

void InitSAMP()
{
	Log("Initializing SAMP..");
	Log("Storage forced to: %s", g_pszStorage);

	pSettings = new CSettings();

	Log("Checking samp files..");
	if(!FileCheckSum())
	{
		Log("SOME FILES HAVE BEEN MODIFIED. YOU NEED REINSTALL SAMP!");
		std::terminate();
		return;
	}
}

void ConnectToServer(const char* szHost, int iPort)
{
	if(!pSettings || !szHost || !szHost[0]) return;
	strncpy(pSettings->Get().szHost, szHost, MAX_SETTINGS_STRING);
	pSettings->Get().szHost[MAX_SETTINGS_STRING] = '\0';
	if(iPort > 0 && iPort < 65536)
		pSettings->Get().iPort = iPort;
	if(pServerBrowser)
	{
		pServerBrowser->AddToHistory(szHost, pSettings->Get().iPort);
		pServerBrowser->Close();
	}
	g_bConnectRequested = true;
	Log("Server browser: connect requested -> %s:%d", pSettings->Get().szHost, pSettings->Get().iPort);
}

void InitInMenu()
{
	pGame = new CGame();
	pGame->InitInMenu();
	
	if(pSettings->Get().bDebug)
		pDebug = new CDebug();

	pGUI = new CGUI();
	pKeyBoard = new CKeyBoard();
	pChatWindow = new CChatWindow();
	pSpawnScreen = new CSpawnScreen();
	pPlayerTags = new CPlayerTags();
	pDialogWindow = new CDialogWindow();
	pServerBrowser = new CServerBrowser();
}

void InitInGame()
{
	static bool bGameInited = false;
	static bool bNetworkInited = false;

	if(!bGameInited)
	{
		pGame->InitInGame();
		pGame->SetMaxStats();

		// Изменено: проверка на онлайн режим
		if(pDebug && !pSettings->Get().bOnline && !pSettings->Get().szHost[0])
		{
			pDebug->SpawnLocalPlayer();
		}

		bGameInited = true;
		return;
	}

	// Server browser mode: wait for the user to pick a server instead of
	// auto-connecting. Legacy behavior (auto-connect from settings.ini)
	// is kept when [client] browser=0.
	bool bWantConnect = g_bConnectRequested ||
		(!pSettings->Get().bServerBrowser &&
		 (pSettings->Get().bOnline || pSettings->Get().szHost[0]));
	if(!bNetworkInited && bWantConnect)
	{
		Log("Connecting to %s:%d as %s", pSettings->Get().szHost, pSettings->Get().iPort, pSettings->Get().szNickName);

		pNetGame = new CNetGame(
			pSettings->Get().szHost,
			pSettings->Get().iPort,
			pSettings->Get().szNickName,
			pSettings->Get().szPassword);
		bNetworkInited = true;
		return;
	}
}

void MainLoop()
{
	InitInGame();

	if(pDebug) pDebug->Process();
	if(pNetGame) pNetGame->Process();
}

void handler(int signum, siginfo_t *info, void* contextPtr)
{
	ucontext_t* context = (ucontext_t*)contextPtr;

	if(info->si_signo == SIGSEGV)
	{
		Log("SIGSEGV | Fault address: %p", info->si_addr);
		Log("libGTASA base address: %p", (void*)g_libGTASA);
		Log("register states:");

#if defined(__arm__)
		Log("r0: 0x%lX, r1: 0x%lX, r2: 0x%lX, r3: 0x%lX",
			(unsigned long)context->uc_mcontext.arm_r0,
			(unsigned long)context->uc_mcontext.arm_r1,
			(unsigned long)context->uc_mcontext.arm_r2,
			(unsigned long)context->uc_mcontext.arm_r3);
		Log("r4: 0x%lx, r5: 0x%lx, r6: 0x%lx, r7: 0x%lx",
			(unsigned long)context->uc_mcontext.arm_r4,
			(unsigned long)context->uc_mcontext.arm_r5,
			(unsigned long)context->uc_mcontext.arm_r6,
			(unsigned long)context->uc_mcontext.arm_r7);
		Log("r8: 0x%lx, r9: 0x%lx, sl: 0x%lx, fp: 0x%lx",
			(unsigned long)context->uc_mcontext.arm_r8,
			(unsigned long)context->uc_mcontext.arm_r9,
			(unsigned long)context->uc_mcontext.arm_r10,
			(unsigned long)context->uc_mcontext.arm_fp);
		Log("ip: 0x%lx, sp: 0x%lx, lr: 0x%lx, pc: 0x%lx",
			(unsigned long)context->uc_mcontext.arm_ip,
			(unsigned long)context->uc_mcontext.arm_sp,
			(unsigned long)context->uc_mcontext.arm_lr,
			(unsigned long)context->uc_mcontext.arm_pc);

		Log("backtrace:");
		Log("1: libGTASA.so + 0x%lX", (unsigned long)(context->uc_mcontext.arm_pc - g_libGTASA));
		Log("2: libGTASA.so + 0x%lX", (unsigned long)(context->uc_mcontext.arm_lr - g_libGTASA));
#elif defined(__aarch64__)
		for(int i = 0; i < 28; i += 4)
		{
			Log("x%d: 0x%llX, x%d: 0x%llX, x%d: 0x%llX, x%d: 0x%llX",
				i, (unsigned long long)context->uc_mcontext.regs[i],
				i + 1, (unsigned long long)context->uc_mcontext.regs[i + 1],
				i + 2, (unsigned long long)context->uc_mcontext.regs[i + 2],
				i + 3, (unsigned long long)context->uc_mcontext.regs[i + 3]);
		}
		Log("sp: 0x%llX, pc: 0x%llX",
			(unsigned long long)context->uc_mcontext.sp,
			(unsigned long long)context->uc_mcontext.pc);
		Log("backtrace:");
		Log("1: libGTASA.so + 0x%llX",
			(unsigned long long)(context->uc_mcontext.pc - g_libGTASA));
#else
		Log("(register dump not implemented for this architecture)");
#endif

		exit(0);
	}

	return;
}

void *Init(void *p)
{
	ApplyPatches_level0();

	pthread_exit(0);
}

jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	Log("SAMP library loaded! Build time: " __DATE__ " " __TIME__);

	g_libGTASA = FindLibrary("libGTASA.so");
	if(g_libGTASA == 0)
	{
		Log("ERROR: libGTASA.so address not found!");
		return 0;
	}

	Log("libGTASA.so image base address: 0x%X", g_libGTASA);

	srand(time(0));

	InitHookStuff();
	InitRenderWareFunctions();
	InstallSpecialHooks();

	pthread_t thread;
	pthread_create(&thread, 0, Init, 0);

	struct sigaction act;
	act.sa_sigaction = handler;
	sigemptyset(&act.sa_mask);
	act.sa_flags = SA_SIGINFO;
	sigaction(SIGSEGV, &act, 0);

	return JNI_VERSION_1_4;
}

void Log(const char *fmt, ...)
{	
	char buffer[0xFF];
	static FILE* flLog = nullptr;

	if(flLog == nullptr && g_pszStorage != nullptr)
	{
		sprintf(buffer, "%sSAMP/samp_log.txt", g_pszStorage);
		flLog = fopen(buffer, "a");
	}

	memset(buffer, 0, sizeof(buffer));

	va_list arg;
	va_start(arg, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, arg);
	va_end(arg);

	__android_log_write(ANDROID_LOG_INFO, "AXL", buffer);

	if(pDebug) pDebug->AddMessage(buffer);

	if(flLog == nullptr) return;
	fprintf(flLog, "%s\n", buffer);
	fflush(flLog);

	return;
}

uint32_t GetTickCount()
{
	struct timeval tv;
	gettimeofday(&tv, nullptr);
	return (tv.tv_sec*1000+tv.tv_usec/1000);
}
