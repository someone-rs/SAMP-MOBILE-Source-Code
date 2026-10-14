#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#define MAX_SERVERS			32
#define MAX_SERVER_NAME		64

struct stServerEntry
{
	char szName[MAX_SERVER_NAME];
	char szHost[128];
	int iPort;

	// live SA-MP query results
	char szQueryName[MAX_SERVER_NAME];
	char szGamemode[64];
	int iPlayers;
	int iMaxPlayers;
	int iPingMs;		// -1 = unknown
	bool bOnline;
	bool bQueried;
};

class CServerBrowser
{
public:
	CServerBrowser();
	~CServerBrowser();

	void Render();

	bool IsOpen() const { return m_bOpen; }
	void Open() { m_bOpen = true; }
	void Close() { m_bOpen = false; }

	void Refresh();
	void AddToHistory(const char* szHost, int iPort);

private:
	void LoadFavorites();
	void SaveFavorites();
	void RenderServerList(const char* szTitle, std::vector<stServerEntry>& list, bool bFavorites);
	void AddFavorite(const char* szName, const char* szHost, int iPort);
	void RemoveFavorite(size_t index);

	static void QueryThread(CServerBrowser* pBrowser);
	static bool QueryServer(const char* szHost, int iPort, stServerEntry& out);

private:
	bool					m_bOpen;
	bool					m_bShowAdd;
	bool					m_bInitialRefresh;
	std::atomic<bool>		m_bQuerying;
	std::vector<stServerEntry> m_Favorites;
	std::vector<stServerEntry> m_History;
	std::mutex				m_Mutex;

	float					m_fScaleX;
	float					m_fScaleY;

	char m_szAddName[MAX_SERVER_NAME];
	char m_szAddHost[128];
	char m_szAddPort[16];
};
