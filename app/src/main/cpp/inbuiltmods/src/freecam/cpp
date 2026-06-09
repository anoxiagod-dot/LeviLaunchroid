#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>
#include <fstream>
#include <string>
#include <cmath>

#include "pl/Gloss.h"

#define LOG_TAG "LeviFreeCam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── состояние мода ───────────────────────────────────────────────────────────

static bool g_initialized    = false;
static bool g_freecamActive  = false;

// Позиция свободной камеры (x, y, z)
static float g_camX = 0.f, g_camY = 0.f, g_camZ = 0.f;

// Углы камеры (yaw = горизонталь, pitch = вертикаль) — в градусах
static float g_camYaw   = 0.f;
static float g_camPitch = 0.f;

// Скорость полёта (блоков за тик)
static float g_speed = 0.5f;

// ─── оригинальные функции (сохраняем перед хуком) ─────────────────────────────

// VanillaCameraAPI::getPlayerViewPerspectiveOption  → int(void*)
static int   (*g_orig_getPerspective)(void*)        = nullptr;

// VanillaCameraAPI::getCameraPosition  → записывает float[3] в out-параметр
// сигнатура: void(void* this, float* outXYZ)
static void  (*g_orig_getCamPos)(void*, float*)     = nullptr;

// VanillaCameraAPI::getCameraRotation → void(void* this, float* outYawPitch)
static void  (*g_orig_getCamRot)(void*, float*)     = nullptr;

// ─── хуки ─────────────────────────────────────────────────────────────────────

// Перспектива: в режиме FreeCam возвращаем 0 (вид от первого лица).
// Это предотвращает отрисовку модели игрока перед камерой.
static int hook_getPerspective(void* self) {
    if (g_freecamActive) return 0;
    return g_orig_getPerspective ? g_orig_getPerspective(self) : 0;
}

// Позиция камеры: подменяем на нашу независимую позицию
static void hook_getCamPos(void* self, float* out) {
    if (g_freecamActive) {
        out[0] = g_camX;
        out[1] = g_camY;
        out[2] = g_camZ;
        return;
    }
    if (g_orig_getCamPos) g_orig_getCamPos(self, out);
}

// Вращение камеры: подменяем на наши углы
static void hook_getCamRot(void* self, float* out) {
    if (g_freecamActive) {
        out[0] = g_camYaw;
        out[1] = g_camPitch;
        return;
    }
    if (g_orig_getCamRot) g_orig_getCamRot(self, out);
}

// ─── поиск vtable и установка хуков ──────────────────────────────────────────

// Универсальная функция: ищет typeinfo по имени, затем vtable
static uintptr_t findVtable(const char* typeinfoName) {
    size_t nameLen = strlen(typeinfoName);
    uintptr_t typeinfoNameAddr = 0;

    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("libminecraftpe.so") == std::string::npos) continue;
        if (line.find("r--p") == std::string::npos &&
            line.find("r-xp") == std::string::npos) continue;

        uintptr_t start, end;
        if (sscanf(line.c_str(), "%lx-%lx", &start, &end) != 2) continue;

        for (uintptr_t addr = start; addr < end - nameLen; addr++) {
            if (memcmp((void*)addr, typeinfoName, nameLen) == 0) {
                typeinfoNameAddr = addr;
                break;
            }
        }
        if (typeinfoNameAddr) break;
    }

    if (!typeinfoNameAddr) {
        LOGE("findVtable: typeinfo name '%s' not found", typeinfoName);
        return 0;
    }

    // Ищем typeinfo struct (указатель на имя хранится в typeinfo + sizeof(void*))
    uintptr_t typeinfoAddr = 0;
    std::ifstream maps2("/proc/self/maps");
    while (std::getline(maps2, line)) {
        if (line.find("libminecraftpe.so") == std::string::npos) continue;
        if (line.find("r--p") == std::string::npos) continue;

        uintptr_t start, end;
        if (sscanf(line.c_str(), "%lx-%lx", &start, &end) != 2) continue;

        for (uintptr_t addr = start; addr < end - sizeof(void*); addr += sizeof(void*)) {
            if (*(uintptr_t*)addr == typeinfoNameAddr) {
                typeinfoAddr = addr - sizeof(void*);
                break;
            }
        }
        if (typeinfoAddr) break;
    }

    if (!typeinfoAddr) {
        LOGE("findVtable: typeinfo struct not found for '%s'", typeinfoName);
        return 0;
    }

    // Vtable начинается сразу после typeinfo указателя
    uintptr_t vtableAddr = 0;
    std::ifstream maps3("/proc/self/maps");
    while (std::getline(maps3, line)) {
        if (line.find("libminecraftpe.so") == std::string::npos) continue;
        if (line.find("r--p") == std::string::npos) continue;

        uintptr_t start, end;
        if (sscanf(line.c_str(), "%lx-%lx", &start, &end) != 2) continue;

        for (uintptr_t addr = start; addr < end - sizeof(void*); addr += sizeof(void*)) {
            if (*(uintptr_t*)addr == typeinfoAddr) {
                vtableAddr = addr + sizeof(void*);
                break;
            }
        }
        if (vtableAddr) break;
    }

    if (!vtableAddr) {
        LOGE("findVtable: vtable not found for '%s'", typeinfoName);
    } else {
        LOGI("findVtable: vtable for '%s' at 0x%lx", typeinfoName, vtableAddr);
    }
    return vtableAddr;
}

// Делает страницу памяти writable, заменяет слот vtable, возвращает обратно r
static bool patchVtableSlot(uintptr_t vtableAddr, int slotIndex,
                             void* hookFn, void** origFn) {
    uintptr_t* slot = (uintptr_t*)(vtableAddr + slotIndex * sizeof(void*));
    *origFn = (void*)(*slot);

    uintptr_t pageStart = (uintptr_t)slot & ~(uintptr_t)(4095);
    if (mprotect((void*)pageStart, 4096, PROT_READ | PROT_WRITE) != 0) {
        LOGE("patchVtableSlot: mprotect RW failed at slot %d", slotIndex);
        return false;
    }
    *slot = (uintptr_t)hookFn;
    mprotect((void*)pageStart, 4096, PROT_READ);
    LOGI("patchVtableSlot: slot %d hooked (orig=0x%lx)", slotIndex, (uintptr_t)*origFn);
    return true;
}

static bool hookCameraAPI() {
    // VanillaCameraAPI — тот же класс что в snaplook.cpp
    uintptr_t vtable = findVtable("16VanillaCameraAPI");
    if (!vtable) return false;

    // Слоты определены эмпирически по аналогии с zoom.cpp / snaplook.cpp:
    //   slot 7  — getPlayerViewPerspectiveOption  (подтверждено в snaplook.cpp)
    //   slot 8  — getCameraPosition
    //   slot 9  — getCameraRotation
    // Если слоты сдвинуты в другой версии MC — мод упадёт в лог с ошибкой,
    // не крашнув игру, потому что orig-указатели проверяются перед вызовом.

    bool ok = true;
    ok &= patchVtableSlot(vtable, 7,
                          (void*)hook_getPerspective,
                          (void**)&g_orig_getPerspective);
    ok &= patchVtableSlot(vtable, 8,
                          (void*)hook_getCamPos,
                          (void**)&g_orig_getCamPos);
    ok &= patchVtableSlot(vtable, 9,
                          (void*)hook_getCamRot,
                          (void**)&g_orig_getCamRot);
    return ok;
}

// ─── JNI — интерфейс для Java-стороны Levi Launcher ─────────────────────────

extern "C" {

// Инициализация — вызывается один раз при загрузке мода
JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeInit(
        JNIEnv*, jclass) {
    if (g_initialized) return JNI_TRUE;

    LOGI("Initializing FreeCam mod...");
    GlossInit(true);

    if (!hookCameraAPI()) {
        LOGE("Failed to hook CameraAPI");
        return JNI_FALSE;
    }

    g_initialized = true;
    LOGI("FreeCam mod initialized successfully");
    return JNI_TRUE;
}

// Включить FreeCam: запоминаем текущую позицию и углы камеры как стартовую точку
JNIEXPORT void JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeActivate(
        JNIEnv*, jclass,
        jfloat playerX, jfloat playerY, jfloat playerZ,
        jfloat yaw, jfloat pitch) {
    if (!g_initialized) return;

    g_camX     = playerX;
    g_camY     = playerY + 1.62f; // смещение на уровень глаз
    g_camZ     = playerZ;
    g_camYaw   = yaw;
    g_camPitch = pitch;
    g_freecamActive = true;
    LOGI("FreeCam activated at (%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f",
         g_camX, g_camY, g_camZ, g_camYaw, g_camPitch);
}

// Выключить FreeCam — камера вернётся к игроку
JNIEXPORT void JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeDeactivate(
        JNIEnv*, jclass) {
    g_freecamActive = false;
    LOGI("FreeCam deactivated");
}

// Движение камеры — вызывается Java-стороной по нажатию виртуального джойстика.
// dx/dz: горизонталь (−1..1), dy: вертикаль (−1..1)
JNIEXPORT void JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeMoveCamera(
        JNIEnv*, jclass,
        jfloat dx, jfloat dy, jfloat dz) {
    if (!g_freecamActive) return;

    // Переводим yaw в радианы и двигаемся относительно направления взгляда
    float yawRad = g_camYaw * (float)M_PI / 180.f;
    float sinYaw = sinf(yawRad);
    float cosYaw = cosf(yawRad);

    // forward (dz < 0 = вперёд в MC-координатах)
    g_camX += (-dz * sinYaw + dx * cosYaw) * g_speed;
    g_camZ += ( dz * cosYaw + dx * sinYaw) * g_speed;
    g_camY += dy * g_speed;
}

// Вращение камеры — вызывается при свайпе по экрану
JNIEXPORT void JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeRotateCamera(
        JNIEnv*, jclass,
        jfloat deltaYaw, jfloat deltaPitch) {
    if (!g_freecamActive) return;

    g_camYaw   += deltaYaw;
    g_camPitch += deltaPitch;

    // Зажимаем pitch чтобы не перевернуться
    if (g_camPitch >  90.f) g_camPitch =  90.f;
    if (g_camPitch < -90.f) g_camPitch = -90.f;
}

// Настройка скорости полёта
JNIEXPORT void JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeSetSpeed(
        JNIEnv*, jclass, jfloat speed) {
    g_speed = speed;
}

// Геттеры для Java-стороны (чтобы отображать координаты камеры в UI)
JNIEXPORT jfloat JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeGetCamX(
        JNIEnv*, jclass) { return g_camX; }

JNIEXPORT jfloat JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeGetCamY(
        JNIEnv*, jclass) { return g_camY; }

JNIEXPORT jfloat JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeGetCamZ(
        JNIEnv*, jclass) { return g_camZ; }

JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeIsActive(
        JNIEnv*, jclass) {
    return g_freecamActive ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_mods_inbuilt_nativemod_FreeCamMod_nativeIsInitialized(
        JNIEnv*, jclass) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"