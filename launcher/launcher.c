#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <stdio.h>
#include <wchar.h>
#include <string.h>

static int ok(const wchar_t *p) { return GetFileAttributesW(p) != INVALID_FILE_ATTRIBUTES; }

static int scan(const wchar_t *base, wchar_t *out) {
    wchar_t pat[MAX_PATH], cand[MAX_PATH]; WIN32_FIND_DATAW fd; int found = 0;
    swprintf(pat, MAX_PATH, L"%ls\\*", base);
    HANDLE h = FindFirstFileW(pat, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || fd.cFileName[0] == L'.') continue;
        swprintf(cand, MAX_PATH, L"%ls\\%ls\\bin\\javaw.exe", base, fd.cFileName);
        if (ok(cand)) { wcscpy(out, cand); found = 1; }
    } while (FindNextFileW(h, &fd));
    FindClose(h); return found;
}


static DWORD mainTid, javaPid; static HANDLE hToJava, hFromJava; static CRITICAL_SECTION cs;
static wchar_t selfPath[MAX_PATH * 2];

/* ---- start with Windows: HKCU\...\Run value "PoingSaveManager" = "<this exe>" --tray ---- */
static const wchar_t *RUN_KEY = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
static const wchar_t *RUN_NAME = L"PoingSaveManager";

static int autostartGet(void) {
    HKEY k; if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_QUERY_VALUE, &k) != ERROR_SUCCESS) return 0;
    LONG r = RegQueryValueExW(k, RUN_NAME, NULL, NULL, NULL, NULL); RegCloseKey(k); return r == ERROR_SUCCESS;
}

static int autostartSet(int on) {
    HKEY k; if (RegCreateKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, NULL, 0, KEY_SET_VALUE, NULL, &k, NULL) != ERROR_SUCCESS) return 0;
    LONG r;
    if (on) { wchar_t v[MAX_PATH * 2 + 16]; swprintf(v, MAX_PATH * 2 + 16, L"\"%ls\" --tray", selfPath);
              r = RegSetValueExW(k, RUN_NAME, 0, REG_SZ, (const BYTE *)v, (DWORD)((wcslen(v) + 1) * sizeof(wchar_t))); }
    else { r = RegDeleteValueW(k, RUN_NAME); if (r == ERROR_FILE_NOT_FOUND) r = ERROR_SUCCESS; }
    RegCloseKey(k); return r == ERROR_SUCCESS;
}

static void sendLine(const char *s) { DWORD w; EnterCriticalSection(&cs); WriteFile(hToJava, s, (DWORD)strlen(s), &w, NULL); LeaveCriticalSection(&cs); }

/* ---- natywne okno wyboru pliku/folderu (to samo, co w Eksploratorze) ---- */
typedef struct { int folder; wchar_t init[MAX_PATH * 2]; wchar_t title[256]; } PickReq;

static BOOL CALLBACK findWin(HWND w, LPARAM lp) {
    DWORD pid; GetWindowThreadProcessId(w, &pid);
    if (pid == javaPid && IsWindowVisible(w) && GetWindow(w, GW_OWNER) == NULL) { *(HWND *)lp = w; return FALSE; }
    return TRUE;
}

static DWORD WINAPI pickThread(LPVOID arg) {
    PickReq *rq = (PickReq *)arg; char out[MAX_PATH * 8]; int done = 0;
    if (SUCCEEDED(CoInitializeEx(NULL, COINIT_APARTMENTTHREADED))) {
        IFileOpenDialog *d = NULL;
        if (SUCCEEDED(CoCreateInstance(&CLSID_FileOpenDialog, NULL, CLSCTX_INPROC_SERVER, &IID_IFileOpenDialog, (void **)&d))) {
            DWORD o = 0; IFileOpenDialog_GetOptions(d, &o);
            IFileOpenDialog_SetOptions(d, o | FOS_FORCEFILESYSTEM | (rq->folder ? FOS_PICKFOLDERS : 0));
            IFileOpenDialog_SetTitle(d, rq->title);
            if (rq->init[0]) {
                wchar_t dir[MAX_PATH * 2]; wcscpy(dir, rq->init);
                DWORD at = GetFileAttributesW(dir);
                if (at != INVALID_FILE_ATTRIBUTES && !(at & FILE_ATTRIBUTE_DIRECTORY)) { wchar_t *sl = wcsrchr(dir, L'\\'); if (sl) *sl = 0; }
                else if (at == INVALID_FILE_ATTRIBUTES) { wchar_t *sl = wcsrchr(dir, L'\\'); if (sl) *sl = 0; }
                IShellItem *si = NULL;
                if (SUCCEEDED(SHCreateItemFromParsingName(dir, NULL, &IID_IShellItem, (void **)&si)) && si) { IFileOpenDialog_SetFolder(d, si); IShellItem_Release(si); }
            }
            HWND owner = NULL; EnumWindows(findWin, (LPARAM)&owner);
            if (SUCCEEDED(IFileOpenDialog_Show(d, owner))) {
                IShellItem *r = NULL;
                if (SUCCEEDED(IFileOpenDialog_GetResult(d, &r)) && r) {
                    PWSTR path = NULL;
                    if (SUCCEEDED(IShellItem_GetDisplayName(r, SIGDN_FILESYSPATH, &path)) && path) {
                        int n = WideCharToMultiByte(CP_UTF8, 0, path, -1, out + 7, (int)sizeof(out) - 9, NULL, NULL);
                        if (n > 0) { memcpy(out, "PICKED\t", 7); strcat(out, "\n"); done = 1; }
                        CoTaskMemFree(path);
                    }
                    IShellItem_Release(r);
                }
            }
            IFileOpenDialog_Release(d);
        }
        CoUninitialize();
    }
    if (!done) strcpy(out, "PICKCANCEL\n");
    sendLine(out); free(rq); return 0;
}

/* linia: PICK <folder|file>\t<poczatkowa sciezka>\t<tytul> (UTF-8) */
static void startPick(const char *line) {
    PickReq *rq = (PickReq *)calloc(1, sizeof(PickReq)); if (!rq) return;
    rq->folder = strncmp(line, "folder", 6) == 0;
    const char *t1 = strchr(line, '\t'); if (!t1) { free(rq); return; }
    const char *t2 = strchr(t1 + 1, '\t'); if (!t2) { free(rq); return; }
    char ini[MAX_PATH * 4]; size_t li = (size_t)(t2 - t1 - 1); if (li >= sizeof ini) li = sizeof ini - 1;
    memcpy(ini, t1 + 1, li); ini[li] = 0;
    MultiByteToWideChar(CP_UTF8, 0, ini, -1, rq->init, MAX_PATH * 2);
    MultiByteToWideChar(CP_UTF8, 0, t2 + 1, -1, rq->title, 256);
    CreateThread(NULL, 0, pickThread, rq, 0, NULL);
}

/* Czyta linie z procesu Javy: "HKCLEAR" i "HK <id> <mods> <vk>". Rejestracja musi isc w watku z petla komunikatow. */
static DWORD WINAPI reader(LPVOID unused) {
    static char buf[4200]; char c; int n = 0; DWORD r;
    for (;;) {
        if (!ReadFile(hFromJava, &c, 1, &r, NULL) || r == 0) break;
        if (c == '\n') {
            buf[n] = 0; n = 0;
            if (strncmp(buf, "AUTOSTART SET ", 14) == 0) {
                int on = buf[14] == '1'; char b[40];
                if (autostartSet(on)) { sprintf(b, "AUTOSTART_OK %d\n", on); sendLine(b); } else sendLine("AUTOSTART_FAIL\n");
            }
            else if (strncmp(buf, "HKCLEAR", 7) == 0) PostThreadMessage(mainTid, WM_APP + 1, 0, 0);
            else if (strncmp(buf, "PICK ", 5) == 0) startPick(buf + 5);
            else if (strncmp(buf, "HK ", 3) == 0) {
                int id, m, vk;
                if (sscanf(buf + 3, "%d %d %d", &id, &m, &vk) == 3)
                    PostThreadMessage(mainTid, WM_APP, (WPARAM)id, (LPARAM)((m << 16) | vk));
            }
        } else if (c != '\r' && n < 4190) buf[n++] = c;
    }
    PostThreadMessage(mainTid, WM_QUIT, 0, 0);   /* Java sie zamknela */
    return 0;
}

int WINAPI WinMain(HINSTANCE h, HINSTANCE p, LPSTR c, int s) {
    wchar_t *self = selfPath, java[MAX_PATH * 2] = L"", tmp[MAX_PATH], cmd[MAX_PATH * 5];
    GetModuleFileNameW(NULL, self, MAX_PATH * 2);
    if (GetEnvironmentVariableW(L"JAVA_HOME", tmp, MAX_PATH)) {
        swprintf(java, MAX_PATH * 2, L"%ls\\bin\\javaw.exe", tmp);
        if (!ok(java)) java[0] = 0;
    }
    if (!java[0]) {
        const wchar_t *vars[] = { L"ProgramFiles", L"ProgramW6432", L"LOCALAPPDATA" };
        const wchar_t *subs[] = { L"\\Eclipse Adoptium", L"\\Java", L"\\Microsoft", L"\\Zulu", L"\\BellSoft", L"\\Amazon Corretto", L"\\Programs\\Eclipse Adoptium" };
        for (int v = 0; v < 3 && !java[0]; v++) {
            wchar_t root[MAX_PATH];
            if (!GetEnvironmentVariableW(vars[v], root, MAX_PATH)) continue;
            for (int i = 0; i < 7; i++) { wchar_t b[MAX_PATH]; swprintf(b, MAX_PATH, L"%ls%ls", root, subs[i]); if (scan(b, java)) break; }
        }
    }
    if (!java[0]) wcscpy(java, L"javaw.exe");   /* ostatnia szansa: PATH */
    int auto_on = autostartGet();
    if (auto_on) autostartSet(1);                                   /* refresh the path if the exe was moved */
    int tray = wcsstr(GetCommandLineW(), L"--tray") != NULL;      /* started by "Start with Windows" */
    swprintf(cmd, MAX_PATH * 5, L"\"%ls\" -Xmx512m -Dpsm.bridge=true -Dpsm.autostart=%ls -jar \"%ls\"%ls", java, auto_on ? L"true" : L"false", self, tray ? L" --tray" : L"");

    /* rury: launcher -> java (stdin), java -> launcher (stdout+stderr) */
    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE inR, inW, outR, outW;
    CreatePipe(&inR, &inW, &sa, 0); CreatePipe(&outR, &outW, &sa, 0);
    SetHandleInformation(inW, HANDLE_FLAG_INHERIT, 0); SetHandleInformation(outR, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si; PROCESS_INFORMATION pi; ZeroMemory(&si, sizeof si); si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES; si.hStdInput = inR; si.hStdOutput = outW; si.hStdError = outW;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        MessageBoxW(NULL, L"Java was not found (version 11 or newer is required).\n"
                          L"Install it, e.g. Eclipse Temurin: https://adoptium.net, then start the program again.\n\n"
                          L"Nie znaleziono Javy (wymagana wersja 11 lub nowsza).\n"
                          L"Zainstaluj ją, np. Eclipse Temurin: https://adoptium.net, i uruchom program ponownie.",
                    L"PoingSaveManager", MB_ICONERROR);
        return 1;
    }
    javaPid = pi.dwProcessId; InitializeCriticalSection(&cs);
    CloseHandle(inR); CloseHandle(outW); CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    hToJava = inW; hFromJava = outR;

    mainTid = GetCurrentThreadId();
    MSG msg; PeekMessage(&msg, NULL, WM_USER, WM_USER, PM_NOREMOVE);      /* utworz kolejke zanim ruszy watek */
    CreateThread(NULL, 0, reader, NULL, 0, NULL);

    int reg[64], nreg = 0;
    while (GetMessage(&msg, NULL, 0, 0) > 0) {
        if (msg.message == WM_APP) {
            int id = (int)msg.wParam, mods = (int)((msg.lParam >> 16) & 0xF), vk = (int)(msg.lParam & 0xFFFF);
            if (nreg < 64 && RegisterHotKey(NULL, id, mods | MOD_NOREPEAT, vk)) reg[nreg++] = id;
            else { char b[32]; sprintf(b, "HKFAIL %d\n", id); sendLine(b); }
        } else if (msg.message == WM_APP + 1) {
            while (nreg > 0) UnregisterHotKey(NULL, reg[--nreg]);
        } else if (msg.message == WM_HOTKEY) {
            char b[32]; sprintf(b, "HOTKEY %d\n", (int)msg.wParam); sendLine(b);
        }
    }
    while (nreg > 0) UnregisterHotKey(NULL, reg[--nreg]);
    return 0;
}
