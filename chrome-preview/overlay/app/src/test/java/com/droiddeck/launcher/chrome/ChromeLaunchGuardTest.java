package com.droiddeck.launcher.chrome;

import static org.junit.Assert.*;
import android.content.Context;
import com.droiddeck.launcher.session.SessionPhase;
import com.droiddeck.launcher.session.SessionState;
import java.io.File;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public class ChromeLaunchGuardTest {
    private Context context;
    private File status;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        status = new File(ChromePreviewRuntime.INSTANCE.stateDir(context), "status.json");
        Files.write(status.toPath(), "{\"phase\":\"stopped\"}".getBytes());
        SessionState.INSTANCE.setRunning(false);
        SessionState.INSTANCE.setPhase(SessionPhase.IDLE);
    }

    @After public void resetSession() {
        SessionState.INSTANCE.setRunning(false);
        SessionState.INSTANCE.setPhase(SessionPhase.IDLE);
    }

    @Test public void idleRuntimeCanStartPreview() {
        ChromePreviewRuntime.INSTANCE.checkStartAllowed(context);
    }
    @Test public void suspendedDesktopBlocksAnotherRuntime() {
        SessionState.INSTANCE.setRunning(true);
        SessionState.INSTANCE.setPhase(SessionPhase.SUSPENDED);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ChromePreviewRuntime.INSTANCE.checkStartAllowed(context));
        assertEquals("OTHER_SESSION_ACTIVE", error.getMessage());
    }
    @Test public void startingDesktopBlocksPreviewBeforeRunningFlag() {
        SessionState.INSTANCE.setPhase(SessionPhase.PREPARING);
        assertThrows(IllegalStateException.class, () -> ChromePreviewRuntime.INSTANCE.checkStartAllowed(context));
    }
    @Test public void stoppingPreviewCannotBeRestartedYet() throws Exception {
        Files.write(status.toPath(), "{\"phase\":\"stopping\"}".getBytes());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ChromePreviewRuntime.INSTANCE.checkStartAllowed(context));
        assertEquals("PREVIEW_STOPPING", error.getMessage());
    }
    @Test public void stoppedFailedDesktopDoesNotPreventRecovery() {
        SessionState.INSTANCE.setPhase(SessionPhase.FAILED);
        ChromePreviewRuntime.INSTANCE.checkStartAllowed(context);
    }
}
