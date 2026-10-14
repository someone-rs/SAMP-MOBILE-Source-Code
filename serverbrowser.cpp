#include "main.h"
#include "serverbrowser.h"
#include "settings.h"
#include "gui/gui.h"
#include "vendor/inih/cpp/INIReader.h"

#include <thread>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>

extern CSettings* pSettings;
extern CGUI* pGUI;

// SA-MP 0.3.7 server query protocol over UDP:
//   out: 'S' 'A' 'M' 'P' + ip[4] + port LE16 + opcode
//   'i' reply: 'S' 'A' 'M' 'P' + ip[4] + port LE16 + 'i'
//              + [u8 len][server name] + [u8 len][gamemode]
//              + [u8 len][language] + [u16 LE players][u16 LE maxplayers]

CServerBrowser::CServerBrowser()
	: m_bOpen(true)
	, m_bShowAdd(false)
	, m_bInitialRefresh(false)
	, m_bQuerying(false)
{
	Log("Initializing Server Browser..");

	m_fScaleX = pGUI ? pGUI->ScaleX(1.0f) : 1.0f;
	m_fScaleY = pGUI ? pGUI->ScaleY(1.0f) : 1.0f;

	m_szAddName[0] = '\0';
	m_szAddHost[0] = '\0';
	m_szAddPort[0] = '\0';
	strcpy(m_szAddPort, "7777");

	LoadFavorites();
}

CServerBrowser::~CServerBrowser()
{
}

void CServerBrowser::LoadFavorites()
{
	char path[256];
	snprintf(path, sizeof(path), "%sSAMP/servers.ini", g_szStorage);

	INIReader reader(path);
	if(reader.ParseError() < 0)
	{
		Log("ServerBrowser: no servers.ini, starting fresh");
		return;
	}

	auto loadSection = [&](const char* section, std::vector<stServerEntry>& list)
	{
		int count = reader.GetInteger(section, "count", 0);
		for(int i = 0; i < count && (int)list.size() < MAX_SERVERS; i++)
		{
			char key[32];
			stServerEntry e{};
			snprintf(key, sizeof(key), "name%d", i);
			std::string name = reader.Get(section, key, "");
			snprintf(key, sizeof(key), "host%d", i);
			std::string host = reader.Get(section, key, "");
			snprintf(key, sizeof(key), "port%d", i);
			int port = reader.GetInteger(section, key, 7777);
			if(host.empty() || port <= 0 || port > 65535)
				continue;
			strncpy(e.szName, name.empty() ? host.c_str() : name.c_str(), sizeof(e.szName) - 1);
			strncpy(e.szHost, host.c_str(), sizeof(e.szHost) - 1);
			e.iPort = port;
			e.iPingMs = -1;
			list.push_back(e);
		}
	};

	loadSection("favorites", m_Favorites);
	loadSection("history", m_History);
	Log("ServerBrowser: loaded %d favorites, %d history entries",
		(int)m_Favorites.size(), (int)m_History.size());
}

void CServerBrowser::SaveFavorites()
{
	char path[256];
	snprintf(path, sizeof(path), "%sSAMP/servers.ini", g_szStorage);

	FILE* f = fopen(path, "w");
	if(!f)
	{
		Log("ServerBrowser: can't write %s", path);
		return;
	}

	auto saveSection = [&](const char* section, const std::vector<stServerEntry>& list)
	{
		fprintf(f, "[%s]\n", section);
		fprintf(f, "count=%d\n", (int)list.size());
		for(size_t i = 0; i < list.size(); i++)
		{
			fprintf(f, "name%zu=%s\n", i, list[i].szName);
			fprintf(f, "host%zu=%s\n", i, list[i].szHost);
			fprintf(f, "port%zu=%d\n", i, list[i].iPort);
		}
		fprintf(f, "\n");
	};

	std::unique_lock<std::mutex> lock(m_Mutex);
	saveSection("favorites", m_Favorites);
	saveSection("history", m_History);
	fclose(f);
}

void CServerBrowser::AddFavorite(const char* szName, const char* szHost, int iPort)
{
	if(!szHost || !szHost[0] || iPort <= 0 || iPort > 65535)
		return;

	std::unique_lock<std::mutex> lock(m_Mutex);
	for(const auto& e : m_Favorites)
	{
		if(e.iPort == iPort && strcmp(e.szHost, szHost) == 0)
			return; // already there
	}
	if((int)m_Favorites.size() >= MAX_SERVERS)
		m_Favorites.erase(m_Favorites.begin());

	stServerEntry e{};
	strncpy(e.szName, (szName && szName[0]) ? szName : szHost, sizeof(e.szName) - 1);
	strncpy(e.szHost, szHost, sizeof(e.szHost) - 1);
	e.iPort = iPort;
	e.iPingMs = -1;
	m_Favorites.push_back(e);

	lock.unlock();
	SaveFavorites();
	Refresh();
}

void CServerBrowser::RemoveFavorite(size_t index)
{
	std::unique_lock<std::mutex> lock(m_Mutex);
	if(index < m_Favorites.size())
		m_Favorites.erase(m_Favorites.begin() + index);
	lock.unlock();
	SaveFavorites();
}

void CServerBrowser::AddToHistory(const char* szHost, int iPort)
{
	if(!szHost || !szHost[0])
		return;

	std::unique_lock<std::mutex> lock(m_Mutex);
	for(auto it = m_History.begin(); it != m_History.end(); ++it)
	{
		if(it->iPort == iPort && strcmp(it->szHost, szHost) == 0)
		{
			m_History.erase(it);
			break;
		}
	}
	while((int)m_History.size() >= 10)
		m_History.erase(m_History.begin());

	stServerEntry e{};
	strncpy(e.szName, szHost, sizeof(e.szName) - 1);
	strncpy(e.szHost, szHost, sizeof(e.szHost) - 1);
	e.iPort = iPort;
	e.iPingMs = -1;
	m_History.insert(m_History.begin(), e);

	lock.unlock();
	SaveFavorites();
}

void CServerBrowser::Refresh()
{
	if(m_bQuerying.exchange(true))
		return;
	std::thread(QueryThread, this).detach();
}

bool CServerBrowser::QueryServer(const char* szHost, int iPort, stServerEntry& out)
{
	struct addrinfo hints{};
	hints.ai_family = AF_INET;
	hints.ai_socktype = SOCK_DGRAM;

	char szPort[16];
	snprintf(szPort, sizeof(szPort), "%d", iPort);

	struct addrinfo* res = nullptr;
	if(getaddrinfo(szHost, szPort, &hints, &res) != 0 || !res)
		return false;
	struct sockaddr_in addr = *reinterpret_cast<struct sockaddr_in*>(res->ai_addr);
	freeaddrinfo(res);

	int sock = socket(AF_INET, SOCK_DGRAM, 0);
	if(sock < 0)
		return false;

	struct timeval tv{};
	tv.tv_sec = 1;
	tv.tv_usec = 200000; // 1.2 s timeout
	setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

	uint8_t pkt[11];
	pkt[0] = 'S'; pkt[1] = 'A'; pkt[2] = 'M'; pkt[3] = 'P';
	memcpy(&pkt[4], &addr.sin_addr.s_addr, 4);
	uint16_t p = static_cast<uint16_t>(iPort);
	pkt[8] = static_cast<uint8_t>(p & 0xFF);
	pkt[9] = static_cast<uint8_t>((p >> 8) & 0xFF);
	pkt[10] = 'i'; // info opcode

	uint32_t t0 = GetTickCount();
	bool ok = sendto(sock, pkt, sizeof(pkt), 0,
		reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == (ssize_t)sizeof(pkt);

	uint8_t resp[1400];
	ssize_t n = ok ? recvfrom(sock, resp, sizeof(resp), 0, nullptr, nullptr) : -1;
	uint32_t t1 = GetTickCount();
	close(sock);

	if(n < 15)
		return false;
	if(resp[0] != 'S' || resp[1] != 'A' || resp[2] != 'M' || resp[3] != 'P' || resp[10] != 'i')
		return false;

	size_t off = 11;
	auto readStr = [&](char* dst, size_t dstsz) -> bool
	{
		if(off >= (size_t)n)
			return false;
		uint8_t len = resp[off++];
		if(off + len > (size_t)n)
			return false;
		size_t cpy = len < dstsz - 1 ? len : dstsz - 1;
		memcpy(dst, &resp[off], cpy);
		dst[cpy] = '\0';
		off += len;
		return true;
	};

	char szLang[32];
	if(!readStr(out.szQueryName, sizeof(out.szQueryName))) return false;
	if(!readStr(out.szGamemode, sizeof(out.szGamemode))) return false;
	if(!readStr(szLang, sizeof(szLang))) return false;
	if(off + 4 > (size_t)n) return false;

	out.iPlayers = resp[off] | (resp[off + 1] << 8);
	out.iMaxPlayers = resp[off + 2] | (resp[off + 3] << 8);
	out.iPingMs = static_cast<int>(t1 - t0);
	out.bOnline = true;
	out.bQueried = true;
	return true;
}

void CServerBrowser::QueryThread(CServerBrowser* pBrowser)
{
	std::vector<stServerEntry> work;
	{
		std::lock_guard<std::mutex> lock(pBrowser->m_Mutex);
		work = pBrowser->m_Favorites;
		work.insert(work.end(), pBrowser->m_History.begin(), pBrowser->m_History.end());
	}

	for(auto& e : work)
	{
		stServerEntry q{};
		q.bQueried = true;
		if(QueryServer(e.szHost, e.iPort, q))
		{
			e.bOnline = true;
			strcpy(e.szQueryName, q.szQueryName);
			strcpy(e.szGamemode, q.szGamemode);
			e.iPlayers = q.iPlayers;
			e.iMaxPlayers = q.iMaxPlayers;
			e.iPingMs = q.iPingMs;
		}
		else
		{
			e.bOnline = false;
		}
		e.bQueried = true;
	}

	{
		std::lock_guard<std::mutex> lock(pBrowser->m_Mutex);
		auto apply = [&](std::vector<stServerEntry>& list)
		{
			for(auto& dst : list)
			{
				for(const auto& src : work)
				{
					if(dst.iPort == src.iPort && strcmp(dst.szHost, src.szHost) == 0)
					{
						dst.bQueried = src.bQueried;
						dst.bOnline = src.bOnline;
						strcpy(dst.szQueryName, src.szQueryName);
						strcpy(dst.szGamemode, src.szGamemode);
						dst.iPlayers = src.iPlayers;
						dst.iMaxPlayers = src.iMaxPlayers;
						dst.iPingMs = src.iPingMs;
						break;
					}
				}
			}
		};
		apply(pBrowser->m_Favorites);
		apply(pBrowser->m_History);
		pBrowser->m_bQuerying = false;
	}
}

void CServerBrowser::RenderServerList(const char* szTitle, std::vector<stServerEntry>& list, bool bFavorites)
{
	ImGui::Text("%s (%d)", szTitle, (int)list.size());

	ImGui::Columns(5, nullptr, false);
	ImGui::Text("Server"); ImGui::NextColumn();
	ImGui::Text("Players"); ImGui::NextColumn();
	ImGui::Text("Ping"); ImGui::NextColumn();
	ImGui::Text("Mode"); ImGui::NextColumn();
	ImGui::Text(""); ImGui::NextColumn();
	ImGui::Separator();

	for(size_t i = 0; i < list.size(); i++)
	{
		stServerEntry& e = list[i];
		const char* name = (e.bQueried && e.szQueryName[0]) ? e.szQueryName : e.szName;

		ImGui::Text("%s", name);
		if(ImGui::IsItemHovered())
			ImGui::SetTooltip("%s:%d", e.szHost, e.iPort);
		ImGui::NextColumn();

		if(e.bQueried && e.bOnline)
			ImGui::Text("%d/%d", e.iPlayers, e.iMaxPlayers);
		else if(e.bQueried)
			ImGui::Text("offline");
		else
			ImGui::Text("...");
		ImGui::NextColumn();

		if(e.bQueried && e.bOnline)
			ImGui::Text("%d ms", e.iPingMs);
		else
			ImGui::Text("-");
		ImGui::NextColumn();

		ImGui::Text("%s", (e.bQueried && e.bOnline) ? e.szGamemode : "-");
		ImGui::NextColumn();

		ImGui::PushID(static_cast<int>(i * 2 + (bFavorites ? 0 : 1)));
		if(ImGui::SmallButton("Connect"))
			ConnectToServer(e.szHost, e.iPort);
		if(bFavorites)
		{
			ImGui::SameLine();
			if(ImGui::SmallButton("X"))
			{
				RemoveFavorite(i);
				ImGui::PopID();
				break; // list changed, stop this frame
			}
		}
		ImGui::PopID();
		ImGui::NextColumn();
	}
	ImGui::Columns(1);
}

void CServerBrowser::Render()
{
	if(!m_bOpen)
		return;

	if(!m_bInitialRefresh)
	{
		m_bInitialRefresh = true;
		Refresh();
	}

	ImGuiIO& io = ImGui::GetIO();
	ImVec2 size(io.DisplaySize.x * 0.88f, io.DisplaySize.y * 0.82f);
	ImGui::SetNextWindowSize(size, ImGuiCond_FirstUseEver);
	ImGui::SetNextWindowPos(
		ImVec2((io.DisplaySize.x - size.x) * 0.5f, (io.DisplaySize.y - size.y) * 0.5f),
		ImGuiCond_FirstUseEver);

	if(!ImGui::Begin("SA-MP Server Browser", nullptr,
		ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoCollapse))
	{
		ImGui::End();
		return;
	}

	if(ImGui::Button(m_bQuerying ? "Refreshing..." : "Refresh"))
		Refresh();
	ImGui::SameLine();
	if(ImGui::Button(m_bShowAdd ? "Cancel" : "Add Server"))
		m_bShowAdd = !m_bShowAdd;
	ImGui::SameLine();
	if(ImGui::Button("Play Offline"))
		Close();

	if(m_bShowAdd)
	{
		ImGui::Separator();
		ImGui::InputText("Name", m_szAddName, sizeof(m_szAddName));
		ImGui::InputText("Host", m_szAddHost, sizeof(m_szAddHost));
		ImGui::InputText("Port", m_szAddPort, sizeof(m_szAddPort));
		if(ImGui::Button("Save Server"))
		{
			int port = atoi(m_szAddPort);
			if(m_szAddHost[0] && port > 0 && port < 65536)
			{
				AddFavorite(m_szAddName[0] ? m_szAddName : m_szAddHost, m_szAddHost, port);
				m_szAddName[0] = '\0';
				m_szAddHost[0] = '\0';
				m_bShowAdd = false;
			}
		}
	}

	ImGui::Separator();

	// snapshot under lock so the query thread never races the render thread
	std::vector<stServerEntry> favCopy, histCopy;
	{
		std::lock_guard<std::mutex> lock(m_Mutex);
		favCopy = m_Favorites;
		histCopy = m_History;
	}
	RenderServerList("Favorites", favCopy, true);
	ImGui::Separator();
	RenderServerList("History", histCopy, false);

	ImGui::End();
}
