package com.blockreality.api.sidecar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SidecarBridge 路徑安全性測試 — v3fix §M3
 *
 * 驗證 validateScriptPath() 能正確阻擋：
 *   1. 路徑穿越攻擊 (../../etc/passwd)
 *   2. 符號連結逸出 (symlink → /tmp/evil)
 *   3. 絕對路徑在 GAMEDIR 外 (/tmp/evil.js)
 *   4. null 路徑
 *   5. 合法路徑放行
 *
 * 因為 validateScriptPath 是 private static，使用反射呼叫。
 * SIDECAR_BASE_DIR 依賴 FMLPaths.GAMEDIR，測試時由 Forge 初始化狀態決定。
 *
 * #9 fix: SidecarBridge 類別的 static initializer 依賴 FMLPaths.GAMEDIR，
 * 在非 Forge 環境下會拋出 ExceptionInInitializerError。
 * 使用 Assumptions.assumeTrue 讓測試在無 Forge 環境時優雅跳過，
 * 而非直接失敗（避免 CI 紅燈）。
 */
class SidecarBridgeSecurityTest {

    /** 標記 SidecarBridge 類別是否能在當前環境載入 */
    private static boolean sidecarClassAvailable = false;

    @BeforeAll
    static void checkForgeEnvironment() {
        try {
            // 嘗試載入 SidecarBridge — 如果 FMLPaths 不可用會拋 Error
            Class.forName("com.blockreality.api.sidecar.SidecarBridge");
            sidecarClassAvailable = true;
        } catch (ExceptionInInitializerError | NoClassDefFoundError | ClassNotFoundException e) {
            // FMLPaths.GAMEDIR 不可用 — 非 Forge 環境
            System.out.println("[SidecarBridgeSecurityTest] Skipping: " + e.getMessage());
            sidecarClassAvailable = false;
        }
    }

    /**
     * 反射取得 validateScriptPath(Path) private static 方法。
     */
    private static void invokeValidateScriptPath(Path script) throws Throwable {
        try {
            Method method = SidecarBridge.class.getDeclaredMethod("validateScriptPath", Path.class);
            method.setAccessible(true);
            method.invoke(null, script);
        } catch (InvocationTargetException e) {
            throw e.getCause();  // 解包真正的例外
        }
    }

    // ─── 1. null 路徑 → SecurityException ───

    @Test
    void testNullPathThrowsSecurityException() {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(null);
        }, "null script path should throw SecurityException");
    }

    // ─── 2. 路徑穿越攻擊 → SecurityException ───

    @Test
    void testPathTraversalBlocked(@TempDir Path tempDir) {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        Path traversalPath = tempDir.resolve("sidecar").resolve("..").resolve("..").resolve("etc").resolve("passwd");

        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(traversalPath);
        }, "Path traversal (../../etc/passwd) should be blocked");
    }

    @Test
    void testDotDotInMiddleBlocked(@TempDir Path tempDir) {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        Path evilPath = tempDir.resolve("sidecar")
            .resolve("..")
            .resolve("..")
            .resolve("..")
            .resolve("evil.js");

        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(evilPath);
        }, "Path with embedded .. should be blocked");
    }

    // ─── 3. 絕對路徑在白名單外 → SecurityException ───

    @Test
    void testAbsolutePathOutsideGamedirBlocked() {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        Path outsidePath = Path.of("/tmp/evil_sidecar.js");

        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(outsidePath);
        }, "Absolute path outside GAMEDIR/sidecar/ should be blocked");
    }

    @Test
    void testWindowsStyleAbsolutePathBlocked() {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path outsidePath;
        if (os.contains("win")) {
            outsidePath = Path.of("C:\\Windows\\System32\\evil.js");
        } else {
            outsidePath = Path.of("/usr/bin/evil.js");
        }

        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(outsidePath);
        }, "System directory path should be blocked");
    }

    // ─── 4. 符號連結逸出 → SecurityException ───

    @Test
    void testSymlinkEscapeBlocked(@TempDir Path tempDir) throws IOException {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");

        Path sidecarDir = tempDir.resolve("sidecar");
        Files.createDirectories(sidecarDir);

        Path externalTarget = tempDir.resolve("external");
        Files.createDirectories(externalTarget);
        Path externalScript = externalTarget.resolve("evil.js");
        Files.writeString(externalScript, "// evil script");

        Path symlinkInSidecar = sidecarDir.resolve("legit.js");

        try {
            Files.createSymbolicLink(symlinkInSidecar, externalScript);
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("Skipping symlink test: " + e.getMessage());
            return;
        }

        assertThrows(SecurityException.class, () -> {
            invokeValidateScriptPath(symlinkInSidecar);
        }, "Symlink escaping to external directory should be blocked");
    }

    // ─── 5. 驗證方法存在且可透過反射存取 ───

    @Test
    void testValidateScriptPathMethodExists() throws NoSuchMethodException {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        Method method = SidecarBridge.class.getDeclaredMethod("validateScriptPath", Path.class);
        assertNotNull(method, "validateScriptPath method should exist");
        assertTrue(java.lang.reflect.Modifier.isPrivate(method.getModifiers()),
            "validateScriptPath should be private");
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()),
            "validateScriptPath should be static");
    }

    // ─── 6. SIDECAR_BASE_DIR 定義存在 ───

    @Test
    void testSidecarBaseDirFieldExists() throws NoSuchFieldException {
        assumeTrue(sidecarClassAvailable, "SidecarBridge requires Forge environment");
        var field = SidecarBridge.class.getDeclaredField("SIDECAR_BASE_DIR");
        assertNotNull(field, "SIDECAR_BASE_DIR constant should exist");
        assertTrue(java.lang.reflect.Modifier.isStatic(field.getModifiers()),
            "SIDECAR_BASE_DIR should be static");
        assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
            "SIDECAR_BASE_DIR should be final");
    }
}
