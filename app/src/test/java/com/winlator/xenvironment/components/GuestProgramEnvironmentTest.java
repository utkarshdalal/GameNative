package com.winlator.xenvironment.components;

import static org.junit.Assert.*;

import app.gamenative.utils.ModDllOverrideLauncher;
import com.winlator.container.Container;
import com.winlator.core.envvars.EnvVars;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GuestProgramEnvironmentTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder(new File("."));

    private GuestProgramLauncherComponent[] launchers() {
        return new GuestProgramLauncherComponent[] {
            new GuestProgramLauncherComponent(),
            new GlibcProgramLauncherComponent(null, null),
            new BionicProgramLauncherComponent(null, null)
        };
    }

    @Test public void resolvesModsAfterSetupWithoutChangingShellEnvironment() throws Exception {
        for (GuestProgramLauncherComponent launcher : launchers()) {
            File game = temp.newFolder();
            assertTrue(new File(game, "game.exe").createNewFile());
            Container container = new Container("CUSTOM_GAME_1");
            container.setRootDir(temp.newFolder());
            // The drive parser uses ':', so use a relative host path on Windows test hosts.
            String gamePath = new File(".").getCanonicalFile().toPath().relativize(game.getCanonicalFile().toPath()).toString();
            container.setDrives("A:" + gamePath);
            container.setExecutablePath("game.exe");
            launcher.setEnvVars(new EnvVars("WINEDLLOVERRIDES=icu=n"));
            launcher.setGuestEnvironmentCallback(env -> ModDllOverrideLauncher.INSTANCE.apply(container, env, true));

            // Preparation runs after the callback is registered and can install the proxy DLL.
            assertEquals("icu=n", launcher.getEnvVars().get("WINEDLLOVERRIDES"));
            File dll = new File(game, "winhttp.dll");
            assertTrue(dll.createNewFile());
            assertTrue(new File(game, "doorstop_config.ini").createNewFile());
            assertEquals("icu=n;winhttp=n,b", launcher.getGuestEnvironment().get("WINEDLLOVERRIDES"));
            assertEquals("icu=n", launcher.getEnvVars().get("WINEDLLOVERRIDES"));

            assertTrue(dll.delete());
            assertEquals("icu=n", launcher.getGuestEnvironment().get("WINEDLLOVERRIDES"));
        }
    }

    @Test public void queuedPrerequisitesUseBaseEnvironmentUntilGameCallbackIsSet() {
        for (GuestProgramLauncherComponent launcher : launchers()) {
            launcher.setEnvVars(new EnvVars("WINEDLLOVERRIDES=icu=n"));
            EnvVars prerequisite = launcher.getGuestEnvironment();
            assertEquals("icu=n", prerequisite.get("WINEDLLOVERRIDES"));
            launcher.setGuestEnvironmentCallback(env -> env.put("WINEDLLOVERRIDES", "icu=n;dinput8=n,b"));
            assertEquals("icu=n;dinput8=n,b", launcher.getGuestEnvironment().get("WINEDLLOVERRIDES"));
            assertEquals("icu=n", prerequisite.get("WINEDLLOVERRIDES"));
            assertEquals("icu=n", launcher.getEnvVars().get("WINEDLLOVERRIDES"));
        }
    }
}
